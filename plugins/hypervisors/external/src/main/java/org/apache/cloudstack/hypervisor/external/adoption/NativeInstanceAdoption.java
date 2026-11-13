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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.hypervisor.external.refresh.ExternalVmConfiguration;
import org.apache.cloudstack.hypervisor.external.refresh.ExternalVmRefreshManager;
import org.apache.cloudstack.resourcelimit.Reserver;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.RunCustomActionCommand;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceAllocationException;
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
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicProfile;
import com.cloud.vm.UserVmService;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Adopts one guest that already exists at the provider.
 *
 * The provider is only read. The claim, the instance, its NICs and its details are written
 * in one database transaction, and the claim becomes managed only after the records have
 * been checked against a second provider snapshot. Any failure rolls the whole adoption back.
 * Disks are not recorded, as for every other External instance.
 */
public class NativeInstanceAdoption {
    @Inject HostDao hostDao;
    @Inject VMInstanceDao vmDao;
    @Inject VMInstanceDetailsDao detailsDao;
    @Inject ServiceOfferingDao offeringDao;
    @Inject VMTemplateDao templateDao;
    @Inject DataCenterDao dataCenterDao;
    @Inject AccountService accountService;
    @Inject com.cloud.domain.dao.DomainDao domainDao;
    @Inject ResourceLimitService resourceLimits;
    @Inject ExtensionHelper extensionHelper;
    @Inject ExtensionsManager extensionsManager;
    @Inject AgentManager agentManager;
    @Inject VirtualMachineManager vmManager;
    @Inject UserVmService userVmService;
    @Inject NetworkOrchestrationService networkOrchestrator;
    @Inject ExternalVmRefreshManager refreshManager;
    @Inject NativeManagedAdoptionVerifier verifier;

    public static final class Request {
        final Long hostId, serviceOfferingId, templateId, domainId;
        final int vmid;
        final String accountName, name;
        final Integer cpuSpeed;
        final boolean dryRun;
        public Request(Long hostId, int vmid, Long serviceOfferingId, Long templateId, String accountName, Long domainId,
                String name, Integer cpuSpeed, boolean dryRun) {
            this.hostId = hostId; this.vmid = vmid; this.serviceOfferingId = serviceOfferingId; this.templateId = templateId;
            this.accountName = accountName; this.domainId = domainId; this.name = name; this.cpuSpeed = cpuSpeed; this.dryRun = dryRun;
        }
    }

    private static IllegalArgumentException reject(String why) { return new IllegalArgumentException(why); }

    AdoptionRegistry registry() { return new AdoptionRegistry(); }

    private Map<String, String> hostDetails(HostVO host) {
        Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
        Map<String, String> details = access == null ? null : access.get("host");
        return details == null ? Map.of() : details;
    }

