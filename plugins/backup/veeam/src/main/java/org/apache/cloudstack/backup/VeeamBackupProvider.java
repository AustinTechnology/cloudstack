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

package org.apache.cloudstack.backup;

import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.veeam.VeeamBackupOffering;
import org.apache.cloudstack.backup.veeam.VeeamClient;
import org.apache.cloudstack.backup.veeam.api.Job;
import org.apache.cloudstack.backup.veeam.rest.VbrRestClient;
import org.apache.cloudstack.backup.veeam.rest.VbrRestorePoints;
import org.apache.cloudstack.backup.veeam.rest.VspcClient;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.EventVO;
import com.cloud.user.User;
import org.apache.cloudstack.api.ApiCommandResourceType;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.dc.VmwareDatacenter;
import com.cloud.hypervisor.vmware.VmwareDatacenterZoneMap;
import com.cloud.dc.dao.VmwareDatacenterDao;
import com.cloud.hypervisor.vmware.dao.VmwareDatacenterZoneMapDao;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.Pair;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class VeeamBackupProvider extends AdapterBase implements BackupProvider, Configurable {

    public static final String BACKUP_IDENTIFIER = "-CSBKP-";

    /** Instance detail caching the hypervisor's own VM UUID, by which Veeam identifies an External instance. */
    public static final String VM_UUID_DETAIL = "veeam.vm.uuid";
    /**
     * Instance detail for a Veeam membership change CloudStack still has to make: "add", "remove", or the same with
     * "-failed" after an attempt failed. Assigning and removing offerings run inside a database transaction, so the
     * provider-side change (an extension call or a Veeam job edit) is made by the next backup sync instead.
     */
    public static final String MEMBERSHIP_DETAIL = "veeam.membership";
    /** Instance detail: the Proxmox pool the provider put the instance in, so removal takes it out of that pool. */
    public static final String POOL_DETAIL = "veeam.proxmox.pool";
    /** Instance detail: the Veeam (Hyper-V) job the provider added the instance to, for removal after the offering is gone. */
    public static final String JOB_DETAIL = "veeam.job";
    /** Instance detail: the instance's own Veeam job (public REST id), cloned from its offering's template job. */
    public static final String VM_JOB_DETAIL = "veeam.vmjob";
    /** Instance detail: the same job's id in the Veeam Proxmox plug-in, which creates, edits and deletes Proxmox jobs. */
    public static final String PVE_JOB_DETAIL = "veeam.proxmox.job";
    /** Instance detail: a quick restore (Veeam Instant Recovery) that is still being finished, as JSON. */
    public static final String QUICK_RESTORE_DETAIL = "veeam.quickrestore";
    /** Instance detail: its CloudStack schedules changed and are still to be written to its Veeam job ("pending"). */
    public static final String SCHEDULE_DETAIL = "veeam.schedule";
    /** Instance detail: the backup copy job (public REST id) that copies its own job offsite. */
    public static final String COPY_DETAIL = "veeam.copyjob";
    /** Instance detail: its own job still has to be assigned to the customer's company in VSPC ("pending"). */
    public static final String VSPC_DETAIL = "veeam.vspc";
    private static final Set<String> REST_JOB_TYPES = Set.of("ProxmoxBackupJob", "HyperVBackup");
    private static final String UUID_PATTERN = "^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$";

    public ConfigKey<String> VeeamUrl = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.url", "https://localhost:9398/api/",
            "The Veeam backup and recovery URL.", true, ConfigKey.Scope.Zone);

    public ConfigKey<Integer> VeeamVersion = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.veeam.version", "0",
            "The version of Veeam backup and recovery. CloudStack will get Veeam server version via PowerShell commands if it is 0 or not set", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> VeeamUsername = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.username", "administrator",
            "The Veeam backup and recovery username.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> VeeamPassword = new ConfigKey<>("Secure", String.class,
            "backup.plugin.veeam.password", "",
            "The Veeam backup and recovery password.", true, ConfigKey.Scope.Zone);

    private ConfigKey<Boolean> VeeamValidateSSLSecurity = new ConfigKey<>("Advanced", Boolean.class, "backup.plugin.veeam.validate.ssl", "false",
            "When set to true, this will validate the SSL certificate when connecting to https/ssl enabled Veeam API service.", true, ConfigKey.Scope.Zone);

    private ConfigKey<Integer> VeeamApiRequestTimeout = new ConfigKey<>("Advanced", Integer.class, "backup.plugin.veeam.request.timeout", "300",
            "The Veeam B&R API request timeout in seconds.", true, ConfigKey.Scope.Zone);

    private static ConfigKey<Integer> VeeamRestoreTimeout = new ConfigKey<>("Advanced", Integer.class, "backup.plugin.veeam.restore.timeout", "600",
            "The Veeam B&R API restore backup timeout in seconds.", true, ConfigKey.Scope.Zone);

    private static ConfigKey<Integer> VeeamTaskPollInterval = new ConfigKey<>("Advanced", Integer.class, "backup.plugin.veeam.task.poll.interval", "5",
            "The time interval in seconds when the management server polls for Veeam task status.", true, ConfigKey.Scope.Zone);

    private static ConfigKey<Integer> VeeamTaskPollMaxRetry = new ConfigKey<>("Advanced", Integer.class, "backup.plugin.veeam.task.poll.max.retry", "120",
            "The max number of retrying times when the management server polls for Veeam task status.", true, ConfigKey.Scope.Zone);

    private ConfigKey<Boolean> VeeamEnterpriseManagerEnabled = new ConfigKey<>("Advanced", Boolean.class,
            "backup.plugin.veeam.em.enabled", "true",
            "Whether the Veeam Backup Enterprise Manager API (backup.plugin.veeam.url) is used, for VMware instances. "
                    + "Set to false when Veeam serves only External instances through the REST API.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> VeeamRestUrl = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.rest.url", "",
            "The Veeam Backup & Replication REST API URL, for example https://vbr.example.com:443. It backs up External "
                    + "instances (Proxmox VE, Microsoft Hyper-V). Empty disables it.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> VeeamRestUsername = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.rest.username", "",
            "The Veeam Backup & Replication REST API user (a service account without multi-factor authentication).", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> VeeamRestPassword = new ConfigKey<>("Secure", String.class,
            "backup.plugin.veeam.rest.password", "",
            "The Veeam Backup & Replication REST API password.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> VeeamRestApiVersion = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.rest.api.version", "1.3-rev2",
            "The Veeam Backup & Replication REST API revision sent as x-api-version.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> VeeamProxmoxPool = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.proxmox.pool", "",
            "The Proxmox pool the domain's instances join when they are assigned a Veeam backup offering: the pool the "
                    + "offering's Veeam job protects. Empty means a pool named like the domain.", true, ConfigKey.Scope.Domain);

    private ConfigKey<String> VeeamTemplatePrefix = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.template.prefix", "CloudStack template",
            "A Veeam job whose name starts with this is a template: each instance assigned its offering gets its own copy "
                    + "of the job, which CloudStack schedules and starts. Empty turns templates off.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> VeeamQuickRestoreStorage = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.proxmox.quickrestore.storage", "",
            "The Proxmox storage a quick restore runs the instance's disks from while Veeam copies them off the backup: shared "
                    + "storage holding qcow2 on every node (NFS, or a directory on a shared file system). Afterwards the disks move "
                    + "back to their own storage. Empty turns quick restore of Proxmox instances off.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> VeeamCopyJob = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.copy.job", "",
            "The name of the customer's Veeam backup copy job that copies its backups offsite. Offsite offerings (a template "
                    + "job that a template backup copy job copies) add each instance's own job to it.", true, ConfigKey.Scope.Domain);

    private ConfigKey<String> VeeamCopyRepository = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.copy.repository", "",
            "The id of the repository (for example the customer's Veeam Cloud Connect repository) a backup copy job made from the "
                    + "template copies to, for a customer without backup.plugin.veeam.copy.job.", true, ConfigKey.Scope.Domain);

    private ConfigKey<String> VspcUrl = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.vspc.url", "",
            "The Veeam Service Provider Console URL (https://vspc.example.com:1280). When set, instances' own Veeam jobs are "
                    + "assigned to their customer's company in VSPC.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> VspcApiKey = new ConfigKey<>("Secure", String.class,
            "backup.plugin.veeam.vspc.api.key", "", "A VSPC REST API key allowed to assign jobs to companies.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> VspcCompany = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.veeam.vspc.company", "",
            "The VSPC company (UID) the domain's instance jobs are assigned to. Empty means the company VSPC assigns the "
                    + "domain's backup copy job to.", true, ConfigKey.Scope.Domain);

    @Inject
    private ExtensionsManager extensionsManager;
    @Inject
    private org.apache.cloudstack.backup.dao.BackupOfferingDao backupOfferingDao;
    @Inject
    private org.apache.cloudstack.backup.dao.BackupScheduleDao backupScheduleDao;
    @Inject
    private com.cloud.storage.dao.VolumeDetailsDao volumeDetailsDao;
    @Inject
    private org.apache.cloudstack.extension.ExtensionHelper extensionHelper;
    @Inject
    private com.cloud.host.dao.HostDao hostDao;
    @Inject
    private com.cloud.domain.dao.DomainDao domainDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private VmwareDatacenterZoneMapDao vmwareDatacenterZoneMapDao;
    @Inject
    private VmwareDatacenterDao vmwareDatacenterDao;
    @Inject
    private BackupDao backupDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private VirtualMachineManager virtualMachineManager;
    @Inject
    private BackupManager backupManager;
    @Inject
    private VolumeDao volumeDao;

    private Map<String, Backup.Metric> backupFilesMetricsMap = new HashMap<>();
    private final VbrRestorePoints restRestorePoints = new VbrRestorePoints();
    /** Veeam job states per zone, as of the last backup sync (job id to state). */
    private final Map<Long, Map<String, VbrRestClient.JobState>> restJobStates = new java.util.concurrent.ConcurrentHashMap<>();
    /** Per zone: template backup copy jobs by the template job they copy (offsite offerings), as of the last sync. */
    private final Map<Long, Map<String, com.fasterxml.jackson.databind.JsonNode>> copyTemplates = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean isExternal(final VirtualMachine vm) {
        return vm != null && vm.getHypervisorType() == Hypervisor.HypervisorType.External;
    }

    private boolean isRestEnabled(final Long zoneId) {
        return StringUtils.isNotBlank(VeeamRestUrl.valueIn(zoneId));
    }

    private boolean isEnterpriseManagerEnabled(final Long zoneId) {
        return BooleanUtils.isTrue(VeeamEnterpriseManagerEnabled.valueIn(zoneId));
    }

    protected VbrRestClient getRestClient(final Long zoneId) {
        if (!isRestEnabled(zoneId)) {
            throw new CloudRuntimeException("Backups of External instances need the Veeam REST API: set backup.plugin.veeam.rest.url for the zone");
        }
        try {
            return new VbrRestClient(VeeamRestUrl.valueIn(zoneId), VeeamRestApiVersion.valueIn(zoneId), VeeamRestUsername.valueIn(zoneId),
                    VeeamRestPassword.valueIn(zoneId), VeeamValidateSSLSecurity.valueIn(zoneId), VeeamApiRequestTimeout.valueIn(zoneId));
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new CloudRuntimeException("Failed to build the Veeam REST API client: " + e.getMessage(), e);
        }
    }

    /**
     * The hypervisor's own UUID of an External instance (Proxmox SMBIOS UUID, Hyper-V VM GUID), read once from the
     * extension's configuration snapshot and kept as an instance detail. Null when the extension does not report it.
     * Calls the extension, so never call it inside a database transaction.
     */
    protected String getBackupUuid(final VMInstanceVO vm) {
        VMInstanceDetailVO cached = vmInstanceDetailsDao.findDetail(vm.getId(), VM_UUID_DETAIL);
        if (cached != null && StringUtils.isNotBlank(cached.getValue())) {
            return cached.getValue();
        }
        Answer answer = extensionsManager.getInstanceConfig(vm);
        if (answer == null || !answer.getResult()) {
            logger.debug("No configuration snapshot of {} for its Veeam identity: {}", vm, answer == null ? null : answer.getDetails());
            return null;
        }
        try {
            JsonObject identity = new JsonParser().parse(answer.getDetails()).getAsJsonObject().getAsJsonObject("identity");
            JsonElement uuid = identity == null ? null : identity.get("uuid");
            JsonElement vmid = identity == null ? null : identity.get("vmid");
            VMInstanceDetailVO recordedVmid = vmInstanceDetailsDao.findDetail(vm.getId(), "proxmox_vmid");
            if (recordedVmid != null && vmid != null && !recordedVmid.getValue().equals(vmid.getAsString())) {
                logger.warn("The configuration snapshot of {} is for another VMID; not using it for Veeam", vm);
                return null;
            }
            if (uuid == null || !uuid.isJsonPrimitive() || !uuid.getAsString().matches(UUID_PATTERN)) {
                logger.debug("The extension of {} reports no hypervisor UUID", vm);
                return null;
            }
            vmInstanceDetailsDao.addDetail(vm.getId(), VM_UUID_DETAIL, uuid.getAsString(), false);
            return uuid.getAsString();
        } catch (RuntimeException e) {
            logger.warn("Unreadable configuration snapshot of {}: {}", vm, e.getMessage());
            return null;
        }
    }

    /** Whether the offering's Veeam job is a template: instances get their own copy of it. */
    protected boolean isTemplateOffering(final BackupOffering offering) {
        String prefix = offering == null ? null : StringUtils.trimToNull(VeeamTemplatePrefix.valueIn(offering.getZoneId()));
        VbrRestClient.JobState state = prefix == null ? null : restJobState(offering.getZoneId(), offering.getExternalId());
        return state != null && state.name != null && state.name.startsWith(prefix);
    }

    /** The Veeam job that backs the instance up: its own job when it has one, else its offering's job. */
    protected String jobIdOf(final VirtualMachine vm) {
        VMInstanceDetailVO own = vmInstanceDetailsDao.findDetail(vm.getId(), VM_JOB_DETAIL);
        return own != null && StringUtils.isNotBlank(own.getValue()) ? own.getValue() : vm.getBackupExternalId();
    }

    /**
     * "CS - &lt;domain&gt; - &lt;instance name&gt;", within Veeam's 40 characters: says which customer the job bills to
     * (VSPC assigns jobs to companies) and which instance it protects. The job itself is found by the instance UUID
     * in its description, so names may repeat and may change with the instance or domain.
     */
    protected String instanceJobName(final VirtualMachine vm) {
        com.cloud.domain.DomainVO domain = domainDao.findById(vm.getDomainId());
        String name = StringUtils.defaultIfBlank(vm.getHostName(), vm.getInstanceName());
        String customer = StringUtils.left(domain == null ? "unknown" : domain.getName(), 16);
        // Veeam refuses these characters (and line feeds) in job names: CMCO.net.au becomes CMCO-net-au.
        return StringUtils.left("CS - " + customer + " - " + name, 40).replaceAll("[~\"#%&*:<>!?/\\\\{|}.'`$\\r\\n]", "-");
    }

    private static String instanceJobDescription(final VirtualMachine vm, final String template) {
        return String.format("CloudStack instance %s (%s), from %s. Made, scheduled and deleted by CloudStack.", vm.getUuid(),
                vm.getHostName(), template);
    }

    private String proxmoxPoolFor(final VirtualMachine vm) {
        String pool = StringUtils.trimToNull(VeeamProxmoxPool.valueInDomain(vm.getDomainId()));
        if (pool == null) {
            com.cloud.domain.DomainVO domain = domainDao.findById(vm.getDomainId());
            pool = domain == null ? null : domain.getName();
        }
        return pool;
    }

    /** Whether the instance's orchestrator extension manages provider pools (Proxmox); otherwise it is Hyper-V. */
    private boolean usesPools(final VirtualMachine vm) {
        Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
        com.cloud.host.HostVO host = hostId == null ? null : hostDao.findById(hostId);
        org.apache.cloudstack.extension.Extension extension = host == null || host.getClusterId() == null
                ? null : extensionHelper.getExtensionForCluster(host.getClusterId());
        return extension != null && extensionHelper.isInstancePoolSupported(extension.getId());
    }

    /** Makes the pending Veeam membership changes. Calls extensions and Veeam, so it runs outside transactions. */
    private void applyPendingMemberships(final Long zoneId) {
        for (VMInstanceDetailVO pending : vmInstanceDetailsDao.findDetails(MEMBERSHIP_DETAIL)) {
            VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(pending.getResourceId());
            if (vm == null || vm.getRemoved() != null || vm.getState() == VirtualMachine.State.Expunging) {
                vmInstanceDetailsDao.removeDetail(pending.getResourceId(), MEMBERSHIP_DETAIL);
                continue;
            }
            if (!zoneId.equals(vm.getDataCenterId()) || !isExternal(vm)) {
                continue;
            }
            boolean add = pending.getValue().startsWith("add");
            try {
                String outcome = add ? addMembership(vm) : removeMembership(vm);
                vmInstanceDetailsDao.removeDetail(vm.getId(), MEMBERSHIP_DETAIL);
                logger.info("Veeam membership of {}: {}", vm, outcome);
                ActionEventUtils.onActionEvent(User.UID_SYSTEM, vm.getAccountId(), vm.getDomainId(),
                        add ? EventTypes.EVENT_VM_BACKUP_OFFERING_ASSIGN : EventTypes.EVENT_VM_BACKUP_OFFERING_REMOVE,
                        "Veeam: " + outcome, vm.getId(), ApiCommandResourceType.VirtualMachine.toString());
            } catch (RuntimeException e) {
                logger.warn("Veeam membership change ({}) of {} failed: {}", pending.getValue(), vm, e.getMessage());
                if (!pending.getValue().endsWith("-failed")) {
                    vmInstanceDetailsDao.addDetail(vm.getId(), MEMBERSHIP_DETAIL, (add ? "add" : "remove") + "-failed", false);
                    ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR,
                            add ? EventTypes.EVENT_VM_BACKUP_OFFERING_ASSIGN : EventTypes.EVENT_VM_BACKUP_OFFERING_REMOVE,
                            "Veeam membership change failed (retrying each backup sync): " + e.getMessage(), vm.getId(),
                            ApiCommandResourceType.VirtualMachine.toString(), 0);
                }
            }
        }
    }

    private String addMembership(final VMInstanceVO vm) {
        BackupOffering offering = vm.getBackupOfferingId() == null ? null : backupOfferingDao.findById(vm.getBackupOfferingId());
        if (isTemplateOffering(offering)) {
            return createInstanceJob(vm, offering);
        }
        if (usesPools(vm)) {
            String pool = proxmoxPoolFor(vm);
            if (pool == null) {
                throw new CloudRuntimeException("No Proxmox pool is known for the instance's domain");
            }
            Answer answer = extensionsManager.setInstancePool(vm, pool, false);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(extensionError(answer));
            }
            vmInstanceDetailsDao.addDetail(vm.getId(), POOL_DETAIL, pool, false);
            return "in Proxmox pool " + pool + " (Veeam picks it up at its next inventory refresh)";
        }
        String uuid = requireBackupUuid(vm);
        VbrRestClient client = getRestClient(vm.getDataCenterId());
        com.fasterxml.jackson.databind.node.ObjectNode job = (com.fasterxml.jackson.databind.node.ObjectNode) client.getJob(vm.getBackupExternalId());
        com.fasterxml.jackson.databind.node.ArrayNode includes = job.with("virtualMachines").withArray("includes");
        for (com.fasterxml.jackson.databind.JsonNode include : includes) {
            if (uuid.equalsIgnoreCase(include.path("objectId").asText())) {
                return "already in Veeam job " + job.path("name").asText();
            }
        }
        com.fasterxml.jackson.databind.JsonNode object = client.findHyperVVm(uuid);
        if (object == null) {
            throw new CloudRuntimeException("Veeam does not see a Hyper-V VM with GUID " + uuid);
        }
        includes.add(object);
        client.putJob(vm.getBackupExternalId(), job);
        return "added to Veeam job " + job.path("name").asText();
    }

    private String removeMembership(final VMInstanceVO vm) {
        if (vmInstanceDetailsDao.findDetail(vm.getId(), VM_JOB_DETAIL) != null) {
            return deleteInstanceJob(vm);
        }
        if (usesPools(vm)) {
            VMInstanceDetailVO recorded = vmInstanceDetailsDao.findDetail(vm.getId(), POOL_DETAIL);
            String pool = recorded != null ? recorded.getValue() : proxmoxPoolFor(vm);
            Answer answer = extensionsManager.setInstancePool(vm, pool, true);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(extensionError(answer));
            }
            vmInstanceDetailsDao.removeDetail(vm.getId(), POOL_DETAIL);
            return "out of Proxmox pool " + pool;
        }
        VMInstanceDetailVO job = vmInstanceDetailsDao.findDetail(vm.getId(), JOB_DETAIL);
        String uuid = requireBackupUuid(vm);
        if (job == null) {
            return "no Veeam job recorded";
        }
        VbrRestClient client = getRestClient(vm.getDataCenterId());
        com.fasterxml.jackson.databind.node.ObjectNode model = (com.fasterxml.jackson.databind.node.ObjectNode) client.getJob(job.getValue());
        com.fasterxml.jackson.databind.node.ArrayNode includes = model.with("virtualMachines").withArray("includes");
        boolean found = false;
        for (java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = includes.iterator(); it.hasNext(); ) {
            if (uuid.equalsIgnoreCase(it.next().path("objectId").asText())) {
                it.remove();
                found = true;
            }
        }
        if (!found) {
            throw new CloudRuntimeException("The VM is not listed itself in Veeam job " + model.path("name").asText()
                    + " (it may be included through a container); remove it in Veeam");
        }
        client.putJob(job.getValue(), model);
        vmInstanceDetailsDao.removeDetail(vm.getId(), JOB_DETAIL);
        return "removed from Veeam job " + model.path("name").asText();
    }

    /**
     * Gives the instance its own Veeam job: a copy of the offering's template job (repository, retention, processing)
     * that holds only this instance and has no Veeam schedule, so CloudStack's schedules and Backup now start it.
     * The template's schedule becomes the instance's first CloudStack schedule. Safe to repeat: a job already made
     * under the instance's name is reused, so a sync that failed half way finishes it.
     */
    private String createInstanceJob(final VMInstanceVO vm, final BackupOffering offering) {
        VbrRestClient client = getRestClient(vm.getDataCenterId());
        VbrRestClient.JobState template = restJobState(vm.getDataCenterId(), offering.getExternalId());
        String name = instanceJobName(vm);
        String description = instanceJobDescription(vm, template.name);
        String publicId = null;
        if (usesPools(vm)) {
            String uuid = requireBackupUuid(vm);
            VMInstanceDetailVO vmid = vmInstanceDetailsDao.findDetail(vm.getId(), "proxmox_vmid");
            if (vmid == null || StringUtils.isBlank(vmid.getValue())) {
                throw new CloudRuntimeException("The instance has no Proxmox VMID recorded");
            }
            com.fasterxml.jackson.databind.JsonNode job = client.findProxmoxJobByDescription(vm.getUuid());
            String pveId;
            if (job == null) {
                com.fasterxml.jackson.databind.node.ObjectNode object = client.findProxmoxVm(uuid, vmid.getValue());
                if (object == null) {
                    client.rescanProxmoxInventory();
                    throw new CloudRuntimeException("Veeam does not list the VM yet; it was asked to read the Proxmox inventory again");
                }
                com.fasterxml.jackson.databind.JsonNode templateJob = client.findProxmoxJobByName(template.name);
                if (templateJob == null) {
                    throw new CloudRuntimeException("The Veeam Proxmox plug-in does not list the template job " + template.name);
                }
                pveId = client.cloneProxmoxJob(templateJob.path("id").asText());
                com.fasterxml.jackson.databind.node.ObjectNode settings = client.getProxmoxJobSettings(pveId);
                settings.put("name", name);
                settings.put("description", description);
                settings.putObject("includes").putArray("objects").add(object);
                settings.putObject("excludes").putArray("objects");
                client.putProxmoxJobSettings(pveId, settings);
                client.enableProxmoxJob(pveId);
                seedSchedule(vm, scheduleOfProxmoxJob(templateJob.path("settings").path("scheduleSettings")));
                logger.info("Created Veeam job {} for {} from template {}", name, vm, template.name);
            } else {
                pveId = job.path("id").asText();
                name = job.path("name").asText();
            }
            vmInstanceDetailsDao.addDetail(vm.getId(), PVE_JOB_DETAIL, pveId, false);
            for (VbrRestClient.JobState state : client.listJobStates()) {
                if (name.equals(state.name)) {
                    publicId = state.id;
                }
            }
            if (publicId == null) {
                throw new CloudRuntimeException("Veeam does not list the new job " + name + " yet");
            }
        } else {
            for (VbrRestClient.JobState state : client.listJobStates()) {
                if (name.equals(state.name)) {
                    publicId = state.id;
                }
            }
            if (publicId == null) {
                String uuid = requireBackupUuid(vm);
                com.fasterxml.jackson.databind.JsonNode object = client.findHyperVVm(uuid);
                if (object == null) {
                    throw new CloudRuntimeException("Veeam does not see a Hyper-V VM with GUID " + uuid);
                }
                publicId = client.cloneJob(offering.getExternalId()).path("id").asText();
                com.fasterxml.jackson.databind.node.ObjectNode job = (com.fasterxml.jackson.databind.node.ObjectNode) client.getJob(publicId);
                com.fasterxml.jackson.databind.JsonNode templateSchedule = job.path("schedule").deepCopy();
                job.put("name", name);
                job.put("description", description);
                com.fasterxml.jackson.databind.node.ObjectNode machines = job.with("virtualMachines");
                machines.putArray("includes").add(object);
                machines.with("excludes").putArray("vms");
                client.putJob(publicId, job);
                client.enableJob(publicId);
                seedSchedule(vm, scheduleOfHyperVJob(templateSchedule));
                logger.info("Created Veeam job {} for {} from template {}", name, vm, template.name);
            }
        }
        vmInstanceDetailsDao.addDetail(vm.getId(), VM_JOB_DETAIL, publicId, false);
        String outcome = "in its own Veeam job " + name + ", made from template " + template.name;
        if (usesPools(vm)) {
            outcome += leaveSharedPool(vm);
        }
        com.fasterxml.jackson.databind.JsonNode copyTemplate = copyTemplateOf(offering);
        if (copyTemplate != null) {
            outcome += ", copied offsite by " + addToCopyJob(vm, publicId, name, copyTemplate);
        }
        if (StringUtils.isNotBlank(VspcUrl.valueIn(vm.getDataCenterId()))) {
            vmInstanceDetailsDao.addDetail(vm.getId(), VSPC_DETAIL, "pending", false);
        }
        return outcome;
    }

    /** Deletes the instance's own Veeam job. Its restore points stay in the repository until removed in Veeam. */
    private String deleteInstanceJob(final VMInstanceVO vm) {
        VbrRestClient client = getRestClient(vm.getDataCenterId());
        VMInstanceDetailVO publicId = vmInstanceDetailsDao.findDetail(vm.getId(), VM_JOB_DETAIL);
        VMInstanceDetailVO copy = vmInstanceDetailsDao.findDetail(vm.getId(), COPY_DETAIL);
        if (copy != null) {
            removeFromCopyJob(client, copy.getValue(), publicId.getValue());
            vmInstanceDetailsDao.removeDetail(vm.getId(), COPY_DETAIL);
        }
        vmInstanceDetailsDao.removeDetail(vm.getId(), VSPC_DETAIL);
        VMInstanceDetailVO pveId = vmInstanceDetailsDao.findDetail(vm.getId(), PVE_JOB_DETAIL);
        VbrRestClient.JobState state = restJobState(vm.getDataCenterId(), publicId.getValue());
        if (pveId != null) {
            client.deleteProxmoxJob(pveId.getValue());
        } else if (state != null) {
            client.deleteJob(publicId.getValue());
        }
        vmInstanceDetailsDao.removeDetail(vm.getId(), VM_JOB_DETAIL);
        vmInstanceDetailsDao.removeDetail(vm.getId(), PVE_JOB_DETAIL);
        return "its Veeam job " + (state == null ? publicId.getValue() : state.name) + " was deleted (its restore points stay in Veeam)";
    }

    /** One CloudStack schedule from a Proxmox plug-in job schedule, or null when it has none CloudStack can express. */
    protected Pair<com.cloud.utils.DateUtil.IntervalType, String> scheduleOfProxmoxJob(final com.fasterxml.jackson.databind.JsonNode schedule) {
        if (!schedule.path("enabled").asBoolean(false)) {
            return null;
        }
        String type = schedule.path("type").asText();
        if ("Weekly".equals(type)) {
            String[] time = schedule.path("weekly").path("startTime").asText("").split(":");
            if (time.length < 2) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode days = schedule.path("weekly").path("selectedDays");
            if (!"EveryDay".equals(schedule.path("weekly").path("configuredDays").asText()) && days.size() == 1) {
                int day = java.time.DayOfWeek.valueOf(days.get(0).asText().toUpperCase()).getValue() % 7 + 1;
                return new Pair<>(com.cloud.utils.DateUtil.IntervalType.WEEKLY, String.format("%s:%s:%d", time[1], time[0], day));
            }
            return new Pair<>(com.cloud.utils.DateUtil.IntervalType.DAILY, String.format("%s:%s", time[1], time[0]));
        }
        if ("Monthly".equals(type) && "DayOfMonth".equals(schedule.path("monthly").path("configuredWeekOrDay").asText())) {
            String[] time = schedule.path("monthly").path("startTime").asText("").split(":");
            if (time.length >= 2) {
                return new Pair<>(com.cloud.utils.DateUtil.IntervalType.MONTHLY,
                        String.format("%s:%s:%d", time[1], time[0], schedule.path("monthly").path("dayOfMonth").asInt(1)));
            }
        }
        if ("Periodic".equals(type) && "EveryHour".equals(schedule.path("periodic").path("mode").asText())
                && schedule.path("periodic").path("interval").asInt(1) == 1) {
            return new Pair<>(com.cloud.utils.DateUtil.IntervalType.HOURLY, String.valueOf(schedule.path("periodic").path("startTimeWithinAnHour").asInt(0)));
        }
        return null;
    }

    /** The Veeam server's UTC offset, from any scheduled job's next run (Veeam schedules jobs in its local time). */
    private java.time.ZoneOffset vbrOffset(final Long zoneId) {
        Map<String, VbrRestClient.JobState> states = restJobStates.get(zoneId);
        for (VbrRestClient.JobState state : states == null ? new ArrayList<VbrRestClient.JobState>() : states.values()) {
            if (StringUtils.isNotBlank(state.nextRun)) {
                return java.time.OffsetDateTime.parse(state.nextRun).getOffset();
            }
        }
        throw new CloudRuntimeException("No scheduled Veeam job tells the Veeam server's time zone yet");
    }

    /** When a CloudStack schedule next runs, in the Veeam server's local time. */
    private java.time.OffsetDateTime nextRunAtVbr(final BackupSchedule schedule, final java.time.ZoneOffset offset) {
        java.util.Date next = com.cloud.utils.DateUtil.getNextRunTime(schedule.getScheduleType(), schedule.getSchedule(), schedule.getTimezone(), null);
        return next.toInstant().atOffset(offset);
    }

    private static String dayName(final java.time.DayOfWeek day) {
        return day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
    }

    /** Writes one CloudStack schedule (or none) into a Proxmox plug-in job's scheduleSettings. */
    protected void writeProxmoxSchedule(final com.fasterxml.jackson.databind.node.ObjectNode settings, final BackupSchedule schedule,
            final java.time.ZoneOffset offset) {
        com.fasterxml.jackson.databind.node.ObjectNode s = settings.with("scheduleSettings");
        if (schedule == null) {
            s.put("enabled", false);
            return;
        }
        java.time.OffsetDateTime at = nextRunAtVbr(schedule, offset);
        String time = String.format("%02d:%02d", at.getHour(), at.getMinute());
        s.put("enabled", true);
        switch (schedule.getScheduleType()) {
            case HOURLY:
                s.put("type", "Periodic");
                s.with("periodic").put("mode", "EveryHour").put("interval", 1).put("startTimeWithinAnHour", at.getMinute());
                break;
            case DAILY:
                s.put("type", "Weekly");
                com.fasterxml.jackson.databind.node.ObjectNode daily = s.with("weekly").put("startTime", time).put("configuredDays", "EveryDay");
                com.fasterxml.jackson.databind.node.ArrayNode all = daily.putArray("selectedDays");
                for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
                    all.add(dayName(d));
                }
                break;
            case WEEKLY:
                s.put("type", "Weekly");
                s.with("weekly").put("startTime", time).put("configuredDays", "SelectedDays").putArray("selectedDays").add(dayName(at.getDayOfWeek()));
                break;
            case MONTHLY:
                s.put("type", "Monthly");
                com.fasterxml.jackson.databind.node.ObjectNode monthly = s.with("monthly").put("startTime", time)
                        .put("configuredWeekOrDay", "DayOfMonth").put("dayOfMonth", at.getDayOfMonth());
                com.fasterxml.jackson.databind.node.ArrayNode months = monthly.putArray("months");
                for (java.time.Month m : java.time.Month.values()) {
                    months.add(m.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH));
                }
                break;
            default:
                throw new CloudRuntimeException("Veeam cannot run a " + schedule.getScheduleType() + " schedule");
        }
    }

    /** Writes one CloudStack schedule (or none) into a Hyper-V job's schedule (public REST model). */
    protected void writeHyperVSchedule(final com.fasterxml.jackson.databind.node.ObjectNode job, final BackupSchedule schedule,
            final java.time.ZoneOffset offset) {
        com.fasterxml.jackson.databind.node.ObjectNode s = job.with("schedule");
        s.with("daily").put("isEnabled", false);
        s.with("monthly").put("isEnabled", false);
        s.with("periodically").put("isEnabled", false);
        if (schedule == null) {
            s.put("runAutomatically", false);
            return;
        }
        java.time.OffsetDateTime at = nextRunAtVbr(schedule, offset);
        String time = String.format("%02d:%02d", at.getHour(), at.getMinute());
        s.put("runAutomatically", true);
        switch (schedule.getScheduleType()) {
            case HOURLY:
                s.with("periodically").put("isEnabled", true).put("periodicallyKind", "Hours").put("frequency", 1).put("startTimeWithinAnHour", at.getMinute());
                break;
            case DAILY:
                s.with("daily").put("isEnabled", true).put("localTime", time).put("dailyKind", "Everyday");
                break;
            case WEEKLY:
                com.fasterxml.jackson.databind.node.ObjectNode weekly = s.with("daily").put("isEnabled", true).put("localTime", time).put("dailyKind", "SelectedDays");
                weekly.putArray("days").add(dayName(at.getDayOfWeek()));
                break;
            case MONTHLY:
                com.fasterxml.jackson.databind.node.ObjectNode monthly = s.with("monthly").put("isEnabled", true).put("localTime", time)
                        .put("dayNumberInMonth", "OnDay").put("dayOfMonth", at.getDayOfMonth());
                com.fasterxml.jackson.databind.node.ArrayNode months = monthly.putArray("months");
                for (java.time.Month m : java.time.Month.values()) {
                    months.add(m.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH));
                }
                break;
            default:
                throw new CloudRuntimeException("Veeam cannot run a " + schedule.getScheduleType() + " schedule");
        }
    }

    /**
     * Keeps instances' own Veeam jobs as CloudStack has them: the job name (CS: domain - instance) and, after CloudStack's
     * schedules changed, the job's Veeam schedule. One step per instance per backup sync; outside transactions.
     */
    private void reconcileInstanceJobs(final Long zoneId) {
        for (VMInstanceDetailVO own : vmInstanceDetailsDao.findDetails(VM_JOB_DETAIL)) {
            VMInstanceVO vm = vmInstanceDao.findById(own.getResourceId());
            if (vm == null || !zoneId.equals(vm.getDataCenterId()) || vm.getBackupOfferingId() == null) {
                continue;
            }
            VMInstanceDetailVO pending = vmInstanceDetailsDao.findDetail(vm.getId(), SCHEDULE_DETAIL);
            VbrRestClient.JobState state = restJobState(zoneId, own.getValue());
            if (pending == null && (state == null || instanceJobName(vm).equals(state.name))) {
                continue;
            }
            try {
                String outcome = applyInstanceJob(vm, own.getValue());
                vmInstanceDetailsDao.removeDetail(vm.getId(), SCHEDULE_DETAIL);
                logger.info("Veeam job of {}: {}", vm, outcome);
                if (pending != null) {
                    ActionEventUtils.onActionEvent(User.UID_SYSTEM, vm.getAccountId(), vm.getDomainId(), EventTypes.EVENT_VM_BACKUP_SCHEDULE_CONFIGURE,
                            "Veeam: " + outcome, vm.getId(), ApiCommandResourceType.VirtualMachine.toString());
                }
            } catch (RuntimeException e) {
                logger.warn("Updating the Veeam job of {} failed: {}", vm, e.getMessage());
                if (pending != null && !"failed".equals(pending.getValue())) {
                    vmInstanceDetailsDao.addDetail(vm.getId(), SCHEDULE_DETAIL, "failed", false);
                    ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR, EventTypes.EVENT_VM_BACKUP_SCHEDULE_CONFIGURE,
                            "Writing the backup schedule to Veeam failed (retried each backup sync): " + e.getMessage(), vm.getId(),
                            ApiCommandResourceType.VirtualMachine.toString(), 0);
                }
            }
        }
    }

    private String applyInstanceJob(final VMInstanceVO vm, final String publicId) {
        VbrRestClient client = getRestClient(vm.getDataCenterId());
        List<BackupScheduleVO> schedules = backupScheduleDao.listByVM(vm.getId());
        BackupSchedule schedule = schedules.isEmpty() ? null : schedules.get(0);
        java.time.ZoneOffset offset = vbrOffset(vm.getDataCenterId());
        BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        VbrRestClient.JobState template = offering == null ? null : restJobState(vm.getDataCenterId(), offering.getExternalId());
        String name = instanceJobName(vm);
        String description = instanceJobDescription(vm, template == null ? "its template" : template.name);
        VMInstanceDetailVO pveId = vmInstanceDetailsDao.findDetail(vm.getId(), PVE_JOB_DETAIL);
        if (pveId != null) {
            com.fasterxml.jackson.databind.node.ObjectNode settings = client.getProxmoxJobSettings(pveId.getValue());
            settings.put("name", name);
            settings.put("description", description);
            writeProxmoxSchedule(settings, schedule, offset);
            client.putProxmoxJobSettings(pveId.getValue(), settings);
        } else {
            com.fasterxml.jackson.databind.node.ObjectNode job = (com.fasterxml.jackson.databind.node.ObjectNode) client.getJob(publicId);
            job.put("name", name);
            job.put("description", description);
            writeHyperVSchedule(job, schedule, offset);
            client.putJob(publicId, job);
        }
        return schedule == null ? name + " has no schedule in Veeam (backups run only on Backup now)"
                : String.format("%s runs %s at %s (%s) in Veeam", name, schedule.getScheduleType(), schedule.getSchedule(), schedule.getTimezone());
    }

    @Override
    public boolean canProviderApplyBackupSchedules(final BackupOffering offering) {
        return isTemplateOffering(offering);
    }

    /** A Veeam job runs one schedule: an instance with its own job takes one CloudStack schedule. */
    @Override
    public void validateBackupSchedule(final VirtualMachine vm, final com.cloud.utils.DateUtil.IntervalType intervalType) {
        for (BackupScheduleVO existing : backupScheduleDao.listByVM(vm.getId())) {
            if (existing.getScheduleType() != intervalType) {
                throw new InvalidParameterValueException(String.format("Veeam runs one schedule per instance; this instance has a %s schedule. "
                        + "Change that schedule, or delete it first", existing.getScheduleType()));
            }
        }
    }

    @Override
    public void backupSchedulesChanged(final VirtualMachine vm) {
        if (vmInstanceDetailsDao.findDetail(vm.getId(), VM_JOB_DETAIL) != null || vmInstanceDetailsDao.findDetail(vm.getId(), MEMBERSHIP_DETAIL) != null) {
            vmInstanceDetailsDao.addDetail(vm.getId(), SCHEDULE_DETAIL, "pending", false);
        }
    }

    /** One CloudStack schedule from a Hyper-V job schedule (daily only), or null. */
    protected Pair<com.cloud.utils.DateUtil.IntervalType, String> scheduleOfHyperVJob(final com.fasterxml.jackson.databind.JsonNode schedule) {
        com.fasterxml.jackson.databind.JsonNode daily = schedule.path("daily");
        String[] time = daily.path("localTime").asText("").split(":");
        if (!schedule.path("runAutomatically").asBoolean(false) || !daily.path("isEnabled").asBoolean(false) || time.length < 2) {
            return null;
        }
        return new Pair<>(com.cloud.utils.DateUtil.IntervalType.DAILY, String.format("%s:%s", time[1], time[0]));
    }

    /**
     * Records the template's schedule as the instance's first CloudStack schedule, in the Veeam server's time zone (taken
     * from any scheduled job's next run), unless the instance has schedules already.
     */
    private void seedSchedule(final VMInstanceVO vm, final Pair<com.cloud.utils.DateUtil.IntervalType, String> schedule) {
        if (schedule == null || !backupScheduleDao.listByVM(vm.getId()).isEmpty()) {
            return;
        }
        String timezone = "GMT";
        Map<String, VbrRestClient.JobState> states = restJobStates.get(vm.getDataCenterId());
        for (VbrRestClient.JobState state : states == null ? new ArrayList<VbrRestClient.JobState>() : states.values()) {
            if (StringUtils.isNotBlank(state.nextRun)) {
                java.time.ZoneOffset offset = java.time.OffsetDateTime.parse(state.nextRun).getOffset();
                timezone = "GMT" + (offset.getTotalSeconds() == 0 ? "" : offset.getId());
                break;
            }
        }
        java.util.Date next = com.cloud.utils.DateUtil.getNextRunTime(schedule.first(), schedule.second(), timezone, null);
        backupScheduleDao.persist(new BackupScheduleVO(vm.getId(), schedule.first(), schedule.second(), timezone, next, 0, null,
                vm.getAccountId(), vm.getDomainId(), false));
        logger.info("Scheduled the backups of {} {} at {} ({}), as its template job does", vm, schedule.first(), schedule.second(), timezone);
    }

    /**
     * Takes a Proxmox instance that now has its own job out of the customer pool, so the customer's shared pool job no
     * longer backs it up too. Its restore points in the shared job stay where they are and stay listed.
     */
    private String leaveSharedPool(final VMInstanceVO vm) {
        VMInstanceDetailVO recorded = vmInstanceDetailsDao.findDetail(vm.getId(), POOL_DETAIL);
        String pool = recorded != null ? recorded.getValue() : proxmoxPoolFor(vm);
        if (pool == null) {
            return "";
        }
        Answer answer = extensionsManager.setInstancePool(vm, pool, true);
        if (answer == null || !answer.getResult()) {
            String error = extensionError(answer);
            if (error.contains("does not exist")) {
                return "";
            }
            throw new CloudRuntimeException("Could not take the instance out of the shared Proxmox pool " + pool + ": " + error);
        }
        vmInstanceDetailsDao.removeDetail(vm.getId(), POOL_DETAIL);
        String previous = "";
        try {
            previous = new JsonParser().parse(answer.getDetails().substring(answer.getDetails().indexOf('{'))).getAsJsonObject().get("previous_pool").getAsString();
        } catch (RuntimeException e) {
            // not reported
        }
        return pool.equals(previous) ? ", out of the shared Proxmox pool " + pool : "";
    }

    /** Reads which template backup copy jobs copy which template jobs (an offering is offsite when its template has one). */
    private void readCopyTemplates(final Long zoneId) {
        String prefix = StringUtils.trimToNull(VeeamTemplatePrefix.valueIn(zoneId));
        Map<String, com.fasterxml.jackson.databind.JsonNode> templates = new HashMap<>();
        Map<String, VbrRestClient.JobState> states = restJobStates.get(zoneId);
        boolean any = false;
        for (VbrRestClient.JobState state : states == null ? new ArrayList<VbrRestClient.JobState>() : states.values()) {
            any |= prefix != null && state.name != null && state.name.startsWith(prefix) && "BackupCopy".equals(state.type);
        }
        if (any) {
            for (com.fasterxml.jackson.databind.JsonNode job : getRestClient(zoneId).listJobs()) {
                if ("BackupCopy".equals(job.path("type").asText()) && job.path("name").asText().startsWith(prefix)) {
                    for (com.fasterxml.jackson.databind.JsonNode source : job.path("sourceObjects").path("includes").path("jobs")) {
                        templates.put(source.path("id").asText(), job);
                    }
                }
            }
        }
        copyTemplates.put(zoneId, templates);
    }

    /** The template backup copy job of an offsite offering (one whose template job a template copy job copies), or null. */
    protected com.fasterxml.jackson.databind.JsonNode copyTemplateOf(final BackupOffering offering) {
        Map<String, com.fasterxml.jackson.databind.JsonNode> templates = offering == null ? null : copyTemplates.get(offering.getZoneId());
        return templates == null ? null : templates.get(offering.getExternalId());
    }

    /**
     * Adds the instance's own job to its customer's backup copy job: the one named by the domain's
     * backup.plugin.veeam.copy.job (adopting the customer's existing copy job), else one made from the template copy job
     * for the domain's backup.plugin.veeam.copy.repository. Returns the copy job's name.
     */
    private String addToCopyJob(final VMInstanceVO vm, final String jobId, final String jobName, final com.fasterxml.jackson.databind.JsonNode template) {
        VbrRestClient client = getRestClient(vm.getDataCenterId());
        String copyId = null;
        String wanted = StringUtils.trimToNull(VeeamCopyJob.valueInDomain(vm.getDomainId()));
        com.cloud.domain.DomainVO domain = domainDao.findById(vm.getDomainId());
        String made = StringUtils.left("CS - " + (domain == null ? "unknown" : domain.getName()) + " - Offsite", 40).replaceAll("[~\"#%&*:<>!?/\\\\{|}.'`$\\r\\n]", "-");
        for (VbrRestClient.JobState state : client.listJobStates()) {
            if ("BackupCopy".equals(state.type) && (state.name.equals(wanted) || (wanted == null && state.name.equals(made)))) {
                copyId = state.id;
            }
        }
        if (copyId == null && wanted != null) {
            throw new CloudRuntimeException("Veeam has no backup copy job named " + wanted + " (backup.plugin.veeam.copy.job of the domain)");
        }
        com.fasterxml.jackson.databind.node.ObjectNode source = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("type", usesPools(vm) ? "ProxmoxBackupJob" : "HyperVBackup").put("id", jobId).put("name", jobName);
        if (copyId == null) {
            String repository = StringUtils.trimToNull(VeeamCopyRepository.valueInDomain(vm.getDomainId()));
            if (repository == null) {
                throw new CloudRuntimeException("The domain has neither a backup copy job (backup.plugin.veeam.copy.job) nor a repository to copy to "
                        + "(backup.plugin.veeam.copy.repository)");
            }
            copyId = client.cloneJob(template.path("id").asText()).path("id").asText();
            com.fasterxml.jackson.databind.node.ObjectNode job = (com.fasterxml.jackson.databind.node.ObjectNode) client.getJob(copyId);
            job.put("name", made);
            job.put("description", "Offsite copies of CloudStack instances of " + (domain == null ? "?" : domain.getName()) + ", from "
                    + template.path("name").asText() + ". Made by CloudStack.");
            com.fasterxml.jackson.databind.node.ObjectNode includes = job.with("sourceObjects").with("includes");
            includes.putArray("jobs").add(source);
            job.with("target").put("backupRepositoryId", repository);
            client.putJob(copyId, job);
            client.enableJob(copyId);
            logger.info("Created Veeam backup copy job {} for domain {} from template {}", made, domain, template.path("name").asText());
        } else {
            com.fasterxml.jackson.databind.node.ObjectNode job = (com.fasterxml.jackson.databind.node.ObjectNode) client.getJob(copyId);
            com.fasterxml.jackson.databind.node.ArrayNode jobs = job.with("sourceObjects").with("includes").withArray("jobs");
            boolean present = false;
            for (com.fasterxml.jackson.databind.JsonNode j : jobs) {
                present |= jobId.equals(j.path("id").asText());
            }
            if (!present) {
                jobs.add(source);
                client.putJob(copyId, job);
            }
        }
        vmInstanceDetailsDao.addDetail(vm.getId(), COPY_DETAIL, copyId, false);
        return client.getJob(copyId).path("name").asText();
    }

    private void removeFromCopyJob(final VbrRestClient client, final String copyId, final String jobId) {
        com.fasterxml.jackson.databind.node.ObjectNode job = (com.fasterxml.jackson.databind.node.ObjectNode) client.getJob(copyId);
        com.fasterxml.jackson.databind.node.ArrayNode jobs = job.with("sourceObjects").with("includes").withArray("jobs");
        for (java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = jobs.iterator(); it.hasNext(); ) {
            if (jobId.equals(it.next().path("id").asText())) {
                it.remove();
            }
        }
        if (jobs.isEmpty()) {
            // Veeam refuses a copy job without sources; leave it for the operator to remove.
            logger.info("Backup copy job {} has no other sources; leaving it as it is", job.path("name").asText());
            return;
        }
        client.putJob(copyId, job);
    }

    private VspcClient getVspcClient(final Long zoneId) {
        try {
            return new VspcClient(VspcUrl.valueIn(zoneId), VspcApiKey.valueIn(zoneId), VeeamValidateSSLSecurity.valueIn(zoneId), VeeamApiRequestTimeout.valueIn(zoneId));
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new CloudRuntimeException("Failed to build the VSPC API client: " + e.getMessage(), e);
        }
    }

    /**
     * Assigns instances' own jobs to their customer's company in VSPC (it bills per protected machine by company). VSPC
     * learns of a new job some minutes after Veeam made it, so this retries each sync until VSPC reports the assignment.
     */
    private void assignVspcCompanies(final Long zoneId) {
        if (StringUtils.isBlank(VspcUrl.valueIn(zoneId))) {
            return;
        }
        for (VMInstanceDetailVO pending : vmInstanceDetailsDao.findDetails(VSPC_DETAIL)) {
            VMInstanceVO vm = vmInstanceDao.findById(pending.getResourceId());
            VMInstanceDetailVO job = vm == null ? null : vmInstanceDetailsDao.findDetail(vm.getId(), VM_JOB_DETAIL);
            if (vm == null || job == null) {
                vmInstanceDetailsDao.removeDetail(pending.getResourceId(), VSPC_DETAIL);
                continue;
            }
            if (!zoneId.equals(vm.getDataCenterId())) {
                continue;
            }
            try {
                VspcClient vspc = getVspcClient(zoneId);
                String company = StringUtils.trimToNull(VspcCompany.valueInDomain(vm.getDomainId()));
                VMInstanceDetailVO copy = vmInstanceDetailsDao.findDetail(vm.getId(), COPY_DETAIL);
                if (company == null && copy != null) {
                    company = StringUtils.trimToNull(vspc.jobCompany(copy.getValue()));
                }
                if (company == null) {
                    throw new CloudRuntimeException("No VSPC company for the domain: set backup.plugin.veeam.vspc.company");
                }
                String current = vspc.jobCompany(job.getValue());
                if (current == null) {
                    logger.debug("VSPC does not know the Veeam job of {} yet", vm);
                    continue;
                }
                if (!company.equals(current)) {
                    vspc.assignJob(job.getValue(), company);
                    logger.info("Asked VSPC to assign the Veeam job of {} to company {}", vm, company);
                    continue;
                }
                vmInstanceDetailsDao.removeDetail(vm.getId(), VSPC_DETAIL);
                ActionEventUtils.onActionEvent(User.UID_SYSTEM, vm.getAccountId(), vm.getDomainId(), EventTypes.EVENT_VM_BACKUP_OFFERING_ASSIGN,
                        "Veeam: the instance's job is assigned to its company in VSPC", vm.getId(), ApiCommandResourceType.VirtualMachine.toString());
            } catch (RuntimeException e) {
                logger.warn("Assigning the Veeam job of {} in VSPC failed: {}", vm, e.getMessage());
                if (!"failed".equals(pending.getValue())) {
                    vmInstanceDetailsDao.addDetail(vm.getId(), VSPC_DETAIL, "failed", false);
                    ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR, EventTypes.EVENT_VM_BACKUP_OFFERING_ASSIGN,
                            "Assigning the instance's Veeam job to its company in VSPC failed (retried each backup sync): " + e.getMessage(), vm.getId(),
                            ApiCommandResourceType.VirtualMachine.toString(), 0);
                }
            }
        }
    }

    /** The extension's own error message (its JSON "error"), not the whole answer. */
    private static String extensionError(final Answer answer) {
        if (answer == null) {
            return "No answer from the extension";
        }
        String details = StringUtils.defaultString(answer.getDetails());
        int json = details.indexOf('{');
        try {
            JsonElement error = json < 0 ? null : new JsonParser().parse(details.substring(json)).getAsJsonObject().get("error");
            if (error != null && error.isJsonPrimitive()) {
                return error.getAsString();
            }
        } catch (RuntimeException e) {
            // not JSON: report as is
        }
        return details;
    }

    private String requireBackupUuid(final VMInstanceVO vm) {
        String uuid = getBackupUuid(vm);
        if (uuid == null) {
            throw new CloudRuntimeException("The instance's extension does not report its hypervisor UUID");
        }
        return uuid;
    }

    private void syncRestRestorePoints(final Long zoneId) {
        List<VbrRestorePoints.Instance> instances = new ArrayList<>();
        for (VMInstanceVO vm : vmInstanceDao.listByZoneAndBackupOffering(zoneId, null)) {
            if (isExternal(vm)) {
                instances.add(new VbrRestorePoints.Instance(vm.getId(), getBackupUuid(vm), null));
            }
        }
        if (!instances.isEmpty()) {
            restRestorePoints.sync(getRestClient(zoneId), instances);
        }
    }

    protected VeeamClient getClient(final Long zoneId) {
        try {
            return new VeeamClient(VeeamUrl.valueIn(zoneId), VeeamVersion.valueIn(zoneId), VeeamUsername.valueIn(zoneId), VeeamPassword.valueIn(zoneId),
                    VeeamValidateSSLSecurity.valueIn(zoneId), VeeamApiRequestTimeout.valueIn(zoneId), VeeamRestoreTimeout.valueIn(zoneId),
                    VeeamTaskPollInterval.valueIn(zoneId), VeeamTaskPollMaxRetry.valueIn(zoneId));
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("Failed to parse Veeam API URL: " + e.getMessage());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            logger.error("Failed to build Veeam API client due to: ", e);
        }
        throw new CloudRuntimeException("Failed to build Veeam API client");
    }

    public List<BackupOffering> listBackupOfferings(final Long zoneId) {
        List<BackupOffering> policies = new ArrayList<>();
        if (isEnterpriseManagerEnabled(zoneId)) {
            for (final BackupOffering policy : getClient(zoneId).listJobs()) {
                if (!policy.getName().contains(BACKUP_IDENTIFIER)) {
                    policies.add(policy);
                }
            }
        }
        if (isRestEnabled(zoneId)) {
            // Proxmox and Hyper-V jobs; an offering is one Veeam job, typically restricted to its customer's domain.
            for (VbrRestClient.JobState job : getRestClient(zoneId).listJobStates()) {
                if (REST_JOB_TYPES.contains(job.type)) {
                    policies.add(new VeeamBackupOffering(job.name, job.id));
                }
            }
        }
        return policies;
    }

    @Override
    public boolean isValidProviderOffering(final Long zoneId, final String uuid) {
        List<BackupOffering> policies = listBackupOfferings(zoneId);
        if (CollectionUtils.isEmpty(policies)) {
            return false;
        }
        for (final BackupOffering policy : policies) {
            if (policy.getExternalId().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    private VmwareDatacenter findVmwareDatacenterForVM(final VirtualMachine vm) {
        if (vm == null || vm.getHypervisorType() != Hypervisor.HypervisorType.VMware) {
            throw new CloudRuntimeException("The Veeam backup provider is only applicable for VMware VMs");
        }
        final VmwareDatacenterZoneMap zoneMap = vmwareDatacenterZoneMapDao.findByZoneId(vm.getDataCenterId());
        if (zoneMap == null) {
            throw new CloudRuntimeException("Failed to find a mapped VMware datacenter for zone id:" + vm.getDataCenterId());
        }
        final VmwareDatacenter vmwareDatacenter = vmwareDatacenterDao.findById(zoneMap.getVmwareDcId());
        if (vmwareDatacenter == null) {
            throw new CloudRuntimeException("Failed to find a valid VMware datacenter mapped for zone id:" + vm.getDataCenterId());
        }
        return vmwareDatacenter;
    }

    private String getGuestBackupName(final String instanceName, final String uuid) {
        return String.format("%s%s%s", instanceName, BACKUP_IDENTIFIER, uuid);
    }

    @Override
    public boolean assignVMToBackupOffering(final VirtualMachine vm, final BackupOffering backupOffering) {
        if (isExternal(vm)) {
            // The instance joins the job's scope (its customer's Proxmox pool, or the Hyper-V job itself) at the
            // next backup sync, outside this transaction.
            getRestClient(vm.getDataCenterId());
            ((VMInstanceVO) vm).setBackupExternalId(backupOffering.getExternalId());
            restRestorePoints.forget(vm.getId());
            vmInstanceDetailsDao.addDetail(vm.getId(), MEMBERSHIP_DETAIL, "add", false);
            vmInstanceDetailsDao.addDetail(vm.getId(), JOB_DETAIL, backupOffering.getExternalId(), false);
            return true;
        }
        if (vm.getHypervisorType() != Hypervisor.HypervisorType.VMware) {
            throw new CloudRuntimeException("Veeam backs up " + vm.getHypervisorType() + " instances from the Veeam console");
        }
        final VeeamClient client = getClient(vm.getDataCenterId());
        final Job parentJob = client.listJob(backupOffering.getExternalId());
        final String clonedJobName = getGuestBackupName(vm.getInstanceName(), vm.getUuid());

        if (!client.cloneVeeamJob(parentJob, clonedJobName)) {
            logger.error("Failed to clone pre-defined Veeam job (backup offering) for backup offering [id: {}, name: {}] but will check the list of jobs again if it was eventually succeeded.", backupOffering.getExternalId(), backupOffering.getName());
        }

        for (final BackupOffering job : client.listJobs()) {
            if (job.getName().equals(clonedJobName)) {
                final Job clonedJob = client.listJob(job.getExternalId());
                if (BooleanUtils.isTrue(clonedJob.getScheduleConfigured()) && !clonedJob.getScheduleEnabled()) {
                    client.toggleJobSchedule(clonedJob.getId());
                }
                logger.debug("Veeam job (backup offering) for backup offering [id: {}, name: {}] found, now trying to assign the VM to the job.", backupOffering.getExternalId(), backupOffering.getName());
                final VmwareDatacenter vmwareDC = findVmwareDatacenterForVM(vm);
                if (client.addVMToVeeamJob(job.getExternalId(), vm.getInstanceName(), vmwareDC.getVcenterHost())) {
                    ((VMInstanceVO) vm).setBackupExternalId(job.getExternalId());
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean removeVMFromBackupOffering(final VirtualMachine vm) {
        if (isExternal(vm)) {
            restRestorePoints.forget(vm.getId());
            vmInstanceDetailsDao.addDetail(vm.getId(), MEMBERSHIP_DETAIL, "remove", false);
            return true;
        }
        final VeeamClient client = getClient(vm.getDataCenterId());
        final VmwareDatacenter vmwareDC = findVmwareDatacenterForVM(vm);
        if (vm.getBackupExternalId() == null) {
            throw new CloudRuntimeException("The VM does not have a backup job assigned.");
        }
        try {
            if (!client.removeVMFromVeeamJob(vm.getBackupExternalId(), vm.getInstanceName(), vmwareDC.getVcenterHost())) {
                logger.warn("Failed to remove VM from Veeam Job id: " + vm.getBackupExternalId());
            }
        } catch (Exception e) {
            logger.debug("VM was removed from the job so could not remove again, trying to delete the veeam job now.", e);
        }

        final String clonedJobName = getGuestBackupName(vm.getInstanceName(), vm.getUuid());
        if (!client.deleteJobAndBackup(clonedJobName)) {
            logger.warn("Failed to remove Veeam job and backup for job: " + clonedJobName);
            throw new CloudRuntimeException("Failed to delete Veeam B&R job and backup, an operation may be in progress. Please try again after some time.");
        }
        client.syncBackupRepository();
        return true;
    }

    @Override
    public boolean willDeleteBackupsOnOfferingRemoval() {
        return false;
    }

    @Override
    public Pair<Boolean, Backup> takeBackup(final VirtualMachine vm, Boolean quiesceVM, boolean isolated) {
        if (isExternal(vm)) {
            VbrRestClient client = getRestClient(vm.getDataCenterId());
            VbrRestClient.Session session = null;
            String jobId = jobIdOf(vm);
            if (isDisabled(restJobState(vm.getDataCenterId(), jobId))) {
                throw new CloudRuntimeException("The Veeam job of this instance is disabled in Veeam");
            }
            if (!usesPools(vm)) {
                // Hyper-V: an incremental of this VM alone, once it has a full backup.
                String uuid = getBackupUuid((VMInstanceVO) vm);
                com.fasterxml.jackson.databind.JsonNode object = uuid == null ? null : client.findHyperVVm(uuid);
                if (object != null) {
                    try {
                        session = client.quickBackupHyperV(object);
                    } catch (CloudRuntimeException e) {
                        logger.info("Veeam quick backup of {} refused ({}); running its job instead", vm, e.getMessage());
                    }
                }
            }
            if (session == null) {
                // Veeam has no per-VM ad-hoc backup for Proxmox; run the instance's job (incremental). Instances
                // sharing a job ask together when their CloudStack schedules fall due: one run serves them all.
                for (VbrRestClient.JobState state : client.listJobStates()) {
                    if (state.id.equals(jobId) && isDisabled(state)) {
                        throw new CloudRuntimeException("The Veeam job " + state.name + " of this instance is disabled in Veeam");
                    }
                    if (state.id.equals(jobId) && !"Stopped".equalsIgnoreCase(state.status)) {
                        logger.info("Veeam job {} for {} is already running ({}); not starting it again", state.name, vm, state.status);
                        return new Pair<>(true, null);
                    }
                }
                session = client.startJob(jobId, false);
            }
            logger.info("Started Veeam backup for {} (job {}): session {}", vm, jobId, session.id);
            return new Pair<>(session.id != null, null);
        }
        final VeeamClient client = getClient(vm.getDataCenterId());
        Boolean result = client.startBackupJob(vm.getBackupExternalId());
        return new Pair<>(result, null);
    }

    @Override
    public boolean deleteBackup(Backup backup, boolean forced) {
        VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm == null) {
            throw new CloudRuntimeException(String.format("Could not find any VM associated with the Backup [uuid: %s, name: %s, externalId: %s].", backup.getUuid(), backup.getName(), backup.getExternalId()));
        }
        if (isExternal(vm)) {
            throw new CloudRuntimeException("Deleting Veeam backups of External instances is not supported yet; Veeam retention removes restore points.");
        }
        if (!forced) {
            logger.debug(String.format("Veeam backup provider does not have a safe way to remove a single restore point, which results in all backup chain being removed. "
                    + "More information about this limitation can be found in the links: [%s, %s].", "https://forums.veeam.com/powershell-f26/removing-a-single-restorepoint-t21061.html",
                    "https://helpcenter.veeam.com/docs/backup/vsphere/retention_separate_vms.html?ver=110"));
            throw new CloudRuntimeException("Veeam backup provider does not have a safe way to remove a single restore point, which results in all backup chain being removed. "
                    + "Use forced:true to skip this verification and remove the complete backup chain.");
        }
        VeeamClient client = getClient(vm.getDataCenterId());
        boolean result = client.deleteBackup(backup.getExternalId());
        if (BooleanUtils.isFalse(result)) {
            return false;
        }

        client.syncBackupRepository();

        List<Backup> allBackups = backupDao.listByVmIdAndOffering(backup.getZoneId(), backup.getVmId(), backup.getBackupOfferingId());
        for (Backup b : allBackups) {
            if (b.getId() != backup.getId()) {
                backupDao.remove(b.getId());
            }
        }
        return result;
    }

    @Override
    public boolean restoreVMFromBackup(VirtualMachine vm, Backup backup, boolean quickRestore, Long hostId) {
        if (isExternal(vm)) {
            VMInstanceVO instance = vmInstanceDao.findById(vm.getId());
            try {
                return restoreExternalInPlace(instance, backup, quickRestore);
            } catch (RuntimeException e) {
                // HA may have been turned off for the restore; a started quick restore turns it back on when it finishes.
                if (instance.isHaEnabled() && usesPools(instance) && vmInstanceDetailsDao.findDetail(instance.getId(), QUICK_RESTORE_DETAIL) == null) {
                    resumeProxmoxHa(instance);
                }
                throw e;
            }
        }
        final String restorePointId = backup.getExternalId();
        try {
            return getClient(vm.getDataCenterId()).restoreFullVM(vm.getInstanceName(), restorePointId);
        } catch (Exception ex) {
            logger.error(String.format("Failed to restore Full VM due to: %s. Retrying after some preparation", ex.getMessage()));
            prepareForBackupRestoration(vm);
            return getClient(vm.getDataCenterId()).restoreFullVM(vm.getInstanceName(), restorePointId);
        }
    }

    /**
     * Restores a stopped External instance over itself, powered off: Proxmox through the Veeam plug-in (same VMID,
     * SMBIOS UUID, disks and configuration as in the restore point), Hyper-V to its original location. Waits for the
     * Veeam session, up to backup.plugin.veeam.restore.timeout. A Proxmox VM goes back into its backup pool after the
     * restore, in case the restored configuration does not carry it.
     */
    private boolean restoreExternalInPlace(final VMInstanceVO vm, final Backup backup, final boolean quick) {
        VbrRestClient client = getRestClient(vm.getDataCenterId());
        String reason = String.format("CloudStack restore of instance %s (%s) from backup %s", vm.getHostName(), vm.getUuid(), backup.getUuid());
        boolean proxmox = usesPools(vm);
        if (vmInstanceDetailsDao.findDetail(vm.getId(), QUICK_RESTORE_DETAIL) != null) {
            throw new CloudRuntimeException("A quick restore of this instance is still being finished; restore it again once that is done");
        }
        if (quick) {
            return proxmox ? startQuickRestore(vm, backup, client, reason) : startHyperVQuickRestore(vm, backup, client, reason);
        }
        VbrRestClient.Session session;
        if (proxmox) {
            pauseProxmoxHa(vm);
            VMInstanceDetailVO vmid = vmInstanceDetailsDao.findDetail(vm.getId(), "proxmox_vmid");
            if (vmid == null || StringUtils.isBlank(vmid.getValue())) {
                throw new CloudRuntimeException("The instance has no Proxmox VMID recorded");
            }
            session = client.restoreProxmoxVmInPlace(requireBackupUuid(vm).toLowerCase() + ":" + vmid.getValue(), backup.getExternalId(), reason);
        } else {
            session = client.restoreHyperVVmInPlace(backup.getExternalId(), reason);
        }
        logger.info("Veeam is restoring {} from restore point {} (backup {}): session {}", vm, backup.getExternalId(), backup.getUuid(), session.id);

        long deadline = System.currentTimeMillis() + VeeamRestoreTimeout.valueIn(vm.getDataCenterId()) * 1000L;
        while (!"Finished".equalsIgnoreCase(session.state) && !"Stopped".equalsIgnoreCase(session.state)) {
            if (System.currentTimeMillis() > deadline) {
                throw new CloudRuntimeException(String.format("The Veeam restore session %s is still running after %ss (backup.plugin.veeam.restore.timeout);"
                        + " check it in Veeam before starting the instance", session.id, VeeamRestoreTimeout.valueIn(vm.getDataCenterId())));
            }
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudRuntimeException("Interrupted while waiting for Veeam restore session " + session.id);
            }
            try {
                session = proxmox ? client.getProxmoxSession(session.id) : client.getSession(session.id);
            } catch (CloudRuntimeException e) {
                logger.debug("Veeam restore session {} not readable this time: {}", session.id, e.getMessage());
            }
        }
        if (!"Success".equalsIgnoreCase(session.result) && !"Warning".equalsIgnoreCase(session.result)) {
            throw new CloudRuntimeException(String.format("Veeam restore session %s ended %s: %s", session.id, session.result,
                    StringUtils.defaultIfBlank(session.message, "see the session in Veeam")));
        }
        logger.info("Veeam restored {} from restore point {} ({}{})", vm, backup.getExternalId(), session.result,
                session.message == null ? "" : ": " + session.message);

        if (proxmox) {
            reapplyProxmoxPool(vm);
            resumeProxmoxHa(vm);
        }
        return true;
    }

    /**
     * Turns Proxmox HA off for an HA instance before Veeam replaces its VM: HA must not act on a VM being deleted and
     * recreated, and Proxmox drops the HA resource with the VM anyway. {@link #resumeProxmoxHa} turns it back on.
     */
    private void pauseProxmoxHa(final VMInstanceVO vm) {
        if (!vm.isHaEnabled()) {
            return;
        }
        Answer answer = extensionsManager.setInstanceHa(vm, false);
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException("Could not take the instance out of Proxmox HA for the restore: " + extensionError(answer));
        }
    }

    /** Puts an HA instance back under Proxmox HA after a restore; returns false (and logs) when that failed. */
    private boolean resumeProxmoxHa(final VMInstanceVO vm) {
        if (!vm.isHaEnabled()) {
            return true;
        }
        Answer answer = extensionsManager.setInstanceHa(vm, true);
        if (answer == null || !answer.getResult()) {
            logger.warn("{} is restored but not back under Proxmox HA: {}", vm, extensionError(answer));
            quickRestoreEvent(vm, EventVO.LEVEL_ERROR, "Restored, but not back under Proxmox HA: " + extensionError(answer)
                    + "; turn HA off and on again for the instance");
            return false;
        }
        return true;
    }

    /** Puts a restored VM back in the Proxmox pool its offering's job protects (a recreated VM is in no pool). */
    private void reapplyProxmoxPool(final VMInstanceVO vm) {
        VMInstanceDetailVO pool = vmInstanceDetailsDao.findDetail(vm.getId(), POOL_DETAIL);
        if (pool == null) {
            return;
        }
        Answer answer = extensionsManager.setInstancePool(vm, pool.getValue(), false);
        if (answer == null || !answer.getResult()) {
            logger.warn("{} is restored but not back in Proxmox pool {} ({}); the backup sync retries", vm, pool.getValue(), extensionError(answer));
            vmInstanceDetailsDao.addDetail(vm.getId(), MEMBERSHIP_DETAIL, "add", false);
        }
    }

    private static final Map<String, String> BUS_PREFIX = Map.of("Virtio", "virtio", "Scsi", "scsi", "Sata", "sata", "Ide", "ide", "Efi", "efidisk");

    /**
     * Quick restore of a stopped Proxmox instance through Veeam Instant Recovery: Veeam replaces the VM (same VMID, SMBIOS
     * UUID and MAC) with one that runs from the backup, its disks qcow2 files on the quick-restore storage backed by the
     * repository, and starts it. Returns once it runs. The backup sync then has Veeam copy the disks off the backup
     * (its "migration") and moves each disk back to the storage it had in the backup; see {@link #finishQuickRestores}.
     */
    private boolean startQuickRestore(final VMInstanceVO vm, final Backup backup, final VbrRestClient client, final String reason) {
        VMInstanceDetailVO vmid = vmInstanceDetailsDao.findDetail(vm.getId(), "proxmox_vmid");
        if (vmid == null || StringUtils.isBlank(vmid.getValue())) {
            throw new CloudRuntimeException("The instance has no Proxmox VMID recorded");
        }
        String sourceVmId = requireBackupUuid(vm).toLowerCase() + ":" + vmid.getValue();
        String staging = StringUtils.trimToNull(VeeamQuickRestoreStorage.valueIn(vm.getDataCenterId()));
        if (staging == null) {
            throw new CloudRuntimeException("No quick-restore storage is set (backup.plugin.veeam.proxmox.quickrestore.storage)");
        }
        com.fasterxml.jackson.databind.JsonNode meta = client.getProxmoxRestorePointMetadata(backup.getExternalId());
        String nodeId = proxmoxNodeIdOf(vm, client, meta);

        com.fasterxml.jackson.databind.node.ArrayNode disks = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        JsonObject targets = new JsonObject();
        long needed = 0;
        for (com.fasterxml.jackson.databind.JsonNode disk : meta.path("disks")) {
            String prefix = BUS_PREFIX.get(disk.path("busType").asText());
            if (prefix == null || disk.path("isCdrom").asBoolean(false)) {
                continue;
            }
            disks.addObject().put("diskId", disk.path("id").asText()).put("busType", disk.path("busType").asText())
                    .put("index", disk.path("index").asInt()).put("storageContainerId", staging);
            targets.addProperty(prefix + disk.path("index").asInt(), disk.path("storageContainerId").asText());
            needed += disk.path("size").asLong(0);
        }
        if (disks.isEmpty()) {
            throw new CloudRuntimeException("The restore point lists no disks");
        }
        // Veeam gives the VM a new, empty TPM state on the quick-restore storage when it had one; it goes with the EFI disk.
        String tpmTarget = targets.has("efidisk0") ? targets.get("efidisk0").getAsString() : null;
        if (tpmTarget != null) {
            targets.addProperty("tpmstate0", tpmTarget);
        }
        long free = -1;
        for (com.fasterxml.jackson.databind.JsonNode storage : client.listProxmoxInstantRecoveryStorage(nodeId)) {
            if (staging.equals(storage.path("id").asText())) {
                free = storage.path("freeSpace").asLong(0);
            }
        }
        if (free < 0) {
            throw new CloudRuntimeException(String.format("The instance's Proxmox node has no storage %s Veeam can run a quick restore from;"
                    + " restore without quick restore", staging));
        }
        if (free < needed) {
            throw new CloudRuntimeException(String.format("A quick restore needs %d GiB free on storage %s of the instance's node, which has %d GiB;"
                    + " restore without quick restore", needed >> 30, staging, free >> 30));
        }

        pauseProxmoxHa(vm);
        com.fasterxml.jackson.databind.node.ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.put("sourceVmId", sourceVmId).put("targetVmClusterId", nodeId).put("targetVmId", sourceVmId)
                .put("targetVmName", meta.path("name").asText()).put("sourceVmName", meta.path("name").asText())
                .put("restoreToOriginal", true).put("forceDeleteExistingVm", true).put("restorePointId", backup.getExternalId())
                .put("powerOnVmAfterRestore", true).put("disconnectNetworksAfterRestore", false).put("reason", reason);
        body.set("diskSettings", disks);
        String session = client.startProxmoxInstantRecovery(body);
        logger.info("Veeam Instant Recovery of {} from restore point {} (backup {}), disks on {}: session {}", vm, backup.getExternalId(),
                backup.getUuid(), staging, session);
        JsonObject state = new JsonObject();
        state.addProperty("session", session);
        state.addProperty("phase", "publishing");
        state.addProperty("staging", staging);
        state.add("targets", targets);
        vmInstanceDetailsDao.addDetail(vm.getId(), QUICK_RESTORE_DETAIL, state.toString(), false);

        long deadline = System.currentTimeMillis() + VeeamRestoreTimeout.valueIn(vm.getDataCenterId()) * 1000L;
        while (true) {
            VbrRestClient.Session ir = null;
            try {
                ir = client.getProxmoxInstantRecovery(session);
            } catch (CloudRuntimeException e) {
                logger.debug("Veeam Instant Recovery session {} not readable this time: {}", session, e.getMessage());
            }
            if (ir != null && "Running".equalsIgnoreCase(ir.state) && !"Failed".equalsIgnoreCase(ir.result)) {
                reapplyProxmoxPool(vm);
                logger.info("{} runs from its backup (Instant Recovery session {}); the backup sync finishes the restore", vm, session);
                return true;
            }
            if (ir != null && ("Failed".equalsIgnoreCase(ir.result) || "Stopped".equalsIgnoreCase(ir.state) || "Finished".equalsIgnoreCase(ir.state))) {
                vmInstanceDetailsDao.removeDetail(vm.getId(), QUICK_RESTORE_DETAIL);
                throw new CloudRuntimeException(String.format("Veeam Instant Recovery session %s ended %s %s; check the instance in Proxmox and the session in Veeam",
                        session, ir.state, StringUtils.defaultString(ir.result)));
            }
            if (System.currentTimeMillis() > deadline) {
                throw new CloudRuntimeException(String.format("Veeam Instant Recovery session %s has not started the instance after %ss; the backup sync keeps "
                        + "following it", session, VeeamRestoreTimeout.valueIn(vm.getDataCenterId())));
            }
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudRuntimeException("Interrupted while waiting for Veeam Instant Recovery session " + session);
            }
        }
    }

    /**
     * Quick restore of a stopped Hyper-V instance through Veeam Instant Recovery to its original location: the VM runs from
     * the backup (Veeam's vPower) and starts; the backup sync then has Veeam move it to production storage, after which
     * Veeam removes the mount.
     */
    private boolean startHyperVQuickRestore(final VMInstanceVO vm, final Backup backup, final VbrRestClient client, final String reason) {
        com.fasterxml.jackson.databind.JsonNode mount = client.startHyperVInstantRecovery(backup.getExternalId(), reason);
        String mountId = mount.path("id").asText();
        JsonObject state = new JsonObject();
        state.addProperty("hyperv", mountId);
        state.addProperty("session", mount.path("sessionId").asText());
        state.addProperty("phase", "publishing");
        vmInstanceDetailsDao.addDetail(vm.getId(), QUICK_RESTORE_DETAIL, state.toString(), false);
        logger.info("Veeam Instant Recovery of {} from restore point {} (backup {}): mount {}", vm, backup.getExternalId(), backup.getUuid(), mountId);
        long deadline = System.currentTimeMillis() + VeeamRestoreTimeout.valueIn(vm.getDataCenterId()) * 1000L;
        while (true) {
            com.fasterxml.jackson.databind.JsonNode now = client.getHyperVInstantRecovery(mountId);
            String mountState = now == null ? "Gone" : now.path("state").asText();
            if ("Mounted".equals(mountState)) {
                return true;
            }
            if ("Failed".equals(mountState) || "Gone".equals(mountState) || "Dismounting".equals(mountState)) {
                vmInstanceDetailsDao.removeDetail(vm.getId(), QUICK_RESTORE_DETAIL);
                throw new CloudRuntimeException(String.format("Veeam Instant Recovery mount %s is %s: %s", mountId, mountState,
                        now == null ? "see the session in Veeam" : now.path("errorMessage").asText("see the session in Veeam")));
            }
            if (System.currentTimeMillis() > deadline) {
                throw new CloudRuntimeException(String.format("Veeam Instant Recovery mount %s is still %s after %ss; the backup sync keeps following it",
                        mountId, mountState, VeeamRestoreTimeout.valueIn(vm.getDataCenterId())));
            }
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudRuntimeException("Interrupted while waiting for Veeam Instant Recovery mount " + mountId);
            }
        }
    }

    private void hyperVQuickRestoreStep(final VMInstanceVO vm, final JsonObject state) {
        VbrRestClient client = getRestClient(vm.getDataCenterId());
        String mountId = state.get("hyperv").getAsString();
        com.fasterxml.jackson.databind.JsonNode mount = client.getHyperVInstantRecovery(mountId);
        String mountState = mount == null ? "Gone" : mount.path("state").asText();
        if ("publishing".equals(state.get("phase").getAsString())) {
            if ("Mounted".equals(mountState)) {
                client.migrateHyperVInstantRecovery(mountId);
                state.addProperty("phase", "migrating");
                state.remove("error");
                quickRestoreEvent(vm, EventVO.LEVEL_INFO, "Quick restore: the instance runs from its backup; Veeam is moving it to production storage");
            } else if (!"Mounting".equals(mountState)) {
                state.addProperty("done", true);
                quickRestoreEvent(vm, EventVO.LEVEL_ERROR, "Quick restore: Veeam Instant Recovery mount " + mountId + " is " + mountState
                        + " before the instance was moved to production storage; check the instance in Hyper-V");
            }
            return;
        }
        if ("Gone".equals(mountState)) {
            VbrRestClient.Session session = client.getSession(state.get("session").getAsString());
            state.addProperty("done", true);
            if ("Success".equalsIgnoreCase(session.result) || "Warning".equalsIgnoreCase(session.result)) {
                quickRestoreEvent(vm, EventVO.LEVEL_INFO, "Quick restore finished: the instance is on production storage");
            } else {
                quickRestoreEvent(vm, EventVO.LEVEL_ERROR, "Quick restore: moving the instance to production storage ended " + session.result
                        + "; check the session in Veeam");
            }
        } else if ("Failed".equals(mountState)) {
            throw new CloudRuntimeException("Veeam could not move the instance to production storage: " + mount.path("errorMessage").asText());
        }
    }

    /** Veeam's id of the Proxmox node the instance is on now (the restore point's node when it has not moved). */
    private String proxmoxNodeIdOf(final VMInstanceVO vm, final VbrRestClient client, final com.fasterxml.jackson.databind.JsonNode meta) {
        Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
        com.cloud.host.HostVO host = hostId == null ? null : hostDao.findById(hostId);
        if (host != null) {
            hostDao.loadDetails(host);
        }
        String node = host == null || host.getDetails() == null ? null : host.getDetails().get("External:node");
        if (node == null || node.equals(meta.path("subClusterName").asText())) {
            return meta.path("subClusterId").asText();
        }
        for (com.fasterxml.jackson.databind.JsonNode n : client.listProxmoxNodes(meta.path("clusterId").asText())) {
            if (node.equals(n.path("name").asText())) {
                return n.path("id").asText();
            }
        }
        throw new CloudRuntimeException("Veeam does not list the instance's Proxmox node " + node);
    }

    /**
     * Moves quick restores on, one step per instance per backup sync: once the instance runs from the backup, Veeam copies
     * its disks off the backup (a failed copy is started again next sync); then each disk moves, online, from the
     * quick-restore storage back to the storage it had in the backup, and CloudStack's volume records follow. A TPM state
     * moves only while the instance is stopped, so it waits for the next stop. Calls Veeam and the extension; outside
     * transactions only.
     */
    private void finishQuickRestores(final Long zoneId) {
        for (VMInstanceDetailVO detail : vmInstanceDetailsDao.findDetails(QUICK_RESTORE_DETAIL)) {
            VMInstanceVO vm = vmInstanceDao.findById(detail.getResourceId());
            if (vm == null || vm.getState() == VirtualMachine.State.Expunging) {
                vmInstanceDetailsDao.removeDetail(detail.getResourceId(), QUICK_RESTORE_DETAIL);
                continue;
            }
            if (!zoneId.equals(vm.getDataCenterId())) {
                continue;
            }
            JsonObject state = new JsonParser().parse(detail.getValue()).getAsJsonObject();
            String before = state.toString();
            try {
                if (state.has("hyperv")) {
                    hyperVQuickRestoreStep(vm, state);
                } else {
                    quickRestoreStep(vm, state);
                }
            } catch (RuntimeException e) {
                logger.warn("Quick restore of {} ({}): {}", vm, state.get("phase").getAsString(), e.getMessage());
                if (!state.has("error")) {
                    state.addProperty("error", e.getMessage());
                    quickRestoreEvent(vm, EventVO.LEVEL_ERROR, "Quick restore: " + e.getMessage() + " (retried each backup sync)");
                }
            }
            if (state.has("done")) {
                vmInstanceDetailsDao.removeDetail(vm.getId(), QUICK_RESTORE_DETAIL);
            } else if (!state.toString().equals(before)) {
                vmInstanceDetailsDao.addDetail(vm.getId(), QUICK_RESTORE_DETAIL, state.toString(), false);
            }
        }
    }

    private void quickRestoreStep(final VMInstanceVO vm, final JsonObject state) {
        VbrRestClient client = getRestClient(vm.getDataCenterId());
        String phase = state.get("phase").getAsString();
        if ("publishing".equals(phase)) {
            VbrRestClient.Session ir = client.getProxmoxInstantRecovery(state.get("session").getAsString());
            if ("Running".equalsIgnoreCase(ir.state)) {
                state.addProperty("migration", client.startProxmoxInstantRecoveryMigration(state.get("session").getAsString()));
                state.addProperty("phase", "migrating");
                state.remove("error");
                quickRestoreEvent(vm, EventVO.LEVEL_INFO, "Quick restore: the instance runs from its backup; Veeam is copying its disks off the backup");
            } else if ("Failed".equalsIgnoreCase(ir.result) || "Stopped".equalsIgnoreCase(ir.state) || "Finished".equalsIgnoreCase(ir.state)) {
                state.addProperty("done", true);
                quickRestoreEvent(vm, EventVO.LEVEL_ERROR, String.format("Quick restore: Veeam Instant Recovery session %s ended %s %s before its disks "
                        + "were copied; check the instance in Proxmox", ir.id, ir.state, StringUtils.defaultString(ir.result)));
            }
            return;
        }
        if ("migrating".equals(phase)) {
            // Veeam forgets a migration once it ends; its Instant Recovery session then finishes too (Success when the
            // disks no longer depend on the backup). While the session runs, the migration's own state tells a failure.
            VbrRestClient.Session ir = client.getProxmoxInstantRecovery(state.get("session").getAsString());
            VbrRestClient.Session migration = new VbrRestClient.Session();
            if ("Finished".equalsIgnoreCase(ir.state) || "Stopped".equalsIgnoreCase(ir.state)) {
                migration.state = ir.state;
                migration.result = ir.result;
            } else {
                try {
                    migration = client.getProxmoxInstantRecoveryMigration(state.get("migration").getAsString());
                } catch (CloudRuntimeException e) {
                    return;
                }
            }
            if (!"Finished".equalsIgnoreCase(migration.state) && !"Stopped".equalsIgnoreCase(migration.state)) {
                return;
            }
            if ("Success".equalsIgnoreCase(migration.result) || "Warning".equalsIgnoreCase(migration.result)) {
                state.addProperty("phase", "moving");
                state.remove("error");
                quickRestoreEvent(vm, EventVO.LEVEL_INFO, "Quick restore: Veeam copied the disks off the backup; moving them to their storage");
            } else {
                state.addProperty("phase", "publishing");
                throw new CloudRuntimeException("Veeam's copy of the disks off the backup ended " + migration.result + "; starting it again");
            }
            return;
        }
        moveRestoredDisks(vm, state);
    }

    private void moveRestoredDisks(final VMInstanceVO vm, final JsonObject state) {
        Answer config = extensionsManager.getInstanceConfig(vm);
        if (config == null || !config.getResult()) {
            throw new CloudRuntimeException("No configuration snapshot of the instance: " + extensionError(config));
        }
        JsonObject snapshot = new JsonParser().parse(config.getDetails()).getAsJsonObject();
        boolean running = "running".equals(snapshot.get("power").getAsString());
        Map<String, String> volumes = new HashMap<>();
        for (JsonElement disk : snapshot.getAsJsonArray("disks")) {
            JsonObject d = disk.getAsJsonObject();
            if (!d.get("storage").isJsonNull() && !d.get("volume").isJsonNull()) {
                volumes.put(d.get("device").getAsString(), d.get("storage").getAsString() + ":" + d.get("volume").getAsString());
            }
        }
        String staging = state.get("staging").getAsString();
        JsonObject targets = state.getAsJsonObject("targets");
        com.google.gson.JsonArray moved = state.has("moved") ? state.getAsJsonArray("moved") : new com.google.gson.JsonArray();
        boolean waiting = false;
        for (Map.Entry<String, JsonElement> target : targets.entrySet()) {
            String device = target.getKey();
            if (moved.contains(new com.google.gson.JsonPrimitive(device))) {
                continue;
            }
            String volume = volumes.get(device);
            if (volume != null && !volume.startsWith(staging + ":")) {
                moved.add(device);
                continue;
            }
            if ("tpmstate0".equals(device) && running) {
                waiting = true;
                continue;
            }
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("device", device);
            parameters.put("volume", StringUtils.defaultString(volume));
            parameters.put("storage", target.getValue().getAsString());
            Answer answer = extensionsManager.changeInstanceDisk(vm, "movedisk", parameters);
            String error = answer == null || !answer.getResult() ? extensionError(answer) : null;
            if (error != null && error.contains("already on that storage")) {
                moved.add(device);
                continue;
            }
            if (error != null) {
                if (!device.startsWith("efidisk") && !device.startsWith("tpmstate") && volume == null) {
                    throw new CloudRuntimeException("Disk " + device + " is not in the instance's configuration");
                }
                if (device.startsWith("tpmstate") && error.contains("holds no disk")) {
                    moved.add(device);
                    continue;
                }
                throw new CloudRuntimeException("Moving disk " + device + " to " + target.getValue().getAsString() + " failed: " + error);
            }
            JsonObject result = new JsonParser().parse(answer.getDetails().substring(answer.getDetails().indexOf('{'))).getAsJsonObject();
            recordMovedVolume(vm, device, result.get("volume").getAsString());
            moved.add(device);
            logger.info("Quick restore of {}: disk {} is on {} again", vm, device, target.getValue().getAsString());
        }
        state.add("moved", moved);
        state.remove("error");
        if (!state.has("haBack") && resumeProxmoxHa(vm)) {
            state.addProperty("haBack", true);
        }
        if (!waiting) {
            state.addProperty("done", true);
            quickRestoreEvent(vm, EventVO.LEVEL_INFO, "Quick restore finished: every disk is on its own storage again");
        } else if (!state.has("waitingTpm")) {
            state.addProperty("waitingTpm", true);
            quickRestoreEvent(vm, EventVO.LEVEL_INFO, "Quick restore: the disks are on their own storage again; the TPM state moves the next time the instance is stopped");
        }
    }

    /** Points the CloudStack volume in that provider slot at the disk's new volume (same storage pool, new image name). */
    private void recordMovedVolume(final VMInstanceVO vm, final String device, final String path) {
        for (com.cloud.storage.VolumeVO volume : volumeDao.findByInstance(vm.getId())) {
            com.cloud.storage.VolumeDetailVO slot = volumeDetailsDao.findDetail(volume.getId(), "externalSlot");
            if (slot != null && device.equals(slot.getValue()) && !path.equals(volume.getPath())) {
                logger.info("Volume {} of {} is now {} (was {})", volume.getUuid(), vm, path, volume.getPath());
                volume.setPath(path);
                volumeDao.update(volume.getId(), volume);
            }
        }
    }

    private void quickRestoreEvent(final VMInstanceVO vm, final String level, final String description) {
        ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), level, EventTypes.EVENT_VM_BACKUP_RESTORE,
                description, vm.getId(), ApiCommandResourceType.VirtualMachine.toString(), 0);
    }

    private void prepareForBackupRestoration(VirtualMachine vm) {
        if (!Hypervisor.HypervisorType.VMware.equals(vm.getHypervisorType())) {
            return;
        }
        logger.info("Preparing for restoring VM " + vm);
        PrepareForBackupRestorationCommand command = new PrepareForBackupRestorationCommand(vm.getInstanceName());
        Long hostId = virtualMachineManager.findClusterAndHostIdForVm(vm.getId()).second();
        if (hostId == null) {
            throw new CloudRuntimeException("Cannot find a host to prepare for restoring VM " + vm);
        }
        try {
            Answer answer = agentMgr.easySend(hostId, command);
            if (answer != null && answer.getResult()) {
                logger.info("Succeeded to prepare for restoring VM " + vm);
            } else {
                throw new CloudRuntimeException(String.format("Failed to prepare for restoring VM %s. details: %s", vm,
                        (answer != null ? answer.getDetails() : null)));
            }
        } catch (Exception e) {
            throw new CloudRuntimeException(String.format("Failed to prepare for restoring VM %s due to exception %s", vm, e));
        }
    }

    @Override
    public Pair<Boolean, String> restoreBackedUpVolume(Backup backup, Backup.VolumeInfo backupVolumeInfo, String hostIp, String dataStoreUuid,
            Pair<String, VirtualMachine.State> vmNameAndState, VirtualMachine vm, boolean quickRestore) {
        requireNotExternal(vm, "Restoring a volume");
        final Long zoneId = backup.getZoneId();
        final String restorePointId = backup.getExternalId();
        return getClient(zoneId).restoreVMToDifferentLocation(restorePointId, null, hostIp, dataStoreUuid);
    }

    private VbrRestClient.JobState restJobState(final Long zoneId, final String jobId) {
        Map<String, VbrRestClient.JobState> states = restJobStates.get(zoneId);
        return states == null || jobId == null ? null : states.get(jobId);
    }

    private static boolean isDisabled(final VbrRestClient.JobState state) {
        return state != null && "Disabled".equalsIgnoreCase(state.status);
    }

    /**
     * A Veeam job with a schedule of its own runs the backups: CloudStack shows that schedule read-only. A job without
     * one is run by CloudStack, on its schedules or Backup now. A disabled job is left to Veeam: CloudStack shows no
     * schedule for it and neither schedules nor starts it. A job not seen yet counts as scheduled in Veeam, so
     * CloudStack never starts one on a schedule by mistake.
     */
    @Override
    public boolean isBackupScheduleManagedByProvider(final BackupOffering offering) {
        if (offering == null || !getName().equals(offering.getProvider()) || !isRestEnabled(offering.getZoneId())) {
            return false;
        }
        if (isTemplateOffering(offering)) {
            return true;
        }
        VbrRestClient.JobState state = restJobState(offering.getZoneId(), offering.getExternalId());
        if (state == null) {
            return !isEnterpriseManagerEnabled(offering.getZoneId());
        }
        return isDisabled(state) || StringUtils.isNotBlank(state.nextRun);
    }

    /**
     * The Veeam job's schedule as one CloudStack schedule record. The REST API gives only the job's last and next run
     * (Proxmox jobs cannot be read in full), so the interval is taken from their distance: monthly from 27 days,
     * weekly from 6 days, else daily, at the next run's time and UTC offset.
     */
    @Override
    public List<BackupSchedule> listProviderBackupSchedules(final VirtualMachine vm) {
        VbrRestClient.JobState state = restJobState(vm.getDataCenterId(), vm.getBackupExternalId());
        if (state == null) {
            return null;
        }
        if (isDisabled(state) || StringUtils.isBlank(state.nextRun)) {
            return new ArrayList<>();
        }
        java.time.OffsetDateTime next = java.time.OffsetDateTime.parse(state.nextRun);
        long days = StringUtils.isBlank(state.lastRun) ? 1
                : java.time.Duration.between(java.time.OffsetDateTime.parse(state.lastRun), next).toDays();
        com.cloud.utils.DateUtil.IntervalType type;
        String schedule;
        if (days >= 27) {
            type = com.cloud.utils.DateUtil.IntervalType.MONTHLY;
            schedule = String.format("%02d:%02d:%02d", next.getMinute(), next.getHour(), next.getDayOfMonth());
        } else if (days >= 6) {
            type = com.cloud.utils.DateUtil.IntervalType.WEEKLY;
            schedule = String.format("%02d:%02d:%d", next.getMinute(), next.getHour(), next.getDayOfWeek().getValue() % 7 + 1);
        } else {
            type = com.cloud.utils.DateUtil.IntervalType.DAILY;
            schedule = String.format("%02d:%02d", next.getMinute(), next.getHour());
        }
        String timezone = "GMT" + (next.getOffset().getTotalSeconds() == 0 ? "" : next.getOffset().getId());
        List<BackupSchedule> schedules = new ArrayList<>();
        schedules.add(new BackupScheduleVO(vm.getId(), type, schedule, timezone, null, 0, null, vm.getAccountId(), vm.getDomainId(), false));
        return schedules;
    }

    private void requireNotExternal(VirtualMachine vm, String operation) {
        if (isExternal(vm)) {
            throw new CloudRuntimeException(operation + " Veeam backups of External instances is not supported yet; restore from the Veeam console.");
        }
    }

    @Override
    public void syncBackupMetrics(Long zoneId) {
        if (isEnterpriseManagerEnabled(zoneId)) {
            backupFilesMetricsMap = getClient(zoneId).getBackupMetrics();
        }
        if (isRestEnabled(zoneId)) {
            Map<String, VbrRestClient.JobState> states = new HashMap<>();
            for (VbrRestClient.JobState state : getRestClient(zoneId).listJobStates()) {
                states.put(state.id, state);
            }
            restJobStates.put(zoneId, states);
            readCopyTemplates(zoneId);
            applyPendingMemberships(zoneId);
            reconcileInstanceJobs(zoneId);
            assignVspcCompanies(zoneId);
            finishQuickRestores(zoneId);
            syncRestRestorePoints(zoneId);
        }
    }

    @Override
    public Backup createNewBackupEntryForRestorePoint(Backup.RestorePoint restorePoint, VirtualMachine vm) {
        BackupVO backup = new BackupVO();
        backup.setVmId(vm.getId());
        backup.setExternalId(restorePoint.getId());
        backup.setType(restorePoint.getType());
        backup.setDate(restorePoint.getCreated());
        backup.setStatus(Backup.Status.BackedUp);
        if (restorePoint.getBackupSize() != null) {
            backup.setSize(restorePoint.getBackupSize());
        }
        if (restorePoint.getDataSize() != null) {
            backup.setProtectedSize(restorePoint.getDataSize());
        }
        backup.setBackupOfferingId(vm.getBackupOfferingId());
        backup.setAccountId(vm.getAccountId());
        backup.setDomainId(vm.getDomainId());
        backup.setZoneId(vm.getDataCenterId());
        backup.setName(backupManager.getBackupNameFromVM(vm));
        List<Volume> volumes = new ArrayList<>(volumeDao.findByInstance(vm.getId()));
        backup.setBackedUpVolumes(backupManager.createVolumeInfoFromVolumes(volumes));
        Map<String, String> details = backupManager.getBackupDetailsFromVM(vm);
        backup.setDetails(details);
        backupDao.persist(backup);
        return backup;
    }

    @Override
    public List<Backup.RestorePoint> listRestorePoints(VirtualMachine vm) {
        if (isExternal(vm)) {
            // Read during the framework's sync transaction: no network calls here, only what syncBackupMetrics gathered.
            return isRestEnabled(vm.getDataCenterId()) ? restRestorePoints.get(vm.getId()) : null;
        }
        if (vm.getHypervisorType() != Hypervisor.HypervisorType.VMware) {
            // KVM instances are backed up by Veeam through the Veeam control service, which records them itself.
            return null;
        }
        final VmwareDatacenter vmwareDC = findVmwareDatacenterForVM(vm);
        String backupName = getGuestBackupName(vm.getInstanceName(), vm.getUuid());
        return getClient(vm.getDataCenterId()).listRestorePoints(backupName, vmwareDC.getVcenterHost(), vm.getInstanceName(), backupFilesMetricsMap);
    }

    @Override
    public Pair<Boolean, String> restoreBackupToVM(VirtualMachine vm, Backup backup, String hostIp, String dataStoreUuid, boolean quickrestore) {
        requireNotExternal(vm, "Restoring");
        final Long zoneId = backup.getZoneId();
        final String restorePointId = backup.getExternalId();
        final String restoreLocation = vm.getInstanceName();
        return getClient(zoneId).restoreVMToDifferentLocation(restorePointId, restoreLocation, hostIp, dataStoreUuid);
    }

    @Override
    public boolean supportsInstanceFromBackup() {
        return true;
    }

    @Override
    public Pair<Long, Long> getBackupStorageStats(Long zoneId) {
        return new Pair<>(0L, 0L);
    }

    @Override
    public void syncBackupStorageStats(Long zoneId) {
    }

    @Override
    public Boolean crossZoneInstanceCreationEnabled(BackupOffering backupOffering) {
        return false;
    }

    @Override
    public String getConfigComponentName() {
        return BackupService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[]{
                VeeamUrl,
                VeeamVersion,
                VeeamUsername,
                VeeamPassword,
                VeeamValidateSSLSecurity,
                VeeamApiRequestTimeout,
                VeeamRestoreTimeout,
                VeeamTemplatePrefix,
                VeeamQuickRestoreStorage,
                VeeamCopyJob,
                VeeamCopyRepository,
                VspcUrl,
                VspcApiKey,
                VspcCompany,
                VeeamTaskPollInterval,
                VeeamTaskPollMaxRetry,
                VeeamEnterpriseManagerEnabled,
                VeeamRestUrl,
                VeeamRestUsername,
                VeeamRestPassword,
                VeeamRestApiVersion,
                VeeamProxmoxPool
        };
    }

    @Override
    public String getName() {
        return "veeam";
    }

    @Override
    public String getDescription() {
        return "Veeam Backup Plugin";
    }
}
