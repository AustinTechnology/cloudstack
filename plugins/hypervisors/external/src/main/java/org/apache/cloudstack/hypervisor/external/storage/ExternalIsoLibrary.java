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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.image.datastore.ImageStoreEntity;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.RunCustomActionCommand;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.ExternalIsoCopies;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateDetailsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.TemplateManager;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.utils.DateUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * ISOs on the storage of an External cluster. CloudStack keeps an ISO on its secondary storage; the orchestrator
 * can only put a file of its own storage into a CD drive. The first time an ISO is used on a cluster the
 * orchestrator downloads it from the secondary storage and checks it against the ISO's checksum.
 */
public class ExternalIsoLibrary implements ExternalIsoCopies {
    protected Logger logger = LogManager.getLogger(getClass());

    /** Setting on the extension's cluster registration (or the extension) naming the cluster's ISO storage. */
    public static final String STORAGE_SETTING = "iso_storage";
    /** Detail of an ISO naming a file that is already on the orchestrator's storage; such an ISO is never copied or deleted. */
    public static final String PATH_DETAIL = "iso_path";

    static final Pattern VOLUME = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*:iso/[^,/]+");
    static final Pattern STORAGE = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");
    static final Pattern TAGGED_CHECKSUM = Pattern.compile("\\{([A-Za-z0-9-]+)\\}([0-9a-fA-F]+)");
    static final Map<String, Integer> CHECKSUM_LENGTHS = new LinkedHashMap<>();
    static {
        CHECKSUM_LENGTHS.put("md5", 32);
        CHECKSUM_LENGTHS.put("sha1", 40);
        CHECKSUM_LENGTHS.put("sha224", 56);
        CHECKSUM_LENGTHS.put("sha256", 64);
        CHECKSUM_LENGTHS.put("sha384", 96);
        CHECKSUM_LENGTHS.put("sha512", 128);
    }
    /** A copy is a download of the whole ISO by the orchestrator. */
    static final int COPY_WAIT_SECONDS = 3600;
    static final int READ_WAIT_SECONDS = 300;
    private static final long GIB = 1L << 30;

    /** Runs one cluster action of the extension and returns whether it succeeded and what it printed. */
    public interface Runner {
        Pair<Boolean, String> run(String action, Map<String, Object> parameters, int waitSeconds);
    }

    @Inject HostDao hostDao;
    @Inject ClusterDao clusterDao;
    @Inject VMTemplateDao templateDao;
    @Inject VMTemplateDetailsDao templateDetailsDao;
    @Inject TemplateDataStoreDao templateStoreDao;
    @Inject DataStoreManager dataStoreManager;
    @Inject TemplateDataFactory templateFactory;
    @Inject ExtensionsManager extensionsManager;
    @Inject ExtensionHelper extensionHelper;
    @Inject AgentManager agentManager;
    @Inject VMInstanceDao vmDao;
    @Inject VMInstanceDetailsDao vmDetailsDao;
    @Inject VolumeDao volumeDao;
    @Inject DiskOfferingDao diskOfferingDao;
    @Inject GuestOSDao guestOsDao;
    @Inject ExternalVolumeSync volumeSync;
    @Inject ExternalStorageInventory inventory;
    @Inject ExternalInstanceLock instanceLock;

    private static InvalidParameterValueException reject(String why) {
        return new InvalidParameterValueException(why);
    }

    public boolean supported(Long clusterId) {
        Extension extension = clusterId == null ? null : extensionHelper.getExtensionForCluster(clusterId);
        return extension != null && extensionHelper.isInstanceIsoSupported(extension.getId());
    }

    @Override
    public boolean supported(VirtualMachine vm) {
        Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
        if (hostId != null) {
            HostVO host = hostDao.findById(hostId);
            return host != null && supported(host.getClusterId());
        }
        // Not placed yet: any cluster of its zone that could take it.
        for (ClusterVO cluster : clusterDao.listByZoneId(vm.getDataCenterId())) {
            if (cluster.getRemoved() == null && cluster.getHypervisorType() == HypervisorType.External && supported(cluster.getId())) {
                return true;
            }
        }
        return false;
    }

