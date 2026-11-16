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

package org.apache.cloudstack.hypervisor.external.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import org.apache.cloudstack.extension.Extension;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;

import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.GetVmIpAddressCommand;
import com.cloud.agent.api.GetVmDiskStatsCommand;
import com.cloud.agent.api.UnsupportedAnswer;
import com.cloud.hypervisor.ExternalProvisioner;
import com.cloud.vm.VirtualMachine;
import org.apache.logging.log4j.Logger;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class ExternalResourceTest {
    private ExternalResource externalResource;
    private Logger logger;

    @Before
    public void setUp() {
        externalResource = spy(new ExternalResource());
        logger = mock(Logger.class);
        ReflectionTestUtils.setField(externalResource, "logger", logger);
    }

    @Test
    public void isExtensionDisconnected_WhenExtensionNameAndPathAreBlank_ReturnsTrue() {
        ReflectionTestUtils.setField(externalResource, "extensionName", "");
        ReflectionTestUtils.setField(externalResource, "extensionRelativePath", "");
        boolean result = externalResource.isExtensionDisconnected();
        assertTrue(result);
    }

    @Test
    public void isExtensionDisconnected_WhenExtensionNameAndPathAreNotBlank_ReturnsFalse() {
        ReflectionTestUtils.setField(externalResource, "extensionName", "testExtension");
        ReflectionTestUtils.setField(externalResource, "extensionRelativePath", "path");
        boolean result = externalResource.isExtensionDisconnected();
        assertFalse(result);
    }

    @Test
    public void isExtensionNotEnabled_WhenExtensionStateIsEnabled_ReturnsFalse() {
        ReflectionTestUtils.setField(externalResource, "extensionState", Extension.State.Enabled);
        boolean result = externalResource.isExtensionNotEnabled();
        assertFalse(result);
    }

    @Test
    public void isExtensionNotEnabled_WhenExtensionStateIsNotEnabled_ReturnsTrue() {
        ReflectionTestUtils.setField(externalResource, "extensionState", Extension.State.Disabled);
        boolean result = externalResource.isExtensionNotEnabled();

        assertTrue(result);
    }

    @Test
    public void logAndGetExtensionNotConnectedOrDisabledError_WhenExtensionDisconnected_LogsErrorAndReturnsMessage() {
        ReflectionTestUtils.setField(externalResource, "extensionName", null);
        ReflectionTestUtils.setField(externalResource, "extensionRelativePath", null);
        String result = externalResource.logAndGetExtensionNotConnectedOrDisabledError();
        verify(logger).error("Extension not connected to host: {}", externalResource.getName());
        assertEquals("Extension not connected", result);
    }

    @Test
    public void logAndGetExtensionNotConnectedOrDisabledError_WhenExtensionDisabled_LogsErrorAndReturnsMessage() {
        ReflectionTestUtils.setField(externalResource, "extensionName", "testExtension");
        ReflectionTestUtils.setField(externalResource, "extensionRelativePath", "path");
        ReflectionTestUtils.setField(externalResource, "extensionState", Extension.State.Disabled);
        String result = externalResource.logAndGetExtensionNotConnectedOrDisabledError();
        verify(logger).error("Extension: {} connected to host: {} is not in Enabled state", "testExtension", externalResource.getName());
        assertEquals("Extension is disabled", result);
    }

    private ExternalProvisioner readyResourceWithProvisioner() {
        ExternalProvisioner provisioner = mock(ExternalProvisioner.class);
        ReflectionTestUtils.setField(externalResource, "externalProvisioner", provisioner);
        ReflectionTestUtils.setField(externalResource, "extensionName", "testExtension");
        ReflectionTestUtils.setField(externalResource, "extensionRelativePath", "path");
        ReflectionTestUtils.setField(externalResource, "extensionState", Extension.State.Enabled);
        ReflectionTestUtils.setField(externalResource, "extensionPathReady", true);
        ReflectionTestUtils.setField(externalResource, "guid", "External:guid");
        return provisioner;
    }

    @Test
    public void metricsUnavailablePreservesLegacyAnswers() {
        ExternalProvisioner provisioner = readyResourceWithProvisioner();
        com.cloud.agent.api.GetVmStatsCommand vm = new com.cloud.agent.api.GetVmStatsCommand(java.util.List.of("vm"), "External:guid", "host");
        Answer answer = externalResource.executeRequest(vm);
        assertFalse(answer.getResult());
        assertEquals("Not supported", answer.getDetails());
        assertEquals(null, externalResource.execute(new com.cloud.agent.api.GetHostStatsCommand("External:guid", "host", 1)).getHostStats());
        when(provisioner.getMetrics(any(), any(), any(), org.mockito.ArgumentMatchers.eq(true))).thenThrow(new RuntimeException("failure"));
        assertEquals("Not supported", externalResource.executeRequest(vm).getDetails());
    }

    private String vmMetrics(int time, long rx, long tx, long read, long write, String ios) {
        return "{\"status\":\"success\",\"timestamp\":" + time + ",\"vms\":{\"vm\":{" +
                "\"cpus\":2,\"cpu_utilization\":25,\"memory_kb\":4096,\"memory_free_kb\":null,\"memory_target_kb\":2048," +
                "\"network_rx_bytes\":" + rx + ",\"network_tx_bytes\":" + tx + ",\"disk_read_bytes\":" + read +
                ",\"disk_write_bytes\":" + write + ",\"disk_read_ios\":" + ios + ",\"disk_write_ios\":null}," +
                "\"other\":{}}}";
    }

    @Test
    public void instanceMetricsUseDeltasResetAndOnlyRequestedNames() {
        ExternalProvisioner p = readyResourceWithProvisioner();
        when(p.getMetrics(any(), any(), any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(vmMetrics(1, 1024, 2048, 4096, 8192, "10"),
                        vmMetrics(2, 3072, 3072, 5120, 10240, "15"),
                        vmMetrics(3, 1, 4096, 1024, 11264, "2"));
        com.cloud.agent.api.GetVmStatsCommand cmd = new com.cloud.agent.api.GetVmStatsCommand(java.util.List.of("vm", "absent"), "External:guid", "host");
        com.cloud.agent.api.VmStatsEntry first = ((com.cloud.agent.api.GetVmStatsAnswer) externalResource.executeRequest(cmd)).getVmStatsMap().get("vm");
        assertEquals(0, first.getNetworkReadKBs(), 0);
        assertEquals(25, first.getCPUUtilization(), 0);
        assertEquals(4096, first.getMemoryKBs(), 0);
        assertEquals(-1, first.getIntFreeMemoryKBs(), 0);
        assertEquals(2048, first.getTargetMemoryKBs(), 0);
        com.cloud.agent.api.GetVmStatsAnswer second = (com.cloud.agent.api.GetVmStatsAnswer) externalResource.executeRequest(cmd);
        assertEquals(1, second.getVmStatsMap().size());
        com.cloud.agent.api.VmStatsEntry s = second.getVmStatsMap().get("vm");
        assertEquals(2, s.getNetworkReadKBs(), 0);
        assertEquals(1, s.getNetworkWriteKBs(), 0);
        assertEquals(1, s.getDiskReadKBs(), 0);
        assertEquals(2, s.getDiskWriteKBs(), 0);
        assertEquals(5, s.getDiskReadIOs(), 0);
        assertEquals(0, s.getDiskWriteIOs(), 0);
        s = ((com.cloud.agent.api.GetVmStatsAnswer) externalResource.executeRequest(cmd)).getVmStatsMap().get("vm");
        assertEquals(0, s.getNetworkReadKBs(), 0);
        assertEquals(0, s.getDiskReadIOs(), 0);
        assertEquals(1, s.getNetworkWriteKBs(), 0);
    }

    @Test
    public void outOfOrderSamplesFailWithoutReplacingBaseline() {
        ExternalProvisioner p = readyResourceWithProvisioner();
        when(p.getMetrics(any(), any(), any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(vmMetrics(2, 1024, 0, 0, 0, "null"), vmMetrics(1, 2048, 0, 0, 0, "null"), vmMetrics(3, 3072, 0, 0, 0, "null"));
        com.cloud.agent.api.GetVmStatsCommand cmd = new com.cloud.agent.api.GetVmStatsCommand(java.util.List.of("vm"), "External:guid", "host");
        externalResource.executeRequest(cmd);
        assertFalse(externalResource.executeRequest(cmd).getResult());
        assertEquals(2, ((com.cloud.agent.api.GetVmStatsAnswer) externalResource.executeRequest(cmd)).getVmStatsMap().get("vm").getNetworkReadKBs(), 0);
    }

    @Test
    public void hostMetricsAreCumulativeKiBAndMalformedResponseIsNull() {
        ExternalProvisioner p = readyResourceWithProvisioner();
        when(p.getMetrics(any(), any(), any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn("{\"status\":\"success\",\"timestamp\":1,\"cpus\":4,\"cpu_utilization\":40,\"total_memory_kb\":8192,\"free_memory_kb\":2048,\"network_rx_bytes\":4096,\"network_tx_bytes\":8192,\"load_average\":null}", "invalid");
        com.cloud.agent.api.GetHostStatsCommand cmd = new com.cloud.agent.api.GetHostStatsCommand("External:guid", "host", 1);
        com.cloud.host.HostStats s = externalResource.execute(cmd).getHostStats();
        assertEquals(40, s.getCpuUtilization(), 0);
        assertEquals(8192, s.getTotalMemoryKBs(), 0);
        assertEquals(2048, s.getFreeMemoryKBs(), 0);
        assertEquals(4, s.getNetworkReadKBs(), 0);
        assertEquals(8, s.getNetworkWriteKBs(), 0);
        assertEquals(-1, s.getLoadAverage(), 0);
        assertEquals(null, externalResource.execute(cmd).getHostStats());
    }

    @Test
    public void nicCommandsHaveTypedAnswersAndUseTheProvisioner() {
        ExternalProvisioner provisioner = readyResourceWithProvisioner();
        PlugNicCommand plug = new PlugNicCommand(null, "i-2-1-VM", VirtualMachine.Type.User);
        UnPlugNicCommand unplug = new UnPlugNicCommand(null, "i-2-1-VM");
        when(provisioner.plugInstanceNic("External:guid", "testExtension", "path", plug))
                .thenReturn(new PlugNicAnswer(plug, true, null));
        when(provisioner.unplugInstanceNic("External:guid", "testExtension", "path", unplug))
                .thenReturn(new UnPlugNicAnswer(unplug, true, null));
        assertTrue(externalResource.executeRequest(plug) instanceof PlugNicAnswer);
        assertTrue(externalResource.executeRequest(unplug) instanceof UnPlugNicAnswer);
    }

    @Test
    public void snapshotCommandsUseTheProvisionerAndAlwaysHaveTheAnswerTheStrategyCastsTo() {
        ExternalProvisioner provisioner = readyResourceWithProvisioner();
        com.cloud.agent.api.VMSnapshotTO target = new com.cloud.agent.api.VMSnapshotTO(1L, "i-2-1-VM_VS_1", com.cloud.vm.snapshot.VMSnapshot.Type.Disk, null, null, false, null, true);
        com.cloud.agent.api.RevertToVMSnapshotCommand revert = new com.cloud.agent.api.RevertToVMSnapshotCommand("i-2-1-VM", "uuid", target, java.util.List.of(), "Other");
        com.cloud.agent.api.CreateVMSnapshotCommand create = new com.cloud.agent.api.CreateVMSnapshotCommand("i-2-1-VM", "uuid", target, java.util.List.of(), "Other");
        com.cloud.agent.api.DeleteVMSnapshotCommand delete = new com.cloud.agent.api.DeleteVMSnapshotCommand("i-2-1-VM", target, java.util.List.of(), "Other");
        when(provisioner.changeInstanceSnapshot(any(), any(), any(), org.mockito.ArgumentMatchers.same(create)))
                .thenReturn(ExternalProvisioner.snapshotAnswer(create, true, null, null));
        assertTrue(((com.cloud.agent.api.CreateVMSnapshotAnswer) externalResource.executeRequest(create)).getResult());

        when(provisioner.changeInstanceSnapshot(any(), any(), any(), org.mockito.ArgumentMatchers.same(revert))).thenThrow(new IllegalArgumentException("lost result"));
        Answer answer = externalResource.executeRequest(revert);
        assertTrue(answer instanceof com.cloud.agent.api.RevertToVMSnapshotAnswer);
        assertFalse(answer.getResult());

        ReflectionTestUtils.setField(externalResource, "extensionState", Extension.State.Disabled);
        answer = externalResource.executeRequest(delete);
        assertTrue(answer instanceof com.cloud.agent.api.DeleteVMSnapshotAnswer);
        assertFalse(answer.getResult());
        verify(provisioner, never()).changeInstanceSnapshot(any(), any(), any(), org.mockito.ArgumentMatchers.same(delete));
    }

    @Test
    public void persistentNetworkSetupHasTypedUnsupportedAnswer() {
        com.cloud.agent.api.SetupPersistentNetworkCommand cmd = new com.cloud.agent.api.SetupPersistentNetworkCommand(null);
        Answer answer = externalResource.executeRequest(cmd);
        assertTrue(answer instanceof com.cloud.agent.api.SetupPersistentNetworkAnswer);
        assertFalse(answer.getResult());
    }

    @Test
    public void nicDispatchExceptionsReturnTypedUnknownAnswers() {
        ExternalProvisioner provisioner = readyResourceWithProvisioner();
        PlugNicCommand plug = new PlugNicCommand(null, "i-2-1-VM", VirtualMachine.Type.User);
        when(provisioner.plugInstanceNic("External:guid", "testExtension", "path", plug))
                .thenThrow(new IllegalArgumentException("lost result"));
        Answer answer = externalResource.executeRequest(plug);
        assertTrue(answer instanceof PlugNicAnswer);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().contains("NIC_OUTCOME_UNKNOWN"));
    }

    @Test
    public void disabledExtensionRejectsNicCommandsWithoutDispatch() {
        ExternalProvisioner provisioner = readyResourceWithProvisioner();
        ReflectionTestUtils.setField(externalResource, "extensionState", Extension.State.Disabled);
        assertFalse(externalResource.executeRequest(new PlugNicCommand(null, "i-2-1-VM", VirtualMachine.Type.User)).getResult());
        assertFalse(externalResource.executeRequest(new UnPlugNicCommand(null, "i-2-1-VM")).getResult());
        verify(provisioner, never()).plugInstanceNic(any(), any(), any(), any());
        verify(provisioner, never()).unplugInstanceNic(any(), any(), any(), any());
    }

    @Test
    public void executeRequest_CheckVirtualMachineCommand_IsAnsweredByTheProvisioner() {
        ExternalProvisioner provisioner = readyResourceWithProvisioner();
        CheckVirtualMachineCommand cmd = new CheckVirtualMachineCommand("i-2-1-VM");
        when(provisioner.checkInstance("External:guid", "testExtension", "path", cmd))
                .thenReturn(new CheckVirtualMachineAnswer(cmd, VirtualMachine.PowerState.PowerOn, null));
        Answer answer = externalResource.executeRequest(cmd);
        assertTrue(answer.getResult());
        assertEquals(VirtualMachine.PowerState.PowerOn, ((CheckVirtualMachineAnswer)answer).getState());
    }

    @Test
    public void executeRequest_CheckVirtualMachineCommand_FailsWhenExtensionDisabled() {
        ExternalProvisioner provisioner = readyResourceWithProvisioner();
        ReflectionTestUtils.setField(externalResource, "extensionState", Extension.State.Disabled);
        Answer answer = externalResource.executeRequest(new CheckVirtualMachineCommand("i-2-1-VM"));
        assertFalse(answer.getResult());
        assertTrue(answer instanceof CheckVirtualMachineAnswer);
        verify(provisioner, never()).checkInstance(any(), any(), any(), any());
    }

    @Test
    public void executeRequest_GetVmIpAddressCommand_IsAnsweredByTheProvisioner() {
        ExternalProvisioner provisioner = readyResourceWithProvisioner();
        GetVmIpAddressCommand cmd = new GetVmIpAddressCommand("i-2-1-VM", "10.0.0.0/24", false, "02:00:00:00:00:01");
        when(provisioner.getInstanceIpAddress("External:guid", "testExtension", "path", cmd))
                .thenReturn(new Answer(cmd, true, "10.0.0.5"));
        Answer answer = externalResource.executeRequest(cmd);
        assertTrue(answer.getResult());
        assertEquals("10.0.0.5", answer.getDetails());
    }

    @Test
    public void executeRequest_CommandWithoutHandler_IsUnsupportedAndNotSentToTheExtension() {
        ExternalProvisioner provisioner = readyResourceWithProvisioner();
        Answer answer = externalResource.executeRequest(mock(GetVmDiskStatsCommand.class));
        assertFalse(answer.getResult());
        assertTrue(answer instanceof UnsupportedAnswer);
        verify(provisioner, never()).runCustomAction(any(), any(), any(), any());
    }

    private void connected(ExternalProvisioner provisioner) {
        ReflectionTestUtils.setField(externalResource, "extensionName", "ext");
        ReflectionTestUtils.setField(externalResource, "extensionRelativePath", "ext/ext.py");
        ReflectionTestUtils.setField(externalResource, "extensionState", Extension.State.Enabled);
        ReflectionTestUtils.setField(externalResource, "extensionPathReady", true);
        ReflectionTestUtils.setField(externalResource, "guid", "g1");
        ReflectionTestUtils.setField(externalResource, "externalProvisioner", provisioner);
    }

    @Test
    public void hostHealthDecidesCheckHealthAndReconnection() {
        ExternalProvisioner provisioner = mock(ExternalProvisioner.class);
        connected(provisioner);
        com.cloud.agent.api.CheckHealthCommand check = new com.cloud.agent.api.CheckHealthCommand();
        when(provisioner.getHostHealth("g1", null)).thenReturn("unknown");
        assertFalse(externalResource.executeRequest(check).getResult());
        assertEquals(null, externalResource.initialize());
        when(provisioner.getHostHealth("g1", null)).thenReturn("up");
        assertTrue(externalResource.executeRequest(check).getResult());
        // Without host health support the old behaviour stands.
        when(provisioner.getHostHealth("g1", null)).thenReturn(null);
        assertTrue(externalResource.executeRequest(check).getResult());
    }

    @Test
    public void externalHostsDoNotClaimPingTests() {
        connected(mock(ExternalProvisioner.class));
        assertFalse(externalResource.executeRequest(new com.cloud.agent.api.PingTestCommand("External")).getResult());
    }

    @Test
    public void startupReportsTheNodeCapacityWhenTheExtensionReportsHostStatistics() {
        ExternalProvisioner provisioner = mock(ExternalProvisioner.class);
        connected(provisioner);
        when(provisioner.getHostHealth("g1", null)).thenReturn("up");
        when(provisioner.getMetrics("g1", "ext", "ext/ext.py", false)).thenReturn("{\"status\":\"success\",\"timestamp\":1,"
                + "\"cpu_utilization\":5,\"cpus\":16,\"cpu_speed_mhz\":2100,\"cpu_sockets\":2,\"total_memory_kb\":67108864,\"free_memory_kb\":1}");
        com.cloud.agent.api.StartupRoutingCommand startup = (com.cloud.agent.api.StartupRoutingCommand) externalResource.initialize()[0];
        assertEquals(16, startup.getCpus());
        assertEquals(2100L, startup.getSpeed());
        assertEquals(64L << 30, startup.getMemory());
        assertEquals(Integer.valueOf(2), startup.getCpuSockets());
        when(provisioner.getMetrics("g1", "ext", "ext/ext.py", false)).thenReturn(null);
        startup = (com.cloud.agent.api.StartupRoutingCommand) externalResource.initialize()[0];
        assertEquals(0, startup.getCpus());
        assertEquals(0L, startup.getMemory());
    }
}
