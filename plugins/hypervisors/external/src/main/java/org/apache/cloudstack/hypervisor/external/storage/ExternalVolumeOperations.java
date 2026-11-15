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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.agent.api.Answer;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.ExternalInstanceDisks;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.DiskOffering;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Add, grow, delete and move a disk of an External instance. The orchestrator makes the change; the volume
 * record, the account's counts and the usage records follow only a change that is known to have succeeded.
 * When the outcome is not known nothing is recorded, and the next refresh of the instance records what is there.
 */
public class ExternalVolumeOperations implements ExternalInstanceDisks {
    private static final long GIB = 1L << 30;

    @Inject VolumeDao volumeDao;
    @Inject PrimaryDataStoreDao poolDao;
    @Inject DiskOfferingDao diskOfferingDao;
    @Inject VMInstanceDao vmDao;
    @Inject AccountService accountService;
    @Inject ResourceLimitService resourceLimits;
    @Inject VirtualMachineManager vmManager;
    @Inject ExtensionsManager extensionsManager;
    @Inject ExternalStorageInventory inventory;
    @Inject ExternalVolumeSync volumeSync;
    @Inject ExternalInstanceLock instanceLock;
    @Inject VMSnapshotDao vmSnapshotDao;
    @Inject ExternalVolumeSnapshots volumeSnapshots;

    /** How long a disk change waits for a refresh or another disk change of the instance to finish. */
    private static final int LOCK_WAIT_SECONDS = 30;

    private static InvalidParameterValueException reject(String why) {
        return new InvalidParameterValueException(why);
    }

    protected long maxCustomSizeGib() {
        return VolumeOrchestrationService.CustomDiskOfferingMaxSize.value();
    }

    private long cluster(VirtualMachine vm) {
        if (vm.getHypervisorType() != HypervisorType.External || vm.getState() != VirtualMachine.State.Running && vm.getState() != VirtualMachine.State.Stopped) {
            throw reject("The instance must be an External instance that is running or stopped");
        }
        Pair<Long, Long> place = vmManager.findClusterAndHostIdForVm(vm, false);
        if (place == null || place.first() == null) {
            throw reject("The instance has no cluster");
        }
        return place.first();
    }

    /** The recorded volume of a disk that is on an External instance now. */
    private VolumeVO onInstance(Volume given) {
        VolumeVO volume = volumeDao.findById(given.getId());
        if (volume == null || volume.getState() != Volume.State.Ready || volume.getInstanceId() == null || volume.getPath() == null
                || volume.getSize() == null || volumeSync.slot(volume.getId()) == null) {
            throw reject("The volume is not a recorded disk of an instance; refresh the instance");
        }
        return volume;
    }

    private JsonObject run(VirtualMachine vm, String action, Map<String, Object> parameters, String what) {
        Answer answer = extensionsManager.changeInstanceDisk(vm, action, parameters);
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException(String.format("Unable to %s on the external system: %s", what, answer == null ? "no answer" : reason(answer.getDetails())));
        }
        try {
            JsonObject result = JsonParser.parseString(answer.getDetails()).getAsJsonObject();
            if (!"success".equals(result.get("status").getAsString())) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (RuntimeException e) {
            throw new CloudRuntimeException("The external system did not say what it did to " + what + "; refresh the instance");
        }
    }

    /** The extension's own error text when its output carries one; its output otherwise. */
    public static String reason(String details) {
        try {
            String json = details.substring(details.indexOf('{'), details.lastIndexOf('}') + 1);
            return JsonParser.parseString(json).getAsJsonObject().get("error").getAsString();
        } catch (RuntimeException e) {
            return details;
        }
    }

    /** The disk the orchestrator reports after a change, checked against what was asked for. */
    private String changed(JsonObject result, StoragePoolVO pool, long sizeBytes, String what) {
        try {
            String path = result.get("volume").getAsString();
            if (path.startsWith(pool.getPath() + ":") && path.length() <= 255 && result.get("size_bytes").getAsLong() == sizeBytes
                    && result.get("device").getAsString().matches("(scsi|sata|virtio|ide)(0|[1-9][0-9]?)")) {
                return path;
            }
        } catch (RuntimeException e) {
            // reported below
        }
        throw new CloudRuntimeException("The external system did not " + what + " as asked; refresh the instance");
    }

    private StoragePoolVO reachable(StoragePoolVO pool, long clusterId) {
        if (pool == null || !ExternalPrimaryDataStoreProvider.NAME.equals(pool.getStorageProviderName()) || pool.getRemoved() != null
                || pool.getStatus() != StoragePoolStatus.Up) {
            throw reject("That is not External storage that is Up");
        }
        if (!inventory.reaches(pool, clusterId, true)) {
            throw reject("The instance's cluster does not reach that storage");
        }
        return pool;
    }

