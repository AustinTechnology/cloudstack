// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0.
package org.apache.cloudstack.hypervisor.external.adoption;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import com.cloud.utils.db.TransactionLegacy;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
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
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

/** CloudStack-typed collaborators; the SQL handover-fence check is isolated here. */
public class NativeManagedAdoptionVerifierTest {
    private static final String CLAIM = "10000000-0000-4000-8000-000000000001";
    private static final String VM = "20000000-0000-4000-8000-000000000002";
    private static final String DOMAIN = "30000000-0000-4000-8000-000000000003";
    private static final String MANIFEST = "{\"placement\":{\"cluster\":\"p2\",\"node\":\"old\"},\"vmid\":100,\"name\":\"provider-name\",\"cpus\":1,\"memory_mib\":512}";
    private static final String SNAP = "{\"status\":\"success\",\"identity\":{\"cluster\":\"p2\",\"vmid\":100,\"name\":\"provider-name\",\"node\":\"new\"},\"power\":\"running\",\"config_digest\":\"0123456789012345678901234567890123456789\",\"cpus\":2,\"memory_mb\":1024,\"agent_available\":false,\"nics\":[{\"device_id\":4,\"mac\":\"02:00:00:00:00:01\",\"bridge\":\"vmbr0\",\"vlan\":42,\"ip_addresses\":[]}],\"disks\":[]}";
    NativeManagedAdoptionVerifier v;
    VMInstanceVO vm;
    HostVO host;
    AccountVO account;
    DomainVO domain;
    ServiceOfferingVO offering;
    NicVO nic;
    NetworkVO network;
    Answer answer;
    AdoptionRegistry.Identity identity;

