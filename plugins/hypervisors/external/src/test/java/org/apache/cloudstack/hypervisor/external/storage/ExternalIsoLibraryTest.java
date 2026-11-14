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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateDetailsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDetailsDao;

public class ExternalIsoLibraryTest {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final String SHA512 = String.join("", Collections.nCopies(32, "0aB9"));
    private static final String COPY = "isos:iso/cloudstack-iso-uuid.iso";

    private ExternalIsoLibrary library;
    private HostVO host;
    private VMTemplateVO iso;
    private Map<String, Map<String, String>> access;
    private List<String> ran;
    private List<Map<String, Object>> sent;
    private List<String> answers;

    private ExternalIsoLibrary.Runner runner() {
        return (action, parameters, wait) -> {
            ran.add(action);
            sent.add(new HashMap<>(parameters));
            return new Pair<>(true, "noise\n" + answers.remove(0));
        };
    }

    @Before
    public void setUp() {
        library = spy(new ExternalIsoLibrary());
        library.hostDao = mock(HostDao.class);
        library.clusterDao = mock(ClusterDao.class);
        library.templateDao = mock(VMTemplateDao.class);
        library.templateDetailsDao = mock(VMTemplateDetailsDao.class);
        library.templateStoreDao = mock(TemplateDataStoreDao.class);
        library.extensionsManager = mock(ExtensionsManager.class);
        library.vmDetailsDao = mock(VMInstanceDetailsDao.class);
        library.volumeDao = mock(VolumeDao.class);
        library.diskOfferingDao = mock(DiskOfferingDao.class);
        library.guestOsDao = mock(GuestOSDao.class);
        library.volumeSync = mock(ExternalVolumeSync.class);
        library.inventory = mock(ExternalStorageInventory.class);
        library.instanceLock = mock(ExternalInstanceLock.class);
        when(library.instanceLock.hold(anyLong(), anyInt(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
        doAnswer(call -> ((Supplier<?>) call.getArgument(1)).get()).when(library).oneCopyAtATime(anyLong(), any());
        host = mock(HostVO.class);
        when(host.getId()).thenReturn(2L);
        when(host.getClusterId()).thenReturn(7L);
        when(host.getDataCenterId()).thenReturn(3L);
        when(host.getHypervisorType()).thenReturn(HypervisorType.External);
        when(host.getStatus()).thenReturn(Status.Up);
        access = new HashMap<>();
        access.put("resourcemap", new HashMap<>(Map.of("iso_storage", "isos")));
        when(library.extensionsManager.getExternalAccessDetails(host, null)).thenReturn(access);
        iso = mock(VMTemplateVO.class);
        when(iso.getId()).thenReturn(50L);
        when(iso.getUuid()).thenReturn("iso-uuid");
        when(iso.getFormat()).thenReturn(ImageFormat.ISO);
        when(iso.getChecksum()).thenReturn("{SHA-512}" + SHA512);
        when(iso.isBootable()).thenReturn(true);
        when(library.templateDao.findById(50L)).thenReturn(iso);
        TemplateDataStoreVO ref = mock(TemplateDataStoreVO.class);
        when(ref.getSize()).thenReturn(700L);
        when(ref.getExtractUrl()).thenReturn("http://198.51.100.7/userdata/abc.iso");
        when(library.templateStoreDao.findByTemplateZoneReady(50L, 3L)).thenReturn(ref);
        ran = new ArrayList<>();
        sent = new ArrayList<>();
        answers = new ArrayList<>();
    }

    @Test
    public void aChecksumIsNamedAsTheOrchestratorNamesItOrNotUsed() {
        assertArrayEquals(new String[] {"sha512", SHA512.toLowerCase()}, ExternalIsoLibrary.checksum("{SHA-512}" + SHA512));
        assertArrayEquals(new String[] {"sha256", SHA512.substring(0, 64).toLowerCase()}, ExternalIsoLibrary.checksum(SHA512.substring(0, 64)));
        assertArrayEquals(new String[] {"md5", SHA512.substring(0, 32).toLowerCase()}, ExternalIsoLibrary.checksum(" {MD5}" + SHA512.substring(0, 32) + " "));
        for (String unusable : Arrays.asList(null, "", "{SHA-512}abc", "{CRC32}0a0a0a0a", "not hex", SHA512.substring(0, 20))) {
            assertNull(unusable, ExternalIsoLibrary.checksum(unusable));
        }
    }

    @Test
    public void anIsoIsItsOwnFileOrACopyOnTheIsoStorageOfTheCluster() {
        assertEquals(COPY, library.volume(host, iso));
        when(library.templateDetailsDao.listDetailsKeyPairs(50L)).thenReturn(Map.of("iso_path", "iso-library:iso/win2025.iso"));
        assertEquals("iso-library:iso/win2025.iso", library.volume(host, iso));
        when(library.templateDetailsDao.listDetailsKeyPairs(50L)).thenReturn(Map.of("iso_path", "local:100/vm-100-disk-0.qcow2"));
        assertThrows(InvalidParameterValueException.class, () -> library.volume(host, iso));
        when(library.templateDetailsDao.listDetailsKeyPairs(50L)).thenReturn(null);
        access.get("resourcemap").clear();
        assertThrows(InvalidParameterValueException.class, () -> library.volume(host, iso));
    }

    @Test
    public void anIsoAlreadyThereIsNotCopiedAndOneThatIsNotIsCopiedWithItsChecksum() {
        answers.add("{\"status\":\"success\",\"present\":true}");
        assertEquals(COPY, library.ensure(host, iso, runner()));
        assertEquals(Collections.singletonList("fetchiso"), ran);
        assertEquals(Map.of("volume", COPY, "size", 700L), sent.get(0));

        ran.clear();
        sent.clear();
        answers.add("{\"status\":\"success\",\"present\":false}");
        answers.add("{\"status\":\"success\",\"present\":true,\"fetched\":true}");
        assertEquals(COPY, library.ensure(host, iso, runner()));
        assertEquals(Arrays.asList("fetchiso", "fetchiso"), ran);
        assertEquals(Map.of("volume", COPY, "size", 700L, "url", "http://198.51.100.7/userdata/abc.iso",
                "checksumalgorithm", "sha512", "checksum", SHA512.toLowerCase()), sent.get(1));
    }

    @Test
    public void anIsoWithNoChecksumOrNotReadyOrMissingOwnFileIsNotCopied() {
        when(iso.getChecksum()).thenReturn(null);
        answers.add("{\"status\":\"success\",\"present\":false}");
        InvalidParameterValueException refused = assertThrows(InvalidParameterValueException.class, () -> library.ensure(host, iso, runner()));
        assertTrue(refused.getMessage().contains("checksum"));
        assertEquals(1, ran.size());

        when(iso.getChecksum()).thenReturn(SHA512);
        when(library.templateStoreDao.findByTemplateZoneReady(50L, 3L)).thenReturn(null);
        answers.add("{\"status\":\"success\",\"present\":false}");
        assertThrows(InvalidParameterValueException.class, () -> library.ensure(host, iso, runner()));

        when(library.templateDetailsDao.listDetailsKeyPairs(50L)).thenReturn(Map.of("iso_path", "iso-library:iso/win2025.iso"));
        answers.add("{\"status\":\"success\",\"present\":false}");
        assertThrows(InvalidParameterValueException.class, () -> library.ensure(host, iso, runner()));
        assertEquals(3, ran.size());

        // An answer that does not say is never taken as present.
        when(library.templateDetailsDao.listDetailsKeyPairs(50L)).thenReturn(null);
        answers.add("{\"status\":\"error\"}");
        assertThrows(CloudRuntimeException.class, () -> library.ensure(host, iso, runner()));
    }

    private static DiskTO cd(long seq, Long isoId, String path) {
        TemplateObjectTO to = mock(TemplateObjectTO.class);
        when(to.getUuid()).thenReturn(isoId == null ? null : "uuid-" + isoId);
        when(to.getId()).thenReturn(isoId == null ? 0L : isoId);
        return new DiskTO(to, seq, path, Volume.Type.ISO);
    }

    @Test
    public void aStartGetsOneEntryForEachDriveCloudStackRecords() {
        doReturn(COPY).when(library).ensure(eq(host), eq(iso), any());
        DiskTO rootDisk = new DiskTO(null, 0L, null, Volume.Type.ROOT);
        List<Map<String, Object>> drives = library.drives(host, new DiskTO[] {cd(4, null, null), rootDisk, cd(3, 50L, null), cd(4, null, "/tmp/i-2-9-VM/configDrive/i-2-9-VM.iso")}, runner());
        assertEquals(2, drives.size());
        assertEquals(Map.of("drive", 0, "volume", COPY, "bootable", true), drives.get(0));
        assertEquals(Map.of("drive", 1, "volume", "", "bootable", false), drives.get(1));
        assertTrue(library.drives(host, new DiskTO[] {rootDisk}, runner()).isEmpty());
        assertTrue(library.drives(host, null, runner()).isEmpty());
        assertThrows(CloudRuntimeException.class, () -> library.drives(host, new DiskTO[] {cd(4, 50L, null)}, runner()));
        when(library.templateDao.findById(51L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> library.drives(host, new DiskTO[] {cd(3, 51L, null)}, runner()));
    }

    @Test
    public void anInstanceCreatedFromAnIsoGetsItsDiskFromItsRootVolumeAndItsFirmwareFromTheDeployment() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(10L);
        when(vm.getDataCenterId()).thenReturn(3L);
        when(vm.getGuestOSId()).thenReturn(8L);
        VolumeVO rootVolume = mock(VolumeVO.class);
        when(rootVolume.getState()).thenReturn(Volume.State.Allocated);
        when(rootVolume.getSize()).thenReturn(40 * GIB);
        when(rootVolume.getDiskOfferingId()).thenReturn(60L);
        when(library.volumeDao.findByInstanceAndType(10L, Volume.Type.ROOT)).thenReturn(Collections.singletonList(rootVolume));
        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        when(library.diskOfferingDao.findById(60L)).thenReturn(offering);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getPath()).thenReturn("ssd-ec");
        when(library.volumeSync.pool(3L, offering)).thenReturn(pool);
        when(library.inventory.reaches(pool, 7L, true)).thenReturn(true);
        GuestOSVO os = mock(GuestOSVO.class);
        when(os.getDisplayName()).thenReturn("Windows Server 2022 (64-bit)");
        when(library.guestOsDao.findById(8L)).thenReturn(os);

        assertEquals(Map.of("template_type", "ISO", "iso_os_type", "win11", "storage", "ssd-ec", "disk_size_gb", "40", "firmware", "bios"),
                library.deployment(host, vm));
        for (String[] firmware : new String[][] {{"SECURE", "uefi-secure"}, {"LEGACY", "uefi"}}) {
            VMInstanceDetailVO uefi = mock(VMInstanceDetailVO.class);
            when(uefi.getValue()).thenReturn(firmware[0]);
            when(library.vmDetailsDao.findDetail(10L, "UEFI")).thenReturn(uefi);
            assertEquals(firmware[1], library.deployment(host, vm).get("firmware"));
        }
        for (String[] kind : new String[][] {{"Windows 10 (64-bit)", "win10"}, {"Windows Server 2012 R2 (64-bit)", "win8"}, {"Ubuntu 24.04 LTS", "l26"}, {"Other (64-bit)", "other"}}) {
            when(os.getDisplayName()).thenReturn(kind[0]);
            assertEquals(kind[0], kind[1], library.deployment(host, vm).get("iso_os_type"));
        }

        // The root disk offering has to be one tier's offering, and the size a whole number of GiB.
        when(library.inventory.reaches(pool, 7L, true)).thenReturn(false);
        assertThrows(InvalidParameterValueException.class, () -> library.deployment(host, vm));
        when(library.inventory.reaches(pool, 7L, true)).thenReturn(true);
        when(library.volumeSync.pool(3L, offering)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> library.deployment(host, vm));
        when(library.volumeSync.pool(3L, offering)).thenReturn(pool);
        when(rootVolume.getSize()).thenReturn(40 * GIB + 1);
        assertThrows(InvalidParameterValueException.class, () -> library.deployment(host, vm));
    }

    private ClusterVO cluster() {
        ClusterVO cluster = mock(ClusterVO.class);
        when(cluster.getId()).thenReturn(7L);
        when(cluster.getName()).thenReturn("P3");
        when(cluster.getHypervisorType()).thenReturn(HypervisorType.External);
        when(library.clusterDao.listAll()).thenReturn(Collections.singletonList(cluster));
        when(library.hostDao.findByClusterId(7L)).thenReturn(Collections.singletonList(host));
        doReturn(true).when(library).supported(7L);
        doReturn(runner()).when(library).throughAgent(host);
        return cluster;
    }

    @Test
    public void aDeletedIsoIsEjectedFromEveryInstanceHoldingItAndThenItsCopyIsDeleted() {
        cluster();
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(10L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        doReturn(vm).when(library).instanceOf(7L, 110L);
        when(library.extensionsManager.changeInstanceIso(eq(vm), eq("detachiso"), any())).thenReturn(new Answer(null, true, "{}"));
        answers.add("{\"status\":\"success\",\"guests\":[{\"vmid\":110,\"node\":\"n1\",\"name\":\"g\",\"slots\":[\"ide2\"]}]}");
        answers.add("{\"status\":\"success\",\"removed\":true}");

        assertTrue(library.removeCopies(iso, null).isEmpty());
        assertEquals(Arrays.asList("findiso", "removeiso"), ran);
        assertEquals(Map.of("volume", COPY), sent.get(1));
        verify(library.extensionsManager).changeInstanceIso(eq(vm), eq("detachiso"), eq(Map.of("volume", COPY)));
    }

    @Test
    public void aCopyIsKeptWhenAGuestHoldingItCannotBeChangedOrACheckFails() {
        cluster();
        doReturn(null).when(library).instanceOf(7L, 110L);
        answers.add("{\"status\":\"success\",\"guests\":[{\"vmid\":110,\"node\":\"n1\",\"name\":\"g\",\"slots\":[\"ide2\"]}]}");
        assertEquals(1, library.removeCopies(iso, null).size());
        assertEquals(Collections.singletonList("findiso"), ran);
        verify(library.extensionsManager, never()).changeInstanceIso(any(), any(), any());

        ran.clear();
        answers.add("{\"status\":\"success\"}");
        assertEquals(1, library.removeCopies(iso, null).size());
        assertEquals(Collections.singletonList("findiso"), ran);

        // An ISO that is its own file on the storage was never copied and is never deleted.
        ran.clear();
        when(library.templateDetailsDao.listDetailsKeyPairs(50L)).thenReturn(Map.of("iso_path", "iso-library:iso/win2025.iso"));
        assertTrue(library.removeCopies(iso, null).isEmpty());
        assertTrue(ran.isEmpty());
        assertFalse(library.supported(mock(VirtualMachine.class)));
    }
}
