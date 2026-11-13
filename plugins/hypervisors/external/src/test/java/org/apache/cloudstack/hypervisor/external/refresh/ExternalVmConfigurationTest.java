// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.hypervisor.external.refresh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class ExternalVmConfigurationTest {
    private static final String SNAPSHOT = "{\"status\":\"success\","
            + "\"identity\":{\"cluster\":\"P2\",\"vmid\":123,\"name\":\"provider-name\",\"node\":\"pve1\"},"
            + "\"power\":\"running\",\"config_digest\":\"0123456789012345678901234567890123456789\","
            + "\"cpus\":4,\"memory_mb\":8192,\"agent_available\":true,"
            + "\"nics\":[{\"device_id\":0,\"mac\":\"12:34:56:78:9a:bc\",\"bridge\":\"vmbr0\","
            + "\"vlan\":100,\"ip_addresses\":[\"192.0.2.1\"]}],"
            + "\"disks\":[{\"device\":\"scsi0\",\"size\":\"5G\"}]}";

    private void rejects(String json) {
        try {
            ExternalVmConfiguration.parse(json);
            fail("Expected invalid snapshot");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().startsWith("Invalid getconfig field:"));
        }
    }

    @Test
    public void readsCompleteSnapshotWithoutUsingNameAsIdentity() {
        ExternalVmConfiguration snapshot = ExternalVmConfiguration.parse(SNAPSHOT);
        assertEquals("P2", snapshot.cluster);
        assertEquals("123", snapshot.vmid);
        assertEquals("provider-name", snapshot.name);
        assertEquals("net0", snapshot.nics.get(0).deviceId);
        assertEquals("192.0.2.1", snapshot.nics.get(0).ipAddresses.get(0));
        assertEquals(4, snapshot.cpus);
        assertEquals(8192, snapshot.memoryMb);
        assertTrue(snapshot.agentAvailable);
        assertEquals(1, snapshot.disks.size());
        snapshot.requireIdentity("P2", "123");
        try {
            snapshot.requireIdentity("P3", "123");
            fail("Cluster mismatch must fail");
        } catch (IllegalArgumentException expected) {
            // Names are intentionally not used as identity; cluster and VMID are mandatory.
        }
    }

    @Test
    public void unavailableAgentAndEmptyAddressesArePreserved() {
        ExternalVmConfiguration snapshot = ExternalVmConfiguration.parse(SNAPSHOT
                .replace("\"agent_available\":true", "\"agent_available\":false")
                .replace("[\"192.0.2.1\"]", "[]"));
        assertFalse(snapshot.agentAvailable);
        assertTrue(snapshot.nics.get(0).ipAddresses.isEmpty());
    }

    @Test
    public void rejectsWrongTypesMissingIdentityAndUnstableEvidence() {
        rejects(SNAPSHOT.replace("\"vmid\":123", "\"vmid\":\"123\""));
        rejects(SNAPSHOT.replace("\"cpus\":4", "\"cpus\":4.0"));
        rejects(SNAPSHOT.replace("\"memory_mb\":8192", "\"memory_mb\":0"));
        rejects(SNAPSHOT.replace("\"agent_available\":true", "\"agent_available\":\"true\""));
        rejects(SNAPSHOT.replace("\"cluster\":\"P2\"", "\"cluster\":\" P2\""));
        rejects(SNAPSHOT.replace("\"power\":\"running\"", "\"power\":\"paused\""));
        rejects(SNAPSHOT.replace("0123456789012345678901234567890123456789", "not-a-digest"));
        rejects(SNAPSHOT.replace("\"status\":\"success\"", "\"status\":\"error\""));
        rejects(SNAPSHOT.replace("\"cluster\":\"P2\"", "\"cluster\":\"P2\",\"cluster\":\"P3\""));
    }

    @Test
    public void rejectsAmbiguousMacDeviceAndBadAddresses() {
        String second = ", {\"device_id\":1,\"mac\":\"12:34:56:78:9A:BC\",\"bridge\":null,\"vlan\":null,\"ip_addresses\":[]}";
        rejects(SNAPSHOT.replace("}],\"disks\"", "}" + second + "],\"disks\""));
        rejects(SNAPSHOT.replace("192.0.2.1", "localhost"));
        rejects(SNAPSHOT.replace("192.0.2.1", "2001:db8::1"));
        rejects(SNAPSHOT.replace("12:34:56:78:9a:bc", "not-a-mac"));
    }

    @Test
    public void topologyRequiresProvenNetworkAndDoesNotClearOnAgentFailure() {
        ExternalVmTopologyDiff.NativeNic nic = new ExternalVmTopologyDiff.NativeNic(
                7, 8, "12:34:56:78:9A:BC", true, "vmbr0", 100, "192.0.2.2");
        ExternalVmConfiguration snapshot = ExternalVmConfiguration.parse(SNAPSHOT);
        ExternalVmTopologyDiff diff = ExternalVmTopologyDiff.compare(snapshot, Collections.singletonList(nic));
        assertEquals(1, diff.ipCandidates.size());
        assertEquals("192.0.2.1", diff.ipCandidates.get(0).observed);
        assertTrue(diff.unresolved.isEmpty());

        ExternalVmConfiguration offline = ExternalVmConfiguration.parse(SNAPSHOT
                .replace("\"agent_available\":true", "\"agent_available\":false")
                .replace("[\"192.0.2.1\"]", "[]"));
        diff = ExternalVmTopologyDiff.compare(offline, Collections.singletonList(nic));
        assertTrue(diff.ipCandidates.isEmpty());
        assertEquals(1, diff.unresolved.size());
        ExternalVmTopologyDiff.NativeNic mismatch = new ExternalVmTopologyDiff.NativeNic(
                7, 8, nic.mac, true, "vmbr0", 200, nic.ipAddress);
        diff = ExternalVmTopologyDiff.compare(snapshot, Collections.singletonList(mismatch));
        assertTrue(diff.ipCandidates.isEmpty());
        assertTrue(diff.unresolved.get(0).contains("network topology differs"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void topologyRejectsDuplicateNativeMacs() {
        ExternalVmTopologyDiff.NativeNic first = new ExternalVmTopologyDiff.NativeNic(
                7, 8, "12:34:56:78:9a:bc", true, "vmbr0", 100, null);
        ExternalVmTopologyDiff.NativeNic second = new ExternalVmTopologyDiff.NativeNic(
                9, 8, "12:34:56:78:9A:BC", true, "vmbr0", 100, null);
        ExternalVmTopologyDiff.compare(ExternalVmConfiguration.parse(SNAPSHOT), Arrays.asList(first, second));
    }

    @Test
    public void topologyReportsMissingNicsAndIsIdempotentForSameAddress() {
        ExternalVmConfiguration snapshot = ExternalVmConfiguration.parse(SNAPSHOT);
        ExternalVmTopologyDiff diff = ExternalVmTopologyDiff.compare(snapshot, Collections.emptyList());
        assertTrue(diff.unresolved.get(0).contains("provider-only"));
        ExternalVmTopologyDiff.NativeNic nic = new ExternalVmTopologyDiff.NativeNic(
                7, 8, "12:34:56:78:9a:bc", true, "vmbr0", 100, "192.0.2.1");
        diff = ExternalVmTopologyDiff.compare(snapshot, Collections.singletonList(nic));
        assertTrue(diff.ipCandidates.isEmpty());
        assertTrue(diff.unresolved.isEmpty());
        ExternalVmTopologyDiff.NativeNic unknown = new ExternalVmTopologyDiff.NativeNic(
                7, 8, nic.mac, false, "vmbr0", 100, nic.ipAddress);
        diff = ExternalVmTopologyDiff.compare(snapshot, Collections.singletonList(unknown));
        assertTrue(diff.unresolved.get(0).contains("network identity unknown"));
    }

    @Test
    public void topologyRejectsAmbiguousAndLinkLocalGuestAddresses() {
        ExternalVmTopologyDiff.NativeNic nic = new ExternalVmTopologyDiff.NativeNic(
                7, 8, "12:34:56:78:9a:bc", true, "vmbr0", 100, null);
        for (String replacement : Arrays.asList("\"169.254.1.1\"", "\"192.0.2.1\",\"192.0.2.2\"")) {
            ExternalVmConfiguration snapshot = ExternalVmConfiguration.parse(
                    SNAPSHOT.replace("\"192.0.2.1\"", replacement));
            ExternalVmTopologyDiff diff = ExternalVmTopologyDiff.compare(snapshot, Collections.singletonList(nic));
            assertTrue(diff.ipCandidates.isEmpty());
            assertEquals(1, diff.unresolved.size());
        }
    }
}
