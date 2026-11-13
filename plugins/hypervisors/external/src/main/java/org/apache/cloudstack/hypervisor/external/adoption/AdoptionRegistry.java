/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.hypervisor.external.adoption;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;

import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionLegacy;

/**
 * JDBC primitives for the management server, not an API or shell credential.
 * Each public call owns a CloudStack shared-DB transaction. The management
 * dispatcher must pass the same immutable identity to authorization and to
 * the actuator, keep the lease across every actuator/postcondition step, and
 * never call complete on an error, timeout, or uncertain outcome.
 *
 * No automatic expiry recovery exists: even an expired operation is fenced.
 * This is intentionally stricter than the legacy expired-lease takeover.
 */
public final class AdoptionRegistry {
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
            "console", "start", "stop", "reboot", "create_snapshot", "restore_snapshot", "delete_snapshot",
            "setha", "scale", "plugnic", "unplugnic"));
    private static final int LEASE_SECONDS = 7200;

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
        public Conflict(String message, Throwable cause) { super(message, cause); }
    }

    /** Non-secret immutable callback identity. Node is intentionally omitted:
     * a guest may move within its Proxmox cluster. Resolve/verify its current
     * node independently before using it as an actuator path. */
    public static final class Identity {
        public final String claimId, cluster, manifestHash, vmUuid, instanceName;
        public final int generation, vmid;
        public Identity(String claimId, int generation, String cluster, int vmid,
                        String manifestHash, String vmUuid, String instanceName) {
            this.claimId = canonicalUuid(claimId);
            this.generation = generation;
            this.cluster = canonicalCluster(cluster);
            this.vmid = vmid;
            this.manifestHash = text(manifestHash);
            this.vmUuid = canonicalUuid(vmUuid);
            this.instanceName = text(instanceName);
            if (generation <= 0 || vmid <= 0 || !manifestHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid claim identity");
            }
        }
    }

    /** Exact operation token; it is internal to the manager and not a bearer credential. */
    public static final class Lease {
        public final String id, action;
        public final java.sql.Timestamp expiresAt;
        private Lease(String id, String action, java.sql.Timestamp expiresAt) {
            this.id = id; this.action = action; this.expiresAt = expiresAt;
        }
    }

    @FunctionalInterface
    interface SqlWork<T> { T run(Connection c) throws SQLException; }

    static <T> T transaction(SqlWork<T> work) {
        return Transaction.execute((TransactionCallback<T>) status -> {
            try {
                return work.run(TransactionLegacy.currentTxn().getConnection());
            } catch (SQLException e) {
                throw new Conflict("adoption registry database failure", e);
            }
        });
    }

    /** Serializes import and lifecycle operations, including across management servers. */
    static void lockControl(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT legacy_fenced FROM external_adoption_control WHERE id=1 FOR UPDATE");
             ResultSet r = s.executeQuery()) {
            if (!r.next() || r.getBoolean(1)) {
                throw new Conflict("legacy migration or authority remains fenced");
            }
        }
    }

    private static String text(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("identity must be nonempty and normalized");
        }
        return value;
    }

    public static String canonicalCluster(String value) {
        text(value);
        if (!value.matches("[a-z0-9][a-z0-9._-]*") || value.length() > 255) {
            throw new IllegalArgumentException("canonical lowercase cluster required");
        }
        return value;
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static JsonNode manifest(String json, String hash, String cluster, String node, int vmid) {
        text(json); text(node); canonicalCluster(cluster);
        if (vmid <= 0 || hash == null || !hash.matches("[0-9a-f]{64}")) { throw new IllegalArgumentException("invalid manifest identity"); }
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (byte b : bytes) { h.append(String.format("%02x", b & 255)); }
            JsonNode m = JSON.readTree(json);
            if (!hash.equals(h.toString()) || m == null || !m.isObject() ||
                    !cluster.equals(m.path("placement").path("cluster").asText()) ||
                    !node.equals(m.path("placement").path("node").asText()) ||
                    !m.path("vmid").isIntegralNumber() || m.path("vmid").asLong() != vmid) {
                throw new IllegalArgumentException("manifest identity mismatch");
            }
            return m;
        } catch (java.io.IOException | NoSuchAlgorithmException e) { throw new IllegalArgumentException("invalid manifest", e); }
    }

    public static final class Reservation {
        public final String claimId, cluster, node, manifestHash, manifestJson;
        public final int generation, vmid;
        public Reservation(String id, int gen, String cluster, String node, int vmid, String hash, String json) {
            canonicalUuid(id);
            if (gen <= 0) { throw new IllegalArgumentException("invalid generation"); }
            manifest(json, hash, cluster, node, vmid);
            this.claimId = id; this.generation = gen; this.cluster = cluster; this.node = node;
            this.vmid = vmid; this.manifestHash = hash; this.manifestJson = json;
        }
    }

    /** The exact manifest bytes are frozen. One row per canonical cluster/VMID survives release. */
    public Reservation reserve(String cluster, String node, int vmid, String manifestJson, String hash) {
        manifest(manifestJson, hash, cluster, node, vmid);
        return transaction(c -> {
            lockControl(c);
            try (PreparedStatement s = c.prepareStatement("SELECT id,generation,state,proxmox_node,manifest_sha256,manifest_json,migration_fenced,operation_lease_id,cloudstack_vm_ref,cloudstack_instance_name FROM external_adoption_claims WHERE proxmox_cluster=? AND proxmox_vmid=? FOR UPDATE")) {
                s.setString(1, cluster); s.setInt(2, vmid);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        String id = r.getString(1); int generation = r.getInt(2); String state = r.getString(3);
                        if (r.getBoolean(7) || r.getString(8) != null) { throw new Conflict("claim fenced"); }
                        try (PreparedStatement leases=c.prepareStatement("SELECT 1 FROM external_adoption_operation_leases WHERE claim_id=? FOR UPDATE")) {
                            leases.setString(1,id); try (ResultSet lr=leases.executeQuery()) { if (lr.next()) { throw new Conflict("orphan or live lease"); } }
                        }
                        if ("reserved".equals(state) && node.equals(r.getString(4)) && hash.equals(r.getString(5)) && manifestJson.equals(r.getString(6))) {
                            if (r.getString(9)!=null || r.getString(10)!=null) { throw new Conflict("reserved claim has bound identity"); }
                            return new Reservation(id, generation, cluster, node, vmid, hash, manifestJson);
                        }
                        if (!"released".equals(state) || generation == Integer.MAX_VALUE) { throw new Conflict("cluster/VMID already claimed"); }
                        try (PreparedStatement executions=c.prepareStatement("SELECT 1 FROM external_adoption_executions WHERE claim_id=? AND (generation>? OR BINARY state<>BINARY 'succeeded' OR worker_lease_id IS NOT NULL OR worker_lease_expires_at IS NOT NULL OR error_code IS NOT NULL) LIMIT 1 FOR UPDATE")) {
                            executions.setString(1,id); executions.setInt(2,generation);
                            try (ResultSet er=executions.executeQuery()) { if (er.next()) { throw new Conflict("uncertain execution fences reservation"); } }
                        }
                        try (PreparedStatement u = c.prepareStatement("UPDATE external_adoption_claims SET generation=generation+1,state='reserved',proxmox_node=?,manifest_sha256=?,manifest_json=?,cloudstack_vm_ref=NULL,cloudstack_instance_name=NULL,updated_at=SYSDATE(6) WHERE id=? AND generation=? AND state='released' AND operation_lease_id IS NULL AND migration_fenced=FALSE")) {
                            u.setString(1,node); u.setString(2,hash); u.setString(3,manifestJson); u.setString(4,id); u.setInt(5,generation);
                            if (u.executeUpdate()!=1) { throw new Conflict("reservation lost tombstone fence"); }
                        }
                        return new Reservation(id, generation+1, cluster, node, vmid, hash, manifestJson);
                    }
                }
            }
            String id = UUID.randomUUID().toString();
            try (PreparedStatement s = c.prepareStatement("INSERT INTO external_adoption_claims (id,proxmox_cluster,proxmox_node,proxmox_vmid,manifest_sha256,manifest_json,generation,state,migration_fenced,created_at,updated_at) VALUES (?,?,?,?,?,?,1,'reserved',FALSE,SYSDATE(6),SYSDATE(6))")) {
                s.setString(1,id); s.setString(2,cluster); s.setString(3,node); s.setInt(4,vmid); s.setString(5,hash); s.setString(6,manifestJson); s.executeUpdate();
            }
            return new Reservation(id,1,cluster,node,vmid,hash,manifestJson);
        });
    }

    /** Bind the exact frozen reservation; same-identity retry only. */
    public void bind(Reservation reservation, String vmUuid, String instanceName) {
        canonicalUuid(reservation.claimId); canonicalUuid(vmUuid); text(instanceName);
        manifest(reservation.manifestJson, reservation.manifestHash, reservation.cluster, reservation.node, reservation.vmid);
        transaction(c -> {
            lockControl(c);
            try (PreparedStatement leases=c.prepareStatement("SELECT 1 FROM external_adoption_operation_leases WHERE claim_id=? FOR UPDATE")) {
                leases.setString(1,reservation.claimId);
                try (ResultSet r=leases.executeQuery()) { if (r.next()) { throw new Conflict("bind has an orphan or live lease"); } }
            }
            try (PreparedStatement s = c.prepareStatement("SELECT state,cloudstack_vm_ref,cloudstack_instance_name,migration_fenced,operation_lease_id FROM external_adoption_claims WHERE id=? AND generation=? AND proxmox_cluster=? AND proxmox_node=? AND proxmox_vmid=? AND manifest_sha256=? AND BINARY manifest_json=BINARY ? FOR UPDATE")) {
                s.setString(1,reservation.claimId); s.setInt(2,reservation.generation); s.setString(3,reservation.cluster); s.setString(4,reservation.node); s.setInt(5,reservation.vmid); s.setString(6,reservation.manifestHash); s.setString(7,reservation.manifestJson);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next() || r.getBoolean(4) || r.getString(5)!=null) { throw new Conflict("reservation mismatch or fenced"); }
                    if ("bound".equals(r.getString(1)) && vmUuid.equals(r.getString(2)) && instanceName.equals(r.getString(3))) { return null; }
                    if (!"reserved".equals(r.getString(1)) || r.getString(2)!=null || r.getString(3)!=null) { throw new Conflict("reservation not bindable"); }
                }
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE external_adoption_claims SET state='bound',cloudstack_vm_ref=?,cloudstack_instance_name=?,updated_at=SYSDATE(6) WHERE id=? AND generation=? AND state='reserved' AND cloudstack_vm_ref IS NULL AND cloudstack_instance_name IS NULL AND operation_lease_id IS NULL")) {
                s.setString(1,vmUuid); s.setString(2,instanceName); s.setString(3,reservation.claimId); s.setInt(4,reservation.generation);
                if (s.executeUpdate()!=1) { throw new Conflict("bind lost fence"); }
            }
            return null;
        });
    }

    /** Must perform an authoritative fresh VM read and compare all manifest/owner/placement fields. */
    @FunctionalInterface public interface VmVerifier { void verify(Identity identity, String node, String manifestJson); }

    public void activate(Identity i, VmVerifier verifier) {
        if (verifier == null) { throw new IllegalArgumentException("verifier required"); }
        transaction(c -> {
            lockControl(c); Claim claim = lockedClaim(c, i);
            if (!("bound".equals(claim.state) || "managed".equals(claim.state))) { throw new Conflict("claim not activatable"); }
            verifyClaim(c, i, verifier);
            if ("managed".equals(claim.state)) { return null; }
            try (PreparedStatement s = c.prepareStatement("UPDATE external_adoption_claims SET state='managed',updated_at=SYSDATE(6) WHERE id=? AND generation=? AND state='bound' AND operation_lease_id IS NULL AND migration_fenced=FALSE")) {
                s.setString(1,i.claimId); s.setInt(2,i.generation); if (s.executeUpdate()!=1) { throw new Conflict("activation lost fence"); }
            }
            return null;
        });
    }

    static void verifyClaim(Connection c, Identity i, VmVerifier verifier) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT proxmox_node,manifest_json FROM external_adoption_claims WHERE id=? AND generation=? FOR UPDATE")) {
            s.setString(1,i.claimId); s.setInt(2,i.generation);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) { throw new Conflict("claim disappeared"); }
                String node=r.getString(1), json=r.getString(2);
                manifest(json,i.manifestHash,i.cluster,node,i.vmid);
                verifier.verify(i,node,json);
            }
        }
    }

    public static final class StatusBinding {
        public final String instanceName, expectedProxmoxName, manifestHash, state;
        public StatusBinding(String instanceName, String expectedProxmoxName, String manifestHash, String state) {
            this.instanceName=instanceName; this.expectedProxmoxName=expectedProxmoxName;
            this.manifestHash=manifestHash; this.state=state;
        }
    }

    /** Fail the entire batch for any unknown or inconsistent active claim. */
    public Map<String, StatusBinding> statusBindings(String cluster) {
        canonicalCluster(cluster);
        return transaction(c -> {
            lockControl(c); Map<String,StatusBinding> result=new LinkedHashMap<>();
            try (PreparedStatement s=c.prepareStatement("SELECT id,generation,proxmox_vmid,manifest_sha256,cloudstack_vm_ref,cloudstack_instance_name,state,proxmox_node,manifest_json,migration_fenced,operation_lease_id FROM external_adoption_claims WHERE proxmox_cluster=? ORDER BY proxmox_vmid FOR UPDATE")) {
                s.setString(1,cluster); try (ResultSet r=s.executeQuery()) {
                    while (r.next()) {
                        String state=r.getString(7);
                        if (r.getBoolean(10)) { throw new Conflict("migration-fenced status claim"); }
                        if ("reserved".equals(state) || "released".equals(state)) {
                            if (r.getString(11)!=null || ("reserved".equals(state) && (r.getString(5)!=null || r.getString(6)!=null))) { throw new Conflict("inactive claim is inconsistent"); }
                            try (PreparedStatement l=c.prepareStatement("SELECT 1 FROM external_adoption_operation_leases WHERE claim_id=? FOR UPDATE")) {
                                l.setString(1,r.getString(1)); try (ResultSet lr=l.executeQuery()) { if (lr.next()) { throw new Conflict("inactive claim has a lease"); } }
                            }
                            continue;
                        }
                        if (!("bound".equals(state) || "managed".equals(state) || "operating".equals(state) || "retiring".equals(state))) { throw new Conflict("unknown claim state"); }
                        Identity i=new Identity(r.getString(1),r.getInt(2),cluster,r.getInt(3),r.getString(4),r.getString(5),r.getString(6));
                        lockedClaim(c,i);
                        JsonNode doc=manifest(r.getString(9),i.manifestHash,cluster,r.getString(8),i.vmid);
                        JsonNode name=doc.get("name");
                        if (name==null || !name.isTextual()) { throw new Conflict("missing manifest Proxmox name"); }
                        String expected=text(name.textValue());
                        result.put(Integer.toString(i.vmid),new StatusBinding(i.instanceName,expected,i.manifestHash,state));
                    }
                }
            }
            return result;
        });
    }

    public Map<String, String> statusMap(String cluster) {
        Map<String,String> result=new LinkedHashMap<>();
        statusBindings(cluster).forEach((key,value) -> result.put(key,value.instanceName));
        return result;
    }

    static String canonicalUuid(String value) {
        if (value == null) { throw new IllegalArgumentException("UUID required"); }
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) { throw new IllegalArgumentException("canonical UUID required"); }
        return value;
    }

    private static String action(String action) {
        if (!ACTIONS.contains(action)) { throw new IllegalArgumentException("unsupported managed action"); }
        return action;
    }

    private static final class Claim {
        final String state, lease;
        Claim(String state, String lease) { this.state = state; this.lease = lease; }
    }

    private Claim lockedClaim(Connection c, Identity i) throws SQLException {
        String sql = "SELECT state,operation_lease_id,migration_fenced FROM external_adoption_claims " +
                "WHERE BINARY id=BINARY ? AND generation=? AND BINARY proxmox_cluster=BINARY ? AND proxmox_vmid=? " +
                "AND BINARY manifest_sha256=BINARY ? AND BINARY cloudstack_vm_ref=BINARY ? AND BINARY cloudstack_instance_name=BINARY ? FOR UPDATE";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, i.claimId); s.setInt(2, i.generation); s.setString(3, i.cluster);
            s.setInt(4, i.vmid); s.setString(5, i.manifestHash); s.setString(6, i.vmUuid);
            s.setString(7, i.instanceName);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || r.getBoolean(3)) { throw new Conflict("claim missing, mismatched or migration-fenced"); }
                Claim claim = new Claim(r.getString(1), r.getString(2));
                if (!("bound".equals(claim.state) || "managed".equals(claim.state) ||
                        "operating".equals(claim.state) || "retiring".equals(claim.state) || "released".equals(claim.state))) {
                    throw new Conflict("claim state is not a lifecycle state");
                }
                if ("operating".equals(claim.state) != (claim.lease != null)) {
                    throw new Conflict("claim lease fence is inconsistent");
                }
                try (PreparedStatement leases = c.prepareStatement("SELECT id,generation FROM external_adoption_operation_leases WHERE claim_id=? FOR UPDATE")) {
                    leases.setString(1, i.claimId);
                    try (ResultSet lease = leases.executeQuery()) {
                        boolean found = lease.next();
                        if (found != (claim.lease != null) || (found && (!claim.lease.equals(lease.getString(1)) || lease.getInt(2) != i.generation))) {
                            throw new Conflict("claim and lease row disagree");
                        }
                    }
                }
                return claim;
            }
        }
    }

    /** State alone is not permission for a mutation: acquire a lease. */
    public String validatedState(Identity i) {
        return transaction(c -> {
            lockControl(c);
            Claim claim = lockedClaim(c, i);
            if ("released".equals(claim.state)) { throw new Conflict("released claim"); }
            if ("operating".equals(claim.state)) { requireLease(c, i, claim.lease, null, false); }
            return claim.state;
        });
    }

    /** Atomic managed->operating plus exact lease row; no automatic retry. */
    public Lease acquire(Identity i, String operation) {
        action(operation);
        return transaction(c -> {
            lockControl(c);
            Claim claim = lockedClaim(c, i);
            if (!"managed".equals(claim.state) || claim.lease != null) { throw new Conflict("claim is not available"); }
            String leaseId = UUID.randomUUID().toString();
            try (PreparedStatement s = c.prepareStatement("UPDATE external_adoption_claims SET state='operating', operation_lease_id=?, updated_at=SYSDATE(6) WHERE id=? AND generation=? AND state='managed' AND operation_lease_id IS NULL")) {
                s.setString(1, leaseId); s.setString(2, i.claimId); s.setInt(3, i.generation);
                if (s.executeUpdate() != 1) { throw new Conflict("acquire lost claim fence"); }
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO external_adoption_operation_leases (id,claim_id,generation,action,expires_at,created_at) VALUES (?,?,?,?,DATE_ADD(SYSDATE(6), INTERVAL " + LEASE_SECONDS + " SECOND),SYSDATE(6))")) {
                s.setString(1, leaseId); s.setString(2, i.claimId); s.setInt(3, i.generation); s.setString(4, operation);
                if (s.executeUpdate() != 1) { throw new Conflict("lease insert failed"); }
            }
            return new Lease(leaseId, operation, requireLease(c, i, leaseId, operation, true));
        });
    }

    private java.sql.Timestamp requireLease(Connection c, Identity i, String leaseId, String operation, boolean unexpired) throws SQLException {
        String sql = "SELECT action,expires_at FROM external_adoption_operation_leases WHERE id=? AND claim_id=? AND generation=?" +
                (unexpired ? " AND expires_at > SYSDATE(6)" : "") + " FOR UPDATE";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, leaseId); s.setString(2, i.claimId); s.setInt(3, i.generation);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || (operation != null && !operation.equals(r.getString(1)))) { throw new Conflict("lease missing, stale, expired or mismatched"); }
                return r.getTimestamp(2);
            }
        }
    }

    /** Recheck immediately before each actuator step, not just initial dispatch. */
    public void assertLease(Identity i, String operation, String leaseId) {
        action(operation); canonicalUuid(leaseId);
        transaction(c -> {
            lockControl(c); Claim claim = lockedClaim(c, i);
            if (!"operating".equals(claim.state) || !leaseId.equals(claim.lease)) { throw new Conflict("lease token is not current"); }
            requireLease(c, i, leaseId, operation, true); return null;
        });
    }

    /** Check dispatch budget against the database clock, after acquiring locks. */
    public void assertLeaseForDuration(Identity i, String operation, String leaseId, int seconds) {
        action(operation); canonicalUuid(leaseId);
        if (seconds <= 0 || seconds >= LEASE_SECONDS) { throw new IllegalArgumentException("invalid lease budget"); }
        transaction(c -> {
            lockControl(c); Claim claim = lockedClaim(c, i);
            if (!"operating".equals(claim.state) || !leaseId.equals(claim.lease)) { throw new Conflict("lease token is not current"); }
            requireLease(c, i, leaseId, operation, true);
            try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM external_adoption_operation_leases WHERE id=? AND claim_id=? AND generation=? AND action=? AND expires_at > DATE_ADD(SYSDATE(6), INTERVAL ? SECOND) FOR UPDATE")) {
                s.setString(1, leaseId); s.setString(2, i.claimId); s.setInt(3, i.generation); s.setString(4, operation); s.setInt(5, seconds);
                try (ResultSet r = s.executeQuery()) { if (!r.next()) { throw new Conflict("insufficient remaining lease budget"); } }
            }
            return null;
        });
    }

    /** Only after the entire actuator operation and required postcondition succeed. */
    public void complete(Identity i, String operation, String leaseId) {
        action(operation); canonicalUuid(leaseId);
        transaction(c -> {
            lockControl(c); Claim claim = lockedClaim(c, i);
            if (!"operating".equals(claim.state) || !leaseId.equals(claim.lease)) { throw new Conflict("completion token is not current"); }
            requireLease(c, i, leaseId, operation, true);
            try (PreparedStatement s = c.prepareStatement("UPDATE external_adoption_claims SET state='managed',operation_lease_id=NULL,updated_at=SYSDATE(6) WHERE id=? AND generation=? AND state='operating' AND operation_lease_id=? AND EXISTS (SELECT 1 FROM external_adoption_operation_leases l WHERE l.id=? AND l.claim_id=? AND l.generation=? AND l.action=? AND l.expires_at>SYSDATE(6))")) {
                s.setString(1, i.claimId); s.setInt(2, i.generation); s.setString(3, leaseId); s.setString(4, leaseId);
                s.setString(5, i.claimId); s.setInt(6, i.generation); s.setString(7, operation);
                if (s.executeUpdate() != 1) { throw new Conflict("completion lost its live fence"); }
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM external_adoption_operation_leases WHERE id=? AND claim_id=? AND generation=? AND action=?")) {
                s.setString(1, leaseId); s.setString(2, i.claimId); s.setInt(3, i.generation); s.setString(4, operation);
                if (s.executeUpdate() != 1) { throw new Conflict("completion lease disappeared"); }
            }
            return null;
        });
    }

    /** Retire is metadata-only; even expired operating claims remain fenced. */
    public void retire(Identity i) {
        transaction(c -> {
            lockControl(c); Claim claim = lockedClaim(c, i);
            if ("retiring".equals(claim.state)) { return null; }
            if (!("bound".equals(claim.state) || "managed".equals(claim.state))) { throw new Conflict("claim cannot retire"); }
            try (PreparedStatement s = c.prepareStatement("UPDATE external_adoption_claims SET state='retiring',updated_at=SYSDATE(6) WHERE id=? AND generation=? AND state=? AND operation_lease_id IS NULL")) {
                s.setString(1, i.claimId); s.setInt(2, i.generation); s.setString(3, claim.state);
                if (s.executeUpdate() != 1) { throw new Conflict("retirement lost its fence"); }
            }
            return null;
        });
    }

    /** The manager must implement an authoritative post-commit exact-UUID absence read;
     * do not use a callback response, a tenant-provided flag, or an outer transaction view. */
    @FunctionalInterface public interface PostCommitAbsence { boolean isAbsent(String exactVmUuid); }

    public void finalizeRetirement(Identity i, PostCommitAbsence absence) {
        if (absence == null || !absence.isAbsent(i.vmUuid)) { throw new Conflict("authoritative post-commit absence not established"); }
        transaction(c -> {
            lockControl(c); Claim claim = lockedClaim(c, i);
            if ("released".equals(claim.state)) { return null; }
            if (!"retiring".equals(claim.state)) { throw new Conflict("claim is not retiring"); }
            try (PreparedStatement s = c.prepareStatement("UPDATE external_adoption_claims SET state='released',updated_at=SYSDATE(6) WHERE id=? AND generation=? AND state='retiring' AND operation_lease_id IS NULL")) {
                s.setString(1, i.claimId); s.setInt(2, i.generation);
                if (s.executeUpdate() != 1) { throw new Conflict("release lost its fence"); }
            }
            return null;
        });
    }
}
