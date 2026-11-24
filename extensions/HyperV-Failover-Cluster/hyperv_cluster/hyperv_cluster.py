#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
"""CloudStack External orchestrator extension for Hyper-V in Windows Server Failover Clusters.

CloudStack runs this on the management server as ``hyperv_cluster.py ACTION PAYLOAD.json WAIT``. The
extension speaks PowerShell to the cluster's nodes over WinRM (HTTPS). See docs/DESIGN.md.

Exit status: 0 success; 90 the request failed and nothing was changed at the cluster; 91 the outcome is
not known (something was sent that may have changed the cluster). CloudStack keeps the operation lease of
an adopted instance on 91, and releases it on 90.
"""

import base64
import hashlib
import ipaddress
import json
import re
import secrets
import sys
import time
from concurrent.futures import ThreadPoolExecutor

KNOWN_FAILURE = 90
UNKNOWN_OUTCOME = 91
VMID_LIMIT = 2147483646
GIB = 1024 ** 3
STORAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9._-]*$")
ISO_VOLUME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9._-]*:iso/[^,/\\:]+$")
UUID4_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
GUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
MARKER_RE = re.compile(r"\[cloudstack-vmid:([1-9][0-9]{0,9}):([0-9a-fA-F-]{36})\]")
HASH_ALGORITHMS = {"md5": "MD5", "sha1": "SHA1", "sha256": "SHA256", "sha384": "SHA384", "sha512": "SHA512"}
SLOTS_PER_CONTROLLER = 25  # SCSI controller c, location l is disk slot scsi<25c+l>: the contract allows 0-99.

# Native adoption: action -> operation name the manager leases. "" is a read with no lease.
NATIVE_OPERATIONS = {
    "start": "start", "stop": "stop", "reboot": "reboot", "setha": "setha", "scale": "scale",
    "plugnic": "plugnic", "unplugnic": "unplugnic", "adddisk": "adddisk", "resizedisk": "resizedisk",
    "deletedisk": "deletedisk", "movedisk": "movedisk", "attachiso": "attachiso", "detachiso": "detachiso",
    "getconsole": "console", "createsnapshot": "create_snapshot", "revertsnapshot": "restore_snapshot",
    "deletesnapshot": "delete_snapshot", "delete": "delete",
    "getconfig": "", "status": "", "getipaddress": "",
}
ADOPTION_KEYS = ("adopt_claim_id", "adopt_claim_generation", "adopt_manifest_sha256", "adopt_manifest_json",
                 "adopt_execution_plan_sha256", "adopt_ip_overrides_json")


class Fail(Exception):
    """A failed request. known=True when nothing was changed at the cluster."""

    def __init__(self, message, known=True):
        super().__init__(message)
        self.known = known


# --------------------------------------------------------------------------------------------------------
# PowerShell. Every script gets $A (its arguments) and these helpers. A check that refuses before changing
# anything throws "UNCHANGED:<reason>"; any other error after a write is an unknown outcome.
# --------------------------------------------------------------------------------------------------------

PS_PRELUDE = r"""
$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'; $WarningPreference = 'SilentlyContinue'
function Unchanged([string]$m) { throw ('UNCHANGED:' + $m) }
function Root([string]$s) { $r = $A.roots.$s; if (-not $r) { Unchanged ('The cluster has no storage ' + $s) }; [string]$r }
function FullPath([string]$vol) {
  $i = $vol.IndexOf(':'); if ($i -lt 1) { Unchanged 'Invalid volume' }
  Join-Path (Root $vol.Substring(0, $i)) ($vol.Substring($i + 1).Replace('/', '\'))
}
function TheVM {
  $v = Get-VM -Id $A.guid -ErrorAction SilentlyContinue
  if (-not $v) { Unchanged 'VM_ABSENT' }
  if ($v.Name -cne $A.name) { Unchanged 'The VM at this identity has another name' }
  $v
}
function Settled($v) {
  if (@('Running', 'Off') -notcontains [string]$v.State) { Unchanged ('The VM is ' + $v.State) }
  $ops = @($v.OperationalStatus | ForEach-Object { [string]$_ })
  if ($ops.Count -gt 0 -and $ops[0] -ne 'Ok') { Unchanged ('Hyper-V has an operation in progress on the VM: ' + ($ops -join ', ')) }
}
function BaseOf([string]$p) { $b = $p; while ($b -match '\.avhdx$') { $b = [string](Get-VHD -Path $b).ParentPath }; $b }
function Describe($v) {
  $mem = Get-VMMemory -VM $v
  $nics = @(Get-VMNetworkAdapter -VM $v | ForEach-Object {
    $vl = Get-VMNetworkAdapterVlan -VMNetworkAdapter $_
    [pscustomobject]@{ name = [string]$_.Name; id = [string]$_.Id; mac = [string]$_.MacAddress; switch = [string]$_.SwitchName
      mode = [string]$vl.OperationMode; vlan = [int]$vl.AccessVlanId; ips = @($_.IPAddresses | ForEach-Object { [string]$_ }) } })
  $disks = @(Get-VMHardDiskDrive -VM $v | ForEach-Object {
    $p = [string]$_.Path; $size = $null; $base = $null
    if ($p) { $size = [int64](Get-VHD -Path $p).Size; $base = BaseOf $p }
    [pscustomobject]@{ ctype = [string]$_.ControllerType; cnum = [int]$_.ControllerNumber; loc = [int]$_.ControllerLocation
      path = $p; base = $base; size = $size } })
  $dvds = @(Get-VMDvdDrive -VM $v | ForEach-Object {
    [pscustomobject]@{ ctype = [string]$_.ControllerType; cnum = [int]$_.ControllerNumber; loc = [int]$_.ControllerLocation; path = [string]$_.Path } })
  $boot = @(); $secure = ''; $sbt = ''
  if ($v.Generation -eq 2) {
    $f = Get-VMFirmware -VM $v; $secure = [string]$f.SecureBoot; $sbt = [string]$f.SecureBootTemplate
    $boot = @($f.BootOrder | ForEach-Object {
      $d = $_.Device
      if ($d -and $d.PSObject.Properties['ControllerLocation']) {
        [pscustomobject]@{ kind = $d.GetType().Name; ctype = [string]$d.ControllerType; cnum = [int]$d.ControllerNumber; loc = [int]$d.ControllerLocation }
      } else { [pscustomobject]@{ kind = [string]$_.BootType } } })
  } else { $boot = @((Get-VMBios -VM $v).StartupOrder | ForEach-Object { [pscustomobject]@{ kind = [string]$_ } }) }
  $tpm = $false; try { $tpm = [bool](Get-VMSecurity -VM $v).TpmEnabled } catch { }
  $kvp = Get-VMIntegrationService -VM $v | Where-Object { $_.Name -eq 'Key-Value Pair Exchange' }
  $shut = Get-VMIntegrationService -VM $v | Where-Object { $_.Name -eq 'Shutdown' }
  [pscustomobject]@{ guid = [string]$v.Id; name = [string]$v.Name; state = [string]$v.State
    ops = @($v.OperationalStatus | ForEach-Object { [string]$_ }); node = [string]$v.ComputerName
    generation = [int]$v.Generation; version = [string]$v.Version; cpus = [int]$v.ProcessorCount
    memory = [int64]$mem.Startup; dynamic = [bool]$mem.DynamicMemoryEnabled; clustered = [bool]$v.IsClustered
    uptime = [int64]$v.Uptime.TotalSeconds; notes = [string]$v.Notes
    kvp = [bool]($kvp -and $kvp.Enabled -and [string]$kvp.PrimaryStatusDescription -eq 'OK')
    shutdown = [bool]($shut -and $shut.Enabled -and [string]$shut.PrimaryStatusDescription -eq 'OK')
    checkpointtype = [string]$v.CheckpointType; current = [string]$v.ParentSnapshotName
    checkpoints = @(Get-VMSnapshot -VM $v | ForEach-Object { [pscustomobject]@{ name = [string]$_.Name; parent = [string]$_.ParentSnapshotName; state = [string]$_.State } })
    nics = $nics; disks = $disks; dvds = $dvds; boot = $boot; secure = $secure; sbtemplate = $sbt; tpm = $tpm }
}
function WaitState($v, [string]$want, [int]$seconds) {
  $deadline = (Get-Date).AddSeconds($seconds)
  while ((Get-Date) -lt $deadline) { $v = Get-VM -Id $v.Id; if ([string]$v.State -eq $want) { return $v }; Start-Sleep -Seconds 1 }
  throw ('The VM did not reach ' + $want)
}
function GroupOf($v) {
  if (-not $v.IsClustered) { return $null }
  Get-ClusterResource | Where-Object { $_.ResourceType -eq 'Virtual Machine' } | Where-Object {
    [string](Get-ClusterParameter -InputObject $_ -Name VmID).Value -eq [string]$v.Id } | ForEach-Object { $_.OwnerGroup } | Select-Object -First 1
}
"""

