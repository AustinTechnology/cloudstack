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

import java.util.Collections;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.ChapInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.CreateCmdResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.utils.Pair;

/**
 * The disks on External storage are created, changed and destroyed at the provider. This driver changes nothing
 * there: deleting a volume removes CloudStack's record only, and the operations that would have to write to the
 * provider are refused.
 */
public class ExternalPrimaryDataStoreDriver implements PrimaryDataStoreDriver {
    static final String UNSUPPORTED = "This operation is not supported on External storage";
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject ExternalStorageInventory inventory;
    @Inject PrimaryDataStoreDao poolDao;

    @Override
    public Map<String, String> getCapabilities() {
        return Collections.emptyMap();
    }

    @Override
    public DataTO getTO(DataObject data) {
        return null;
    }

    @Override
    public DataStoreTO getStoreTO(DataStore store) {
        return null;
    }

    @Override
    public void createAsync(DataStore store, DataObject data, AsyncCompletionCallback<CreateCmdResult> callback) {
        CreateCmdResult result = new CreateCmdResult(null, null);
        result.setResult(UNSUPPORTED);
        callback.complete(result);
    }

    /**
     * Record only. An instance's disks go with the instance at the provider: destroyed with a deployed instance,
     * kept with an adopted one. Neither is decided here.
     */
    @Override
    public void deleteAsync(DataStore store, DataObject data, AsyncCompletionCallback<CommandResult> callback) {
        callback.complete(new CommandResult());
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, AsyncCompletionCallback<CopyCommandResult> callback) {
        CopyCommandResult result = new CopyCommandResult(null, null);
        result.setResult(UNSUPPORTED);
        callback.complete(result);
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        copyAsync(srcData, destData, callback);
    }

    @Override
    public boolean canCopy(DataObject srcData, DataObject destData) {
        return false;
    }

    @Override
    public void resize(DataObject data, AsyncCompletionCallback<CreateCmdResult> callback) {
        CreateCmdResult result = new CreateCmdResult(null, null);
        result.setResult(UNSUPPORTED);
        callback.complete(result);
    }

    @Override
    public ChapInfo getChapInfo(DataObject dataObject) {
        return null;
    }

    @Override
    public boolean grantAccess(DataObject dataObject, Host host, DataStore dataStore) {
        return true;
    }

    @Override
    public void revokeAccess(DataObject dataObject, Host host, DataStore dataStore) {
    }

    @Override
    public long getDataObjectSizeIncludingHypervisorSnapshotReserve(DataObject dataObject, StoragePool storagePool) {
        return dataObject.getSize() == null ? 0 : dataObject.getSize();
    }

    @Override
    public long getBytesRequiredForTemplate(TemplateInfo templateInfo, StoragePool storagePool) {
        return 0;
    }

    @Override
    public long getUsedBytes(StoragePool storagePool) {
        return storagePool.getUsedBytes();
    }

    @Override
    public long getUsedIops(StoragePool storagePool) {
        return 0;
    }

    @Override
    public void takeSnapshot(SnapshotInfo snapshot, AsyncCompletionCallback<CreateCmdResult> callback) {
        CreateCmdResult result = new CreateCmdResult(null, null);
        result.setResult(UNSUPPORTED);
        callback.complete(result);
    }

    @Override
    public void revertSnapshot(SnapshotInfo snapshotOnImageStore, SnapshotInfo snapshotOnPrimaryStore, AsyncCompletionCallback<CommandResult> callback) {
        CommandResult result = new CommandResult();
        result.setResult(UNSUPPORTED);
        callback.complete(result);
    }

    @Override
    public void handleQualityOfServiceForVolumeMigration(VolumeInfo volumeInfo, QualityOfServiceState qualityOfServiceState) {
    }

    @Override
    public boolean canProvideStorageStats() {
        return true;
    }

    /** Capacity and used bytes as the provider reports them now; null when it cannot be asked. */
    @Override
    public Pair<Long, Long> getStorageStats(StoragePool storagePool) {
        try {
            StoragePoolVO pool = poolDao.findById(storagePool.getId());
            ExternalStorageInventory.Storage storage = pool == null ? null : inventory.findAny(pool);
            return storage == null ? null : new Pair<>(storage.totalBytes, storage.usedBytes);
        } catch (RuntimeException e) {
            logger.debug("No storage statistics for {}: {}", storagePool, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean canProvideVolumeStats() {
        return false;
    }

    @Override
    public Pair<Long, Long> getVolumeStats(StoragePool storagePool, String volumeId) {
        return null;
    }

    @Override
    public boolean canHostAccessStoragePool(Host host, StoragePool pool) {
        return host.getHypervisorType() == HypervisorType.External && host.getClusterId() != null && host.getDataCenterId() == pool.getDataCenterId()
                && inventory.reaches(poolDao.findById(pool.getId()), host.getClusterId(), false);
    }

    @Override
    public boolean isVmInfoNeeded() {
        return false;
    }

    @Override
    public void provideVmInfo(long vmId, long volumeId) {
    }

    @Override
    public boolean isVmTagsNeeded(String tagKey) {
        return false;
    }

    @Override
    public void provideVmTags(long vmId, long volumeId, String tagValue) {
    }

    @Override
    public boolean isStorageSupportHA(StoragePoolType type) {
        return false;
    }

    @Override
    public void detachVolumeFromAllStorageNodes(Volume volume) {
    }
}
