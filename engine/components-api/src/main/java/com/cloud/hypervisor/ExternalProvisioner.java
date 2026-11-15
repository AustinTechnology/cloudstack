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
package com.cloud.hypervisor;

import java.util.Map;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CreateVMSnapshotAnswer;
import com.cloud.agent.api.CreateVMSnapshotCommand;
import com.cloud.agent.api.DeleteVMSnapshotAnswer;
import com.cloud.agent.api.DeleteVMSnapshotCommand;
import com.cloud.agent.api.RevertToVMSnapshotAnswer;
import com.cloud.agent.api.RevertToVMSnapshotCommand;
import com.cloud.agent.api.VMSnapshotBaseCommand;
import com.cloud.vm.VirtualMachine;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.GetExternalConsoleAnswer;
import com.cloud.agent.api.GetExternalConsoleCommand;
import com.cloud.agent.api.GetVmIpAddressCommand;
import com.cloud.agent.api.HostVmStateReportEntry;
import com.cloud.agent.api.PrepareExternalProvisioningAnswer;
import com.cloud.agent.api.PrepareExternalProvisioningCommand;
import com.cloud.agent.api.RebootAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.RunCustomActionAnswer;
import com.cloud.agent.api.RunCustomActionCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StopAnswer;
import com.cloud.agent.api.StopCommand;
import com.cloud.utils.component.Manager;

public interface ExternalProvisioner extends Manager {

    String getExtensionsPath();

    String getExtensionPath(String relativePath);

    String getChecksumForExtensionPath(String extensionName, String relativePath);

    void prepareExtensionPath(String extensionName, boolean userDefined, String extensionRelativePath);

    void cleanupExtensionPath(String extensionName, String extensionRelativePath);

    void cleanupExtensionData(String extensionName, int olderThanDays, boolean cleanupDirectory);

    PrepareExternalProvisioningAnswer prepareExternalProvisioning(String hostGuid, String extensionName, String extensionRelativePath, PrepareExternalProvisioningCommand cmd);

    StartAnswer startInstance(String hostGuid, String extensionName, String extensionRelativePath, StartCommand cmd);

    StopAnswer stopInstance(String hostGuid, String extensionName, String extensionRelativePath, StopCommand cmd);

    RebootAnswer rebootInstance(String hostGuid, String extensionName, String extensionRelativePath, RebootCommand cmd);

    StopAnswer expungeInstance(String hostGuid, String extensionName, String extensionRelativePath, StopCommand cmd);

    Map<String, HostVmStateReportEntry> getHostVmStateReport(long hostId, String extensionName, String extensionRelativePath);

    GetExternalConsoleAnswer getInstanceConsole(String hostGuid, String extensionName, String extensionRelativePath, GetExternalConsoleCommand cmd);

    RunCustomActionAnswer runCustomAction(String hostGuid, String extensionName, String extensionRelativePath, RunCustomActionCommand cmd);

    CheckVirtualMachineAnswer checkInstance(String hostGuid, String extensionName, String extensionRelativePath, CheckVirtualMachineCommand cmd);

    Answer getInstanceIpAddress(String hostGuid, String extensionName, String extensionRelativePath, GetVmIpAddressCommand cmd);

    PlugNicAnswer plugInstanceNic(String hostGuid, String extensionName, String extensionRelativePath, PlugNicCommand cmd);

    UnPlugNicAnswer unplugInstanceNic(String hostGuid, String extensionName, String extensionRelativePath, UnPlugNicCommand cmd);

    /** Loads the ISO the command names into a CD drive of a running instance. */
    Answer attachInstanceIso(String hostGuid, String extensionName, String extensionRelativePath, org.apache.cloudstack.storage.command.AttachCommand cmd);

    /** Empties the CD drive of a running instance that holds the ISO the command names. */
    Answer detachInstanceIso(String hostGuid, String extensionName, String extensionRelativePath, org.apache.cloudstack.storage.command.DettachCommand cmd);

    /**
     * Takes, reverts to or deletes a snapshot of a whole instance, as the stock Instance Snapshot command asks.
     * The answer is of the type that command is answered with.
     */
    Answer changeInstanceSnapshot(String hostGuid, String extensionName, String extensionRelativePath, VMSnapshotBaseCommand cmd);

    /** The answer of the type the stock Instance Snapshot strategy casts to, for a result that changed nothing it reads. */
    static Answer snapshotAnswer(VMSnapshotBaseCommand cmd, boolean result, String details, VirtualMachine.PowerState power) {
        if (cmd instanceof CreateVMSnapshotCommand) {
            return result ? new CreateVMSnapshotAnswer((CreateVMSnapshotCommand) cmd, cmd.getTarget(), cmd.getVolumeTOs())
                    : new CreateVMSnapshotAnswer((CreateVMSnapshotCommand) cmd, false, details);
        }
        if (cmd instanceof RevertToVMSnapshotCommand) {
            return result ? new RevertToVMSnapshotAnswer((RevertToVMSnapshotCommand) cmd, cmd.getVolumeTOs(), power)
                    : new RevertToVMSnapshotAnswer((RevertToVMSnapshotCommand) cmd, false, details);
        }
        if (cmd instanceof DeleteVMSnapshotCommand) {
            return result ? new DeleteVMSnapshotAnswer((DeleteVMSnapshotCommand) cmd, cmd.getVolumeTOs())
                    : new DeleteVMSnapshotAnswer((DeleteVMSnapshotCommand) cmd, false, details);
        }
        return new Answer(cmd, false, "Unsupported Instance Snapshot command");
    }
}
