// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.reconciliation;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.hypervisor.external.refresh.ExternalVmRefreshManager;
import org.apache.cloudstack.hypervisor.external.adoption.AdoptionRegistry;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.host.HostVO;
import com.cloud.user.AccountManager;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

/** Default-off, bounded coordinator. All mutations remain inside the one-shot refresh service. */
public class ExternalRefreshWorker extends ManagerBase implements Configurable {
    private static final Logger LOG = LogManager.getLogger(ExternalRefreshWorker.class);
    public static final ConfigKey<Boolean> ENABLED = new ConfigKey<>("Advanced", Boolean.class,
            "external.refresh.periodic.enabled", "false", "Enable periodic External VM metadata refresh", true);
    public static final ConfigKey<Integer> INTERVAL = new ConfigKey<>("Advanced", Integer.class,
            "external.refresh.periodic.interval.seconds", "300", "Periodic refresh interval in seconds (30 to 86400)", true);
    public static final ConfigKey<Boolean> CLUSTER_ENABLED = new ConfigKey<>("Advanced", Boolean.class,
            "external.refresh.periodic.cluster.enabled", "false", "Explicitly opt a cluster into periodic External VM refresh", true, ConfigKey.Scope.Cluster);
    static final int BATCH = 100;
    static final long MAX_NANOS = TimeUnit.SECONDS.toNanos(60);
    @Inject AccountManager accountManager;
    @Inject VMInstanceDao vmDao;
    @Inject VMInstanceDetailsDao detailsDao;
    @Inject HostDao hostDao;
    @Inject ExtensionsManager extensions;
    @Inject ExtensionHelper extensionHelper;
    @Inject ExternalVmRefreshManager refresh;
    private ScheduledExecutorService executor;
    private long cursor;
    private long lastRun;
    /** A pass over every instance is under way: the next tick continues it rather than waiting a whole interval. */
    private boolean midPass;

