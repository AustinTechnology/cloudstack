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
package org.apache.cloudstack.hypervisor.external.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.hypervisor.external.refresh.ExternalVmConfiguration;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;

import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.VMInstanceVO;

public class ExternalVolumeSyncTest {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final String ROOT_DISK = "{\"device\":\"scsi0\",\"storage\":\"ssd\",\"volume\":\"vm-110-disk-0\",\"size_bytes\":" + 32 * GIB + ",\"root\":true}";
    private static final String DATA_DISK = "{\"device\":\"scsi1\",\"storage\":\"ssd\",\"volume\":\"vm-110-disk-1\",\"size_bytes\":" + 100 * GIB + ",\"root\":false}";

    private ExternalVolumeSync sync;
    private VMInstanceVO vm;
    private StoragePoolVO pool;
    private DiskOfferingVO offering;
    private List<String> unresolved;

    private static ExternalVmConfiguration snapshot(String... disks) {
        return ExternalVmConfiguration.parse("{\"status\":\"success\",\"identity\":{\"cluster\":\"P2\",\"vmid\":110,\"name\":\"guest\",\"node\":\"pve1\"},"
                + "\"power\":\"running\",\"config_digest\":\"0123456789012345678901234567890123456789\",\"cpus\":4,\"memory_mb\":8192,"
                + "\"agent_available\":false,\"nics\":[],\"disks\":[" + String.join(",", disks) + "]}");
    }

    private static DiskOfferingVO offering(String name, String tag) {
        DiskOfferingVO o = mock(DiskOfferingVO.class);
        when(o.isCustomized()).thenReturn(true);
        when(o.getTags()).thenReturn(tag);
        when(o.getTagsArray()).thenReturn(new String[] {tag});
        when(o.getName()).thenReturn(name);
        return o;
    }

    private VolumeVO recorded(long id, Volume.Type type, String path, long size, long deviceId, long instanceId) {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(id);
        when(volume.getVolumeType()).thenReturn(type);
        when(volume.getPath()).thenReturn(path);
        when(volume.getSize()).thenReturn(size);
        when(volume.getDeviceId()).thenReturn(deviceId);
        when(volume.getInstanceId()).thenReturn(instanceId);
        when(volume.getState()).thenReturn(Volume.State.Ready);
        when(volume.getAccountId()).thenReturn(40L);
        when(volume.isDisplayVolume()).thenReturn(true);
        when(volume.getDiskOfferingId()).thenReturn(60L);
        when(volume.getUuid()).thenReturn("volume-" + id);
        when(sync.volumeDao.findByPoolIdAndPath(5L, path)).thenReturn(volume);
        return volume;
    }

