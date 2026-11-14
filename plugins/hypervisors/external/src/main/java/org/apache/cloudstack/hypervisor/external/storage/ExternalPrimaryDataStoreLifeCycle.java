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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.HostScope;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreParameters;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.volume.datastore.PrimaryDataStoreHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.StoragePoolInfo;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Registers one storage of an External cluster as a primary storage pool. The storage must already exist at the
 * provider; registering it creates nothing there. URL: external://&lt;storage name&gt;, scope CLUSTER.
 */
public class ExternalPrimaryDataStoreLifeCycle implements PrimaryDataStoreLifeCycle {
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject PrimaryDataStoreHelper dataStoreHelper;
    @Inject DataCenterDao zoneDao;
    @Inject VolumeDao volumeDao;
    @Inject PrimaryDataStoreDao poolDao;
    @Inject ExternalStorageInventory inventory;

    static String storageName(String url) {
        String prefix = "external://";
        String name = url != null && url.startsWith(prefix) ? url.substring(prefix.length()) : null;
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9._-]*")) {
            throw new InvalidParameterValueException("The URL of External storage is external://<storage name>");
        }
        return name;
    }

    /** The nearest CloudStack pool type. It describes the pool; nothing is sent to a host because of it. */
    static StoragePoolType poolType(String providerType) {
        switch (providerType) {
            case "rbd": return StoragePoolType.RBD;
            case "nfs": return StoragePoolType.NetworkFilesystem;
            case "lvm": return StoragePoolType.CLVM;
            case "iscsi": case "iscsidirect": return StoragePoolType.Iscsi;
            default: return StoragePoolType.PreSetup;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public DataStore initialize(Map<String, Object> info) {
        Long zoneId = (Long) info.get("zoneId");
        DataCenterVO zone = zoneId == null ? null : zoneDao.findById(zoneId);
        if (zone == null || info.get("clusterId") != null || info.get("hypervisorType") != HypervisorType.External) {
            throw new InvalidParameterValueException("External storage is registered once for a zone (scope ZONE, hypervisor External)");
        }
        String storageName = storageName((String) info.get("url"));
        for (StoragePoolVO pool : poolDao.listByDataCenterId(zoneId)) {
            if (ExternalPrimaryDataStoreProvider.NAME.equals(pool.getStorageProviderName()) && storageName.equals(pool.getPath()) && pool.getRemoved() == null) {
                throw new InvalidParameterValueException("That storage is already registered for the zone");
            }
        }
        // One pool for every cluster that reaches the storage. Clusters that name something else the same way
        // would put two different sets of disks in one pool, so that is refused.
        Map<ClusterVO, ExternalStorageInventory.Storage> found = inventory.findInZone(zoneId, storageName);
        if (found.isEmpty()) {
            throw new InvalidParameterValueException("No External cluster in the zone has a storage of that name that holds instance disks");
        }
        ExternalStorageInventory.Storage storage = found.values().iterator().next();
        Set<String> monitors = new TreeSet<>();
        Map<String, String> details = info.get("details") == null ? new HashMap<>() : new HashMap<>((Map<String, String>) info.get("details"));
        for (Map.Entry<ClusterVO, ExternalStorageInventory.Storage> entry : found.entrySet()) {
            ExternalStorageInventory.Storage other = entry.getValue();
            String where = " on cluster " + entry.getKey().getName();
            if (!other.enabled || !other.active) {
                throw new InvalidParameterValueException("The storage is not enabled and active at the provider" + where);
            }
            if (!other.shared) {
                throw new InvalidParameterValueException("Storage that is local to one host is not supported; it is local" + where);
            }
            if (!other.type.equals(storage.type) || !ExternalStorageInventory.same(storage.backing, storage.monitors, other.backing, other.monitors)) {
                throw new InvalidParameterValueException("The clusters of the zone do not mean the same storage by that name; it differs" + where);
            }
            monitors.addAll(other.monitors);
            details.put(ExternalStorageInventory.CLUSTER + entry.getKey().getUuid(), inventory.providerCluster(entry.getKey().getId()));
        }
        details.put("externalStorageType", storage.type);
        details.put(ExternalStorageInventory.BACKING, storage.backing);
        details.put(ExternalStorageInventory.MONITORS, String.join(",", monitors));
        PrimaryDataStoreParameters parameters = new PrimaryDataStoreParameters();
        parameters.setHost("external");
        parameters.setPort(0);
        parameters.setPath(storageName);
        parameters.setType(poolType(storage.type));
        parameters.setUuid(UUID.nameUUIDFromBytes(("external-storage:" + zone.getUuid() + ":" + storageName).getBytes(StandardCharsets.UTF_8)).toString());
        parameters.setZoneId(zoneId);
        parameters.setName((String) info.get("name"));
        parameters.setProviderName(ExternalPrimaryDataStoreProvider.NAME);
        parameters.setManaged(false);
        parameters.setCapacityBytes(storage.totalBytes);
        parameters.setUsedBytes(storage.usedBytes);
        parameters.setHypervisorType(HypervisorType.External);
        parameters.setTags((String) info.get("tags"));
        parameters.setIsTagARule((Boolean) info.get("isTagARule"));
        parameters.setDetails(details);
        return dataStoreHelper.createPrimaryDataStore(parameters);
    }

    @Override
    public boolean attachCluster(DataStore store, ClusterScope scope) {
        throw new CloudRuntimeException("External storage is zone scoped");
    }

    @Override
    public boolean attachHost(DataStore store, HostScope scope, StoragePoolInfo existingInfo) {
        throw new CloudRuntimeException("External storage is zone scoped");
    }

    /** Nothing is sent to a host: the clusters already reach the storage, and which of them do is kept with the pool. */
    @Override
    public boolean attachZone(DataStore dataStore, ZoneScope scope, HypervisorType hypervisorType) {
        dataStoreHelper.attachZone(dataStore, HypervisorType.External);
        return true;
    }

    /**
     * Maintenance mode stops or moves the instances on a pool, and CloudStack never does that to External
     * instances. A pool that records no volume can enter it, which is how a pool is removed.
     */
    @Override
    public boolean maintain(DataStore store) {
        if (!volumeDao.findNonDestroyedVolumesByPoolId(store.getId()).isEmpty()) {
            throw new CloudRuntimeException("External storage with recorded volumes cannot enter maintenance mode");
        }
        return dataStoreHelper.maintain(store);
    }

    @Override
    public boolean cancelMaintain(DataStore store) {
        return dataStoreHelper.cancelMaintain(store);
    }

    @Override
    public boolean deleteDataStore(DataStore store) {
        return dataStoreHelper.deletePrimaryDataStore(store);
    }

    @Override
    public boolean migrateToObjectStore(DataStore store) {
        return false;
    }

    @Override
    public void updateStoragePool(StoragePool storagePool, Map<String, String> details) {
    }

    @Override
    public void enableStoragePool(DataStore store) {
        dataStoreHelper.enable(store);
    }

    @Override
    public void disableStoragePool(DataStore store) {
        dataStoreHelper.disable(store);
    }

    @Override
    public void changeStoragePoolScopeToZone(DataStore store, ClusterScope clusterScope, HypervisorType hypervisorType) {
        throw new CloudRuntimeException("External storage is zone scoped");
    }

    @Override
    public void changeStoragePoolScopeToCluster(DataStore store, ClusterScope clusterScope, HypervisorType hypervisorType) {
        throw new CloudRuntimeException("External storage is zone scoped");
    }
}
