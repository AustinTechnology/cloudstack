// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.hypervisor.external.refresh;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.ResourceLimitService;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;

public class ExternalVmRefreshManagerTest {
    private static final String SNAPSHOT = "{\"status\":\"success\",\"identity\":{\"cluster\":\"P2\",\"vmid\":123,"
            + "\"name\":\"guest\",\"node\":\"pve1\"},\"power\":\"running\",\"config_digest\":\"0123456789012345678901234567890123456789\","
            + "\"cpus\":4,\"memory_mb\":8192,\"agent_available\":false,\"nics\":[],\"disks\":[]}";
    private ExternalVmRefreshManager manager;
    private VMInstanceVO vm;
    private ServiceOfferingVO base;
    private ServiceOfferingVO current;
    private VMTemplateVO template;

    @Before public void setup() {
        manager = spy(new ExternalVmRefreshManager());
        manager.offeringDao = mock(ServiceOfferingDao.class);
        manager.detailsDao = mock(VMInstanceDetailsDao.class);
        manager.templateDao = mock(VMTemplateDao.class);
        manager.nicDao = mock(NicDao.class);
        manager.resourceLimits = mock(ResourceLimitService.class);
        manager.extensionsManager = mock(ExtensionsManager.class);
        vm = mock(VMInstanceVO.class);
        base = mock(ServiceOfferingVO.class);
        when(base.getCpu()).thenReturn(null);
        when(base.getRamSize()).thenReturn(null);
        current = mock(ServiceOfferingVO.class);
        template = mock(VMTemplateVO.class);
        when(vm.getId()).thenReturn(10L);
        when(vm.getInstanceName()).thenReturn("guest");
        when(vm.getServiceOfferingId()).thenReturn(20L);
        when(vm.getTemplateId()).thenReturn(30L);
        when(vm.getAccountId()).thenReturn(40L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.External);
        when(vm.isDisplay()).thenReturn(true);
        when(manager.offeringDao.findByIdIncludingRemoved(20L)).thenReturn(base);
        when(manager.offeringDao.findByIdIncludingRemoved(10L, 20L)).thenReturn(current);
        when(current.getCpu()).thenReturn(2);
        when(current.getRamSize()).thenReturn(4096);
        when(current.getSpeed()).thenReturn(1000);
        when(manager.nicDao.listByVmId(10L)).thenReturn(Collections.emptyList());
        when(manager.templateDao.findByIdIncludingRemoved(30L)).thenReturn(template);
        when(manager.offeringDao.getComputeOffering(eq(base), anyMap())).thenReturn(current);
        doReturn(false).when(manager).countsRunningOnly();
        doNothing().when(manager).publishUpgrade(any(), anyMap());
    }

    @Test public void dryRunDoesNotWrite() {
        manager.reconcile(vm, ExternalVmConfiguration.parse(SNAPSHOT), true);
        verifyNoInteractions(manager.resourceLimits);
        verify(manager.detailsDao, never()).persist(any());
        verify(manager, never()).publishUpgrade(any(), anyMap());
    }

    @Test public void unchangedSnapshotDoesNotEmitAccountingOrDetails() {
        when(current.getCpu()).thenReturn(4);
        when(current.getRamSize()).thenReturn(8192);
        manager.reconcile(vm, ExternalVmConfiguration.parse(SNAPSHOT), false);
        verifyNoInteractions(manager.resourceLimits);
        verify(manager.detailsDao, never()).persist(any());
        verify(manager, never()).publishUpgrade(any(), anyMap());
    }

    @Test public void fixedOfferingDriftDoesNotChangeDetailsOrCounts() {
        when(base.getCpu()).thenReturn(2);
        when(base.getRamSize()).thenReturn(4096);
        manager.reconcile(vm, ExternalVmConfiguration.parse(SNAPSHOT), false);
        verifyNoInteractions(manager.resourceLimits);
        verify(manager.detailsDao, never()).persist(any());
        verify(manager, never()).publishUpgrade(any(), anyMap());
    }

    @Test public void adoptedApplyRequiresRegistryIntegration() {
        when(manager.detailsDao.findDetails(10L, "External:adopt_existing")).thenReturn(
                java.util.List.of(new com.cloud.vm.VMInstanceDetailVO(10L, "External:adopt_existing", "true", false)));
        assertThrows(RuntimeException.class, () -> manager.reconcile(vm, ExternalVmConfiguration.parse(SNAPSHOT), false));
        verifyNoInteractions(manager.resourceLimits);
        verify(manager.detailsDao, never()).persist(any());
        verify(manager, never()).publishUpgrade(any(), anyMap());
    }

    @Test public void appliesCustomValuesWithResourceAndUsageAccounting() {
        manager.reconcile(vm, ExternalVmConfiguration.parse(SNAPSHOT), false);
        verify(manager.detailsDao, times(2)).persist(any());
        verify(manager.resourceLimits).updateVmResourceCountForServiceOfferingChange(eq(40L), eq(true), eq(2L), eq(4L),
                eq(4096L), eq(8192L), eq(current), eq(current), eq(template));
        verify(manager).publishUpgrade(eq(vm), anyMap());
    }

    @Test public void accountingFailurePropagatesAndDoesNotPublishUsage() {
        doThrow(new IllegalStateException("accounting failed")).when(manager.resourceLimits)
                .updateVmResourceCountForServiceOfferingChange(anyLong(), anyBoolean(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> manager.reconcile(vm, ExternalVmConfiguration.parse(SNAPSHOT), false));
        verify(manager, never()).publishUpgrade(any(), anyMap());
        // Database rollback is exercised at Transaction.execute, not by these DAO mocks.
    }

    @Test public void rejectsMismatchedSnapshotIdentityAndHost() {
        Answer answer = mock(Answer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getDetails()).thenReturn(SNAPSHOT);
        when(manager.extensionsManager.getInstanceConfig(vm)).thenReturn(answer);
        assertThrows(IllegalArgumentException.class, () -> manager.fetch(vm, new ExternalVmRefreshManager.Binding(1, 2, "P3", "123", "pve1")));
        assertThrows(IllegalArgumentException.class, () -> manager.fetch(vm, new ExternalVmRefreshManager.Binding(1, 2, "P2", "124", "pve1")));
        assertThrows(RuntimeException.class, () -> manager.fetch(vm, new ExternalVmRefreshManager.Binding(1, 2, "P2", "123", "pve2")));
        when(vm.getInstanceName()).thenReturn("another-instance");
        assertThrows(RuntimeException.class, () -> manager.fetch(vm, new ExternalVmRefreshManager.Binding(1, 2, "P2", "123", "pve1")));
    }
}
