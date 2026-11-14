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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.hypervisor.external.refresh.ExternalVmConfiguration;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.configuration.Resource;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.VMInstanceVO;
import com.google.gson.annotations.SerializedName;

/**
 * Makes CloudStack's volume records match the disks an External instance has at the provider. It writes to CloudStack
 * only and never to the provider. A disk is matched by its pool and the provider's volume identifier, never by name.
 * A disk the provider no longer lists is reported, not removed.
 */
public class ExternalVolumeSync {
    /** Volume detail holding the provider slot of the disk, for example scsi1. */
    public static final String SLOT_DETAIL = "externalSlot";

    public static final class Change {
        @SerializedName("device") final String device;
        @SerializedName("volume") final String volumeId;
        @SerializedName("action") final String action;
        @SerializedName("type") final String type;
        @SerializedName("previousbytes") final Long previousBytes;
        @SerializedName("bytes") final long bytes;
        @SerializedName("diskoffering") final String diskOffering;
        @SerializedName("applied") final boolean applied;
        Change(String device, String volumeId, String action, String type, Long previousBytes, long bytes, String diskOffering, boolean applied) {
            this.device = device;
            this.volumeId = volumeId;
            this.action = action;
            this.type = type;
            this.previousBytes = previousBytes;
            this.bytes = bytes;
            this.diskOffering = diskOffering;
            this.applied = applied;
        }
        public String getAction() { return action; }
        public String getVolumeId() { return volumeId; }
        public String getType() { return type; }
        public long getBytes() { return bytes; }
        public Long getPreviousBytes() { return previousBytes; }
    }

    @Inject VolumeDao volumeDao;
    @Inject VolumeDetailsDao volumeDetailsDao;
    @Inject PrimaryDataStoreDao poolDao;
    @Inject DiskOfferingDao diskOfferingDao;
    @Inject VMTemplateDao templateDao;
    @Inject VolumeOrchestrationService volumeManager;
    @Inject ResourceLimitService resourceLimits;
    @Inject AccountService accountService;

    private static boolean live(VolumeVO volume) {
        return volume.getRemoved() == null && volume.getState() != Volume.State.Destroy
                && volume.getState() != Volume.State.Expunging && volume.getState() != Volume.State.Expunged;
    }

    /**
     * @param vm null for an instance CloudStack has not recorded yet; only a dry run is possible then
     * @param unresolved receives one line for everything seen and deliberately left alone
     * @return the imports and size changes, applied unless dryRun
     */
    public List<Change> sync(VMInstanceVO vm, long clusterId, ExternalVmConfiguration snapshot, boolean dryRun, List<String> unresolved) {
        return sync(vm, vm == null ? null : accountService.getAccount(vm.getAccountId()), clusterId, snapshot, dryRun, unresolved);
    }

