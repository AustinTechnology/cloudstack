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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.extensions.dao.ExtensionDetailsDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.framework.extensions.vo.ExtensionDetailsVO;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.RunCustomActionCommand;
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
        Storage(String name, String type, boolean shared, boolean enabled, boolean active, long totalBytes, long usedBytes) {
            this.name = name;
            this.type = type;
            this.shared = shared;
            this.enabled = enabled;
            this.active = active;
            this.totalBytes = totalBytes;
            this.usedBytes = usedBytes;
        }
    }

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

    public List<Storage> list(long clusterId) {
        HostVO host = host(clusterId);
        Extension extension = extensionHelper.getExtensionForCluster(clusterId);
        ExtensionDetailsVO capability = extension == null ? null : extensionDetailsDao.findDetail(extension.getId(), CAPABILITY);
        if (extension == null || extension.getType() != Extension.Type.Orchestrator || extension.getState() != Extension.State.Enabled
                || capability == null || capability.getValue() == null || !"true".equalsIgnoreCase(capability.getValue().trim())) {
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
                result.add(new Storage(name, type, flag(row, "shared"), flag(row, "enabled"), flag(row, "active"), total, used));
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
