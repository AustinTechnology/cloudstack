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

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.RunCustomActionCommand;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.ExternalInstanceDiskSnapshots;
import com.cloud.hypervisor.ExternalProvisioner;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Snapshots of one disk of an External instance, where the disk is an image on Ceph. The extension takes, rolls
 * back and deletes the snapshot on the image with Ceph's own tools, as a Ceph client named in its details, and
 * copies it to secondary storage as a file. CloudStack's snapshot strategies are the stock ones: this is what the
 * External storage driver does when they call it.
 */
public class ExternalVolumeSnapshots implements ExternalInstanceDiskSnapshots {
    /** The Ceph client the extension uses, and the file its key is in. Set on the extension or on a cluster's mapping. */
    public static final String CEPH_CLIENT = "ceph_client";
    public static final String CEPH_KEYRING = "ceph_keyring";
    /** Where the rbd and qemu-img commands are, when they are not the ones on the management server's path. */
    public static final String RBD_COMMAND = "rbd_command";
    public static final String QEMU_IMG_COMMAND = "qemu_img_command";

    private static final List<String[]> SETTINGS = Arrays.asList(new String[] {CEPH_CLIENT, "cephclient"}, new String[] {CEPH_KEYRING, "cephkeyring"},
            new String[] {RBD_COMMAND, "rbdcommand"}, new String[] {QEMU_IMG_COMMAND, "qemuimgcommand"});
    private static final int LOCK_WAIT_SECONDS = 30;

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject VolumeDao volumeDao;
    @Inject PrimaryDataStoreDao poolDao;
    @Inject VMInstanceDao vmDao;
    @Inject HostDao hostDao;
    @Inject SnapshotDao snapshotDao;
    @Inject SnapshotDataStoreDao snapshotStoreDao;
    @Inject ExtensionHelper extensionHelper;
    @Inject ExtensionsManager extensionsManager;
    @Inject ExternalProvisioner externalProvisioner;
    @Inject VirtualMachineManager vmManager;
    @Inject ExternalStorageInventory inventory;
    @Inject ExternalVolumeSync volumeSync;
    @Inject ExternalInstanceLock instanceLock;
    @Inject ResourceLimitService resourceLimits;

    private static InvalidParameterValueException reject(String why) {
        return new InvalidParameterValueException(why);
    }

    /** The name of a snapshot on the image. Proxmox names its own after the instance snapshot, never like this. */
    public static String nameOnCeph(String snapshotUuid) {
        return "cloudstack-" + snapshotUuid;
    }

    private boolean onCeph(StoragePoolVO pool) {
        return pool != null && ExternalPrimaryDataStoreProvider.NAME.equals(pool.getStorageProviderName()) && pool.getPoolType() == StoragePoolType.RBD;
    }

    private Long cluster(VirtualMachine vm) {
        Pair<Long, Long> place = vmManager.findClusterAndHostIdForVm(vm, false);
        return place == null ? null : place.first();
    }

    @Override
    public String whyNot(long volumeId) {
        VolumeVO volume = volumeDao.findById(volumeId);
        VMInstanceVO vm = volume == null || volume.getInstanceId() == null ? null : vmDao.findById(volume.getInstanceId());
        if (vm == null || vm.getHypervisorType() != HypervisorType.External) {
            return "The volume is not a disk of an External hypervisor instance";
        }
        if (volume.getPoolId() == null || !onCeph(poolDao.findById(volume.getPoolId()))) {
            return "Snapshots of a disk of an External hypervisor instance need the disk on Ceph storage";
        }
        Long clusterId = cluster(vm);
        Extension extension = clusterId == null ? null : extensionHelper.getExtensionForCluster(clusterId);
        if (extension == null || !extensionHelper.isInstanceVolumeSnapshotSupported(extension.getId())) {
            return "The instance's extension does not support snapshots of a disk";
        }
        return null;
    }

    @Override
    public boolean held(Volume volume) {
        for (SnapshotVO snapshot : snapshotDao.listByVolumeId(volume.getId())) {
            if (snapshot.getState() != Snapshot.State.Destroyed && snapshot.getState() != Snapshot.State.Error && onCephRef(snapshot.getId()) != null) {
                return true;
            }
        }
        return false;
    }

    /** The record of a snapshot's copy on the image, while Ceph is taken to have it. */
    private SnapshotDataStoreVO onCephRef(long snapshotId) {
        for (SnapshotDataStoreVO ref : snapshotStoreDao.listBySnapshotAndDataStoreRole(snapshotId, DataStoreRole.Primary)) {
            if (ref.getState() != ObjectInDataStoreStateMachine.State.Destroyed && onCeph(poolDao.findById(ref.getDataStoreId()))) {
                return ref;
            }
        }
        return null;
    }

