// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.hypervisor.external.refresh;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.hypervisor.external.adoption.AdoptionRegistry;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.hypervisor.external.observation.ExternalObservationService;
import org.apache.cloudstack.hypervisor.external.observation.ObservationBindingResolver;
import org.apache.cloudstack.hypervisor.external.observation.ObservationValidation;
import org.apache.cloudstack.hypervisor.external.storage.ExternalVolumeSync;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.network.Network;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.user.Account;
import com.cloud.agent.api.Answer;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

/** One-shot metadata refresh. No provider mutations, topology mutations, or state transitions. */
public class ExternalVmRefreshManager extends ManagerBase implements PluggableService {
    @Inject VMInstanceDao vmDao;
    @Inject VMInstanceDetailsDao detailsDao;
    @Inject ServiceOfferingDao offeringDao;
    @Inject VMTemplateDao templateDao;
    @Inject NicDao nicDao;
    @Inject HostDao hostDao;
    @Inject AccountService accountService;
    @Inject ResourceLimitService resourceLimits;
    @Inject VirtualMachineManager vmManager;
    @Inject ExtensionsManager extensionsManager;
    @Inject NetworkDao networkDao;
    @Inject ObservationBindingResolver observationBindingResolver;
    @Inject ExternalObservationService observationService;
    @Inject NetworkOrchestrationService networkOrchestrator;
    @Inject DataCenterDao dataCenterDao;
    @Inject ExternalVolumeSync volumeSync;
    @Inject org.apache.cloudstack.hypervisor.external.storage.ExternalInstanceLock instanceLock;

    @Override public List<Class<?>> getCommands() { return List.of(RefreshExternalVirtualMachineCmd.class); }

    static final class Binding {
        final long hostId;
        final long clusterId;
        final String cluster;
        final String vmid;
        final String node;
        Binding(long hostId, long clusterId, String cluster, String vmid, String node) {
            this.hostId = hostId; this.clusterId = clusterId; this.cluster = cluster; this.vmid = vmid; this.node = node;
        }
        boolean same(Binding b) {
            return hostId == b.hostId && clusterId == b.clusterId && Objects.equals(cluster, b.cluster)
                    && Objects.equals(vmid, b.vmid) && Objects.equals(node, b.node);
        }
    }