    /** @param owner the account the volumes are, or would be, counted against; null when it is not known */
    public List<Change> sync(VMInstanceVO vm, Account owner, long clusterId, ExternalVmConfiguration snapshot, boolean dryRun, List<String> unresolved) {
        Map<String, StoragePoolVO> pools = new HashMap<>();
        for (StoragePoolVO pool : poolDao.listPoolsByCluster(clusterId)) {
            if (ExternalPrimaryDataStoreProvider.NAME.equals(pool.getStorageProviderName()) && pool.getRemoved() == null) {
                pools.put(pool.getPath(), pool);
            }
        }
        if (vm == null && !dryRun) {
            throw new IllegalArgumentException("Volumes can only be recorded for a recorded instance");
        }
        List<VolumeVO> recorded = new ArrayList<>();
        Set<Long> deviceIds = new HashSet<>();
        boolean hasRoot = false;
        for (VolumeVO volume : vm == null ? new ArrayList<VolumeVO>() : volumeDao.findByInstance(vm.getId())) {
            if (!live(volume)) {
                continue;
            }
            recorded.add(volume);
            if (volume.getDeviceId() != null) {
                deviceIds.add(volume.getDeviceId());
            }
            hasRoot |= volume.getVolumeType() == Volume.Type.ROOT;
        }
        // The root disk first, so that it takes device 0 when everything is imported at once.
        List<ExternalVmConfiguration.Disk> disks = new ArrayList<>(snapshot.diskList);
        disks.sort((a, b) -> Boolean.compare(b.root, a.root));
        List<Change> changes = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ExternalVmConfiguration.Disk disk : disks) {
            String id = disk.volumeId();
            if (id == null) {
                unresolved.add("disk " + disk.device + " is not on a storage; not recorded");
                continue;
            }
            StoragePoolVO pool = pools.get(disk.storage);
            if (pool == null) {
                unresolved.add("disk " + disk.device + " is on storage " + disk.storage + ", which is not registered; not recorded");
                continue;
            }
            if (disk.sizeBytes == null) {
                unresolved.add("disk " + disk.device + " has no size at the provider; not recorded");
                continue;
            }
            VolumeVO volume = volumeDao.findByPoolIdAndPath(pool.getId(), id);
            if (volume != null && (vm == null || !live(volume) || volume.getInstanceId() == null || volume.getInstanceId() != vm.getId())) {
                unresolved.add("disk " + disk.device + " (" + id + ") is recorded for another instance or is being removed; report-only");
                continue;
            }
            if (volume == null) {
                DiskOfferingVO offering = offering(pool);
                if (offering == null) {
                    unresolved.add("storage " + disk.storage + " has no single custom disk offering tagged for it; disk " + disk.device + " not recorded");
                    continue;
                }
                boolean root = disk.root && !hasRoot;
                if (disk.root && hasRoot) {
                    unresolved.add("the provider's boot disk " + disk.device + " is not the recorded root volume; recorded as a data disk");
                }
                long deviceId = 0;
                if (!root) {
                    deviceId = 1;
                    while (deviceIds.contains(deviceId)) {
                        deviceId++;
                    }
                }
                deviceIds.add(deviceId);
                hasRoot |= root;
                if (!dryRun) {
                    create(vm, pool, offering, disk, id, root, deviceId);
                }
                changes.add(new Change(disk.device, id, "import", root ? "ROOT" : "DATADISK", null, disk.sizeBytes, offering.getName(), !dryRun));
                continue;
            }
            seen.add(volume.getId());
            if (!Objects.equals(volume.getSize(), disk.sizeBytes)) {
                changes.add(new Change(disk.device, id, "resize", volume.getVolumeType().toString(), volume.getSize(), disk.sizeBytes, null, !dryRun));
                if (!dryRun) {
                    resize(volume, disk.sizeBytes);
                }
            }
            if (!dryRun) {
                saveSlot(volume.getId(), disk.device);
            }
        }
        for (VolumeVO volume : recorded) {
            if (!seen.contains(volume.getId())) {
                unresolved.add("volume " + volume.getUuid() + " (" + volume.getPath() + ") is not in the provider configuration; report-only");
            }
        }
        if (owner != null && !changes.isEmpty()) {
            long volumes = 0;
            long bytes = 0;
            for (Change change : changes) {
                volumes += change.previousBytes == null ? 1 : 0;
                bytes += change.bytes - (change.previousBytes == null ? 0 : change.previousBytes);
            }
            overLimit(owner, ResourceType.volume, dryRun ? volumes : 0, "volumes", unresolved);
            overLimit(owner, ResourceType.primary_storage, dryRun ? bytes : 0, "bytes of primary storage", unresolved);
        }
        return changes;
    }

    /**
     * The disks exist whatever the account's limits are, so a limit never stops them being recorded. It is
     * reported, so that the limit can be raised or left to stop the account creating anything more.
     */
    private void overLimit(Account owner, ResourceType type, long toBeAdded, String what, List<String> unresolved) {
        long limit = resourceLimits.findCorrectResourceLimitForAccount(owner, type, null);
        long count = resourceLimits.getResourceCount(owner, type, null) + toBeAdded;
        if (limit != Resource.RESOURCE_UNLIMITED && count > limit) {
            unresolved.add("account " + owner.getAccountName() + (toBeAdded > 0 ? " would hold " : " holds ") + count + " " + what
                    + ", over its limit of " + limit + "; existing disks are recorded regardless");
        }
    }

    /** The one active custom-size disk offering whose storage tags the pool carries; null when none or several. */
    DiskOfferingVO offering(StoragePoolVO pool) {
        List<String> poolTags = poolDao.searchForStoragePoolTags(pool.getId());
        DiskOfferingVO match = null;
        for (DiskOfferingVO offering : diskOfferingDao.listAllActiveAndNonComputeDiskOfferings()) {
            String tags = offering.getTags();
            if (!offering.isCustomized() || tags == null || tags.trim().isEmpty() || poolTags == null
                    || !poolTags.containsAll(Arrays.asList(offering.getTagsArray()))) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = offering;
        }
        return match;
    }

    /** The record, usage event and resource count that creating the volume through the API would leave. No limit is checked: the disk exists. */
    private void create(VMInstanceVO vm, StoragePoolVO pool, DiskOfferingVO offering, ExternalVmConfiguration.Disk disk, String id, boolean root, long deviceId) {
        VMTemplateVO template = root ? templateDao.findByIdIncludingRemoved(vm.getTemplateId()) : null;
        String name = root ? "ROOT-" + vm.getId() : "DATA-" + vm.getId() + "-" + disk.device;
        DiskProfile profile = volumeManager.importVolume(root ? Volume.Type.ROOT : Volume.Type.DATADISK, name, offering, disk.sizeBytes, null, null,
                vm.getDataCenterId(), HypervisorType.External, vm, template, accountService.getAccount(vm.getAccountId()), deviceId,
                pool.getId(), pool.getPoolType(), id, null);
        VolumeVO volume = volumeDao.findById(profile.getVolumeId());
        volumeDetailsDao.addDetail(volume.getId(), SLOT_DETAIL, disk.device, false);
        publish(EventTypes.EVENT_VOLUME_CREATE, volume, volume.getSize());
        resourceLimits.incrementVolumeResourceCount(volume.getAccountId(), volume.isDisplayVolume(), volume.getSize(), offering);
    }

    private void resize(VolumeVO volume, long size) {
        long previous = volume.getSize() == null ? 0 : volume.getSize();
        DiskOfferingVO offering = diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId());
        volume.setSize(size);
        volumeDao.update(volume.getId(), volume);
        if (size > previous) {
            resourceLimits.incrementVolumePrimaryStorageResourceCount(volume.getAccountId(), volume.isDisplayVolume(), size - previous, offering);
        } else {
            resourceLimits.decrementVolumePrimaryStorageResourceCount(volume.getAccountId(), volume.isDisplayVolume(), previous - size, offering);
        }
        publish(EventTypes.EVENT_VOLUME_RESIZE, volume, size);
    }

    protected void publish(String eventType, VolumeVO volume, long size) {
        UsageEventUtils.publishUsageEvent(eventType, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                volume.getDiskOfferingId(), volume.getTemplateId(), size, Volume.class.getName(), volume.getUuid(), volume.isDisplayVolume());
    }

    private void saveSlot(long volumeId, String device) {
        VolumeDetailVO detail = volumeDetailsDao.findDetail(volumeId, SLOT_DETAIL);
        if (detail != null && device.equals(detail.getValue())) {
            return;
        }
        if (detail != null) {
            volumeDetailsDao.removeDetail(volumeId, SLOT_DETAIL);
        }
        volumeDetailsDao.addDetail(volumeId, SLOT_DETAIL, device, false);
    }
}