    private Map<String, Object> cephParameters(HostVO host) {
        Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
        Map<String, Object> parameters = new HashMap<>();
        for (String[] setting : SETTINGS) {
            for (String scope : Arrays.asList("resourcemap", "extension")) {
                Map<String, String> settings = access == null ? null : access.get(scope);
                String value = settings == null ? null : settings.get(setting[0]);
                if (!parameters.containsKey(setting[1]) && value != null && !value.trim().isEmpty()) {
                    parameters.put(setting[1], value.trim());
                }
            }
        }
        if (!parameters.containsKey("cephclient") || !parameters.containsKey("cephkeyring")) {
            throw reject("The extension has no Ceph client set (" + CEPH_CLIENT + ", " + CEPH_KEYRING + ")");
        }
        return parameters;
    }

    private HostVO hostOf(VirtualMachine vm) {
        Pair<Long, Long> place = vmManager.findClusterAndHostIdForVm(vm, false);
        HostVO host = place == null || place.second() == null ? null : hostDao.findById(place.second());
        if (host == null) {
            throw reject("The instance has no host");
        }
        return host;
    }

    /** A host that is Up in a cluster that reaches the storage: for work on a snapshot whose instance may be gone. */
    private HostVO hostFor(StoragePoolVO pool) {
        for (HostVO host : hostDao.listByDataCenterIdAndHypervisorType(pool.getDataCenterId(), HypervisorType.External)) {
            if (host.getHypervisorType() == HypervisorType.External && host.getStatus() == Status.Up && host.getRemoved() == null
                    && host.getClusterId() != null && inventory.reaches(pool, host.getClusterId(), false)) {
                return host;
            }
        }
        throw new CloudRuntimeException("No host that is Up reaches the storage of the snapshot");
    }

