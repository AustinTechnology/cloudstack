// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.provisioner;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.hypervisor.external.adoption.AdoptionRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.cloud.utils.Pair;
import com.cloud.serializer.GsonHelper;

public class NativeLifecycleProvisionerTest {
    private static final String CLAIM = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String VM = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String LEASE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final String MANIFEST = "{\"vmid\":101,\"placement\":{\"cluster\":\"cluster\",\"node\":\"old-node\"}}";
    private Path dir;
    private AdoptionRegistry registry;
    private TestProvisioner provisioner;
    private Map<String, Object> payload;

    private class TestProvisioner extends ExternalPathPayloadProvisioner {
        Map<String, Object> observed;
        @Override protected AdoptionRegistry adoptionRegistry() { return registry; }
        @Override protected String prepareExternalPayload(String extension, Map<String, Object> details) throws java.io.IOException {
            observed = new HashMap<>(details);
            Path p = dir.resolve("payload.json");
            Files.writeString(p, GsonHelper.getGson().toJson(details));
            return p.toString();
        }
        @Override protected void deleteExtensionPayloadFile(String extension, String action, String file) { }
    }

    @Before public void setup() throws Exception {
        dir = Files.createTempDirectory("native-lifecycle-test");
        registry = mock(AdoptionRegistry.class);
        provisioner = new TestProvisioner();
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(MANIFEST.getBytes(StandardCharsets.UTF_8))) {
            hash.append(String.format("%02x", b & 255));
        }
        Map<String, String> vm = new HashMap<>();
        vm.put("adopt_existing", "true"); vm.put("adopt_claim_id", CLAIM); vm.put("adopt_claim_generation", "1");
        vm.put("adopt_manifest_sha256", hash.toString()); vm.put("adopt_manifest_json", MANIFEST);
        vm.put("proxmox_vmid", "101");
        payload = new HashMap<>();
        payload.put("virtualmachineid", VM); payload.put("virtualmachinename", "i-1-VM");
        payload.put("externaldetails", Map.of("host", Map.of("proxmox_cluster", "cluster", "native_adoption_lifecycle", "true"), "virtualmachine", vm));
        payload.put("native_adoption_context", "untrusted");
        java.lang.reflect.Constructor<AdoptionRegistry.Lease> ctor = AdoptionRegistry.Lease.class.getDeclaredConstructor(String.class, String.class, Timestamp.class);
        ctor.setAccessible(true);
        AdoptionRegistry.Lease lease = ctor.newInstance(LEASE, "plugnic", new Timestamp(System.currentTimeMillis() + 600000));
        when(registry.acquire(any(), eq("plugnic"))).thenReturn(lease);
    }

    @After public void cleanup() throws Exception {
        if (dir != null) { try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception e) { throw new RuntimeException(e); } });
        } }
    }

    private Path script(String body) throws Exception {
        Path p = dir.resolve("callback.sh");
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }
    private Pair<Boolean, String> run(String body, int wait) throws Exception {
        return provisioner.executeExternalCommand("test", "plugnic", payload, wait, "test", script(body).toString());
    }

    @Test public void successUsesAuthoredContextAndCompletesExactLease() throws Exception {
        assertTrue(run("printf '%s' '{\"status\":\"success\"}'", 5).first());
        Map<?, ?> context = (Map<?, ?>) provisioner.observed.get("native_adoption_context");
        assertEquals("plugnic", context.get("action")); assertEquals(LEASE, context.get("lease_id"));
        assertEquals("operating", context.get("stored_state"));
        verify(registry, times(2)).assertLease(any(), eq("plugnic"), eq(LEASE));
        verify(registry).assertLeaseForDuration(any(), eq("plugnic"), eq(LEASE), eq(35));
        verify(registry).complete(any(), eq("plugnic"), eq(LEASE));
    }

    @Test public void insufficientLeaseBudgetNeverLaunches() throws Exception {
        doThrow(new AdoptionRegistry.Conflict("insufficient budget")).when(registry)
                .assertLeaseForDuration(any(), eq("plugnic"), eq(LEASE), anyInt());
        Path marker = dir.resolve("started");
        try { run("touch " + marker, 5); fail("expired dispatch budget"); }
        catch (AdoptionRegistry.Conflict expected) { }
        assertFalse(Files.exists(marker));
        verify(registry, never()).complete(any(), anyString(), anyString());
        // Nothing was sent, so the lease is returned instead of fencing the instance.
        verify(registry).abandon(any(), eq("plugnic"), eq(LEASE));
    }

    @Test public void knownFailureReturnsTheLeaseAndIsAnOrdinaryFailure() throws Exception {
        Pair<Boolean, String> result = run("printf '%s' '{\"status\":\"error\"}'; exit 90", 5);
        assertFalse(result.first());
        assertTrue(result.second(), result.second().startsWith("Exit code: 90"));
        verify(registry).abandon(any(), eq("plugnic"), eq(LEASE));
        verify(registry, never()).complete(any(), anyString(), anyString());
    }

    @Test public void expungeRetiresTheClaimWithoutStartingTheExtension() throws Exception {
        Path marker = dir.resolve("started");
        Pair<Boolean, String> result = provisioner.executeExternalCommand("test", "delete", payload, 5, "test", script("touch " + marker).toString());
        assertTrue(result.first());
        assertFalse(Files.exists(marker));
        verify(registry).retire(any());
        verify(registry, never()).acquire(any(), anyString());
        doThrow(new AdoptionRegistry.Conflict("claim cannot retire")).when(registry).retire(any());
        try { provisioner.executeExternalCommand("test", "delete", payload, 5, "test", script("touch " + marker).toString()); fail("operating claim"); }
        catch (AdoptionRegistry.Conflict expected) { }
        assertFalse(Files.exists(marker));
    }

    @Test public void readonlyRequiresManagedWithoutLease() throws Exception {
        when(registry.validatedState(any())).thenReturn("managed");
        for (String action : new String[] {"status", "ListSnapshots"}) {
            Pair<Boolean, String> result = provisioner.executeExternalCommand("test", action, payload, 5,
                    "test", script("printf '%s' '{\"status\":\"success\"}'").toString());
            assertTrue(result.first());
            Map<?, ?> context = (Map<?, ?>) provisioner.observed.get("native_adoption_context");
            assertEquals("managed", context.get("stored_state"));
            assertFalse(context.containsKey("lease_id"));
        }
        verify(registry, never()).acquire(any(), anyString());
    }

    @Test public void uncertainResultsNeverComplete() throws Exception {
        assertFalse(run("printf '%s' '{\"status\":\"error\"}'", 5).first());
        assertFalse(run("printf '%s' 'not-json'", 5).first());
        assertFalse(run("exit 1", 5).first());
        assertFalse(run("sleep 3", 1).first());
        verify(registry, never()).complete(any(), anyString(), anyString());
        verify(registry, never()).abandon(any(), anyString(), anyString());
    }

    @Test public void mismatchedActuatorAndUnsupportedActionNeverAcquire() throws Exception {
        @SuppressWarnings("unchecked") Map<String, Object> details = (Map<String, Object>) payload.get("externaldetails");
        @SuppressWarnings("unchecked") Map<String, String> vm = (Map<String, String>) details.get("virtualmachine");
        vm.put("proxmox_vmid", "102");
        try { run("exit 0", 5); fail("VMID mismatch"); } catch (IllegalArgumentException expected) { }
        vm.put("proxmox_vmid", "101");
        try { provisioner.executeExternalCommand("test", "prepare", payload, 5, "test", script("exit 0").toString());
            fail("unsupported prepare"); } catch (IllegalArgumentException expected) { }
        verify(registry, never()).acquire(any(), anyString());
    }

    @Test public void completionFailurePreservesNicUnknownOutcome() throws Exception {
        doThrow(new AdoptionRegistry.Conflict("completion failed")).when(registry).complete(any(), anyString(), anyString());
        Pair<Boolean, String> result = run("printf '%s' '{\"status\":\"success\"}'", 5);
        assertFalse(result.first());
        assertTrue(result.second().startsWith("NIC_OUTCOME_UNKNOWN:"));
    }

    @Test public void hostClusterMismatchCannotAcquireOrDispatch() throws Exception {
        @SuppressWarnings("unchecked") Map<String, Object> details = (Map<String, Object>) payload.get("externaldetails");
        @SuppressWarnings("unchecked") Map<String, String> vm = (Map<String, String>) details.get("virtualmachine");
        vm.put("proxmox_cluster", "different-cluster");
        Path marker = dir.resolve("started");
        try { run("touch " + marker, 5); fail("cluster mismatch"); }
        catch (IllegalArgumentException expected) { }
        assertFalse(Files.exists(marker));
        verifyNoInteractions(registry);
    }

    @Test public void externalVmidAliasIsSupportedButConflictsAreRejected() throws Exception {
        for (String bad : new String[] {"", "0101", "102"}) {
            Map<String, String> aliases = new HashMap<>();
            aliases.put("External:proxmox_vmid", bad);
            payload.put("cloudstack.vm.details", Map.of("details", aliases));
            try { run("exit 0", 5); fail("malformed or conflicting alias"); }
            catch (IllegalArgumentException expected) { }
            verifyNoInteractions(registry);
        }
        @SuppressWarnings("unchecked") Map<String, Object> details = (Map<String, Object>) payload.get("externaldetails");
        @SuppressWarnings("unchecked") Map<String, String> vm = (Map<String, String>) details.get("virtualmachine");
        vm.remove("proxmox_vmid");
        payload.put("cloudstack.vm.details", Map.of("details", Map.of("External:proxmox_vmid", "101")));
        assertTrue(run("printf '%s' '{\"status\":\"success\"}'", 5).first());
    }

    @Test public void legacyNeverUsesRegistryOrUntrustedContext() throws Exception {
        @SuppressWarnings("unchecked") Map<String, Object> details = (Map<String, Object>) payload.get("externaldetails");
        details = new HashMap<>(details); details.put("host", Map.of("proxmox_cluster", "cluster")); payload.put("externaldetails", details);
        assertTrue(run("printf '%s' '{\"status\":\"error\"}'", 5).first());
        assertFalse(provisioner.observed.containsKey("native_adoption_context"));
        verifyNoInteractions(registry);
    }
}
