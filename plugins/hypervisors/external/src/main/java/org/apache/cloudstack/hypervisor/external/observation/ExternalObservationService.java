// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.observation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Shared management database only. No external DB credentials or background process. */
public class ExternalObservationService extends ManagerBase implements PluggableService {
    private static final int MAX_BYTES = 60000;
    private static final int MAX_ROWS = 256;
    @Inject private ObservationBindingResolver bindingResolver;

    @Override public List<Class<?>> getCommands() { return List.of(SubmitExternalIpObservationsCmd.class); }


    static final class Row {
        final int vmid;
        final String mac;
        final String ip; // null means a multi-address conflict
        Row(int vmid, String mac, String ip) { this.vmid = vmid; this.mac = mac; this.ip = ip; }
    }

    private static String string(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("invalid string");
        }
        return e.getAsString();
    }

    static List<Row> parse(String payload) {
        if (payload == null || payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("invalid payload size");
        }
        JsonElement root = JsonParser.parseString(payload);
        if (!root.isJsonArray() || root.getAsJsonArray().size() > MAX_ROWS) {
            throw new IllegalArgumentException("invalid observations");
        }
        List<Row> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) { throw new IllegalArgumentException("invalid row"); }
            JsonObject obj = element.getAsJsonObject();
            if (obj.size() != 3 || !obj.has("vmid") || !obj.has("mac") || !obj.has("ips")) {
                throw new IllegalArgumentException("invalid row fields");
            }
            JsonElement id = obj.get("vmid");
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber() || !id.getAsString().matches("[1-9][0-9]{0,9}")) {
                throw new IllegalArgumentException("invalid VMID");
            }
            int vmid;
            try { vmid = Integer.parseInt(id.getAsString()); } catch (NumberFormatException e) { throw new IllegalArgumentException("invalid VMID"); }
            String mac = ObservationValidation.mac(string(obj.get("mac")));
            if (!keys.add(vmid + "/" + mac)) { throw new IllegalArgumentException("duplicate row"); }
            if (!obj.get("ips").isJsonArray()) { throw new IllegalArgumentException("invalid addresses"); }
            JsonArray ips = obj.getAsJsonArray("ips");
            if (ips.size() == 0 || ips.size() > 32) { throw new IllegalArgumentException("invalid addresses"); }
            Set<String> unique = new HashSet<>();
            for (JsonElement v : ips) {
                String ip = string(v);
                if (!ObservationValidation.usableIp(ip) || !unique.add(ip)) { throw new IllegalArgumentException("invalid address"); }
            }
            rows.add(new Row(vmid, mac, unique.size() == 1 ? unique.iterator().next() : null));
        }
        return rows;
    }

    /** Idempotent for the same capture UUID and host. Empty means no observation, not clear. */
    public void submit(long userId, long hostId, String captureId, String payload) throws SQLException {
        if (bindingResolver == null) { throw new IllegalStateException("Observation binding resolver unavailable"); }
        if (captureId == null || captureId.length() != 36) { throw new IllegalArgumentException("invalid capture ID"); }
        final UUID capture;
        try { capture = UUID.fromString(captureId); } catch (RuntimeException e) { throw new IllegalArgumentException("invalid capture ID"); }
        if (!capture.toString().equals(captureId)) { throw new IllegalArgumentException("invalid capture ID"); }
        List<Row> rows = parse(payload); // validate the complete batch before writing
        TransactionLegacy txn = TransactionLegacy.open("externalIpObservationSubmit");
        boolean started = false;
        try {
            Connection c = txn.getConnection();
            // Repeated native lookups must not reuse a REPEATABLE READ snapshot.
            // This affects only the next transaction, not the pooled session default.
            if (!c.getAutoCommit()) throw new SQLException("observation submit requires its own transaction");
            try (PreparedStatement ps = c.prepareStatement("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")) { ps.execute(); }
            txn.start();
            started = true;
            long clusterId;
            try (PreparedStatement ps = c.prepareStatement("SELECT s.cluster_id FROM external_observation_sender s JOIN host h ON h.id=s.host_id AND h.cluster_id=s.cluster_id WHERE s.user_id=? AND s.host_id=? AND h.removed IS NULL AND h.hypervisor_type='External' FOR UPDATE")) {
                ps.setLong(1, userId); ps.setLong(2, hostId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { throw new SecurityException("sender is not mapped to host and cluster"); }
                    clusterId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO external_observation_capture(host_id,capture_id,received_at) VALUES(?,?,SYSDATE(6))")) {
                ps.setLong(1, hostId); ps.setString(2, captureId);
                try { ps.executeUpdate(); } catch (SQLException e) {
                    if (e.getErrorCode() == 1062) { txn.rollback(); return; }
                    throw e;
                }
            }
            for (Row row : rows) {
                ObservationBindingResolver.Binding b = bindingResolver.source(clusterId, hostId, row.vmid, row.mac);
                if (b == null) continue;
                // Upsert locks the key even if two management servers see it for the first time.
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO external_ip_observation(cluster_id,vmid,mac,host_id,ip,sightings,seen_at,binding_fingerprint) VALUES(?,?,?,?,NULL,0,SYSDATE(6),?) ON DUPLICATE KEY UPDATE vmid=vmid")) {
                    ps.setLong(1, clusterId); ps.setInt(2, row.vmid); ps.setString(3, row.mac); ps.setLong(4, hostId); ps.setString(5, b.fingerprint); ps.executeUpdate();
                }
                int sightings = 1;
                try (PreparedStatement ps = c.prepareStatement("SELECT host_id,ip,sightings,seen_at >= DATE_SUB(SYSDATE(6), INTERVAL 24 HOUR),binding_fingerprint FROM external_ip_observation WHERE cluster_id=? AND vmid=? AND mac=? FOR UPDATE")) {
                    ps.setLong(1, clusterId); ps.setInt(2, row.vmid); ps.setString(3, row.mac);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { throw new SQLException("observation missing"); }
                        sightings = ObservationSightings.advance(row.ip, hostId, rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4) && b.fingerprint.equals(rs.getString(5)));
                    }
                }
                ObservationBindingResolver.Binding after = bindingResolver.source(clusterId, hostId, row.vmid, row.mac);
                if (after == null || !b.fingerprint.equals(after.fingerprint)) continue;
                try (PreparedStatement ps = c.prepareStatement("UPDATE external_ip_observation SET previous_seen_at=IF(? >= 2,seen_at,NULL),host_id=?,ip=?,sightings=?,seen_at=SYSDATE(6),binding_fingerprint=? WHERE cluster_id=? AND vmid=? AND mac=?")) {
                    ps.setInt(1, sightings); ps.setLong(2, hostId); ps.setString(3, row.ip); ps.setInt(4, sightings); ps.setString(5, b.fingerprint);
                    ps.setLong(6, clusterId); ps.setInt(7, row.vmid); ps.setString(8, row.mac); ps.executeUpdate();
                }
            }
            txn.commit();
        } catch (RuntimeException | SQLException e) {
            if (started) txn.rollback();
            throw e;
        } finally { txn.close(); }
    }

    /** Returns only a fallback candidate. Null is not an instruction to clear any IP.
     * The caller must revalidate its whole binding immediately before any write. */
    public static final class Candidate {
        public final String ip;
        public final ObservationBindingResolver.Binding binding;
        /** Monotonic deadline, conservatively bounded by observation expiry and lookup age. */
        public final long validUntilNanos;
        Candidate(String ip, ObservationBindingResolver.Binding binding, long validUntilNanos) {
            this.ip = ip; this.binding = binding; this.validUntilNanos = validUntilNanos;
        }
    }

    public String fallback(long cloudStackVmId, String exactMac, String agentIp) throws SQLException {
        Candidate candidate = fallbackCandidate(cloudStackVmId, exactMac, agentIp);
        return candidate == null ? null : candidate.ip;
    }

    public Candidate fallbackCandidate(long cloudStackVmId, String exactMac, String agentIp) throws SQLException {
        if (bindingResolver == null) { return null; }
        if (ObservationValidation.usableIp(agentIp)) { return null; }
        // Do not silently reuse a caller's REPEATABLE READ snapshot for either
        // native/provider revalidation. The guarded writer must recheck later.
        TransactionLegacy check = TransactionLegacy.open("externalObservationIsolationCheck");
        try { if (!check.getConnection().getAutoCommit()) return null; }
        finally { check.close(); }
        String mac = ObservationValidation.mac(exactMac);
        ObservationBindingResolver.Binding b = bindingResolver.current(cloudStackVmId, mac);
        if (b == null || b.vmid <= 0 || !mac.equals(ObservationValidation.mac(b.mac))) { return null; }
        String ip = null;
        long remainingMicros = 0;
        long startedNanos = System.nanoTime();
        TransactionLegacy txn = TransactionLegacy.open("externalIpObservationLookup");
        try {
            try (PreparedStatement ps = txn.getConnection().prepareStatement("SELECT ip,TIMESTAMPDIFF(MICROSECOND,SYSDATE(6),DATE_ADD(LEAST(previous_seen_at,seen_at), INTERVAL 24 HOUR)) FROM external_ip_observation WHERE cluster_id=? AND vmid=? AND mac=? AND host_id=? AND binding_fingerprint=? AND sightings>=2 AND previous_seen_at>=DATE_SUB(SYSDATE(6), INTERVAL 24 HOUR) AND seen_at>=DATE_SUB(SYSDATE(6), INTERVAL 24 HOUR)")) {
                ps.setLong(1, b.clusterId); ps.setInt(2, b.vmid); ps.setString(3, mac); ps.setLong(4, b.hostId); ps.setString(5, b.fingerprint);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { ip = rs.getString(1); remainingMicros = rs.getLong(2); } }
            }
        } finally { txn.close(); }
        if (!ObservationValidation.usableIp(ip) || remainingMicros <= 0) { return null; }
        long deadline = startedNanos + Math.min(remainingMicros, 30_000_000L) * 1000L;
        ObservationBindingResolver.Binding after = bindingResolver.current(cloudStackVmId, mac);
        if (after == null || !b.fingerprint.equals(after.fingerprint) || System.nanoTime() - deadline >= 0) { return null; }
        return new Candidate(ip, after, deadline);
    }
}