    private static String hash(String s) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))) b.append(String.format("%02x", x & 255));
        return b.toString();
    }
    private void detail(String key, String value) {
        VMInstanceDetailVO row = mock(VMInstanceDetailVO.class);
        when(row.getValue()).thenReturn(value);
        when(v.detailsDao.findDetails(1L, key)).thenReturn(List.of(row));
    }
    @Before public void setup() throws Exception {
        v = new NativeManagedAdoptionVerifier() { @Override void requireManagedImport(AdoptionRegistry.Identity i, String node, String manifest) { } };
        v.vmDao = mock(VMInstanceDao.class); v.detailsDao = mock(VMInstanceDetailsDao.class);
        v.nicDao = mock(NicDao.class); v.hostDao = mock(HostDao.class); v.networkDao = mock(NetworkDao.class);
        v.accountDao = mock(AccountDao.class); v.domainDao = mock(DomainDao.class); v.offeringDao = mock(ServiceOfferingDao.class);
        v.accountService = mock(AccountService.class);
        v.vmManager = mock(VirtualMachineManager.class); v.extensionsManager = mock(ExtensionsManager.class);
        vm = mock(VMInstanceVO.class); host = mock(HostVO.class); account = mock(AccountVO.class); domain = mock(DomainVO.class);
        offering = mock(ServiceOfferingVO.class); nic = mock(NicVO.class); network = mock(NetworkVO.class); answer = mock(Answer.class);
        identity = new AdoptionRegistry.Identity(CLAIM, 2, "p2", 100, hash(MANIFEST), VM, "i-2-1-VM");
        when(v.vmDao.findByUuid(VM)).thenReturn(vm); when(v.vmDao.lockRow(1L, true)).thenReturn(vm);
        when(v.vmDao.listAll()).thenReturn(List.of(vm));
        when(vm.getId()).thenReturn(1L); when(vm.getUuid()).thenReturn(VM); when(vm.getInstanceName()).thenReturn("i-2-1-VM");
        when(vm.getType()).thenReturn(VirtualMachine.Type.User); when(vm.getHypervisorType()).thenReturn(HypervisorType.External);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running); when(vm.getHostId()).thenReturn(10L);
        when(vm.getAccountId()).thenReturn(5L); when(vm.getDomainId()).thenReturn(6L); when(vm.getServiceOfferingId()).thenReturn(7L);
        when(v.vmManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(20L, 10L));
        when(v.hostDao.findById(10L)).thenReturn(host); when(host.getHypervisorType()).thenReturn(HypervisorType.External); when(host.getClusterId()).thenReturn(20L);
        when(v.extensionsManager.getExternalAccessDetails(host, null)).thenReturn(Map.of("host", Map.of("proxmox_cluster", "p2", "node", "new", "network_bridge", "vmbr0")));
        when(v.extensionsManager.getInstanceConfig(vm)).thenReturn(answer); when(answer.getResult()).thenReturn(true); when(answer.getDetails()).thenReturn(SNAP);
        when(v.accountDao.findById(5L)).thenReturn(account); when(account.getDomainId()).thenReturn(6L); when(account.getType()).thenReturn(Account.Type.ADMIN); when(account.getAccountName()).thenReturn("admin");
        when(v.domainDao.findById(6L)).thenReturn(domain); when(domain.getUuid()).thenReturn(DOMAIN);
        when(v.offeringDao.findByIdIncludingRemoved(1L, 7L)).thenReturn(offering); when(offering.getCpu()).thenReturn(2); when(offering.getRamSize()).thenReturn(1024);
        when(v.nicDao.listByVmId(1L)).thenReturn(List.of(nic)); when(nic.getMacAddress()).thenReturn("02:00:00:00:00:01");
        when(nic.getInstanceId()).thenReturn(1L); when(nic.getVmType()).thenReturn(VirtualMachine.Type.User);
        when(nic.getDeviceId()).thenReturn(0); when(nic.getNetworkId()).thenReturn(8L); when(nic.getBroadcastUri()).thenReturn(URI.create("vlan://42"));
        when(v.networkDao.findById(8L)).thenReturn(network); when(network.getBroadcastUri()).thenReturn(URI.create("vlan://42"));
        when(network.getId()).thenReturn(8L); when(network.getTrafficType()).thenReturn(TrafficType.Guest);
        detail("proxmox_vmid", "100"); detail("proxmox_cluster", "p2"); detail("adopt_existing", "true");
        detail("adopt_claim_id", CLAIM); detail("adopt_claim_generation", "2"); detail("adopt_manifest_sha256", identity.manifestHash); detail("adopt_manifest_json", MANIFEST);
    }
    private void run() { v.verify(identity, "old", MANIFEST); }
    private void rejected(Runnable work, String diagnostic) {
        try { work.run(); fail("expected rejection"); } catch (AdoptionRegistry.Conflict e) { assertTrue(e.getMessage(), e.getMessage().contains(diagnostic)); }
    }
    @Test public void managedScaledMovedAndSparseNicPasses() { run(); verify(v.extensionsManager).getInstanceConfig(vm); }
    private VMInstanceVO candidate(String cluster) {
        VMInstanceVO other = mock(VMInstanceVO.class);
        HostVO otherHost = mock(HostVO.class);
        when(other.getId()).thenReturn(2L); when(other.getHypervisorType()).thenReturn(HypervisorType.External);
        when(other.getHostId()).thenReturn(null); when(other.getLastHostId()).thenReturn(11L);
        when(v.vmManager.findClusterAndHostIdForVm(other, false)).thenReturn(new Pair<>(21L, 11L));
        when(v.hostDao.findById(11L)).thenReturn(otherHost); when(otherHost.getHypervisorType()).thenReturn(HypervisorType.External);
        when(otherHost.getClusterId()).thenReturn(21L);
        when(v.extensionsManager.getExternalAccessDetails(otherHost, null)).thenReturn(Map.of("host", Map.of("proxmox_cluster", cluster)));
        VMInstanceDetailVO d = mock(VMInstanceDetailVO.class); when(d.getValue()).thenReturn("100");
        when(v.detailsDao.findDetails(2L, "External:proxmox_vmid")).thenReturn(List.of(d));
        when(v.vmDao.listAll()).thenReturn(List.of(vm, other));
        return other;
    }
    @Test public void duplicateNativeProviderIdentityRejectedAcrossCloudStackClusters() {
        candidate("p2"); rejected(this::run, "duplicate native provider identity");
    }
    @Test public void sameVmidInAnotherProviderClusterIsAllowed() { candidate("p3"); run(); }
    @Test public void malformedOrConflictingCandidateAliasesFailClosed() {
        candidate("p3");
        VMInstanceDetailVO d = mock(VMInstanceDetailVO.class); when(d.getValue()).thenReturn("0100");
        when(v.detailsDao.findDetails(2L, "external.proxmox_vmid")).thenReturn(List.of(d));
        rejected(this::run, "aliases");
    }
    @Test public void malformedSoleCandidateVmidFailsClosed() {
        candidate("p3");
        VMInstanceDetailVO d = mock(VMInstanceDetailVO.class); when(d.getValue()).thenReturn("0100");
        when(v.detailsDao.findDetails(2L, "External:proxmox_vmid")).thenReturn(List.of(d));
        rejected(this::run, "malformed candidate VMID");
    }
    @Test public void missingCandidateVmidInSameProviderClusterFailsClosed() {
        candidate("p2"); when(v.detailsDao.findDetails(2L, "External:proxmox_vmid")).thenReturn(List.of());
        rejected(this::run, "candidate VMID unavailable");
    }
    @Test public void candidateUsesCurrentHostRatherThanStaleLastHost() {
        VMInstanceVO other = candidate("p3"); when(other.getHostId()).thenReturn(12L);
        rejected(this::run, "candidate current or last host unavailable");
    }
    @Test public void targetMustAppearInNativeEnumeration() { when(v.vmDao.listAll()).thenReturn(List.of()); rejected(this::run, "target absent"); }
    @Test public void candidateHostMappingAndDetailsMustAgree() {
        candidate("p3");
        VMInstanceDetailVO d = mock(VMInstanceDetailVO.class); when(d.getValue()).thenReturn("p2");
        when(v.detailsDao.findDetails(2L, "proxmox_cluster")).thenReturn(List.of(d));
        rejected(this::run, "candidate cluster detail mismatch");
    }
    @Test public void staleNicInstanceBindingRejected() { when(nic.getInstanceId()).thenReturn(2L); rejected(this::run, "NIC instance binding"); }
    @Test public void staleNicVmTypeRejected() { when(nic.getVmType()).thenReturn(VirtualMachine.Type.DomainRouter); rejected(this::run, "NIC instance binding"); }
    @Test public void staleNicNetworkZoneAndTrafficRejected() {
        when(network.getId()).thenReturn(9L); rejected(this::run, "NIC topology");
        when(network.getId()).thenReturn(8L); when(network.getDataCenterId()).thenReturn(9L); rejected(this::run, "NIC topology");
        when(network.getDataCenterId()).thenReturn(0L); when(network.getTrafficType()).thenReturn(TrafficType.Public); rejected(this::run, "NIC topology");
    }
    @Test public void sharedNetworkUsesCloudStackAccessServiceNotOwnerEquality() {
        when(network.getAccountId()).thenReturn(999L); run();
        verify(v.accountService).checkAccess(account, AccessType.UseEntry, false, network);
        doThrow(new IllegalStateException()).when(v.accountService).checkAccess(account, AccessType.UseEntry, false, network);
        rejected(this::run, "denied native network access");
        v.accountService = null; rejected(this::run, "network access service unavailable");
    }
    @Test public void aliasesMustAgreeAndBeUnique() {
        detail("External:proxmox_vmid", "0100"); rejected(this::run, "aliases");
    }
    @Test public void exactManifestBytesAndHashAreRequired() { rejected(() -> v.verify(identity, "old", MANIFEST + " "), "manifest"); }
    @Test public void malformedGenerationRejected() { detail("adopt_claim_generation", "02"); rejected(this::run, "generation"); }
    @Test public void nativeLifecycleNeverBypassesCallback() {
        when(v.extensionsManager.getExternalAccessDetails(host, null)).thenReturn(Map.of("host", Map.of("proxmox_cluster", "p2", "node", "new", "network_bridge", "vmbr0", "native_adoption_lifecycle", "true")));
        rejected(this::run, "native lifecycle"); verify(v.extensionsManager, never()).getInstanceConfig(vm);
    }
    @Test public void failedSnapshotDoesNotLeakProviderError() {
        when(answer.getResult()).thenReturn(false); rejected(this::run, "provider snapshot unavailable");
    }
    @Test public void currentResourcesMustMatchNotHistoricalResources() { when(offering.getCpu()).thenReturn(1); rejected(this::run, "CPU or memory"); }
    @Test public void wrongProviderNodeRejected() { when(answer.getDetails()).thenReturn(SNAP.replace("\"node\":\"new\"", "\"node\":\"other\"")); rejected(this::run, "current node"); }
    @Test public void projectAccountsRejected() { when(account.getType()).thenReturn(Account.Type.PROJECT); rejected(this::run, "project-owned"); }
    @Test public void providerNicMismatchRejected() { when(nic.getMacAddress()).thenReturn("02:00:00:00:00:02"); rejected(this::run, "topology"); }
    @Test public void nativeNetworkVlanMustAgree() { when(network.getBroadcastUri()).thenReturn(URI.create("vlan://43")); rejected(this::run, "topology"); }
    @Test public void stoppedVmUsesLastHost() { when(vm.getState()).thenReturn(VirtualMachine.State.Stopped); when(vm.getHostId()).thenReturn(null); when(vm.getLastHostId()).thenReturn(10L); when(answer.getDetails()).thenReturn(SNAP.replace("\"power\":\"running\"", "\"power\":\"stopped\"")); run(); }
    @Test public void wrongClaimAndWrongOwnerAreRejected() { detail("adopt_claim_id", VM); rejected(this::run, "claim_id"); detail("adopt_claim_id", CLAIM); when(account.getAccountName()).thenReturn("other"); rejected(this::run, "admin account"); }
    @Test public void missingSnapshotRejected() { when(v.extensionsManager.getInstanceConfig(vm)).thenReturn(null); rejected(this::run, "snapshot unavailable"); }
    @Test public void explicitTenantOwnershipRequiresExactDomainAndAccount() throws Exception {
        String tenant = MANIFEST.substring(0, MANIFEST.length() - 1) + ",\"ownership\":{\"account\":\"TenantA\",\"domain_id\":\"" + DOMAIN + "\",\"project_id\":null}}";
        AdoptionRegistry.Identity ti = new AdoptionRegistry.Identity(CLAIM, 2, "p2", 100, hash(tenant), VM, "i-2-1-VM");
        detail("adopt_manifest_sha256", ti.manifestHash); detail("adopt_manifest_json", tenant);
        when(account.getAccountName()).thenReturn("TenantA"); when(account.getType()).thenReturn(Account.Type.NORMAL);
        v.verify(ti, "old", tenant);
        when(domain.getUuid()).thenReturn(VM);
        rejected(() -> v.verify(ti, "old", tenant), "owner differs");
    }
    @Test public void realManagedFenceRequiresTransactionAndRejectsBound() throws Exception {
        NativeManagedAdoptionVerifier real = new NativeManagedAdoptionVerifier();
        TransactionLegacy tx = mock(TransactionLegacy.class); Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class); ResultSet rs = mock(ResultSet.class);
        try (MockedStatic<TransactionLegacy> current = mockStatic(TransactionLegacy.class)) {
            current.when(TransactionLegacy::currentTxn).thenReturn(tx);
            when(tx.dbTxnStarted()).thenReturn(false);
            rejected(() -> real.requireManagedImport(identity, "old", MANIFEST), "transaction required");
            when(tx.dbTxnStarted()).thenReturn(true); when(tx.getConnection()).thenReturn(c);
            when(c.prepareStatement(anyString())).thenReturn(ps); when(ps.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true, false); when(rs.getBoolean(2)).thenReturn(true); when(rs.getBoolean(3)).thenReturn(true);
            when(rs.getString(1)).thenReturn("bound");
            rejected(() -> real.requireManagedImport(identity, "old", MANIFEST), "bound activation");
            when(rs.next()).thenReturn(true, false); when(rs.getString(1)).thenReturn("managed");
            real.requireManagedImport(identity, "old", MANIFEST);
            verify(ps, atLeastOnce()).setString(7, MANIFEST);
            when(rs.next()).thenReturn(true); when(rs.getBoolean(3)).thenReturn(false);
            rejected(() -> real.requireManagedImport(identity, "old", MANIFEST), "migration-fenced");
        }
    }
}
