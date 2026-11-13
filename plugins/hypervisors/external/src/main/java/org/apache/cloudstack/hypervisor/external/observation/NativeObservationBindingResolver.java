// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.observation;


import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.hypervisor.external.refresh.ExternalVmConfiguration;
import com.cloud.agent.api.Answer;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
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

/** Resolves only ordinary deployed guests. Never grants lifecycle or adoption authority. */
public class NativeObservationBindingResolver implements ObservationBindingResolver {
    @Inject VMInstanceDao vmDao;
    @Inject VMInstanceDetailsDao detailsDao;
    @Inject NicDao nicDao;
    @Inject HostDao hostDao;
    @Inject VirtualMachineManager vmManager;
    @Inject ExtensionsManager extensionsManager;

    private static String exact(String s) {
        if (s == null || s.isEmpty() || !s.equals(s.trim()) || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return s;
    }
    private String detail(long id, String key) {
        List<VMInstanceDetailVO> rows = detailsDao.findDetails(id, key);
        if (rows == null || rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalArgumentException();
        return exact(rows.get(0).getValue());
    }
    private String aliases(long id, String key) {
        String a = detail(id, key), b = detail(id, "External:" + key);
        if (a != null && b != null && !a.equals(b)) throw new IllegalArgumentException();
        return a != null ? a : b;
    }
    boolean adopted(long id, String uuid, String name, String cluster, int vmid) throws Exception {
        for (String p : new String[]{"", "External:"}) {
            for (String k : new String[]{"adopt_claim_id", "adopt_claim_generation", "adopt_manifest_sha256", "adopt_manifest_json"}) {
                if (detail(id, p + k) != null) return true;
            }
            String flag = detail(id, p + "adopt_existing");
            if (flag != null && !"false".equals(flag)) return true;
        }
        TransactionLegacy t = TransactionLegacy.open("observationAdoptionCheck");
        try (PreparedStatement s = t.getConnection().prepareStatement("SELECT 1 FROM external_adoption_claims WHERE BINARY cloudstack_vm_ref=BINARY ? OR BINARY cloudstack_instance_name=BINARY ? OR (BINARY proxmox_cluster=BINARY ? AND proxmox_vmid=?) LIMIT 1")) {
            s.setString(1, uuid); s.setString(2, name); s.setString(3, cluster); s.setInt(4, vmid);
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        } finally { t.close(); }
    }
    private Pair<Long, Long> placement(VMInstanceVO vm) {
        return vmManager.findClusterAndHostIdForVm(vm, false);
    }
    /** Source uniqueness is checked by VMID and native cluster, never by global MAC. */
    Long uniqueVm(long cluster, int vmid) throws Exception {
        Long found = null;
        TransactionLegacy t = TransactionLegacy.open("observationSourceLookup");
        try (PreparedStatement s = t.getConnection().prepareStatement("SELECT DISTINCT vm_id FROM vm_instance_details WHERE name IN ('proxmox_vmid','External:proxmox_vmid') AND BINARY value=BINARY ?")) {
            s.setString(1, Integer.toString(vmid));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    VMInstanceVO vm = vmDao.findById(r.getLong(1));
                    if (vm == null || vm.getRemoved() != null) continue;
                    Pair<Long, Long> p = placement(vm);
                    // Unknown placement may conceal a duplicate in this cluster.
                    if (p == null || p.first() == null) throw new IllegalArgumentException();
                    if (p.first().longValue() != cluster) continue;
                    if (found != null) throw new IllegalArgumentException();
                    found = vm.getId();
                }
            }
        } finally { t.close(); }
        return found;
    }
    @Override public Binding source(long clusterId, long hostId, int vmid, String mac) {
        try {
            Long id = uniqueVm(clusterId, vmid);
            if (id == null) return null;
            Binding b = current(id, mac);
            return b != null && b.clusterId == clusterId && b.hostId == hostId && b.vmid == vmid ? b : null;
        } catch (Exception e) { return null; }
    }
    String networkUuid(long id, String uri) throws Exception {
        TransactionLegacy t = TransactionLegacy.open("observationNetworkLookup");
        try (PreparedStatement s = t.getConnection().prepareStatement("SELECT uuid,broadcast_uri FROM networks WHERE id=? AND removed IS NULL")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) { if (!r.next() || !uri.equals(r.getString(2))) throw new IllegalArgumentException(); return exact(r.getString(1)); }
        } finally { t.close(); }
    }

    @Override public Binding current(long id, String mac) {
        try {
            mac = ObservationValidation.mac(mac);
            VMInstanceVO vm = vmDao.findById(id);
            if (vm == null || vm.getRemoved() != null || vm.getType() != VirtualMachine.Type.User || vm.getHypervisorType() != HypervisorType.External
                    || (vm.getState() != VirtualMachine.State.Running && vm.getState() != VirtualMachine.State.Stopped)) return null;
            String uuid = exact(vm.getUuid()), instanceName = exact(vm.getInstanceName());

            String vmidText = exact(aliases(id, "proxmox_vmid"));
            if (!vmidText.matches("[1-9][0-9]*")) return null;
            int vmid = Integer.parseInt(vmidText);
            Pair<Long, Long> p = placement(vm);
            if (p == null || p.first() == null || p.second() == null) return null;
            HostVO host = hostDao.findById(p.second());
            if (host == null || host.getRemoved() != null || host.getHypervisorType() != HypervisorType.External || !Objects.equals(host.getClusterId(), p.first())) return null;
            Long unique = uniqueVm(p.first(), vmid);
            if (unique == null || unique != id) return null;
            Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
            Map<String, String> hd = access == null ? Collections.emptyMap() : access.getOrDefault("host", Collections.emptyMap());
            String cluster = exact(hd.get("proxmox_cluster")), node = exact(hd.get("node")), bridge = exact(hd.get("network_bridge"));
            if (adopted(id, uuid, instanceName, cluster, vmid)) return null;
            String vc = aliases(id, "proxmox_cluster");
            if (vc != null && !cluster.equals(vc)) return null;
            NicVO selected = null;
            for (NicVO n : nicDao.listByVmId(id)) {
                if (n.getRemoved() == null && mac.equals(ObservationValidation.mac(n.getMacAddress()))) {
                    if (selected != null) return null;
                    selected = n;
                }
            }
            if (selected == null || selected.getBroadcastUri() == null) return null;
            String uri = selected.getBroadcastUri().toString();
            if (!uri.matches("vlan://([1-9][0-9]{0,3})")) return null;
            int vlan = Integer.parseInt(uri.substring(7));
            if (vlan < 1 || vlan > 4094) return null;
            String nicUuid = exact(selected.getUuid()), network = networkUuid(selected.getNetworkId(), uri);
            Answer answer = extensionsManager.getInstanceConfig(vm);
            if (answer == null || !answer.getResult()) return null;
            ExternalVmConfiguration snap = ExternalVmConfiguration.parse(answer.getDetails());
            snap.requireIdentity(cluster, vmidText);
            if (!node.equals(snap.node) || !instanceName.equals(snap.name)) return null;
            ExternalVmConfiguration.Nic providerNic = null;
            for (ExternalVmConfiguration.Nic n : snap.nics) if (mac.equals(n.mac)) { if (providerNic != null) return null; providerNic = n; }
            if (providerNic == null || !ObservationProvenance.providerMatches(cluster, vmidText, node, instanceName, mac, bridge, vlan,
                    snap.cluster, snap.vmid, snap.node, snap.name, providerNic.mac, providerNic.bridge, providerNic.vlan)) return null;
            String fp = ObservationProvenance.fingerprint("external-observation-v1", Long.toString(id), uuid, Long.toString(vm.getUpdated()),
                    Long.toString(p.first()), Long.toString(p.second()), exact(host.getUuid()), cluster, vmidText, node,
                    instanceName, nicUuid, Long.toString(selected.getNetworkId()), network, mac, providerNic.deviceId, bridge, Integer.toString(vlan));
            return new Binding(id, p.first(), p.second(), vmid, mac, fp);
        } catch (Exception e) { return null; }
    }
}
