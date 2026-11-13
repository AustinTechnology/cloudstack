// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.refresh;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.apache.cloudstack.hypervisor.external.observation.ExternalObservationService;
import org.apache.cloudstack.hypervisor.external.observation.ObservationBindingResolver;
import com.cloud.network.Network;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.NicDao;

public class ExternalNativeNicRefreshTest {
    private static final String MAC = "02:00:00:00:00:01";
    private NicDao nicDao;
    private NetworkDao networkDao;
    private ObservationBindingResolver resolver;
    private ExternalNativeNicRefresh refresh;
    private NicVO nic;
    private NetworkVO network;
    private VMInstanceVO vm;
    private ObservationBindingResolver.Binding binding;

    private ExternalVmConfiguration snapshot(boolean agent, String addresses, String bridge, String vlan) {
        return ExternalVmConfiguration.parse("{\"status\":\"success\",\"identity\":{\"cluster\":\"P2\",\"vmid\":123,\"name\":\"guest\",\"node\":\"pve1\"},"
                + "\"power\":\"running\",\"config_digest\":\"0123456789012345678901234567890123456789\",\"cpus\":4,\"memory_mb\":8192,"
                + "\"agent_available\":" + agent + ",\"nics\":[{\"device_id\":0,\"mac\":\"" + MAC + "\",\"bridge\":\"" + bridge
                + "\",\"vlan\":" + vlan + ",\"ip_addresses\":[" + addresses + "]}],\"disks\":[]}");
    }
    @Before public void setup() {
        nicDao = mock(NicDao.class); networkDao = mock(NetworkDao.class); resolver = mock(ObservationBindingResolver.class);
        refresh = spy(new ExternalNativeNicRefresh(nicDao, networkDao, resolver, null));
        vm = mock(VMInstanceVO.class); nic = mock(NicVO.class); network = mock(NetworkVO.class);
        when(vm.getId()).thenReturn(10L); when(vm.getAccountId()).thenReturn(40L); when(vm.getDataCenterId()).thenReturn(2L);
        when(nic.getId()).thenReturn(30L); when(nic.getUuid()).thenReturn("nic-uuid"); when(nic.getNetworkId()).thenReturn(20L);
        when(nic.getInstanceId()).thenReturn(10L); when(nic.getMacAddress()).thenReturn(MAC); when(nic.getIPv4Address()).thenReturn("10.0.0.10");
        when(nic.getBroadcastUri()).thenReturn(URI.create("vlan://100"));
        when(network.getId()).thenReturn(20L); when(network.getAccountId()).thenReturn(40L); when(network.getDataCenterId()).thenReturn(2L);
        when(network.getGuestType()).thenReturn(Network.GuestType.L2); when(network.getTrafficType()).thenReturn(TrafficType.Guest);
        when(network.getBroadcastUri()).thenReturn(URI.create("vlan://100")); when(network.getCidr()).thenReturn("10.0.0.0/24");
        when(nicDao.listByVmId(10L)).thenReturn(List.of(nic)); when(nicDao.lockRow(30L, true)).thenReturn(nic);
        when(networkDao.lockRow(20L, true)).thenReturn(network); when(nicDao.update(eq(30L), any())).thenReturn(true);
        binding = new ObservationBindingResolver.Binding(10, 3, 4, 123, MAC, "fingerprint");
        when(resolver.current(10L, MAC)).thenReturn(binding);
        doReturn(false).when(refresh).assignedElsewhere(anyLong(), anyLong(), anyString());
        doReturn(true).when(refresh).observationStillValid(any(), anyString());
    }
    private List<ExternalVmRefreshResponse.IpChange> run(ExternalVmConfiguration snapshot, String arp, boolean dry) {
        return refresh.apply(vm, snapshot, Map.of(MAC, new ExternalNativeNicRefresh.Evidence(binding, arp)), "vmbr0", dry, new ArrayList<>());
    }
    @Test public void agentFirstAndApplyThenRepeat() {
        ExternalVmConfiguration s = snapshot(true, "\"10.0.0.20\"", "vmbr0", "100");
        List<ExternalVmRefreshResponse.IpChange> changes = run(s, "10.0.0.30", false);
        assertEquals(1, changes.size()); assertEquals("10.0.0.20", changes.get(0).proposed); assertEquals("guestagent", changes.get(0).source);
        verify(nic).setIPv4Address("10.0.0.20"); verify(nicDao).update(30L, nic);
        when(nic.getIPv4Address()).thenReturn("10.0.0.20");
        assertTrue(run(s, null, false).isEmpty()); verify(nicDao, times(1)).update(30L, nic);
    }
    @Test public void dryRunProposesWithoutWriting() {
        List<ExternalVmRefreshResponse.IpChange> changes = run(snapshot(true, "\"10.0.0.20\"", "vmbr0", "100"), null, true);
        assertEquals(1, changes.size()); assertFalse(changes.get(0).applied);
        verify(nic, never()).setIPv4Address(anyString()); verify(nicDao, never()).update(anyLong(), any());
    }
    @Test public void missingAndMultipleNeverClearOrUseFallback() {
        assertTrue(run(snapshot(false, "", "vmbr0", "100"), null, false).isEmpty());
        assertTrue(run(snapshot(true, "\"10.0.0.20\",\"10.0.0.21\"", "vmbr0", "100"), "10.0.0.30", false).isEmpty());
        verify(nic, never()).setIPv4Address(any());
    }
    @Test public void duplicateAndNetworkMismatchesSkip() {
        ExternalVmConfiguration s = snapshot(true, "\"10.0.0.20\"", "vmbr0", "100");
        doReturn(true).when(refresh).assignedElsewhere(20L, 30L, "10.0.0.20"); assertTrue(run(s, null, false).isEmpty());
        doReturn(false).when(refresh).assignedElsewhere(anyLong(), anyLong(), anyString());
        assertTrue(run(snapshot(true, "\"10.0.0.20\"", "vmbr1", "100"), null, false).isEmpty());
        assertTrue(run(snapshot(true, "\"10.0.0.20\"", "vmbr0", "101"), null, false).isEmpty());
        when(network.getAccountId()).thenReturn(41L); assertTrue(run(s, null, false).isEmpty());
        when(network.getAccountId()).thenReturn(40L); when(network.getCidr()).thenReturn("10.0.1.0/24"); assertTrue(run(s, null, false).isEmpty());
        verify(nicDao, never()).update(anyLong(), any());
    }
    @Test public void arpNeedsStableBinding() {
        ExternalVmConfiguration s = snapshot(false, "", "vmbr0", "100");
        assertEquals("observation", run(s, "10.0.0.30", true).get(0).source);
        when(resolver.current(10L, MAC)).thenReturn(new ObservationBindingResolver.Binding(10, 3, 4, 123, MAC, "changed"));
        assertTrue(run(s, "10.0.0.30", false).isEmpty()); verify(nicDao, never()).update(anyLong(), any());
    }
    @Test public void collectionNeverQueriesArpForAgentOrAmbiguousAgent() throws Exception {
        ExternalObservationService observations = mock(ExternalObservationService.class);
        ExternalNativeNicRefresh collecting = new ExternalNativeNicRefresh(nicDao, networkDao, resolver, observations);
        assertEquals(1, collecting.collect(10L, snapshot(true, "\"10.0.0.20\"", "vmbr0", "100")).size());
        assertTrue(collecting.collect(10L, snapshot(true, "\"10.0.0.20\",\"10.0.0.21\"", "vmbr0", "100")).isEmpty());
        verify(observations, never()).fallbackCandidate(anyLong(), anyString(), any());
        collecting.collect(10L, snapshot(false, "", "vmbr0", "100"));
        verify(observations).fallbackCandidate(10L, MAC, null);
    }
    @Test public void invalidatedOrExpiredObservationIsNotApplied() {
        ExternalVmConfiguration s = snapshot(false, "", "vmbr0", "100");
        doReturn(false).when(refresh).observationStillValid(binding, "10.0.0.30");
        assertTrue(run(s, "10.0.0.30", false).isEmpty());
        doReturn(true).when(refresh).observationStillValid(binding, "10.0.0.30");
        Map<String, ExternalNativeNicRefresh.Evidence> old = Map.of(MAC,
                new ExternalNativeNicRefresh.Evidence(binding, "10.0.0.30", System.nanoTime() - 1));
        assertTrue(refresh.apply(vm, s, old, "vmbr0", false, new ArrayList<>()).isEmpty());
        verify(nicDao, never()).update(anyLong(), any());
    }
    @Test public void presentNetmaskMustBeContiguousAndAgree() {
        when(nic.getIPv4Netmask()).thenReturn("255.0.255.0"); assertFalse(ExternalNativeNicRefresh.subnetAllows(network, nic, "10.0.0.20"));
        when(nic.getIPv4Netmask()).thenReturn("255.255.255.0"); assertTrue(ExternalNativeNicRefresh.subnetAllows(network, nic, "10.0.0.20"));
        assertFalse(ExternalNativeNicRefresh.subnetAllows(network, nic, "10.0.0.255"));
    }
}
