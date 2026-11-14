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
package org.apache.cloudstack.hypervisor.external.adoption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.hypervisor.external.refresh.ExternalVmConfiguration;
import org.apache.cloudstack.hypervisor.external.refresh.ExternalVmRefreshManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.dao.NetworkVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.vm.UserVmService;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

public class NativeInstanceAdoptionTest {
    private static final String SNAP = "{\"status\":\"success\",\"identity\":{\"cluster\":\"lab\",\"vmid\":105,\"name\":\"oob-guest\",\"node\":\"pve2\"},"
            + "\"power\":\"running\",\"config_digest\":\"0123456789012345678901234567890123456789\",\"cpus\":2,\"memory_mb\":768,\"agent_available\":false,"
            + "\"nics\":[{\"device_id\":0,\"mac\":\"02:00:00:00:00:01\",\"bridge\":\"vmbr1\",\"vlan\":100,\"ip_addresses\":[]}],\"disks\":[]}";
    private static final String DOMAIN = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
    NativeInstanceAdoption a;
    HostVO host1, host2;
    Answer answer;
    Account owner;
    ServiceOfferingVO offering;
    VMTemplateVO template;
    NetworkVO network;

    @Before public void setup() {
        a = new NativeInstanceAdoption();
        a.hostDao = mock(HostDao.class); a.vmDao = mock(VMInstanceDao.class); a.detailsDao = mock(VMInstanceDetailsDao.class);
        a.offeringDao = mock(ServiceOfferingDao.class); a.templateDao = mock(VMTemplateDao.class); a.dataCenterDao = mock(DataCenterDao.class);
        a.accountService = mock(AccountService.class); a.domainDao = mock(DomainDao.class); a.resourceLimits = mock(ResourceLimitService.class);
        a.extensionHelper = mock(ExtensionHelper.class); a.extensionsManager = mock(ExtensionsManager.class); a.agentManager = mock(AgentManager.class);
        a.vmManager = mock(VirtualMachineManager.class); a.userVmService = mock(UserVmService.class);
        a.networkOrchestrator = mock(NetworkOrchestrationService.class); a.refreshManager = mock(ExternalVmRefreshManager.class);
        a.verifier = mock(NativeManagedAdoptionVerifier.class);
        a.volumeSync = mock(org.apache.cloudstack.hypervisor.external.storage.ExternalVolumeSync.class);
        host1 = mock(HostVO.class); host2 = mock(HostVO.class); answer = mock(Answer.class); owner = mock(Account.class);
        offering = mock(ServiceOfferingVO.class); template = mock(VMTemplateVO.class); network = mock(NetworkVO.class);
        Extension extension = mock(Extension.class);
        for (HostVO h : new HostVO[] {host1, host2}) {
            when(h.getHypervisorType()).thenReturn(HypervisorType.External); when(h.getClusterId()).thenReturn(20L);
            when(h.getDataCenterId()).thenReturn(1L);
        }
        when(host1.getId()).thenReturn(10L); when(host2.getId()).thenReturn(11L); when(host2.getName()).thenReturn("pve2");
        when(a.hostDao.findById(10L)).thenReturn(host1);
        when(a.hostDao.listByClusterAndHypervisorType(20L, HypervisorType.External)).thenReturn(List.of(host1, host2));
        when(a.extensionsManager.getExternalAccessDetails(host1, null)).thenReturn(Map.of("host", Map.of("proxmox_cluster", "lab", "node", "pve1",
                "network_bridge", "vmbr1", "native_adoption_status", "true", "native_adoption_lifecycle", "true")));
        when(a.extensionsManager.getExternalAccessDetails(host2, null)).thenReturn(Map.of("host", Map.of("proxmox_cluster", "lab", "node", "pve2",
                "network_bridge", "vmbr1", "native_adoption_status", "true", "native_adoption_lifecycle", "true")));
        when(a.extensionHelper.getExtensionForCluster(20L)).thenReturn(extension); when(extension.getId()).thenReturn(4L);
        when(a.extensionHelper.isInstanceGetConfigSupported(4L)).thenReturn(true);
        when(a.agentManager.easySend(eq(10L), any())).thenReturn(answer); when(answer.getResult()).thenReturn(true); when(answer.getDetails()).thenReturn(SNAP);
        when(a.detailsDao.findDetails(any(String.class))).thenReturn(Collections.emptyList());
        when(a.accountService.finalizeOwner(any(), isNull(), isNull(), isNull())).thenReturn(owner);
        when(owner.getType()).thenReturn(Account.Type.ADMIN); when(owner.getDomainId()).thenReturn(6L); when(owner.getAccountName()).thenReturn("admin");
        DomainVO domain = mock(DomainVO.class); when(a.domainDao.findById(6L)).thenReturn(domain); when(domain.getUuid()).thenReturn(DOMAIN);
        DataCenterVO zone = mock(DataCenterVO.class); when(a.dataCenterDao.findById(1L)).thenReturn(zone); when(zone.getId()).thenReturn(1L);
        when(a.templateDao.findById(202L)).thenReturn(template); when(template.getHypervisorType()).thenReturn(HypervisorType.External);
        when(template.getFormat()).thenReturn(ImageFormat.EXTERNAL);
        when(a.offeringDao.findById(17L)).thenReturn(offering); when(offering.isDynamic()).thenReturn(true);
        when(offering.getCpu()).thenReturn(null); when(offering.getRamSize()).thenReturn(null); when(offering.getSpeed()).thenReturn(null);
        when(a.offeringDao.getComputeOffering(eq(offering), any())).thenReturn(offering);
        when(a.refreshManager.importNetwork(eq(owner), anyLong(), any(), eq("vmbr1"))).thenReturn(network);
        when(network.getUuid()).thenReturn("network-uuid");
        CallContext.register(mock(User.class), mock(Account.class));
    }
    @After public void cleanup() { CallContext.unregister(); }

