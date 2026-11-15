// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.refresh;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.cloudstack.hypervisor.external.observation.ExternalObservationService;
import org.apache.cloudstack.hypervisor.external.observation.ObservationBindingResolver;
import org.apache.cloudstack.hypervisor.external.observation.ObservationValidation;
import com.cloud.network.Network;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.NicDao;

/** Bounded existing-NIC metadata update; never changes network membership or the provider. */
final class ExternalNativeNicRefresh {
    static final class Evidence {
        final ObservationBindingResolver.Binding binding;
        final String arp;
        final long validUntilNanos;
        Evidence(ObservationBindingResolver.Binding binding, String arp) { this(binding, arp, Long.MAX_VALUE); }
        Evidence(ObservationBindingResolver.Binding binding, String arp, long validUntilNanos) {
            this.binding = binding; this.arp = arp; this.validUntilNanos = validUntilNanos;
        }
    }
    private final NicDao nicDao;
    private final NetworkDao networkDao;
    private final ObservationBindingResolver resolver;
    private final ExternalObservationService observations;

    ExternalNativeNicRefresh(NicDao nicDao, NetworkDao networkDao, ObservationBindingResolver resolver, ExternalObservationService observations) {
        this.nicDao = nicDao; this.networkDao = networkDao; this.resolver = resolver; this.observations = observations;
    }

    static List<String> usable(ExternalVmConfiguration snapshot, ExternalVmConfiguration.Nic nic) {
        List<String> ips = new ArrayList<>();
        if (snapshot.agentAvailable) for (String ip : nic.ipAddresses) if (ObservationValidation.usableIp(ip)) ips.add(ip);
        return ips;
    }

    /** Called before the enclosing JDBC transaction. No observation lookup occurs inside it. */
    Map<String, Evidence> collect(long vmId, ExternalVmConfiguration snapshot) {
        Map<String, Evidence> result = new HashMap<>();
        if (resolver == null || observations == null) return result;
        for (ExternalVmConfiguration.Nic nic : snapshot.nics) {
            List<String> ips = usable(snapshot, nic);
            if (ips.size() > 1) continue; // ambiguity is not absence
            try {
                if (ips.size() == 1) {
                    ObservationBindingResolver.Binding b = resolver.current(vmId, nic.mac);
                    if (b != null) result.put(nic.mac, new Evidence(b, null));
                } else {
                    ExternalObservationService.Candidate c = observations.fallbackCandidate(vmId, nic.mac, null);
                    if (c != null) result.put(nic.mac, new Evidence(c.binding, c.ip, c.validUntilNanos));
                    else {
                        ObservationBindingResolver.Binding b = resolver.current(vmId, nic.mac);
                        if (b != null) result.put(nic.mac, new Evidence(b, null));
                    }
                }
            } catch (SQLException | RuntimeException e) {
                // Missing observation storage or malformed bindings never clear an address.
            }
        }
        return result;
    }

    private static long ip(String s) {
        if (s == null || !s.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) throw new IllegalArgumentException();
        long value = 0;
        for (String part : s.split("\\.")) { int n = Integer.parseInt(part); if (n > 255) throw new IllegalArgumentException(); value = (value << 8) | n; }
        return value;
    }
    private static long mask(int prefix) { return prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL; }
    private static boolean hostIn(long addr, long base, long mask) {
        if ((addr & mask) != (base & mask)) return false;
        long host = addr & (~mask & 0xffffffffL);
        return mask >= 0xfffffffeL || (host != 0 && host != (~mask & 0xffffffffL));
    }
    /** L2 networks can legitimately have neither CIDR nor NIC netmask. Validate all that exist. */
    static boolean subnetAllows(NetworkVO network, NicVO nic, String candidate) {
        try {
            long address = ip(candidate);
            String cidr = network.getCidr();
            String netmask = nic.getIPv4Netmask();
            Long base = null, cidrMask = null;
            if (cidr != null) {
                String[] parts = cidr.split("/", -1);
                if (parts.length != 2 || !parts[1].matches("0|[1-9][0-9]?")) return false;
                int prefix = Integer.parseInt(parts[1]);
                if (prefix > 32) return false;
                base = ip(parts[0]); cidrMask = mask(prefix);
                if (!hostIn(address, base, cidrMask)) return false;
            }
            if (network.getNetworkCidr() != null) {
                String[] effective = network.getNetworkCidr().split("/", -1);
                if (effective.length != 2 || !effective[1].matches("0|[1-9][0-9]?")) return false;
                int prefix = Integer.parseInt(effective[1]);
                if (prefix > 32 || !hostIn(address, ip(effective[0]), mask(prefix))) return false;
            }
            if (network.getGateway() != null && ip(network.getGateway()) == address) return false;
            if (nic.getIPv4Gateway() != null && ip(nic.getIPv4Gateway()) == address) return false;
            if (netmask != null) {
                long m = ip(netmask);
                int prefix = Long.bitCount(m);
                if (m != mask(prefix) || (cidrMask != null && m != cidrMask)) return false;
                String anchor = nic.getIPv4Gateway() != null ? nic.getIPv4Gateway() : nic.getIPv4Address();
                if (anchor == null && base == null) return false;
                if (!hostIn(address, anchor == null ? base : ip(anchor), m)) return false;
                if (base != null && anchor != null && (ip(anchor) & m) != (base & m)) return false;
            }
            return true;
        } catch (RuntimeException e) { return false; }
    }

