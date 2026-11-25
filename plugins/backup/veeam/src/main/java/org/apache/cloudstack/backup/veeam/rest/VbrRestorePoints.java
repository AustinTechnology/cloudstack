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

package org.apache.cloudstack.backup.veeam.rest;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.cloudstack.backup.Backup;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Restore points of External (Proxmox, Hyper-V) instances as Veeam reports them, gathered once per
 * backup-sync cycle outside any database transaction. The framework's sync then reads them from memory.
 *
 * An instance is matched to Veeam by its hypervisor UUID (Proxmox SMBIOS UUID, Hyper-V VM GUID). Every restore
 * point Veeam lists for it is shown, whatever job or copy made it (its own job, a former shared job, backup copies
 * on repositories this Veeam server manages), so history stays visible when the instance changes job. An instance
 * given a job id lists only that job's points.
 */
public class VbrRestorePoints {
    private static final Logger LOG = LogManager.getLogger(VbrRestorePoints.class);

    /** What the provider knows about one instance to be synced. */
    public static class Instance {
        public final long id;
        public final String uuid;
        public final String jobId;

        public Instance(long id, String uuid, String jobId) {
            this.id = id;
            this.uuid = uuid;
            this.jobId = jobId;
        }
    }

    private final Map<Long, List<Backup.RestorePoint>> byInstance = new ConcurrentHashMap<>();
    /** Instances Veeam reported without restore points in the previous cycle; an empty answer counts only when repeated. */
    private final Set<Long> emptyLastCycle = ConcurrentHashMap.newKeySet();

    /** The last synced restore points, or null when this cycle could not read them (the framework then keeps its records). */
    public List<Backup.RestorePoint> get(long instanceId) {
        return byInstance.get(instanceId);
    }

    public void forget(long instanceId) {
        byInstance.remove(instanceId);
    }

    public void sync(VbrRestClient client, Collection<Instance> instances) {
        Map<String, String> backupToJob = client.listBackupJobIds();
        List<VbrRestClient.BackupObject> objects = client.listBackupObjects();
        // Veeam 13.1 now and then answers /backups or /backupObjects with an empty list (HTTP 200). Taken at face value
        // that would delete every backup record in CloudStack and re-create them next cycle: skip such a cycle.
        if (backupToJob.isEmpty() || objects.isEmpty()) {
            LOG.warn("Veeam returned {} backups and {} backup objects; keeping the restore points of the previous sync",
                    backupToJob.size(), objects.size());
            return;
        }
        // Veeam lists a machine once per backup holding it (the job's and each copy's), under one object id.
        Map<String, Set<String>> objectIdsByUuid = new HashMap<>();
        Map<String, Integer> pointsReported = new HashMap<>();
        for (VbrRestClient.BackupObject o : objects) {
            if (o.objectId != null && o.id != null) {
                objectIdsByUuid.computeIfAbsent(o.objectId.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>()).add(o.id);
                pointsReported.merge(o.id, o.restorePointsCount, Math::max);
            }
        }
        Map<String, Map<String, VbrRestClient.BackupFile>> filesByBackup = new HashMap<>();
        Function<String, Map<String, VbrRestClient.BackupFile>> files = backupId -> filesByBackup.computeIfAbsent(backupId, id -> {
            Map<String, VbrRestClient.BackupFile> byFile = new HashMap<>();
            for (VbrRestClient.BackupFile f : client.listBackupFiles(id)) {
                byFile.put(f.id, f);
            }
            return byFile;
        });
        for (Instance instance : instances) {
            if (instance.uuid == null) {
                byInstance.remove(instance.id);
                continue;
            }
            try {
                List<Backup.RestorePoint> points = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                boolean incomplete = false;
                for (String objectId : objectIdsByUuid.getOrDefault(instance.uuid.toLowerCase(Locale.ROOT), Set.of())) {
                    List<VbrRestClient.RestorePoint> listed = client.listRestorePoints(objectId);
                    if (listed.isEmpty() && pointsReported.getOrDefault(objectId, 0) > 0) {
                        incomplete = true;
                    }
                    for (VbrRestClient.RestorePoint p : listed) {
                        // A restore point names the job's backup (an object's own backupId is its per-machine chain).
                        if ((instance.jobId != null && !Objects.equals(instance.jobId, backupToJob.get(p.backupId))) || !seen.add(p.id)) {
                            continue;
                        }
                        VbrRestClient.BackupFile file = p.backupFileId == null ? null : files.apply(p.backupId).get(p.backupFileId);
                        points.add(new Backup.RestorePoint(p.id, parse(p.creationTime), type(p.type),
                                file == null ? 0L : file.backupSize, file == null ? 0L : file.dataSize));
                    }
                }
                if (incomplete) {
                    LOG.warn("Veeam listed no restore points for a backup object of instance {} that reports some; keeping the previous sync", instance.id);
                    continue;
                }
                if (points.isEmpty()) {
                    if (emptyLastCycle.add(instance.id)) {
                        // An empty answer is believed only when the next sync agrees: until then CloudStack keeps its records.
                        continue;
                    }
                } else {
                    emptyLastCycle.remove(instance.id);
                }
                byInstance.put(instance.id, points);
            } catch (RuntimeException e) {
                byInstance.remove(instance.id);
                LOG.warn("Could not read Veeam restore points of instance {}: {}", instance.id, e.getMessage());
            }
        }
    }

    static Date parse(String time) {
        return time == null ? null : Date.from(OffsetDateTime.parse(time).toInstant());
    }

    static String type(String veeamType) {
        if ("Full".equalsIgnoreCase(veeamType)) {
            return "FULL";
        }
        if ("Increment".equalsIgnoreCase(veeamType)) {
            return "INCREMENTAL";
        }
        return veeamType == null ? null : veeamType.toUpperCase(Locale.ROOT);
    }
}