SCRIPTS = {
    # Read-only.
    "cluster": r"""
$c = Get-Cluster
[pscustomobject]@{ cluster = [string]$c.Name
  nodes = @(Get-ClusterNode | ForEach-Object { [pscustomobject]@{ name = [string]$_.Name; state = [string]$_.State } })
  groups = @(Get-ClusterResource | Where-Object { $_.ResourceType -eq 'Virtual Machine' } | ForEach-Object {
    [pscustomobject]@{ guid = [string](Get-ClusterParameter -InputObject $_ -Name VmID).Value; group = [string]$_.OwnerGroup.Name
      state = [string]$_.OwnerGroup.State; owner = [string]$_.OwnerNode.Name } }) }
""",
    "nodes": r"""
[pscustomobject]@{ cluster = [string](Get-Cluster).Name; local = [string]$env:COMPUTERNAME
  nodes = @(Get-ClusterNode | ForEach-Object { [pscustomobject]@{ name = [string]$_.Name; state = [string]$_.State } }) }
""",
    "inventory": r"""
[pscustomobject]@{ node = [string]$env:COMPUTERNAME; vms = @(Get-VM | ForEach-Object {
  [pscustomobject]@{ guid = [string]$_.Id; name = [string]$_.Name; state = [string]$_.State; notes = [string]$_.Notes
    clustered = [bool]$_.IsClustered; ops = @($_.OperationalStatus | ForEach-Object { [string]$_ }) } }) }
""",
    "describe": r"""
$v = TheVM; $a = Describe $v; $b = Describe (Get-VM -Id $v.Id)
[pscustomobject]@{ first = $a; second = $b }
""",
    "hoststats": r"""
$os = Get-CimInstance Win32_OperatingSystem
# Formatted counters read once by a fresh WMI client are not a utilisation (often 0 or 100); take two raw samples
# of every logical processor a few seconds apart: % = run-time delta / (elapsed * processors).
$lp = { @(Get-CimInstance Win32_PerfRawData_HvStats_HyperVHypervisorLogicalProcessor | Where-Object { $_.Name -ne '_Total' }) }
# The first perf query starts the WMI perf provider (CPU-heavy on a small host): warm it up, then measure 3 s.
$null = & $lp; Start-Sleep -Seconds 1; $a = & $lp; Start-Sleep -Seconds 3; $b = & $lp
if ($a.Count -eq 0 -or $a.Count -ne $b.Count) { Unchanged 'Hyper-V logical processor counters unavailable' }
$run = 0.0; foreach ($x in $b) { $y = $a | Where-Object Name -eq $x.Name; $run += [double]$x.PercentTotalRunTime - [double]$y.PercentTotalRunTime }
$elapsed = [double]$b[0].Timestamp_Sys100NS - [double]$a[0].Timestamp_Sys100NS
if ($elapsed -le 0) { Unchanged 'Hyper-V logical processor counters did not advance' }
$cpu = [pscustomobject]@{ PercentTotalRunTime = [Math]::Max(0, [Math]::Min(100, 100 * $run / ($elapsed * $b.Count))) }
$cs = Get-CimInstance Win32_ComputerSystem; $cpus = $cs.NumberOfLogicalProcessors
$mhz = (@(Get-CimInstance Win32_Processor) | Select-Object -First 1).MaxClockSpeed
$rx = $null; $tx = $null
try {
  $adapters = @(Get-NetAdapter -Physical -ErrorAction Stop)
  $stats = @($adapters | Get-NetAdapterStatistics -ErrorAction Stop)
  if ($adapters.Count -gt 0 -and $stats.Count -eq $adapters.Count -and
      @($stats | Where-Object { $null -eq $_.ReceivedBytes -or $null -eq $_.SentBytes }).Count -eq 0) {
    $rx = [uint64]0; $tx = [uint64]0
    foreach ($s in $stats) { $rx += [uint64]$s.ReceivedBytes; $tx += [uint64]$s.SentBytes }
  }
} catch { $rx = $null; $tx = $null }
[pscustomobject]@{ node = [string]$env:COMPUTERNAME; cpu_utilization = [double]$cpu.PercentTotalRunTime
  cpus = [int]$cpus; cpu_speed_mhz = $(if ($mhz -gt 0) { [int]$mhz } else { $null }); cpu_sockets = [int]$cs.NumberOfProcessors
  total_memory_kb = [int64]$os.TotalVisibleMemorySize; free_memory_kb = [int64]$os.FreePhysicalMemory
  network_rx_bytes = $rx; network_tx_bytes = $tx; load_average = $null }
""",
    "vmstats": r"""
$all = @(Get-VM)
$processors = $null
try { $processors = @(Get-CimInstance -Namespace root/virtualization/v2 -ClassName Msvm_Processor -ErrorAction Stop) } catch { }
$counter = $null
try { $counter = @(Get-CimInstance Win32_PerfRawData_NvspNicStats_HyperVVirtualNetworkAdapter -ErrorAction Stop) } catch { }
$vms = @(foreach ($vm in $all) {
  if ([string]$vm.State -ne 'Running') { continue }
  $rx = $null; $tx = $null
  try {
    $adapters = @(Get-VMNetworkAdapter -VM $vm -ErrorAction Stop)
    if ($null -ne $counter -and $adapters.Count -gt 0) {
      $r = [uint64]0; $t = [uint64]0; $valid = $true
      foreach ($a in $adapters) {
        if ([string]$a.Id -notmatch '^Microsoft:([0-9a-fA-F-]{36})\\([0-9a-fA-F-]{36})$' -or $Matches[1] -ine [string]$vm.Id) { $valid = $false; break }
        $suffix = '_' + $Matches[1] + '--' + $Matches[2]
        $matches = @($counter | Where-Object { ([string]$_.Name).EndsWith($suffix, [StringComparison]::OrdinalIgnoreCase) })
        if ($matches.Count -ne 1 -or $null -eq $matches[0].BytesReceivedPersec -or $null -eq $matches[0].BytesSentPersec) { $valid = $false; break }
        $r += [uint64]$matches[0].BytesReceivedPersec; $t += [uint64]$matches[0].BytesSentPersec
      }
      if ($valid) { $rx = $r; $tx = $t }
    }
  } catch { $rx = $null; $tx = $null }
  $cpu = $null
  if ($null -ne $processors -and $vm.ProcessorCount -gt 0) {
    $vp = @($processors | Where-Object { $_.SystemName -ieq [string]$vm.Id })
    if ($vp.Count -eq $vm.ProcessorCount -and @($vp | Where-Object { $null -eq $_.LoadPercentage -or $_.LoadPercentage -gt 100 }).Count -eq 0 -and
        @($vp | Select-Object -ExpandProperty DeviceID -Unique).Count -eq $vp.Count) {
      $cpu = [double](($vp | Measure-Object -Property LoadPercentage -Average).Average)
    }
  }
  [pscustomobject]@{ guid = [string]$vm.Id; name = [string]$vm.Name; notes = [string]$vm.Notes
    cpus = [int]$vm.ProcessorCount; cpu_utilization = $cpu; memory_kb = [int64]($vm.MemoryAssigned / 1024)
    memory_free_kb = $null; memory_target_kb = $null; network_rx_bytes = $rx; network_tx_bytes = $tx
    disk_read_bytes = $null; disk_write_bytes = $null; disk_read_ios = $null; disk_write_ios = $null }
})
[pscustomobject]@{ node = [string]$env:COMPUTERNAME; vms = $vms }
""",
    "storage": r"""
$out = @()
foreach ($csv in @(Get-ClusterSharedVolume)) {
  $info = $csv.SharedVolumeInfo | Select-Object -First 1
  $path = [string]$info.FriendlyVolumeName
  $out += [pscustomobject]@{ storage = (Split-Path $path -Leaf); kind = 'csv'; root = $path; state = [string]$csv.State
    total = [int64]$info.Partition.Size; used = [int64]$info.Partition.UsedSpace }
}
foreach ($s in @($A.smb.PSObject.Properties)) {
  $free = [uint64]0; $total = [uint64]0; $x = [uint64]0
  if (-not ('Win32.Disk' -as [type])) {
    Add-Type -Namespace Win32 -Name Disk -MemberDefinition '[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] public static extern bool GetDiskFreeSpaceEx(string d, out ulong a, out ulong t, out ulong f);'
  }
  $ok = [Win32.Disk]::GetDiskFreeSpaceEx([string]$s.Value, [ref]$x, [ref]$total, [ref]$free)
  $out += [pscustomobject]@{ storage = $s.Name; kind = 'smb'; root = [string]$s.Value; state = $(if ($ok) { 'Online' } else { 'Offline' })
    total = [int64]$total; used = [int64]($total - $free) }
}
$out
""",
    "isofind": r"""
$want = (FullPath $A.volume)
@(Get-VM | ForEach-Object { $vm = $_; $slots = @(Get-VMDvdDrive -VM $vm | Where-Object { $_.Path -and $_.Path -eq $want } |
  ForEach-Object { '{0}{1}:{2}' -f $_.ControllerType, $_.ControllerNumber, $_.ControllerLocation })
  if ($slots.Count) { [pscustomobject]@{ guid = [string]$vm.Id; name = [string]$vm.Name; slots = $slots } } })
""",
    "isostat": r"""
$p = FullPath $A.volume
if (Test-Path -LiteralPath $p -PathType Leaf) { [pscustomobject]@{ present = $true; size = [int64](Get-Item -LiteralPath $p).Length } }
else { [pscustomobject]@{ present = $false; size = 0 } }
""",
    # Writes. Each checks what it relies on before its first change.
    "vm_new": r"""
if (Get-VM -Name $A.name -ErrorAction SilentlyContinue) { Unchanged 'A VM of that name already exists on the node' }
$root = Root $A.storage
$dir = Join-Path $root $A.name
if (Test-Path -LiteralPath $dir) { Unchanged ('The folder ' + $dir + ' already exists') }
$v = New-VM -Name $A.name -Generation $A.generation -NoVHD -Path $root -MemoryStartupBytes $A.memory
Set-VM -VM $v -Notes $A.notes -ProcessorCount $A.cpus -StaticMemory -AutomaticStartAction Nothing -AutomaticStopAction ShutDown
Set-VM -VM $v -Notes ('[cloudstack-vmid:' + $A.vmid + ':' + [string]$v.Id + ']')
Get-VMNetworkAdapter -VM $v | Remove-VMNetworkAdapter
[pscustomobject]@{ guid = [string]$v.Id }
""",
    "vm_build": r"""
$v = TheVM
if ([string]$v.State -ne 'Off') { Unchanged 'The new VM is not off' }
$root = Root $A.storage; $dir = Join-Path $root $A.name
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$disk = Join-Path $dir ($A.name + '-disk-0.vhdx')
if ($A.template) {
  $src = FullPath $A.template
  if (-not (Test-Path -LiteralPath $src -PathType Leaf)) { throw 'The template disk is not on the storage' }
  if ($A.linked) { New-VHD -Path $disk -ParentPath $src -Differencing | Out-Null } else { Copy-Item -LiteralPath $src -Destination $disk }
  if ($A.sizebytes -and (Get-VHD -Path $disk).Size -lt $A.sizebytes) { Resize-VHD -Path $disk -SizeBytes $A.sizebytes }
} else { New-VHD -Path $disk -SizeBytes $A.sizebytes -Dynamic | Out-Null }
if ($v.Generation -eq 2) {
  Add-VMHardDiskDrive -VM $v -ControllerType SCSI -ControllerNumber 0 -ControllerLocation 0 -Path $disk
  for ($i = 0; $i -lt $A.dvds.Count; $i++) {
    $d = Add-VMDvdDrive -VM $v -ControllerNumber 0 -ControllerLocation (1 + $i) -Passthru
    if ($A.dvds[$i]) { Set-VMDvdDrive -VMDvdDrive $d -Path (FullPath $A.dvds[$i]) }
  }
  $order = @()
  if ($A.dvds.Count -gt 0 -and $A.bootcd) { $order += Get-VMDvdDrive -VM $v -ControllerNumber 0 -ControllerLocation 1 }
  $order += Get-VMHardDiskDrive -VM $v -ControllerType SCSI -ControllerNumber 0 -ControllerLocation 0
  Set-VMFirmware -VM $v -BootOrder $order
  if ($A.firmware -eq 'uefi-secure') {
    Set-VMFirmware -VM $v -EnableSecureBoot On -SecureBootTemplate $A.sbtemplate
    Set-VMKeyProtector -VM $v -NewLocalKeyProtector; Enable-VMTPM -VM $v
  } else { Set-VMFirmware -VM $v -EnableSecureBoot Off }
} else {
  Add-VMHardDiskDrive -VM $v -ControllerType IDE -ControllerNumber 0 -ControllerLocation 0 -Path $disk
  Get-VMDvdDrive -VM $v | Remove-VMDvdDrive
  for ($i = 0; $i -lt $A.dvds.Count -and $i -lt 2; $i++) {
    $d = Add-VMDvdDrive -VM $v -ControllerNumber 1 -ControllerLocation $i -Passthru
    if ($A.dvds[$i]) { Set-VMDvdDrive -VMDvdDrive $d -Path (FullPath $A.dvds[$i]) }
  }
  if ($A.bootcd) { Set-VMBios -VM $v -StartupOrder @('CD', 'IDE', 'LegacyNetworkAdapter', 'Floppy') }
}
foreach ($n in $A.nics) {
  $nic = Add-VMNetworkAdapter -VM $v -Name $n.name -SwitchName $A.switch -StaticMacAddress $n.mac -Passthru
  if ($n.vlan) { Set-VMNetworkAdapterVlan -VMNetworkAdapter $nic -Access -VlanId $n.vlan } else { Set-VMNetworkAdapterVlan -VMNetworkAdapter $nic -Untagged }
}
Describe (Get-VM -Id $v.Id)
""",
    "vm_destroy": r"""
$v = Get-VM -Id $A.guid -ErrorAction SilentlyContinue
if (-not $v) { Unchanged 'VM_ABSENT' }
if ($v.Name -cne $A.name) { Unchanged 'The VM at this identity has another name' }
if ($A.requireoff -and [string]$v.State -ne 'Off') { Unchanged 'The VM is not stopped' }
if ([string]$v.State -ne 'Off') { Stop-VM -VM $v -TurnOff -Force; $v = WaitState $v 'Off' 120 }
$files = @()
foreach ($d in @(Get-VMHardDiskDrive -VM $v)) { $p = [string]$d.Path; while ($p) { $files += $p; if ($p -match '\.avhdx$') { $p = [string](Get-VHD -Path $p).ParentPath } else { $p = '' } } }
foreach ($s in @(Get-VMSnapshot -VM $v)) { foreach ($d in @(Get-VMHardDiskDrive -VMSnapshot $s)) { if ($d.Path) { $files += [string]$d.Path } } }
$g = GroupOf $v
if ($g) { Remove-ClusterGroup -InputObject $g -RemoveResources -Force }
$dirs = @($files | ForEach-Object { Split-Path $_ -Parent }) + @([string]$v.Path)
Remove-VM -VM $v -Force
foreach ($f in ($files | Sort-Object -Unique)) { if ($A.template -and $f -eq $A.template) { continue }; if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force } }
foreach ($d in ($dirs | Sort-Object -Unique -Descending)) {
  if ($d -and (Split-Path $d -Leaf) -eq $A.name -and (Test-Path -LiteralPath $d) -and -not (Get-ChildItem -LiteralPath $d -Recurse -File)) { Remove-Item -LiteralPath $d -Recurse -Force }
}
[pscustomobject]@{ removed = $files.Count }
""",
    "power": r"""
$v = TheVM; Settled $v
$deadline = [int]$A.seconds
switch ($A.op) {
  'start' {
    if ([string]$v.State -eq 'Running') { break }
    # A dynamic MAC address can change when the VM starts on another node, and CloudStack knows a NIC by its MAC:
    # make every adapter's present address static before the first start through CloudStack.
    foreach ($n in @(Get-VMNetworkAdapter -VM $v | Where-Object { $_.DynamicMacAddressEnabled })) {
      if ($n.MacAddress -and $n.MacAddress -ne '000000000000') { Set-VMNetworkAdapter -VMNetworkAdapter $n -StaticMacAddress $n.MacAddress }
    }
    $g = GroupOf $v
    if ($g) { Start-ClusterGroup -InputObject $g -Wait $deadline | Out-Null } else { Start-VM -VM $v }
    $v = WaitState $v 'Running' $deadline
  }
  'stop' {
    if ([string]$v.State -eq 'Off') { break }
    $shut = Get-VMIntegrationService -VM $v | Where-Object { $_.Name -eq 'Shutdown' -and $_.Enabled -and [string]$_.PrimaryStatusDescription -eq 'OK' }
    if ($shut -and -not $A.force) {
      # Ask the guest through the shutdown component and poll, as reboot does. (Stop-VM -AsJob intermittently took
      # the whole PowerShell process down with exit code 2 and no output while the guest shut down.)
      $sc = Get-CimInstance -Namespace root\virtualization\v2 -ClassName Msvm_ShutdownComponent -Filter ("SystemName='" + $v.Id + "'")
      if ($sc) {
        $null = Invoke-CimMethod -InputObject $sc -MethodName InitiateShutdown -Arguments @{ Force = $true; Reason = 'CloudStack stop' }
        $grace = [Math]::Max(10, $deadline - 40)
        try { $v = WaitState $v 'Off' $grace } catch { }
      }
    }
    if ([string](Get-VM -Id $v.Id).State -ne 'Off') { Stop-VM -VM $v -TurnOff -Force }
    $v = WaitState $v 'Off' 60
  }
  'reboot' {
    if ([string]$v.State -ne 'Running') { Unchanged 'The VM is not running' }
    $shut = Get-VMIntegrationService -VM $v | Where-Object { $_.Name -eq 'Shutdown' -and $_.Enabled -and [string]$_.PrimaryStatusDescription -eq 'OK' }
    if (-not $shut) { Unchanged 'The guest cannot be asked to restart: its shutdown integration service is not running' }
    $sc = Get-CimInstance -Namespace root\virtualization\v2 -ClassName Msvm_ShutdownComponent -Filter ("SystemName='" + $v.Id + "'")
    if (-not $sc) { Unchanged 'The guest cannot be asked to restart' }
    $r = Invoke-CimMethod -InputObject $sc -MethodName InitiateReboot -Arguments @{ Force = $true; Reason = 'CloudStack reboot' }
    if ($r.ReturnValue -ne 0) { Unchanged ('The guest refused to restart (' + $r.ReturnValue + ')') }
    # The VM keeps running through a guest restart; the heartbeat drops and comes back.
    $end = (Get-Date).AddSeconds($deadline); $dropped = $false
    while ((Get-Date) -lt $end) {
      $hb = [string](Get-VMIntegrationService -VM $v | Where-Object { $_.Name -eq 'Heartbeat' }).PrimaryStatusDescription
      if ($hb -ne 'OK') { $dropped = $true } elseif ($dropped) { break }
      Start-Sleep -Milliseconds 500
    }
    $v = Get-VM -Id $v.Id
    if (-not $dropped -or [string]$v.State -ne 'Running') { throw 'The guest restart could not be confirmed' }
    $hb = [string](Get-VMIntegrationService -VM $v | Where-Object { $_.Name -eq 'Heartbeat' }).PrimaryStatusDescription
    if ($hb -ne 'OK') { throw 'The guest has not come back after its restart' }
  }
}
Describe (Get-VM -Id $v.Id)
""",
    "setha": r"""
$v = TheVM; Settled $v
$g = GroupOf $v
if ($A.enabled) {
  if (-not $g) { Add-ClusterVirtualMachineRole -VMId $v.Id | Out-Null }
} elseif ($g) {
  # Removing the role leaves the VM as it is, running or not.
  Remove-ClusterGroup -InputObject $g -RemoveResources -Force
}
$v = Get-VM -Id $v.Id
[pscustomobject]@{ clustered = [bool]$v.IsClustered; state = [string]$v.State }
""",
    "scale": r"""
$v = TheVM; Settled $v
if ((Get-VMMemory -VM $v).DynamicMemoryEnabled) { Unchanged 'The VM uses dynamic memory, which CloudStack cannot represent' }
if ([string]$v.State -ne 'Off') { Unchanged 'The instance is not stopped in Hyper-V' }
Set-VM -VM $v -ProcessorCount $A.cpus -MemoryStartupBytes $A.memory
Describe (Get-VM -Id $v.Id)
""",
    "nic": r"""
$v = TheVM; Settled $v
$mac = ($A.mac -replace ':', '').ToUpper()
$have = @(Get-VMNetworkAdapter -VM $v | Where-Object { $_.MacAddress -eq $mac })
$running = [string]$v.State -eq 'Running'
if ($A.expected -and (($A.expected -eq 'running') -ne $running)) { Unchanged 'The instance power state differs from what CloudStack expects' }
if ($A.op -eq 'plug') {
  if ($have.Count) {
    $n = $have[0]; $vl = Get-VMNetworkAdapterVlan -VMNetworkAdapter $n
    if ($n.Name -eq $A.devname -and $n.SwitchName -eq $A.switch -and [string]$vl.OperationMode -eq 'Access' -and [int]$vl.AccessVlanId -eq [int]$A.vlan) { return Describe $v }
    Unchanged 'An adapter with that MAC address already exists with another configuration'
  }
  if (Get-VMNetworkAdapter -VM $v | Where-Object { $_.Name -eq $A.devname }) { Unchanged ('The VM already has an adapter named ' + $A.devname) }
  if ($running -and $v.Generation -ne 2) { Unchanged 'A generation 1 VM must be stopped to add a network adapter' }
  $n = Add-VMNetworkAdapter -VM $v -Name $A.devname -SwitchName $A.switch -StaticMacAddress $mac -Passthru
  Set-VMNetworkAdapterVlan -VMNetworkAdapter $n -Access -VlanId $A.vlan
} else {
  if (-not $have.Count) { return Describe $v }
  if ($running -and $v.Generation -ne 2) { Unchanged 'A generation 1 VM must be stopped to remove a network adapter' }
  Remove-VMNetworkAdapter -VMNetworkAdapter $have[0]
}
Describe (Get-VM -Id $v.Id)
""",
    "disk": r"""
$v = TheVM; Settled $v
if (@(Get-VMSnapshot -VM $v).Count) { Unchanged 'The instance has checkpoints; delete them before changing its disks' }
$running = [string]$v.State -eq 'Running'
function Drive([string]$t, [int]$c, [int]$l) { Get-VMHardDiskDrive -VM $v -ControllerType $t -ControllerNumber $c -ControllerLocation $l -ErrorAction SilentlyContinue }
switch ($A.op) {
  'add' {
    $c = [int]$A.cnum; $l = [int]$A.loc
    if (Get-VMScsiController -VM $v -ControllerNumber $c -ErrorAction SilentlyContinue) { } elseif ($running) { Unchanged 'Stop the instance to give it a SCSI controller' } else { Add-VMScsiController -VM $v }
    if ((Drive 'SCSI' $c $l) -or (Get-VMDvdDrive -VM $v -ControllerNumber $c -ControllerLocation $l -ErrorAction SilentlyContinue)) { Unchanged 'The disk slot is taken' }
    $path = FullPath $A.volume
    if (Test-Path -LiteralPath $path) { Unchanged 'The disk file already exists' }
    New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent) | Out-Null
    New-VHD -Path $path -SizeBytes $A.sizebytes -Dynamic | Out-Null
    try { Add-VMHardDiskDrive -VM $v -ControllerType SCSI -ControllerNumber $c -ControllerLocation $l -Path $path }
    catch { $m = $_.Exception.Message; Remove-Item -LiteralPath $path -Force; if (-not (Test-Path -LiteralPath $path)) { Unchanged ('Hyper-V refused the disk: ' + $m) }; throw }
  }
  default {
    $d = Drive $A.ctype $A.cnum $A.loc
    if (-not $d -or -not $d.Path) { if ($A.op -eq 'delete') { return Describe $v }; Unchanged 'The disk is not in that slot' }
    $path = FullPath $A.volume
    if ([string]$d.Path -ne $path) { Unchanged 'The slot holds another disk' }
    if ($running -and $A.ctype -ne 'SCSI') { Unchanged 'The instance must be stopped to change a disk that is not on a SCSI controller' }
    if ($A.op -eq 'resize') {
      $size = (Get-VHD -Path $path).Size
      if ($size -gt $A.sizebytes) { Unchanged 'A disk cannot be made smaller' }
      if ($size -lt $A.sizebytes) { Resize-VHD -Path $path -SizeBytes $A.sizebytes }
    } elseif ($A.op -eq 'delete') {
      Remove-VMHardDiskDrive -VMHardDiskDrive $d
      Remove-Item -LiteralPath $path -Force
    } elseif ($A.op -eq 'move') {
      $dest = FullPath $A.target
      if (Test-Path -LiteralPath $dest) { Unchanged 'The destination file already exists' }
      # The values must be plain strings: Join-Path's PSObject-wrapped strings fail Move-VMStorage's key check.
      Move-VMStorage -VM $v -VHDs @(@{ 'SourceFilePath' = [string]$path; 'DestinationFilePath' = [string]$dest })
    }
  }
}
Describe (Get-VM -Id $v.Id)
""",
    "cd": r"""
$v = TheVM; Settled $v
$drives = @(Get-VMDvdDrive -VM $v | Sort-Object ControllerType, ControllerNumber, ControllerLocation)
switch ($A.op) {
  'attach' {
    if ([string]$v.State -ne 'Running') { Unchanged 'The instance is not running' }
    $path = FullPath $A.volume
    if ($drives | Where-Object { $_.Path -eq $path }) { return Describe $v }
    if (-not $drives.Count) { Unchanged 'Stop the instance to give it another CD drive' }
    $pick = $null
    if ($A.drive -lt $drives.Count -and -not $drives[$A.drive].Path) { $pick = $drives[$A.drive] }
    if (-not $pick) { $pick = $drives | Where-Object { -not $_.Path } | Select-Object -First 1 }
    if (-not $pick) { Unchanged 'The instance has no empty CD drive: each holds a disc. Eject one in Hyper-V, or stop the instance and start it through CloudStack' }
    Set-VMDvdDrive -VMDvdDrive $pick -Path $path
  }
  'detach' {
    $path = FullPath $A.volume
    foreach ($d in $drives) { if ($d.Path -eq $path) { Set-VMDvdDrive -VMDvdDrive $d -Path $null } }
    # On a running VM Hyper-V reports the eject only after the guest lets the disc go (seconds).
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline -and (Get-VMDvdDrive -VM $v | Where-Object { $_.Path -eq $path })) { Start-Sleep -Seconds 1 }
  }
  'sync' {
    if ([string]$v.State -ne 'Off') { return Describe $v }
    $want = @($A.wanted)
    for ($i = $drives.Count; $i -lt $want.Count; $i++) {
      if ($v.Generation -eq 2) {
        $used = @(Get-VMHardDiskDrive -VM $v -ControllerType SCSI -ControllerNumber 0 | ForEach-Object { $_.ControllerLocation }) + @(Get-VMDvdDrive -VM $v -ControllerNumber 0 | ForEach-Object { $_.ControllerLocation })
        $loc = 0; while ($used -contains $loc) { $loc++ }
        Add-VMDvdDrive -VM $v -ControllerNumber 0 -ControllerLocation $loc
      } else {
        $used = @(Get-VMHardDiskDrive -VM $v -ControllerType IDE | ForEach-Object { '{0}:{1}' -f $_.ControllerNumber, $_.ControllerLocation }) + @(Get-VMDvdDrive -VM $v | ForEach-Object { '{0}:{1}' -f $_.ControllerNumber, $_.ControllerLocation })
        $slot = @('1:0', '1:1', '0:1') | Where-Object { $used -notcontains $_ } | Select-Object -First 1
        if (-not $slot) { throw 'The generation 1 VM has no free IDE slot for a CD drive' }
        Add-VMDvdDrive -VM $v -ControllerNumber ([int]$slot.Split(':')[0]) -ControllerLocation ([int]$slot.Split(':')[1])
      }
    }
    $drives = @(Get-VMDvdDrive -VM $v | Sort-Object ControllerType, ControllerNumber, ControllerLocation)
    for ($i = 0; $i -lt $drives.Count; $i++) {
      $p = $null; if ($i -lt $want.Count -and $want[$i]) { $p = FullPath $want[$i] }
      if ([string]$drives[$i].Path -ne [string]$p) { Set-VMDvdDrive -VMDvdDrive $drives[$i] -Path $p }
    }
    if ($A.bootcd -and $drives.Count) {
      if ($v.Generation -eq 2) {
        $first = $drives[0]; $rest = @((Get-VMFirmware -VM $v).BootOrder | Where-Object { -not ($_.Device -and $_.Device.Id -eq $first.Id) } | ForEach-Object { $_ })
        Set-VMFirmware -VM $v -BootOrder (@($first) + $rest)
      } else { Set-VMBios -VM $v -StartupOrder @('CD', 'IDE', 'LegacyNetworkAdapter', 'Floppy') }
    }
  }
}
Describe (Get-VM -Id $v.Id)
""",
    "iso_fetch": r"""
$p = FullPath $A.volume
if (Test-Path -LiteralPath $p) { Unchanged 'The ISO is already there' }
$dir = Split-Path $p -Parent
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$part = $p + '.part'
if (Test-Path -LiteralPath $part) { Remove-Item -LiteralPath $part -Force }
try {
  Invoke-WebRequest -UseBasicParsing -Uri $A.url -OutFile $part -TimeoutSec $A.seconds
  $h = (Get-FileHash -LiteralPath $part -Algorithm $A.algorithm).Hash.ToLower()
  if ($h -ne $A.checksum.ToLower()) { throw 'The downloaded ISO does not match its checksum' }
  if ($A.size -and (Get-Item -LiteralPath $part).Length -ne $A.size) { throw 'The downloaded ISO is not the expected size' }
  Move-Item -LiteralPath $part -Destination $p
} catch { $m = $_.Exception.Message; Remove-Item -LiteralPath $part -Force -ErrorAction SilentlyContinue; Unchanged ('The ISO could not be copied: ' + ($m -replace 'https?://\S+', '<link>')) }
[pscustomobject]@{ present = $true; size = [int64](Get-Item -LiteralPath $p).Length }
""",
    "iso_remove": r"""
$p = FullPath $A.volume
if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Force }
[pscustomobject]@{ present = [bool](Test-Path -LiteralPath $p) }
""",
    "checkpoint": r"""
$v = TheVM; Settled $v
$cp = Get-VMSnapshot -VM $v -Name $A.snap -ErrorAction SilentlyContinue
switch ($A.op) {
  'create' {
    if ($cp) { Unchanged 'A checkpoint of that name already exists' }
    $running = [string]$v.State -eq 'Running'
    if ($A.memory -and -not $running) { Unchanged 'A checkpoint with memory needs the instance running' }
    $type = $(if ($A.memory) { 'Standard' } elseif ($running) { 'ProductionOnly' } else { 'Standard' })
    $was = [string]$v.CheckpointType
    if ($was -ne $type) { Set-VM -VM $v -CheckpointType $type }
    try { Checkpoint-VM -VM $v -SnapshotName $A.snap }
    catch { $m = $_.Exception.Message; if ($was -ne $type) { Set-VM -VM $v -CheckpointType $was }
      if (-not (Get-VMSnapshot -VM $v -Name $A.snap -ErrorAction SilentlyContinue) -and [string](Get-VM -Id $v.Id).CheckpointType -eq $was) { Unchanged ('Hyper-V could not take the checkpoint: ' + $m) }; throw }
    if ($was -ne $type) { Set-VM -VM $v -CheckpointType $was }
  }
  'revert' {
    if (-not $cp) { Unchanged 'There is no checkpoint of that name' }
    $withMemory = @('Running', 'Paused', 'Saved') -contains [string]$cp.State
    if ($withMemory -ne [bool]$A.memory) { Unchanged 'The checkpoint is not of the kind CloudStack recorded' }
    if (-not $withMemory -and [string]$v.State -ne 'Off') { Unchanged 'Stop the instance to revert it to a checkpoint without memory' }
    Restore-VMSnapshot -VMSnapshot $cp -Confirm:$false
    if ($withMemory) { $v = Get-VM -Id $v.Id; if ([string]$v.State -ne 'Running') { Start-VM -VM $v }; $v = WaitState $v 'Running' 300 }
  }
  'delete' {
    if ($cp) { Remove-VMSnapshot -VMSnapshot $cp; $deadline = (Get-Date).AddSeconds([int]$A.seconds)
      while ((Get-VMSnapshot -VM $v -Name $A.snap -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) { Start-Sleep 1 } }
  }
}
Describe (Get-VM -Id $v.Id)
""",
}
# Every PowerShell run pays for loading the Hyper-V and FailoverClusters modules (seconds each on a busy node), so the
# cluster, its storages and the first node's VMs are read in one run.
SCRIPTS["overview"] = "[pscustomobject]@{ cluster = & { %s }; storage = @(& { %s }); inventory = & { %s } }" % (
    SCRIPTS["cluster"], SCRIPTS["storage"], SCRIPTS["inventory"])