    /** A locking current read avoids a stale MySQL repeatable-read snapshot and finds all matches. */
    boolean assignedElsewhere(long networkId, long nicId, String address) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        try (PreparedStatement ps = txn.getConnection().prepareStatement(
                "SELECT id FROM nics WHERE network_id=? AND ip4_address=? AND removed IS NULL FOR UPDATE")) {
            ps.setLong(1, networkId); ps.setString(2, address);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) if (rs.getLong(1) != nicId) return true; }
            try (PreparedStatement secondary = txn.getConnection().prepareStatement(
                    "SELECT nicId FROM nic_secondary_ips WHERE network_id=? AND ip4_address=? FOR UPDATE")) {
                secondary.setLong(1, networkId); secondary.setString(2, address);
                try (ResultSet rs = secondary.executeQuery()) { if (rs.next()) return true; }
            }
            return false;
        } catch (SQLException e) { throw new CloudRuntimeException("Cannot check existing network address assignments", e); }
    }

    boolean observationStillValid(ObservationBindingResolver.Binding b, String address) {
        try (PreparedStatement ps = TransactionLegacy.currentTxn().getConnection().prepareStatement(
                "SELECT 1 FROM external_ip_observation WHERE cluster_id=? AND vmid=? AND mac=? AND host_id=? AND binding_fingerprint=? AND ip=? AND sightings>=2 AND previous_seen_at>=DATE_SUB(SYSDATE(6), INTERVAL 24 HOUR) AND seen_at>=DATE_SUB(SYSDATE(6), INTERVAL 24 HOUR) FOR UPDATE")) {
            ps.setLong(1, b.clusterId); ps.setInt(2, b.vmid); ps.setString(3, b.mac); ps.setLong(4, b.hostId);
            ps.setString(5, b.fingerprint); ps.setString(6, address);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw new CloudRuntimeException("Cannot revalidate observation", e); }
    }

    List<ExternalVmRefreshResponse.IpChange> apply(VMInstanceVO vm, ExternalVmConfiguration snapshot,
            Map<String, Evidence> evidence, String hostBridge, boolean dryRun, List<String> unresolved) {
        List<ExternalVmRefreshResponse.IpChange> changes = new ArrayList<>();
        Map<String, ExternalVmConfiguration.Nic> source = new HashMap<>();
        Map<String, Integer> agentUse = new HashMap<>();
        for (ExternalVmConfiguration.Nic n : snapshot.nics) {
            source.put(n.mac, n);
            for (String address : usable(snapshot, n)) agentUse.put(address, agentUse.getOrDefault(address, 0) + 1);
        }
        List<NicVO> nics = new ArrayList<>(nicDao.listByVmId(vm.getId()));
        nics.sort(Comparator.comparingLong(NicVO::getNetworkId).thenComparingLong(NicVO::getId));
        Map<String, Integer> nativeMacs = new HashMap<>();
        Map<String, Integer> proposedUse = new HashMap<>();
        for (NicVO n : nics) {
            try {
                String mac = ObservationValidation.mac(n.getMacAddress());
                nativeMacs.put(mac, nativeMacs.getOrDefault(mac, 0) + 1);
                ExternalVmConfiguration.Nic provider = source.get(mac);
                Evidence e = evidence.get(mac);
                if (provider != null && e != null) {
                    List<String> ips = usable(snapshot, provider);
                    String address = ips.size() == 1 ? ips.get(0) : (ips.isEmpty() ? e.arp : null);
                    if (address != null) {
                        String key = n.getNetworkId() + "/" + address;
                        proposedUse.put(key, proposedUse.getOrDefault(key, 0) + 1);
                    }
                }
            } catch (IllegalArgumentException ignored) { }
        }
        for (NicVO listed : nics) {
            String mac;
            try { mac = ObservationValidation.mac(listed.getMacAddress()); } catch (IllegalArgumentException e) { unresolved.add("invalid native NIC MAC"); continue; }
            ExternalVmConfiguration.Nic provider = source.get(mac);
            Evidence e = evidence.get(mac);
            if (provider == null || e == null || nativeMacs.get(mac) != 1) { unresolved.add("NIC binding unavailable or ambiguous: " + mac); continue; }
            List<String> ips = usable(snapshot, provider);
            if (ips.size() > 1) { unresolved.add("multiple guest IPv4 addresses for NIC " + mac); continue; }
            String address = ips.size() == 1 ? ips.get(0) : e.arp;
            if (address == null || !ObservationValidation.usableIp(address)) { unresolved.add("no usable IPv4 for NIC " + mac); continue; }
            if ((ips.size() == 1 && agentUse.get(address) != 1) || (ips.isEmpty() && agentUse.containsKey(address))
                    || proposedUse.getOrDefault(listed.getNetworkId() + "/" + address, 0) != 1) {
                unresolved.add("IPv4 appears on multiple NICs: " + mac); continue;
            }
            NetworkVO network = networkDao.lockRow(listed.getNetworkId(), true);
            NicVO nic = nicDao.lockRow(listed.getId(), true);
            String lockedMac = null;
            if (nic != null) {
                try { lockedMac = ObservationValidation.mac(nic.getMacAddress()); } catch (IllegalArgumentException ignored) { }
            }
            if (network == null || nic == null || network.getRemoved() != null || nic.getRemoved() != null
                    || nic.getInstanceId() != vm.getId() || nic.getNetworkId() != listed.getNetworkId()
                    || !mac.equals(lockedMac)
                    || network.getGuestType() != Network.GuestType.L2 || network.getTrafficType() != TrafficType.Guest
                    || network.getAccountId() != vm.getAccountId() || network.getDataCenterId() != vm.getDataCenterId()
                    || ObservationValidation.vlanUri(nic.getBroadcastUri(), network.getBroadcastUri()) == null || hostBridge == null
                    || !hostBridge.equals(provider.bridge) || provider.vlan == null
                    || !network.getBroadcastUri().toString().equals("vlan://" + provider.vlan)
                    || !subnetAllows(network, nic, address)) {
                unresolved.add("NIC network or subnet mismatch: " + mac); continue;
            }
            ObservationBindingResolver.Binding now = resolver.current(vm.getId(), mac);
            if (now == null || e.binding == null || e.binding.fingerprint == null || now.fingerprint == null
                    || !mac.equals(e.binding.mac) || !Objects.equals(e.binding.fingerprint, now.fingerprint)
                    || now.cloudStackVmId != vm.getId() || now.hostId != e.binding.hostId || now.clusterId != e.binding.clusterId
                    || now.vmid != e.binding.vmid || !mac.equals(now.mac)) {
                unresolved.add("NIC binding changed: " + mac); continue;
            }
            if (assignedElsewhere(network.getId(), nic.getId(), address)) { unresolved.add("IPv4 already assigned on network: " + mac); continue; }
            if (ips.isEmpty() && !observationStillValid(e.binding, address)) {
                unresolved.add("observation changed before NIC update: " + mac); continue;
            }
            if (ips.isEmpty() && e.validUntilNanos != Long.MAX_VALUE && System.nanoTime() - e.validUntilNanos >= 0) {
                unresolved.add("observation expired before NIC update: " + mac); continue;
            }
            String old = nic.getIPv4Address();
            if (Objects.equals(old, address)) continue;
            if (!dryRun) {
                nic.setIPv4Address(address);
                if (!nicDao.update(nic.getId(), nic)) throw new CloudRuntimeException("Failed to update NIC IPv4");
            }
            changes.add(new ExternalVmRefreshResponse.IpChange(nic.getUuid(), old, address, ips.size() == 1 ? "guestagent" : "observation", !dryRun));
        }
        return changes;
    }
}