    /** A provider read for a guest that has no CloudStack instance yet. */
    ExternalVmConfiguration snapshot(HostVO host, String cluster, int vmid) {
        Map<String, Map<String, String>> access = new HashMap<>(extensionsManager.getExternalAccessDetails(host, null));
        access.put(ApiConstants.VIRTUAL_MACHINE, Map.of("proxmox_vmid", Integer.toString(vmid)));
        RunCustomActionCommand cmd = new RunCustomActionCommand(ExtensionsManager.GET_CONFIG_ACTION);
        cmd.setExternalDetails(access);
        cmd.setWait(60);
        Answer answer = agentManager.easySend(host.getId(), cmd);
        if (answer == null || !answer.getResult()) {
            // The extension's own error text names the cause, such as a config lock or pending changes.
            String detail = answer == null || answer.getDetails() == null ? "" : answer.getDetails();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"error\"\\s*:\\s*\"([^\"]{1,200})").matcher(detail);
            throw reject("the provider configuration of instance " + vmid + " cannot be read" + (m.find() ? ": " + m.group(1) : ""));
        }
        try {
            ExternalVmConfiguration snapshot = ExternalVmConfiguration.parse(answer.getDetails());
            snapshot.requireIdentity(cluster, Integer.toString(vmid));
            return snapshot;
        } catch (RuntimeException e) {
            throw reject("the provider configuration of instance " + vmid + " is invalid");
        }
    }

    /** No CloudStack instance of this provider cluster may already hold the VMID or the provider name. */
    void requireUnmapped(String cluster, int vmid, String providerName) {
        VMInstanceVO named = vmDao.findVMByInstanceName(providerName);
        if (named != null && named.getRemoved() == null) throw reject("the provider name " + providerName + " is the name of a CloudStack instance");
        for (String key : new String[] {"proxmox_vmid", "External:proxmox_vmid", "external.proxmox_vmid"}) {
            for (VMInstanceDetailVO row : detailsDao.findDetails(key)) {
                if (!Integer.toString(vmid).equals(row.getValue())) continue;
                VMInstanceVO vm = vmDao.findById(row.getResourceId());
                if (vm == null || vm.getRemoved() != null || vm.getHypervisorType() != HypervisorType.External) continue;
                Pair<Long, Long> place = vmManager.findClusterAndHostIdForVm(vm, false);
                HostVO host = place == null || place.second() == null ? null : hostDao.findById(place.second());
                // Without a known placement the instance could be the same guest.
                if (host == null || cluster.equals(hostDetails(host).get("proxmox_cluster"))) {
                    throw reject("provider instance " + vmid + " already belongs to CloudStack instance " + vm.getUuid());
                }
            }
        }
    }

    static String sha256(String text) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** What was adopted, in the form the legacy registry used: sorted keys, no whitespace. */
    static String manifest(ExternalVmConfiguration snapshot, int vmid, Account owner, String domainUuid, Map<ExternalVmConfiguration.Nic, NetworkVO> nics) {
        Map<String, Object> doc = new TreeMap<>();
        doc.put("cpus", snapshot.cpus);
        doc.put("memory_mib", snapshot.memoryMb);
        doc.put("name", snapshot.name);
        List<Map<String, Object>> networks = new ArrayList<>();
        for (Map.Entry<ExternalVmConfiguration.Nic, NetworkVO> e : nics.entrySet()) {
            Map<String, Object> nic = new TreeMap<>();
            nic.put("bridge", e.getKey().bridge);
            nic.put("cloudstack_network_id", e.getValue().getUuid());
            nic.put("device", e.getKey().deviceId);
            nic.put("mac", e.getKey().mac);
            nic.put("tag", e.getKey().vlan);
            networks.add(nic);
        }
        doc.put("networks", networks);
        Map<String, Object> ownership = new TreeMap<>();
        ownership.put("account", owner.getAccountName());
        ownership.put("domain_id", domainUuid);
        ownership.put("project_id", null);
        doc.put("ownership", ownership);
        Map<String, Object> placement = new TreeMap<>();
        placement.put("cluster", snapshot.cluster);
        placement.put("node", snapshot.node);
        doc.put("placement", placement);
        doc.put("status", snapshot.power);
        doc.put("vmid", vmid);
        try {
            return new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writeValueAsString(doc);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    public ExternalAdoptionResponse adopt(Request request) {
        if (request.vmid <= 0) throw reject("a positive provider instance ID is required");
        HostVO queried = request.hostId == null ? null : hostDao.findById(request.hostId);
        if (queried == null || queried.getRemoved() != null || queried.getHypervisorType() != HypervisorType.External || queried.getClusterId() == null) {
            throw reject("an External host with a cluster is required");
        }
        Extension extension = extensionHelper.getExtensionForCluster(queried.getClusterId());
        if (extension == null || !extensionHelper.isInstanceGetConfigSupported(extension.getId())) {
            throw reject("the extension of this cluster does not support configuration snapshots");
        }
        final String cluster;
        try { cluster = AdoptionRegistry.canonicalCluster(hostDetails(queried).get("proxmox_cluster")); }
        catch (RuntimeException e) { throw reject("the host has no exact provider cluster identity"); }

        final ExternalVmConfiguration snapshot = snapshot(queried, cluster, request.vmid);
        if (!"running".equals(snapshot.power) && !"stopped".equals(snapshot.power)) throw reject("the provider instance is neither running nor stopped");
        if (snapshot.name == null || snapshot.name.isEmpty()) throw reject("the provider instance has no name");
        if (snapshot.nics.isEmpty()) throw reject("the provider instance has no NIC");

        // The instance is placed on the CloudStack host of the node the guest is on now.
        HostVO placed = null;
        for (HostVO candidate : hostDao.listByClusterAndHypervisorType(queried.getClusterId(), HypervisorType.External)) {
            Map<String, String> details = hostDetails(candidate);
            if (candidate.getRemoved() == null && cluster.equals(details.get("proxmox_cluster")) && snapshot.node.equals(details.get("node"))) {
                if (placed != null) throw reject("more than one CloudStack host maps provider node " + snapshot.node);
                placed = candidate;
            }
        }
        if (placed == null) throw reject("no CloudStack host of this cluster maps provider node " + snapshot.node);
        final HostVO host = placed;
        Map<String, String> placedDetails = hostDetails(host);
        if (!"true".equals(placedDetails.get("native_adoption_lifecycle")) || !"true".equals(placedDetails.get("native_adoption_status"))) {
            throw reject("native adoption status and lifecycle are not enabled on host " + host.getName());
        }
        final String bridge = placedDetails.get("network_bridge");

        requireUnmapped(cluster, request.vmid, snapshot.name);

        Account caller = CallContext.current().getCallingAccount();
        final Account owner = accountService.finalizeOwner(caller, request.accountName, request.domainId, null);
        if (owner == null || owner.getType() == Account.Type.PROJECT) throw reject("an account owner is required; projects are not supported");
        final String domainUuid = domainDao.findById(owner.getDomainId()).getUuid();

        final DataCenterVO zone = dataCenterDao.findById(host.getDataCenterId());
        final VMTemplateVO template = request.templateId == null ? null : templateDao.findById(request.templateId);
        if (template == null || template.getHypervisorType() != HypervisorType.External || template.getFormat() != ImageFormat.EXTERNAL) {
            throw reject("an External template is required");
        }
        ServiceOfferingVO base = request.serviceOfferingId == null ? null : offeringDao.findById(request.serviceOfferingId);
        if (base == null) throw reject("a service offering is required");
        final Map<String, String> details = new LinkedHashMap<>();
        final ServiceOfferingVO offering;
        if (base.isDynamic()) {
            Integer speed = base.getSpeed() != null ? base.getSpeed() : request.cpuSpeed;
            if (speed == null || speed <= 0) throw reject("this custom offering has no CPU speed; pass cpuspeed");
            if ((base.getCpu() != null && base.getCpu() != snapshot.cpus) || (base.getRamSize() != null && base.getRamSize() != snapshot.memoryMb)) {
                throw reject("the offering fixes a CPU count or memory size that differs from the instance (" + snapshot.cpus + " CPU, " + snapshot.memoryMb + " MiB)");
            }
            details.put("cpuNumber", Integer.toString(snapshot.cpus));
            details.put("memory", Integer.toString(snapshot.memoryMb));
            details.put("cpuSpeed", Integer.toString(speed));
            offering = offeringDao.getComputeOffering(base, details);
        } else {
            if (!Objects.equals(base.getCpu(), snapshot.cpus) || !Objects.equals(base.getRamSize(), snapshot.memoryMb)) {
                throw reject("the offering differs from the instance (" + snapshot.cpus + " CPU, " + snapshot.memoryMb + " MiB); use a custom offering");
            }
            offering = base;
        }

        final Map<ExternalVmConfiguration.Nic, NetworkVO> nics = new LinkedHashMap<>();
        List<String> nicReport = new ArrayList<>();
        for (ExternalVmConfiguration.Nic nic : snapshot.nics) {
            NetworkVO network = refreshManager.importNetwork(owner, zone.getId(), nic, bridge);
            if (network == null) {
                throw reject("NIC " + nic.deviceId + " (" + nic.mac + ", bridge " + nic.bridge + ", VLAN " + nic.vlan
                        + ") has no single matching L2 network that " + owner.getAccountName() + " may use");
            }
            nics.put(nic, network);
            nicReport.add(nic.deviceId + " " + nic.mac + " on " + network.getUuid());
        }
        List<Reserver> reservations = new ArrayList<>();
        try {
            resourceLimits.checkVmResourceLimit(owner, true, offering, template, reservations);
        } catch (ResourceAllocationException e) {
            throw reject("the owner's resource limit would be exceeded: " + e.getMessage());
        } finally {
            for (Reserver r : reservations) { try { r.close(); } catch (Exception ignored) { } }
        }

        final ExternalAdoptionResponse response = new ExternalAdoptionResponse(request.dryRun, cluster, request.vmid, snapshot.node,
                snapshot.name, snapshot.power, snapshot.cpus, snapshot.memoryMb, nicReport);
        if (request.dryRun) return response;

        final String manifest = manifest(snapshot, request.vmid, owner, domainUuid, nics);
        final String hash = sha256(manifest);
        final boolean running = "running".equals(snapshot.power);
        final String displayName = request.name == null || request.name.isEmpty() ? snapshot.name : request.name;
        return Transaction.execute((TransactionCallback<ExternalAdoptionResponse>) status -> {
            AdoptionRegistry registry = registry();
            AdoptionRegistry.Reservation claim = registry.reserve(cluster, snapshot.node, request.vmid, manifest, hash);
            details.put("proxmox_vmid", Integer.toString(request.vmid));
            details.put("External:proxmox_vmid", Integer.toString(request.vmid));
            details.put("External:proxmox_cluster", cluster);
            details.put("External:adopt_existing", "true");
            details.put("External:adopt_claim_id", claim.claimId);
            details.put("External:adopt_claim_generation", Integer.toString(claim.generation));
            details.put("External:adopt_manifest_sha256", hash);
            details.put("External:adopt_manifest_json", manifest);
            String hostName = "adopt-" + request.vmid + "-" + claim.claimId.substring(0, 8);
            final UserVm created;
            try {
                created = userVmService.importVM(zone, host, template, null, displayName, owner, null, caller, true, null, owner.getId(),
                        CallContext.current().getCallingUserId(), offering, null, null, hostName, HypervisorType.External, details,
                        running ? VirtualMachine.PowerState.PowerOn : VirtualMachine.PowerState.PowerOff, null);
            } catch (InsufficientCapacityException e) {
                throw new CloudRuntimeException("Failed to record the adopted instance", e);
            }
            // The instance already exists at the provider, so its first start here is a start, not a deployment.
            detailsDao.removeDetail(created.getId(), "deployvm");
            // The import leaves an External instance without a host.
            VMInstanceVO vm = vmDao.findById(created.getId());
            if (running) vm.setHostId(host.getId());
            vm.setLastHostId(host.getId());
            if (!vmDao.update(vm.getId(), vm)) throw new CloudRuntimeException("Failed to place the adopted instance");
            int device = 0;
            List<Pair<Long, NetworkVO>> imported = new ArrayList<>();
            for (Map.Entry<ExternalVmConfiguration.Nic, NetworkVO> e : nics.entrySet()) {
                try {
                    Pair<NicProfile, Integer> nic = networkOrchestrator.importNic(e.getKey().mac, device, e.getValue(), device == 0, vm, null, zone, false);
                    imported.add(new Pair<>(nic == null || nic.first() == null ? null : nic.first().getId(), e.getValue()));
                } catch (InsufficientCapacityException failure) {
                    throw new CloudRuntimeException("Failed to record NIC " + e.getKey().mac, failure);
                }
                device++;
            }
            registry.bind(claim, vm.getUuid(), vm.getInstanceName());
            AdoptionRegistry.Identity identity = new AdoptionRegistry.Identity(claim.claimId, claim.generation, cluster, request.vmid,
                    hash, vm.getUuid(), vm.getInstanceName());
            registry.activate(identity, verifier.forAdoption(row -> {
                ExternalVmConfiguration again = snapshot(host, cluster, request.vmid);
                if (!snapshot.configDigest.equals(again.configDigest) || !snapshot.power.equals(again.power) || !snapshot.node.equals(again.node)) {
                    throw new AdoptionRegistry.Conflict("the provider instance changed during adoption; adopt it again");
                }
                return again;
            }));
            account(vm, offering, template, running, imported);
            response.adopted(vm.getUuid(), vm.getInstanceName(), claim.claimId, claim.generation);
            return response;
        });
    }

    /** The usage records, resource counts and event that deploying the instance would have left. */
    protected void account(VMInstanceVO vm, ServiceOfferingVO offering, VMTemplateVO template, boolean running, List<Pair<Long, NetworkVO>> nics) {
        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_CREATE, vm.getAccountId(), vm.getDataCenterId(), vm.getId(), vm.getHostName(),
                offering.getId(), vm.getTemplateId(), vm.getHypervisorType().toString(), VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplay());
        if (running) {
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_START, vm.getAccountId(), vm.getDataCenterId(), vm.getId(), vm.getHostName(),
                    offering.getId(), vm.getTemplateId(), vm.getHypervisorType().toString(), VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplay());
        }
        boolean first = true;
        for (Pair<Long, NetworkVO> nic : nics) {
            if (nic.first() != null) {
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                        Long.toString(nic.first()), nic.second().getNetworkOfferingId(), null, first ? 1L : 0L, VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplay());
            }
            first = false;
        }
        if (running || !VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            resourceLimits.incrementVmResourceCount(vm.getAccountId(), true, offering, template, null);
        } else {
            resourceLimits.incrementResourceCount(vm.getAccountId(), ResourceType.user_vm);
        }
        ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), vm.getAccountId(), "INFO", EventTypes.EVENT_VM_IMPORT,
                "Adopted the existing provider instance; nothing was changed at the provider", vm.getId(), "VirtualMachine", 0);
    }
}