    /** The ISO a command or a start names; null for an empty drive. */
    public VMTemplateVO iso(DataTO data) {
        if (!(data instanceof TemplateObjectTO) || ((TemplateObjectTO) data).getUuid() == null) {
            return null;
        }
        VMTemplateVO iso = templateDao.findById(((TemplateObjectTO) data).getId());
        if (iso == null || iso.getFormat() != ImageFormat.ISO) {
            throw reject("The ISO was not found");
        }
        return iso;
    }

    /**
     * The CD drives of a start, as the extension takes them: one entry for each drive CloudStack gives the
     * instance, numbered from 0, with the ISO it records for the drive or none. An ISO is copied if it has to be.
     */
    public List<Map<String, Object>> drives(Host host, DiskTO[] disks, Runner runner) {
        TreeMap<Long, DiskTO> slots = new TreeMap<>();
        for (DiskTO disk : disks == null ? new DiskTO[0] : disks) {
            // A drive CloudStack builds itself, such as a config drive, names a path and is not one of these.
            if (disk != null && disk.getType() == Volume.Type.ISO && disk.getDiskSeq() != null && disk.getPath() == null) {
                slots.put(disk.getDiskSeq(), disk);
            }
        }
        List<Map<String, Object>> drives = new ArrayList<>();
        for (Map.Entry<Long, DiskTO> slot : slots.entrySet()) {
            if (slot.getKey() - TemplateManager.CDROM_PRIMARY_DEVICE_SEQ != drives.size() || drives.size() > 3) {
                throw new CloudRuntimeException("The instance's CD drives are not numbered as expected");
            }
            VMTemplateVO iso = iso(slot.getValue().getData());
            Map<String, Object> drive = new HashMap<>();
            drive.put("drive", drives.size());
            drive.put("volume", iso == null ? "" : ensure(host, iso, runner));
            drive.put("bootable", iso != null && iso.isBootable());
            drives.add(drive);
        }
        return drives;
    }

    /** The ISO's own file on the orchestrator's storage, when its details name one. */
    private String ownFile(VirtualMachineTemplate iso) {
        Map<String, String> details = templateDetailsDao.listDetailsKeyPairs(iso.getId());
        String path = details == null ? null : details.get(PATH_DETAIL);
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        if (!VOLUME.matcher(path).matches()) {
            throw reject("The ISO's " + PATH_DETAIL + " detail is not an ISO volume of the external system");
        }
        return path;
    }

    /** Where the ISO is, or would be after a copy, on the storage of the host's cluster. */
    public String volume(Host host, VirtualMachineTemplate iso) {
        if (iso.getFormat() != ImageFormat.ISO) {
            throw reject("Not an ISO");
        }
        String own = ownFile(iso);
        if (own != null) {
            return own;
        }
        Map<String, Map<String, String>> access = extensionsManager.getExternalAccessDetails(host, null);
        String storage = null;
        for (String scope : Arrays.asList("resourcemap", "extension")) {
            Map<String, String> settings = access == null ? null : access.get(scope);
            if (storage == null && settings != null && settings.get(STORAGE_SETTING) != null && !settings.get(STORAGE_SETTING).trim().isEmpty()) {
                storage = settings.get(STORAGE_SETTING).trim();
            }
        }
        if (storage == null || !STORAGE.matcher(storage).matches()) {
            throw reject("The cluster has no ISO storage set (" + STORAGE_SETTING + ")");
        }
        return storage + ":iso/cloudstack-" + iso.getUuid() + ".iso";
    }

    /** The algorithm, as the orchestrator names it, and the value of a recorded checksum; null when there is none it can check. */
    static String[] checksum(String recorded) {
        if (recorded == null || recorded.trim().isEmpty()) {
            return null;
        }
        String value = recorded.trim();
        String algorithm = null;
        Matcher tagged = TAGGED_CHECKSUM.matcher(value);
        if (tagged.matches()) {
            algorithm = tagged.group(1).toLowerCase().replace("-", "");
            value = tagged.group(2);
        } else if (value.matches("[0-9a-fA-F]+")) {
            for (Map.Entry<String, Integer> known : CHECKSUM_LENGTHS.entrySet()) {
                if (known.getValue() == value.length()) {
                    algorithm = known.getKey();
                }
            }
        }
        Integer length = algorithm == null ? null : CHECKSUM_LENGTHS.get(algorithm);
        return length == null || length != value.length() ? null : new String[] {algorithm, value.toLowerCase()};
    }