READ_ONLY_SCRIPTS = {"cluster", "nodes", "inventory", "describe", "storage", "isofind", "isostat", "overview", "hoststats", "vmstats"}


class WinRMTransport:
    """Runs a named script on a node over WinRM. The script is sent on stdin, base64, so it is not limited by
    the length of a command line; only a short loader is on the command line."""

    LOADER = ("$s=[Console]::In.ReadToEnd();"
              "$t=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s.Trim()));"
              "& ([ScriptBlock]::Create($t))")

    def __init__(self, settings):
        import winrm  # noqa: imported here so the tests need no WinRM library
        self.winrm = winrm
        self.settings = settings

    def endpoint(self, node):
        address = self.settings["addresses"].get(node.lower()) or node
        if "://" not in address:
            address = "https://%s:5986/wsman" % address
        return address

    def run(self, node, script, args, timeout):
        body = PS_PRELUDE + "\n$A = ConvertFrom-Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s')))\n" % (
            base64.b64encode(json.dumps(args).encode()).decode())
        body += ("try { $r = & { %s }; [pscustomobject]@{ ok = $true; result = $r } | ConvertTo-Json -Compress -Depth 12 }\n"
                 "catch { $m = [string]$_.Exception.Message; [pscustomobject]@{ ok = $false; unchanged = $m.StartsWith('UNCHANGED:');"
                 " error = ($m -replace '^UNCHANGED:', '') } | ConvertTo-Json -Compress }\n") % SCRIPTS[script]
        protocol = self.winrm.Protocol(
            endpoint=self.endpoint(node), transport=self.settings["transport"],
            username=self.settings["username"], password=self.settings["password"],
            server_cert_validation="validate" if self.settings["verify"] else "ignore",
            ca_trust_path=self.settings["ca"] or "legacy_requests",
            read_timeout_sec=max(60, timeout + 30), operation_timeout_sec=max(20, min(timeout, 50)))
        shell = protocol.open_shell()
        try:
            loader = base64.b64encode(self.LOADER.encode("utf-16-le")).decode()
            command = protocol.run_command(shell, "powershell.exe", ["-NoProfile", "-NonInteractive", "-EncodedCommand", loader])
            protocol.send_command_input(shell, command, base64.b64encode(body.encode()).decode(), end=True)
            out, err, code = protocol.get_command_output(shell, command)
            protocol.cleanup_command(shell, command)
        finally:
            protocol.close_shell(shell)
        text = out.decode("utf-8", "replace").strip()
        try:
            return json.loads(text.splitlines()[-1])
        except (ValueError, IndexError):
            # PowerShell's own errors arrive as CLIXML on stderr; name the first one so the failure can be diagnosed.
            errors = re.findall(r'<S S="Error">(.*?)</S>', err.decode("utf-8", "replace"))
            detail = re.sub(r"_x000D__x000A_|\s+", " ", " ".join(errors)).strip()[:300]
            raise Fail("Unreadable answer from node %s (exit %s)%s" % (node, code, ": " + detail if detail else ""),
                       known=script in READ_ONLY_SCRIPTS)


