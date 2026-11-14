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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.inject.Inject;

import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.extensions.dao.ExtensionDetailsDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.framework.extensions.vo.ExtensionDetailsVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.RunCustomActionCommand;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Read-only: the storages of an External cluster that can hold instance disks, as the extension reports them. */
public class ExternalStorageInventory {
    /** Extension detail that declares the storage actions. Missing or false means unsupported. */
    public static final String CAPABILITY = "instancestoragesupported";

    public static final class Storage {
        public final String name;
        public final String type;
        public final boolean shared;
        public final boolean enabled;
        public final boolean active;
        public final long totalBytes;
        public final long usedBytes;
        /** What is behind the storage, as the extension names it. */
        public final String backing;
        /** The Ceph monitors the cluster uses for it; empty for anything else. */
        public final Set<String> monitors;
        Storage(String name, String type, boolean shared, boolean enabled, boolean active, long totalBytes, long usedBytes, String backing, Set<String> monitors) {
            this.name = name;
            this.type = type;
            this.shared = shared;
            this.enabled = enabled;
            this.active = active;
            this.totalBytes = totalBytes;
            this.usedBytes = usedBytes;
            this.backing = backing;
            this.monitors = monitors;
        }
    }

    /** Pool details: what the pool was registered for, and the clusters checked against it. */
    static final String BACKING = "externalBacking";
    static final String MONITORS = "externalMonitors";
    static final String CLUSTER = "externalCluster.";

    /**
     * Two clusters reach the same storage when it has the same backing on both. Clusters list the monitors of
     * one Ceph differently, so for Ceph one monitor in common is enough.
     */
    static boolean same(String backing, Set<String> monitors, String otherBacking, Set<String> otherMonitors) {
        return backing.equals(otherBacking) && (monitors.isEmpty() && otherMonitors.isEmpty() || !Collections.disjoint(monitors, otherMonitors));
    }

    @Inject ClusterDao clusterDao;
    @Inject StoragePoolDetailsDao poolDetailsDao;
    @Inject HostDao hostDao;
    @Inject ExtensionHelper extensionHelper;
    @Inject ExtensionDetailsDao extensionDetailsDao;
    @Inject ExtensionsManager extensionsManager;
    @Inject AgentManager agentManager;

    /** The external cluster identity of an External cluster, taken from one of its hosts. */
    public String providerCluster(long clusterId) {
        return providerCluster(host(clusterId));
    }

    private String providerCluster(HostVO host) {
        Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
        String cluster = access == null ? null : access.getOrDefault("host", Collections.emptyMap()).get("proxmox_cluster");
        if (cluster == null || cluster.isEmpty() || !cluster.equals(cluster.trim())) {
            throw new InvalidParameterValueException("The host needs an exact external cluster identity");
        }
        return cluster;
    }

    private HostVO host(long clusterId) {
        for (HostVO host : hostDao.findByClusterId(clusterId)) {
            if (host.getHypervisorType() == HypervisorType.External && host.getStatus() == Status.Up && host.getRemoved() == null) {
                return host;
            }
        }
        throw new CloudRuntimeException("The External cluster has no host that is Up");
    }

    public boolean supported(long clusterId) {
        Extension extension = extensionHelper.getExtensionForCluster(clusterId);
        ExtensionDetailsVO capability = extension == null ? null : extensionDetailsDao.findDetail(extension.getId(), CAPABILITY);
        return extension != null && extension.getType() == Extension.Type.Orchestrator && extension.getState() == Extension.State.Enabled
                && capability != null && capability.getValue() != null && "true".equalsIgnoreCase(capability.getValue().trim());
    }

    /** The External clusters of a zone whose extension supports storage. */
    public List<ClusterVO> clusters(long zoneId) {
        List<ClusterVO> result = new ArrayList<>();
        for (ClusterVO cluster : clusterDao.listByDcHyType(zoneId, HypervisorType.External.toString())) {
            if (cluster.getRemoved() == null && supported(cluster.getId())) {
                result.add(cluster);
            }
        }
        return result;
    }

    /** The storage of that name on every cluster of the zone that has it. A cluster that cannot be asked fails the call. */
    public Map<ClusterVO, Storage> findInZone(long zoneId, String name) {
        Map<ClusterVO, Storage> result = new LinkedHashMap<>();
        for (ClusterVO cluster : clusters(zoneId)) {
            Storage storage = find(cluster.getId(), name);
            if (storage != null) {
                result.put(cluster, storage);
            }
        }
        return result;
    }

    /** The pool's storage as the first cluster that can be asked reports it; null when none can. */
    public Storage findAny(StoragePoolVO pool) {
        Map<String, String> details = poolDetailsDao.listDetailsKeyPairs(pool.getId());
        for (ClusterVO cluster : clusters(pool.getDataCenterId())) {
            if (!details.containsKey(CLUSTER + cluster.getUuid())) {
                continue;
            }
            try {
                Storage storage = find(cluster.getId(), pool.getPath());
                if (storage != null) {
                    return storage;
                }
            } catch (RuntimeException e) {
                // the next cluster
            }
        }
        return null;
    }