    private interface Change {
        Volume make();
    }

    /** Runs a change that raises what the account holds, refusing it first if that would pass a limit. */
    private Volume withinLimits(VolumeVO volume, long newSize, DiskOfferingVO from, DiskOfferingVO to, Change change) {
        Account owner = accountService.getAccount(volume.getAccountId());
        List<Reserver> reservations = new ArrayList<>();
        try {
            resourceLimits.checkVolumeResourceLimitForDiskOfferingChange(owner, volume.isDisplayVolume(), volume.getSize(), newSize, from, to, reservations);
            return change.make();
        } catch (ResourceAllocationException e) {
            throw reject("The account's resource limit would be exceeded: " + e.getMessage());
        } finally {
            for (Reserver reservation : reservations) {
                try {
                    reservation.close();
                } catch (Exception ignored) {
                    // nothing to release
                }
            }
        }
    }

    /** The instance a recorded volume is on, to lock it; the volume is read again once it is locked. */
    /**
     * A snapshot of the instance holds its disks as they were. Reverting to it would bring back a disk deleted
     * since, lose one added since, and return a moved or grown disk to where and what it was, so the disks are
     * left alone until the snapshots are deleted.
     */
    private VirtualMachine withoutSnapshots(VirtualMachine vm) {
        if (!vmSnapshotDao.findByVm(vm.getId()).isEmpty()) {
            throw reject("The disks of an instance with Instance Snapshots cannot be changed; delete its Instance Snapshots first");
        }
        return vm;
    }

    private Volume withoutSnapshots(Volume given) {
        if (!vmSnapshotDao.findByVm(instanceOf(given)).isEmpty()) {
            throw reject("The disks of an instance with Instance Snapshots cannot be changed; delete its Instance Snapshots first");
        }
        if (volumeSnapshots.held(given)) {
            throw reject("A disk with snapshots cannot be grown or moved; delete its snapshots first");
        }
        return given;
    }

    private long instanceOf(Volume given) {
        VolumeVO volume = volumeDao.findById(given.getId());
        if (volume == null || volume.getInstanceId() == null) {
            throw reject("The volume is not a recorded disk of an instance; refresh the instance");
        }
        return volume.getInstanceId();
    }

    @Override
    public Volume add(VirtualMachine vm, Volume given) {
        return instanceLock.hold(vm.getId(), LOCK_WAIT_SECONDS, () -> addLocked(withoutSnapshots(vm), given));
    }

    private Volume addLocked(VirtualMachine vm, Volume given) {
        VolumeVO volume = volumeDao.findById(given.getId());
        if (volume == null || volume.getVolumeType() != Volume.Type.DATADISK || volume.getState() != Volume.State.Allocated || volume.getInstanceId() != null) {
            throw reject("Only a new data volume that has never been attached can be added to an External instance");
        }
        long size = volume.getSize() == null ? 0 : volume.getSize();
        if (size <= 0 || size % GIB != 0) {
            throw reject("The size of a disk of an External instance is a whole number of GiB");
        }
        long clusterId = cluster(vm);
        StoragePoolVO pool = volumeSync.pool(vm.getDataCenterId(), diskOfferingDao.findById(volume.getDiskOfferingId()));
        if (pool == null) {
            throw reject("The volume's disk offering is not for one External storage");
        }
        reachable(pool, clusterId);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("storage", pool.getPath());
        parameters.put("sizegb", size / GIB);
        JsonObject result = run(vm, "adddisk", parameters, "add the disk");
        volumeSync.attached(volume, vmDao.findById(vm.getId()), pool, changed(result, pool, size, "add the disk"), result.get("device").getAsString());
        return volumeDao.findById(volume.getId());
    }

    @Override
    public Volume grow(Volume given, long newSizeBytes) {
        return instanceLock.hold(instanceOf(given), LOCK_WAIT_SECONDS, () -> growLocked(withoutSnapshots(given), newSizeBytes));
    }

