// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.adoption;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class LegacyAdoptionImportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLAIM = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String EXECUTION = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private ObjectNode export(String claimState, String executionState) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode claim = root.putArray("adoption_claims").addObject();
        claim.put("id", CLAIM).put("generation", 1).put("proxmox_vmid", 103).put("state", claimState).putNull("operation_lease_id");
        root.putArray("adoption_operation_leases");
        ObjectNode execution = root.putArray("adoption_executions").addObject();
        execution.put("id", EXECUTION).put("claim_id", CLAIM).put("generation", 1).put("state", executionState)
                .put("cloudstack_vm_ref", EXECUTION).put("cloudstack_instance_name", "i-2-6-VM");
        execution.putNull("worker_lease_id").putNull("worker_lease_expires_at").putNull("error_code");
        ObjectNode authority = root.putArray("adoption_write_authority").addObject();
        authority.put("id", 1).putNull("mode").putNull("owner_id").putNull("target").putNull("expires_at");
        return root;
    }

    private void refused(ObjectNode root, String expected) {
        try {
            LegacyAdoptionImport.requireSettled(root);
            fail("accepted an unsettled export");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
    }

    @Test public void settledExportIsAccepted() {
        LegacyAdoptionImport.requireSettled(export("managed", "succeeded"));
    }

    @Test public void unfinishedClaimIsRefusedAndNamed() {
        ObjectNode root = export("reserved", "submission_unknown");
        ((ObjectNode) root.get("adoption_executions").get(0)).putNull("cloudstack_vm_ref").put("error_code", "deploy_submission_unknown");
        refused(root, "claim " + CLAIM + " (VMID 103) is reserved");
        refused(root, "execution " + EXECUTION + " is submission_unknown");
    }

    @Test public void unfinishedExecutionOfManagedClaimIsRefused() {
        refused(export("managed", "start_unknown"), "execution " + EXECUTION + " is start_unknown");
    }

    @Test public void leaseAndHeldAuthorityAreRefused() {
        ObjectNode root = export("managed", "succeeded");
        root.withArray("adoption_operation_leases").addObject().put("id", "lease");
        refused(root, "1 operation lease(s)");
        root = export("managed", "succeeded");
        ((ObjectNode) root.get("adoption_write_authority").get(0)).put("mode", "operator");
        refused(root, "write authority is held");
    }
}