    /**
     * Whether the cluster's storage of the pool's name is the storage the pool was registered for. A cluster is
     * asked once; the answer is kept with the pool when remember is set.
     */
    public boolean reaches(StoragePoolVO pool, long clusterId, boolean remember) {
        ClusterVO cluster = clusterDao.findById(clusterId);
        Map<String, String> details = poolDetailsDao.listDetailsKeyPairs(pool.getId());
        if (cluster == null || details.get(BACKING) == null) {
            return false;
        }
        if (details.containsKey(CLUSTER + cluster.getUuid())) {
            return true;
        }
        Storage storage = supported(clusterId) ? find(clusterId, pool.getPath()) : null;
        String monitors = details.get(MONITORS);
        Set<String> registered = monitors == null || monitors.isEmpty() ? Collections.emptySet() : new HashSet<>(Arrays.asList(monitors.split(",")));
        if (storage == null || !storage.shared || !same(storage.backing, storage.monitors, details.get(BACKING), registered)) {
            return false;
        }
        if (remember) {
            poolDetailsDao.addDetail(pool.getId(), CLUSTER + cluster.getUuid(), providerCluster(clusterId), true);
        }
        return true;
    }

    public List<Storage> list(long clusterId) {
        HostVO host = host(clusterId);
        if (!supported(clusterId)) {
            throw new InvalidParameterValueException("The cluster's extension does not support storage");
        }
        Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
        RunCustomActionCommand cmd = new RunCustomActionCommand("liststorage");
        cmd.setExternalDetails(access);
        cmd.setWait(60);
        Answer answer = agentManager.easySend(host.getId(), cmd);
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException("External storage inventory collection failed");
        }
        return parse(answer.getDetails(), providerCluster(host));
    }

    /** Null when the cluster has no such storage. */
    public Storage find(long clusterId, String name) {
        for (Storage storage : list(clusterId)) {
            if (storage.name.equals(name)) {
                return storage;
            }
        }
        return null;
    }

    static List<Storage> parse(String payload, String expectedCluster) {
        try {
            if (payload == null || payload.length() > 1024 * 1024) {
                throw new IllegalArgumentException();
            }
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            if (!"success".equals(text(root, "status")) || !expectedCluster.equals(text(root, "cluster"))) {
                throw new IllegalArgumentException();
            }
            JsonArray rows = root.getAsJsonArray("storages");
            if (rows == null || rows.size() > 1000) {
                throw new IllegalArgumentException();
            }
            Set<String> names = new HashSet<>();
            List<Storage> result = new ArrayList<>();
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                String name = text(row, "storage");
                String type = text(row, "type");
                long total = bytes(row, "total");
                long used = bytes(row, "used");
                if (!name.matches("[A-Za-z][A-Za-z0-9._-]*") || !names.add(name) || !type.matches("[a-z][a-z0-9]*")) {
                    throw new IllegalArgumentException();
                }
                String backing = text(row, "backing");
                Set<String> monitors = new TreeSet<>();
                for (JsonElement monitor : row.getAsJsonArray("monitors")) {
                    String address = monitor.getAsJsonPrimitive().getAsString();
                    if (!monitor.getAsJsonPrimitive().isString() || !address.matches("[A-Za-z0-9.:\\[\\]/_-]{1,255}")) {
                        throw new IllegalArgumentException();
                    }
                    monitors.add(address);
                }
                if (!backing.matches("[\\x21-\\x7e]{1,512}") || monitors.size() > 64) {
                    throw new IllegalArgumentException();
                }
                result.add(new Storage(name, type, flag(row, "shared"), flag(row, "enabled"), flag(row, "active"), total, used, backing, monitors));
            }
            return result;
        } catch (RuntimeException e) {
            // The payload is not echoed: it is provider output.
            throw new InvalidParameterValueException("Invalid or ambiguous external storage inventory");
        }
    }

    private static String text(JsonObject object, String key) {
        JsonPrimitive value = object.getAsJsonPrimitive(key);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException();
        }
        return value.getAsString();
    }

    private static boolean flag(JsonObject object, String key) {
        JsonPrimitive value = object.getAsJsonPrimitive(key);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException();
        }
        return value.getAsBoolean();
    }

    private static long bytes(JsonObject object, String key) {
        JsonPrimitive value = object.getAsJsonPrimitive(key);
        if (value == null || !value.isNumber() || !value.getAsString().matches("0|[1-9][0-9]{0,17}")) {
            throw new IllegalArgumentException();
        }
        return Long.parseLong(value.getAsString());
    }
}