    @Override public String getConfigComponentName() { return getClass().getSimpleName(); }
    @Override public ConfigKey<?>[] getConfigKeys() { return new ConfigKey<?>[]{ENABLED, INTERVAL, CLUSTER_ENABLED}; }
    @Override public boolean start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "external-refresh-worker"); t.setDaemon(true); return t;
        });
        ManagedContextRunnable task = new ManagedContextRunnable() {
            @Override protected void runInContext() { tick(); }
        };
        executor.scheduleWithFixedDelay(() -> {
            try { task.run(); } catch (Exception e) { LOG.warn("External periodic refresh: managed context failure"); }
        }, 30, 30, TimeUnit.SECONDS);
        return true;
    }
    @Override public boolean stop() {
        if (executor != null) executor.shutdown();
        return true;
    }
    void tick() {
        try {
            if (!enabled()) return;
            Integer interval = INTERVAL.value();
            if (interval == null || interval < 30 || interval > 86400) return;
            long now = System.nanoTime();
            if (!midPass) {
                if (lastRun != 0 && now - lastRun < TimeUnit.SECONDS.toNanos(interval)) return;
                lastRun = now;
            }
            midPass = runOnce();
        } catch (Exception e) { LOG.warn("External periodic refresh: scheduler failure"); }
    }
    boolean enabled() { return Boolean.TRUE.equals(ENABLED.value()); }
    boolean clusterEnabled(long id) {
        // strictScope avoids a global fallback enabling all clusters.
        return Boolean.TRUE.equals(CLUSTER_ENABLED.valueInScope(ConfigKey.Scope.Cluster, id, true));
    }
    interface Lock extends AutoCloseable { boolean acquire(); void close(); }
    Lock lock() {
        GlobalLock l = GlobalLock.getInternLock("external.periodic.refresh");
        return new Lock() {
            boolean held;
            public boolean acquire() { held = l.lock(1); return held; }
            public void close() { try { if (held) l.unlock(); } finally { l.releaseRef(); } }
        };
    }
    void registerContext() { CallContext.register(accountManager.getSystemUser(), accountManager.getSystemAccount()); }
    void unregisterContext() { CallContext.unregister(); }
    long now() { return System.nanoTime(); }

    List<VMInstanceVO> page(long after) {
        SearchCriteria<VMInstanceVO> sc = vmDao.createSearchCriteria();
        sc.addAnd("id", SearchCriteria.Op.GT, after);
        sc.addAnd("type", SearchCriteria.Op.EQ, VirtualMachine.Type.User);
        sc.addAnd("hypervisorType", SearchCriteria.Op.EQ, HypervisorType.External);
        sc.addAnd("state", SearchCriteria.Op.IN, VirtualMachine.State.Running, VirtualMachine.State.Stopped);
        return vmDao.search(sc, new Filter(VMInstanceVO.class, "id", true, 0L, (long)BATCH));
    }
    private String detail(long id, String name) {
        List<VMInstanceDetailVO> rows = detailsDao.findDetails(id, name);
        if (rows == null || rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalArgumentException();
        String v = rows.get(0).getValue();
        if (v == null || v.isEmpty() || !v.equals(v.trim()) || v.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return v;
    }
    private String aliases(long id, String name) {
        String result = null;
        for (String prefix : new String[]{"", "External:", "external."}) {
            String value = detail(id, prefix + name);
            if (value != null) {
                if (result != null && !result.equals(value)) throw new IllegalArgumentException();
                result = value;
            }
        }
        return result;
    }
    boolean hasAdoptionMarker(long id) {
        for (String p : new String[]{"", "External:", "external."}) {
            for (String key : new String[]{"adopt_claim_id", "adopt_claim_generation", "adopt_manifest_sha256", "adopt_manifest_json"}) {
                if (detail(id, p + key) != null) return true;
            }
            String flag = detail(id, p + "adopt_existing");
            if (flag != null && !flag.equals("false")) return true;
        }
        return false;
    }
    boolean hasClaim(String uuid, String instance, String cluster, String vmid) throws Exception {
        TransactionLegacy t = TransactionLegacy.open("externalPeriodicClaimCheck");
        try (PreparedStatement s = t.getConnection().prepareStatement("SELECT 1 FROM external_adoption_claims WHERE BINARY cloudstack_vm_ref=BINARY ? OR BINARY cloudstack_instance_name=BINARY ? OR (BINARY proxmox_cluster=BINARY ? AND proxmox_vmid=?) LIMIT 1")) {
            s.setString(1, uuid); s.setString(2, instance); s.setString(3, cluster); s.setString(4, vmid);
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        } finally { t.close(); }
    }
    List<Long> candidateIds(String vmid) throws Exception {
        List<Long> result = new ArrayList<>();
        TransactionLegacy t = TransactionLegacy.open("externalPeriodicIdentityCheck");
        // CAST is only a broad ambiguity search, never an identity repair. Every
        // returned raw alias is validated strictly below, including 0101/101x.
        try (PreparedStatement s = t.getConnection().prepareStatement(
                "SELECT DISTINCT v.id FROM vm_instance v JOIN vm_instance_details d ON d.vm_id=v.id " +
                "WHERE v.removed IS NULL AND v.hypervisor_type='External' " +
                "AND d.name IN ('proxmox_vmid','External:proxmox_vmid','external.proxmox_vmid') " +
                "AND CAST(d.value AS UNSIGNED)=? ORDER BY v.id LIMIT 101")) {
            s.setInt(1, Integer.parseInt(vmid));
            try (ResultSet r = s.executeQuery()) { while (r.next()) result.add(r.getLong(1)); }
            return result;
        } finally { t.close(); }
    }
    boolean unique(long vmId, String cluster, String vmid) throws Exception {
        List<Long> matches = candidateIds(vmid);
        if (matches == null || matches.size() >= 101) return false;
        boolean found = false;
        Set<Long> seen = new HashSet<>();
        for (Long candidate : matches) {
            if (candidate == null || !seen.add(candidate)) return false;
            VMInstanceVO other = vmDao.findById(candidate);
            if (other == null || other.getRemoved() != null || other.getHypervisorType() != HypervisorType.External) return false;
            String raw = aliases(other.getId(), "proxmox_vmid");
            if (!vmid.equals(raw)) return false;
            Long hostId = other.getHostId() != null ? other.getHostId() : other.getLastHostId();
            HostVO h = hostId == null ? null : hostDao.findById(hostId);
            if (h == null || h.getRemoved() != null || h.getHypervisorType() != HypervisorType.External || h.getClusterId() == null) return false;
            Map<String, Map<String, String>> access = extensions.getExternalAccessDetails(h, null);
            Map<String, String> hd = access == null ? null : access.get("host");
            String providerCluster = AdoptionRegistry.canonicalCluster(hd == null ? null : hd.get("proxmox_cluster"));
            String declared = aliases(other.getId(), "proxmox_cluster");
            if (declared != null && !providerCluster.equals(declared)) return false;
            if (!providerCluster.equals(cluster)) continue;
            if (other.getId() != vmId) return false;
            found = true;
        }
        return found;
    }
    boolean eligible(VMInstanceVO candidate) throws Exception {
        VMInstanceVO vm = vmDao.findById(candidate.getId());
        if (vm == null || vm.getRemoved() != null || vm.getType() != VirtualMachine.Type.User || vm.getHypervisorType() != HypervisorType.External
                || (vm.getState() != VirtualMachine.State.Running && vm.getState() != VirtualMachine.State.Stopped)) return false;
        if (!UUID.fromString(vm.getUuid()).toString().equals(vm.getUuid())) return false;
        if (vm.getInstanceName() == null || vm.getInstanceName().isEmpty()) return false;
        Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
        HostVO host = hostId == null ? null : hostDao.findById(hostId);
        if (host == null || host.getRemoved() != null || host.getHypervisorType() != HypervisorType.External || host.getClusterId() == null || !clusterEnabled(host.getClusterId())) return false;
        Extension ext = extensionHelper.getExtensionForCluster(host.getClusterId());
        if (ext == null || !extensionHelper.isInstanceGetConfigSupported(ext.getId())) return false;
        String vmid = aliases(vm.getId(), "proxmox_vmid");
        if (vmid == null || !vmid.matches("[1-9][0-9]*") || Integer.parseInt(vmid) <= 0) return false;
        String cluster = extensions.getExternalAccessDetails(host, null).get("host").get("proxmox_cluster");
        AdoptionRegistry.canonicalCluster(cluster);
        String ownCluster = aliases(vm.getId(), "proxmox_cluster");
        if (ownCluster != null && !cluster.equals(ownCluster)) return false;
        // An adopted instance must have a claim and any other instance must not. The refresh
        // itself then requires that claim to be managed with no operation in progress.
        return unique(vm.getId(), cluster, vmid)
                && hasAdoptionMarker(vm.getId()) == hasClaim(vm.getUuid(), vm.getInstanceName(), cluster, vmid);
    }
    void apply(VMInstanceVO vm) { refresh.refresh(vm.getId(), false); }
    /** Why a refresh failed, short and on one line. The refresh's own messages name no secret. */
    static String reason(Exception e) {
        String text = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        text = text.replaceAll("\\p{Cntrl}", " ");
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
    /** Refreshes up to a batch of instances. True when the pass over all of them is not finished. */
    boolean runOnce() {
        if (!enabled()) return false;
        try (Lock guard = lock()) {
            if (!guard.acquire() || !enabled()) return false;
            registerContext();
            try {
                long deadline = now() + MAX_NANOS;
                int ok = 0, skipped = 0, failed = 0, examined = 0;
                List<VMInstanceVO> rows = page(cursor);
                if (rows.isEmpty()) { cursor = 0; return false; }
                for (VMInstanceVO vm : rows) {
                    if (now() - deadline >= 0 || !enabled() || Thread.currentThread().isInterrupted()) break;
                    cursor = vm.getId(); examined++;
                    try {
                        if (!eligible(vm)) { skipped++; continue; }
                        apply(vm); ok++;
                    } catch (Exception e) {
                        failed++;
                        String uuid;
                        try { uuid = UUID.fromString(vm.getUuid()).toString(); } catch (Exception ignored) { uuid = "invalid"; }
                        LOG.warn("External periodic refresh vm={} category=failed reason={}", uuid, reason(e));
                    }
                }
                if (rows.size() < BATCH && examined == rows.size()) cursor = 0;
                LOG.info("External periodic refresh examined={} succeeded={} skipped={} failed={} more={}", examined, ok, skipped, failed, cursor != 0);
                return cursor != 0;
            } finally { unregisterContext(); }
        } catch (Exception e) { LOG.warn("External periodic refresh category=run_failed reason={}", reason(e)); return false; }
    }
}
