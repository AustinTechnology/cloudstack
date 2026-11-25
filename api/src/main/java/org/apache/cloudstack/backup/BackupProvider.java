//Licensed to the Apache Software Foundation (ASF) under one
//or more contributor license agreements.  See the NOTICE file
//distributed with this work for additional information
//regarding copyright ownership.  The ASF licenses this file
//to you under the Apache License, Version 2.0 (the
//"License"); you may not use this file except in compliance
//the License.  You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing,
//software distributed under the License is distributed on an
//"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//KIND, either express or implied.  See the License for the
//specific language governing permissions and limitations
//under the License.
package org.apache.cloudstack.backup;

import java.util.List;

import com.cloud.utils.Pair;
import com.cloud.vm.VirtualMachine;

public interface BackupProvider {

    Boolean crossZoneInstanceCreationEnabled(BackupOffering backupOffering);

    /**
     * Returns the unique name of the provider
     * @return returns provider name
     */
    String getName();

    /**
     * Returns description about the backup and recovery provider plugin
     * @return returns description
     */
    String getDescription();

    /**
     * Returns the list of existing backup policies on the provider
     * @return backup policies list
     */
    List<BackupOffering> listBackupOfferings(Long zoneId);

    /**
     * True if a backup offering exists on the backup provider
     */
    boolean isValidProviderOffering(Long zoneId, String uuid);

    /**
     * Assign a VM to a backup offering or policy
     * @param vm the machine to back up
     * @param backupOffering the SLA definition for the backup
     * @return succeeded?
     */
    boolean assignVMToBackupOffering(VirtualMachine vm, BackupOffering backupOffering);

    /**
     * Removes a VM from a backup offering or policy
     * @param vm the machine to stop backing up
     * @return succeeded?
     */
    boolean removeVMFromBackupOffering(VirtualMachine vm);


    /**
     * Whether the provider will delete backups on removal of VM from the offering
     * @return boolean result
     */
    boolean willDeleteBackupsOnOfferingRemoval();

    /**
     * Starts and creates an adhoc backup process
     * for a previously registered VM backup
     *
     * @param vm
     *         the machine to make a backup of
     * @param quiesceVM
     *         instance will be quiesced for checkpointing for backup. Applicable only to NAS plugin.
     * @param isolated
     * @return the result and {code}Backup{code} {code}Object{code}
     */
    Pair<Boolean, Backup> takeBackup(VirtualMachine vm, Boolean quiesceVM, boolean isolated);

    /**
     * Delete an existing backup
     * @param backup The backup to exclude
     * @param forced Indicates if backup will be force removed or not
     * @return succeeded?
     */
    boolean deleteBackup(Backup backup, boolean forced);

    /**
     * Whether {@link #deleteBackup(Backup, boolean)} owns DB-row removal and resource-count /
     * usage accounting for every backup it physically removes. Providers that manage incremental
     * chains (e.g. NAS) delete several backups per call — the leaf plus swept delete-pending
     * ancestors — and decrement once per removed backup themselves, so the manager must NOT
     * decrement or remove the row again. Defaults to {@code false}: the manager does the
     * single-backup accounting (the historical behaviour for non-chain providers).
     */
    default boolean handlesChainDeleteResourceAccounting() {
        return false;
    }

    Pair<Boolean, String> restoreBackupToVM(VirtualMachine vm, Backup backup, String hostIp, String dataStoreUuid, boolean quickrestore);

    /**
     * Restore VM from backup
     */
    boolean restoreVMFromBackup(VirtualMachine vm, Backup backup, boolean quickRestore, Long hostId);

    /**
     * Restore a volume from a backup
     */
    Pair<Boolean, String> restoreBackedUpVolume(Backup backup, Backup.VolumeInfo backupVolumeInfo, String hostIp, String dataStoreUuid,
            Pair<String, VirtualMachine.State> vmNameAndState, VirtualMachine vm, boolean quickRestore);

    /**
     * Syncs backup metrics (backup size, protected size) from the plugin and stores it within the provider
     * @param zoneId the zone for which to return metrics
     */
    void syncBackupMetrics(Long zoneId);

    /**
     * Returns a list of Backup.RestorePoint
     * @param vm the machine to get the restore points for
     */
    List<Backup.RestorePoint> listRestorePoints(VirtualMachine vm);

    /**
     * Creates and returns an entry in the backups table by getting the information from restorePoint and vm.
     *
     * @param restorePoint the restore point to create a backup for
     * @param vm           The machine for which to create a backup
     */
    Backup createNewBackupEntryForRestorePoint(Backup.RestorePoint restorePoint, VirtualMachine vm);

    /**
     * Returns if the backup provider supports creating new instance from backup
     */
    boolean supportsInstanceFromBackup();

    default boolean supportsMemoryVmSnapshot() {
        return true;
    }

    /**
     * Returns the backup storage usage (Used, Total) for a backup provider
     * @param zoneId the zone for which to return metrics
     * @return a pair of Used size and Total size for the backup storage
     */
    Pair<Long, Long> getBackupStorageStats(Long zoneId);

    /**
     * Whether the backup provider itself schedules the backups of instances with this offering (for example a backup
     * job with its own schedule in the backup software). CloudStack then neither creates, deletes nor runs backup
     * schedules for those instances: their schedule records are a read-only copy of {@link #listProviderBackupSchedules}.
     */
    default boolean isBackupScheduleManagedByProvider(BackupOffering offering) {
        return false;
    }

    /**
     * The provider's own schedule for an instance whose offering is {@link #isBackupScheduleManagedByProvider managed
     * by the provider}, at most one per interval type. Only the interval type, schedule, timezone and maximum backups
     * are used. Null when the provider cannot tell now: CloudStack then keeps the records it has. Called by the backup
     * sync, outside database transactions.
     */
    default List<BackupSchedule> listProviderBackupSchedules(VirtualMachine vm) {
        return java.util.Collections.emptyList();
    }

    /**
     * Whether, for an offering {@link #isBackupScheduleManagedByProvider managed by the provider}, CloudStack's schedule
     * records are the provider's schedule: users create, change and delete them as usual, CloudStack never runs them, and
     * the provider applies them to its own scheduler (told through {@link #backupSchedulesChanged}). The backup sync then
     * leaves the records alone instead of copying {@link #listProviderBackupSchedules}.
     */
    default boolean canProviderApplyBackupSchedules(BackupOffering offering) {
        return false;
    }

    /**
     * Called before CloudStack records a schedule of this interval type for an instance whose offering
     * {@link #canProviderApplyBackupSchedules lets the provider apply schedules}; throws when the provider cannot hold it
     * (for example a second schedule where the provider keeps one per instance). Database reads only.
     */
    default void validateBackupSchedule(VirtualMachine vm, com.cloud.utils.DateUtil.IntervalType intervalType) {
    }

    /**
     * CloudStack's schedule records of the instance changed (created, changed or deleted). Called right after the change
     * from the API thread: record the work and make provider calls later, outside this call.
     */
    default void backupSchedulesChanged(VirtualMachine vm) {
    }

    /**
     * Gets the backup storage usage (Used, Total) from the plugin and stores it in db
     * @param zoneId the zone for which to return metrics
     */
    void syncBackupStorageStats(Long zoneId);
}
