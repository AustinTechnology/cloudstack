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
package com.cloud.vm;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.dc.DataCenter;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.Network;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

public class ExternalNicCleanupTest {
    private void exerciseRunningExternalAdd(boolean unknown) throws Exception {
        VirtualMachineManagerImpl manager = spy(new VirtualMachineManagerImpl());
        NetworkOrchestrationService networkManager = mock(NetworkOrchestrationService.class);
        VMInstanceDao vmDao = mock(VMInstanceDao.class);
        NicDao nicDao = mock(NicDao.class);
        HostDao hostDao = mock(HostDao.class);
        EntityManager entityManager = mock(EntityManager.class);
        HypervisorGuruManager gurus = mock(HypervisorGuruManager.class);
        HypervisorGuru guru = mock(HypervisorGuru.class);
        ReflectionTestUtils.setField(manager, "_networkMgr", networkManager);
        ReflectionTestUtils.setField(manager, "_vmDao", vmDao);
        ReflectionTestUtils.setField(manager, "_nicsDao", nicDao);
        ReflectionTestUtils.setField(manager, "_hostDao", hostDao);
        ReflectionTestUtils.setField(manager, "_entityMgr", entityManager);
        ReflectionTestUtils.setField(manager, "_hvGuruMgr", gurus);

        VMInstanceVO vm = mock(VMInstanceVO.class);
        Network network = mock(Network.class);
        NicProfile nic = mock(NicProfile.class);
        NicVO nicVo = mock(NicVO.class);
        when(vm.getId()).thenReturn(1L);
        when(vm.getHostId()).thenReturn(2L);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.External);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vmDao.findById(1L)).thenReturn(vm);
        when(nicDao.listByVmId(1L)).thenReturn(Collections.emptyList());
        when(hostDao.findById(2L)).thenReturn(mock(HostVO.class));
        when(networkManager.createNicForVm(any(), any(), any(), any(), anyBoolean())).thenReturn(nic);
        when(nic.getId()).thenReturn(3L);
        when(nicDao.findById(3L)).thenReturn(nicVo);
        when(gurus.getGuru(HypervisorType.External)).thenReturn(guru);
        when(guru.implement(any())).thenReturn(mock(VirtualMachineTO.class));
        doReturn(mock(NicTO.class)).when(manager).toNicTO(any(), any());
        if (unknown) {
            doThrow(new ResourceUnavailableException("NIC_OUTCOME_UNKNOWN", DataCenter.class, 1L))
                    .when(manager).plugNic(any(), any(), any(), any(), any());
        } else {
            doReturn(false).when(manager).plugNic(any(), any(), any(), any(), any());
        }

        Method add = VirtualMachineManagerImpl.class.getDeclaredMethod("orchestrateAddVmToNetwork",
                VirtualMachine.class, Network.class, NicProfile.class);
        add.setAccessible(true);
        try (MockedStatic<CallContext> context = Mockito.mockStatic(CallContext.class)) {
            context.when(CallContext::current).thenReturn(mock(CallContext.class));
            if (unknown) {
                assertThrows(InvocationTargetException.class, () -> add.invoke(manager, vm, network, new NicProfile()));
                verify(networkManager, never()).removeNic(any(), any());
            } else {
                assertNull(add.invoke(manager, vm, network, new NicProfile()));
                verify(networkManager).removeNic(any(), any());
            }
        }
    }

    @Test
    public void runningExternalUnknownOutcomeRetainsAllocation() throws Exception {
        exerciseRunningExternalAdd(true);
    }

    @Test
    public void runningExternalDefiniteFailureRemovesAllocation() throws Exception {
        exerciseRunningExternalAdd(false);
    }
}