    private JsonObject result(Pair<Boolean, String> answer, String what) {
        if (answer == null || !answer.first()) {
            throw new CloudRuntimeException(String.format("Unable to %s on the external system: %s", what,
                    answer == null ? "no answer" : ExternalVolumeOperations.reason(answer.second())));
        }
        try {
            String output = answer.second().trim();
            JsonObject result = JsonParser.parseString(output.substring(output.lastIndexOf("\n") + 1)).getAsJsonObject();
            if (!"success".equals(result.get("status").getAsString())) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (RuntimeException e) {
            throw new CloudRuntimeException("The external system did not say what it did to " + what);
        }
    }

    /** A link the orchestrator can download the ISO from, made the way an ISO download link is made. */
    protected String downloadLink(VirtualMachineTemplate iso, long zoneId) {
        TemplateDataStoreVO ref = templateStoreDao.findByTemplateZoneReady(iso.getId(), zoneId);
        if (ref == null) {
            throw reject("The ISO is not ready on the secondary storage of this zone");
        }
        if (ref.getExtractUrl() != null) {
            return ref.getExtractUrl();
        }
        DataStore store = dataStoreManager.getDataStore(ref.getDataStoreId(), DataStoreRole.Image);
        TemplateInfo info = templateFactory.getTemplate(iso.getId(), store);
        String link = ((ImageStoreEntity) store).createEntityExtractUrl(info.getInstallPath(), iso.getFormat(), info);
        if (link == null || !link.matches("https?://\\S+")) {
            throw new CloudRuntimeException("The secondary storage gave no download link for the ISO");
        }
        ref.setExtractUrl(link);
        ref.setExtractUrlCreated(DateUtil.now());
        templateStoreDao.update(ref.getId(), ref);
        return link;
    }

    /**
     * Makes sure the ISO is on the storage of the host's cluster and returns where. One copy of an ISO is made at
     * a time: clusters can share the storage.
     */
    public String ensure(Host host, VirtualMachineTemplate iso, Runner runner) {
        String volume = volume(host, iso);
        return oneCopyAtATime(iso.getId(), () -> {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("volume", volume);
            boolean own = ownFile(iso) != null;
            TemplateDataStoreVO ref = own ? null : templateStoreDao.findByTemplateZoneReady(iso.getId(), host.getDataCenterId());
            if (ref != null && ref.getSize() > 0) {
                parameters.put("size", ref.getSize());
            }
            if (present(runner.run("fetchiso", parameters, READ_WAIT_SECONDS))) {
                return volume;
            }
            if (own) {
                throw reject("The file the ISO's " + PATH_DETAIL + " detail names is not on the external system's storage");
            }
            String[] checksum = checksum(iso.getChecksum());
            if (checksum == null) {
                throw reject("The ISO has no checksum recorded, so a copy of it could not be verified. Record its checksum and try again.");
            }
            parameters.put("url", downloadLink(iso, host.getDataCenterId()));
            parameters.put("checksumalgorithm", checksum[0]);
            parameters.put("checksum", checksum[1]);
            if (!present(runner.run("fetchiso", parameters, COPY_WAIT_SECONDS))) {
                throw new CloudRuntimeException("The ISO is not on the external system's storage after the copy");
            }
            logger.info("Copied ISO {} to the external system as {}", iso.getUuid(), volume);
            return volume;
        });
    }

    protected <T> T oneCopyAtATime(long isoId, Supplier<T> work) {
        GlobalLock lock = GlobalLock.getInternLock("external.iso." + isoId);
        try {
            if (!lock.lock(COPY_WAIT_SECONDS)) {
                throw new CloudRuntimeException("Another copy of the ISO to the external system has not finished");
            }
            try {
                return work.get();
            } finally {
                lock.unlock();
            }
        } finally {
            lock.releaseRef();
        }
    }

    private boolean present(Pair<Boolean, String> answer) {
        JsonElement present = result(answer, "find the ISO").get("present");
        return present != null && present.isJsonPrimitive() && present.getAsBoolean();
    }

    /** Runs a cluster action through the host's agent. Not for use while that agent is running a command. */
    protected Runner throughAgent(HostVO host) {
        return (action, parameters, wait) -> {
            RunCustomActionCommand cmd = new RunCustomActionCommand(action);
            cmd.setParameters(parameters);
            cmd.setExternalDetails(extensionsManager.getExternalAccessDetails(host, null));
            cmd.setWait(wait);
            Answer answer = agentManager.easySend(host.getId(), cmd);
            return answer == null ? null : new Pair<>(answer.getResult(), answer.getDetails());
        };
    }

    private HostVO upHost(long clusterId) {
        for (HostVO host : hostDao.findByClusterId(clusterId)) {
            if (host.getHypervisorType() == HypervisorType.External && host.getStatus() == Status.Up && host.getRemoved() == null) {
                return host;
            }
        }
        return null;
    }

    /** The one CloudStack instance of a cluster recorded with that VMID at the orchestrator; null when none or several. */
    protected VMInstanceVO instanceOf(long clusterId, long vmid) {
        Set<Long> ids = new HashSet<>();
        for (String key : Arrays.asList("proxmox_vmid", "External:proxmox_vmid")) {
            for (VMInstanceDetailVO detail : vmDetailsDao.findDetails(key, Long.toString(vmid), null)) {
                ids.add(detail.getResourceId());
            }
        }
        VMInstanceVO match = null;
        for (Long id : ids) {
            VMInstanceVO vm = vmDao.findById(id);
            if (vm == null || vm.getRemoved() != null || vm.getHypervisorType() != HypervisorType.External) {
                continue;
            }
            Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
            HostVO host = hostId == null ? null : hostDao.findById(hostId);
            if (host == null || host.getClusterId() == null || host.getClusterId() != clusterId) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = vm;
        }
        return match;
    }

    @Override
    public List<String> removeCopies(VirtualMachineTemplate iso, Long zoneId) {
        List<String> kept = new ArrayList<>();
        if (iso.getFormat() != ImageFormat.ISO || ownFile(iso) != null) {
            return kept;
        }
        Map<ClusterVO, String> copies = new LinkedHashMap<>();
        for (ClusterVO cluster : zoneId == null ? clusterDao.listAll() : clusterDao.listByZoneId(zoneId)) {
            if (cluster.getRemoved() != null || cluster.getHypervisorType() != HypervisorType.External || !supported(cluster.getId())) {
                continue;
            }
            HostVO host = upHost(cluster.getId());
            if (host == null) {
                kept.add("cluster " + cluster.getName() + " has no host that is Up; any copy of the ISO there was kept");
                continue;
            }
            try {
                copies.put(cluster, volume(host, iso));
            } catch (InvalidParameterValueException e) {
                // A cluster with no ISO storage has no copies.
            }
        }
        // Every cluster first: clusters can share the storage, and a copy is only deleted when no instance of any of them holds it.
        for (Map.Entry<ClusterVO, String> copy : copies.entrySet()) {
            ClusterVO cluster = copy.getKey();
            try {
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("volume", copy.getValue());
                JsonObject found = result(throughAgent(upHost(cluster.getId())).run("findiso", parameters, READ_WAIT_SECONDS), "find the ISO in CD drives");
                for (JsonElement element : found.getAsJsonArray("guests")) {
                    long vmid = element.getAsJsonObject().get("vmid").getAsLong();
                    VMInstanceVO vm = instanceOf(cluster.getId(), vmid);
                    if (vm == null || vm.getState() != VirtualMachine.State.Running && vm.getState() != VirtualMachine.State.Stopped) {
                        kept.add("guest " + vmid + " of cluster " + cluster.getName() + " has the ISO in a CD drive and is not an instance that can be changed; the copy was kept");
                        continue;
                    }
                    Answer answer = null;
                    try {
                        answer = instanceLock.hold(vm.getId(), 1, () -> extensionsManager.changeInstanceIso(vm, "detachiso", new HashMap<>(parameters)));
                    } catch (RuntimeException busy) {
                        logger.debug("Instance {} is being changed; the ISO was not ejected from it", vm.getUuid());
                    }
                    if (answer == null || !answer.getResult()) {
                        kept.add("the ISO could not be ejected from instance " + vm.getUuid() + "; the copy was kept");
                    }
                }
            } catch (RuntimeException e) {
                kept.add("cluster " + cluster.getName() + " could not be checked for instances holding the ISO; the copy was kept");
            }
        }
        if (!kept.isEmpty()) {
            return kept;
        }
        for (Map.Entry<ClusterVO, String> copy : copies.entrySet()) {
            try {
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("volume", copy.getValue());
                result(throughAgent(upHost(copy.getKey().getId())).run("removeiso", parameters, READ_WAIT_SECONDS), "delete the ISO copy");
            } catch (RuntimeException e) {
                kept.add("the copy " + copy.getValue() + " on cluster " + copy.getKey().getName() + " could not be deleted: " + e.getMessage());
            }
        }
        return kept;
    }

    /** The orchestrator's name for a kind of guest. */
    protected String osType(long guestOsId) {
        GuestOSVO os = guestOsDao.findById(guestOsId);
        String name = os == null || os.getDisplayName() == null ? "" : os.getDisplayName().toLowerCase();
        if (name.contains("windows")) {
            if (name.matches(".*(\\b11\\b|2022|2025).*")) {
                return "win11";
            } else if (name.matches(".*(\\b8(\\.1)?\\b|2012).*")) {
                return "win8";
            } else if (name.matches(".*(\\b7\\b|2008).*")) {
                return "win7";
            }
            return "win10";
        }
        return name.matches(".*(linux|ubuntu|debian|centos|red hat|rhel|rocky|alma|suse|fedora|oracle|alpine|coreos|photon).*") ? "l26" : "other";
    }

    /**
     * What the extension needs to create an instance from an ISO, which a template would have supplied: the
     * storage and size of the root disk, taken from the root volume the instance was allocated; the kind of
     * guest; and the firmware asked for when the instance was deployed. What goes in its CD drives is what
     * CloudStack records at the start, as for any start.
     */
    public Map<String, String> deployment(Host host, VMInstanceVO vm) {
        VolumeVO root = null;
        for (VolumeVO volume : volumeDao.findByInstanceAndType(vm.getId(), Volume.Type.ROOT)) {
            if (volume.getRemoved() == null && volume.getState() == Volume.State.Allocated && root == null) {
                root = volume;
            }
        }
        if (root == null || root.getSize() == null || root.getSize() <= 0 || root.getSize() % GIB != 0) {
            throw reject("The instance has no root disk of a whole number of GiB to create");
        }
        DiskOfferingVO offering = diskOfferingDao.findById(root.getDiskOfferingId());
        StoragePoolVO pool = volumeSync.pool(vm.getDataCenterId(), offering);
        if (pool == null || host.getClusterId() == null || !inventory.reaches(pool, host.getClusterId(), true)) {
            throw reject("The root disk offering must be the disk offering of one External storage that the instance's cluster reaches");
        }
        VMInstanceDetailVO uefi = vmDetailsDao.findDetail(vm.getId(), "UEFI");
        Map<String, String> details = new HashMap<>();
        details.put("template_type", "ISO");
        details.put("iso_os_type", osType(vm.getGuestOSId()));
        details.put("storage", pool.getPath());
        details.put("disk_size_gb", Long.toString(root.getSize() / GIB));
        details.put("firmware", uefi == null ? "bios" : "SECURE".equalsIgnoreCase(uefi.getValue()) ? "uefi-secure" : "uefi");
        return details;
    }
}
