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
package org.apache.cloudstack.hypervisor.external.ha;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Test;

import com.cloud.ha.Investigator;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.ExternalProvisioner;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.VirtualMachine;

public class ExternalInvestigatorTest {

    private final ExternalInvestigator investigator = new ExternalInvestigator();

    @Test
    public void externalInstanceIsNeverReportedDead() throws Investigator.UnknownVM {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.External);
        assertTrue(investigator.isVmAlive(vm, mock(Host.class)));
    }

    @Test(expected = Investigator.UnknownVM.class)
    public void otherInstancesAreLeftToOtherInvestigators() throws Investigator.UnknownVM {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        investigator.isVmAlive(vm, mock(Host.class));
    }

    private HostVO host(long id, Status status, String guid) {
        HostVO h = mock(HostVO.class);
        when(h.getId()).thenReturn(id);
        when(h.getStatus()).thenReturn(status);
        when(h.getType()).thenReturn(Host.Type.Routing);
        when(h.getGuid()).thenReturn(guid);
        when(h.getClusterId()).thenReturn(7L);
        when(h.getHypervisorType()).thenReturn(HypervisorType.External);
        return h;
    }

    private ExternalInvestigator withHealth(ExternalProvisioner provisioner, HostVO... cluster) {
        ExternalInvestigator inv = new ExternalInvestigator();
        inv.externalProvisioner = provisioner;
        inv.hostDao = mock(HostDao.class);
        when(inv.hostDao.findByClusterId(7L)).thenReturn(List.of(cluster));
        return inv;
    }

    @Test
    public void hostIsDownOnlyWhenUnreachableAndAPeerConfirms() {
        HostVO dead = host(9, Status.Up, "g9"), peer = host(8, Status.Up, "g8");
        ExternalProvisioner p = mock(ExternalProvisioner.class);
        when(p.getHostHealth("g9", null)).thenReturn("unknown");
        when(p.getHostHealth("g8", 9L)).thenReturn("down");
        assertEquals(Status.Down, withHealth(p, dead, peer).getHostAgentStatus(dead));
    }

    @Test
    public void unreachableHostThatPeersSeeUpIsDisconnected() {
        HostVO dead = host(9, Status.Up, "g9"), peer = host(8, Status.Up, "g8");
        ExternalProvisioner p = mock(ExternalProvisioner.class);
        when(p.getHostHealth("g9", null)).thenReturn("unknown");
        when(p.getHostHealth("g8", 9L)).thenReturn("up");
        assertEquals(Status.Disconnected, withHealth(p, dead, peer).getHostAgentStatus(dead));
    }

    @Test
    public void peersThatAreNotUpAreNotAsked() {
        HostVO dead = host(9, Status.Up, "g9"), peer = host(8, Status.Alert, "g8");
        ExternalProvisioner p = mock(ExternalProvisioner.class);
        when(p.getHostHealth("g9", null)).thenReturn("unknown");
        assertEquals(Status.Disconnected, withHealth(p, dead, peer).getHostAgentStatus(dead));
        verify(p, never()).getHostHealth("g8", 9L);
    }

    @Test
    public void reachableHostIsUpAndUnsupportedHealthKeepsOldBehaviour() {
        HostVO h = host(9, Status.Up, "g9");
        ExternalProvisioner p = mock(ExternalProvisioner.class);
        when(p.getHostHealth("g9", null)).thenReturn("up");
        assertEquals(Status.Up, withHealth(p, h).getHostAgentStatus(h));
        when(p.getHostHealth("g9", null)).thenReturn(null);
        assertEquals(Status.Disconnected, withHealth(p, h).getHostAgentStatus(h));
    }

    @Test
    public void externalHostWithoutHealthSupportIsNeverReportedDown() {
        Host host = mock(Host.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.External);
        assertEquals(Status.Disconnected, investigator.getHostAgentStatus(host));
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        assertNull(investigator.getHostAgentStatus(host));
    }

    @Test
    public void fencingIsLeftToTheOrchestrator() {
        assertNull(new ExternalFencer().fenceOff(mock(VirtualMachine.class), mock(Host.class)));
    }
}