    @Before
    public void setUp() {
        sync = spy(new ExternalVolumeSync());
        sync.volumeDao = mock(VolumeDao.class);
        sync.volumeDetailsDao = mock(VolumeDetailsDao.class);
        sync.poolDao = mock(PrimaryDataStoreDao.class);
        sync.diskOfferingDao = mock(DiskOfferingDao.class);
        sync.templateDao = mock(VMTemplateDao.class);
        sync.volumeManager = mock(VolumeOrchestrationService.class);
        sync.resourceLimits = mock(ResourceLimitService.class);
        sync.accountService = mock(AccountService.class);
        doNothing().when(sync).publish(anyString(), any(), anyLong());
        vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(10L);
        when(vm.getAccountId()).thenReturn(40L);
        pool = mock(StoragePoolVO.class);
        when(pool.getId()).thenReturn(5L);
        when(pool.getPath()).thenReturn("ssd");
        when(pool.getStorageProviderName()).thenReturn("External");
        when(pool.getPoolType()).thenReturn(StoragePoolType.RBD);
        when(pool.getName()).thenReturn("Proxmox SSD");
        sync.clusterDao = mock(com.cloud.dc.dao.ClusterDao.class);
        com.cloud.dc.ClusterVO cluster = mock(com.cloud.dc.ClusterVO.class);
        when(cluster.getDataCenterId()).thenReturn(3L);
        when(sync.clusterDao.findById(7L)).thenReturn(cluster);
        when(sync.poolDao.listByDataCenterId(3L)).thenReturn(Collections.singletonList(pool));
        sync.inventory = mock(ExternalStorageInventory.class);
        when(sync.inventory.reaches(eq(pool), eq(7L), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        when(sync.poolDao.searchForStoragePoolTags(5L)).thenReturn(Arrays.asList("ssd-tier", "other"));
        offering = offering("Standard SSD", "ssd-tier");
        when(sync.diskOfferingDao.listAllActiveAndNonComputeDiskOfferings()).thenReturn(Collections.singletonList(offering));
        when(sync.diskOfferingDao.findByIdIncludingRemoved(60L)).thenReturn(offering);
        when(sync.volumeDao.findByInstance(10L)).thenReturn(new ArrayList<>());
        unresolved = new ArrayList<>();
    }

    @Test
    public void dryRunReportsTheImportsAndWritesNothing() {
        List<ExternalVolumeSync.Change> changes = sync.sync(vm, 7L, snapshot(DATA_DISK, ROOT_DISK), true, unresolved);

        assertEquals(2, changes.size());
        assertEquals("ROOT", changes.get(0).getType());
        assertEquals("ssd:vm-110-disk-0", changes.get(0).getVolumeId());
        assertEquals("DATADISK", changes.get(1).getType());
        assertFalse(changes.get(0).applied);
        assertTrue(unresolved.isEmpty());
        verify(sync.volumeManager, never()).importVolume(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(sync.resourceLimits, sync.volumeDetailsDao);
    }

    @Test
    public void applyRecordsRootAsDeviceZeroAndCountsItWithoutCheckingALimit() {
        DiskProfile profile = mock(DiskProfile.class);
        when(profile.getVolumeId()).thenReturn(99L);
        when(sync.volumeManager.importVolume(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(profile);
        VolumeVO created = mock(VolumeVO.class);
        when(created.getId()).thenReturn(99L);
        when(created.getAccountId()).thenReturn(40L);
        when(created.isDisplayVolume()).thenReturn(true);
        when(created.getSize()).thenReturn(32 * GIB);
        when(sync.volumeDao.findById(99L)).thenReturn(created);

        List<ExternalVolumeSync.Change> changes = sync.sync(vm, 7L, snapshot(ROOT_DISK, DATA_DISK), false, unresolved);

        assertEquals(2, changes.size());
        assertTrue(changes.get(0).applied);
        verify(sync.volumeManager).importVolume(eq(Volume.Type.ROOT), eq("ROOT-10"), eq(offering), eq(32 * GIB), isNull(), isNull(), any(), any(), eq(vm), any(), any(),
                eq(0L), eq(5L), eq(StoragePoolType.RBD), eq("ssd:vm-110-disk-0"), isNull());
        verify(sync.volumeManager).importVolume(eq(Volume.Type.DATADISK), eq("DATA-10-scsi1"), eq(offering), eq(100 * GIB), isNull(), isNull(), any(), any(), eq(vm), any(), any(),
                eq(1L), eq(5L), eq(StoragePoolType.RBD), eq("ssd:vm-110-disk-1"), isNull());
        verify(sync.volumeDetailsDao).addDetail(99L, ExternalVolumeSync.SLOT_DETAIL, "scsi0", false);
        verify(sync.resourceLimits, org.mockito.Mockito.times(2)).incrementVolumeResourceCount(40L, true, 32 * GIB, offering);
        verifyNoMoreInteractions(sync.resourceLimits);
        verify(sync, org.mockito.Mockito.times(2)).publish(eq(EventTypes.EVENT_VOLUME_CREATE), eq(created), anyLong());
    }

    @Test
    public void diskWithNoRegisteredStorageNoOfferingOrNoVolumeIsReportedNotRecorded() {
        String elsewhere = DATA_DISK.replace("\"ssd\"", "\"local-lvm\"");
        String passthrough = "{\"device\":\"sata0\",\"storage\":null,\"volume\":null,\"size_bytes\":" + GIB + ",\"root\":false}";
        DiskOfferingVO another = offering("Another", "other");
        when(sync.diskOfferingDao.listAllActiveAndNonComputeDiskOfferings()).thenReturn(Arrays.asList(offering, another));

        List<ExternalVolumeSync.Change> changes = sync.sync(vm, 7L, snapshot(ROOT_DISK, elsewhere, passthrough), false, unresolved);

        assertTrue(changes.isEmpty());
        assertEquals(3, unresolved.size());
        assertTrue(unresolved.toString(), unresolved.get(0).contains("no single custom disk offering"));
        assertTrue(unresolved.toString(), unresolved.get(1).contains("local-lvm, which is not registered"));
        assertTrue(unresolved.toString(), unresolved.get(2).contains("sata0 is not on a storage"));
        verify(sync.volumeManager, never()).importVolume(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void diskRecordedForAnotherInstanceIsReportedAndLeftAlone() {
        VolumeVO other = recorded(70L, Volume.Type.ROOT, "ssd:vm-110-disk-0", 32 * GIB, 0L, 11L);

        List<ExternalVolumeSync.Change> changes = sync.sync(vm, 7L, snapshot(ROOT_DISK), false, unresolved);

        assertTrue(changes.isEmpty());
        assertTrue(unresolved.get(0).contains("recorded for another instance"));
        verify(other, never()).setSize(any());
        verify(sync.volumeDao, never()).update(anyLong(), any());
    }

    @Test
    public void anInstanceNotRecordedYetCanOnlyBePlanned() {
        List<ExternalVolumeSync.Change> changes = sync.sync(null, 7L, snapshot(ROOT_DISK), true, unresolved);

        assertEquals("import", changes.get(0).getAction());
        assertFalse(changes.get(0).applied);
        assertThrows(IllegalArgumentException.class, () -> sync.sync(null, 7L, snapshot(ROOT_DISK), false, unresolved));
        recorded(70L, Volume.Type.ROOT, "ssd:vm-110-disk-0", 32 * GIB, 0L, 11L);
        assertTrue(sync.sync(null, 7L, snapshot(ROOT_DISK), true, unresolved).isEmpty());
        assertTrue(unresolved.get(0).contains("recorded for another instance"));
    }

    @Test
    public void recordingOverALimitIsReportedAndStillDone() {
        com.cloud.user.Account owner = mock(com.cloud.user.Account.class);
        when(owner.getAccountName()).thenReturn("tenant");
        when(sync.resourceLimits.findCorrectResourceLimitForAccount(owner, com.cloud.configuration.Resource.ResourceType.volume, null)).thenReturn(1L);
        when(sync.resourceLimits.findCorrectResourceLimitForAccount(owner, com.cloud.configuration.Resource.ResourceType.primary_storage, null)).thenReturn(-1L);
        when(sync.resourceLimits.getResourceCount(owner, com.cloud.configuration.Resource.ResourceType.volume, null)).thenReturn(1L);

        List<ExternalVolumeSync.Change> changes = sync.sync(null, owner, 7L, snapshot(ROOT_DISK, DATA_DISK), true, unresolved);

        assertEquals(2, changes.size());
        assertEquals(1, unresolved.size());
        assertTrue(unresolved.get(0), unresolved.get(0).contains("account tenant would hold 3 volumes, over its limit of 1"));
    }

    @Test
    public void sizeChangeAtTheProviderUpdatesTheRecordAndTheCount() {
        VolumeVO volume = recorded(70L, Volume.Type.ROOT, "ssd:vm-110-disk-0", 20 * GIB, 0L, 10L);
        when(sync.volumeDao.findByInstance(10L)).thenReturn(Collections.singletonList(volume));

        List<ExternalVolumeSync.Change> changes = sync.sync(vm, 7L, snapshot(ROOT_DISK), false, unresolved);

        assertEquals("resize", changes.get(0).getAction());
        assertEquals(Long.valueOf(20 * GIB), changes.get(0).getPreviousBytes());
        assertEquals(32 * GIB, changes.get(0).getBytes());
        verify(volume).setSize(32 * GIB);
        verify(sync.volumeDao).update(70L, volume);
        verify(sync.resourceLimits).incrementVolumePrimaryStorageResourceCount(40L, true, 12 * GIB, offering);
        verify(sync).publish(EventTypes.EVENT_VOLUME_RESIZE, volume, 32 * GIB);
        assertTrue(unresolved.isEmpty());
    }

    @Test
    public void unchangedDiskIsLeftAloneAndARecordWithNoDiskIsReportedNotRemoved() {
        VolumeVO root = recorded(70L, Volume.Type.ROOT, "ssd:vm-110-disk-0", 32 * GIB, 0L, 10L);
        VolumeVO gone = recorded(71L, Volume.Type.DATADISK, "ssd:vm-110-disk-7", GIB, 1L, 10L);
        when(sync.volumeDao.findByInstance(10L)).thenReturn(Arrays.asList(root, gone));

        List<ExternalVolumeSync.Change> changes = sync.sync(vm, 7L, snapshot(ROOT_DISK), false, unresolved);

        assertTrue(changes.isEmpty());
        assertEquals(1, unresolved.size());
        assertTrue(unresolved.get(0).contains("volume-71") && unresolved.get(0).contains("report-only"));
        verify(sync.volumeDao, never()).update(anyLong(), any());
        verify(sync.volumeDao, never()).remove(anyLong());
        verifyNoMoreInteractions(sync.resourceLimits);
    }

    @Test
    public void aClusterWhoseStorageOfThatNameIsSomethingElseHasItsDisksLeftOut() {
        when(sync.inventory.reaches(pool, 7L, true)).thenReturn(false);

        assertTrue(sync.sync(vm, 7L, snapshot(ROOT_DISK, DATA_DISK), false, unresolved).isEmpty());

        assertEquals(1, unresolved.size());
        assertTrue(unresolved.get(0), unresolved.get(0).contains("storage ssd of this cluster is not the storage registered as Proxmox SSD"));
        verify(sync.inventory).reaches(pool, 7L, true);
        verify(sync.volumeManager, never()).importVolume(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        // Volumes already recorded are left as they are, and not called missing.
        VolumeVO root = recorded(70L, Volume.Type.ROOT, "ssd:vm-110-disk-0", 32 * GIB, 0L, 10L);
        when(sync.volumeDao.findByInstance(10L)).thenReturn(Collections.singletonList(root));
        when(sync.inventory.reaches(pool, 7L, false)).thenThrow(new RuntimeException("no host"));
        unresolved.clear();
        assertTrue(sync.sync(vm, 7L, snapshot(ROOT_DISK), true, unresolved).isEmpty());
        assertEquals(unresolved.toString(), 1, unresolved.size());
        assertTrue(unresolved.get(0), unresolved.get(0).contains("could not be compared"));
        verify(sync.volumeDao, never()).update(anyLong(), any());
    }

    @Test
    public void clustersMeanTheSameStorageByBackingAndForCephAMonitorInCommon() {
        java.util.Set<String> none = Collections.emptySet();
        java.util.Set<String> p2 = new java.util.HashSet<>(Arrays.asList("10.0.0.44", "10.0.0.32", "10.0.0.85"));
        java.util.Set<String> p3 = new java.util.HashSet<>(Arrays.asList("10.0.0.32", "10.0.0.67"));
        java.util.Set<String> elsewhere = Collections.singleton("192.168.1.1");
        assertTrue(ExternalStorageInventory.same("rbd/ssd.meta/ssd/", p2, "rbd/ssd.meta/ssd/", p3));
        assertFalse(ExternalStorageInventory.same("rbd/ssd.meta/ssd/", p2, "rbd/ssd.meta/ssd/", elsewhere));
        assertFalse(ExternalStorageInventory.same("rbd/ssd.meta/ssd/", p2, "rbd/ssd.meta/other/", p3));
        assertFalse(ExternalStorageInventory.same("rbd/ssd.meta/ssd/", p2, "rbd/ssd.meta/ssd/", none));
        assertTrue(ExternalStorageInventory.same("nfs/10.0.0.9/srv/vm", none, "nfs/10.0.0.9/srv/vm", none));
        assertFalse(ExternalStorageInventory.same("lvmthin/@P2/local-lvm", none, "lvmthin/@P3/local-lvm", none));
    }

    @Test
    public void oneZonePoolIsRegisteredForEveryClusterThatReachesTheStorage() {
        ExternalPrimaryDataStoreLifeCycle lifeCycle = new ExternalPrimaryDataStoreLifeCycle();
        lifeCycle.zoneDao = mock(com.cloud.dc.dao.DataCenterDao.class);
        lifeCycle.poolDao = mock(PrimaryDataStoreDao.class);
        lifeCycle.inventory = mock(ExternalStorageInventory.class);
        lifeCycle.dataStoreHelper = mock(org.apache.cloudstack.storage.volume.datastore.PrimaryDataStoreHelper.class);
        com.cloud.dc.DataCenterVO zone = mock(com.cloud.dc.DataCenterVO.class);
        when(zone.getUuid()).thenReturn("zone-uuid");
        when(lifeCycle.zoneDao.findById(3L)).thenReturn(zone);
        when(lifeCycle.poolDao.listByDataCenterId(3L)).thenReturn(Collections.emptyList());
        com.cloud.dc.ClusterVO p2 = mock(com.cloud.dc.ClusterVO.class);
        com.cloud.dc.ClusterVO p3 = mock(com.cloud.dc.ClusterVO.class);
        when(p2.getUuid()).thenReturn("c2");
        when(p2.getId()).thenReturn(2L);
        when(p2.getName()).thenReturn("P2");
        when(p3.getUuid()).thenReturn("c3");
        when(p3.getId()).thenReturn(3L);
        when(p3.getName()).thenReturn("P3");
        when(lifeCycle.inventory.providerCluster(2L)).thenReturn("cluster-a");
        when(lifeCycle.inventory.providerCluster(3L)).thenReturn("cluster-b");
        java.util.Map<com.cloud.dc.ClusterVO, ExternalStorageInventory.Storage> found = new java.util.LinkedHashMap<>();
        found.put(p2, new ExternalStorageInventory.Storage("ssd", "rbd", true, true, true, 1000, 250, "rbd/ssd.meta/ssd/", new java.util.TreeSet<>(Arrays.asList("10.0.0.44", "10.0.0.32"))));
        found.put(p3, new ExternalStorageInventory.Storage("ssd", "rbd", true, true, true, 1000, 250, "rbd/ssd.meta/ssd/", new java.util.TreeSet<>(Arrays.asList("10.0.0.32", "10.0.0.67"))));
        when(lifeCycle.inventory.findInZone(3L, "ssd")).thenReturn(found);
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        info.put("zoneId", 3L);
        info.put("hypervisorType", com.cloud.hypervisor.Hypervisor.HypervisorType.External);
        info.put("url", "external://ssd");
        info.put("name", "Proxmox SSD");

        lifeCycle.initialize(info);

        org.mockito.ArgumentCaptor<org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreParameters> created =
                org.mockito.ArgumentCaptor.forClass(org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreParameters.class);
        verify(lifeCycle.dataStoreHelper).createPrimaryDataStore(created.capture());
        assertEquals("ssd", created.getValue().getPath());
        assertNull(created.getValue().getClusterId());
        assertEquals(StoragePoolType.RBD, created.getValue().getType());
        java.util.Map<String, String> details = created.getValue().getDetails();
        assertEquals("rbd/ssd.meta/ssd/", details.get(ExternalStorageInventory.BACKING));
        assertEquals("10.0.0.32,10.0.0.44,10.0.0.67", details.get(ExternalStorageInventory.MONITORS));
        assertEquals("cluster-a", details.get(ExternalStorageInventory.CLUSTER + "c2"));
        assertEquals("cluster-b", details.get(ExternalStorageInventory.CLUSTER + "c3"));

        // The same name for something else on one cluster, a cluster scope, or no cluster with it: nothing is registered.
        found.put(p3, new ExternalStorageInventory.Storage("ssd", "rbd", true, true, true, 1000, 250, "rbd/ssd.meta/ssd/", Collections.singleton("192.168.1.1")));
        assertThrows(InvalidParameterValueException.class, () -> lifeCycle.initialize(info));
        found.put(p3, new ExternalStorageInventory.Storage("ssd", "lvmthin", false, true, true, 1000, 250, "lvmthin/@p3/ssd", Collections.emptySet()));
        assertThrows(InvalidParameterValueException.class, () -> lifeCycle.initialize(info));
        found.clear();
        assertThrows(InvalidParameterValueException.class, () -> lifeCycle.initialize(info));
        info.put("clusterId", 2L);
        assertThrows(InvalidParameterValueException.class, () -> lifeCycle.initialize(info));
        verify(lifeCycle.dataStoreHelper, org.mockito.Mockito.times(1)).createPrimaryDataStore(any());
    }

    @Test
    public void storageInventoryIsParsedStrictlyAndBoundToItsCluster() {
        String ok = "{\"status\":\"success\",\"cluster\":\"P2\",\"storages\":[{\"storage\":\"ssd\",\"type\":\"rbd\",\"shared\":true,\"enabled\":true,\"active\":true,\"total\":1000,\"used\":250,"
                + "\"backing\":\"rbd/ssd.meta/ssd/\",\"monitors\":[\"10.0.0.2\",\"10.0.0.1\"]}]}";
        ExternalStorageInventory.Storage storage = ExternalStorageInventory.parse(ok, "P2").get(0);
        assertEquals("ssd", storage.name);
        assertEquals(1000L, storage.totalBytes);
        assertTrue(storage.shared);
        assertEquals("rbd/ssd.meta/ssd/", storage.backing);
        assertEquals("[10.0.0.1, 10.0.0.2]", storage.monitors.toString());
        for (String bad : new String[] {ok.replace("\"P2\"", "\"P3\""), ok.replace("\"shared\":true", "\"shared\":1"), ok.replace("1000", "-1"),
                ok.replace("rbd/ssd.meta/ssd/", ""), ok.replace("\"10.0.0.2\"", "7"), ok.replace(",\"monitors\":[\"10.0.0.2\",\"10.0.0.1\"]", ""),
                ok.replace("\"ssd\"", "\"s s\""), ok.replace("success", "error"), "not json", null}) {
            assertThrows(InvalidParameterValueException.class, () -> ExternalStorageInventory.parse(bad, "P2"));
        }
    }

    @Test
    public void poolUrlNamesOneStorageAndItsTypeOnlyDescribesIt() {
        assertEquals("ssd-ec", ExternalPrimaryDataStoreLifeCycle.storageName("external://ssd-ec"));
        for (String bad : new String[] {null, "rbd://mon/pool", "external://", "external://a/b", "external://a b"}) {
            assertThrows(InvalidParameterValueException.class, () -> ExternalPrimaryDataStoreLifeCycle.storageName(bad));
        }
        assertEquals(StoragePoolType.RBD, ExternalPrimaryDataStoreLifeCycle.poolType("rbd"));
        assertEquals(StoragePoolType.PreSetup, ExternalPrimaryDataStoreLifeCycle.poolType("zfspool"));
        assertNull(new ExternalPrimaryDataStoreDriver().getTO(null));
    }
}
