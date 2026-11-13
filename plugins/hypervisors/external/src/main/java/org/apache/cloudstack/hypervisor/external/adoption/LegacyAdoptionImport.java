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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Offline handoff primitive, NOT an exposed endpoint. Any future endpoint MUST
 * enforce root-admin authentication and require an operator-controlled common
 * fence that has stopped every legacy writer/worker/callback before invocation.
 * Dry-run is advisory only, not a cutover lock. Never log the export payload.
 *
 * All claims are imported migration-fenced. The original entire export, including
 * unknown executions, jobs, authority, leases and inconsistencies, is archived
 * as the exact input JSON text. Admission stays globally fenced until the
 * guarded handover verifies every retained managed claim and all other rows.
 */
public final class LegacyAdoptionImport {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String[] ARRAYS = {"adoption_claims", "adoption_operation_leases", "adoption_executions", "adoption_write_authority"};
    private static final String[] CLAIM_COLUMNS = {"id", "proxmox_cluster", "proxmox_node", "proxmox_vmid", "manifest_sha256", "manifest_json", "generation", "state", "operation_lease_id", "cloudstack_vm_ref", "cloudstack_instance_name", "created_at", "updated_at"};

    public static final class Report {
        public final int claims, leases, executions, authorities;
        public final boolean dryRun;
        Report(JsonNode root, boolean dryRun) {
            claims = root.get(ARRAYS[0]).size(); leases = root.get(ARRAYS[1]).size();
            executions = root.get(ARRAYS[2]).size(); authorities = root.get(ARRAYS[3]).size(); this.dryRun = dryRun;
        }
    }