    private NativeInstanceAdoption.Request request(Integer cpuSpeed) {
        return new NativeInstanceAdoption.Request(10L, 105, 17L, 202L, null, null, null, cpuSpeed, true);
    }
    private void rejected(Integer cpuSpeed, String expected) {
        try { a.adopt(request(cpuSpeed)); fail("expected rejection: " + expected); }
        catch (IllegalArgumentException e) { assertTrue(e.getMessage(), e.getMessage().contains(expected)); }
    }

    @Test public void dryRunReportsThePlanOnTheHostOfTheCurrentNodeAndWritesNothing() {
        ExternalAdoptionResponse plan = a.adopt(request(500));
        assertTrue(plan.isDryRun());
        assertNull(plan.getVmUuid());
        assertEquals(List.of("net0 02:00:00:00:00:01 on network-uuid"), plan.getNics());
        verifyNoInteractions(a.userVmService, a.networkOrchestrator, a.verifier);
    }

    @Test public void refusesWhatItCannotRecordExactly() {
        rejected(null, "pass cpuspeed");
        when(a.refreshManager.importNetwork(eq(owner), anyLong(), any(), eq("vmbr1"))).thenReturn(null);
        rejected(500, "has no single matching L2 network");
        when(a.refreshManager.importNetwork(eq(owner), anyLong(), any(), eq("vmbr1"))).thenReturn(network);
        when(template.getFormat()).thenReturn(ImageFormat.QCOW2);
        rejected(500, "an External template is required");
        when(template.getFormat()).thenReturn(ImageFormat.EXTERNAL);
        when(offering.isDynamic()).thenReturn(false); when(offering.getCpu()).thenReturn(1); when(offering.getRamSize()).thenReturn(768);
        rejected(500, "use a custom offering");
        when(offering.isDynamic()).thenReturn(true); when(offering.getCpu()).thenReturn(null); when(offering.getRamSize()).thenReturn(null);
        when(a.extensionsManager.getExternalAccessDetails(host2, null)).thenReturn(Map.of("host", Map.of("proxmox_cluster", "lab", "node", "pve2", "network_bridge", "vmbr1")));
        rejected(500, "native adoption status and lifecycle are not enabled");
        when(a.hostDao.listByClusterAndHypervisorType(20L, HypervisorType.External)).thenReturn(List.of(host1));
        rejected(500, "no CloudStack host of this cluster maps provider node pve2");
        when(answer.getResult()).thenReturn(false);
        when(answer.getDetails()).thenReturn("Exit code: 1, Output: {\"status\":\"error\",\"error\":\"Instance configuration has pending changes\"}");
        rejected(500, "cannot be read: Instance configuration has pending changes");
    }

    @Test public void refusesAGuestThatCloudStackAlreadyHolds() {
        VMInstanceDetailVO row = mock(VMInstanceDetailVO.class); when(row.getValue()).thenReturn("105"); when(row.getResourceId()).thenReturn(7L);
        when(a.detailsDao.findDetails("External:proxmox_vmid")).thenReturn(List.of(row));
        VMInstanceVO existing = mock(VMInstanceVO.class); when(a.vmDao.findById(7L)).thenReturn(existing);
        when(existing.getHypervisorType()).thenReturn(HypervisorType.External); when(existing.getUuid()).thenReturn("existing-uuid");
        when(a.vmManager.findClusterAndHostIdForVm(existing, false)).thenReturn(new Pair<>(20L, 10L));
        rejected(500, "already belongs to CloudStack instance existing-uuid");
        // The same VMID in another provider cluster is a different guest.
        HostVO other = mock(HostVO.class); when(a.hostDao.findById(12L)).thenReturn(other);
        when(a.extensionsManager.getExternalAccessDetails(other, null)).thenReturn(Map.of("host", Map.of("proxmox_cluster", "other")));
        when(a.vmManager.findClusterAndHostIdForVm(existing, false)).thenReturn(new Pair<>(21L, 12L));
        assertFalse(a.adopt(request(500)).getNics().isEmpty());
        when(a.vmDao.findVMByInstanceName("oob-guest")).thenReturn(existing);
        rejected(500, "is the name of a CloudStack instance");
    }

    @Test public void manifestIsCanonicalAndAcceptedByTheRegistryAndTheVerifier() throws Exception {
        ExternalVmConfiguration snapshot = ExternalVmConfiguration.parse(SNAP);
        Map<ExternalVmConfiguration.Nic, NetworkVO> nics = new LinkedHashMap<>();
        nics.put(snapshot.nics.get(0), network);
        String manifest = NativeInstanceAdoption.manifest(snapshot, 105, owner, DOMAIN, nics);
        assertEquals("{\"cpus\":2,\"memory_mib\":768,\"name\":\"oob-guest\",\"networks\":[{\"bridge\":\"vmbr1\",\"cloudstack_network_id\":\"network-uuid\","
                + "\"device\":\"net0\",\"mac\":\"02:00:00:00:00:01\",\"tag\":100}],\"ownership\":{\"account\":\"admin\",\"domain_id\":\"" + DOMAIN + "\","
                + "\"project_id\":null},\"placement\":{\"cluster\":\"lab\",\"node\":\"pve2\"},\"status\":\"running\",\"vmid\":105}", manifest);
        AdoptionRegistry.manifest(manifest, NativeInstanceAdoption.sha256(manifest), "lab", "pve2", 105);
    }
}