    private static String exact(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidParameterValueException("Missing or malformed external identity");
        }
        return value;
    }

    private String uniqueDetail(long vmId, String name) {
        List<VMInstanceDetailVO> rows = detailsDao.findDetails(vmId, name);
        if (rows == null || rows.isEmpty()) return null;
        if (rows.size() != 1) throw new InvalidParameterValueException("Ambiguous external instance identity");
        return exact(rows.get(0).getValue());
    }

    Binding binding(VMInstanceVO vm) {
        if (vm == null || vm.getType() != VirtualMachine.Type.User || vm.getHypervisorType() != HypervisorType.External
                || (vm.getState() != VirtualMachine.State.Running && vm.getState() != VirtualMachine.State.Stopped)) {
            throw new InvalidParameterValueException("A running or stopped External user instance is required");
        }
        String plain = uniqueDetail(vm.getId(), "proxmox_vmid");
        String prefixed = uniqueDetail(vm.getId(), "External:proxmox_vmid");
        if (plain != null && prefixed != null && !plain.equals(prefixed)) {
            throw new InvalidParameterValueException("Conflicting external instance identity aliases");
        }
        String vmid = exact(plain == null ? prefixed : plain);
        if (!vmid.matches("[1-9][0-9]*")) throw new InvalidParameterValueException("Malformed external instance identity");
        Pair<Long, Long> placement = vmManager.findClusterAndHostIdForVm(vm, false);
        HostVO host = placement == null || placement.second() == null ? null : hostDao.findById(placement.second());
        if (host == null || host.getHypervisorType() != HypervisorType.External || placement.first() == null
                || !Objects.equals(host.getClusterId(), placement.first())) {
            throw new InvalidParameterValueException("External instance placement is unavailable or inconsistent");
        }
        Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
        Map<String, String> hostDetails = access == null ? Collections.emptyMap() : access.getOrDefault("host", Collections.emptyMap());
        String cluster = exact(hostDetails.get("proxmox_cluster"));
        String node = exact(hostDetails.get("node"));
        String vmCluster = uniqueDetail(vm.getId(), "proxmox_cluster");
        String vmClusterExternal = uniqueDetail(vm.getId(), "External:proxmox_cluster");
        if ((vmCluster != null && !vmCluster.equals(cluster)) || (vmClusterExternal != null && !vmClusterExternal.equals(cluster))) {
            throw new InvalidParameterValueException("Instance and host external cluster identities disagree");
        }
        return new Binding(host.getId(), host.getClusterId(), cluster, vmid, node);
    }

    ExternalVmConfiguration fetch(VMInstanceVO vm, Binding binding) {
        Answer answer = extensionsManager.getInstanceConfig(vm);
        if (answer == null || !answer.getResult()) throw new CloudRuntimeException("External configuration collection failed");
        ExternalVmConfiguration snapshot = ExternalVmConfiguration.parse(answer.getDetails());
        snapshot.requireIdentity(binding.cluster, binding.vmid);
        if (!binding.node.equals(snapshot.node)) throw new InvalidParameterValueException("External instance node does not match its selected host");
        if (!isAdopted(vm.getId()) && !Objects.equals(vm.getInstanceName(), snapshot.name)) {
            throw new InvalidParameterValueException("External instance name does not match its native binding");
        }
        String expectedPower = vm.getState() == VirtualMachine.State.Running ? "running" : "stopped";
        if (!expectedPower.equals(snapshot.power)) {
            throw new InvalidParameterValueException("External power state is still reconciling");
        }
        return snapshot;
    }

    private boolean isAdopted(long vmId) {
        for (String prefix : new String[]{"", "External:"}) {
            for (String key : new String[]{"adopt_claim_id", "adopt_claim_generation", "adopt_manifest_sha256", "adopt_manifest_json"}) {
                if (uniqueDetail(vmId, prefix + key) != null) return true;
            }
            String flag = uniqueDetail(vmId, prefix + "adopt_existing");
            if (flag != null && !"false".equals(flag)) return true;
        }
        return false;
    }

    public ExternalVmRefreshResponse refresh(Long vmId, boolean dryRun) {
        VMInstanceVO vm = vmId == null ? null : vmDao.findById(vmId);
        if (vm == null) throw new InvalidParameterValueException("Instance not found");
        accountService.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
        // Not while a disk of the instance is being changed: the change is at the provider before it is recorded.
        return instanceLock.hold(vmId, 1, () -> refreshLocked(vm, vmId, dryRun));
    }

    private ExternalVmRefreshResponse refreshLocked(VMInstanceVO vm, Long vmId, boolean dryRun) {
        Binding before = binding(vm);
        ExternalVmConfiguration initial = fetch(vm, before);
        ExternalNativeNicRefresh nicRefresh = new ExternalNativeNicRefresh(nicDao, networkDao, observationBindingResolver, observationService);
        Map<String, ExternalNativeNicRefresh.Evidence> collected = nicRefresh.collect(vm.getId(), initial);
        // Keep the same connection across SET TRANSACTION and Transaction.execute. Resolver reads
        // must not reuse a repeatable-read snapshot predating the NIC/network locks.
        TransactionLegacy isolation = TransactionLegacy.open("externalRefreshIsolation");
        try {
            boolean freshReads = isolation.getConnection().getAutoCommit();
            if (freshReads) {
                try (PreparedStatement ps = isolation.getConnection().prepareStatement("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")) { ps.execute(); }
            }
            Map<String, ExternalNativeNicRefresh.Evidence> evidence = freshReads ? collected : Collections.emptyMap();
            return Transaction.execute((com.cloud.utils.db.TransactionCallback<ExternalVmRefreshResponse>) status -> {
            VMInstanceVO locked = vmDao.lockRow(vmId, true);
            if (locked == null) throw new InvalidParameterValueException("Instance no longer exists");
            accountService.checkAccess(CallContext.current().getCallingAccount(), null, true, locked);
            Binding current = binding(locked);
            if (!before.same(current) || vm.getAccountId() != locked.getAccountId()
                    || vm.getServiceOfferingId() != locked.getServiceOfferingId() || vm.getState() != locked.getState()) {
                throw new InvalidParameterValueException("Instance identity or placement changed during refresh");
            }
            ExternalVmConfiguration fresh = fetch(locked, current);
            if (!current.same(binding(locked))) {
                throw new InvalidParameterValueException("External placement changed during collection");
            }
            if (!initial.configDigest.equals(fresh.configDigest) || initial.cpus != fresh.cpus || initial.memoryMb != fresh.memoryMb) {
                throw new InvalidParameterValueException("External configuration changed during refresh");
            }
            HostVO host = hostDao.findById(current.hostId);
            Map<String, Map<String, String>> access = host == null ? null : extensionsManager.getExternalAccessDetails(host, null);
            String bridge = access == null || access.get("host") == null ? null : access.get("host").get("network_bridge");
            return reconcile(locked, fresh, dryRun, nicRefresh, evidence, bridge, current.clusterId);
            });
        } catch (SQLException e) {
            throw new CloudRuntimeException("Cannot establish refresh transaction isolation", e);
        } finally {
            isolation.close();
        }
    }

    ExternalVmRefreshResponse reconcile(VMInstanceVO vm, ExternalVmConfiguration snapshot, boolean dryRun) {
        return reconcile(vm, snapshot, dryRun, null, Collections.emptyMap(), null, null);
    }

    ExternalVmRefreshResponse reconcile(VMInstanceVO vm, ExternalVmConfiguration snapshot, boolean dryRun,
            ExternalNativeNicRefresh nicRefresh, Map<String, ExternalNativeNicRefresh.Evidence> evidence, String bridge) {
        return reconcile(vm, snapshot, dryRun, nicRefresh, evidence, bridge, null);
    }

    ExternalVmRefreshResponse reconcile(VMInstanceVO vm, ExternalVmConfiguration snapshot, boolean dryRun,
            ExternalNativeNicRefresh nicRefresh, Map<String, ExternalNativeNicRefresh.Evidence> evidence, String bridge, Long clusterId) {
        ServiceOfferingVO base = offeringDao.findByIdIncludingRemoved(vm.getServiceOfferingId());
        // The DAO's map projection otherwise hides duplicate custom keys.
        uniqueDetail(vm.getId(), "cpuNumber");
        uniqueDetail(vm.getId(), "memory");
        uniqueDetail(vm.getId(), "cpuSpeed");
        ServiceOfferingVO current = offeringDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId());
        if (base == null || current == null || current.getCpu() == null || current.getRamSize() == null || current.getSpeed() == null) {
            throw new InvalidParameterValueException("Instance service offering is incomplete");
        }
        int oldCpu = current.getCpu(), oldMemory = current.getRamSize();
        int targetCpu = base.getCpu() == null ? snapshot.cpus : oldCpu;
        int targetMemory = base.getRamSize() == null ? snapshot.memoryMb : oldMemory;
        List<String> unresolved = new ArrayList<>();
        if (base.getCpu() != null && oldCpu != snapshot.cpus) unresolved.add("fixed offering CPU drift is report-only");
        if (base.getRamSize() != null && oldMemory != snapshot.memoryMb) unresolved.add("fixed offering memory drift is report-only");
        List<NicVO> nativeNics = nicDao.listByVmId(vm.getId());
        // A NIC added at the provider is imported when exactly one of the owner's L2 networks
        // has its VLAN on the host bridge. A NIC removed at the provider is only reported.
        Map<ExternalVmConfiguration.Nic, NetworkVO> nicImports = new java.util.LinkedHashMap<>();
        for (ExternalVmConfiguration.Nic providerNic : snapshot.nics) {
            if (nativeNics.stream().noneMatch(n -> providerNic.mac.equalsIgnoreCase(n.getMacAddress()))) {
                NetworkVO network = importNetwork(vm, providerNic, bridge);
                if (network == null) {
                    unresolved.add("provider-only NIC " + providerNic.deviceId + " (" + providerNic.mac + ") has no unique matching network");
                } else {
                    nicImports.put(providerNic, network);
                }
            }
        }
        for (NicVO nativeNic : nativeNics) {
            if (snapshot.nics.stream().noneMatch(n -> n.mac.equalsIgnoreCase(nativeNic.getMacAddress()))) {
                unresolved.add("native-only NIC " + nativeNic.getUuid() + " (" + nativeNic.getMacAddress() + ") is report-only");
            }
        }
        if (!snapshot.agentAvailable) unresolved.add("guest agent unavailable; only qualified observation fallback may update addresses");
        boolean changed = oldCpu != targetCpu || oldMemory != targetMemory;
        // Metadata refresh uses fresh source reads and a locked native VM binding.
        // It does not authorize adoption, power changes, or claim transitions.
        boolean adopted = isAdopted(vm.getId());
        String claimStamp = adopted ? claimIdleStamp(vm.getUuid()) : null;
        if (adopted && claimStamp == null) {
            unresolved.add("adopted instance is not managed or has an operation in progress; report-only");
            if (!dryRun) throw new InvalidParameterValueException("Adopted instance is not managed or has an operation in progress");
        }
        if (changed && !dryRun) {
            VMTemplateVO template = templateDao.findByIdIncludingRemoved(vm.getTemplateId());
            if (template == null) throw new InvalidParameterValueException("Instance template is unavailable for resource accounting");
            Map<String, String> parameters = new HashMap<>();
            parameters.put("cpuNumber", Integer.toString(targetCpu));
            parameters.put("cpuSpeed", current.getSpeed().toString());
            parameters.put("memory", Integer.toString(targetMemory));
            ServiceOfferingVO target = offeringDao.getComputeOffering(base, parameters);
            if (base.getCpu() == null && oldCpu != targetCpu) saveDetail(vm.getId(), "cpuNumber", targetCpu);
            if (base.getRamSize() == null && oldMemory != targetMemory) saveDetail(vm.getId(), "memory", targetMemory);
            if (!countsRunningOnly() || vm.getState() == VirtualMachine.State.Running) {
                resourceLimits.updateVmResourceCountForServiceOfferingChange(vm.getAccountId(), vm.isDisplay(), (long)oldCpu, (long)targetCpu,
                        (long)oldMemory, (long)targetMemory, current, target, template);
            }
            publishUpgrade(vm, parameters);
        }
        List<String> imported = new ArrayList<>();
        int deviceId = nativeNics.stream().mapToInt(NicVO::getDeviceId).max().orElse(-1);
        for (Map.Entry<ExternalVmConfiguration.Nic, NetworkVO> entry : nicImports.entrySet()) {
            if (!dryRun) {
                try {
                    boolean first = nativeNics.isEmpty() && imported.isEmpty();
                    Pair<com.cloud.vm.NicProfile, Integer> nic = networkOrchestrator.importNic(entry.getKey().mac, ++deviceId, entry.getValue(),
                            first, vm, null, dataCenterDao.findById(vm.getDataCenterId()), false);
                    publishNicImport(vm, nic == null || nic.first() == null ? null : nic.first().getId(), entry.getValue(), first);
                } catch (InsufficientCapacityException e) {
                    throw new CloudRuntimeException("Failed to import NIC " + entry.getKey().mac, e);
                }
            }
            imported.add(entry.getKey().mac + " on " + entry.getValue().getUuid());
        }
        List<ExternalVmRefreshResponse.IpChange> ipChanges = nicRefresh == null ? Collections.emptyList()
                : nicRefresh.apply(vm, snapshot, evidence, bridge, dryRun, unresolved);
        // Disks become volume records on the storages registered for the cluster. Nothing is written to the provider.
        List<ExternalVolumeSync.Change> volumeChanges = Collections.emptyList();
        if (volumeSync == null || clusterId == null) {
            if (snapshot.disks.size() > 0) unresolved.add("disk topology is report-only");
        } else {
            volumeChanges = volumeSync.sync(vm, clusterId, snapshot, dryRun, unresolved);
        }
        // Last, after every provider read: nothing above is committed if an operation started meanwhile.
        if (adopted && !dryRun && !claimStillIdle(vm.getUuid(), claimStamp)) {
            throw new InvalidParameterValueException("An operation started on the adopted instance during the refresh; nothing was changed");
        }
        return new ExternalVmRefreshResponse(vm.getUuid(), dryRun,
                !dryRun && (changed || !ipChanges.isEmpty() || !imported.isEmpty() || !volumeChanges.isEmpty()),
                snapshot, oldCpu, oldMemory, unresolved, ipChanges, imported, volumeChanges);
    }

    protected String claimIdleStamp(String vmUuid) {
        try { return new AdoptionRegistry().idleStamp(vmUuid); }
        catch (AdoptionRegistry.Conflict e) { return null; }
    }

    protected boolean claimStillIdle(String vmUuid, String stamp) {
        try { return new AdoptionRegistry().stillIdle(vmUuid, stamp); }
        catch (AdoptionRegistry.Conflict e) { return false; }
    }

    /** The one L2 guest network the instance's owner may use that carries this NIC's VLAN on the host bridge. */
    NetworkVO importNetwork(VMInstanceVO vm, ExternalVmConfiguration.Nic nic, String bridge) {
        return importNetwork(accountService.getAccount(vm.getAccountId()), vm.getDataCenterId(), nic, bridge);
    }

    public NetworkVO importNetwork(Account owner, long zoneId, ExternalVmConfiguration.Nic nic, String bridge) {
        if (bridge == null || nic.vlan == null || !bridge.equals(nic.bridge)) return null;
        String uri = "vlan://" + nic.vlan;
        NetworkVO match = null;
        for (NetworkVO network : networkDao.listByZoneAndUriAndGuestType(zoneId, uri, Network.GuestType.L2)) {
            if (network.getRemoved() != null || network.getTrafficType() != TrafficType.Guest
                    || network.getBroadcastUri() == null || !uri.equals(network.getBroadcastUri().toString())) continue;
            try { accountService.checkAccess(owner, AccessType.UseEntry, false, network); }
            catch (RuntimeException denied) { continue; }
            if (match != null) return null;
            match = network;
        }
        return match;
    }

    protected boolean countsRunningOnly() {
        return VirtualMachineManager.ResourceCountRunningVMsonly.value();
    }

    /** The same usage record and event that adding a NIC through the API leaves. */
    protected void publishNicImport(VMInstanceVO vm, Long nicId, NetworkVO network, boolean defaultNic) {
        if (nicId == null) return;
        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                Long.toString(nicId), network.getNetworkOfferingId(), null, defaultNic ? 1L : 0L, VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplay());
        com.cloud.event.ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), vm.getAccountId(), "INFO", EventTypes.EVENT_NIC_CREATE,
                "Imported a NIC found at the provider into network " + network.getUuid(), vm.getId(), "VirtualMachine", 0);
    }

    protected void publishUpgrade(VMInstanceVO vm, Map<String, String> parameters) {
        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_UPGRADE, vm.getAccountId(), vm.getDataCenterId(), vm.getId(), vm.getHostName(),
                vm.getServiceOfferingId(), vm.getTemplateId(), vm.getHypervisorType().toString(), VirtualMachine.class.getName(), vm.getUuid(), parameters, vm.isDisplay());
    }

    private void saveDetail(long vmId, String key, int value) {
        List<VMInstanceDetailVO> rows = detailsDao.findDetails(vmId, key);
        if (rows == null || rows.isEmpty()) {
            detailsDao.persist(new VMInstanceDetailVO(vmId, key, Integer.toString(value), true));
        } else if (rows.size() == 1) {
            VMInstanceDetailVO detail = rows.get(0);
            detail.setValue(Integer.toString(value));
            if (!detailsDao.update(detail.getId(), detail)) throw new CloudRuntimeException("Failed to update instance offering detail");
        } else {
            throw new InvalidParameterValueException("Ambiguous instance offering details");
        }
    }
}
