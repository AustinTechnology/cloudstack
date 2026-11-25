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

package org.apache.cloudstack.backup;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.veeam.VeeamClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VeeamBackupProviderTest {
    @Spy
    @InjectMocks
    VeeamBackupProvider backupProvider = new VeeamBackupProvider();

    @Mock
    VeeamClient client;

    @Mock
    VMInstanceDao vmInstanceDao;

    @Mock
    BackupDao backupDao;

    @Test
    public void deleteBackupTestExceptionWhenVmIsNull() {
        BackupVO backup = new BackupVO();
        backup.setVmId(1l);
        backup.setExternalId("abc");
        Mockito.when(vmInstanceDao.findByIdIncludingRemoved(Mockito.anyLong())).thenReturn(null);
        try {
            backupProvider.deleteBackup(backup, false);
        } catch (Exception e) {
            assertEquals(CloudRuntimeException.class, e.getClass());
            String expected = String.format("Could not find any VM associated with the Backup [uuid: %s, name: null, externalId: %s].", backup.getUuid(), "abc");
            assertEquals(expected , e.getMessage());
        }
    }

    @Test
    public void deleteBackupTestExceptionWhenForcedIsFalse() {
        VMInstanceVO vmInstanceVO = new VMInstanceVO();
        BackupVO backup = new BackupVO();
        backup.setVmId(1l);
        backup.setExternalId("abc");
        Mockito.when(vmInstanceDao.findByIdIncludingRemoved(Mockito.anyLong())).thenReturn(vmInstanceVO);
        try {
            backupProvider.deleteBackup(backup, false);
        } catch (Exception e) {
            assertEquals(CloudRuntimeException.class, e.getClass());
            String expected = "Veeam backup provider does not have a safe way to remove a single restore point, which results in all backup chain being removed. "
                    + "Use forced:true to skip this verification and remove the complete backup chain.";
            assertEquals(expected , e.getMessage());
        }
    }

    @Test
    public void deleteBackupTestSuccessWhenForcedIsTrueAndHasJustOneBackup() {
        VMInstanceVO vmInstanceVO = new VMInstanceVO();
        vmInstanceVO.setInstanceName("test");
        vmInstanceVO.setDataCenterId(2l);
        BackupVO backup = new BackupVO();
        backup.setVmId(1l);
        backup.setExternalId("abc");
        backup.setType("Full");
        backup.setZoneId(3l);
        backup.setBackupOfferingId(4l);

        Mockito.when(vmInstanceDao.findByIdIncludingRemoved(Mockito.anyLong())).thenReturn(vmInstanceVO);
        Mockito.doReturn(client).when(backupProvider).getClient(2l);
        Mockito.doReturn(true).when(client).deleteBackup("abc");
        List<Backup> backups = new ArrayList<>();
        backups.add(backup);
        Mockito.when(backupDao.listByVmIdAndOffering(3l, 1l, 4l)).thenReturn(backups);
        Mockito.verify(backupDao, Mockito.never()).remove(Mockito.anyLong());
        boolean result = backupProvider.deleteBackup(backup, true);
        assertEquals(true, result);
    }

    @Test
    public void deleteBackupTestSuccessWhenForcedIsTrueAndHasMoreThanOneBackup() {
        VMInstanceVO vmInstanceVO = new VMInstanceVO();
        vmInstanceVO.setInstanceName("test");
        vmInstanceVO.setDataCenterId(2l);
        BackupVO backup = Mockito.mock(BackupVO.class);
        Mockito.when(backup.getId()).thenReturn(1l);
        Mockito.when(backup.getVmId()).thenReturn(1l);
        Mockito.when(backup.getExternalId()).thenReturn("abc");
        Mockito.when(backup.getZoneId()).thenReturn(3l);
        Mockito.when(backup.getBackupOfferingId()).thenReturn(4l);

        BackupVO backup2 = Mockito.mock(BackupVO.class);
        Mockito.when(backup2.getId()).thenReturn(2l);

        Mockito.when(vmInstanceDao.findByIdIncludingRemoved(Mockito.anyLong())).thenReturn(vmInstanceVO);
        Mockito.doReturn(client).when(backupProvider).getClient(2l);
        Mockito.doReturn(true).when(client).deleteBackup("abc");
        Mockito.when(backupDao.listByVmIdAndOffering(3l, 1l, 4l)).thenReturn(List.of(backup, backup2));
        boolean result = backupProvider.deleteBackup(backup, true);
        Mockito.verify(backupDao, Mockito.times(1)).remove(2l);
        assertEquals(true, result);
    }

    /** A template job's Veeam schedule becomes the instance's first CloudStack schedule (minute:hour[:day]). */
    @Test
    public void scheduleOfProxmoxJobMapsTheTemplateSchedule() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        String daily = "{\"enabled\":true,\"type\":\"Weekly\",\"weekly\":{\"startTime\":\"22:05\",\"configuredDays\":\"EveryDay\",\"selectedDays\":[\"Monday\",\"Sunday\"]}}";
        assertEquals(new com.cloud.utils.Pair<>(com.cloud.utils.DateUtil.IntervalType.DAILY, "05:22"), backupProvider.scheduleOfProxmoxJob(json.readTree(daily)));
        String weekly = "{\"enabled\":true,\"type\":\"Weekly\",\"weekly\":{\"startTime\":\"10:00\",\"configuredDays\":\"SelectedDays\",\"selectedDays\":[\"Saturday\"]}}";
        assertEquals(new com.cloud.utils.Pair<>(com.cloud.utils.DateUtil.IntervalType.WEEKLY, "00:10:7"), backupProvider.scheduleOfProxmoxJob(json.readTree(weekly)));
        String monthly = "{\"enabled\":true,\"type\":\"Monthly\",\"monthly\":{\"startTime\":\"01:30\",\"configuredWeekOrDay\":\"DayOfMonth\",\"dayOfMonth\":3}}";
        assertEquals(new com.cloud.utils.Pair<>(com.cloud.utils.DateUtil.IntervalType.MONTHLY, "30:01:3"), backupProvider.scheduleOfProxmoxJob(json.readTree(monthly)));
        assertEquals(null, backupProvider.scheduleOfProxmoxJob(json.readTree("{\"enabled\":false,\"type\":\"Weekly\"}")));
    }

    /** CloudStack schedules are written to Veeam in the Veeam server's local time. */
    @Test
    public void writeProxmoxScheduleConvertsToTheVeeamServersTime() {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        java.time.ZoneOffset awst = java.time.ZoneOffset.ofHours(8);
        com.fasterxml.jackson.databind.node.ObjectNode settings = json.createObjectNode();
        backupProvider.writeProxmoxSchedule(settings, new BackupScheduleVO(1L, com.cloud.utils.DateUtil.IntervalType.DAILY, "00:23", "GMT", null, 0, null, 1L, 1L, false), awst);
        assertEquals("Weekly", settings.at("/scheduleSettings/type").asText());
        assertEquals("07:00", settings.at("/scheduleSettings/weekly/startTime").asText());
        assertEquals("EveryDay", settings.at("/scheduleSettings/weekly/configuredDays").asText());
        backupProvider.writeProxmoxSchedule(settings, new BackupScheduleVO(1L, com.cloud.utils.DateUtil.IntervalType.WEEKLY, "30:01:2", "GMT+08:00", null, 0, null, 1L, 1L, false), awst);
        assertEquals("01:30", settings.at("/scheduleSettings/weekly/startTime").asText());
        assertEquals("[\"Monday\"]", settings.at("/scheduleSettings/weekly/selectedDays").toString());
        backupProvider.writeProxmoxSchedule(settings, null, awst);
        assertEquals(false, settings.at("/scheduleSettings/enabled").asBoolean());
    }
}
