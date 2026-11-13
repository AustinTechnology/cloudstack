// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.observation;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.Pair;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

/** Resolver behavior with DB lookup seams stubbed; not a database integration test. */
public class NativeObservationBindingResolverTest {
    static class Resolver extends NativeObservationBindingResolver {
        boolean isAdopted; Long unique = 7L; String network = "network-uuid";
        @Override boolean adopted(long i, String u, String n, String c, int v) { return isAdopted; }
        @Override Long uniqueVm(long cluster, int vmid) { return unique; }
        @Override String networkUuid(long id, String uri) { return network; }
    }
    private static final String SNAPSHOT = "{\"status\":\"success\",\"identity\":{\"cluster\":\"P2\",\"vmid\":100,\"name\":\"i-2-7-VM\",\"node\":\"pve1\"},"
            + "\"power\":\"running\",\"config_digest\":\"0123456789012345678901234567890123456789\",\"cpus\":2,\"memory_mb\":2048,\"agent_available\":false,"
            + "\"nics\":[{\"device_id\":0,\"mac\":\"02:00:00:00:00:01\",\"bridge\":\"vmbr0\",\"vlan\":100,\"ip_addresses\":[]}],\"disks\":[]}";
    @Test public void ordinaryGuestResolvesAndDriftOrAdoptionFailsClosed() throws Exception {
        Resolver r = new Resolver();
        r.vmDao=mock(VMInstanceDao.class); r.detailsDao=mock(VMInstanceDetailsDao.class); r.nicDao=mock(NicDao.class);
        r.hostDao=mock(HostDao.class); r.vmManager=mock(VirtualMachineManager.class); r.extensionsManager=mock(ExtensionsManager.class);
        VMInstanceVO vm=mock(VMInstanceVO.class); NicVO nic=mock(NicVO.class); HostVO host=mock(HostVO.class); Answer answer=mock(Answer.class);
        when(r.vmDao.findById(7L)).thenReturn(vm);
        when(vm.getId()).thenReturn(7L); when(vm.getUuid()).thenReturn("vm-uuid"); when(vm.getInstanceName()).thenReturn("i-2-7-VM");
        when(vm.getType()).thenReturn(VirtualMachine.Type.User); when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.External); when(vm.getUpdated()).thenReturn(9L);
        when(r.detailsDao.findDetails(eq(7L), anyString())).thenReturn(Collections.emptyList());
        when(r.detailsDao.findDetails(7L,"proxmox_vmid")).thenReturn(Collections.singletonList(new VMInstanceDetailVO(7L,"proxmox_vmid","100",false)));
        when(r.vmManager.findClusterAndHostIdForVm(vm,false)).thenReturn(new Pair<>(2L,3L));
        when(r.hostDao.findById(3L)).thenReturn(host); when(host.getClusterId()).thenReturn(2L); when(host.getUuid()).thenReturn("host-uuid");
        when(host.getHypervisorType()).thenReturn(HypervisorType.External);
        when(r.extensionsManager.getExternalAccessDetails(host,null)).thenReturn(Map.of("host",Map.of("proxmox_cluster","P2","node","pve1","network_bridge","vmbr0")));
        when(r.nicDao.listByVmId(7L)).thenReturn(Collections.singletonList(nic));
        when(nic.getMacAddress()).thenReturn("02:00:00:00:00:01"); when(nic.getUuid()).thenReturn("nic-uuid");
        when(nic.getNetworkId()).thenReturn(4L); when(nic.getBroadcastUri()).thenReturn(URI.create("vlan://100"));
        when(r.extensionsManager.getInstanceConfig(vm)).thenReturn(answer); when(answer.getResult()).thenReturn(true); when(answer.getDetails()).thenReturn(SNAPSHOT);
        ObservationBindingResolver.Binding b=r.source(2,3,100,"02:00:00:00:00:01"); assertNotNull(b);
        assertEquals(b.fingerprint,r.current(7,"02:00:00:00:00:01").fingerprint);
        assertNull(r.source(2,4,100,"02:00:00:00:00:01"));
        when(answer.getDetails()).thenReturn(SNAPSHOT.replace("vmbr0","vmbr1")); assertNull(r.current(7,"02:00:00:00:00:01"));
        when(answer.getDetails()).thenReturn(SNAPSHOT.replace("i-2-7-VM","different")); assertNull(r.current(7,"02:00:00:00:00:01"));
        when(answer.getDetails()).thenReturn(SNAPSHOT); r.isAdopted=true; assertNull(r.current(7,"02:00:00:00:00:01"));
        r.isAdopted=false; r.unique=null; assertNull(r.current(7,"02:00:00:00:00:01"));
        r.unique=7L; when(vm.getUpdated()).thenReturn(10L); assertNotEquals(b.fingerprint,r.current(7,"02:00:00:00:00:01").fingerprint);
    }
}
