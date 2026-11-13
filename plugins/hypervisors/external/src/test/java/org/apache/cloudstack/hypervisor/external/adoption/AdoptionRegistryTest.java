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

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Focused parsing/identity tests. Shared-MySQL transaction races require a separate integration gate. */
public class AdoptionRegistryTest {
    private static final String UUID = "8ebc8968-d73a-4bb9-8ee5-49d04344a716";
    private static final String MANIFEST = "{\"placement\":{\"cluster\":\"lab\",\"node\":\"node1\"},\"vmid\":101}";

    private static String hash(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) { result.append(String.format("%02x", b & 255)); }
        return result.toString();
    }

    private static String claim() throws Exception {
        return "{\"id\":\"" + UUID + "\",\"proxmox_cluster\":\"lab\",\"proxmox_node\":\"node1\",\"proxmox_vmid\":101," +
                "\"manifest_sha256\":\"" + hash(MANIFEST) + "\",\"manifest_json\":\"" + MANIFEST.replace("\"", "\\\"") + "\"," +
                "\"generation\":3,\"state\":\"operating\",\"operation_lease_id\":\"old-token\",\"cloudstack_vm_ref\":\"cs-uuid\"," +
                "\"cloudstack_instance_name\":\"i-1\",\"created_at\":null,\"updated_at\":null}";
    }
    private static String export(String claims) {
        return "{\"adoption_claims\":[" + claims + "],\"adoption_operation_leases\":[{\"id\":\"old-token\",\"claim_id\":\"" + UUID + "\",\"generation\":3,\"action\":\"start\",\"expires_at\":\"2026-01-01T00:00:00\",\"created_at\":null}]," +
                "\"adoption_executions\":[{\"id\":\"exec\",\"claim_id\":\"" + UUID + "\",\"generation\":3,\"plan_sha256\":\"hash\",\"plan_json\":\"{}\",\"state\":\"submission_unknown\",\"deploy_job_id\":\"job-1\",\"start_job_id\":null,\"cleanup_job_id\":null,\"cloudstack_vm_ref\":null,\"cloudstack_instance_name\":null,\"error_code\":null,\"worker_lease_id\":null,\"worker_lease_expires_at\":null,\"attempt_count\":1,\"created_at\":null,\"updated_at\":null}]," +
                "\"adoption_write_authority\":[{\"id\":1,\"mode\":\"operator\",\"owner_id\":\"in-flight\",\"target\":null,\"expires_at\":null,\"updated_at\":null}]}";
    }
    private static void rejects(Runnable f) {
        try { f.run(); fail("expected rejection"); } catch (IllegalArgumentException expected) { /* expected */ }
    }

    @Test public void exportRetainsAmbiguousStateAndTombstone() throws Exception {
        JsonNode root = LegacyAdoptionImport.parse(export(claim()));
        assertEquals("operating", root.path("adoption_claims").get(0).path("state").asText());
        assertEquals("submission_unknown", root.path("adoption_executions").get(0).path("state").asText());
        assertEquals("in-flight", root.path("adoption_write_authority").get(0).path("owner_id").asText());
        JsonNode released = LegacyAdoptionImport.parse(export(claim().replace("operating", "released")));
        assertEquals(3, released.path("adoption_claims").get(0).path("generation").asInt());
    }

    @Test public void rejectsDuplicateKeyAndAlteredFrozenIdentity() throws Exception {
        String original = claim();
        rejects(() -> LegacyAdoptionImport.parse(export(original + "," + original)));
        rejects(() -> LegacyAdoptionImport.parse(export(original.replace("node1", "node2"))));
        rejects(() -> LegacyAdoptionImport.parse(export(original.replace("\"generation\":3", "\"generation\":0"))));
        rejects(() -> LegacyAdoptionImport.parse(export(original).replace("\"attempt_count\":1", "\"attempt_count\":\"1\"")));
        rejects(() -> LegacyAdoptionImport.parse(export(original).replace("\"start_job_id\":null,", "")));
        rejects(() -> LegacyAdoptionImport.parse("{\"adoption_claims\":[],\"adoption_claims\":[]}"));
        rejects(() -> LegacyAdoptionImport.parse(export(original) + "{}"));
    }

    @Test public void validatesExactIdentityAndCanonicalToken() {
        AdoptionRegistry.Identity identity = new AdoptionRegistry.Identity(UUID, 1, "lab", 101,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", UUID, "i-1");
        assertEquals(101, identity.vmid);
        assertTrue(identity.claimId.equals(UUID));
        rejects(() -> AdoptionRegistry.canonicalUuid("1-1-1-1-1"));
        rejects(() -> new AdoptionRegistry.Identity(UUID, 0, "lab", 101,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", UUID, "i-1"));
    }

    @Test public void reservationFreezesManifestAndRejectsAliases() throws Exception {
        String digest=hash(MANIFEST);
        AdoptionRegistry.Reservation r=new AdoptionRegistry.Reservation(UUID,3,"lab","node1",101,digest,MANIFEST);
        assertEquals(3,r.generation);
        rejects(() -> new AdoptionRegistry.Reservation(UUID,0,"lab","node1",101,digest,MANIFEST));
        rejects(() -> new AdoptionRegistry.Reservation(UUID,3,"Lab","node1",101,digest,MANIFEST));
        rejects(() -> new AdoptionRegistry.Reservation(UUID,3,"lab","node2",101,digest,MANIFEST));
        rejects(() -> new AdoptionRegistry.Reservation(UUID,3,"lab","node1",102,digest,MANIFEST));
        rejects(() -> new AdoptionRegistry.Reservation(UUID,3,"lab","node1",101,digest,MANIFEST + " "));
        String trailing=MANIFEST + "{}", trailingHash=hash(trailing);
        rejects(() -> new AdoptionRegistry.Reservation(UUID,3,"lab","node1",101,trailingHash,trailing));
        rejects(() -> new AdoptionRegistry.Identity(UUID,1,"LAB",101,digest,UUID,"i-1"));
    }

    @Test public void newerOperationsAreExplicitlyAllowlisted() throws Exception {
        java.lang.reflect.Method action=AdoptionRegistry.class.getDeclaredMethod("action",String.class);
        action.setAccessible(true);
        for (String name : new String[]{"setha","scale","plugnic","unplugnic","console"}) {
            assertEquals(name,action.invoke(null,name));
        }
        try { action.invoke(null,"other"); fail("unsupported operation accepted"); }
        catch (java.lang.reflect.InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IllegalArgumentException); }
    }

    @Test public void verifierCannotBeOmitted() {
        AdoptionRegistry.Identity i=new AdoptionRegistry.Identity(UUID,1,"lab",101,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",UUID,"i-1");
        rejects(() -> new AdoptionRegistry().activate(i,null));
        rejects(() -> new LegacyAdoptionImport().handover(null));
    }
}
