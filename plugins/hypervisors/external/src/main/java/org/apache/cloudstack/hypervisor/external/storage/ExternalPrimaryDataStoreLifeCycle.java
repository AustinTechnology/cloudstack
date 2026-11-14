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
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Registers one storage of an External cluster as a primary storage pool. The storage must already exist at the
 * provider; registering it creates nothing there. URL: external://&lt;storage name&gt;, scope CLUSTER.
 */
public class ExternalPrimaryDataStoreLifeCycle implements PrimaryDataStoreLifeCycle {
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject PrimaryDataStoreHelper dataStoreHelper;
    @Inject ClusterDao clusterDao;
    @Inject HostDao hostDao;
    @Inject PrimaryDataStoreDao poolDao;
    @Inject StorageManager storageManager;
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
        Long clusterId = (Long) info.get("clusterId");
        ClusterVO cluster = clusterId == null ? null : clusterDao.findById(clusterId);
        if (cluster == null || cluster.getHypervisorType() != HypervisorType.External) {
            throw new InvalidParameterValueException("External storage is registered for one External cluster (scope CLUSTER)");
        }
        String storageName = storageName((String) info.get("url"));
        for (StoragePoolVO pool : poolDao.listPoolsByCluster(clusterId)) {
            if (ExternalPrimaryDataStoreProvider.NAME.equals(pool.getStorageProviderName()) && storageName.equals(pool.getPath())) {
                throw new InvalidParameterValueException("That storage is already registered for the cluster");
            }
        }
        ExternalStorageInventory.Storage storage = inventory.find(clusterId, storageName);
        if (storage == null) {
            throw new InvalidParameterValueException("The cluster has no storage of that name that holds instance disks");
        }
        if (!storage.enabled || !storage.active) {
            throw new InvalidParameterValueException("The storage is not enabled and active at the provider");
        }
        if (!storage.shared) {
            throw new InvalidParameterValueException("Storage that is local to one host is not supported");
        }
        Map<String, String> details = info.get("details") == null ? new HashMap<>() : new HashMap<>((Map<String, String>) info.get("details"));
        details.put("externalStorageType", storage.type);
        PrimaryDataStoreParameters parameters = new PrimaryDataStoreParameters();
        parameters.setHost(inventory.providerCluster(clusterId));
        parameters.setPort(0);
        parameters.setPath(storageName);
        parameters.setType(poolType(storage.type));
        parameters.setUuid(UUID.nameUUIDFromBytes(("external-storage:" + cluster.getUuid() + ":" + storageName).getBytes(StandardCharsets.UTF_8)).toString());
        parameters.setZoneId((Long) info.get("zoneId"));
        parameters.setPodId(cluster.getPodId());
        parameters.setClusterId(clusterId);
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
        dataStoreHelper.attachCluster(store);
        for (HostVO host : hostDao.findByClusterId(scope.getScopeId())) {
            if (host.getHypervisorType() != HypervisorType.External || host.getStatus() != Status.Up) {
                continue;
            }
            try {
                storageManager.connectHostToSharedPool(host, store.getId());
            } catch (Exception e) {
                logger.warn("Unable to record that {} reaches {}: {}", host, store, e.getMessage());
            }
        }
        return true;
    }

    @Override
    public boolean attachHost(DataStore store, HostScope scope, StoragePoolInfo existingInfo) {
        throw new CloudRuntimeException("External storage is cluster scoped");
    }

    @Override
    public boolean attachZone(DataStore dataStore, ZoneScope scope, HypervisorType hypervisorType) {
        throw new CloudRuntimeException("External storage is cluster scoped");
    }

    /** Maintenance mode stops or moves the instances on a pool. CloudStack never does that to External instances. */
    @Override
    public boolean maintain(DataStore store) {
        throw new CloudRuntimeException("Maintenance mode is not supported for External storage");
    }

    @Override
    public boolean cancelMaintain(DataStore store) {
        throw new CloudRuntimeException("Maintenance mode is not supported for External storage");
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
        throw new CloudRuntimeException("External storage is cluster scoped");
    }

    @Override
    public void changeStoragePoolScopeToCluster(DataStore store, ClusterScope clusterScope, HypervisorType hypervisorType) {
        throw new CloudRuntimeException("External storage is cluster scoped");
    }
}