    private JsonObject result(boolean ok, String details, String what) {
        if (!ok) {
            throw new CloudRuntimeException(String.format("Unable to %s on the external system: %s", what,
                    details == null ? "no answer" : ExternalVolumeOperations.reason(details)));
        }
        try {
            String output = details.trim();
            JsonObject result = JsonParser.parseString(output.substring(output.lastIndexOf("\n") + 1)).getAsJsonObject();
            if (!"success".equals(result.get("status").getAsString())) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (RuntimeException e) {
            throw new CloudRuntimeException("The external system did not say what it did to " + what);
        }
    }

    private JsonObject onInstance(VMInstanceVO vm, String action, Map<String, Object> parameters, String what) {
        Answer answer = extensionsManager.changeInstanceDiskSnapshot(vm, action, parameters);
        return result(answer != null && answer.getResult(), answer == null ? null : answer.getDetails(), what);
    }

    /**
     * Runs an action that is on the storage, not on an instance, on this management server: the copy to secondary
     * storage writes to this server's mount of it. The host gives the cluster whose orchestrator names the storage.
     */
    protected JsonObject onStorage(HostVO host, String action, Map<String, Object> parameters, int wait, String what) {
        Extension extension = extensionHelper.getExtensionForCluster(host.getClusterId());
        if (extension == null) {
            throw new CloudRuntimeException("The cluster of the storage has no extension");
        }
        RunCustomActionCommand cmd = new RunCustomActionCommand(action);
        cmd.setParameters(parameters);
        cmd.setExternalDetails(extensionsManager.getExternalAccessDetails(host, null));
        cmd.setWait(wait);
        Answer answer = externalProvisioner.runCustomAction(host.getGuid(), extension.getName(), extension.getRelativePath(), cmd);
        return result(answer != null && answer.getResult(), answer == null ? null : answer.getDetails(), what);
    }

    private VolumeVO disk(long volumeId) {
        VolumeVO volume = volumeDao.findById(volumeId);
        String why = whyNot(volumeId);
        if (why != null) {
            throw reject(why);
        }
        if (volume.getPath() == null || volumeSync.slot(volume.getId()) == null) {
            throw reject("The volume is not a recorded disk of an instance; refresh the instance");
        }
        return volume;
    }

    private Map<String, Object> named(VMInstanceVO vm, VolumeVO volume, String snapshotUuid) {
        Map<String, Object> parameters = cephParameters(hostOf(vm));
        parameters.put("device", volumeSync.slot(volume.getId()));
        parameters.put("volume", volume.getPath());
        parameters.put("snapshot", nameOnCeph(snapshotUuid));
        return parameters;
    }

    /** Where a snapshot is on Ceph, as recorded with it: the disk's volume at the orchestrator, then its name there. */
    static String path(String volume, String snapshotUuid) {
        return volume + "@" + nameOnCeph(snapshotUuid);
    }

    /**
     * Takes the snapshot on the disk's image. Returns where it is and the size of the disk it holds.
     */
    public Pair<String, Long> take(long volumeId, String snapshotUuid) {
        VolumeVO first = disk(volumeId);
        return instanceLock.hold(first.getInstanceId(), LOCK_WAIT_SECONDS, () -> {
            VolumeVO volume = disk(volumeId);
            VMInstanceVO vm = vmDao.findById(volume.getInstanceId());
            JsonObject result = onInstance(vm, "snapshotdisk", named(vm, volume, snapshotUuid), "snapshot the disk");
            return new Pair<>(path(volume.getPath(), snapshotUuid), result.get("size_bytes").getAsLong());
        });
    }

    /** Returns the disk to the snapshot. The instance is stopped: the caller has checked, and the extension checks again. */
    public void revert(long volumeId, String snapshotUuid, String recordedPath) {
        VolumeVO first = disk(volumeId);
        instanceLock.hold(first.getInstanceId(), LOCK_WAIT_SECONDS, () -> {
            VolumeVO volume = disk(volumeId);
            if (!path(volume.getPath(), snapshotUuid).equals(recordedPath)) {
                throw reject("The snapshot is of the disk as it was on other storage; it cannot be reverted to");
            }
            VMInstanceVO vm = vmDao.findById(volume.getInstanceId());
            onInstance(vm, "revertdisksnapshot", named(vm, volume, snapshotUuid), "revert the disk to the snapshot");
            return null;
        });
    }

    private String volumeOf(String recordedPath, String snapshotUuid) {
        String suffix = "@" + nameOnCeph(snapshotUuid);
        if (recordedPath == null || !recordedPath.endsWith(suffix) || recordedPath.length() == suffix.length()) {
            throw new CloudRuntimeException("The snapshot has no recorded place on Ceph");
        }
        return recordedPath.substring(0, recordedPath.length() - suffix.length());
    }

    /** Deletes the snapshot from the image recorded with it. One already gone, with or without its image, needs nothing. */
    public void delete(StoragePoolVO pool, String snapshotUuid, String recordedPath) {
        if (recordedPath == null) {
            return; // never recorded as taken
        }
        HostVO host = hostFor(pool);
        Map<String, Object> parameters = cephParameters(host);
        parameters.put("volume", volumeOf(recordedPath, snapshotUuid));
        parameters.put("snapshot", nameOnCeph(snapshotUuid));
        onStorage(host, "deletedisksnapshot", parameters, AgentManager.Wait.value(), "delete the snapshot of the disk");
    }

    /** Copies the snapshot to a file under a mounted secondary storage. Returns the file's size. */
    public long backup(StoragePoolVO pool, String snapshotUuid, String recordedPath, File file, int wait) {
        HostVO host = hostFor(pool);
        Map<String, Object> parameters = cephParameters(host);
        parameters.put("volume", volumeOf(recordedPath, snapshotUuid));
        parameters.put("snapshot", nameOnCeph(snapshotUuid));
        parameters.put("file", file.getPath());
        JsonObject result = onStorage(host, "backupdisksnapshot", parameters, wait, "copy the snapshot to secondary storage");
        return result.get("size_bytes").getAsLong();
    }

    /**
     * The disk's record is going, and its image, with every snapshot on it, goes with the disk at the external system.
     * A snapshot that was also copied to secondary storage keeps that copy, as stock. One that was only on Ceph no
     * longer exists: its record goes here, with what it counted, which stock does only for KVM.
     */
    public void gone(long volumeId) {
        for (SnapshotVO snapshot : snapshotDao.listByVolumeId(volumeId)) {
            SnapshotDataStoreVO ref = onCephRef(snapshot.getId());
            if (ref == null || snapshot.getState() == Snapshot.State.Destroyed || snapshot.getState() == Snapshot.State.Error
                    || snapshotStoreDao.findBySnapshotIdWithNonDestroyedState(snapshot.getId()).size() != 1) {
                continue;
            }
            snapshotStoreDao.remove(ref.getId());
            snapshot.setState(Snapshot.State.Destroyed);
            snapshotDao.update(snapshot.getId(), snapshot);
            resourceLimits.decrementResourceCount(snapshot.getAccountId(), ResourceType.snapshot);
            resourceLimits.decrementResourceCount(snapshot.getAccountId(), ResourceType.primary_storage, ref.getPhysicalSize());
            publishDeleted(snapshot);
            logger.info("Snapshot {} was only on the disk of volume {}, deleted at the external system, and is gone with it", snapshot.getUuid(), volumeId);
        }
    }

    protected void publishDeleted(SnapshotVO snapshot) {
        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_SNAPSHOT_DELETE, snapshot.getAccountId(), snapshot.getDataCenterId(), snapshot.getId(),
                snapshot.getName(), null, null, 0L, snapshot.getClass().getName(), snapshot.getUuid());
    }
}
