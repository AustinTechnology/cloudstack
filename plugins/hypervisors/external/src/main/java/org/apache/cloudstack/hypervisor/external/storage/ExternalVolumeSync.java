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
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.VMInstanceVO;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
        public Change(String device, String volumeId, String action, String type, Long previousBytes, long bytes, String diskOffering, boolean applied) {
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

    protected org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(getClass());

    static final long GIB = 1024L * 1024 * 1024;

    @Inject VolumeDao volumeDao;
    @Inject VolumeDetailsDao volumeDetailsDao;
    @Inject ExternalInstanceLock instanceLock;
    @Inject PrimaryDataStoreDao poolDao;
    @Inject com.cloud.dc.dao.ClusterDao clusterDao;
    @Inject ExternalStorageInventory inventory;
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

    /**
     * Every instance has a root volume record. An adoption whose boot disk would not be recorded as the root
     * volume (its storage is not registered, not reached by the cluster, or has no disk offering) is refused,
     * with the reasons the disks gave.
     */
    public static void requireRoot(List<Change> changes, List<String> unresolved) {
        for (Change change : changes) {
            if ("ROOT".equals(change.type)) {
                return;
            }
        }
        throw new InvalidParameterValueException("The instance's boot disk cannot be recorded as its root volume"
                + (unresolved.isEmpty() ? "" : ": " + String.join("; ", unresolved)));
    }

    /** @param owner the account the volumes are, or would be, counted against; null when it is not known */
    public List<Change> sync(VMInstanceVO vm, Account owner, long clusterId, ExternalVmConfiguration snapshot, boolean dryRun, List<String> unresolved) {
        Map<String, StoragePoolVO> pools = new HashMap<>();
        for (StoragePoolVO pool : poolDao.listByDataCenterId(clusterDao.findById(clusterId).getDataCenterId())) {
            if (ExternalPrimaryDataStoreProvider.NAME.equals(pool.getStorageProviderName()) && pool.getRemoved() == null) {
                pools.put(pool.getPath(), pool);
            }
        }
        // A pool is shared by every cluster that reaches its storage. Whether this cluster's storage of that
        // name is that storage is asked once for each pool.
        Map<String, Boolean> reached = new HashMap<>();
        Set<String> notCompared = new HashSet<>();
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
        Set<String> configured = new HashSet<>();
        for (ExternalVmConfiguration.Disk disk : disks) {
            if (disk.volumeId() != null) {
                configured.add(disk.volumeId());
            }
        }
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
            if (!reached.containsKey(disk.storage)) {
                Boolean same = null;
                try {
                    same = inventory.reaches(pool, clusterId, !dryRun);
                } catch (RuntimeException e) {
                    unresolved.add("storage " + disk.storage + " could not be compared with the registered storage; its disks are not recorded");
                }
                if (Boolean.FALSE.equals(same)) {
                    unresolved.add("storage " + disk.storage + " of this cluster is not the storage registered as " + pool.getName() + "; its disks are not recorded");
                }
                reached.put(disk.storage, Boolean.TRUE.equals(same));
            }
            if (!reached.get(disk.storage)) {
                notCompared.add(id);
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
            // An instance created from an ISO was allocated its root volume before it had a disk. The boot disk
            // the extension then created is that volume, when it is on the storage of the volume's offering.
            VolumeVO allocated = null;
            for (VolumeVO candidate : volume != null || !disk.root ? new ArrayList<VolumeVO>() : recorded) {
                if (candidate.getVolumeType() == Volume.Type.ROOT && candidate.getState() == Volume.State.Allocated
                        && candidate.getPath() == null && candidate.getPoolId() == null && !seen.contains(candidate.getId())) {
                    allocated = candidate;
                }
            }
            if (allocated != null) {
                DiskOfferingVO own = diskOfferingDao.findByIdIncludingRemoved(allocated.getDiskOfferingId());
                StoragePoolVO meant = pool(vm.getDataCenterId(), own);
                seen.add(allocated.getId());
                if (meant == null || meant.getId() != pool.getId()) {
                    unresolved.add("the boot disk " + disk.device + " is on storage " + disk.storage + ", not the storage of the root volume's offering; report-only");
                    continue;
                }
                changes.add(new Change(disk.device, id, "create", "ROOT", allocated.getSize(), disk.sizeBytes, own.getName(), !dryRun));
                if (!dryRun) {
                    created(allocated, pool, id, disk.device);
                    if (!Objects.equals(allocated.getSize(), disk.sizeBytes)) {
                        resize(allocated, disk.sizeBytes);
                    }
                }
                continue;
            }
            if (volume == null) {
                DiskOfferingVO offering = offering(pool);
                if (offering == null) {
                    unresolved.add("storage " + disk.storage + " has no single custom disk offering tagged for it; disk " + disk.device + " not recorded");
                    continue;
                }
                // A disk moved to other storage at the provider is the same volume: same slot, same size, and
                // the disk it was is no longer in the configuration.
                VolumeVO movedFrom = null;
                for (VolumeVO candidate : recorded) {
                    if (!seen.contains(candidate.getId()) && !configured.contains(candidate.getPath()) && disk.device.equals(slot(candidate.getId()))
                            && Objects.equals(candidate.getSize(), disk.sizeBytes)) {
                        movedFrom = candidate;
                    }
                }
                if (movedFrom != null) {
                    seen.add(movedFrom.getId());
                    changes.add(new Change(disk.device, id, "move", movedFrom.getVolumeType().toString(), disk.sizeBytes, disk.sizeBytes, offering.getName(), !dryRun));
                    if (!dryRun) {
                        moved(movedFrom, pool, offering, id);
                    }
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
                if (volume.getFormat() == null) {
                    volume.setFormat(format(id));
                    volumeDao.update(volume.getId(), volume);
                }
            }
        }
        for (VolumeVO volume : recorded) {
            if (!seen.contains(volume.getId()) && !notCompared.contains(volume.getPath())) {
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
        volume.setFormat(format(id));
        volumeDao.update(volume.getId(), volume);
        volumeDetailsDao.addDetail(volume.getId(), SLOT_DETAIL, disk.device, false);
        publish(EventTypes.EVENT_VOLUME_CREATE, volume, volume.getSize());
        resourceLimits.incrementVolumeResourceCount(volume.getAccountId(), volume.isDisplayVolume(), volume.getSize(), offering);
    }

    /**
     * Every volume has an image format: stock code that walks all volumes, such as the volume statistics task,
     * does not expect one without. A disk at the orchestrator is raw unless its file says otherwise.
     */
    static ImageFormat format(String providerVolumeId) {
        String id = providerVolumeId == null ? "" : providerVolumeId.toLowerCase();
        return id.endsWith(".qcow2") ? ImageFormat.QCOW2 : id.endsWith(".vmdk") ? ImageFormat.VMDK : ImageFormat.RAW;
    }

    void resize(VolumeVO volume, long size) {
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

    /** The provider slot recorded for a volume; null when none is. */
    String slot(long volumeId) {
        VolumeDetailVO detail = volumeDetailsDao.findDetail(volumeId, SLOT_DETAIL);
        return detail == null ? null : detail.getValue();
    }

    /** The External pool of a zone that a disk offering is for: the one Up pool that carries all its tags. */
    StoragePoolVO pool(long zoneId, DiskOfferingVO offering) {
        if (offering == null || !offering.isCustomized() || offering.getTags() == null || offering.getTags().trim().isEmpty()) {
            return null;
        }
        StoragePoolVO match = null;
        for (StoragePoolVO pool : poolDao.listByDataCenterId(zoneId)) {
            List<String> tags = ExternalPrimaryDataStoreProvider.NAME.equals(pool.getStorageProviderName()) && pool.getRemoved() == null
                    && pool.getStatus() == com.cloud.storage.StoragePoolStatus.Up ? poolDao.searchForStoragePoolTags(pool.getId()) : null;
            if (tags == null || !tags.containsAll(Arrays.asList(offering.getTagsArray()))) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = pool;
        }
        return match;
    }

    /** Records a data volume that has just been created on an instance. It was counted when it was allocated. */
    void attached(VolumeVO volume, VMInstanceVO vm, StoragePoolVO pool, String path, String device) {
        Set<Long> used = new HashSet<>();
        for (VolumeVO other : volumeDao.findByInstance(vm.getId())) {
            if (live(other) && other.getDeviceId() != null) {
                used.add(other.getDeviceId());
            }
        }
        long deviceId = 1;
        while (used.contains(deviceId)) {
            deviceId++;
        }
        volume.setPoolId(pool.getId());
        volume.setPoolType(pool.getPoolType());
        volume.setPath(path);
        volume.setFormat(format(path));
        volume.setState(Volume.State.Ready);
        volumeDao.update(volume.getId(), volume);
        volumeDao.attachVolume(volume.getId(), vm.getId(), deviceId);
        saveSlot(volume.getId(), device);
    }

    /**
     * What the extension needs to create an instance from a template so that its boot disk is the root volume the
     * instance was allocated: the storage and the size of that disk. The storage is the one of the root volume's
     * offering when that is the disk offering of an External storage; otherwise the template's storage, and the
     * volume takes that storage's offering. The size is the larger of the volume's and the template's. Nothing has
     * been created at the provider when this refuses. Empty when the instance was allocated no root volume.
     */
    public Map<String, String> templateDeployment(long clusterId, VMInstanceVO vm, String templateStorage, String templateSizeGb) {
        Map<String, String> details = new HashMap<>();
        if (!inventory.supported(clusterId)) {
            return details;
        }
        VolumeVO root = null;
        for (VolumeVO volume : volumeDao.findByInstanceAndType(vm.getId(), Volume.Type.ROOT)) {
            if (live(volume) && volume.getState() == Volume.State.Allocated && volume.getPoolId() == null && volume.getPath() == null) {
                root = volume;
            }
        }
        if (root == null) {
            return details;
        }
        DiskOfferingVO own = diskOfferingDao.findByIdIncludingRemoved(root.getDiskOfferingId());
        StoragePoolVO pool = pool(vm.getDataCenterId(), own);
        DiskOfferingVO offering = own;
        if (pool == null) {
            StoragePoolVO named = null;
            for (StoragePoolVO candidate : poolDao.listByDataCenterId(vm.getDataCenterId())) {
                if (ExternalPrimaryDataStoreProvider.NAME.equals(candidate.getStorageProviderName()) && candidate.getRemoved() == null
                        && candidate.getStatus() == com.cloud.storage.StoragePoolStatus.Up && candidate.getPath().equals(templateStorage)) {
                    named = candidate;
                }
            }
            offering = named == null ? null : offering(named);
            if (offering == null) {
                throw new InvalidParameterValueException("The root disk needs an External storage: the root disk offering is not the disk offering of one, and the template's storage "
                        + (templateStorage == null || templateStorage.isEmpty() ? "is not set" : templateStorage + " is not a registered External storage with one disk offering"));
            }
            pool = named;
        }
        if (!inventory.reaches(pool, clusterId, true)) {
            throw new InvalidParameterValueException("The instance's cluster does not reach the storage " + pool.getName() + " of its root disk");
        }
        long allocated = root.getSize() == null ? 0 : root.getSize();
        long templateBytes = templateSizeGb != null && templateSizeGb.matches("[1-9][0-9]{0,5}") ? Long.parseLong(templateSizeGb) * GIB : 0;
        long size = (Math.max(allocated, templateBytes) + GIB - 1) / GIB * GIB;
        if (size <= 0) {
            throw new InvalidParameterValueException("The root disk has no size: give a root disk size, or a template with a disk_size_gb detail");
        }
        if (size > allocated) {
            try {
                resourceLimits.checkResourceLimit(accountService.getAccount(vm.getAccountId()), ResourceType.primary_storage, size - allocated);
            } catch (com.cloud.exception.ResourceAllocationException e) {
                throw new InvalidParameterValueException("The root disk would exceed the owner's primary storage limit: " + e.getMessage());
            }
        }
        if (offering.getId() != own.getId()) {
            // The volume is counted and billed as the storage's offering from now on.
            root.setDiskOfferingId(offering.getId());
            volumeDao.update(root.getId(), root);
            resourceLimits.updateVolumeResourceCountForDiskOfferingChange(root.getAccountId(), root.isDisplayVolume(), allocated, allocated, own, offering);
            if (size == allocated) {
                publish(EventTypes.EVENT_VOLUME_RESIZE, root, size);
            }
        }
        if (size > allocated) {
            resize(root, size);
        }
        details.put("storage", pool.getPath());
        details.put("disk_size_gb", Long.toString(size / GIB));
        return details;
    }

    /** Records where the disk of an allocated root volume was created. It was counted when it was allocated. */
    void created(VolumeVO volume, StoragePoolVO pool, String path, String device) {
        volume.setPoolId(pool.getId());
        volume.setPoolType(pool.getPoolType());
        volume.setPath(path);
        volume.setFormat(format(path));
        volume.setState(Volume.State.Ready);
        volumeDao.update(volume.getId(), volume);
        saveSlot(volume.getId(), device);
    }

    /**
     * Records the disks of an instance the extension has just created, from what it reported of the new instance.
     * Never fails: what is not recorded here is recorded by the next refresh.
     */
    public void recordCreated(VMInstanceVO vm, long clusterId, String output) {
        try {
            if (output == null || !inventory.supported(clusterId)) {
                return;
            }
            String last = output.trim();
            JsonObject result = JsonParser.parseString(last.substring(last.lastIndexOf("\n") + 1)).getAsJsonObject();
            if (!result.has("config") || !result.get("config").isJsonObject()) {
                return;
            }
            ExternalVmConfiguration snapshot = ExternalVmConfiguration.parse(result.get("config").toString());
            List<String> unresolved = new ArrayList<>();
            List<Change> changes = instanceLock.hold(vm.getId(), 1, () -> sync(vm, clusterId, snapshot, false, unresolved));
            logger.info("Recorded {} disk(s) of new instance {}; left alone: {}", changes.size(), vm.getUuid(), unresolved);
        } catch (RuntimeException e) {
            logger.warn("The disks of new instance {} were not recorded and are left to a refresh: {}", vm.getUuid(), e.getMessage());
        }
    }

    /** Records that a volume's disk is now on other storage. Its offering, counts and usage follow the storage. */
    void moved(VolumeVO volume, StoragePoolVO pool, DiskOfferingVO offering, String path) {
        DiskOfferingVO previous = diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId());
        volume.setPoolId(pool.getId());
        volume.setPoolType(pool.getPoolType());
        volume.setPath(path);
        volume.setFormat(format(path));
        volume.setDiskOfferingId(offering.getId());
        volumeDao.update(volume.getId(), volume);
        if (previous == null || previous.getId() != offering.getId()) {
            resourceLimits.updateVolumeResourceCountForDiskOfferingChange(volume.getAccountId(), volume.isDisplayVolume(), volume.getSize(), volume.getSize(), previous, offering);
            publish(EventTypes.EVENT_VOLUME_RESIZE, volume, volume.getSize());
        }
    }

    void detached(VolumeVO volume) {
        volumeDao.detachVolume(volume.getId());
        volumeDetailsDao.removeDetail(volume.getId(), SLOT_DETAIL);
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
