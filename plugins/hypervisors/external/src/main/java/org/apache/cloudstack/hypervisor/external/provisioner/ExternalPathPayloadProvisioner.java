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
package org.apache.cloudstack.hypervisor.external.provisioner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.hypervisor.external.adoption.AdoptionRegistry;
import org.apache.cloudstack.hypervisor.external.storage.ExternalInstanceLock;
import org.apache.cloudstack.hypervisor.external.storage.ExternalIsoLibrary;
import org.apache.cloudstack.hypervisor.external.storage.ExternalVolumeOperations;
import org.apache.cloudstack.hypervisor.external.storage.ExternalVolumeSync;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.utils.security.DigestHelper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ObjectUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CreateVMSnapshotCommand;
import com.cloud.agent.api.DeleteVMSnapshotCommand;
import com.cloud.agent.api.RevertToVMSnapshotCommand;
import com.cloud.agent.api.VMSnapshotBaseCommand;
import com.cloud.agent.api.VMSnapshotTO;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
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
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.ExternalProvisioner;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.serializer.GsonHelper;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.template.TemplateManager;
import com.cloud.utils.FileUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.StringUtils;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.json.JsonMergeUtil;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.script.Script;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ExternalPathPayloadProvisioner extends ManagerBase implements ExternalProvisioner, PluggableService {

    public static final String BASE_EXTERNAL_PROVISIONER_SCRIPTS_DIR = "scripts/vm/hypervisor/external/provisioner";
    public static final String BASE_EXTERNAL_PROVISIONER_SHELL_SCRIPT =
            BASE_EXTERNAL_PROVISIONER_SCRIPTS_DIR + "/provisioner.sh";

    private static final String PROPERTIES_FILE = "server.properties";
    private static final String EXTENSIONS = "extensions";
    private static final String EXTENSIONS_DEPLOYMENT_MODE_NAME = "extensions.deployment.mode";
    private static final String EXTENSIONS_DIRECTORY_PROD = "/usr/share/cloudstack-management/extensions";
    private static final String EXTENSIONS_DATA_DIRECTORY_PROD = System.getProperty("user.home") + File.separator + EXTENSIONS;
    private static final String EXTENSIONS_DIRECTORY_DEV = EXTENSIONS;
    private static final String EXTENSIONS_DATA_DIRECTORY_DEV = "client/target/extensions-data";

    @Inject
    UserVmDao _uservmDao;

    @Inject
    VMInstanceDao vmInstanceDao;

    @Inject
    HostDao hostDao;

    @Inject
    HypervisorGuruManager hypervisorGuruManager;

    @Inject
    ExtensionsManager extensionsManager;

    @Inject
    ExtensionHelper extensionHelper;

    @Inject
    ExternalIsoLibrary isoLibrary;

    @Inject
    ExternalVolumeSync volumeSync;

    @Inject
    ExternalInstanceLock instanceLock;

    @Inject
    VMSnapshotDao vmSnapshotDao;

    @Inject
    VMTemplateDao templateDao;

    private static final AtomicReference<Properties> propertiesRef = new AtomicReference<>();
    private String extensionsDirectory;
    private String extensionsDataDirectory;
    private ExecutorService payloadCleanupExecutor;
    private ScheduledExecutorService payloadCleanupScheduler;
    private static final List<String> TRIVIAL_ACTIONS = Arrays.asList(
            // Read-only actions that run every minute or so: their payload (with the cluster's credentials) is
            // removed as soon as they answer instead of being kept for a day.
            "status", "statuses", "getipaddress", "health", "hoststats", "vmstats", "liststorage"
    );

    // This is deliberately a status-only switch, not a lifecycle cutover.
    // Lifecycle callbacks continue to use their existing authorization path.
    private static final String NATIVE_ADOPTION_STATUS = "native_adoption_status";
    private static final String NATIVE_ADOPTION_LIFECYCLE = "native_adoption_lifecycle";
    private static final String NATIVE_CONTEXT = "native_adoption_context";

    protected AdoptionRegistry adoptionRegistry() { return new AdoptionRegistry(); }

    private static String field(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static JsonObject object(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : new JsonObject();
    }

    private static String nativeOperation(String action) {
        switch (action) {
            case "getconsole": return "console";
            case "CreateSnapshot": return "create_snapshot";
            case "RestoreSnapshot": return "restore_snapshot";
            case "DeleteSnapshot": return "delete_snapshot";
            case "start": case "stop": case "reboot": case "setha": case "scale": case "plugnic": case "unplugnic": return action;
            case "adddisk": case "resizedisk": case "deletedisk": case "movedisk": return action;
            case "attachiso": case "detachiso": return action;
            case "snapshotdisk": case "revertdisksnapshot": return action;
            case "delete": return action;
            case "createsnapshot": return "create_snapshot";
            case "revertsnapshot": return "restore_snapshot";
            case "deletesnapshot": return "delete_snapshot";
            case "status": case "getconfig": case "getipaddress": case "ListSnapshots": return "";
            // Pool membership changes no guest configuration or power state, so it needs no lifecycle lease.
            case "setpool": return "";
            default: throw new IllegalArgumentException("Native adoption does not support action: " + action);
        }
    }

    private static final class NativeOperation {
        final AdoptionRegistry registry;
        final AdoptionRegistry.Identity identity;
        final AdoptionRegistry.Lease lease;
        final boolean retired;
        NativeOperation(AdoptionRegistry registry, AdoptionRegistry.Identity identity, AdoptionRegistry.Lease lease) {
            this(registry, identity, lease, false);
        }
        NativeOperation(AdoptionRegistry registry, AdoptionRegistry.Identity identity, AdoptionRegistry.Lease lease, boolean retired) {
            this.registry = registry; this.identity = identity; this.lease = lease; this.retired = retired;
        }
    }

    /** The extension exits with this status when a native adopted action failed and it knows
     * that the provider did not change: nothing was sent, or the one task that ran reported failure. */
    static final int NATIVE_KNOWN_FAILURE = 90;

    /** Derive authorization only from the manager's callback identity and VM details. */
    protected NativeOperation prepareNativeOperation(String action, Map<String, Object> payload, int wait) {
        JsonObject root = GsonHelper.getGson().toJsonTree(payload).getAsJsonObject();
        JsonObject details = object(root, "externaldetails");
        JsonObject host = object(details, "host");
        String enabled = field(host, NATIVE_ADOPTION_LIFECYCLE);
        if (enabled.isEmpty() || "false".equals(enabled)) { return null; }
        if (!"true".equals(enabled)) { throw new IllegalArgumentException("Invalid native adoption lifecycle setting"); }
        JsonObject vm = object(details, "virtualmachine");
        boolean adopted = "true".equals(field(vm, "adopt_existing"));
        for (String key : Arrays.asList("adopt_claim_id", "adopt_claim_generation", "adopt_manifest_sha256",
                "adopt_manifest_json", "adopt_execution_plan_sha256", "adopt_ip_overrides_json")) {
            adopted |= !field(vm, key).isEmpty();
        }
        if (!adopted) { return null; }
        String operation = nativeOperation(action);
        if (wait <= 0 || wait > 6900) { throw new IllegalArgumentException("Native adoption wait must be 1..6900 seconds"); }
        String cluster = field(host, "proxmox_cluster");
        String vmCluster = field(vm, "proxmox_cluster");
        if (cluster.isEmpty() || (!vmCluster.isEmpty() && !cluster.equals(vmCluster))) {
            throw new IllegalArgumentException("Adoption cluster differs from actuator host cluster");
        }
        JsonObject toDetails = object(object(root, "cloudstack.vm.details"), "details");
        String vmid = "";
        for (JsonObject aliases : Arrays.asList(toDetails, vm)) {
            for (String key : Arrays.asList("proxmox_vmid", "External:proxmox_vmid")) {
                if (!aliases.has(key)) { continue; }
                String candidate = field(aliases, key);
                if (!candidate.matches("[1-9][0-9]*") || (!vmid.isEmpty() && !vmid.equals(candidate))) {
                    throw new IllegalArgumentException("Invalid or conflicting callback Proxmox VMID");
                }
                vmid = candidate;
            }
        }
        if (vmid.isEmpty()) { throw new IllegalArgumentException("Missing callback Proxmox VMID"); }
        String generation = field(vm, "adopt_claim_generation");
        if (!generation.matches("[1-9][0-9]*")) { throw new IllegalArgumentException("Invalid adoption generation"); }
        String manifest = field(vm, "adopt_manifest_json");
        JsonObject manifestObject = JsonParser.parseString(manifest).getAsJsonObject();
        String node = field(object(manifestObject, "placement"), "node");
        AdoptionRegistry.Identity identity = new AdoptionRegistry.Identity(field(vm, "adopt_claim_id"),
                Integer.parseInt(generation), cluster, Integer.parseInt(vmid), field(vm, "adopt_manifest_sha256"),
                field(root, "virtualmachineid"), field(root, "virtualmachinename"));
        // Reservation validates manifest bytes/hash, cluster, node, and actuator VMID.
        new AdoptionRegistry.Reservation(identity.claimId, identity.generation, identity.cluster, node,
                identity.vmid, identity.manifestHash, manifest);
        AdoptionRegistry registry = adoptionRegistry();
        AdoptionRegistry.Lease lease = null;
        // Expunge deletes an adopted instance at its orchestrator, as an ordinary one. Once that has
        // succeeded the claim is retiring, and a repeated expunge must send nothing: by then the
        // orchestrator may have given the VMID to another guest.
        if ("delete".equals(action) && "retiring".equals(registry.validatedState(identity))) {
            return new NativeOperation(registry, identity, null, true);
        }
        if (operation.isEmpty()) {
            if (!"managed".equals(registry.validatedState(identity))) { throw new IllegalStateException("Adoption is not managed"); }
        } else {
            lease = registry.acquire(identity, operation);
        }
        Map<String, Object> context = new HashMap<>();
        context.put("action", action); context.put("operation", operation);
        context.put("authorization_from_state", "managed");
        context.put("stored_state", lease == null ? "managed" : "operating");
        context.put("claim_id", identity.claimId); context.put("generation", identity.generation);
        context.put("cluster", identity.cluster); context.put("vmid", identity.vmid);
        context.put("manifest_sha256", identity.manifestHash); context.put("cloudstack_vm_ref", identity.vmUuid);
        context.put("cloudstack_instance_name", identity.instanceName);
        if (lease != null) {
            context.put("lease_id", lease.id); context.put("expires_epoch", lease.expiresAt.toInstant().getEpochSecond());
        }
        payload.put(NATIVE_CONTEXT, context);
        return new NativeOperation(registry, identity, lease);
    }

    protected Map<String, AdoptionRegistry.StatusBinding> nativeStatusBindings(String cluster) {
        return new AdoptionRegistry().statusBindings(cluster);
    }

    protected boolean nativeAdoptionStatus(Map<String, Map<String, String>> details) {
        Map<String, String> host = details == null ? null : details.get("host");
        String value = host == null ? null : host.get(NATIVE_ADOPTION_STATUS);
        if (value == null || "false".equals(value)) { return false; }
        if ("true".equals(value)) { return true; }
        throw new IllegalArgumentException("Invalid native adoption status setting");
    }

    /** Returns the instance names whose claim is inconsistent; their power state is unknown. */
    protected List<String> addNativeStatusBindings(Map<String, Map<String, String>> details, Map<String, Object> payload) {
        List<String> unknown = new ArrayList<>();
        if (!nativeAdoptionStatus(details)) { return unknown; }
        String cluster = details.get("host").get("proxmox_cluster");
        Map<String, Map<String, String>> bindings = new HashMap<>();
        nativeStatusBindings(cluster).forEach((vmid, binding) -> {
            if (AdoptionRegistry.StatusBinding.INCONSISTENT.equals(binding.state)) {
                unknown.add(binding.instanceName);
                return;
            }
            if ("retiring".equals(binding.state) && binding.identity != null && releaseRetiredClaim(binding.identity)) { return; }
            bindings.put(vmid, Map.of(
                    "cloudstack_instance_name", binding.instanceName,
                    "expected_proxmox_name", binding.expectedProxmoxName));
        });
        // This field is authored here, not copied from command parameters or VM details.
        payload.put("native_adoption_status_bindings", bindings);
        return unknown;
    }

    /** A retiring claim is released once its instance row is gone from CloudStack. */
    protected boolean releaseRetiredClaim(AdoptionRegistry.Identity identity) {
        try {
            if (vmInstanceDao.findByUuid(identity.vmUuid) != null) { return false; }
            adoptionRegistry().finalizeRetirement(identity, uuid -> vmInstanceDao.findByUuid(uuid) == null);
            logger.info("Released the adoption claim {} of expunged instance {}", identity.claimId, identity.vmUuid);
            return true;
        } catch (RuntimeException e) {
            logger.warn("Could not release the retired adoption claim {}: {}", identity.claimId, e.getMessage());
            return false;
        }
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    protected Map<String, Object> loadAccessDetails(Map<String, Map<String, String>> externalDetails,
                            VirtualMachineTO virtualMachineTO) {
        Map<String, Object> modifiedDetails = new HashMap<>();
        if (MapUtils.isNotEmpty(externalDetails) && externalDetails.containsKey(ApiConstants.CALLER)) {
            modifiedDetails.put(ApiConstants.CALLER, externalDetails.get(ApiConstants.CALLER));
            externalDetails.remove(ApiConstants.CALLER);
        }
        if (MapUtils.isNotEmpty(externalDetails)) {
            modifiedDetails.put(ApiConstants.EXTERNAL_DETAILS, externalDetails);
        }
        if (virtualMachineTO != null) {
            modifiedDetails.put(ApiConstants.VIRTUAL_MACHINE_ID, virtualMachineTO.getUuid());
            modifiedDetails.put(ApiConstants.VIRTUAL_MACHINE_NAME, virtualMachineTO.getName());
            modifiedDetails.put(VmDetailConstants.CLOUDSTACK_VM_DETAILS, virtualMachineTO);
        }
        return modifiedDetails;
    }

    protected String getExtensionCheckedPath(String extensionName, String extensionRelativePath) {
        String path = getExtensionPath(extensionRelativePath);
        File file = new File(path);
        String errorSuffix = String.format("Entry point [%s] for extension: %s", path, extensionName);
        if (!file.exists()) {
            logger.error("{} does not exist", errorSuffix);
            return null;
        }
        if (!file.isFile()) {
            logger.error("{} is not a file", errorSuffix);
            return null;
        }
        if (!file.canRead()) {
            logger.error("{} is not readable", errorSuffix);
            return null;
        }
        if (!file.canExecute()) {
            logger.error("{} is not executable", errorSuffix);
            return null;
        }
        return path;

    }

    protected boolean checkExtensionsDirectory() {
        File dir = new File(extensionsDirectory);
        if (!dir.exists() || !dir.isDirectory() || !dir.canWrite()) {
            logger.error("Extension directory [{}] is not properly set up. It must exist, be a directory, and be writeable",
                    dir.getAbsolutePath());
            return false;
        }
        if (!extensionsDirectory.equals(dir.getAbsolutePath())) {
            extensionsDirectory = dir.getAbsolutePath();
        }
        logger.info("Extensions directory path: {}", extensionsDirectory);
        return true;
    }

    protected void createOrCheckExtensionsDataDirectory() throws ConfigurationException {
        File dir = new File(extensionsDataDirectory);
        if (!dir.exists()) {
            try {
                Files.createDirectories(dir.toPath());
            } catch (IOException e) {
                logger.error("Unable to create extensions data directory [{}]", dir.getAbsolutePath(), e);
                throw new ConfigurationException("Unable to create extensions data directory path");
            }
        }
        if (!dir.isDirectory() || !dir.canWrite()) {
            logger.error("Extensions data directory [{}] is not properly set up. It must exist, be a directory, and be writeable",
                    dir.getAbsolutePath());
            throw new ConfigurationException("Extensions data directory path is not accessible");
        }
        extensionsDataDirectory = dir.getAbsolutePath();
        logger.info("Extensions data directory path: {}", extensionsDataDirectory);
    }

    protected VirtualMachineTO getVirtualMachineTO(VirtualMachine vm) {
        if (vm == null) {
            return null;
        }
        final HypervisorGuru hvGuru = hypervisorGuruManager.getGuru(Hypervisor.HypervisorType.External);
        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        return hvGuru.implement(profile);
    }

    protected String getSanitizedJsonStringForLog(String json) {
        if (StringUtils.isBlank(json)) {
            return json;
        }
        return json.replaceAll("(\"password\"\\s*:\\s*\")([^\"]*)(\")", "$1****$3");
    }

    private String getServerProperty(String name) {
        Properties props = propertiesRef.get();
        if (props == null) {
            File propsFile = PropertiesUtil.findConfigFile(PROPERTIES_FILE);
            if (propsFile == null) {
                logger.error("{} file not found", PROPERTIES_FILE);
                return null;
            }
            Properties tempProps = new Properties();
            try (FileInputStream is = new FileInputStream(propsFile)) {
                tempProps.load(is);
            } catch (IOException e) {
                logger.error("Error loading {}: {}", PROPERTIES_FILE, e.getMessage(), e);
                return null;
            }
            if (!propertiesRef.compareAndSet(null, tempProps)) {
                tempProps = propertiesRef.get();
            }
            props = tempProps;
        }
        return props.getProperty(name);
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);

        initializeExtensionDirectories();
        checkExtensionsDirectory();
        createOrCheckExtensionsDataDirectory();
        return true;
    }

    private void initializeExtensionDirectories() {
        String deploymentMode = getServerProperty(EXTENSIONS_DEPLOYMENT_MODE_NAME);
        if ("developer".equals(deploymentMode)) {
            extensionsDirectory = EXTENSIONS_DIRECTORY_DEV;
            extensionsDataDirectory = EXTENSIONS_DATA_DIRECTORY_DEV;
        } else {
            extensionsDirectory = EXTENSIONS_DIRECTORY_PROD;
            extensionsDataDirectory = EXTENSIONS_DATA_DIRECTORY_PROD;
        }
    }

    @Override
    public boolean start() {
        payloadCleanupExecutor = Executors.newSingleThreadExecutor();
        payloadCleanupScheduler = Executors.newSingleThreadScheduledExecutor();
        return true;
    }

    @Override
    public boolean stop() {
        payloadCleanupExecutor.shutdown();
        payloadCleanupScheduler.shutdown();
        return true;
    }

    @Override
    public String getExtensionsPath() {
        return extensionsDirectory;
    }

    @Override
    public String getExtensionPath(String relativePath) {
        return String.format("%s%s%s", extensionsDirectory, File.separator, relativePath);
    }

    @Override
    public String getChecksumForExtensionPath(String extensionName, String relativePath) {
        String path = getExtensionCheckedPath(extensionName, relativePath);
        if (StringUtils.isBlank(path)) {
            return null;
        }
        try {
            return DigestHelper.calculateChecksum(new File(path));
        } catch (CloudRuntimeException ignored) {
            return null;
        }
    }

    protected String getExtensionConfigureError(String extensionName, String hostName) {
        StringBuilder sb = new StringBuilder("Extension: ").append(extensionName).append(" not configured");
        if (StringUtils.isNotBlank(hostName)) {
            sb.append(" for host: ").append(hostName);
        }
        return sb.toString();
    }

    @Override
    public PrepareExternalProvisioningAnswer prepareExternalProvisioning(String hostName,
                 String extensionName, String extensionRelativePath, PrepareExternalProvisioningCommand cmd) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new PrepareExternalProvisioningAnswer(cmd, false, getExtensionConfigureError(extensionName, hostName));
        }
        VirtualMachineTO vmTO = cmd.getVirtualMachineTO();
        String vmUUID = vmTO.getUuid();
        logger.debug("Executing PrepareExternalProvisioningCommand in the external provisioner " +
                "for the VM {} as part of VM deployment", vmUUID);
        Map<String, Object> accessDetails = loadAccessDetails(cmd.getExternalDetails(), vmTO);
        Pair<Boolean, String> result = prepareExternalProvisioningInternal(extensionName, extensionPath,
                vmUUID, accessDetails, cmd.getWait());
        String output = result.second();
        if (!result.first()) {
            return new PrepareExternalProvisioningAnswer(cmd, false, output);
        }
        if (StringUtils.isEmpty(output)) {
            return new PrepareExternalProvisioningAnswer(cmd, result.first(), "");
        }
        try {
            String merged = JsonMergeUtil.mergeJsonPatch(GsonHelper.getGson().toJson(vmTO), result.second());
            VirtualMachineTO virtualMachineTO  = GsonHelper.getGson().fromJson(merged, VirtualMachineTO.class);
            return new PrepareExternalProvisioningAnswer(cmd, null, virtualMachineTO, null);
        } catch (Exception e) {
            logger.warn("Failed to parse the output from preparing external provisioning operation as " +
                    "part of VM deployment: {}", e.getMessage(), e);
            return new PrepareExternalProvisioningAnswer(cmd, false, "Failed to parse VM");
        }
    }

    @Override
    public StartAnswer startInstance(String hostName, String extensionName, String extensionRelativePath,
                             StartCommand cmd) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new StartAnswer(cmd, getExtensionConfigureError(extensionName, hostName));
        }
        VirtualMachineTO virtualMachineTO = cmd.getVirtualMachine();
        String vmUUID = virtualMachineTO.getUuid();

        logger.debug(String.format("Executing StartCommand in the external provisioner for VM %s", vmUUID));

        Object deployvm = virtualMachineTO.getDetails().get("deployvm");
        boolean isDeploy = (deployvm != null && Boolean.parseBoolean((String)deployvm));
        String operation = isDeploy ? "Deploying" : "Starting";
        try {
            HostVO host = hostDao.findByGuid(hostName);
            VMInstanceVO vm = vmInstanceDao.findByUuid(vmUUID);
            List<Map<String, Object>> cdroms = null;
            if (isoLibrary != null && host != null && vm != null && isoLibrary.supported(host.getClusterId())) {
                // As on any hypervisor, a start gives the instance the CD drives CloudStack records for it.
                cdroms = isoLibrary.drives(host, virtualMachineTO.getDisks(), clusterAction(host, extensionName, extensionPath));
                VMTemplateVO image = isDeploy ? templateDao.findByIdIncludingRemoved(vm.getTemplateId()) : null;
                if (image != null && image.getFormat() == Storage.ImageFormat.ISO) {
                    // An ISO supplies none of what a template does to create the instance from.
                    Map<String, Map<String, String>> details = cmd.getExternalDetails();
                    Map<String, String> vmDetails = details.get(ApiConstants.VIRTUAL_MACHINE) == null ? new HashMap<>()
                            : new HashMap<>(details.get(ApiConstants.VIRTUAL_MACHINE));
                    vmDetails.putAll(isoLibrary.deployment(host, vm));
                    details.put(ApiConstants.VIRTUAL_MACHINE, vmDetails);
                }
            }
            VMTemplateVO deployedFrom = isDeploy && vm != null ? templateDao.findByIdIncludingRemoved(vm.getTemplateId()) : null;
            if (deployedFrom != null && deployedFrom.getFormat() == Storage.ImageFormat.EXTERNAL && volumeSync != null
                    && host != null && host.getClusterId() != null) {
                // The boot disk the extension creates is the root volume the instance was allocated: tell it where.
                Map<String, Map<String, String>> details = cmd.getExternalDetails();
                Map<String, String> vmDetails = details.get(ApiConstants.VIRTUAL_MACHINE) == null ? new HashMap<>()
                        : new HashMap<>(details.get(ApiConstants.VIRTUAL_MACHINE));
                vmDetails.putAll(volumeSync.templateDeployment(host.getClusterId(), vm, vmDetails.get("storage"), vmDetails.get("disk_size_gb")));
                details.put(ApiConstants.VIRTUAL_MACHINE, vmDetails);
            }
            Map<String, Object> accessDetails = loadAccessDetails(cmd.getExternalDetails(), virtualMachineTO);
            if (cdroms != null) {
                accessDetails.put("cdroms", cdroms);
            }
            Pair<Boolean, String> result = executeStartCommandOnExternalSystem(extensionName, isDeploy,
                    extensionPath, vmUUID, accessDetails, cmd.getWait());

            if (!result.first()) {
                String errMsg = String.format("%s VM %s on the external system failed: %s", operation, vmUUID, result.second());
                logger.debug(errMsg);
                return new StartAnswer(cmd, result.second());
            }
            logger.debug(String.format("%s VM %s on the external system", operation, vmUUID));
            if (isDeploy && volumeSync != null && host != null && vm != null && host.getClusterId() != null) {
                volumeSync.recordCreated(vm, host.getClusterId(), result.second());
            }
            return new StartAnswer(cmd);

        } catch (CloudRuntimeException e) {
            String errMsg = String.format("%s VM %s on the external system failed: %s", operation, vmUUID, e.getMessage());
            logger.debug(errMsg);
            return new StartAnswer(cmd, errMsg);
        }
    }

    private Pair<Boolean, String> executeStartCommandOnExternalSystem(String extensionName, boolean isDeploy,
                          String filename, String vmUUID, Map<String, Object> accessDetails, int wait) {
        if (isDeploy) {
            return deployInstanceOnExternalSystem(extensionName, filename, vmUUID, accessDetails, wait);
        } else {
            return startInstanceOnExternalSystem(extensionName, filename, vmUUID, accessDetails, wait);
        }
    }

    /** The power sync sends a stop with checkBeforeCleanup after a host reported the instance off,
     * before it marks the instance Stopped. That stop never changes the provider: the instance may
     * have been started again since the report. The power report is read again; off is acknowledged,
     * running is refused so CloudStack keeps the instance Running, and anything else is refused as
     * unconfirmed. No operation lease is taken, so a retained lease does not keep a stopped
     * instance showing as Running, and it still fences every lifecycle operation. */
    protected StopAnswer answerCleanupStop(String hostGuid, String extensionName, String extensionPath, StopCommand cmd) {
        VirtualMachine.PowerState state = reportedPowerState(hostGuid, extensionName, extensionPath, cmd);
        if (VirtualMachine.PowerState.PowerOff.equals(state)) {
            return new StopAnswer(cmd, null, true);
        }
        if (VirtualMachine.PowerState.PowerOn.equals(state)) {
            return new StopAnswer(cmd, "vm is still running on host", false);
        }
        return new StopAnswer(cmd, "The power state of the instance could not be confirmed; nothing was stopped", false);
    }

    /** What the host's power report says now. An instance that was moved while off is no longer
     * listed by the host CloudStack has it on, so the host that last reported it is read as well. */
    protected VirtualMachine.PowerState reportedPowerState(String hostGuid, String extensionName, String extensionPath, StopCommand cmd) {
        String name = cmd.getVmName();
        if (StringUtils.isBlank(name) && cmd.getVirtualMachine() != null) { name = cmd.getVirtualMachine().getName(); }
        HostVO host = hostDao.findByGuid(hostGuid);
        if (StringUtils.isBlank(name) || host == null) { return VirtualMachine.PowerState.PowerUnknown; }
        HostVmStateReportEntry entry = reportEntry(host, name, extensionName, extensionPath);
        if (entry == null) {
            VMInstanceVO instance = vmInstanceDao.findVMByInstanceName(name);
            Long reporterId = instance == null ? null : instance.getPowerHostId();
            HostVO reporter = reporterId == null || reporterId.equals(host.getId()) ? null : hostDao.findById(reporterId);
            if (reporter != null && Objects.equals(reporter.getClusterId(), host.getClusterId())) {
                entry = reportEntry(reporter, name, extensionName, extensionPath);
            }
        }
        return entry == null ? VirtualMachine.PowerState.PowerUnknown : entry.getState();
    }

    private HostVmStateReportEntry reportEntry(HostVO host, String name, String extensionName, String extensionPath) {
        Map<String, HostVmStateReportEntry> states = getVmPowerStates(host,
                extensionsManager.getExternalAccessDetails(host, null), extensionName, extensionPath);
        return states == null ? null : states.get(name);
    }

    @Override
    public StopAnswer stopInstance(String hostName, String extensionName, String extensionRelativePath,
                           StopCommand cmd) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new StopAnswer(cmd, getExtensionConfigureError(extensionName, hostName), false);
        }
        logger.debug("Executing stop command on the external provisioner");
        if (cmd.checkBeforeCleanup()) {
            return answerCleanupStop(hostName, extensionName, extensionPath, cmd);
        }
        VirtualMachineTO virtualMachineTO = cmd.getVirtualMachine();
        String vmUUID = cmd.getVirtualMachine().getUuid();
        logger.debug("Executing stop command in the external system for the VM {}", vmUUID);
        Map<String, Object> accessDetails = loadAccessDetails(cmd.getExternalDetails(), virtualMachineTO);
        Pair<Boolean, String> result = stopInstanceOnExternalSystem(extensionName, extensionPath, vmUUID,
                accessDetails, cmd.getWait());
        if (result.first()) {
            return new StopAnswer(cmd, null, true);
        } else {
            return new StopAnswer(cmd, result.second(), false);
        }
    }

    @Override
    public RebootAnswer rebootInstance(String hostName, String extensionName, String extensionRelativePath,
                           RebootCommand cmd) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new RebootAnswer(cmd, getExtensionConfigureError(extensionName, hostName), false);
        }
        VirtualMachineTO virtualMachineTO = cmd.getVirtualMachine();
        String vmUUID = virtualMachineTO.getUuid();
        logger.debug("Executing reboot command in the external system for the VM {}", vmUUID);
        Map<String, Object> accessDetails = loadAccessDetails(cmd.getExternalDetails(), virtualMachineTO);
        Pair<Boolean, String> result = rebootInstanceOnExternalSystem(extensionName, extensionPath, vmUUID,
                accessDetails, cmd.getWait());
        if (result.first()) {
            return new RebootAnswer(cmd, null, true);
        } else {
            return new RebootAnswer(cmd, result.second(), false);
        }
    }

    @Override
    public StopAnswer expungeInstance(String hostName, String extensionName, String extensionRelativePath,
                          StopCommand cmd) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new StopAnswer(cmd, getExtensionConfigureError(extensionName, hostName), false);
        }
        VirtualMachineTO virtualMachineTO = cmd.getVirtualMachine();
        String vmUUID = virtualMachineTO.getUuid();
        logger.debug("Executing stop command as part of expunge in the external system for the VM {}", vmUUID);
        Map<String, Object> accessDetails = loadAccessDetails(cmd.getExternalDetails(), virtualMachineTO);
        Pair<Boolean, String> result = deleteInstanceOnExternalSystem(extensionName, extensionPath, vmUUID,
                accessDetails, cmd.getWait());
        if (result.first()) {
            return new StopAnswer(cmd, null, true);
        } else {
            return new StopAnswer(cmd, result.second(), false);
        }
    }

    @Override
    public Map<String, HostVmStateReportEntry> getHostVmStateReport(long hostId, String extensionName,
                            String extensionRelativePath) {
        Map<String, HostVmStateReportEntry> vmStates = new HashMap<>();
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return vmStates;
        }
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            logger.error("Host with ID: {} not found", hostId);
            return vmStates;
        }
        Map<String, Map<String, String>> accessDetails =
                extensionsManager.getExternalAccessDetails(host, null);
        vmStates = getVmPowerStates(host, accessDetails, extensionName, extensionPath);
        if (vmStates != null) {
            logger.debug("Found {} VMs on the host {}", vmStates.size(), host);
            return vmStates;
        }
        if (nativeAdoptionStatus(accessDetails)) {
            // A failed native batch must not fall back to unrelated per-VM paths.
            throw new CloudRuntimeException("Native adoption status batch failed");
        }
        vmStates = new HashMap<>();
        List<UserVmVO> allVms = _uservmDao.listByHostId(hostId);
        allVms.addAll(_uservmDao.listByLastHostId(hostId));
        if (CollectionUtils.isEmpty(allVms)) {
            logger.debug("No VMs found for the {}", host);
            return vmStates;
        }
        for (UserVmVO vm: allVms) {
            VirtualMachine.PowerState powerState = getVmPowerState(vm, accessDetails, extensionName, extensionPath);
            vmStates.put(vm.getInstanceName(), new HostVmStateReportEntry(powerState, "host-" + hostId));
        }
        return vmStates;
    }

    @Override
    public String getMetrics(String hostGuid, String extensionName, String extensionRelativePath, boolean instances) {
        HostVO host = hostGuid == null ? null : hostDao.findByGuid(hostGuid);
        if (host == null) {
            return null;
        }
        Map<String, Map<String, String>> details = extensionsManager.getExternalAccessDetails(host, null);
        Map<String, String> extension = details == null ? null : details.get(ApiConstants.EXTENSION);
        if (extension == null || !"true".equals(extension.get("instancestatssupported"))) {
            return null;
        }
        String path = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(path)) {
            return null;
        }
        Map<String, Object> payload = loadAccessDetails(details, null);
        if (instances) {
            // Exclude inconsistent native claims even if an extension returns a matching provider name.
            List<String> unknown = addNativeStatusBindings(details, payload);
            Pair<Boolean, String> result = executeExternalCommand(extensionName, "vmstats", payload, 60,
                    "Failed to fetch instance metrics", path);
            if (result == null || !Boolean.TRUE.equals(result.first()) || StringUtils.isBlank(result.second())) {
                return null;
            }
            JsonObject response = JsonParser.parseString(result.second()).getAsJsonObject();
            if (response.has("vms") && response.get("vms").isJsonObject()) {
                unknown.forEach(name -> response.getAsJsonObject("vms").remove(name));
            }
            return response.toString();
        }
        Pair<Boolean, String> result = executeExternalCommand(extensionName, "hoststats", payload, 60,
                "Failed to fetch host metrics", path);
        return result != null && Boolean.TRUE.equals(result.first()) ? result.second() : null;
    }

    static final String HOST_HEALTH_SUPPORTED = "hosthealthsupported";
    private static final int HOST_HEALTH_WAIT = 60;

    @Override
    public String getHostHealth(String viaHostGuid, Long targetHostId) {
        HostVO via = viaHostGuid == null ? null : hostDao.findByGuid(viaHostGuid);
        if (via == null || via.getClusterId() == null) {
            return null;
        }
        Map<String, Map<String, String>> accessDetails = extensionsManager.getExternalAccessDetails(via, null);
        Map<String, String> extension = accessDetails == null ? null : accessDetails.get(ApiConstants.EXTENSION);
        if (extension == null || !"true".equals(extension.get(HOST_HEALTH_SUPPORTED))) {
            return null;
        }
        Extension ext = extensionHelper.getExtensionForCluster(via.getClusterId());
        String extensionPath = ext == null ? null : getExtensionCheckedPath(ext.getName(), ext.getRelativePath());
        if (StringUtils.isEmpty(extensionPath)) {
            return HOST_HEALTH_UNKNOWN;
        }
        Map<String, Object> payload = loadAccessDetails(accessDetails, null);
        if (targetHostId != null) {
            HostVO target = hostDao.findById(targetHostId);
            if (target == null || !via.getClusterId().equals(target.getClusterId())) {
                return HOST_HEALTH_UNKNOWN;
            }
            Map<String, String> targetHost = extensionsManager.getExternalAccessDetails(target, null).get(ApiConstants.HOST);
            String node = targetHost == null ? null : targetHost.get("node");
            if (StringUtils.isBlank(node)) {
                return HOST_HEALTH_UNKNOWN;
            }
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("node", node);
            payload.put(ApiConstants.PARAMETERS, parameters);
        }
        Pair<Boolean, String> result = executeExternalCommand(ext.getName(), "health", payload, HOST_HEALTH_WAIT,
                String.format("Failed to check the health of host %s", targetHostId == null ? via : targetHostId), extensionPath);
        String health = parseHostHealth(result);
        if (!HOST_HEALTH_UP.equals(health)) {
            logger.info("Health of {} as seen through {}: {} ({})", targetHostId == null ? via : hostDao.findById(targetHostId), via, health, healthReason(result));
        }
        return health;
    }

    /** Why the extension answered other than up, bounded and without the raw answer. */
    static String healthReason(Pair<Boolean, String> result) {
        if (result == null || StringUtils.isBlank(result.second())) {
            return "no answer";
        }
        String text = result.second().trim();
        try {
            JsonObject obj = JsonParser.parseString(text.substring(Math.max(0, text.indexOf('{')))).getAsJsonObject();
            String reason = !field(obj, "reason").isEmpty() ? field(obj, "reason") : field(obj, "error");
            if (!reason.isEmpty()) {
                return org.apache.commons.lang3.StringUtils.abbreviate(reason.replaceAll("[^\\x20-\\x7e]", "?"), 200);
            }
        } catch (RuntimeException e) {
            // below
        }
        return result.first() ? "unreadable answer" : org.apache.commons.lang3.StringUtils.abbreviate(text.replaceAll("[^\\x20-\\x7e]", "?"), 120);
    }

    static String parseHostHealth(Pair<Boolean, String> result) {
        if (result == null || !result.first() || StringUtils.isBlank(result.second())) {
            return HOST_HEALTH_UNKNOWN;
        }
        try {
            JsonObject obj = JsonParser.parseString(result.second().trim()).getAsJsonObject();
            String health = field(obj, "health");
            if ("success".equals(field(obj, "status"))
                    && (HOST_HEALTH_UP.equals(health) || HOST_HEALTH_DOWN.equals(health) || HOST_HEALTH_UNKNOWN.equals(health))) {
                return health;
            }
        } catch (RuntimeException e) {
            // fall through: an unreadable answer says nothing about the node
        }
        return HOST_HEALTH_UNKNOWN;
    }

    @Override
    public GetExternalConsoleAnswer getInstanceConsole(String hostName, String extensionName,
                           String extensionRelativePath, GetExternalConsoleCommand cmd) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new GetExternalConsoleAnswer(cmd, getExtensionConfigureError(extensionName, hostName));
        }
        VirtualMachineTO virtualMachineTO = cmd.getVirtualMachine();
        String vmUUID = virtualMachineTO.getUuid();
        logger.debug("Executing getconsole command in the external system for the VM {}", vmUUID);
        Map<String, Object> accessDetails = loadAccessDetails(cmd.getExternalDetails(), virtualMachineTO);
        Pair<Boolean, String> result = getInstanceConsoleOnExternalSystem(extensionName, extensionPath, vmUUID,
                accessDetails, cmd.getWait());
        if (result == null) {
            return new GetExternalConsoleAnswer(cmd, "No response from external system");
        }
        String output = result.second();
        if (!result.first()) {
            return new GetExternalConsoleAnswer(cmd, output);
        }
        logger.debug("Received console details from the external system: {}",
                getSanitizedJsonStringForLog(output));
        try {
            JsonObject jsonObj = JsonParser.parseString(output).getAsJsonObject();
            JsonObject consoleObj = jsonObj.has("console") ? jsonObj.getAsJsonObject("console") : null;
            if (consoleObj == null) {
                logger.error("Missing console object in external console output: {}",
                        getSanitizedJsonStringForLog(output));
                return new GetExternalConsoleAnswer(cmd, "Missing console object in output");
            }
            String url = consoleObj.has("url") ? consoleObj.get("url").getAsString() : null;
            String host = consoleObj.has("host") ? consoleObj.get("host").getAsString() : null;
            Integer port = consoleObj.has("port") ? Integer.valueOf(consoleObj.get("port").getAsString()) : null;
            String password = consoleObj.has("password") ? consoleObj.get("password").getAsString() : null;
            boolean passwordOneTimeUseOnly = consoleObj.has("passwordonetimeuseonly") &&
                    consoleObj.get("passwordonetimeuseonly").getAsBoolean();
            String protocol = consoleObj.has("protocol") ? consoleObj.get("protocol").getAsString() : null;
            if (url == null && ObjectUtils.anyNull(host, port)) {
                logger.error("Missing required fields in external console output: {}",
                        getSanitizedJsonStringForLog(output));
                return new GetExternalConsoleAnswer(cmd, "Missing required fields in output");
            }
            return new GetExternalConsoleAnswer(cmd, url, host, port, password, passwordOneTimeUseOnly, protocol);
        } catch (RuntimeException e) {
            logger.error("Failed to parse output for getInstanceConsole: {}", e.getMessage(), e);
            return new GetExternalConsoleAnswer(cmd, "Failed to parse output");
        }
    }

    @Override
    public RunCustomActionAnswer runCustomAction(String hostName, String extensionName,
                         String extensionRelativePath, RunCustomActionCommand cmd) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new RunCustomActionAnswer(cmd, false, getExtensionConfigureError(extensionName, hostName));
        }
        final String actionName = cmd.getActionName();
        final Map<String, Object> parameters = cmd.getParameters();
        logger.debug("Executing custom action '{}' in the external system", actionName);
        Map<String, Object> accessDetails = loadAccessDetails(cmd.getExternalDetails(), cmd.getVmTO());
        accessDetails.put(ApiConstants.ACTION, actionName);
        if (MapUtils.isNotEmpty(parameters)) {
            accessDetails.put(ApiConstants.PARAMETERS, parameters);
        }
        Pair<Boolean, String> result = runCustomActionOnExternalSystem(extensionName, extensionPath,
                actionName, accessDetails, cmd.getWait());
        return new RunCustomActionAnswer(cmd, result.first(), result.second());
    }

    protected UserVmVO getUserVmByInstanceName(String instanceName) {
        VMInstanceVO instance = vmInstanceDao.findVMByInstanceName(instanceName);
        if (instance == null) {
            return null;
        }
        return _uservmDao.findById(instance.getId());
    }

    @Override
    public CheckVirtualMachineAnswer checkInstance(String hostGuid, String extensionName,
                           String extensionRelativePath, CheckVirtualMachineCommand cmd) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new CheckVirtualMachineAnswer(cmd, getExtensionConfigureError(extensionName, hostGuid));
        }
        HostVO host = hostDao.findByGuid(hostGuid);
        UserVmVO userVm = getUserVmByInstanceName(cmd.getVmName());
        if (ObjectUtils.anyNull(host, userVm)) {
            logger.warn("Unable to check instance {} as the host or the instance is not found", cmd.getVmName());
            return new CheckVirtualMachineAnswer(cmd, "Host or instance not found");
        }
        Map<String, Map<String, String>> accessDetails = extensionsManager.getExternalAccessDetails(host, null);
        VirtualMachine.PowerState powerState = getVmPowerState(userVm, accessDetails, extensionName, extensionPath);
        if (VirtualMachine.PowerState.PowerUnknown.equals(powerState)) {
            return new CheckVirtualMachineAnswer(cmd, "Unable to get the power state from the external system");
        }
        return new CheckVirtualMachineAnswer(cmd, powerState, null);
    }

    @Override
    public Answer getInstanceIpAddress(String hostGuid, String extensionName, String extensionRelativePath,
                           GetVmIpAddressCommand cmd) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new Answer(cmd, false, getExtensionConfigureError(extensionName, hostGuid));
        }
        HostVO host = hostDao.findByGuid(hostGuid);
        UserVmVO userVm = getUserVmByInstanceName(cmd.getVmName());
        if (ObjectUtils.anyNull(host, userVm)) {
            logger.warn("Unable to get the IP address of instance {} as the host or the instance is not found",
                    cmd.getVmName());
            return new Answer(cmd, false, "Host or instance not found");
        }
        VirtualMachineTO virtualMachineTO = getVirtualMachineTO(userVm);
        Map<String, Map<String, String>> accessDetails = extensionsManager.getExternalAccessDetails(host, null);
        accessDetails.put(ApiConstants.VIRTUAL_MACHINE, virtualMachineTO.getExternalDetails());
        Map<String, Object> modifiedDetails = loadAccessDetails(accessDetails, virtualMachineTO);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(ApiConstants.MAC_ADDRESS, cmd.getMacAddress());
        if (StringUtils.isNotBlank(cmd.getVmNetworkCidr())) {
            parameters.put(ApiConstants.CIDR, cmd.getVmNetworkCidr());
        }
        modifiedDetails.put(ApiConstants.PARAMETERS, parameters);
        logger.debug("Trying to get the IP address from the external system for {}", userVm);
        Pair<Boolean, String> result = executeExternalCommand(extensionName, "getipaddress", modifiedDetails,
                cmd.getWait(), String.format("Failed to get the IP address of instance %s on external system",
                        userVm.getUuid()), extensionPath);
        if (!result.first()) {
            return new Answer(cmd, false, result.second());
        }
        return getIpAddressAnswerFromResponse(cmd, result.second());
    }

    /**
     * The external system answers with {"status": "success", "ip_address": "..."} or with the bare address.
     * An empty address means the instance was reached and has no address on that NIC.
     */
    protected Answer getIpAddressAnswerFromResponse(GetVmIpAddressCommand cmd, String response) {
        String ipAddress = StringUtils.trimToNull(response);
        if (ipAddress != null && ipAddress.startsWith("{")) {
            try {
                JsonObject jsonObj = JsonParser.parseString(ipAddress).getAsJsonObject();
                if (!jsonObj.has("status") || !"success".equalsIgnoreCase(jsonObj.get("status").getAsString())) {
                    return new Answer(cmd, false, "Failure response received for the IP address");
                }
                ipAddress = jsonObj.has("ip_address") && !jsonObj.get("ip_address").isJsonNull() ?
                        StringUtils.trimToNull(jsonObj.get("ip_address").getAsString()) : null;
            } catch (RuntimeException e) {
                logger.warn("Failed to parse the IP address response: {}", e.getMessage());
                return new Answer(cmd, false, "Failed to parse output");
            }
        }
        if (ipAddress != null && !NetUtils.isValidIp4(ipAddress)) {
            return new Answer(cmd, false, "Invalid IP address in output");
        }
        return new Answer(cmd, true, ipAddress);
    }

    @Override
    public PlugNicAnswer plugInstanceNic(String hostGuid, String extensionName, String extensionRelativePath, PlugNicCommand cmd) {
        Pair<Boolean, String> result = changeInstanceNic(hostGuid, extensionName, extensionRelativePath,
                cmd.getVmName(), cmd.getNic(), "plugnic", cmd.getWait());
        return new PlugNicAnswer(cmd, result.first(), result.second());
    }

    @Override
    public UnPlugNicAnswer unplugInstanceNic(String hostGuid, String extensionName, String extensionRelativePath, UnPlugNicCommand cmd) {
        Pair<Boolean, String> result = changeInstanceNic(hostGuid, extensionName, extensionRelativePath,
                cmd.getVmName(), cmd.getNic(), "unplugnic", cmd.getWait());
        return new UnPlugNicAnswer(cmd, result.first(), result.second());
    }

    /** Runs an action of the extension that concerns the host's cluster and no instance. */
    protected ExternalIsoLibrary.Runner clusterAction(HostVO host, String extensionName, String extensionPath) {
        return (action, parameters, wait) -> {
            Map<String, Object> payload = loadAccessDetails(extensionsManager.getExternalAccessDetails(host, null), null);
            payload.put(ApiConstants.ACTION, action);
            payload.put(ApiConstants.PARAMETERS, parameters);
            return executeExternalCommand(extensionName, action, payload, wait,
                    String.format("Failed to execute '%s' on external system", action), extensionPath);
        };
    }

    @Override
    public Answer attachInstanceIso(String hostGuid, String extensionName, String extensionRelativePath, AttachCommand cmd) {
        Pair<Boolean, String> result = changeInstanceIso(hostGuid, extensionName, extensionRelativePath, cmd.getVmName(), cmd.getDisk(), true, cmd.getWait());
        return new Answer(cmd, result.first(), result.second());
    }

    @Override
    public Answer detachInstanceIso(String hostGuid, String extensionName, String extensionRelativePath, DettachCommand cmd) {
        Pair<Boolean, String> result = changeInstanceIso(hostGuid, extensionName, extensionRelativePath, cmd.getVmName(), cmd.getDisk(), false, cmd.getWait());
        return new Answer(cmd, result.first(), result.second());
    }

    /**
     * The stock ISO commands are only sent for a running instance; a stopped one has its drives set at its next
     * start. Attach loads the drive CloudStack chose when it is empty, else the first empty drive: a drive that
     * holds a disc is never taken, as the disc may be one CloudStack does not record. Detach empties whichever
     * drive holds the ISO. A refusal carries the extension's reason.
     */
    protected Pair<Boolean, String> changeInstanceIso(String hostGuid, String extensionName, String extensionRelativePath,
            String instanceName, DiskTO disk, boolean attach, int wait) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new Pair<>(false, getExtensionConfigureError(extensionName, hostGuid));
        }
        HostVO host = hostDao.findByGuid(hostGuid);
        UserVmVO userVm = getUserVmByInstanceName(instanceName);
        if (ObjectUtils.anyNull(host, userVm, disk, isoLibrary) || disk.getType() != Volume.Type.ISO || disk.getDiskSeq() == null) {
            return new Pair<>(false, "Host, instance or ISO not found");
        }
        if (!isoLibrary.supported(host.getClusterId())) {
            return new Pair<>(false, "The instance's extension does not support ISOs.");
        }
        if (userVm.getState() != VirtualMachine.State.Running || userVm.getHostId() == null || userVm.getHostId() != host.getId()) {
            return new Pair<>(false, "The instance is not running on this host.");
        }
        long drive = disk.getDiskSeq() - TemplateManager.CDROM_PRIMARY_DEVICE_SEQ;
        if (drive < 0 || drive > 3) {
            return new Pair<>(false, "The instance has no such CD drive.");
        }
        try {
            VMTemplateVO iso = isoLibrary.iso(disk.getData());
            if (iso == null) {
                return new Pair<>(false, "Host, instance or ISO not found");
            }
            return instanceLock.hold(userVm.getId(), 1, () -> {
                String volume = attach ? isoLibrary.ensure(host, iso, clusterAction(host, extensionName, extensionPath)) : isoLibrary.volume(host, iso);
                VirtualMachineTO vmTO = getVirtualMachineTO(userVm);
                Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
                access.put(ApiConstants.VIRTUAL_MACHINE, vmTO.getExternalDetails());
                Map<String, Object> payload = loadAccessDetails(access, vmTO);
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("volume", volume);
                if (attach) {
                    parameters.put("drive", drive);
                }
                payload.put(ApiConstants.PARAMETERS, parameters);
                Pair<Boolean, String> result = executeExternalCommand(extensionName, attach ? "attachiso" : "detachiso", payload, wait,
                        "Failed to change a CD drive on the external system", extensionPath);
                return result.first() ? result : new Pair<>(false, ExternalVolumeOperations.reason(result.second()));
            });
        } catch (CloudRuntimeException e) {
            return new Pair<>(false, e.getMessage());
        }
    }

    /** How long a snapshot waits for a refresh or a disk change of the instance to finish. */
    private static final int SNAPSHOT_LOCK_WAIT_SECONDS = 30;

    @Override
    public Answer changeInstanceSnapshot(String hostGuid, String extensionName, String extensionRelativePath, VMSnapshotBaseCommand cmd) {
        String action = cmd instanceof CreateVMSnapshotCommand ? "createsnapshot" : cmd instanceof RevertToVMSnapshotCommand ? "revertsnapshot"
                : cmd instanceof DeleteVMSnapshotCommand ? "deletesnapshot" : null;
        if (action == null) {
            return ExternalProvisioner.snapshotAnswer(cmd, false, null, null);
        }
        Pair<Boolean, String> result = changeInstanceSnapshot(hostGuid, extensionName, extensionRelativePath, cmd, action);
        VirtualMachine.PowerState power = null;
        if (result.first() && cmd instanceof RevertToVMSnapshotCommand) {
            // Checked by the extension against the kind of snapshot: with memory the instance runs on, without it stays stopped.
            power = cmd.getTarget().getType() == VMSnapshot.Type.DiskAndMemory ? VirtualMachine.PowerState.PowerOn : VirtualMachine.PowerState.PowerOff;
        }
        return ExternalProvisioner.snapshotAnswer(cmd, result.first(), result.second(), power);
    }

    /**
     * The snapshot is held by the orchestrator under the name CloudStack gave it. It is taken and removed with
     * the instance running or stopped. No disk change or refresh of the instance runs at the same time.
     */
    protected Pair<Boolean, String> changeInstanceSnapshot(String hostGuid, String extensionName, String extensionRelativePath,
            VMSnapshotBaseCommand cmd, String action) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new Pair<>(false, getExtensionConfigureError(extensionName, hostGuid));
        }
        HostVO host = hostDao.findByGuid(hostGuid);
        UserVmVO userVm = getUserVmByInstanceName(cmd.getVmName());
        VMSnapshotTO target = cmd.getTarget();
        if (ObjectUtils.anyNull(host, userVm, target) || StringUtils.isEmpty(target.getSnapshotName()) || target.getType() == null) {
            return new Pair<>(false, "Host, instance or snapshot not found");
        }
        Extension extension = host.getClusterId() == null ? null : extensionHelper.getExtensionForCluster(host.getClusterId());
        if (extension == null || !extensionHelper.isInstanceSnapshotSupported(extension.getId())) {
            return new Pair<>(false, "The instance's extension does not support instance snapshots.");
        }
        if (userVm.getState() != VirtualMachine.State.Stopped && userVm.getState() != VirtualMachine.State.Running) {
            return new Pair<>(false, "Instance snapshots require a stopped or running instance.");
        }
        try {
            return instanceLock.hold(userVm.getId(), SNAPSHOT_LOCK_WAIT_SECONDS, () -> {
                VirtualMachineTO vmTO = getVirtualMachineTO(userVm);
                Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
                access.put(ApiConstants.VIRTUAL_MACHINE, vmTO.getExternalDetails());
                Map<String, Object> payload = loadAccessDetails(access, vmTO);
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("snap_name", target.getSnapshotName());
                parameters.put("snap_save_memory", String.valueOf(target.getType() == VMSnapshot.Type.DiskAndMemory));
                if (cmd instanceof CreateVMSnapshotCommand) {
                    parameters.put("snap_description", snapshotDescription(target));
                }
                payload.put(ApiConstants.PARAMETERS, parameters);
                Pair<Boolean, String> result = executeExternalCommand(extensionName, action, payload, cmd.getWait(),
                        "Failed to change an instance snapshot on the external system", extensionPath);
                return result.first() ? result : new Pair<>(false, ExternalVolumeOperations.reason(result.second()));
            });
        } catch (CloudRuntimeException e) {
            return new Pair<>(false, e.getMessage());
        }
    }

    /** What the orchestrator shows for the snapshot: the name its owner gave it, and the owner's description. */
    protected String snapshotDescription(VMSnapshotTO target) {
        VMSnapshotVO snapshot = target.getId() == null || vmSnapshotDao == null ? null : vmSnapshotDao.findById(target.getId());
        String name = snapshot == null ? null : snapshot.getDisplayName();
        return Stream.of(name, target.getDescription()).filter(StringUtils::isNotBlank).distinct().collect(java.util.stream.Collectors.joining(": "));
    }

    protected Pair<Boolean, String> changeInstanceNic(String hostGuid, String extensionName, String extensionRelativePath,
            String instanceName, NicTO nic, String action, int wait) {
        String extensionPath = getExtensionCheckedPath(extensionName, extensionRelativePath);
        if (StringUtils.isEmpty(extensionPath)) {
            return new Pair<>(false, getExtensionConfigureError(extensionName, hostGuid));
        }
        HostVO host = hostDao.findByGuid(hostGuid);
        UserVmVO userVm = getUserVmByInstanceName(instanceName);
        if (ObjectUtils.anyNull(host, userVm, nic)) {
            return new Pair<>(false, "Host, instance or NIC not found");
        }
        Extension extension = host.getClusterId() == null ? null : extensionHelper.getExtensionForCluster(host.getClusterId());
        if (extension == null || !extensionHelper.isInstanceNicChangeSupported(extension.getId())) {
            return new Pair<>(false, "The instance's extension does not support NIC changes.");
        }
        VirtualMachine.State state = userVm.getState();
        if (state != VirtualMachine.State.Stopped && state != VirtualMachine.State.Running) {
            return new Pair<>(false, "External NIC changes require a stopped or running instance.");
        }
        if (state == VirtualMachine.State.Running && !extensionHelper.isInstanceNicHotplugSupported(extension.getId())) {
            return new Pair<>(false, "The instance's extension does not support online NIC changes.");
        }
        Long vmHostId = state == VirtualMachine.State.Running ? userVm.getHostId() :
                (userVm.getHostId() != null ? userVm.getHostId() : userVm.getLastHostId());
        if (vmHostId == null || !vmHostId.equals(host.getId())) {
            return new Pair<>(false, "The instance is not assigned to this host.");
        }
        VirtualMachineTO vmTO = getVirtualMachineTO(userVm);
        Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
        access.put(ApiConstants.VIRTUAL_MACHINE, vmTO.getExternalDetails());
        Map<String, Object> payload = loadAccessDetails(access, vmTO);
        Map<String, Object> nicDetails = new HashMap<>();
        nicDetails.put("mac", nic.getMac());
        nicDetails.put("deviceId", nic.getDeviceId());
        nicDetails.put("broadcastUri", nic.getBroadcastUri() == null ? null : nic.getBroadcastUri().toString());
        payload.put(ApiConstants.PARAMETERS, Map.of("nic", nicDetails, "expectedpowerstate",
                state == VirtualMachine.State.Running ? "running" : "stopped"));
        Pair<Boolean, String> result = executeExternalCommand(extensionName, action, payload, wait,
                "Failed to change NIC on the external system", extensionPath);
        if (!result.first() && "Timeout".equals(result.second())) {
            return new Pair<>(false, "NIC_OUTCOME_UNKNOWN: extension execution timed out");
        }
        return result;
    }

    protected boolean createExtensionPath(String extensionName, Path destinationPathObj) throws IOException {
        String sourceScriptPath = Script.findScript("", BASE_EXTERNAL_PROVISIONER_SHELL_SCRIPT);
        if(sourceScriptPath == null) {
            logger.error("Failed to find base script for preparing extension: {}",
                    extensionName);
            return false;
        }
        Path sourcePath = Paths.get(sourceScriptPath);
        Files.copy(sourcePath, destinationPathObj, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    @Override
    public void prepareExtensionPath(String extensionName, boolean userDefined,
                           String extensionRelativePath) {
        logger.debug("Preparing entry point for Extension [name: {}, user-defined: {}]", extensionName, userDefined);
        if (!userDefined) {
            logger.debug("Skipping preparing entry point for inbuilt extension: {}", extensionName);
            return;
        }
        String destinationPath = getExtensionPath(extensionRelativePath);
        if (!destinationPath.endsWith(".sh")) {
            logger.info("File {} for extension: {} is not a bash script, skipping copy.", destinationPath,
                    extensionName);
            return;
        }
        File destinationFile = new File(destinationPath);
        if (destinationFile.exists()) {
            logger.info("File already exists at {} for extension: {}, skipping copy.", destinationPath,
                    extensionName);
            return;
        }
        CloudRuntimeException exception =
                new CloudRuntimeException(String.format("Failed to prepare scripts for extension: %s", extensionName));
        if (!checkExtensionsDirectory()) {
            throw exception;
        }
        Path destinationPathObj = Paths.get(destinationPath);
        Path destinationDirPath = destinationPathObj.getParent();
        if (destinationDirPath == null) {
            logger.error("Failed to find parent directory for extension: {} script path {}",
                    extensionName, destinationPath);
            throw exception;
        }
        try {
            Files.createDirectories(destinationDirPath);
        } catch (IOException e) {
            logger.error("Failed to create directory: {} for extension: {}", destinationDirPath,
                    extensionName, e);
            throw exception;
        }
        try {
            if (!createExtensionPath(extensionName, destinationPathObj)) {
                throw exception;
            }
        } catch (IOException e) {
            logger.error("Failed to copy entry point file to [{}] for extension: {}",
                    destinationPath, extensionName, e);
            throw exception;
        }
        logger.debug("Successfully prepared entry point [{}] for extension: {}", destinationPath,
                extensionName);
    }

    @Override
    public void cleanupExtensionPath(String extensionName, String extensionRelativePath) {
        String normalizedPath = extensionRelativePath;
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        try {
            Path rootPath = Paths.get(extensionsDirectory).toAbsolutePath().normalize();
            String extensionDirName = Extension.getDirectoryName(extensionName);
            Path filePath = rootPath
                    .resolve(normalizedPath.startsWith(extensionDirName) ? extensionDirName : normalizedPath)
                    .normalize();
            if (!Files.exists(filePath)) {
                return;
            }
            if (!Files.isDirectory(filePath) && !Files.isRegularFile(filePath)) {
                throw new CloudRuntimeException(
                        String.format("Failed to cleanup path: %s for extension: %s as it either " +
                                        "does not exist or is not a regular file/directory",
                                extensionName, extensionRelativePath));
            }
            if (!FileUtil.deleteRecursively(filePath)) {
                throw new CloudRuntimeException(
                        String.format("Failed to delete path: %s for extension: %s",
                                extensionName, filePath));
            }
        } catch (IOException e) {
            throw new CloudRuntimeException(
                    String.format("Failed to cleanup path: %s for extension: %s due to: %s",
                            extensionName, normalizedPath, e.getMessage()), e);
        }
    }

    @Override
    public void cleanupExtensionData(String extensionName, int olderThanDays, boolean cleanupDirectory) {
        String extensionPayloadDirPath = extensionsDataDirectory + File.separator + extensionName;
        Path dirPath = Paths.get(extensionPayloadDirPath);
        if (!Files.exists(dirPath)) {
            return;
        }
        try {
            if (cleanupDirectory) {
                try (Stream<Path> paths = Files.walk(dirPath)) {
                    paths.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
                return;
            }
            long cutoffMillis = System.currentTimeMillis() - (olderThanDays * 24L * 60 * 60 * 1000);
            long lastModified = Files.getLastModifiedTime(dirPath).toMillis();
            if (lastModified < cutoffMillis) {
                return;
            }
            try (Stream<Path> paths = Files.walk(dirPath)) {
                paths.filter(path -> !path.equals(dirPath))
                        .filter(path -> {
                            try {
                                return Files.getLastModifiedTime(path).toMillis() < cutoffMillis;
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (java.io.UncheckedIOException e) {
                // A payload an action removed itself while the directory was being read; the next pass goes on.
                if (!(e.getCause() instanceof java.nio.file.NoSuchFileException)) {
                    throw e;
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to clean up extension payloads for {}: {}", extensionName, e.getMessage());
        }
    }

    public Pair<Boolean, String> runCustomActionOnExternalSystem(String extensionName, String filename,
                 String actionName, Map<String, Object> accessDetails, int wait) {
        return executeExternalCommand(extensionName, actionName, accessDetails, wait,
                String.format("Failed to execute custom action '%s' on external system", actionName), filename);
    }

    protected VirtualMachine.PowerState getPowerStateFromString(String powerStateStr) {
        if (StringUtils.isBlank(powerStateStr)) {
            return VirtualMachine.PowerState.PowerUnknown;
        }
        if (powerStateStr.equalsIgnoreCase(VirtualMachine.PowerState.PowerOn.toString())) {
            return VirtualMachine.PowerState.PowerOn;
        } else if (powerStateStr.equalsIgnoreCase(VirtualMachine.PowerState.PowerOff.toString())) {
            return VirtualMachine.PowerState.PowerOff;
        }
        return VirtualMachine.PowerState.PowerUnknown;
    }

    protected VirtualMachine.PowerState parsePowerStateFromResponse(UserVmVO userVmVO, String response) {
        logger.debug("Power status response from the external system for {} : {}", userVmVO, response);
        if (StringUtils.isBlank(response)) {
            logger.warn("Empty response while trying to fetch the power status of the {}", userVmVO);
            return VirtualMachine.PowerState.PowerUnknown;
        }
        if (!response.trim().startsWith("{")) {
            return getPowerStateFromString(response);
        }
        try {
            JsonObject jsonObj = JsonParser.parseString(response).getAsJsonObject();
            String powerState = jsonObj.has("power_state") ? jsonObj.get("power_state").getAsString() : null;
            return getPowerStateFromString(powerState);
        } catch (Exception e) {
            logger.warn("Failed to parse power status response: {} for {} as JSON: {}",
                    response, userVmVO, e.getMessage());
            return VirtualMachine.PowerState.PowerUnknown;
        }
    }

    protected VirtualMachine.PowerState getVmPowerState(UserVmVO userVmVO, Map<String, Map<String, String>> accessDetails,
                  String extensionName, String extensionPath) {
        VirtualMachineTO virtualMachineTO = getVirtualMachineTO(userVmVO);
        accessDetails.put(ApiConstants.VIRTUAL_MACHINE, virtualMachineTO.getExternalDetails());
        Map<String, Object> modifiedDetails = loadAccessDetails(accessDetails, virtualMachineTO);
        String vmUUID = userVmVO.getUuid();
        logger.debug("Trying to get VM power status from the external system for {}", userVmVO);
        Pair<Boolean, String> result = getInstanceStatusOnExternalSystem(extensionName, extensionPath, vmUUID,
                modifiedDetails, AgentManager.Wait.value());
        if (!result.first()) {
            logger.warn("Failure response received while trying to fetch the power status of the {} : {}",
                    userVmVO, result.second());
            return VirtualMachine.PowerState.PowerUnknown;
        }
        return parsePowerStateFromResponse(userVmVO, result.second());
    }

    protected Map<String, HostVmStateReportEntry> getVmPowerStates(Host host,
                   Map<String, Map<String, String>> accessDetails, String extensionName, String extensionPath) {
        Map<String, Object> modifiedDetails = loadAccessDetails(accessDetails, null);
        List<String> unknown = addNativeStatusBindings(accessDetails, modifiedDetails);
        logger.debug("Trying to get VM power statuses from the external system for {}", host);
        Pair<Boolean, String> result = getInstanceStatusesOnExternalSystem(extensionName, extensionPath,
                host.getName(), modifiedDetails, AgentManager.Wait.value());
        if (!result.first()) {
            logger.warn("Failure response received while trying to fetch the power statuses for {} : {}",
                    host, result.second());
            return null;
        }
        if (StringUtils.isBlank(result.second())) {
            logger.warn("Empty response while trying to fetch VM power statuses for host: {}", host);
            return null;
        }
        try {
            JsonObject jsonObj = JsonParser.parseString(result.second()).getAsJsonObject();
            if (!jsonObj.has("status") || !"success".equalsIgnoreCase(jsonObj.get("status").getAsString())) {
                logger.warn("Invalid status in response while trying to fetch VM power statuses for host: {}: {}",
                        host, result.second());
                return null;
            }
            if (!jsonObj.has("power_state") || !jsonObj.get("power_state").isJsonObject()) {
                logger.warn("Missing or invalid power_state in response for host: {}: {}", host, result.second());
                return null;
            }
            JsonObject powerStates = jsonObj.getAsJsonObject("power_state");
            Map<String, HostVmStateReportEntry> states = new HashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : powerStates.entrySet()) {
                VirtualMachine.PowerState powerState = getPowerStateFromString(entry.getValue().getAsString());
                states.put(entry.getKey(), new HostVmStateReportEntry(powerState, "host-" + host.getId()));
            }
            for (String instanceName : unknown) {
                states.put(instanceName, new HostVmStateReportEntry(VirtualMachine.PowerState.PowerUnknown, "host-" + host.getId()));
            }
            return states;
        } catch (Exception e) {
            logger.warn("Failed to parse VM power statuses response for host: {}: {}", host, e.getMessage());
            return null;
        }
    }

    public Pair<Boolean, String> prepareExternalProvisioningInternal(String extensionName, String filename,
                             String vmUUID, Map<String, Object> accessDetails, int wait) {
        return executeExternalCommand(extensionName, "prepare", accessDetails, wait,
                String.format("Failed to prepare external provisioner for deploying VM %s on external system", vmUUID),
                filename);
    }

    public Pair<Boolean, String> deployInstanceOnExternalSystem(String extensionName, String filename, String vmUUID,
                            Map<String, Object> accessDetails, int wait) {
        return executeExternalCommand(extensionName, "create", accessDetails, wait,
                String.format("Failed to create the instance %s on external system", vmUUID), filename);
    }

    public Pair<Boolean, String> startInstanceOnExternalSystem(String extensionName, String filename, String vmUUID,
                               Map<String, Object> accessDetails, int wait) {
        return executeExternalCommand(extensionName, "start", accessDetails, wait,
                String.format("Failed to start the instance %s on external system", vmUUID), filename);
    }

    public Pair<Boolean, String> stopInstanceOnExternalSystem(String extensionName, String filename, String vmUUID,
                              Map<String, Object> accessDetails, int wait) {
        return executeExternalCommand(extensionName, "stop", accessDetails, wait,
                String.format("Failed to stop the instance %s on external system", vmUUID), filename);
    }

    public Pair<Boolean, String> rebootInstanceOnExternalSystem(String extensionName, String filename, String vmUUID,
                                Map<String, Object> accessDetails, int wait) {
        return executeExternalCommand(extensionName, "reboot", accessDetails, wait,
                String.format("Failed to reboot the instance %s on external system", vmUUID), filename);
    }

    public Pair<Boolean, String> deleteInstanceOnExternalSystem(String extensionName, String filename, String vmUUID,
                                Map<String, Object> accessDetails, int wait) {
        return executeExternalCommand(extensionName, "delete", accessDetails, wait,
                String.format("Failed to delete the instance %s on external system", vmUUID), filename);
    }

    public Pair<Boolean, String> getInstanceStatusOnExternalSystem(String extensionName, String filename, String vmUUID,
                               Map<String, Object> accessDetails, int wait) {
        return executeExternalCommand(extensionName, "status", accessDetails, wait,
                String.format("Failed to get the instance power status %s on external system", vmUUID), filename);
    }

    public Pair<Boolean, String> getInstanceStatusesOnExternalSystem(String extensionName, String filename,
                               String hostName, Map<String, Object> accessDetails, int wait) {
        return executeExternalCommand(extensionName, "statuses", accessDetails, wait,
                String.format("Failed to get the %s instances power status on external system", hostName), filename);
    }

    public Pair<Boolean, String> getInstanceConsoleOnExternalSystem(String extensionName, String filename,
                            String vmUUID, Map<String, Object> accessDetails, int wait) {
        return executeExternalCommand(extensionName, "getconsole", accessDetails, wait,
                String.format("Failed to get the instance console %s on external system", vmUUID), filename);
    }

    public Pair<Boolean, String> executeExternalCommand(String extensionName, String action,
                        Map<String, Object> accessDetails, int wait, String errorLogPrefix, String file) {
        NativeOperation nativeOperation = null;
        Process nativeProcess = null;
        String dataFile = null;
        try {
            Path executablePath = Paths.get(file).toAbsolutePath().normalize();
            if (!Files.isExecutable(executablePath)) {
                logger.error("{}: File is not executable: {}", errorLogPrefix, executablePath);
                return new Pair<>(false, "File is not executable");
            }
            if (wait == 0) {
                wait = AgentManager.Wait.value();
            }
            // Never accept a context supplied by command parameters or another caller.
            Map<String, Object> payload = new HashMap<>(accessDetails);
            payload.remove(NATIVE_CONTEXT);
            nativeOperation = prepareNativeOperation(action, payload, wait);
            if (nativeOperation != null && nativeOperation.retired) {
                return new Pair<>(true, "{\"status\":\"success\",\"message\":\"Instance already deleted\"}");
            }
            List<String> command = new ArrayList<>();
            command.add(executablePath.toString());
            command.add(action);
            dataFile = prepareExternalPayload(extensionName, payload);
            command.add(dataFile);
            command.add(Integer.toString(wait));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);

            logger.debug("Executing {} for command: {} with wait: {} and data file: {}", executablePath,
                    action, wait, dataFile);

            if (nativeOperation != null && nativeOperation.lease != null) {
                nativeOperation.registry.assertLease(nativeOperation.identity, nativeOperation.lease.action, nativeOperation.lease.id);
                nativeOperation.registry.assertLeaseForDuration(nativeOperation.identity, nativeOperation.lease.action, nativeOperation.lease.id, wait + 30);
            }
            Process process = builder.start();
            if (nativeOperation != null && nativeOperation.lease != null) { nativeProcess = process; }
            boolean finished = process.waitFor(wait, TimeUnit.SECONDS);
            if (!finished) {
                // Stop what the script started (transports, qemu-img) as well as the script; for a native
                // operation an unknown outcome still retains the durable operating fence.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                logger.error("{}: External API execution timed out after {} seconds", errorLogPrefix, wait);
                return nativeProcess == null ? new Pair<>(false, "Timeout") : nativeUncertainOutcome(action);
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.warn("{}: External API execution failed with exit code {}", errorLogPrefix, exitCode);
                if (nativeProcess != null && exitCode != NATIVE_KNOWN_FAILURE) { return nativeUncertainOutcome(action); }
                if (nativeProcess != null) {
                    // The provider did not change, so this is an ordinary failure and the lease is returned.
                    nativeOperation.registry.abandon(nativeOperation.identity, nativeOperation.lease.action, nativeOperation.lease.id);
                }
                return new Pair<>(false, "Exit code: " + exitCode + ", Output: " + output.toString().trim());
            }
            if (nativeOperation != null && nativeOperation.lease != null) {
                try {
                    JsonObject result = JsonParser.parseString(output.toString()).getAsJsonObject();
                    if (!"success".equals(field(result, "status"))) {
                        return nativeUncertainOutcome(action);
                    }
                } catch (RuntimeException invalid) {
                    return nativeUncertainOutcome(action);
                }
                nativeOperation.registry.assertLease(nativeOperation.identity, nativeOperation.lease.action, nativeOperation.lease.id);
                if ("delete".equals(nativeOperation.lease.action)) {
                    nativeOperation.registry.completeDelete(nativeOperation.identity, nativeOperation.lease.id);
                } else {
                    nativeOperation.registry.complete(nativeOperation.identity, nativeOperation.lease.action, nativeOperation.lease.id);
                }
            }
            return new Pair<>(true, output.toString().trim());

        } catch (IOException | InterruptedException e) {
            if (nativeProcess != null) {
                nativeProcess.descendants().forEach(ProcessHandle::destroyForcibly);
                nativeProcess.destroyForcibly();
                if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                return nativeUncertainOutcome(action);
            }
            abandonUnsentNativeOperation(nativeOperation);
            logger.error("{}: External operation failed", errorLogPrefix, e);
            throw new CloudRuntimeException(String.format("%s: External operation failed", errorLogPrefix), e);
        } catch (RuntimeException e) {
            // Completion/read-back failures after a provider write must not enter
            // ordinary NIC allocation cleanup or trigger a compensating write.
            if (nativeProcess != null) { return nativeUncertainOutcome(action); }
            abandonUnsentNativeOperation(nativeOperation);
            throw e;
        } finally {
            // Whatever the outcome; only the payloads of trivial read-only actions are removed here.
            if (dataFile != null) {
                deleteExtensionPayloadFile(extensionName, action, dataFile);
            }
        }
    }

    /** The extension was never started, so the lease taken for it fences nothing. */
    private void abandonUnsentNativeOperation(NativeOperation operation) {
        if (operation == null || operation.lease == null) { return; }
        try {
            operation.registry.abandon(operation.identity, operation.lease.action, operation.lease.id);
        } catch (RuntimeException e) {
            logger.warn("Could not return the unused adoption lease {}: {}", operation.lease.id, e.getMessage());
        }
    }

    private Pair<Boolean, String> nativeUncertainOutcome(String action) {
        String prefix = ("plugnic".equals(action) || "unplugnic".equals(action)) ? "NIC_OUTCOME_UNKNOWN: " : "";
        return new Pair<>(false, prefix + "Native adoption outcome is unknown; inspect guest and claim before recovery");
    }

    protected void deleteExtensionPayloadFile(String extensionName, String action, String payloadFileName) {
        if (!TRIVIAL_ACTIONS.contains(action)) {
            return;
        }
        logger.trace("Deleting payload file: {} for extension: {}, action: {}, file: {}",
                payloadFileName, extensionName, action);
        FileUtil.deletePath(payloadFileName);
    }

    protected void scheduleExtensionPayloadDirectoryCleanup(String extensionName) {
        try {
            Future<?> future = payloadCleanupExecutor.submit(() -> {
                try {
                    cleanupExtensionData(extensionName, 1, false);
                    logger.trace("Cleaned up payload directory for extension: {}", extensionName);
                } catch (Exception e) {
                    logger.warn("Exception during payload cleanup for extension: {} due to {}", extensionName,
                            e.getMessage());
                    logger.trace(e);
                }
            });
            payloadCleanupScheduler.schedule(() -> {
                try {
                    if (!future.isDone()) {
                        future.cancel(true);
                        logger.trace("Cancelled cleaning up payload directory for extension: {} as it " +
                                "running for more than 3 seconds", extensionName);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to cancel payload cleanup task for extension: {} due to {}",
                            extensionName, e.getMessage());
                    logger.trace(e);
                }
            }, 3, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            logger.warn("Payload cleanup task for extension: {} was rejected due to: {}", extensionName,
                    e.getMessage());
            logger.trace(e);
        }
    }

    protected String prepareExternalPayload(String extensionName, Map<String, Object> details) throws IOException {
        String json = GsonHelper.getGson().toJson(details);
        String fileName = UUID.randomUUID() + ".json";
        String extensionPayloadDir = extensionsDataDirectory + File.separator + extensionName;
        Path payloadDirPath = Paths.get(extensionPayloadDir);
        if (!Files.exists(payloadDirPath)) {
            Files.createDirectories(payloadDirPath);
        } else {
            scheduleExtensionPayloadDirectoryCleanup(extensionName);
        }
        Path payloadFile = payloadDirPath.resolve(fileName);
        Files.writeString(payloadFile, json, StandardOpenOption.CREATE_NEW);
        return payloadFile.toAbsolutePath().toString();
    }

    @Override
    public List<Class<?>> getCommands() {
        return new ArrayList<>();
    }
}
