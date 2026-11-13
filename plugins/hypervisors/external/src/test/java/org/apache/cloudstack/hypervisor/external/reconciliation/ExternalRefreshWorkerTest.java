// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.reconciliation;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.junit.Test;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.hypervisor.external.refresh.ExternalVmRefreshManager;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.dao.VMInstanceDetailsDao;

public class ExternalRefreshWorkerTest {
    private static VMInstanceVO vm(long id) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(id);
        when(vm.getUuid()).thenReturn(String.format("00000000-0000-0000-0000-%012d", id));
        return vm;
    }
    private static class Harness extends ExternalRefreshWorker {
        boolean on = true;
        List<VMInstanceVO> rows = Collections.emptyList();
        AtomicBoolean shared = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger(), contexts = new AtomicInteger(), cleanups = new AtomicInteger();
        boolean throwFirst;
        CountDownLatch entered, resume;
        @Override boolean enabled() { return on; }
        @Override Lock lock() { return new Lock() {
            boolean held;
            public boolean acquire() { held = shared.compareAndSet(false, true); return held; }
            public void close() { if (held) shared.set(false); }
        }; }
        @Override List<VMInstanceVO> page(long cursor) { return rows; }
        @Override boolean eligible(VMInstanceVO vm) { return true; }
        @Override void registerContext() { contexts.incrementAndGet(); }
        @Override void unregisterContext() { cleanups.incrementAndGet(); }
        @Override void apply(VMInstanceVO vm) {
            int n = calls.incrementAndGet();
            if (entered != null) {
                entered.countDown();
                try { if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            if (throwFirst && n == 1) throw new IllegalStateException("sensitive provider response");
        }
    }
    @Test public void defaultsOffAndStrictClusterScope() {
        assertEquals(Boolean.FALSE, ExternalRefreshWorker.ENABLED.value());
        assertNotEquals(Boolean.TRUE, ExternalRefreshWorker.CLUSTER_ENABLED.valueInScope(ConfigKey.Scope.Cluster, 123L, true));
        Harness h = new Harness(); h.on = false; h.rows = List.of(vm(1)); h.runOnce();
        assertEquals(0, h.calls.get()); assertEquals(0, h.contexts.get());
    }
    @Test public void perVmFailureDoesNotStopBatchAndContextCloses() {
        Harness h = new Harness(); h.rows = Arrays.asList(vm(1), vm(2)); h.throwFirst = true;
        h.runOnce();
        assertEquals(2, h.calls.get()); assertEquals(1, h.contexts.get()); assertEquals(1, h.cleanups.get()); assertFalse(h.shared.get());
    }
    @Test public void overlappingManagementRunsAreExcluded() throws Exception {
        Harness first = new Harness(), second = new Harness();
        first.shared = second.shared; first.rows = second.rows = List.of(vm(1));
        first.entered = new CountDownLatch(1); first.resume = new CountDownLatch(1);
        Thread a = new Thread(first::runOnce); a.start();
        try {
            assertTrue(first.entered.await(5, TimeUnit.SECONDS));
            second.runOnce(); assertEquals(0, second.calls.get()); assertEquals(0, second.contexts.get());
        } finally { first.resume.countDown(); a.join(5000); }
        assertFalse(a.isAlive()); assertFalse(first.shared.get());
    }
    @Test public void systemCallContextIsUnregistered() throws Exception {
        ExternalRefreshWorker w = new ExternalRefreshWorker();
        java.lang.reflect.Field f = CallContext.class.getDeclaredField("s_currentContext"); f.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<CallContext> local = (ThreadLocal<CallContext>)f.get(null);
        CallContext previous = local.get();
        w.accountManager = mock(com.cloud.user.AccountManager.class);
        com.cloud.user.User user = mock(com.cloud.user.User.class);
        com.cloud.user.Account account = mock(com.cloud.user.Account.class);
        when(user.getId()).thenReturn(1L); when(account.getId()).thenReturn(1L);
        when(w.accountManager.getSystemUser()).thenReturn(user);
        when(w.accountManager.getSystemAccount()).thenReturn(account);
        w.registerContext(); assertNotNull(local.get()); assertNotSame(previous, local.get());
        try { assertEquals(1L, CallContext.current().getCallingAccountId()); }
        finally { w.unregisterContext(); }
        assertSame(previous, local.get());
    }
    private static class Eligibility extends ExternalRefreshWorker {
        boolean opted = true, unique = true, claim;
        @Override boolean clusterEnabled(long id) { return opted; }
        @Override boolean unique(long vm, String cluster, String id) { return unique; }
        @Override boolean hasClaim(String uuid, String name, String cluster, String id) { return claim; }
    }
    private static void detail(Eligibility e, String key, String value) {
        VMInstanceDetailVO d = mock(VMInstanceDetailVO.class); when(d.getValue()).thenReturn(value);
        when(e.detailsDao.findDetails(1L, key)).thenReturn(List.of(d));
    }
    private static Eligibility eligibleFixture() {
        Eligibility e = new Eligibility(); e.vmDao = mock(VMInstanceDao.class); e.hostDao = mock(HostDao.class);
        e.detailsDao = mock(VMInstanceDetailsDao.class); e.extensions = mock(ExtensionsManager.class); e.extensionHelper = mock(ExtensionHelper.class);
        VMInstanceVO vm = vm(1); HostVO host = mock(HostVO.class); Extension ext = mock(Extension.class);
        when(e.vmDao.findById(1L)).thenReturn(vm); when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped); when(vm.getHypervisorType()).thenReturn(HypervisorType.External);
        when(vm.getInstanceName()).thenReturn("i-1-1-VM"); when(vm.getHostId()).thenReturn(null); when(vm.getLastHostId()).thenReturn(5L);
        when(e.hostDao.findById(5L)).thenReturn(host); when(host.getHypervisorType()).thenReturn(HypervisorType.External); when(host.getClusterId()).thenReturn(9L);
        when(e.extensionHelper.getExtensionForCluster(9L)).thenReturn(ext); when(ext.getId()).thenReturn(2L);
        when(e.extensionHelper.isInstanceGetConfigSupported(2L)).thenReturn(true);
        when(e.extensions.getExternalAccessDetails(host, null)).thenReturn(Map.of("host", Map.of("proxmox_cluster", "cluster-a")));
        detail(e, "proxmox_vmid", "101");
        return e;
    }
    @Test public void explicitClusterAndCapabilityRequired() throws Exception {
        Eligibility e = eligibleFixture(); VMInstanceVO vm = e.vmDao.findById(1L);
        assertEquals(VirtualMachine.Type.User, vm.getType()); assertEquals(VirtualMachine.State.Stopped, vm.getState());
        assertEquals(HypervisorType.External, vm.getHypervisorType()); assertFalse(e.hasAdoptionMarker(1));
        assertNotNull(e.hostDao.findById(5L)); assertNotNull(e.extensionHelper.getExtensionForCluster(9L));
        assertTrue(e.extensionHelper.isInstanceGetConfigSupported(2L));
        assertEquals(1L, vm.getId()); assertEquals(Long.valueOf(5L), vm.getLastHostId());
        assertEquals("i-1-1-VM", vm.getInstanceName());
        assertEquals("101", e.detailsDao.findDetails(1L, "proxmox_vmid").get(0).getValue());
        clearInvocations(e.vmDao, e.hostDao, e.detailsDao, e.extensionHelper, e.extensions);
        boolean result = e.eligible(vm);
        assertTrue("host=" + mockingDetails(e.hostDao).getInvocations() + " detail=" + mockingDetails(e.detailsDao).getInvocations() + " extension=" + mockingDetails(e.extensionHelper).getInvocations(), result);
        e.opted = false; assertFalse(e.eligible(vm)); e.opted = true;
        when(e.extensionHelper.isInstanceGetConfigSupported(2L)).thenReturn(false); assertFalse(e.eligible(vm));
        when(e.extensionHelper.isInstanceGetConfigSupported(2L)).thenReturn(true); e.unique = false; assertFalse(e.eligible(vm));
    }
    @Test public void applyDelegatesToTheExistingService() {
        ExternalRefreshWorker w = new ExternalRefreshWorker();
        w.refresh = mock(ExternalVmRefreshManager.class);
        w.apply(vm(7));
        verify(w.refresh).refresh(7L, false);
    }
    @Test public void cleanupOnPageFailure() {
        Harness h = new Harness() { @Override List<VMInstanceVO> page(long cursor) { throw new IllegalStateException(); } };
        h.runOnce();
        assertEquals(1, h.contexts.get()); assertEquals(1, h.cleanups.get()); assertFalse(h.shared.get());
    }
    @Test public void duplicateOrUnknownPlacementIsNotUnique() throws Exception {
        class CandidateWorker extends ExternalRefreshWorker {
            List<Long> ids = List.of(1L);
            @Override List<Long> candidateIds(String vmid) { return ids; }
        }
        CandidateWorker w = new CandidateWorker();
        w.vmDao = mock(VMInstanceDao.class); w.detailsDao = mock(VMInstanceDetailsDao.class); w.hostDao = mock(HostDao.class);
        w.extensions = mock(ExtensionsManager.class);
        VMInstanceVO one = vm(1), two = vm(2); HostVO first = mock(HostVO.class), second = mock(HostVO.class);
        when(one.getHostId()).thenReturn(5L); when(two.getHostId()).thenReturn(6L);
        when(one.getHypervisorType()).thenReturn(HypervisorType.External); when(two.getHypervisorType()).thenReturn(HypervisorType.External);
        when(w.vmDao.findById(1L)).thenReturn(one); when(w.vmDao.findById(2L)).thenReturn(two);
        when(first.getClusterId()).thenReturn(9L); when(second.getClusterId()).thenReturn(10L);
        when(first.getHypervisorType()).thenReturn(HypervisorType.External); when(second.getHypervisorType()).thenReturn(HypervisorType.External);
        when(w.hostDao.findById(5L)).thenReturn(first); when(w.hostDao.findById(6L)).thenReturn(second);
        when(w.extensions.getExternalAccessDetails(first, null)).thenReturn(Map.of("host", Map.of("proxmox_cluster", "cluster-a")));
        when(w.extensions.getExternalAccessDetails(second, null)).thenReturn(Map.of("host", Map.of("proxmox_cluster", "cluster-a")));
        VMInstanceDetailVO a = mock(VMInstanceDetailVO.class), b = mock(VMInstanceDetailVO.class);
        when(a.getValue()).thenReturn("101"); when(b.getValue()).thenReturn("101");
        when(w.detailsDao.findDetails(1L,"proxmox_vmid")).thenReturn(List.of(a));
        when(w.detailsDao.findDetails(2L,"proxmox_vmid")).thenReturn(List.of(b));
        assertTrue(w.unique(1, "cluster-a", "101"));
        w.ids = List.of(1L, 2L);
        assertFalse(w.unique(1, "cluster-a", "101")); // Distinct CS clusters, same provider cluster.
        when(w.extensions.getExternalAccessDetails(second, null)).thenReturn(Map.of("host", Map.of("proxmox_cluster", "cluster-b")));
        assertTrue(w.unique(1, "cluster-a", "101"));
        when(b.getValue()).thenReturn("0101"); assertFalse(w.unique(1, "cluster-a", "101"));
        when(b.getValue()).thenReturn("101");
        when(w.hostDao.findById(5L)).thenReturn(null); assertFalse(w.unique(1, "cluster-a", "101"));
    }
    @Test public void adoptionMarkersAndClaimsAreExcluded() throws Exception {
        Eligibility e = eligibleFixture(); VMInstanceVO vm = e.vmDao.findById(1L);
        detail(e, "External:adopt_claim_id", "claim"); assertFalse(e.eligible(vm));
        when(e.detailsDao.findDetails(1L, "External:adopt_claim_id")).thenReturn(Collections.emptyList());
        detail(e, "adopt_existing", "true"); assertFalse(e.eligible(vm));
        when(e.detailsDao.findDetails(1L, "adopt_existing")).thenReturn(Collections.emptyList());
        e.claim = true; assertFalse(e.eligible(vm));
    }
}
