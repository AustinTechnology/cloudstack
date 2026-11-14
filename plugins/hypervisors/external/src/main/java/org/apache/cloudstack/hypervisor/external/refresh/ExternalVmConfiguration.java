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
package org.apache.cloudstack.hypervisor.external.refresh;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Validated, read-only snapshot. Parsing it does not authorize or perform any update. */
public final class ExternalVmConfiguration {
    public static final class Nic {
        public final String deviceId;
        public final String mac;
        public final String bridge;
        public final Integer vlan;
        public final List<String> ipAddresses;

        private Nic(String deviceId, String mac, String bridge, Integer vlan, List<String> ipAddresses) {
            this.deviceId = deviceId;
            this.mac = mac;
            this.bridge = bridge;
            this.vlan = vlan;
            this.ipAddresses = Collections.unmodifiableList(ipAddresses);
        }
    }

    /** One data-bearing disk as the instance's provider configuration references it. */
    public static final class Disk {
        /** Provider slot, for example scsi0. */
        public final String device;
        /** Null for a passthrough device, which is on no storage. */
        public final String storage;
        public final String volume;
        /** Null when the provider reports no size. */
        public final Long sizeBytes;
        public final boolean root;
        private Disk(String device, String storage, String volume, Long sizeBytes, boolean root) {
            this.device = device;
            this.storage = storage;
            this.volume = volume;
            this.sizeBytes = sizeBytes;
            this.root = root;
        }
        /** The provider's own identifier of the volume: storage and volume name. Never derived from the instance. */
        public String volumeId() {
            return storage == null ? null : storage + ":" + volume;
        }
    }

    public final String cluster;
    public final String vmid;
    public final String name;
    public final String node;
    public final String power;
    public final String configDigest;
    public final int cpus;
    public final int memoryMb;
    public final boolean agentAvailable;
    public final List<Nic> nics;
    /** Provider disk inventory as received. Never a storage mutation instruction. */
    public final JsonArray disks;
    public final List<Disk> diskList;

    private ExternalVmConfiguration(String cluster, String vmid, String name, String node, String power, String configDigest, int cpus,
            int memoryMb, boolean agentAvailable, List<Nic> nics, JsonArray disks, List<Disk> diskList) {
        this.cluster = cluster;
        this.vmid = vmid;
        this.name = name;
        this.node = node;
        this.power = power;
        this.configDigest = configDigest;
        this.cpus = cpus;
        this.memoryMb = memoryMb;
        this.agentAvailable = agentAvailable;
        this.nics = Collections.unmodifiableList(nics);
        this.disks = disks.deepCopy();
        this.diskList = Collections.unmodifiableList(diskList);
    }

    private static IllegalArgumentException invalid(String field) {
        // Do not include provider output, which could contain credentials or unexpected data.
        return new IllegalArgumentException("Invalid getconfig field: " + field);
    }

    /** Exact source identity; display name and current provider node are not ownership keys. */
    public void requireIdentity(String expectedCluster, String expectedVmid) {
        if (expectedCluster == null || expectedVmid == null
                || !cluster.equals(expectedCluster) || !vmid.equals(expectedVmid)) {
            throw new IllegalArgumentException("getconfig identity does not match the instance");
        }
    }

