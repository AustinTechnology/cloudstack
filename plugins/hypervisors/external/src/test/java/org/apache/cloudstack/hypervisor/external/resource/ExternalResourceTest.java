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
}
