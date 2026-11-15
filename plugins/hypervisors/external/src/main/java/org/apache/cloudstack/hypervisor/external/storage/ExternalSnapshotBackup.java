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
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataMotionStrategy;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.NfsMountManager;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.to.DataObjectType;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.host.Host;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.ImageStoreDetailsUtil;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

/**
 * The copy of a snapshot of an External instance's disk from Ceph to secondary storage: what CloudStack does after
 * taking a snapshot when snapshot.backup.to.secondary is set. A KVM host does this for a KVM volume; an External
 * host is not one CloudStack can ask, so this management server mounts the secondary storage and the extension
 * writes the snapshot there as a qcow2 file, in the place and under the name a KVM host would give it.
 */
public class ExternalSnapshotBackup implements DataMotionStrategy {
    private static final int DEFAULT_WAIT_SECONDS = 21600;
    private static final long FILE_COMMAND_TIMEOUT_MS = 60000;

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject PrimaryDataStoreDao poolDao;
    @Inject ExternalVolumeSnapshots volumeSnapshots;
    @Inject NfsMountManager nfsMountManager;
    @Inject ImageStoreDetailsUtil imageStoreDetailsUtil;
    @Inject ConfigurationDao configDao;

    private StoragePoolVO externalPool(DataStore store) {
        StoragePoolVO pool = store.getRole() == DataStoreRole.Primary ? poolDao.findById(store.getId()) : null;
        return pool != null && ExternalPrimaryDataStoreProvider.NAME.equals(pool.getStorageProviderName()) ? pool : null;
    }

    @Override
    public StrategyPriority canHandle(DataObject srcData, DataObject destData) {
        boolean ours = srcData.getType() == DataObjectType.SNAPSHOT && destData.getType() == DataObjectType.SNAPSHOT
                && destData.getDataStore().getRole() == DataStoreRole.Image && externalPool(srcData.getDataStore()) != null;
        return ours ? StrategyPriority.HIGHEST : StrategyPriority.CANT_HANDLE;
    }

    @Override
    public StrategyPriority canHandle(Map<VolumeInfo, DataStore> volumeMap, Host srcHost, Host destHost) {
        return StrategyPriority.CANT_HANDLE;
    }

    protected int waitSeconds() {
        return NumbersUtil.parseInt(configDao.getValue("backup.snapshot.wait"), DEFAULT_WAIT_SECONDS);
    }

    /** The directory, made if it is not there, that this server's user can write the copy to. */
    protected void writable(File directory) {
        for (String[] command : new String[][] {{"mkdir", "-p"}, {"chmod", "777"}}) {
            Script script = new Script(true, command[0], FILE_COMMAND_TIMEOUT_MS, logger);
            script.add(command[1]);
            script.add(directory.getPath());
            String failure = script.execute();
            if (failure != null) {
                throw new CloudRuntimeException("Unable to prepare the directory of the snapshot on secondary storage: " + failure);
            }
        }
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        CopyCmdAnswer answer;
        String failure = null;
        try {
            DataStore imageStore = destData.getDataStore();
            String uri = imageStore.getUri();
            if (uri == null || !uri.startsWith("nfs://")) {
                throw new CloudRuntimeException("A snapshot of an External instance's disk can be copied only to NFS secondary storage");
            }
            String directory = ((SnapshotInfo)destData).getPath();
            if (directory == null || directory.isEmpty() || directory.startsWith("/") || directory.contains("..")) {
                throw new CloudRuntimeException("The snapshot has no place on secondary storage");
            }
            String mount = nfsMountManager.getMountPoint(uri, imageStoreDetailsUtil.getNfsVersion(imageStore.getId()));
            File target = new File(new File(mount, directory), srcData.getUuid());
            writable(target.getParentFile());
            long size = volumeSnapshots.backup(externalPool(srcData.getDataStore()), srcData.getUuid(), ((SnapshotInfo)srcData).getPath(), target, waitSeconds());
            SnapshotObjectTO copy = new SnapshotObjectTO();
            copy.setPath(directory + "/" + srcData.getUuid());
            copy.setPhysicalSize(size);
            answer = new CopyCmdAnswer(copy);
            logger.info("Copied snapshot {} to secondary storage {} as {}", srcData.getUuid(), imageStore.getName(), copy.getPath());
        } catch (RuntimeException e) {
            failure = e.getMessage() == null ? e.toString() : e.getMessage();
            logger.warn("Unable to copy snapshot {} to secondary storage: {}", srcData.getUuid(), failure);
            answer = new CopyCmdAnswer(failure);
        }
        CopyCommandResult result = new CopyCommandResult(null, answer);
        result.setResult(failure);
        callback.complete(result);
    }

    @Override
    public void copyAsync(Map<VolumeInfo, DataStore> volumeMap, VirtualMachineTO vmTo, Host srcHost, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        CopyCommandResult result = new CopyCommandResult(null, null);
        result.setResult(ExternalPrimaryDataStoreDriver.UNSUPPORTED);
        callback.complete(result);
    }
}