    private static JsonObject object(JsonElement value, String field) {
        if (value == null || !value.isJsonObject()) {
            throw invalid(field);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String field) {
        if (value == null || !value.isJsonArray()) {
            throw invalid(field);
        }
        return value.getAsJsonArray();
    }

    private static String text(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(field);
        }
        String s = value.getAsString();
        if (s.isEmpty() || !s.equals(s.trim()) || s.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field);
        }
        return s;
    }

    private static String optionalText(JsonElement value, String field) {
        return value == null || value.isJsonNull() ? null : text(value, field);
    }
    private static int integer(JsonElement value, String field, int min, int max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(field);
        }
        String s = value.getAsString();
        if (!s.matches("0|[1-9][0-9]*")) {
            throw invalid(field);
        }
        try {
            long n = Long.parseLong(s);
            if (n < min || n > max) {
                throw invalid(field);
            }
            return (int)n;
        } catch (NumberFormatException e) {
            throw invalid(field);
        }
    }

    private static String vmid(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            throw invalid("identity.vmid");
        }
        JsonPrimitive p = value.getAsJsonPrimitive();
        if (!p.isNumber()) {
            throw invalid("identity.vmid");
        }
        String s = p.getAsString();
        if (!s.matches("[1-9][0-9]*")) {
            throw invalid("identity.vmid");
        }
        return s;
    }

    private static String deviceId(JsonElement value) {
        return "net" + integer(value, "nics.device_id", 0, Integer.MAX_VALUE);
    }

    private static boolean isIpv4Literal(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    /** Gson's tree parser accepts duplicate keys; that must not select an arbitrary identity. */
    private static void validateJsonValue(JsonReader reader, int depth) throws IOException {
        if (depth > 64) {
            throw invalid("response nesting");
        }
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT:
                reader.beginObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    if (!names.add(reader.nextName())) {
                        throw invalid("duplicate key");
                    }
                    validateJsonValue(reader, depth + 1);
                }
                reader.endObject();
                break;
            case BEGIN_ARRAY:
                reader.beginArray();
                while (reader.hasNext()) {
                    validateJsonValue(reader, depth + 1);
                }
                reader.endArray();
                break;
            case STRING:
            case NUMBER:
                reader.nextString();
                break;
            case BOOLEAN:
                reader.nextBoolean();
                break;
            case NULL:
                reader.nextNull();
                break;
            default:
                throw invalid("response");
        }
    }

    public static ExternalVmConfiguration parse(String raw) {
        final JsonObject root;
        try {
            if (raw == null || raw.length() > 1024 * 1024) {
                throw invalid("response size");
            }
            JsonReader reader = new JsonReader(new StringReader(raw));
            reader.setLenient(false);
            validateJsonValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalid("response");
            }
            root = object(new JsonParser().parse(raw), "response");
        } catch (RuntimeException | IOException e) {
            throw invalid("response");
        }
        if (!"success".equals(text(root.get("status"), "status"))) {
            throw invalid("status");
        }
        JsonObject identity = object(root.get("identity"), "identity");
        String cluster = text(identity.get("cluster"), "identity.cluster");
        String vmid = vmid(identity.get("vmid"));
        String name = text(identity.get("name"), "identity.name");
        String node = text(identity.get("node"), "identity.node");
        String power = text(root.get("power"), "power");
        if (!"running".equals(power) && !"stopped".equals(power)) {
            throw invalid("power");
        }
        String digest = text(root.get("config_digest"), "config_digest");
        if (!digest.matches("[0-9a-f]{40}")) {
            throw invalid("config_digest");
        }
        int cpus = integer(root.get("cpus"), "cpus", 1, Integer.MAX_VALUE);
        int memory = integer(root.get("memory_mb"), "memory_mb", 1, Integer.MAX_VALUE);
        JsonElement agent = root.get("agent_available");
        if (agent == null || !agent.isJsonPrimitive() || !agent.getAsJsonPrimitive().isBoolean()) {
            throw invalid("agent_available");
        }
        List<Nic> nics = new ArrayList<>();
        Set<String> macs = new HashSet<>();
        Set<String> devices = new HashSet<>();
        for (JsonElement element : array(root.get("nics"), "nics")) {
            JsonObject nic = object(element, "nics entry");
            String id = deviceId(nic.get("device_id"));
            String mac = text(nic.get("mac"), "nics.mac").toLowerCase(Locale.ROOT);
            if (!mac.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}") || !macs.add(mac) || !devices.add(id)) {
                throw invalid("nics duplicate or malformed identity");
            }
            JsonElement bridgeValue = nic.get("bridge");
            if (bridgeValue == null) {
                throw invalid("nics.bridge");
            }
            String bridge = bridgeValue.isJsonNull() ? null : text(bridgeValue, "nics.bridge");
            JsonElement vlanValue = nic.get("vlan");
            if (vlanValue == null) {
                throw invalid("nics.vlan");
            }
            Integer vlan = vlanValue.isJsonNull() ? null : integer(vlanValue, "nics.vlan", 1, 4094);
            List<String> addresses = new ArrayList<>();
            Set<String> uniqueAddresses = new HashSet<>();
            for (JsonElement address : array(nic.get("ip_addresses"), "nics.ip_addresses")) {
                String ip = text(address, "nics.ip_addresses");
                if (!isIpv4Literal(ip) || !uniqueAddresses.add(ip)) {
                    throw invalid("nics.ip_addresses");
                }
                addresses.add(ip);
            }
            nics.add(new Nic(id, mac, bridge, vlan, addresses));
        }
        JsonArray disks = array(root.get("disks"), "disks");
        List<Disk> diskList = new ArrayList<>();
        Set<String> diskDevices = new HashSet<>();
        Set<String> volumeIds = new HashSet<>();
        int roots = 0;
        for (JsonElement element : disks) {
            JsonObject disk = object(element, "disks entry");
            String device = text(disk.get("device"), "disks.device");
            if (!device.matches("(scsi|sata|virtio|ide)(0|[1-9][0-9]?)") || !diskDevices.add(device)) {
                throw invalid("disks.device");
            }
            // An extension that predates disk details reports only the device; such a disk has no volume identity.
            String storage = optionalText(disk.get("storage"), "disks.storage");
            String volume = optionalText(disk.get("volume"), "disks.volume");
            if ((storage == null) != (volume == null)
                    || (storage != null && (!storage.matches("[A-Za-z][A-Za-z0-9._-]*") || volume.contains(":") || volume.contains(",")))) {
                throw invalid("disks.volume");
            }
            if (storage != null && !volumeIds.add(storage + ":" + volume)) {
                throw invalid("disks.volume");
            }
            Long size = null;
            JsonElement sizeElement = disk.get("size_bytes");
            if (sizeElement != null && !sizeElement.isJsonNull()) {
                if (!sizeElement.isJsonPrimitive() || !sizeElement.getAsJsonPrimitive().isNumber() || !sizeElement.getAsString().matches("[1-9][0-9]{0,17}")) {
                    throw invalid("disks.size_bytes");
                }
                size = Long.parseLong(sizeElement.getAsString());
            }
            boolean isRoot = false;
            JsonElement rootElement = disk.get("root");
            if (rootElement != null && !rootElement.isJsonNull()) {
                if (!rootElement.isJsonPrimitive() || !rootElement.getAsJsonPrimitive().isBoolean()) {
                    throw invalid("disks.root");
                }
                isRoot = rootElement.getAsBoolean();
            }
            if (isRoot && ++roots > 1) {
                throw invalid("disks.root");
            }
            diskList.add(new Disk(device, storage, volume, size, isRoot));
        }
        return new ExternalVmConfiguration(cluster, vmid, name, node, power, digest, cpus, memory, agent.getAsBoolean(), nics, disks, diskList);
    }
}