def console_request(path, request, timeout):
    """One request to the console helper over its Unix socket."""
    import socket
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as conn:
        conn.settimeout(timeout)
        conn.connect(path)
        conn.sendall((json.dumps(request) + "\n").encode())
        data = b""
        while not data.endswith(b"\n"):
            chunk = conn.recv(4096)
            if not chunk:
                break
            data += chunk
    try:
        return json.loads(data.decode())
    except ValueError:
        return {"status": "error", "error": "unreadable answer"}


def as_list(value):
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def vmid_of(guid, notes):
    """The CloudStack VMID of a Hyper-V VM: the one CloudStack gave the VM it created, else from its GUID."""
    guid = guid.lower()
    for match in MARKER_RE.finditer(notes or ""):
        if match.group(2).lower() == guid and 0 < int(match.group(1)) <= VMID_LIMIT:
            return int(match.group(1))
    return int.from_bytes(hashlib.sha256(guid.encode()).digest()[:8], "big") % VMID_LIMIT + 1


class Extension:
    def __init__(self, action, payload, wait, transport=None):
        self.action = action
        self.payload = payload
        self.wait = int(wait) if str(wait).isdigit() and int(wait) > 0 else 600
        self.sent_write = False
        self.started = time.monotonic()
        details = payload.get("externaldetails") or {}
        self.ext = details.get("extension") or {}
        self.rmap = details.get("resourcemap") or {}
        self.host = details.get("host") or {}
        self.vmd = details.get("virtualmachine") or {}
        self.params = payload.get("parameters") or {}
        self.to = payload.get("cloudstack.vm.details") or {}
        self.to_details = self.to.get("details") or {}
        self.settings = self.load_settings()
        self.transport = transport or WinRMTransport(self.settings)
        self._cluster = None
        self._inventory = None
        self._storage = []
        self._local_inventory = {}

    # ---- configuration ------------------------------------------------------------------------------
    def setting(self, key, default=""):
        for source in (self.host, self.rmap, self.ext):
            value = source.get(key)
            if value not in (None, ""):
                return str(value)
        return default

    def load_settings(self):
        username = self.rmap.get("username") or self.ext.get("username") or ""
        # CloudStack stores "password" details as hidden and never passes hidden details to the extension, so the
        # mapping carries the password as "secret" (as the Proxmox extension's token does).
        password = self.rmap.get("secret") or self.rmap.get("password") or self.ext.get("password") or ""
        cluster = self.host.get("proxmox_cluster") or ""
        alias = self.host.get("hyperv_cluster") or ""
        if alias and cluster and alias != cluster:
            raise Fail("The host's hyperv_cluster and proxmox_cluster details differ")
        addresses = {}
        raw = self.setting("node_addresses")
        if raw:
            try:
                addresses = {str(k).lower(): str(v) for k, v in json.loads(raw).items()}
            except (ValueError, AttributeError):
                raise Fail("node_addresses must be a JSON object of node name to address")
        node = (self.host.get("node") or "").lower()
        url = self.host.get("url") or ""
        if node and url:
            addresses.setdefault(node, url)
        suffix = self.setting("dns_suffix")
        smb = {}
        if self.setting("smb_storages"):
            try:
                smb = {str(k): str(v) for k, v in json.loads(self.setting("smb_storages")).items()}
            except (ValueError, AttributeError):
                raise Fail("smb_storages must be a JSON object of storage name to share path")
        return {
            "username": username, "password": password, "cluster": cluster or alias, "node": node,
            "addresses": addresses, "suffix": suffix, "smb": smb,
            "transport": self.setting("winrm_transport", "ntlm"),
            "verify": self.setting("verify_tls_certificate", "true").lower() != "false",
            "ca": self.setting("ca_file"),
            "switch": self.host.get("network_bridge") or self.setting("network_bridge"),
        }

    def node_address_key(self, node):
        node = node.lower()
        if node not in self.settings["addresses"] and self.settings["suffix"]:
            self.settings["addresses"][node] = "%s.%s" % (node, self.settings["suffix"])
        return node

    def remaining(self):
        return max(10, int(self.wait - (time.monotonic() - self.started) - 15))

    # ---- calls --------------------------------------------------------------------------------------
    def call(self, node, script, args=None, timeout=None):
        args = dict(args or {})
        args.setdefault("roots", self.roots())
        write = script not in READ_ONLY_SCRIPTS
        written_before = self.sent_write
        if write:
            self.sent_write = True
        try:
            answer = self.transport.run(self.node_address_key(node), script, args, timeout or self.remaining())
        except Fail as e:
            raise Fail(str(e), known=e.known and not written_before)
        except Exception as e:  # transport failure: a write may or may not have happened
            raise Fail("No answer from node %s: %s" % (node, type(e).__name__), known=not self.sent_write)
        if not isinstance(answer, dict) or "ok" not in answer:
            raise Fail("Unreadable answer from node %s" % node, known=not self.sent_write)
        if not answer["ok"]:
            # A refusal is known only when nothing was written: by this script (it says it changed nothing)
            # or by an earlier one.
            known = not written_before and (not write or bool(answer.get("unchanged")))
            if known and write:
                self.sent_write = written_before
            raise Fail(str(answer.get("error") or "Hyper-V refused the request"), known=known)
        return answer.get("result")

    def roots(self):
        roots = {}
        for name in self.storage_names():
            roots[name] = self.settings["smb"].get(name) or "C:\\ClusterStorage\\%s" % name
        return roots

    def storage_names(self):
        names = set(self.settings["smb"])
        for key in ("storage", "iso_storage", "default_storage"):
            if self.setting(key):
                names.add(self.setting(key))
        if self.vmd.get("storage"):
            names.add(str(self.vmd["storage"]))
        for value in (self.params.get("storage"), self.params.get("volume"), self.vmd.get("template_volume")):
            if isinstance(value, str) and ":" in value:
                names.add(value.split(":", 1)[0])
            elif isinstance(value, str) and value:
                names.add(value)
        for cd in as_list(self.payload.get("cdroms")):
            if isinstance(cd, dict) and ":" in str(cd.get("volume") or ""):
                names.add(cd["volume"].split(":", 1)[0])
        names.update(self.extra_storage)
        return sorted(n for n in names if STORAGE_RE.match(n))

    extra_storage = set()

    def cluster(self):
        """The failover cluster as its nodes see it: name, nodes and clustered VM groups."""
        if self._cluster is None:
            if not self.settings["node"]:
                raise Fail("The host has no node detail")
            both = self.call(self.settings["node"], "overview", {"smb": self.settings["smb"]})
            info = both["cluster"]
            self._storage = as_list(both.get("storage"))
            self._local_inventory = both["inventory"]
            name = str(info.get("cluster") or "").lower()
            if not self.settings["cluster"] or name != self.settings["cluster"]:
                raise Fail("The host's cluster identity does not match the failover cluster %s" % name)
            info["nodes"] = as_list(info.get("nodes"))
            info["groups"] = {g["guid"].lower(): g for g in as_list(info.get("groups")) if g.get("guid")}
            self._cluster = info
        return self._cluster

    def inventory(self, require_all=True):
        """Every VM of every node, with its VMID. Fails when a node is up but cannot be read."""
        if self._inventory is None:
            nodes = [n["name"] for n in self.cluster()["nodes"] if n.get("state") in ("Up", "Paused")]
            down = [n["name"] for n in self.cluster()["nodes"] if n.get("state") not in ("Up", "Paused")]
            if require_all and down:
                raise Fail("Cluster node(s) %s are not up; the inventory would be incomplete" % ", ".join(sorted(down)))
            local = self.settings["node"]
            others = [n for n in nodes if n.lower() != local]
            with ThreadPoolExecutor(max_workers=max(1, min(8, len(others)))) as pool:
                results = list(pool.map(lambda n: (n, self.call(n, "inventory", timeout=60)), others))
            if any(n.lower() == local for n in nodes):
                results.insert(0, (local, self._local_inventory))
            vms, seen = [], {}
            for node, result in results:
                for vm in as_list(result.get("vms")):
                    vm["node"] = node.lower()
                    vm["guid"] = vm["guid"].lower()
                    vm["vmid"] = vmid_of(vm["guid"], vm.get("notes"))
                    seen.setdefault(vm["vmid"], []).append(vm)
                    vms.append(vm)
            for vm in vms:
                vm["duplicate"] = len(seen[vm["vmid"]]) > 1
            self._inventory = vms
        return self._inventory

    # ---- identity -----------------------------------------------------------------------------------
    def vmid(self):
        values = set()
        for source in (self.to_details, self.vmd):
            for key in ("proxmox_vmid", "External:proxmox_vmid"):
                if source.get(key) not in (None, ""):
                    values.add(str(source[key]))
        if len(values) != 1 or not re.match(r"^[1-9][0-9]*$", next(iter(values))):
            raise Fail("The instance has no single VMID recorded")
        return int(values.pop())

    def instance_name(self):
        return self.payload.get("virtualmachinename") or self.to.get("name") or ""

    def manifest(self):
        raw = self.vmd.get("adopt_manifest_json") or ""
        try:
            return json.loads(raw) if raw else {}
        except ValueError:
            raise Fail("Invalid adoption manifest")

    def is_adoption(self):
        return str(self.vmd.get("adopt_existing", "")).lower() == "true" or any(self.vmd.get(k) for k in ADOPTION_KEYS)

    def expected_name(self):
        if self.is_adoption():
            return str(self.manifest().get("name") or "")
        return self.instance_name()

    def find(self):
        """The VM this request is about: its VMID, unique in the cluster, under the expected name."""
        vmid = self.vmid()
        matches = [vm for vm in self.inventory(require_all=False) if vm["vmid"] == vmid]
        if not matches:
            raise Fail("VM_ABSENT")
        if len(matches) > 1 or matches[0]["duplicate"]:
            raise Fail("Two VMs of the cluster have VMID %d (%s); refusing to act on either" % (
                vmid, ", ".join(m["guid"] for m in matches)))
        vm = matches[0]
        expected = self.expected_name()
        if expected and vm["name"] != expected:
            raise Fail("The VM with VMID %d is named %s, not %s" % (vmid, vm["name"], expected))
        return vm

    def vm_args(self, vm, **extra):
        args = {"guid": vm["guid"], "name": vm["name"]}
        args.update(extra)
        return args

    # ---- native adoption ----------------------------------------------------------------------------
    def check_native(self):
        if not self.is_adoption():
            return
        if self.action not in NATIVE_OPERATIONS:
            raise Fail("Native adoption action is unsupported")
        if self.host.get("native_adoption_lifecycle") != "true":
            raise Fail("This extension supports only native adoption; the host does not enable it")
        operation = NATIVE_OPERATIONS[self.action]
        vmid = str(self.vmid())
        generation = str(self.vmd.get("adopt_claim_generation") or "")
        if not re.match(r"^[1-9][0-9]*$", generation):
            raise Fail("Invalid native adoption identity")
        cluster = self.settings["cluster"]
        if self.vmd.get("proxmox_cluster") not in (None, "", cluster):
            raise Fail("Native host cluster or VMID alias mismatch")
        raw = self.vmd.get("adopt_manifest_json") or ""
        if hashlib.sha256(raw.encode()).hexdigest() != self.vmd.get("adopt_manifest_sha256"):
            raise Fail("Adoption manifest hash mismatch")
        manifest = self.manifest()
        if str(manifest.get("vmid")) != vmid or (manifest.get("placement") or {}).get("cluster") != cluster:
            raise Fail("Native manifest identity mismatch")
        ctx = self.payload.get("native_adoption_context")
        expected = {"action": self.action, "operation": operation, "authorization_from_state": "managed",
                    "claim_id": self.vmd.get("adopt_claim_id"), "cluster": cluster,
                    "manifest_sha256": self.vmd.get("adopt_manifest_sha256"),
                    "cloudstack_vm_ref": self.payload.get("virtualmachineid") or self.to.get("uuid"),
                    "cloudstack_instance_name": self.instance_name()}
        if not isinstance(ctx, dict) or any(ctx.get(k) != v for k, v in expected.items()) \
                or str(ctx.get("generation")) != generation or str(ctx.get("vmid")) != vmid:
            raise Fail("Native adoption context mismatch or expired lease")
        if operation == "":
            ok = ctx.get("stored_state") == "managed" and "lease_id" not in ctx
        else:
            expires = ctx.get("expires_epoch")
            ok = (ctx.get("stored_state") == "operating" and isinstance(ctx.get("lease_id"), str)
                  and UUID4_RE.match(ctx["lease_id"]) is not None
                  and isinstance(expires, (int, float)) and not isinstance(expires, bool) and expires > time.time())
        if not ok:
            raise Fail("Native adoption context mismatch or expired lease")

    # ---- shared views -------------------------------------------------------------------------------
    def storage_root_map(self):
        """name -> root path for every storage of the cluster, read from the cluster."""
        self.cluster()
        rows = self._storage
        return {r["storage"]: r for r in rows if STORAGE_RE.match(str(r.get("storage") or ""))}

    def volume_of(self, path, roots):
        """storage:relative/path for a file under a known storage root, or None."""
        if not path:
            return None
        for name, root in sorted(roots.items(), key=lambda kv: -len(kv[1])):
            prefix = root.rstrip("\\") + "\\"
            if path.lower().startswith(prefix.lower()):
                rel = path[len(prefix):].replace("\\", "/")
                if rel and "," not in rel and ":" not in rel and len(name) + 1 + len(rel) <= 255:
                    return "%s:%s" % (name, rel)
        return None

    @staticmethod
    def disk_slot(disk):
        ctype, cnum, loc = disk["ctype"].upper(), int(disk["cnum"]), int(disk["loc"])
        if ctype == "IDE" and cnum in (0, 1) and loc in (0, 1):
            return "ide%d" % (cnum * 2 + loc)
        if ctype == "SCSI" and 0 <= cnum <= 3 and 0 <= loc < SLOTS_PER_CONTROLLER:
            return "scsi%d" % (cnum * SLOTS_PER_CONTROLLER + loc)
        return None

    @staticmethod
    def slot_address(device):
        match = re.match(r"^(scsi|ide)([0-9]{1,2})$", device or "")
        if not match:
            raise Fail("Invalid disk device %s" % device)
        n = int(match.group(2))
        if match.group(1) == "ide":
            if n > 3:
                raise Fail("Invalid disk device %s" % device)
            return "IDE", n // 2, n % 2
        return "SCSI", n // SLOTS_PER_CONTROLLER, n % SLOTS_PER_CONTROLLER

    def nic_numbers(self, nics):
        numbers, taken = {}, set()
        for nic in nics:
            match = re.match(r"^net([0-9]{1,2})$", nic.get("name") or "")
            if match and int(match.group(1)) not in taken:
                numbers[nic["id"]] = int(match.group(1))
                taken.add(int(match.group(1)))
        free = (n for n in range(0, 1000) if n not in taken)
        for nic in sorted(nics, key=lambda n: n["id"]):
            if nic["id"] not in numbers:
                numbers[nic["id"]] = next(free)
        return numbers

    @staticmethod
    def usable_ipv4(text):
        try:
            ip = ipaddress.ip_address(text)
        except ValueError:
            return False
        return ip.version == 4 and not (ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_unspecified)

    def root_disk(self, desc, disks):
        if desc["generation"] == 2:
            for entry in as_list(desc.get("boot")):
                if entry.get("kind") == "HardDiskDrive":
                    for disk in disks:
                        if (disk["ctype"], disk["cnum"], disk["loc"]) == (entry.get("ctype"), entry.get("cnum"), entry.get("loc")):
                            return disk
        else:
            for disk in disks:
                if disk["ctype"] == "IDE" and disk["cnum"] == 0 and disk["loc"] == 0:
                    return disk
        ordered = sorted(disks, key=lambda d: (d["ctype"] != "IDE", d["ctype"], d["cnum"], d["loc"]))
        return ordered[0] if ordered else None

    def digest(self, desc):
        keep = {k: desc.get(k) for k in ("guid", "name", "generation", "version", "cpus", "memory", "dynamic",
                                         "clustered", "notes", "checkpointtype", "current", "secure", "sbtemplate", "tpm")}
        keep["nics"] = sorted(({k: n.get(k) for k in ("name", "mac", "switch", "mode", "vlan")} for n in as_list(desc.get("nics"))),
                              key=lambda n: n["mac"])
        keep["disks"] = [{k: d.get(k) for k in ("ctype", "cnum", "loc", "path", "size")} for d in as_list(desc.get("disks"))]
        keep["dvds"] = as_list(desc.get("dvds"))
        keep["boot"] = as_list(desc.get("boot"))
        keep["checkpoints"] = as_list(desc.get("checkpoints"))
        return hashlib.sha1(json.dumps(keep, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

    def snapshot(self, vm, desc, roots):
        """The getconfig answer for a description of the VM."""
        if desc["state"] not in ("Running", "Off"):
            raise Fail("The VM is %s" % desc["state"])
        ops = as_list(desc.get("ops"))
        if ops and ops[0] != "Ok":
            raise Fail("Hyper-V has an operation in progress on the VM: %s" % ", ".join(ops))
        memory_mb = int(desc["memory"]) // (1024 * 1024)
        if desc.get("dynamic"):
            # Optional stand-in until such VMs are given static memory: a fixed MiB per vCPU is recorded as the size.
            per_cpu = str(self.setting("dynamic_memory_mb_per_cpu") or "")
            if not per_cpu.isdigit() or int(per_cpu) < 256:
                raise Fail("The VM uses dynamic memory, which CloudStack cannot represent")
            memory_mb = int(per_cpu) * int(desc["cpus"])
        nics_raw = as_list(desc.get("nics"))
        numbers = self.nic_numbers(nics_raw)
        nics = []
        for nic in nics_raw:
            mac = nic["mac"].upper()
            mac = ":".join(mac[i:i + 2] for i in range(0, 12, 2)).lower()
            bridge, vlan = None, None
            if nic.get("switch") and nic.get("mode") in ("Access", "Untagged"):
                bridge = nic["switch"]
                if nic.get("mode") == "Access" and 1 <= int(nic.get("vlan") or 0) <= 4094:
                    vlan = int(nic["vlan"])
            ips = sorted({ip for ip in as_list(nic.get("ips")) if self.usable_ipv4(ip)})
            nics.append({"device_id": numbers[nic["id"]], "mac": mac, "bridge": bridge, "vlan": vlan, "ip_addresses": ips})
        disks_raw = [d for d in as_list(desc.get("disks"))]
        root = self.root_disk(desc, disks_raw)
        disks = []
        for disk in disks_raw:
            slot = self.disk_slot(disk)
            if slot is None:
                raise Fail("The VM has a disk at %s %s:%s, which this extension cannot represent" % (disk["ctype"], disk["cnum"], disk["loc"]))
            entry = {"device": slot, "root": disk is root}
            volume = self.volume_of(disk.get("base"), roots)
            if volume:
                entry["storage"], entry["volume"] = volume.split(":", 1)
            if disk.get("size"):
                entry["size_bytes"] = int(disk["size"])
            disks.append(entry)
        return {"status": "success",
                "identity": {"cluster": self.settings["cluster"], "vmid": vm["vmid"], "name": desc["name"], "node": desc["node"].lower()},
                "power": "running" if desc["state"] == "Running" else "stopped",
                "config_digest": self.digest(desc), "cpus": int(desc["cpus"]), "memory_mb": memory_mb,
                "agent_available": bool(desc.get("kvp")), "nics": nics, "disks": disks}

    def describe(self, vm):
        both = self.call(vm["node"], "describe", self.vm_args(vm))
        first, second = both["first"], both["second"]
        if self.digest(first) != self.digest(second) or first["state"] != second["state"]:
            raise Fail("The VM changed while it was read")
        return second

    def config(self, vm=None):
        vm = vm or self.find()
        roots = {name: row["root"] for name, row in self.storage_root_map().items()}
        self.extra_storage = set(roots)
        return self.snapshot(vm, self.describe(vm), roots)

    # ---- actions ------------------------------------------------------------------------------------
    def do_prepare(self):
        if self.is_adoption():
            raise Fail("Instances are adopted with adoptExternalInstance; this extension has no other adoption")
        # A node that is down cannot be read, but deployments must not stop for it: a random 31-bit VMID is very
        # unlikely to meet one of its VMs, and if it ever does both are refused as duplicates once the node is back.
        taken = {vm["vmid"] for vm in self.inventory(require_all=False)}
        for _ in range(64):
            candidate = secrets.randbelow(VMID_LIMIT) + 1
            if candidate not in taken:
                return {"details": {"proxmox_vmid": str(candidate)}}
        raise Fail("No free VMID")

    def wanted_cds(self):
        cds = self.payload.get("cdroms")
        if cds is None:
            return None
        cds = as_list(cds)
        if len(cds) > 4 or [c.get("drive") for c in cds] != list(range(len(cds))):
            raise Fail("Invalid CD drive list")
        for c in cds:
            if not isinstance(c.get("volume"), str) or (c["volume"] and not ISO_VOLUME_RE.match(c["volume"])):
                raise Fail("Invalid CD drive list")
        return cds

    def nic_plan(self):
        plan = []
        for i, nic in enumerate(as_list(self.to.get("nics"))):
            mac = str(nic.get("mac") or "")
            uri = str(nic.get("broadcastUri") or "")
            match = re.match(r"^vlan://([0-9]+)$", uri)
            vlan = int(match.group(1)) if match else None
            if uri and not match and uri not in ("vlan://untagged",):
                raise Fail("Unsupported network for Hyper-V: %s" % uri)
            if vlan is not None and not 1 <= vlan <= 4094:
                raise Fail("Invalid VLAN")
            plan.append({"name": "net%d" % int(nic.get("deviceId", i)), "mac": mac.replace(":", "").upper(), "vlan": vlan})
        return plan

    def do_create(self):
        if self.is_adoption():
            raise Fail("Instances are adopted with adoptExternalInstance; this extension has no other adoption")
        vmid, name = self.vmid(), self.instance_name()
        if not name or not re.match(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$", name):
            raise Fail("Invalid instance name")
        if any(vm["vmid"] == vmid for vm in self.inventory(require_all=False)):
            raise Fail("VMID %d is already used in the cluster" % vmid)
        if not self.settings["switch"]:
            raise Fail("The host has no network_bridge (virtual switch) detail")
        node = self.settings["node"]
        firmware = self.vmd.get("firmware") or ""
        iso = (self.vmd.get("template_type") or "").upper() == "ISO"
        template = self.vmd.get("template_volume") or ""
        storage = self.vmd.get("storage") or self.setting("default_storage")
        if not storage or not STORAGE_RE.match(storage):
            raise Fail("No storage to create the instance on")
        if iso:
            if firmware not in ("bios", "uefi", "uefi-secure"):
                raise Fail("Invalid firmware")
            generation = 1 if firmware == "bios" else 2
            size = int(self.vmd.get("disk_size_gb") or 0) * GIB
            if size <= 0:
                raise Fail("Invalid root disk size")
        else:
            if not template or ":" not in template:
                raise Fail("The template has no template_volume detail")
            generation = int(self.vmd.get("generation") or 2)
            firmware = "uefi-secure" if str(self.vmd.get("secure_boot", "")).lower() == "true" else ("uefi" if generation == 2 else "bios")
            # The template's disk_size_gb is its minimum; a root disk size asked for at deployment (rootdisksize) grows it.
            asked = str(self.to_details.get("rootdisksize") or "")
            size = max(int(self.vmd.get("disk_size_gb") or 0), int(asked) if asked.isdigit() else 0) * GIB
        if firmware == "uefi-secure" and self.setting("hyperv_shared_guardian") != "true":
            raise Fail("Secure Boot with a TPM needs a guardian shared by the cluster's nodes; set hyperv_shared_guardian=true once it is")
        os_type = self.vmd.get("iso_os_type") or ""
        cds = self.wanted_cds() or []
        dvds = [c["volume"] for c in cds] if iso else []
        if iso and len(dvds) < 2:
            dvds += [""] * (2 - len(dvds))
        memory = int(self.to.get("minRam") or 0)
        cpus = int(self.to.get("cpus") or 0)
        if memory < 32 * 1024 * 1024 or cpus < 1:
            raise Fail("Invalid CPU or memory")
        memory -= memory % (2 * 1024 * 1024)
        nics = self.nic_plan()
        created = self.call(node, "vm_new", {"name": name, "generation": generation, "storage": storage, "memory": memory,
                                             "cpus": cpus, "vmid": vmid, "notes": ""})
        vm = {"guid": str(created["guid"]).lower(), "name": name, "node": node, "vmid": vmid}
        try:
            self.call(node, "vm_build", self.vm_args(
                vm, storage=storage, template=template if not iso else "", linked=str(self.vmd.get("is_full_clone", "true")).lower() == "false",
                sizebytes=size, dvds=dvds, bootcd=bool(iso and cds and cds[0].get("bootable")), firmware=firmware,
                sbtemplate="MicrosoftWindows" if os_type.startswith("win") else "MicrosoftUEFICertificateAuthority",
                switch=self.settings["switch"], nics=nics))
            if str(self.to.get("enableHA")).lower() == "true":
                self.call(node, "setha", self.vm_args(vm, enabled=True))
            self.call(node, "power", self.vm_args(vm, op="start", seconds=self.remaining()))
        except Fail as failure:
            try:
                self.call(node, "vm_destroy", self.vm_args(vm, template=self.full_path_hint(template), keepparents=True))
            except Fail as cleanup:
                raise Fail("%s; the partly created VM %s could not be removed: %s" % (failure, vm["guid"], cleanup), known=False)
            raise Fail("%s; the partly created VM was removed" % failure, known=False)
        self._inventory = None
        result = {"status": "success", "message": "Instance created"}
        try:
            result["config"] = self.config(vm)
        except Fail:
            pass
        return result

    def full_path_hint(self, template):
        if not template:
            return ""
        name, rel = template.split(":", 1)
        root = self.settings["smb"].get(name) or "C:\\ClusterStorage\\%s" % name
        return root.rstrip("\\") + "\\" + rel.replace("/", "\\")

    def do_start(self):
        vm = self.find()
        cds = self.wanted_cds()
        if cds is not None and vm["state"] == "Off":
            self.call(vm["node"], "cd", self.vm_args(vm, op="sync", wanted=[c["volume"] for c in cds],
                                                     bootcd=bool(cds and cds[0]["volume"] and cds[0].get("bootable"))))
        ha = str(self.to.get("enableHA", "")).lower()
        if ha in ("true", "false") and (ha == "true") != bool(vm.get("clustered")):
            self.call(vm["node"], "setha", self.vm_args(vm, enabled=ha == "true"))
        desc = self.call(vm["node"], "power", self.vm_args(vm, op="start", seconds=self.remaining()))
        if desc["state"] != "Running":
            raise Fail("The VM is not running after the start", known=False)
        return {"status": "success", "message": "Instance started"}

    def do_stop(self):
        try:
            vm = self.find()
        except Fail as e:
            if str(e) == "VM_ABSENT" and not self.is_adoption():
                return {"status": "success", "message": "The instance does not exist in Hyper-V"}
            raise
        desc = self.call(vm["node"], "power", self.vm_args(vm, op="stop", seconds=self.remaining()))
        if desc["state"] != "Off":
            raise Fail("The VM is not off after the stop", known=False)
        return {"status": "success", "message": "Instance stopped"}

    def do_reboot(self):
        vm = self.find()
        self.call(vm["node"], "power", self.vm_args(vm, op="reboot", seconds=self.remaining()))
        return {"status": "success", "message": "Instance rebooted"}

    def do_delete(self):
        try:
            vm = self.find()
        except Fail as e:
            if str(e) == "VM_ABSENT":
                return {"status": "success", "message": "The instance does not exist in Hyper-V"}
            raise
        try:
            self.call(vm["node"], "vm_destroy", self.vm_args(vm, requireoff=self.is_adoption(), keepparents=True,
                                                            template=self.full_path_hint(self.vmd.get("template_volume") or "")))
        except Fail as e:
            if str(e) == "VM_ABSENT":
                return {"status": "success", "message": "The instance does not exist in Hyper-V"}
            raise
        return {"status": "success", "message": "Instance deleted"}

    def do_status(self):
        try:
            vm = self.find()
        except Fail as e:
            if str(e) == "VM_ABSENT":
                return {"status": "success", "power_state": "poweroff"}
            raise
        state = {"Running": "poweron", "Off": "poweroff"}.get(vm["state"], "unknown")
        return {"status": "success", "power_state": state, "ha_enabled": bool(vm.get("clustered"))}

    def status_bindings(self):
        native = self.host.get("native_adoption_status") or "false"
        if native not in ("true", "false"):
            raise Fail("Invalid native_adoption_status host detail")
        if native != "true":
            return {}
        bindings = self.payload.get("native_adoption_status_bindings")
        if not isinstance(bindings, dict) or any(
                not isinstance(k, str) or not re.match(r"^[1-9][0-9]*$", k) or not isinstance(b, dict)
                or not b.get("cloudstack_instance_name") or not b.get("expected_proxmox_name")
                for k, b in bindings.items()):
            raise Fail("Invalid native adoption status bindings")
        return bindings

    def do_statuses(self):
        node = self.settings["node"]
        self.cluster()
        vms = [vm for vm in as_list(self._local_inventory.get("vms"))]
        for vm in vms:
            vm["guid"] = vm["guid"].lower()
            vm["vmid"] = vmid_of(vm["guid"], vm.get("notes"))
        state = {"Running": "poweron", "Off": "poweroff"}
        bindings = self.status_bindings()
        rows, ids = [], [vm["vmid"] for vm in vms]
        if len(set(ids)) != len(ids):
            raise Fail("Duplicate VMIDs on the node")
        for vm in vms:
            binding = bindings.get(str(vm["vmid"]))
            key = binding["cloudstack_instance_name"] if binding else vm["name"]
            value = state.get(vm["state"], "unknown")
            if binding and vm["name"] != binding["expected_proxmox_name"]:
                value = "unknown"
            if key:
                rows.append((key, value))
        power = {}
        for key, value in rows:
            power[key] = "unknown" if key in power else value
        return {"status": "success", "power_state": power}

    def stats_view(self, script):
        node = self.settings["node"]
        if not node:
            raise Fail("The host has no node detail")
        view = self.call(node, script, {"roots": {}}, timeout=40)
        if not isinstance(view, dict) or str(view.get("node") or "").lower() != node:
            raise Fail("The node identity does not match the host")
        return view

    def do_hoststats(self):
        view = self.stats_view("hoststats")
        fields = ("cpu_utilization", "cpus", "cpu_speed_mhz", "cpu_sockets", "total_memory_kb", "free_memory_kb",
                  "network_rx_bytes", "network_tx_bytes", "load_average")
        return dict(status="success", timestamp=time.time(), **{k: view.get(k) for k in fields})

    def do_vmstats(self):
        bindings = self.status_bindings()
        view = self.stats_view("vmstats")
        vms = as_list(view.get("vms"))
        ids = [vmid_of(vm["guid"], vm.get("notes")) for vm in vms]
        if len(set(ids)) != len(ids):
            raise Fail("Duplicate VMIDs on the node")
        fields = ("cpus", "cpu_utilization", "memory_kb", "memory_free_kb", "memory_target_kb", "network_rx_bytes",
                  "network_tx_bytes", "disk_read_bytes", "disk_write_bytes", "disk_read_ios", "disk_write_ios")
        result = {}
        for vm, vmid in zip(vms, ids):
            binding = bindings.get(str(vmid))
            if binding and vm["name"] != binding["expected_proxmox_name"]:
                continue
            key = binding["cloudstack_instance_name"] if binding else vm["name"]
            if not key:
                continue
            if key in result:
                raise Fail("Duplicate VM stats key on the node")
            result[key] = {k: vm.get(k) for k in fields}
        return {"status": "success", "timestamp": time.time(), "vms": result}

    def do_health(self):
        """Is a node up? Without a "node" parameter: can this host's own node be reached, and is it an up member of
        the expected cluster. With one: how this host's node sees that other node's cluster membership, so
        CloudStack can tell a node that is down from one it merely cannot reach. Never fails for a dead node; it
        answers "unknown" instead."""
        own = self.settings["node"]
        if not own:
            raise Fail("The host has no node detail")
        target = str(self.params.get("node") or own).lower()
        try:
            view = self.call(own, "nodes", {"roots": {}}, timeout=20)
        except Fail as e:
            return {"status": "success", "health": "unknown", "node": target, "reason": str(e)[:200]}
        if str(view.get("cluster") or "").lower() != self.settings["cluster"]:
            raise Fail("The host's cluster identity does not match the failover cluster")
        states = {str(n.get("name") or "").lower(): str(n.get("state") or "") for n in as_list(view.get("nodes"))}
        if target not in states:
            raise Fail("Node %s is not a member of the cluster" % target)
        state = states[target]
        if target == own:
            health = "up" if state in ("Up", "Paused") else "unknown"
        else:
            health = {"Up": "up", "Paused": "up", "Down": "down"}.get(state, "unknown")
        return {"status": "success", "health": health, "node": target, "state": state}

    def do_getconsole(self):
        """The VM's console as VNC, through the console helper (VMConnect RDP on the node, served as VNC)."""
        vm = self.find()
        if vm["state"] != "Running":
            raise Fail("The instance is not running")
        address = self.settings["addresses"].get(self.node_address_key(vm["node"])) or vm["node"]
        address = re.sub(r"^https?://", "", address).split("/")[0].rsplit(":", 1)[0] if "://" in address else address
        request = {"node": address, "vmid": vm["guid"], "username": self.settings["username"],
                   "password": self.settings["password"]}
        path = self.setting("console_helper_socket", "/run/hyperv-console/helper.sock")
        try:
            answer = console_request(path, request, timeout=min(30, self.remaining()))
        except OSError as e:
            raise Fail("The Hyper-V console helper is not available (%s)" % type(e).__name__)
        if answer.get("status") != "success" or not isinstance(answer.get("console"), dict):
            raise Fail("The Hyper-V console helper could not open the console: %s" % answer.get("error", "no answer"))
        c = answer["console"]
        return {"status": "success", "console": {"host": c["host"], "port": int(c["port"]), "password": c["password"],
                                                 "passwordonetimeuseonly": True, "protocol": "vnc"}}

    def do_discover(self):
        groups = self.cluster()["groups"]
        rows = []
        for vm in self.inventory():
            if vm["duplicate"]:
                continue
            group = groups.get(vm["guid"])
            hastate = ""
            if group:
                hastate = {"Online": "started", "Offline": "stopped", "Failed": "error", "PartialOnline": "started"}.get(group.get("state"), "unknown")
            rows.append({"vmid": vm["vmid"], "node": vm["node"], "name": vm["name"], "type": "qemu",
                         "power": {"Running": "running", "Off": "stopped"}.get(vm["state"], "unknown"),
                         "template": False, "hastate": hastate})
        return {"status": "success", "cluster": self.settings["cluster"], "instances": rows}

    def do_getconfig(self):
        # Before adoption CloudStack names only the VMID; then no name is expected.
        return self.config()

    def do_getipaddress(self):
        mac = str(self.params.get("macaddress") or "").lower()
        if not re.match(r"^([0-9a-f]{2}:){5}[0-9a-f]{2}$", mac):
            raise Fail("Invalid MAC address")
        config = self.config()
        if not config["agent_available"]:
            raise Fail("The guest's Key-Value Pair Exchange integration service is not available")
        cidr = self.params.get("cidr")
        for nic in config["nics"]:
            if nic["mac"] == mac:
                ips = nic["ip_addresses"]
                if cidr:
                    try:
                        net = ipaddress.ip_network(cidr, strict=False)
                        ips = sorted(ips, key=lambda ip: ipaddress.ip_address(ip) not in net)
                    except ValueError:
                        pass
                return {"status": "success", "ip_address": ips[0] if ips else ""}
        raise Fail("The instance has no adapter with that MAC address")

    def do_setha(self):
        enabled = self.params.get("enabled")
        if not isinstance(enabled, bool):
            enabled = str(enabled).lower() == "true" if str(enabled).lower() in ("true", "false") else None
        if enabled is None:
            raise Fail("Missing parameter enabled")
        vm = self.find()
        result = self.call(vm["node"], "setha", self.vm_args(vm, enabled=enabled))
        if bool(result.get("clustered")) != enabled or result.get("state") != vm["state"]:
            raise Fail("The VM's cluster role is not as asked after the change", known=False)
        return {"status": "success", "ha_enabled": enabled}

    def do_scale(self):
        cpus, memory = self.params.get("cpunumber"), self.params.get("memory")
        if not isinstance(cpus, int) or not isinstance(memory, int) or cpus < 1 or memory < 32:
            raise Fail("Missing or invalid parameters: cpunumber, memory")
        vm = self.find()
        config = self.config(vm)
        if config["cpus"] == cpus and config["memory_mb"] == memory:
            return {"status": "success", "cpus": cpus, "memory_mb": memory}
        if memory % 2:
            raise Fail("Hyper-V memory must be a multiple of 2 MiB")
        desc = self.call(vm["node"], "scale", self.vm_args(vm, cpus=cpus, memory=memory * 1024 * 1024))
        if int(desc["cpus"]) != cpus or int(desc["memory"]) != memory * 1024 * 1024:
            raise Fail("Hyper-V does not show the new CPU and memory", known=False)
        return {"status": "success", "cpus": cpus, "memory_mb": memory}

    def do_plugnic(self, plug=True):
        nic = self.params.get("nic") or {}
        mac = str(nic.get("mac") or "").lower()
        if not re.match(r"^([0-9a-f]{2}:){5}[0-9a-f]{2}$", mac) or int(mac[:2], 16) & 1:
            raise Fail("Invalid MAC address")
        expected = self.params.get("expectedpowerstate")
        if expected not in ("running", "stopped"):
            raise Fail("Invalid expected power state")
        args = {"op": "plug" if plug else "unplug", "mac": mac, "expected": expected}
        if plug:
            match = re.match(r"^vlan://([0-9]+)$", str(nic.get("broadcastUri") or ""))
            device = nic.get("deviceId")
            if not match or not 1 <= int(match.group(1)) <= 4094 or not isinstance(device, int) or not 0 <= device <= 99:
                raise Fail("Unsupported network or device for Hyper-V")
            if not self.settings["switch"]:
                raise Fail("The host has no network_bridge (virtual switch) detail")
            args.update(vlan=int(match.group(1)), devname="net%d" % device, switch=self.settings["switch"])
        vm = self.find()
        desc = self.call(vm["node"], "nic", self.vm_args(vm, **args))
        present = [n for n in as_list(desc.get("nics")) if n["mac"].replace("-", "").upper() == mac.replace(":", "").upper()]
        if plug and (len(present) != 1 or present[0].get("switch") != args["switch"] or int(present[0].get("vlan") or 0) != args["vlan"]):
            raise Fail("Hyper-V does not show the adapter as asked", known=False)
        if not plug and present:
            raise Fail("Hyper-V still shows the adapter", known=False)
        return {"status": "success"}

    def do_unplugnic(self):
        return self.do_plugnic(plug=False)

    def disk_result(self, desc, device, roots):
        ctype, cnum, loc = self.slot_address(device)
        for disk in as_list(desc.get("disks")):
            if (disk["ctype"].upper(), int(disk["cnum"]), int(disk["loc"])) == (ctype, cnum, loc):
                volume = self.volume_of(disk.get("base"), roots)
                if not volume:
                    raise Fail("The disk is not on a storage of the cluster", known=False)
                return {"status": "success", "device": device, "volume": volume, "size_bytes": int(disk["size"])}
        raise Fail("Hyper-V does not show the disk", known=False)

    def disk_target(self, vm, desc, storage):
        """A new disk's slot and volume: the lowest free SCSI location, and a file named for the VM."""
        if not STORAGE_RE.match(storage or ""):
            raise Fail("Invalid storage")
        used = {(d["ctype"].upper(), int(d["cnum"]), int(d["loc"])) for d in as_list(desc.get("disks")) + as_list(desc.get("dvds"))}
        slot = None
        for cnum in range(4):
            for loc in range(SLOTS_PER_CONTROLLER):
                if ("SCSI", cnum, loc) not in used:
                    slot = (cnum, loc)
                    break
            if slot:
                break
        if not slot:
            raise Fail("The instance has no free disk slot")
        names = {str(d.get("base") or "").lower().replace("\\", "/").rsplit("/", 1)[-1] for d in as_list(desc.get("disks"))}
        n = 1
        while ("%s-disk-%d.vhdx" % (vm["name"], n)).lower() in names:
            n += 1
        return slot, "%s:%s/%s-disk-%d.vhdx" % (storage, vm["name"], vm["name"], n)

    def disk_change(self, op):
        vm = self.find()
        roots = {name: row["root"] for name, row in self.storage_root_map().items()}
        self.extra_storage = set(roots)
        desc = self.describe(vm)
        size = self.params.get("sizegb")
        if op == "add":
            if not isinstance(size, int) or size < 1:
                raise Fail("Invalid size")
            (cnum, loc), volume = self.disk_target(vm, desc, self.params.get("storage"))
            device = "scsi%d" % (cnum * SLOTS_PER_CONTROLLER + loc)
            # A rerun after a lost answer must not add a second disk: refuse if the file is already there.
            after = self.call(vm["node"], "disk", self.vm_args(vm, op="add", cnum=cnum, loc=loc, volume=volume, sizebytes=size * GIB))
            result = self.disk_result(after, device, roots)
            if result["volume"] != volume or result["size_bytes"] != size * GIB:
                raise Fail("Hyper-V does not show the new disk as asked", known=False)
            return result
        device, volume = self.params.get("device"), self.params.get("volume")
        if not isinstance(volume, str) or ":" not in volume:
            raise Fail("Invalid volume")
        ctype, cnum, loc = self.slot_address(device)
        current = [d for d in as_list(desc.get("disks")) if (d["ctype"].upper(), int(d["cnum"]), int(d["loc"])) == (ctype, cnum, loc)]
        if op == "delete":
            root = self.root_disk(desc, as_list(desc.get("disks")))
            if current and root is not None and current[0] is root:
                raise Fail("The root disk cannot be deleted")
            if not current:
                return {"status": "success"}
        args = self.vm_args(vm, op=op, ctype=ctype, cnum=cnum, loc=loc, volume=volume)
        if current and self.volume_of(current[0].get("base"), roots) != volume:
            raise Fail("The slot holds another disk")
        if op == "resize":
            if not isinstance(size, int) or size < 1:
                raise Fail("Invalid size")
            args["sizebytes"] = size * GIB
        if op == "move":
            target = self.params.get("storage")
            if not STORAGE_RE.match(target or "") or target not in roots:
                raise Fail("Invalid target storage")
            args["target"] = "%s:%s" % (target, volume.split(":", 1)[1])
        after = self.call(vm["node"], "disk", args, timeout=self.remaining())
        if op == "delete":
            if any((d["ctype"].upper(), int(d["cnum"]), int(d["loc"])) == (ctype, cnum, loc) for d in as_list(after.get("disks"))):
                raise Fail("Hyper-V still shows the disk", known=False)
            return {"status": "success"}
        result = self.disk_result(after, device, roots)
        if op == "resize" and (result["volume"] != volume or result["size_bytes"] != size * GIB):
            raise Fail("Hyper-V does not show the disk at the new size", known=False)
        if op == "move" and result["volume"] != args["target"]:
            raise Fail("Hyper-V does not show the disk on the new storage", known=False)
        return result

    def do_adddisk(self):
        return self.disk_change("add")

    def do_resizedisk(self):
        return self.disk_change("resize")

    def do_deletedisk(self):
        return self.disk_change("delete")

    def do_movedisk(self):
        return self.disk_change("move")

    def do_liststorage(self):
        cluster = self.settings["cluster"]
        self.cluster()
        rows = []
        for name, row in sorted(self.storage_root_map().items()):
            backing = ("csv/@%s/%s" % (cluster, name)) if row["kind"] == "csv" else \
                "cifs/" + row["root"].strip("\\").replace("\\", "/")
            rows.append({"storage": name, "type": row["kind"], "shared": True, "enabled": True,
                         "active": row.get("state") == "Online", "total": max(0, int(row.get("total") or 0)),
                         "used": max(0, int(row.get("used") or 0)), "backing": backing.lower(), "monitors": []})
        return {"status": "success", "cluster": cluster, "storages": rows}

    def iso_volume(self):
        volume = self.params.get("volume")
        if not isinstance(volume, str) or not ISO_VOLUME_RE.match(volume):
            raise Fail("Invalid ISO volume")
        return volume

    def do_attachiso(self, attach=True):
        volume = self.iso_volume()
        drive = self.params.get("drive", 0)
        vm = self.find()
        desc = self.call(vm["node"], "cd", self.vm_args(vm, op="attach" if attach else "detach", volume=volume,
                                                        drive=int(drive) if str(drive).isdigit() else 0))
        roots = self.roots()
        held = [d for d in as_list(desc.get("dvds")) if self.volume_of(d.get("path"), roots) == volume]
        if attach and not held:
            raise Fail("Hyper-V does not show the ISO in a CD drive", known=False)
        if not attach and held:
            raise Fail("Hyper-V still shows the ISO in a CD drive", known=False)
        return {"status": "success", "volume": volume}

    def do_detachiso(self):
        return self.do_attachiso(attach=False)

    def do_fetchiso(self):
        volume = self.iso_volume()
        node = self.settings["node"]
        self.cluster()
        stat = self.call(node, "isostat", {"volume": volume})
        size = self.params.get("size")
        if stat["present"] and (not isinstance(size, int) or size <= 0 or int(stat["size"]) == size):
            return {"status": "success", "present": True, "volume": volume, "size_bytes": int(stat["size"])}
        url = self.params.get("url")
        if not url:
            return {"status": "success", "present": False, "volume": volume}
        if stat["present"]:
            raise Fail("A file of another size is already at %s" % volume)
        algorithm = HASH_ALGORITHMS.get(str(self.params.get("checksumalgorithm") or "").lower().replace("-", ""))
        checksum = str(self.params.get("checksum") or "")
        if not algorithm or not re.match(r"^[0-9a-fA-F]+$", checksum):
            raise Fail("The ISO's checksum algorithm is not one Hyper-V nodes can check")
        if not re.match(r"^https?://\S+$", url):
            raise Fail("Invalid download link")
        result = self.call(node, "iso_fetch", {"volume": volume, "url": url, "algorithm": algorithm, "checksum": checksum,
                                               "size": size if isinstance(size, int) and size > 0 else 0, "seconds": self.remaining()})
        return {"status": "success", "present": bool(result["present"]), "volume": volume, "fetched": True,
                "size_bytes": int(result["size"])}

    def iso_holders(self, volume):
        """Every VM of the cluster with the ISO in a CD drive. Fails if any node cannot be read."""
        nodes = [n["name"] for n in self.cluster()["nodes"] if n.get("state") in ("Up", "Paused")]
        if len(nodes) != len(self.cluster()["nodes"]):
            raise Fail("A cluster node is down; its VMs cannot be checked for the ISO")
        holders = []
        for node in nodes:
            for row in as_list(self.call(node, "isofind", {"volume": volume}, timeout=120)):
                guid = row["guid"].lower()
                inv = [vm for vm in self.inventory() if vm["guid"] == guid]
                vmid = inv[0]["vmid"] if inv else vmid_of(guid, "")
                holders.append({"vmid": vmid, "node": node.lower(), "name": row["name"], "slots": as_list(row.get("slots"))})
        return holders

    def do_findiso(self):
        return {"status": "success", "guests": self.iso_holders(self.iso_volume())}

    def do_removeiso(self):
        volume = self.iso_volume()
        if self.iso_holders(volume):
            raise Fail("A VM of the cluster has the ISO in a CD drive")
        result = self.call(self.settings["node"], "iso_remove", {"volume": volume})
        if result.get("present"):
            raise Fail("The ISO is still there", known=False)
        return {"status": "success"}

    def snapshot_args(self):
        name = str(self.params.get("snap_name") or "")
        if not re.match(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$", name):
            raise Fail("Invalid snapshot name")
        memory = str(self.params.get("snap_save_memory", "false")).lower() == "true"
        return name, memory

    def do_createsnapshot(self):
        name, memory = self.snapshot_args()
        vm = self.find()
        desc = self.call(vm["node"], "checkpoint", self.vm_args(vm, op="create", snap=name, memory=memory))
        match = [c for c in as_list(desc.get("checkpoints")) if c["name"] == name]
        if len(match) != 1:
            raise Fail("Hyper-V does not show the checkpoint", known=False)
        return {"status": "success", "snapshot": {"name": name, "memory": memory, "parent": match[0].get("parent") or ""}}

    def do_revertsnapshot(self):
        name, memory = self.snapshot_args()
        vm = self.find()
        desc = self.call(vm["node"], "checkpoint", self.vm_args(vm, op="revert", snap=name, memory=memory))
        if desc["state"] != ("Running" if memory else "Off") or desc.get("current") != name:
            raise Fail("The VM is not as the checkpoint left it", known=False)
        return {"status": "success"}

    def do_deletesnapshot(self):
        name, _ = self.snapshot_args()
        vm = self.find()
        if not any(c["name"] == name for c in as_list(self.describe(vm).get("checkpoints"))):
            return {"status": "success", "message": "The checkpoint does not exist"}
        desc = self.call(vm["node"], "checkpoint", self.vm_args(vm, op="delete", snap=name, seconds=self.remaining()))
        if any(c["name"] == name for c in as_list(desc.get("checkpoints"))):
            raise Fail("Hyper-V still shows the checkpoint", known=False)
        return {"status": "success"}

    # ---- entry --------------------------------------------------------------------------------------
    def run(self):
        handler = getattr(self, "do_" + self.action, None)
        if handler is None:
            raise Fail("Unsupported action: %s" % self.action)
        if not self.settings["username"] or not self.settings["password"]:
            raise Fail("The cluster mapping has no username/password")
        self.check_native()
        return handler()


def main(argv, transport=None, out=sys.stdout):
    if len(argv) < 3:
        print(json.dumps({"status": "error", "error": "usage: hyperv_cluster.py ACTION PAYLOAD [WAIT]"}), file=out)
        return 1
    action, path = argv[1], argv[2]
    wait = argv[3] if len(argv) > 3 else "600"
    ext = None
    try:
        with open(path, encoding="utf-8") as f:
            payload = json.load(f)
        ext = Extension(action, payload, wait, transport)
        result = ext.run()
        print(json.dumps(result, separators=(",", ":")) if result is not None else "", file=out)
        return 0
    except Fail as failure:
        message = str(failure)
        if message == "VM_ABSENT":
            message = "The instance does not exist in the Hyper-V cluster"
        print(json.dumps({"status": "error", "error": message}), file=out)
        return KNOWN_FAILURE if failure.known else UNKNOWN_OUTCOME
    except Exception as e:  # noqa: anything unexpected after a write is an unknown outcome
        written = ext is not None and ext.sent_write
        print(json.dumps({"status": "error", "error": "Unexpected %s" % type(e).__name__}), file=out)
        return UNKNOWN_OUTCOME if written else KNOWN_FAILURE


if __name__ == "__main__":
    sys.exit(main(sys.argv))
