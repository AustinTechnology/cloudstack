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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.api.Answer;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;

public class ExternalVolumeOperationsTest {
    private static final long GIB = 1L << 30;

    private ExternalVolumeOperations operations;
    private VMInstanceVO vm;
    private VolumeVO volume;
    private StoragePoolVO ssd;
    private StoragePoolVO hdd;
    private DiskOfferingVO standardSsd;
    private DiskOfferingVO standardHdd;

    private static StoragePoolVO pool(long id, String path) {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getId()).thenReturn(id);
        when(pool.getPath()).thenReturn(path);
        when(pool.getStorageProviderName()).thenReturn("External");
        when(pool.getStatus()).thenReturn(StoragePoolStatus.Up);
        when(pool.getDataCenterId()).thenReturn(3L);
        return pool;
    }

    private void answer(boolean result, String details) {
        Answer answer = mock(Answer.class);
        when(answer.getResult()).thenReturn(result);
        when(answer.getDetails()).thenReturn(details);
        when(operations.extensionsManager.changeInstanceDisk(eq(vm), anyString(), anyMap())).thenReturn(answer);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sent(String action) {
        ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
        verify(operations.extensionsManager).changeInstanceDisk(eq(vm), eq(action), parameters.capture());
        return parameters.getValue();
    }

    @Before
    public void setUp() {
        operations = spy(new ExternalVolumeOperations());
        operations.volumeDao = mock(VolumeDao.class);
        operations.poolDao = mock(PrimaryDataStoreDao.class);
        operations.diskOfferingDao = mock(DiskOfferingDao.class);
        operations.vmDao = mock(VMInstanceDao.class);
        operations.accountService = mock(AccountService.class);
        operations.resourceLimits = mock(ResourceLimitService.class);
        operations.vmManager = mock(VirtualMachineManager.class);
        operations.extensionsManager = mock(ExtensionsManager.class);
        operations.inventory = mock(ExternalStorageInventory.class);
        operations.volumeSync = mock(ExternalVolumeSync.class);
        operations.instanceLock = mock(ExternalInstanceLock.class);
        operations.vmSnapshotDao = mock(com.cloud.vm.snapshot.dao.VMSnapshotDao.class);
        when(operations.instanceLock.hold(eq(10L), anyInt(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
        doReturn(1024L).when(operations).maxCustomSizeGib();
        vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(10L);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.External);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getDataCenterId()).thenReturn(3L);
        when(operations.vmDao.findById(10L)).thenReturn(vm);
        when(operations.vmManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(7L, 8L));
        ssd = pool(5L, "ssd");
        hdd = pool(6L, "hdd");
        when(operations.poolDao.findById(5L)).thenReturn(ssd);
        when(operations.poolDao.findById(6L)).thenReturn(hdd);
        when(operations.inventory.reaches(any(), eq(7L), anyBoolean())).thenReturn(true);
        standardSsd = mock(DiskOfferingVO.class);
        standardHdd = mock(DiskOfferingVO.class);
        when(standardSsd.getId()).thenReturn(60L);
        when(standardHdd.getId()).thenReturn(61L);
        when(operations.diskOfferingDao.findById(60L)).thenReturn(standardSsd);
        when(operations.diskOfferingDao.findById(61L)).thenReturn(standardHdd);
        when(operations.diskOfferingDao.findByIdIncludingRemoved(60L)).thenReturn(standardSsd);
        when(operations.volumeSync.pool(3L, standardSsd)).thenReturn(ssd);
        when(operations.volumeSync.pool(3L, standardHdd)).thenReturn(hdd);
        when(operations.volumeSync.offering(hdd)).thenReturn(standardHdd);
        volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(70L);
        when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volume.getState()).thenReturn(Volume.State.Ready);
        when(volume.getInstanceId()).thenReturn(10L);
        when(volume.getPath()).thenReturn("ssd:vm-110-disk-1");
        when(volume.getSize()).thenReturn(100 * GIB);
        when(volume.getPoolId()).thenReturn(5L);
        when(volume.getDataCenterId()).thenReturn(3L);
        when(volume.getDiskOfferingId()).thenReturn(60L);
        when(operations.volumeDao.findById(70L)).thenReturn(volume);
        when(operations.volumeSync.slot(70L)).thenReturn("scsi1");
    }

    @Test
    public void addCreatesTheDiskOnTheStorageOfTheOfferingAndRecordsWhatWasCreated() {
        when(volume.getState()).thenReturn(Volume.State.Allocated);
        when(volume.getInstanceId()).thenReturn(null);
        when(volume.getSize()).thenReturn(50 * GIB);
        answer(true, "{\"status\":\"success\",\"device\":\"scsi2\",\"volume\":\"ssd:vm-110-disk-9\",\"storage\":\"ssd\",\"size_bytes\":" + 50 * GIB + "}");

        operations.add(vm, volume);

        Map<String, Object> parameters = sent("adddisk");
        assertEquals("ssd", parameters.get("storage"));
        assertEquals(50L, parameters.get("sizegb"));
        verify(operations.volumeSync).attached(volume, vm, ssd, "ssd:vm-110-disk-9", "scsi2");
    }

    @Test
    public void addIsRefusedForAnythingButANewDataVolumeOnStorageTheClusterReaches() {
        assertThrows(InvalidParameterValueException.class, () -> operations.add(vm, volume));
        when(volume.getState()).thenReturn(Volume.State.Allocated);
        when(volume.getInstanceId()).thenReturn(null);
        when(volume.getSize()).thenReturn(GIB + 1);
        assertThrows(InvalidParameterValueException.class, () -> operations.add(vm, volume));
        when(volume.getSize()).thenReturn(GIB);
        when(operations.volumeSync.pool(3L, standardSsd)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> operations.add(vm, volume));
        when(operations.volumeSync.pool(3L, standardSsd)).thenReturn(ssd);
        when(operations.inventory.reaches(ssd, 7L, true)).thenReturn(false);
        assertThrows(InvalidParameterValueException.class, () -> operations.add(vm, volume));
        when(operations.inventory.reaches(ssd, 7L, true)).thenReturn(true);
        when(vm.getState()).thenReturn(VirtualMachine.State.Migrating);
        assertThrows(InvalidParameterValueException.class, () -> operations.add(vm, volume));
        verifyNoInteractions(operations.extensionsManager);
    }

    @Test
    public void aFailedOrUnconfirmedChangeRecordsNothing() {
        when(volume.getState()).thenReturn(Volume.State.Allocated);
        when(volume.getInstanceId()).thenReturn(null);
        when(volume.getSize()).thenReturn(50 * GIB);
        answer(false, "task failed");
        assertThrows(CloudRuntimeException.class, () -> operations.add(vm, volume));
        for (String odd : new String[] {"not json", "{\"status\":\"error\"}",
                "{\"status\":\"success\",\"device\":\"scsi2\",\"volume\":\"hdd:vm-110-disk-9\",\"size_bytes\":" + 50 * GIB + "}",
                "{\"status\":\"success\",\"device\":\"scsi2\",\"volume\":\"ssd:vm-110-disk-9\",\"size_bytes\":" + 40 * GIB + "}"}) {
            answer(true, odd);
            assertThrows(CloudRuntimeException.class, () -> operations.add(vm, volume));
        }
        verify(operations.volumeSync, never()).attached(any(), any(), any(), any(), any());
    }

    @Test
    public void growChecksTheLimitThenGrowsTheRecordedDisk() throws Exception {
        answer(true, "{\"status\":\"success\",\"device\":\"scsi1\",\"volume\":\"ssd:vm-110-disk-1\",\"storage\":\"ssd\",\"size_bytes\":" + 150 * GIB + "}");

        operations.grow(volume, 150 * GIB);

        Map<String, Object> parameters = sent("resizedisk");
        assertEquals("scsi1", parameters.get("device"));
        assertEquals("ssd:vm-110-disk-1", parameters.get("volume"));
        assertEquals(150L, parameters.get("sizegb"));
        verify(operations.resourceLimits).checkVolumeResourceLimitForDiskOfferingChange(any(), any(), eq(100 * GIB), eq(150 * GIB), eq(standardSsd), eq(standardSsd), any());
        verify(operations.volumeSync).resize(volume, 150 * GIB);
    }

    @Test
    public void growIsRefusedWhenSmallerUnevenOverTheMaximumOrOverALimit() throws Exception {
        for (long size : new long[] {100 * GIB, 50 * GIB, 150 * GIB + 1, 2000 * GIB}) {
            assertThrows(InvalidParameterValueException.class, () -> operations.grow(volume, size));
        }
        doThrow(new ResourceAllocationException("over", null)).when(operations.resourceLimits)
                .checkVolumeResourceLimitForDiskOfferingChange(any(), any(), anyLong(), anyLong(), any(), any(), any());
        assertThrows(InvalidParameterValueException.class, () -> operations.grow(volume, 150 * GIB));
        when(operations.volumeSync.slot(70L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> operations.grow(volume, 150 * GIB));
        verifyNoInteractions(operations.extensionsManager);
        verify(operations.volumeSync, never()).resize(any(), anyLong());
    }

    @Test
    public void noDiskOfAnInstanceWithSnapshotsIsChanged() {
        when(operations.vmSnapshotDao.findByVm(10L)).thenReturn(java.util.List.of(mock(com.cloud.vm.snapshot.VMSnapshotVO.class)));
        assertThrows(InvalidParameterValueException.class, () -> operations.add(vm, volume));
        assertThrows(InvalidParameterValueException.class, () -> operations.grow(volume, 150 * GIB));
        assertThrows(InvalidParameterValueException.class, () -> operations.remove(vm, volume));
        assertThrows(InvalidParameterValueException.class, () -> operations.move(volume, hdd));
        assertThrows(InvalidParameterValueException.class, () -> operations.changeOffering(volume, standardHdd, null, true));
        verifyNoInteractions(operations.extensionsManager);
    }

    @Test
    public void removeDeletesADataDiskAndLeavesItsRecordDetachedButNeverTheRootDisk() {
        answer(true, "{\"status\":\"success\",\"device\":\"scsi1\",\"volume\":\"ssd:vm-110-disk-1\"}");

        operations.remove(vm, volume);

        assertEquals("ssd:vm-110-disk-1", sent("deletedisk").get("volume"));
        verify(operations.volumeSync).detached(volume);

        when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        assertThrows(InvalidParameterValueException.class, () -> operations.remove(vm, volume));
        verify(operations.volumeSync).detached(volume);
    }

    @Test
    public void moveTakesTheDestinationsOfferingAndChangeOfferingMovesThenGrows() {
        answer(true, "{\"status\":\"success\",\"device\":\"scsi1\",\"volume\":\"hdd:vm-110-disk-5\",\"storage\":\"hdd\",\"size_bytes\":" + 100 * GIB + "}");

        operations.move(volume, hdd);

        assertEquals("hdd", sent("movedisk").get("storage"));
        verify(operations.volumeSync).moved(volume, hdd, standardHdd, "hdd:vm-110-disk-5");
        verifyNoInteractions(operations.resourceLimits);
        assertEquals("The instance is locked or is a template",
                ExternalVolumeOperations.reason("Exit code: 90, Output: {\n  \"status\": \"error\",\n  \"error\": \"The instance is locked or is a template\"\n}\n"));
        assertEquals("no json here", ExternalVolumeOperations.reason("no json here"));

        assertThrows(InvalidParameterValueException.class, () -> operations.move(volume, ssd));
        when(operations.volumeSync.offering(hdd)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> operations.move(volume, hdd));
        assertThrows(InvalidParameterValueException.class, () -> operations.changeOffering(volume, standardHdd, 50 * GIB, true));
        when(operations.volumeSync.pool(3L, standardHdd)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> operations.changeOffering(volume, standardHdd, null, true));
    }

    @Test
    public void changeOfferingMovesOnlyWhenAskedToAndNothingRunsWhileTheInstanceIsBusy() {
        assertThrows(InvalidParameterValueException.class, () -> operations.changeOffering(volume, standardHdd, null, false));
        assertEquals(true, operations.offered(volume, 61L));
        assertEquals(false, operations.offered(volume, 99L));
        when(operations.instanceLock.hold(eq(10L), anyInt(), any())).thenThrow(new InvalidParameterValueException("busy"));
        assertThrows(InvalidParameterValueException.class, () -> operations.grow(volume, 150 * GIB));
        assertThrows(InvalidParameterValueException.class, () -> operations.remove(vm, volume));
        assertThrows(InvalidParameterValueException.class, () -> operations.move(volume, hdd));
        verifyNoInteractions(operations.extensionsManager);
    }
}