    /** A single consistent export is imported atomically into an empty target.
     * The caller must separately ensure the legacy writer fence is held. */
    public Report importExport(String exportJson, boolean dryRun) {
        final JsonNode root = parse(exportJson);
        return AdoptionRegistry.transaction(c -> {
            AdoptionRegistry.lockControl(c);
            requireEmpty(c);
            if (!dryRun) {
                for (JsonNode claim : root.get(ARRAYS[0])) { insertClaim(c, claim); }
                for (JsonNode row : root.get(ARRAYS[1])) { insertTyped(c, row, "external_adoption_operation_leases", LEASE_COLUMNS); }
                for (JsonNode row : root.get(ARRAYS[2])) { insertTyped(c, row, "external_adoption_executions", EXECUTION_COLUMNS); }
                for (JsonNode row : root.get(ARRAYS[3])) { insertTyped(c, row, "external_adoption_write_authority", AUTHORITY_COLUMNS); }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO external_adoption_legacy_export (id,export_json,imported_at) VALUES (1,?,SYSDATE(6))")) {
                    s.setString(1, exportJson);
                    if (s.executeUpdate() != 1) { throw new AdoptionRegistry.Conflict("legacy export archive failed"); }
                }
                try (PreparedStatement s = c.prepareStatement("UPDATE external_adoption_control SET legacy_fenced=TRUE WHERE id=1 AND legacy_fenced=FALSE")) {
                    if (s.executeUpdate() != 1) { throw new AdoptionRegistry.Conflict("legacy import lost global fence"); }
                }
            }
            return new Report(root, dryRun);
        });
    }

    private static void requireEmpty(Connection c) throws SQLException {
        for (String table : new String[] {"external_adoption_claims", "external_adoption_operation_leases", "external_adoption_executions", "external_adoption_write_authority", "external_adoption_legacy_export"}) {
            try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM " + table + " LIMIT 1"); ResultSet r = s.executeQuery()) {
                if (r.next()) { throw new AdoptionRegistry.Conflict("target registry is not empty"); }
            }
        }
    }

    static JsonNode parse(String input) {
        try {
            JsonNode root = JSON.readTree(input);
            if (root == null || !root.isObject() || root.size() != ARRAYS.length) { throw new IllegalArgumentException("invalid export envelope"); }
            for (String name : ARRAYS) {
                if (!root.has(name) || !root.get(name).isArray()) { throw new IllegalArgumentException("missing export array: " + name); }
                for (JsonNode row : root.get(name)) { if (!row.isObject()) { throw new IllegalArgumentException("export row must be an object"); } }
            }
            Set<String> ids = new HashSet<>(), keys = new HashSet<>(), refs = new HashSet<>();
            for (JsonNode claim : root.get(ARRAYS[0])) {
                for (String col : CLAIM_COLUMNS) { if (!claim.has(col)) { throw new IllegalArgumentException("missing claim column: " + col); } }
                String id = required(claim, "id"); AdoptionRegistry.canonicalUuid(id);
                String cluster = normalized(claim, "proxmox_cluster");
                String node = normalized(claim, "proxmox_node");
                int vmid = positiveInt(claim, "proxmox_vmid"); positiveInt(claim, "generation");
                required(claim, "state");
                String hash = required(claim, "manifest_sha256");
                String manifest = required(claim, "manifest_json");
                if (!hash.matches("[0-9a-f]{64}") || !hash.equals(sha256(manifest))) { throw new IllegalArgumentException("manifest bytes/hash mismatch"); }
                JsonNode doc = JSON.readTree(manifest);
                if (doc == null || !doc.isObject() || !doc.path("placement").path("cluster").asText().equals(cluster) ||
                        !doc.path("placement").path("node").asText().equals(node) || !doc.path("vmid").isIntegralNumber() || doc.path("vmid").asLong() != vmid) {
                    throw new IllegalArgumentException("manifest/claim placement mismatch");
                }
                if (!ids.add(id) || !keys.add(cluster + "\u0000" + vmid)) { throw new IllegalArgumentException("duplicate claim identity"); }
                String ref = optional(claim, "cloudstack_vm_ref");
                if (ref != null && !refs.add(ref)) { throw new IllegalArgumentException("duplicate CloudStack VM reference"); }
                optional(claim, "cloudstack_instance_name"); optional(claim, "operation_lease_id");
                timestamp(claim, "created_at"); timestamp(claim, "updated_at");
            }
            for (JsonNode row : root.get(ARRAYS[1])) { validateTyped(row, LEASE_COLUMNS, false); }
            for (JsonNode row : root.get(ARRAYS[2])) { validateTyped(row, EXECUTION_COLUMNS, false); }
            for (JsonNode row : root.get(ARRAYS[3])) { validateTyped(row, AUTHORITY_COLUMNS, true); }
            return root;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("malformed legacy export", e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) { result.append(String.format("%02x", b & 255)); }
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String required(JsonNode row, String name) {
        JsonNode n = row.get(name);
        if (n == null || !n.isTextual() || n.textValue().isEmpty()) { throw new IllegalArgumentException("invalid " + name); }
        return n.textValue();
    }
    private static String normalized(JsonNode row, String name) {
        String value = required(row, name);
        if (!value.equals(value.trim())) { throw new IllegalArgumentException("invalid " + name); }
        return value;
    }
    private static String optional(JsonNode row, String name) {
        JsonNode n = row.get(name);
        if (n == null || n.isNull()) { return null; }
        if (!n.isTextual()) { throw new IllegalArgumentException("invalid " + name); }
        return n.textValue();
    }
    private static int positiveInt(JsonNode row, String name) {
        JsonNode n = row.get(name);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() <= 0) { throw new IllegalArgumentException("invalid " + name); }
        return n.intValue();
    }
    private static java.util.Calendar utc() { return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")); }

    private static Timestamp timestamp(JsonNode row, String name) {
        String value = optional(row, name);
        if (value == null) { return null; }
        try {
            String iso = value.replace(' ', 'T');
            try { return Timestamp.from(OffsetDateTime.parse(iso).toInstant()); }
            catch (DateTimeParseException ignored) { return Timestamp.from(LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC)); }
        } catch (DateTimeParseException e) { throw new IllegalArgumentException("invalid UTC timestamp " + name, e); }
    }

    private static final String[] LEASE_COLUMNS = {"id","claim_id","generation","action","expires_at","created_at"};
    private static final String[] EXECUTION_COLUMNS = {"id","claim_id","generation","plan_sha256","plan_json","state","deploy_job_id","start_job_id","cleanup_job_id","cloudstack_vm_ref","cloudstack_instance_name","error_code","worker_lease_id","worker_lease_expires_at","attempt_count","created_at","updated_at"};
    private static final String[] AUTHORITY_COLUMNS = {"id","mode","owner_id","target","expires_at","updated_at"};

    private static void validateTyped(JsonNode row, String[] columns, boolean authority) {
        for (String col : columns) {
            if (!row.has(col)) { throw new IllegalArgumentException("missing typed legacy field: " + col); }
            JsonNode v=row.get(col);
            boolean required=col.equals("id") || (!authority && (col.equals("claim_id") || col.equals("generation") || col.equals("state") || col.equals("action") || col.equals("expires_at") || col.equals("plan_sha256") || col.equals("plan_json") || col.equals("attempt_count")));
            if (v.isNull()) { if (required) { throw new IllegalArgumentException("null required typed field: " + col); } continue; }
            if (col.endsWith("_at")) { timestamp(row,col); }
            else if (col.equals("generation") || col.equals("attempt_count") || (col.equals("id") && authority)) {
                if (!v.isIntegralNumber() || !v.canConvertToInt() || (col.equals("generation") && v.intValue()<=0) || (col.equals("attempt_count") && v.intValue()<0)) { throw new IllegalArgumentException("invalid typed field: " + col); }
            } else if (!v.isTextual() || (required && v.textValue().isEmpty())) { throw new IllegalArgumentException("invalid typed field: " + col); }
        }
    }

    private static void insertTyped(Connection c, JsonNode row, String table, String[] columns) throws SQLException {
        StringBuilder names = new StringBuilder(), values = new StringBuilder();
        for (String column : columns) { names.append(column).append(','); values.append("?,"); }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + table + " (" + names + "legacy_json) VALUES (" + values + "?)")) {
            int n=1;
            for (String col : columns) {
                if (!row.has(col)) { throw new IllegalArgumentException("missing typed legacy field: " + col); }
                JsonNode v=row.get(col);
                if (v.isNull()) { s.setObject(n++,null); }
                else if (col.endsWith("_at")) { s.setTimestamp(n++,timestamp(row,col),utc()); }
                else if (col.equals("generation") || col.equals("attempt_count") || (col.equals("id") && table.endsWith("authority"))) {
                    if (!v.isIntegralNumber() || !v.canConvertToInt()) { throw new IllegalArgumentException("invalid typed field: " + col); }
                    s.setInt(n++,v.intValue());
                } else {
                    if (!v.isTextual()) { throw new IllegalArgumentException("invalid typed field: " + col); }
                    s.setString(n++,v.textValue());
                }
            }
            s.setString(n,row.toString());
            if (s.executeUpdate()!=1) { throw new AdoptionRegistry.Conflict("legacy typed insert failed"); }
        }
    }

    /** Atomic whole-snapshot handover. The caller must keep every legacy writer stopped. */
    public void handover(AdoptionRegistry.VmVerifier verifier) {
        if (verifier == null) { throw new IllegalArgumentException("verifier required"); }
        AdoptionRegistry.transaction(c -> {
            try (PreparedStatement s=c.prepareStatement("SELECT legacy_fenced FROM external_adoption_control WHERE id=1 FOR UPDATE"); ResultSet r=s.executeQuery()) {
                if (!r.next() || !r.getBoolean(1)) { throw new AdoptionRegistry.Conflict("legacy fence is not held"); }
            }
            JsonNode archive;
            try (PreparedStatement s=c.prepareStatement("SELECT export_json FROM external_adoption_legacy_export WHERE id=1 FOR UPDATE"); ResultSet r=s.executeQuery()) {
                if (!r.next()) { throw new AdoptionRegistry.Conflict("legacy archive missing"); }
                archive=parse(r.getString(1));
            }
            verifyArchivedClaims(c,archive.get("adoption_claims"));
            if (archive.get("adoption_operation_leases").size()!=0) { throw new AdoptionRegistry.Conflict("archived lease requires explicit recovery"); }
            rejectRows(c,"SELECT 1 FROM external_adoption_operation_leases LIMIT 1 FOR UPDATE");
            rejectRows(c,"SELECT 1 FROM external_adoption_write_authority WHERE id<>1 OR mode IS NOT NULL OR owner_id IS NOT NULL OR target IS NOT NULL OR expires_at IS NOT NULL LIMIT 1 FOR UPDATE");
            rejectRows(c,"SELECT 1 FROM external_adoption_executions e LEFT JOIN external_adoption_claims c ON c.id=e.claim_id AND c.generation=e.generation WHERE c.id IS NULL OR BINARY c.state<>BINARY 'managed' OR BINARY e.state<>BINARY 'succeeded' OR e.worker_lease_id IS NOT NULL OR e.worker_lease_expires_at IS NOT NULL OR e.error_code IS NOT NULL OR e.cloudstack_vm_ref IS NULL OR e.cloudstack_instance_name IS NULL OR BINARY e.id<>BINARY e.cloudstack_vm_ref OR BINARY e.cloudstack_vm_ref<>BINARY c.cloudstack_vm_ref OR BINARY e.cloudstack_instance_name<>BINARY c.cloudstack_instance_name LIMIT 1 FOR UPDATE");
            checkKnownColumns(c,"external_adoption_executions",EXECUTION_COLUMNS,archive.get("adoption_executions"));
            checkKnownColumns(c,"external_adoption_write_authority",AUTHORITY_COLUMNS,archive.get("adoption_write_authority"));
            try (PreparedStatement s=c.prepareStatement("SELECT plan_json,plan_sha256 FROM external_adoption_executions FOR UPDATE"); ResultSet r=s.executeQuery()) {
                while (r.next()) {
                    String plan=r.getString(1), hash=r.getString(2);
                    if (plan==null || hash==null || !hash.equals(sha256(plan))) { throw new AdoptionRegistry.Conflict("execution plan hash mismatch"); }
                    try { if (!JSON.readTree(plan).isObject()) { throw new AdoptionRegistry.Conflict("invalid execution plan"); } }
                    catch (java.io.IOException | NullPointerException e) { throw new AdoptionRegistry.Conflict("invalid execution plan",e); }
                }
            }
            try (PreparedStatement s=c.prepareStatement("SELECT id,generation,proxmox_cluster,proxmox_vmid,manifest_sha256,cloudstack_vm_ref,cloudstack_instance_name,state,operation_lease_id,migration_fenced,proxmox_node,manifest_json FROM external_adoption_claims FOR UPDATE"); ResultSet r=s.executeQuery()) {
                while (r.next()) {
                    String state=r.getString(8);
                    if (!r.getBoolean(10) || r.getString(9)!=null || !("managed".equals(state) || "released".equals(state))) { throw new AdoptionRegistry.Conflict("legacy claim is not quiescent"); }
                    String id=r.getString(1), cluster=r.getString(3), hash=r.getString(5), node=r.getString(11), json=r.getString(12);
                    int generation=r.getInt(2), vmid=r.getInt(4);
                    AdoptionRegistry.canonicalUuid(id);
                    if (generation<=0) { throw new AdoptionRegistry.Conflict("invalid retained generation"); }
                    AdoptionRegistry.manifest(json,hash,cluster,node,vmid);
                    if ("managed".equals(state)) {
                        AdoptionRegistry.Identity identity=new AdoptionRegistry.Identity(id,generation,cluster,vmid,hash,r.getString(6),r.getString(7));
                        verifier.verify(identity,node,json);
                    }
                }
            }
            try (PreparedStatement s=c.prepareStatement("UPDATE external_adoption_claims SET migration_fenced=FALSE WHERE migration_fenced=TRUE")) { s.executeUpdate(); }
            try (PreparedStatement s=c.prepareStatement("UPDATE external_adoption_control SET legacy_fenced=FALSE WHERE id=1 AND legacy_fenced=TRUE")) {
                if (s.executeUpdate()!=1) { throw new AdoptionRegistry.Conflict("global handover fence changed"); }
            }
            return null;
        });
    }

    private static void rejectRows(Connection c, String sql) throws SQLException {
        try (PreparedStatement s=c.prepareStatement(sql); ResultSet r=s.executeQuery()) {
            if (r.next()) { throw new AdoptionRegistry.Conflict("legacy execution, lease or authority is not quiescent"); }
        }
    }

    private static void checkKnownColumns(Connection c, String table, String[] columns, JsonNode archivedRows) throws SQLException {
        Set<String> known=new HashSet<>(java.util.Arrays.asList(columns));
        Set<JsonNode> remaining=new HashSet<>();
        for (JsonNode row : archivedRows) { if (!remaining.add(row)) { throw new AdoptionRegistry.Conflict("duplicate archived row"); } }
        try (PreparedStatement s=c.prepareStatement("SELECT " + String.join(",",columns) + ",legacy_json FROM " + table + " FOR UPDATE"); ResultSet r=s.executeQuery()) {
            while (r.next()) {
                try {
                    JsonNode row=JSON.readTree(r.getString(columns.length+1));
                    if (!remaining.remove(row)) { throw new AdoptionRegistry.Conflict("legacy snapshot changed"); }
                    java.util.Iterator<String> names=row.fieldNames();
                    while (names.hasNext()) { if (!known.contains(names.next())) { throw new AdoptionRegistry.Conflict("unknown legacy field"); } }
                    for (String col : columns) { if (!row.has(col)) { throw new AdoptionRegistry.Conflict("missing legacy field"); } }
                    compareRow(r,row,columns,table.endsWith("authority"));
                } catch (java.io.IOException | NullPointerException e) { throw new AdoptionRegistry.Conflict("invalid legacy row",e); }
            }
        }
        if (!remaining.isEmpty()) { throw new AdoptionRegistry.Conflict("legacy row disappeared"); }
    }

    private static void verifyArchivedClaims(Connection c, JsonNode claims) throws SQLException {
        int count=0;
        try (PreparedStatement s=c.prepareStatement("SELECT " + String.join(",",CLAIM_COLUMNS) + " FROM external_adoption_claims WHERE id=? FOR UPDATE")) {
            for (JsonNode row : claims) {
                if (row.size()!=CLAIM_COLUMNS.length) { throw new AdoptionRegistry.Conflict("unknown legacy claim field"); }
                s.setString(1,required(row,"id"));
                try (ResultSet r=s.executeQuery()) {
                    if (!r.next()) { throw new AdoptionRegistry.Conflict("archived claim missing"); }
                    compareRow(r,row,CLAIM_COLUMNS,false); count++;
                }
            }
        }
        try (PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM external_adoption_claims"); ResultSet r=s.executeQuery()) {
            if (!r.next() || r.getInt(1)!=count) { throw new AdoptionRegistry.Conflict("claim snapshot changed"); }
        }
    }

    private static void compareRow(ResultSet r, JsonNode row, String[] columns, boolean authority) throws SQLException {
        for (int j=0; j<columns.length; j++) {
            String col=columns[j]; JsonNode value=row.get(col);
            Object expected, actual;
            if (value==null || value.isNull()) { expected=null; actual=r.getObject(j+1); }
            else if (col.endsWith("_at")) { expected=timestamp(row,col); actual=r.getTimestamp(j+1,utc()); }
            else if (col.equals("generation") || col.equals("proxmox_vmid") || col.equals("attempt_count") || (col.equals("id") && authority)) {
                expected=value.intValue(); int n=r.getInt(j+1); actual=r.wasNull() ? null : n;
            } else { expected=value.textValue(); actual=r.getString(j+1); }
            if (!java.util.Objects.equals(expected,actual)) { throw new AdoptionRegistry.Conflict("legacy typed identity changed: " + col); }
        }
    }

    private static void insertClaim(Connection c, JsonNode row) throws SQLException {
        String sql = "INSERT INTO external_adoption_claims (id,proxmox_cluster,proxmox_node,proxmox_vmid,manifest_sha256,manifest_json,generation,state,operation_lease_id,cloudstack_vm_ref,cloudstack_instance_name,created_at,updated_at,migration_fenced) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE)";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, required(row,"id")); s.setString(2, required(row,"proxmox_cluster")); s.setString(3, required(row,"proxmox_node"));
            s.setInt(4, positiveInt(row,"proxmox_vmid")); s.setString(5, required(row,"manifest_sha256")); s.setString(6, required(row,"manifest_json"));
            s.setInt(7, positiveInt(row,"generation")); s.setString(8, required(row,"state")); s.setString(9, optional(row,"operation_lease_id"));
            s.setString(10, optional(row,"cloudstack_vm_ref")); s.setString(11, optional(row,"cloudstack_instance_name"));
            s.setTimestamp(12, timestamp(row,"created_at"),utc()); s.setTimestamp(13, timestamp(row,"updated_at"),utc());
            if (s.executeUpdate() != 1) { throw new AdoptionRegistry.Conflict("claim import failed"); }
        }
    }
}
