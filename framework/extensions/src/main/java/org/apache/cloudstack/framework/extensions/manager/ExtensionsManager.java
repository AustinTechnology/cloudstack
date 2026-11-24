//
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
//

package org.apache.cloudstack.framework.extensions.manager;


import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.response.ExtensionCustomActionResponse;
import org.apache.cloudstack.api.response.ExtensionResponse;
import org.apache.cloudstack.extension.CustomActionResultResponse;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionCustomAction;
import org.apache.cloudstack.extension.ExtensionResourceMap;
import org.apache.cloudstack.framework.extensions.api.AddCustomActionCmd;
import org.apache.cloudstack.framework.extensions.api.CreateExtensionCmd;
import org.apache.cloudstack.framework.extensions.api.DeleteCustomActionCmd;
import org.apache.cloudstack.framework.extensions.api.DeleteExtensionCmd;
import org.apache.cloudstack.framework.extensions.api.ListCustomActionCmd;
import org.apache.cloudstack.framework.extensions.api.ListExtensionsCmd;
import org.apache.cloudstack.framework.extensions.api.RegisterExtensionCmd;
import org.apache.cloudstack.framework.extensions.api.RunCustomActionCmd;
import org.apache.cloudstack.framework.extensions.api.UnregisterExtensionCmd;
import org.apache.cloudstack.framework.extensions.api.UpdateCustomActionCmd;
import org.apache.cloudstack.framework.extensions.api.UpdateExtensionCmd;
import org.apache.cloudstack.framework.extensions.api.UpdateRegisteredExtensionCmd;
import org.apache.cloudstack.framework.extensions.command.ExtensionServerActionBaseCommand;

import com.cloud.agent.api.Answer;
import com.cloud.host.Host;
import com.cloud.org.Cluster;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Manager;
import com.cloud.vm.VirtualMachine;

public interface ExtensionsManager extends Manager {

    String getExtensionsPath();

    Extension createExtension(CreateExtensionCmd cmd);

    boolean prepareExtensionPathAcrossServers(Extension extension);

    List<ExtensionResponse> listExtensions(ListExtensionsCmd cmd);

    boolean deleteExtension(DeleteExtensionCmd cmd);

    Extension unregisterExtensionWithResource(UnregisterExtensionCmd cmd);

    Extension updateRegisteredExtensionWithResource(UpdateRegisteredExtensionCmd cmd);

    Extension updateExtension(UpdateExtensionCmd cmd);

    Extension registerExtensionWithResource(RegisterExtensionCmd cmd);

    ExtensionResponse createExtensionResponse(Extension extension, EnumSet<ApiConstants.ExtensionDetails> viewDetails);

    ExtensionResourceMap registerExtensionWithCluster(Cluster cluster, Extension extension, Map<String, String> externalDetails);

    void unregisterExtensionWithCluster(Cluster cluster, Long extensionId);

    CustomActionResultResponse runCustomAction(RunCustomActionCmd cmd);

    ExtensionCustomAction addCustomAction(AddCustomActionCmd cmd);

    boolean deleteCustomAction(DeleteCustomActionCmd cmd);

    List<ExtensionCustomActionResponse> listCustomActions(ListCustomActionCmd cmd);

    ExtensionCustomAction updateCustomAction(UpdateCustomActionCmd cmd);

    ExtensionCustomActionResponse createCustomActionResponse(ExtensionCustomAction customAction);

    Map<String, Map<String, String>> getExternalAccessDetails(Host host, Map<String, String> vmDetails);

    String handleExtensionServerCommands(ExtensionServerActionBaseCommand cmd);

    Pair<Boolean, ExtensionResourceMap> extensionResourceMapDetailsNeedUpdate(final long resourceId,
                      final ExtensionResourceMap.ResourceType resourceType, final Map<String, String> details);

    void updateExtensionResourceMapDetails(final long extensionResourceMapId, final Map<String, String> details);

    Answer getInstanceConsole(VirtualMachine vm, Host host);

    /**
     * Asks the orchestrator extension of an External instance to turn its own HA on or off for the instance.
     * The extension receives the action {@link #SET_HA_ACTION} with the parameter "enabled".
     */
    Answer setInstanceHa(VirtualMachine vm, boolean enabled);

    String SET_HA_ACTION = "setha";

    /**
     * Asks the orchestrator extension of a stopped External instance to change its CPU count and memory.
     * The extension receives the action {@link #SCALE_ACTION} with the parameters "cpunumber" and "memory" (in MiB).
     */
    Answer scaleInstance(VirtualMachine vm, int cpuNumber, int memoryMib);

    String SCALE_ACTION = "scale";

    /**
     * Asks the orchestrator extension of an External instance to add it to the existing provider pool {@code pool}
     * (moving it out of its current pool), or with {@code remove} to take it out of that pool. The extension must
     * declare {@link org.apache.cloudstack.extension.ExtensionHelper#INSTANCE_POOL_SUPPORTED}.
     */
    Answer setInstancePool(VirtualMachine vm, String pool, boolean remove);

    String SET_POOL_ACTION = "setpool";

    /** Read-only configuration snapshot from the currently assigned extension. */
    Answer getInstanceConfig(VirtualMachine vm);

    String GET_CONFIG_ACTION = "getconfig";

    /**
     * Asks the orchestrator extension of an External instance to change one of its disks. The action is one of
     * {@link #DISK_ACTIONS}; the parameters are "device", "volume", "storage" and "sizegb" as the action needs them.
     * The extension must declare {@link org.apache.cloudstack.extension.ExtensionHelper#INSTANCE_STORAGE_SUPPORTED}.
     */
    Answer changeInstanceDisk(VirtualMachine vm, String action, java.util.Map<String, Object> parameters);

    java.util.List<String> DISK_ACTIONS = java.util.Arrays.asList("adddisk", "resizedisk", "deletedisk", "movedisk");

    /** A disk move copies the disk, so it is given longer than other actions: as long as any action may take. */
    int DISK_MOVE_WAIT_SECONDS = 6900;

    /**
     * Asks the orchestrator extension of an External instance to change what is in its CD drives. The action is one of
     * {@link #ISO_ACTIONS}; the parameters are "volume" and, to name one drive, "drive".
     * The extension must declare {@link org.apache.cloudstack.extension.ExtensionHelper#INSTANCE_ISO_SUPPORTED}.
     */
    Answer changeInstanceIso(VirtualMachine vm, String action, java.util.Map<String, Object> parameters);

    java.util.List<String> ISO_ACTIONS = java.util.Arrays.asList("attachiso", "detachiso");

    /**
     * Asks the orchestrator extension of an External instance to snapshot one of its disks where the disk is, or to
     * return the disk to such a snapshot. The action is one of {@link #DISK_SNAPSHOT_ACTIONS}; the parameters are
     * "device", "volume" and "snapshot", and what the extension needs to reach the storage. The extension must declare
     * {@link org.apache.cloudstack.extension.ExtensionHelper#INSTANCE_VOLUME_SNAPSHOT_SUPPORTED}.
     */
    Answer changeInstanceDiskSnapshot(VirtualMachine vm, String action, java.util.Map<String, Object> parameters);

    java.util.List<String> DISK_SNAPSHOT_ACTIONS = java.util.Arrays.asList("snapshotdisk", "revertdisksnapshot");
}
