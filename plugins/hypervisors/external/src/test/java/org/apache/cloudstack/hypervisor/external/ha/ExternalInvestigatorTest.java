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
import static org.mockito.Mockito.when;

import org.junit.Test;

import com.cloud.ha.Investigator;
import com.cloud.host.Host;
import com.cloud.host.Status;
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

    @Test
    public void externalHostIsNeverReportedDown() {
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