    private Volume growLocked(Volume given, long newSizeBytes) {
        VolumeVO volume = onInstance(given);
        VMInstanceVO vm = vmDao.findById(volume.getInstanceId());
        cluster(vm);
        if (newSizeBytes % GIB != 0 || newSizeBytes <= volume.getSize()) {
            throw reject("A disk of an External instance can only be made larger, by a whole number of GiB");
        }
        if (newSizeBytes / GIB > maxCustomSizeGib()) {
            throw reject("The size is over the largest custom disk size allowed: " + maxCustomSizeGib() + " GiB");
        }
        DiskOfferingVO offering = diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId());
        StoragePoolVO pool = poolDao.findById(volume.getPoolId());
        return withinLimits(volume, newSizeBytes, offering, offering, () -> {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("device", volumeSync.slot(volume.getId()));
            parameters.put("volume", volume.getPath());
            parameters.put("sizegb", newSizeBytes / GIB);
            JsonObject result = run(vm, "resizedisk", parameters, "grow the disk");
            if (!volume.getPath().equals(changed(result, pool, newSizeBytes, "grow the disk"))) {
                throw new CloudRuntimeException("The external system grew another disk; refresh the instance");
            }
            volumeSync.resize(volume, newSizeBytes);
            return volumeDao.findById(volume.getId());
        });
    }

    @Override
    public void remove(VirtualMachine vm, Volume given) {
        instanceLock.hold(vm.getId(), LOCK_WAIT_SECONDS, () -> {
            removeLocked(withoutSnapshots(vm), given);
            return null;
        });
    }

    private void removeLocked(VirtualMachine vm, Volume given) {
        VolumeVO volume = onInstance(given);
        if (volume.getVolumeType() != Volume.Type.DATADISK || volume.getInstanceId() != vm.getId()) {
            throw reject("The root disk of an External instance is removed with the instance");
        }
        cluster(vm);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("device", volumeSync.slot(volume.getId()));
        parameters.put("volume", volume.getPath());
        run(vm, "deletedisk", parameters, "delete the disk");
        volumeSync.detached(volume);
    }

    @Override
    public Volume move(Volume given, StoragePool destination) {
        return instanceLock.hold(instanceOf(given), LOCK_WAIT_SECONDS, () -> moveLocked(withoutSnapshots(given), destination));
    }

    private Volume moveLocked(Volume given, StoragePool destination) {
        VolumeVO volume = onInstance(given);
        VMInstanceVO vm = vmDao.findById(volume.getInstanceId());
        StoragePoolVO pool = reachable(poolDao.findById(destination.getId()), cluster(vm));
        if (pool.getId() == volume.getPoolId() || pool.getDataCenterId() != volume.getDataCenterId()) {
            throw reject("The disk is already on that storage, or the storage is in another zone");
        }
        DiskOfferingVO to = volumeSync.offering(pool);
        if (to == null) {
            throw reject("That storage has no single custom disk offering tagged for it");
        }
        // A move adds nothing to what the account holds, so no limit is asked. An account that is over a
        // limit, as one can be after its existing disks were recorded, can still move a disk.
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("device", volumeSync.slot(volume.getId()));
        parameters.put("volume", volume.getPath());
        parameters.put("storage", pool.getPath());
        JsonObject result = run(vm, "movedisk", parameters, "move the disk");
        volumeSync.moved(volume, pool, to, changed(result, pool, volume.getSize(), "move the disk"));
        return volumeDao.findById(volume.getId());
    }

    @Override
    public boolean offered(Volume volume, long diskOfferingId) {
        return volumeSync.pool(volume.getDataCenterId(), diskOfferingDao.findById(diskOfferingId)) != null;
    }

    @Override
    public Volume changeOffering(Volume given, DiskOffering offering, Long newSizeBytes, boolean moveAllowed) {
        return instanceLock.hold(instanceOf(given), LOCK_WAIT_SECONDS, () -> changeOfferingLocked(withoutSnapshots(given), offering, newSizeBytes, moveAllowed));
    }

    private Volume changeOfferingLocked(Volume given, DiskOffering offering, Long newSizeBytes, boolean moveAllowed) {
        VolumeVO volume = onInstance(given);
        StoragePoolVO pool = volumeSync.pool(volume.getDataCenterId(), diskOfferingDao.findById(offering.getId()));
        if (pool == null) {
            throw reject("That disk offering is not for one External storage");
        }
        if (newSizeBytes != null && newSizeBytes < volume.getSize()) {
            throw reject("A disk of an External instance cannot be made smaller");
        }
        Volume result = volume;
        if (pool.getId() != volume.getPoolId()) {
            if (!moveAllowed) {
                throw reject("That disk offering is for other storage, so the disk would be moved; allow the volume to be migrated to make the change");
            }
            result = moveLocked(volume, pool);
        }
        if (newSizeBytes != null && newSizeBytes > volume.getSize()) {
            result = growLocked(result, newSizeBytes);
        }
        return result;
    }
}
