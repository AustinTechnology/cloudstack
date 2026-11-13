// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.adoption;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.hypervisor.external.refresh.ExternalVmConfiguration;
import com.cloud.agent.api.Answer;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.Networks.TrafficType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.AccountService;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

/** Read-only handover verifier for imported managed claims, not an adoption executor.
 * Caller must hold the legacy writer fence AND quiesce native VM, NIC, detail,
 * account, domain, host, network and offering writers across management servers
 * for the entire handover. The VM row is locked, but the other DAO reads are
 * not a complete concurrent-writer fence and may use a repeatable-read view.
 * The provider response is a point-in-time observation, not an incarnation fence. */
public class NativeManagedAdoptionVerifier implements AdoptionRegistry.VmVerifier {
    @Inject VMInstanceDao vmDao;
    @Inject VMInstanceDetailsDao detailsDao;
    @Inject NicDao nicDao;
    @Inject HostDao hostDao;
    @Inject NetworkDao networkDao;
    @Inject AccountDao accountDao;
    @Inject AccountService accountService;
    @Inject DomainDao domainDao;
    @Inject ServiceOfferingDao offeringDao;
    @Inject VirtualMachineManager vmManager;
    @Inject ExtensionsManager extensionsManager;

    private static AdoptionRegistry.Conflict fail(String why) {
        return new AdoptionRegistry.Conflict("managed handover: " + why);
    }
    private static String exact(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)) {
            throw fail("missing or malformed identity");
        }
        return value;
    }
    private String detail(long id, String name) {
        List<VMInstanceDetailVO> rows = detailsDao.findDetails(id, name);
        if (rows == null || rows.isEmpty()) return null;
        if (rows.size() != 1 || rows.get(0) == null) throw fail("duplicate instance detail");
        String value = rows.get(0).getValue();
        if (value == null || value.isEmpty()) throw fail("empty instance detail");
        return value;
    }
    private String aliases(long id, String name) {
        String result = null;
        for (String prefix : new String[]{"", "External:", "external."}) {
            String value = detail(id, prefix + name);
            if (value != null) {
                if (result != null && !result.equals(value)) throw fail("conflicting instance detail aliases");
                result = value;
            }
        }
        return result;
    }
    private void requiredDetail(long id, String key, String expected) {
        if (!expected.equals(aliases(id, key))) throw fail("instance adoption detail mismatch: " + key);
    }

    /** Active native External rows, not just adoption claims. Quiescence is essential:
     * listAll and the per-row detail/host reads do not fence concurrent writers. */
    private void requireUniqueNativeMapping(VMInstanceVO target, AdoptionRegistry.Identity i) {
        List<VMInstanceVO> instances = vmDao.listAll();
        if (instances == null) throw fail("native mapping enumeration unavailable");
        boolean sawTarget = false;
        Set<Long> seen = new HashSet<>();
        for (VMInstanceVO candidate : instances) {
            if (candidate == null) throw fail("invalid native mapping candidate");
            if (candidate.getRemoved() != null || candidate.getHypervisorType() != HypervisorType.External) continue;
            if (!seen.add(candidate.getId())) throw fail("duplicate native mapping candidate");
            if (candidate.getId() == target.getId()) sawTarget = true;
            String vmid = aliases(candidate.getId(), "proxmox_vmid");
            if (vmid != null) {
                try {
                    if (!vmid.matches("[1-9][0-9]*") || Integer.parseInt(vmid) <= 0) throw new IllegalArgumentException();
                } catch (IllegalArgumentException e) { throw fail("malformed candidate VMID"); }
                if (!vmid.equals(Integer.toString(i.vmid))) continue;
            }
            Long hostId = candidate.getHostId() != null ? candidate.getHostId() : candidate.getLastHostId();
            Pair<Long, Long> place = vmManager.findClusterAndHostIdForVm(candidate, false);
            if (hostId == null || place == null || place.first() == null || !Objects.equals(hostId, place.second())) {
                throw fail("candidate current or last host unavailable or inconsistent");
            }
            HostVO host = hostDao.findById(hostId);
            if (host == null || host.getRemoved() != null || host.getHypervisorType() != HypervisorType.External
                    || !Objects.equals(host.getClusterId(), place.first())) throw fail("candidate host or cluster mapping mismatch");
            Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
            Map<String, String> hd = access == null ? null : access.get("host");
            String cluster = hd == null ? null : hd.get("proxmox_cluster");
            try { cluster = AdoptionRegistry.canonicalCluster(cluster); }
            catch (RuntimeException e) { throw fail("malformed candidate provider cluster"); }
            String detailCluster = aliases(candidate.getId(), "proxmox_cluster");
            if (detailCluster != null && !cluster.equals(detailCluster)) throw fail("candidate cluster detail mismatch");
            if (cluster.equals(i.cluster)) {
                if (vmid == null) throw fail("candidate VMID unavailable in provider cluster");
                if (candidate.getId() != target.getId()) throw fail("duplicate native provider identity");
            }
        }
        if (!sawTarget) throw fail("target absent from native mapping enumeration");
    }

    /** Uses the already-held handover transaction; never starts another transaction. */
    void requireManagedImport(AdoptionRegistry.Identity i, String storedNode, String manifestJson) {
        try {
            TransactionLegacy tx = TransactionLegacy.currentTxn();
            if (tx == null || !tx.dbTxnStarted()) throw fail("handover transaction required");
            String sql = "SELECT c.state,c.migration_fenced,g.legacy_fenced FROM external_adoption_claims c "
                    + "JOIN external_adoption_control g ON g.id=1 WHERE BINARY c.id=BINARY ? AND c.generation=? "
                    + "AND BINARY c.proxmox_cluster=BINARY ? AND c.proxmox_vmid=? AND BINARY c.proxmox_node=BINARY ? "
                    + "AND BINARY c.manifest_sha256=BINARY ? AND BINARY c.manifest_json=BINARY ? "
                    + "AND BINARY c.cloudstack_vm_ref=BINARY ? AND BINARY c.cloudstack_instance_name=BINARY ? FOR UPDATE";
            try (PreparedStatement s = tx.getConnection().prepareStatement(sql)) {
                s.setString(1, i.claimId); s.setInt(2, i.generation); s.setString(3, i.cluster); s.setInt(4, i.vmid);
                s.setString(5, storedNode); s.setString(6, i.manifestHash); s.setString(7, manifestJson);
                s.setString(8, i.vmUuid); s.setString(9, i.instanceName);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next() || !"managed".equals(r.getString(1)) || !r.getBoolean(2) || !r.getBoolean(3) || r.next()) {
                        throw fail("only migration-fenced managed imports are supported; bound activation is unsupported");
                    }
                }
            }
        } catch (SQLException e) { throw fail("cannot verify managed import fence"); }
    }

    @Override public void verify(AdoptionRegistry.Identity i, String storedNode, String manifestJson) {
        if (i == null) throw fail("identity required");
        if (manifestJson == null || manifestJson.length() > 1024 * 1024) throw fail("manifest size unsupported");
        requireManagedImport(i, storedNode, manifestJson);
        final JsonNode manifest;
        try { manifest = AdoptionRegistry.manifest(manifestJson, i.manifestHash, i.cluster, storedNode, i.vmid); }
        catch (RuntimeException e) { throw fail("manifest bytes, hash or original placement mismatch"); }
        VMInstanceVO found = vmDao.findByUuid(i.vmUuid);
        if (found == null) throw fail("instance not found");
        // A row lock prevents concurrent native VM updates while taking the snapshot.
        VMInstanceVO vm = vmDao.lockRow(found.getId(), true);
        if (vm == null || vm.getRemoved() != null || !i.vmUuid.equals(vm.getUuid()) || !i.instanceName.equals(vm.getInstanceName())
                || vm.getType() != VirtualMachine.Type.User || vm.getHypervisorType() != HypervisorType.External
                || (vm.getState() != VirtualMachine.State.Running && vm.getState() != VirtualMachine.State.Stopped)) {
            throw fail("instance identity, type or stable state mismatch");
        }
        long id = vm.getId();
        requiredDetail(id, "proxmox_vmid", Integer.toString(i.vmid));
        requiredDetail(id, "proxmox_cluster", i.cluster);
        requiredDetail(id, "adopt_existing", "true");
        requiredDetail(id, "adopt_claim_id", i.claimId);
        requiredDetail(id, "adopt_claim_generation", Integer.toString(i.generation));
        requiredDetail(id, "adopt_manifest_sha256", i.manifestHash);
        requiredDetail(id, "adopt_manifest_json", manifestJson);
        verifyOwner(vm, manifest);
        requireUniqueNativeMapping(vm, i);

        Pair<Long, Long> place = vmManager.findClusterAndHostIdForVm(vm, false);
        Long expectedHost = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
        if (place == null || place.first() == null || place.second() == null || !Objects.equals(place.second(), expectedHost)) {
            throw fail("current or last host unavailable or inconsistent");
        }
        HostVO host = hostDao.findById(place.second());
        if (host == null || host.getRemoved() != null || host.getHypervisorType() != HypervisorType.External
                || !Objects.equals(host.getClusterId(), place.first())) throw fail("host or cluster mapping mismatch");
        Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
        Map<String, String> hd = access == null ? null : access.get("host");
        if (hd == null || !i.cluster.equals(hd.get("proxmox_cluster"))) throw fail("provider cluster mapping mismatch");
        String node = exact(hd.get("node"));
        String bridge = exact(hd.get("network_bridge"));
        String nativeLifecycle = hd.get("native_adoption_lifecycle");
        if (nativeLifecycle != null && !nativeLifecycle.isEmpty() && !"false".equals(nativeLifecycle)) {
            throw fail("native lifecycle must be disabled before handover snapshot");
        }
        // Never remove adoption metadata or bypass the legacy authorization callback.
        final Answer answer;
        try { answer = extensionsManager.getInstanceConfig(vm); }
        catch (RuntimeException e) { throw fail("provider snapshot unavailable or legacy callback fenced"); }
        if (answer == null || !answer.getResult()) throw fail("provider snapshot unavailable or legacy callback fenced");
        final ExternalVmConfiguration snapshot;
        try {
            snapshot = ExternalVmConfiguration.parse(answer.getDetails());
            snapshot.requireIdentity(i.cluster, Integer.toString(i.vmid));
        } catch (RuntimeException e) { throw fail("invalid or mismatched provider snapshot"); }
        JsonNode name = manifest.get("name");
        if (name == null || !name.isTextual() || !exact(name.textValue()).equals(snapshot.name) || !node.equals(snapshot.node)) {
            throw fail("provider name or current node mismatch");
        }
        String power = vm.getState() == VirtualMachine.State.Running ? "running" : "stopped";
        if (!power.equals(snapshot.power)) throw fail("provider and instance power differ");
        // Managed guests may have scaled since adoption. Compare current resources only.
        for (String key : new String[]{"cpuNumber", "cpuSpeed", "memory"}) detail(id, key);
        ServiceOfferingVO offering = offeringDao.findByIdIncludingRemoved(id, vm.getServiceOfferingId());
        if (offering == null || offering.getCpu() == null || offering.getRamSize() == null
                || offering.getCpu() <= 0 || offering.getRamSize() <= 0
                || offering.getCpu() != snapshot.cpus || offering.getRamSize() != snapshot.memoryMb) {
            throw fail("current instance CPU or memory differs from provider");
        }
        verifyNics(vm, snapshot, bridge);
        Pair<Long, Long> after = vmManager.findClusterAndHostIdForVm(vm, false);
        if (after == null || !Objects.equals(place.first(), after.first()) || !Objects.equals(place.second(), after.second())) {
            throw fail("instance placement changed during snapshot");
        }
    }

    private void verifyOwner(VMInstanceVO vm, JsonNode manifest) {
        AccountVO account = accountDao.findById(vm.getAccountId());
        DomainVO domain = domainDao.findById(vm.getDomainId());
        if (account == null || account.getRemoved() != null || domain == null || domain.getRemoved() != null
                || account.getDomainId() != vm.getDomainId() || account.getType() == null || account.getType() == Account.Type.PROJECT) {
            throw fail("instance ownership unavailable or project-owned");
        }
        JsonNode owner = manifest.get("ownership");
        if (owner == null) {
            // Legacy policy records no domain UUID in the manifest; do not invent one.
            if (!"admin".equals(account.getAccountName())) throw fail("legacy manifest requires admin account");
            return;
        }
        if (!owner.isObject() || owner.size() != 3 || !owner.has("account") || !owner.has("domain_id") || !owner.has("project_id")
                || !owner.get("account").isTextual() || !owner.get("domain_id").isTextual() || !owner.get("project_id").isNull()) {
            throw fail("unsupported manifest ownership policy");
        }
        String accountName = exact(owner.get("account").textValue());
        String domainUuid = exact(owner.get("domain_id").textValue());
        try { AdoptionRegistry.canonicalUuid(domainUuid); } catch (RuntimeException e) { throw fail("invalid manifest domain UUID"); }
        if (!accountName.equals(account.getAccountName()) || !domainUuid.equals(domain.getUuid())) throw fail("manifest owner differs from current instance");
    }

    private void verifyNics(VMInstanceVO vm, ExternalVmConfiguration snapshot, String bridge) {
        List<NicVO> rows = nicDao.listByVmId(vm.getId());
        if (rows == null || snapshot.nics.isEmpty() || snapshot.nics.size() > 32 || rows.size() > 64) throw fail("unsupported NIC count");
        Map<String, ExternalVmConfiguration.Nic> byMac = new HashMap<>();
        for (ExternalVmConfiguration.Nic n : snapshot.nics) byMac.put(n.mac, n);
        Set<String> macs = new HashSet<>();
        Set<Integer> devices = new HashSet<>();
        int count = 0;
        for (NicVO nic : rows) {
            if (nic == null) throw fail("invalid native NIC");
            if (nic.getRemoved() != null) continue;
            if (nic.getInstanceId() != vm.getId() || nic.getVmType() != VirtualMachine.Type.User) {
                throw fail("stale NIC instance binding");
            }
            count++;
            String mac = exact(nic.getMacAddress()).toLowerCase(Locale.ROOT);
            if (!mac.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}") || !macs.add(mac) || !devices.add(nic.getDeviceId())) throw fail("ambiguous native NIC identity");
            ExternalVmConfiguration.Nic p = byMac.get(mac);
            NetworkVO network = networkDao.findById(nic.getNetworkId());
            if (p == null || network == null || network.getRemoved() != null || network.getId() != nic.getNetworkId()
                    || network.getDataCenterId() != vm.getDataCenterId() || network.getTrafficType() != TrafficType.Guest
                    || nic.getBroadcastUri() == null
                    || network.getBroadcastUri() == null || !nic.getBroadcastUri().equals(network.getBroadcastUri())) {
                throw fail("native and provider NIC topology differs");
            }
            AccountVO owner = accountDao.findById(vm.getAccountId());
            if (accountService == null || owner == null || owner.getRemoved() != null) throw fail("unsupported: network access service unavailable");
            try { accountService.checkAccess(owner, AccessType.UseEntry, false, network); }
            catch (RuntimeException e) { throw fail("unsupported or denied native network access"); }
            String uri = nic.getBroadcastUri().toString();
            if (!uri.matches("vlan://[1-9][0-9]{0,3}")) throw fail("only tagged VLAN NICs are supported");
            int vlan = Integer.parseInt(uri.substring(7));
            if (vlan > 4094 || !Objects.equals(p.vlan, vlan) || !bridge.equals(p.bridge)) throw fail("NIC bridge or VLAN mismatch");
            // MAC joins sparse provider device IDs to current native NICs. Device
            // numbering need not remain the same after managed NIC operations.
        }
        if (count != snapshot.nics.size()) throw fail("native and provider NIC counts differ");
    }
}
