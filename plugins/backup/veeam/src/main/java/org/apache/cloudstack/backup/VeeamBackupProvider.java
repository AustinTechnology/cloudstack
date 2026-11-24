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

    @Inject
    private ExtensionsManager extensionsManager;
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
                instances.add(new VbrRestorePoints.Instance(vm.getId(), getBackupUuid(vm), vm.getBackupExternalId()));
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
                // Veeam has no per-VM ad-hoc backup for Proxmox; run the instance's job (incremental).
                session = client.startJob(vm.getBackupExternalId(), false);
            }
            logger.info("Started Veeam backup for {} (job {}): session {}", vm, vm.getBackupExternalId(), session.id);
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
        requireNotExternal(vm, "Restoring");
        final String restorePointId = backup.getExternalId();
        try {
            return getClient(vm.getDataCenterId()).restoreFullVM(vm.getInstanceName(), restorePointId);
        } catch (Exception ex) {
            logger.error(String.format("Failed to restore Full VM due to: %s. Retrying after some preparation", ex.getMessage()));
            prepareForBackupRestoration(vm);
            return getClient(vm.getDataCenterId()).restoreFullVM(vm.getInstanceName(), restorePointId);
        }
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
            applyPendingMemberships(zoneId);
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
