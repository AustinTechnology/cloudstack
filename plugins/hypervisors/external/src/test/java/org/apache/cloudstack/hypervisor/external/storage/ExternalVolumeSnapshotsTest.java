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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DataObjectType;
import com.cloud.agent.api.RunCustomActionAnswer;
import com.cloud.agent.api.RunCustomActionCommand;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.ExternalProvisioner;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;

public class ExternalVolumeSnapshotsTest {
    private static final String UUID = "0a1b2c3d-1111-4222-8333-444455556666";
    private static final String NAME = "cloudstack-" + UUID;

    private ExternalVolumeSnapshots snapshots;
    private VMInstanceVO vm;
    private VolumeVO volume;
    private StoragePoolVO ceph;
    private HostVO host;
    private Extension extension;

    @Before
    public void setUp() {
        snapshots = org.mockito.Mockito.spy(new ExternalVolumeSnapshots());
        org.mockito.Mockito.doNothing().when(snapshots).publishDeleted(any());
        snapshots.volumeDao = mock(VolumeDao.class);
        snapshots.poolDao = mock(PrimaryDataStoreDao.class);
        snapshots.vmDao = mock(VMInstanceDao.class);
        snapshots.hostDao = mock(HostDao.class);
        snapshots.snapshotDao = mock(SnapshotDao.class);
        snapshots.snapshotStoreDao = mock(SnapshotDataStoreDao.class);
        snapshots.extensionHelper = mock(ExtensionHelper.class);
        snapshots.extensionsManager = mock(ExtensionsManager.class);
        snapshots.externalProvisioner = mock(ExternalProvisioner.class);
        snapshots.vmManager = mock(VirtualMachineManager.class);
        snapshots.inventory = mock(ExternalStorageInventory.class);
        snapshots.volumeSync = mock(ExternalVolumeSync.class);
        snapshots.instanceLock = mock(ExternalInstanceLock.class);
        snapshots.resourceLimits = mock(com.cloud.user.ResourceLimitService.class);
        when(snapshots.instanceLock.hold(eq(10L), anyInt(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());

        vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(10L);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.External);
        when(snapshots.vmDao.findById(10L)).thenReturn(vm);
        when(snapshots.vmManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(7L, 8L));

        ceph = mock(StoragePoolVO.class);
        when(ceph.getId()).thenReturn(5L);
        when(ceph.getStorageProviderName()).thenReturn("External");
        when(ceph.getPoolType()).thenReturn(StoragePoolType.RBD);
        when(ceph.getDataCenterId()).thenReturn(3L);
        when(snapshots.poolDao.findById(5L)).thenReturn(ceph);

        volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(20L);
        when(volume.getInstanceId()).thenReturn(10L);
        when(volume.getPoolId()).thenReturn(5L);
        when(volume.getPath()).thenReturn("ssd:vm-110-disk-1");
        when(snapshots.volumeDao.findById(20L)).thenReturn(volume);
        when(snapshots.volumeSync.slot(20L)).thenReturn("scsi1");

        extension = mock(Extension.class);
        when(extension.getId()).thenReturn(4L);
        when(extension.getName()).thenReturn("proxmox");
        when(extension.getRelativePath()).thenReturn("proxmox/proxmox.sh");
        when(snapshots.extensionHelper.getExtensionForCluster(7L)).thenReturn(extension);
        when(snapshots.extensionHelper.isInstanceVolumeSnapshotSupported(4L)).thenReturn(true);

        host = mock(HostVO.class);
        when(host.getId()).thenReturn(8L);
        when(host.getGuid()).thenReturn("guid-8");
        when(host.getClusterId()).thenReturn(7L);
        when(host.getHypervisorType()).thenReturn(HypervisorType.External);
        when(host.getStatus()).thenReturn(Status.Up);
        when(snapshots.hostDao.findById(8L)).thenReturn(host);
        when(snapshots.hostDao.listByDataCenterIdAndHypervisorType(3L, HypervisorType.External)).thenReturn(Collections.singletonList(host));
        when(snapshots.inventory.reaches(ceph, 7L, false)).thenReturn(true);

        Map<String, String> settings = new HashMap<>();
        settings.put("ceph_client", "ext");
        settings.put("ceph_keyring", "/etc/cloudstack/management/ceph/ext.keyring");
        when(snapshots.extensionsManager.getExternalAccessDetails(host, null)).thenReturn(Collections.singletonMap("extension", settings));
    }

    private void instanceAnswers(boolean ok, String details) {
        Answer answer = mock(Answer.class);
        when(answer.getResult()).thenReturn(ok);
        when(answer.getDetails()).thenReturn(details);
        when(snapshots.extensionsManager.changeInstanceDiskSnapshot(eq(vm), anyString(), anyMap())).thenReturn(answer);
    }

    private void storageAnswers(String details) {
        when(snapshots.externalProvisioner.runCustomAction(eq("guid-8"), eq("proxmox"), eq("proxmox/proxmox.sh"), any()))
                .thenAnswer(call -> new RunCustomActionAnswer(call.getArgument(3), true, details));
    }

    @Test
    public void onlyADiskOnCephOfAnExtensionThatSaysSoCanBeSnapshotted() {
        assertNull(snapshots.whyNot(20L));

        when(snapshots.extensionHelper.isInstanceVolumeSnapshotSupported(4L)).thenReturn(false);
        assertTrue(snapshots.whyNot(20L).contains("does not support"));

        when(ceph.getPoolType()).thenReturn(StoragePoolType.NetworkFilesystem);
        assertTrue(snapshots.whyNot(20L).contains("Ceph"));

        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        assertNotNull(snapshots.whyNot(20L));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aSnapshotIsTakenOfTheRecordedDiskInItsSlotAndRecordedByWhereItIs() {
        instanceAnswers(true, "{\"status\":\"success\",\"volume\":\"ssd:vm-110-disk-1\",\"snapshot\":\"" + NAME + "\",\"size_bytes\":1073741824}");

        Pair<String, Long> taken = snapshots.take(20L, UUID);

        assertEquals("ssd:vm-110-disk-1@" + NAME, taken.first());
        assertEquals(Long.valueOf(1L << 30), taken.second());
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(snapshots.extensionsManager).changeInstanceDiskSnapshot(eq(vm), eq("snapshotdisk"), sent.capture());
        assertEquals("scsi1", sent.getValue().get("device"));
        assertEquals("ssd:vm-110-disk-1", sent.getValue().get("volume"));
        assertEquals(NAME, sent.getValue().get("snapshot"));
        assertEquals("ext", sent.getValue().get("cephclient"));
        assertEquals("/etc/cloudstack/management/ceph/ext.keyring", sent.getValue().get("cephkeyring"));
    }

    @Test
    public void aFailureAtTheExternalSystemIsAFailureWithItsReason() {
        instanceAnswers(false, "{\"status\":\"error\",\"error\":\"Ceph did not take the snapshot\"}");
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> snapshots.take(20L, UUID));
        assertTrue(e.getMessage().contains("Ceph did not take the snapshot"));
    }

    @Test
    public void withoutACephClientNothingIsAsked() {
        when(snapshots.extensionsManager.getExternalAccessDetails(host, null)).thenReturn(Collections.singletonMap("extension", new HashMap<>()));
        assertThrows(InvalidParameterValueException.class, () -> snapshots.take(20L, UUID));
        verify(snapshots.extensionsManager, never()).changeInstanceDiskSnapshot(any(), anyString(), anyMap());
    }

    @Test
    public void aDiskIsRevertedOnlyToASnapshotOfItWhereItIsNow() {
        instanceAnswers(true, "{\"status\":\"success\"}");
        snapshots.revert(20L, UUID, "ssd:vm-110-disk-1@" + NAME);
        verify(snapshots.extensionsManager).changeInstanceDiskSnapshot(eq(vm), eq("revertdisksnapshot"), anyMap());

        assertThrows(InvalidParameterValueException.class, () -> snapshots.revert(20L, UUID, "hdd:vm-110-disk-0@" + NAME));
    }

    @Test
    public void aSnapshotIsDeletedFromTheImageRecordedWithItOnThisServer() {
        storageAnswers("{\"status\":\"success\",\"removed\":true}");
        snapshots.delete(ceph, UUID, "ssd:vm-110-disk-1@" + NAME);

        ArgumentCaptor<RunCustomActionCommand> sent = ArgumentCaptor.forClass(RunCustomActionCommand.class);
        verify(snapshots.externalProvisioner).runCustomAction(eq("guid-8"), eq("proxmox"), eq("proxmox/proxmox.sh"), sent.capture());
        assertEquals("deletedisksnapshot", sent.getValue().getActionName());
        assertEquals("ssd:vm-110-disk-1", sent.getValue().getParameters().get("volume"));
        assertEquals(NAME, sent.getValue().getParameters().get("snapshot"));
    }

    @Test
    public void aSnapshotNeverRecordedAsTakenNeedsNoDelete() {
        snapshots.delete(ceph, UUID, null);
        verify(snapshots.externalProvisioner, never()).runCustomAction(anyString(), anyString(), anyString(), any());
    }

    @Test
    public void theCopyToSecondaryStorageIsWrittenToTheFileGivenAndItsSizeReturned() {
        storageAnswers("{\"status\":\"success\",\"size_bytes\":4096,\"virtual_size_bytes\":1073741824}");
        long size = snapshots.backup(ceph, UUID, "ssd:vm-110-disk-1@" + NAME, new File("/mnt/sec/snapshots/2/20/" + UUID), 21600);

        assertEquals(4096L, size);
        ArgumentCaptor<RunCustomActionCommand> sent = ArgumentCaptor.forClass(RunCustomActionCommand.class);
        verify(snapshots.externalProvisioner).runCustomAction(eq("guid-8"), eq("proxmox"), eq("proxmox/proxmox.sh"), sent.capture());
        assertEquals("backupdisksnapshot", sent.getValue().getActionName());
        assertEquals("/mnt/sec/snapshots/2/20/" + UUID, sent.getValue().getParameters().get("file"));
        assertEquals(21600, sent.getValue().getWait());
    }

    @Test
    public void aDiskIsHeldWhileASnapshotOfItIsOnCeph() {
        SnapshotVO snapshot = mock(SnapshotVO.class);
        when(snapshot.getId()).thenReturn(30L);
        when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        when(snapshots.snapshotDao.listByVolumeId(20L)).thenReturn(Arrays.asList(snapshot));
        SnapshotDataStoreVO ref = mock(SnapshotDataStoreVO.class);
        when(ref.getDataStoreId()).thenReturn(5L);
        when(ref.getState()).thenReturn(ObjectInDataStoreStateMachine.State.Ready);
        when(snapshots.snapshotStoreDao.listBySnapshotAndDataStoreRole(30L, DataStoreRole.Primary)).thenReturn(Arrays.asList(ref));
        assertTrue(snapshots.held(volume));

        when(ref.getState()).thenReturn(ObjectInDataStoreStateMachine.State.Destroyed);
        assertFalse(snapshots.held(volume));

        when(ref.getState()).thenReturn(ObjectInDataStoreStateMachine.State.Ready);
        when(snapshot.getState()).thenReturn(Snapshot.State.Destroyed);
        assertFalse(snapshots.held(volume));
    }

    @Test
    public void whenTheDiskGoesASnapshotOnlyOnCephGoesWithItAndOneAlsoOnSecondaryStorageIsLeftToStock() {
        SnapshotVO snapshot = mock(SnapshotVO.class);
        when(snapshot.getId()).thenReturn(30L);
        when(snapshot.getAccountId()).thenReturn(2L);
        when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        when(snapshots.snapshotDao.listByVolumeId(20L)).thenReturn(Arrays.asList(snapshot));
        SnapshotDataStoreVO ref = mock(SnapshotDataStoreVO.class);
        when(ref.getId()).thenReturn(40L);
        when(ref.getDataStoreId()).thenReturn(5L);
        when(ref.getPhysicalSize()).thenReturn(1L << 30);
        when(ref.getState()).thenReturn(ObjectInDataStoreStateMachine.State.Ready);
        when(snapshots.snapshotStoreDao.listBySnapshotAndDataStoreRole(30L, DataStoreRole.Primary)).thenReturn(Arrays.asList(ref));

        when(snapshots.snapshotStoreDao.findBySnapshotIdWithNonDestroyedState(30L)).thenReturn(Arrays.asList(ref, mock(SnapshotDataStoreVO.class)));
        snapshots.gone(20L);
        verify(snapshots.snapshotStoreDao, never()).remove(40L);
        verify(snapshots.resourceLimits, never()).decrementResourceCount(eq(2L), any());

        when(snapshots.snapshotStoreDao.findBySnapshotIdWithNonDestroyedState(30L)).thenReturn(Arrays.asList(ref));
        snapshots.gone(20L);
        verify(snapshots).publishDeleted(snapshot);
        verify(snapshots.snapshotStoreDao).remove(40L);
        verify(snapshot).setState(Snapshot.State.Destroyed);
        verify(snapshots.resourceLimits).decrementResourceCount(2L, com.cloud.configuration.Resource.ResourceType.snapshot);
        verify(snapshots.resourceLimits).decrementResourceCount(2L, com.cloud.configuration.Resource.ResourceType.primary_storage, 1L << 30);
    }

    @Test
    public void onlyASnapshotOnExternalStorageGoingToSecondaryStorageIsCopiedHere() {
        ExternalSnapshotBackup backup = new ExternalSnapshotBackup();
        backup.poolDao = snapshots.poolDao;
        DataStore primary = mock(DataStore.class);
        when(primary.getRole()).thenReturn(DataStoreRole.Primary);
        when(primary.getId()).thenReturn(5L);
        DataStore image = mock(DataStore.class);
        when(image.getRole()).thenReturn(DataStoreRole.Image);
        DataObject source = mock(DataObject.class);
        when(source.getType()).thenReturn(DataObjectType.SNAPSHOT);
        when(source.getDataStore()).thenReturn(primary);
        DataObject destination = mock(DataObject.class);
        when(destination.getType()).thenReturn(DataObjectType.SNAPSHOT);
        when(destination.getDataStore()).thenReturn(image);
        assertEquals(StrategyPriority.HIGHEST, backup.canHandle(source, destination));

        when(ceph.getStorageProviderName()).thenReturn("DefaultPrimary");
        assertEquals(StrategyPriority.CANT_HANDLE, backup.canHandle(source, destination));

        when(ceph.getStorageProviderName()).thenReturn("External");
        when(source.getType()).thenReturn(DataObjectType.VOLUME);
        assertEquals(StrategyPriority.CANT_HANDLE, backup.canHandle(source, destination));
    }
}
