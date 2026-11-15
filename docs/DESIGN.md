# Hyper-V failover-cluster extension: design

Goal: a CloudStack External orchestrator extension for Hyper-V hosts in Windows Server Failover Clusters, at feature
parity with the Proxmox extension, written only against the contract in `EXTERNAL-EXTENSION-CONTRACT.md`. It is an
exercise to validate that contract: the CloudStack side is not changed; where it assumes Proxmox, the extension uses a
compatibility value and the gap is recorded in "Contract gaps" below.

Python 3 + pywinrm, WinRM over HTTPS, NTLM or Kerberos, one domain account per cluster mapping. PowerShell runs on
the cluster nodes; the extension runs on the management server.

## Topology

| CloudStack | Hyper-V / WSFC |
|---|---|
| External cluster (extension resource map) | One failover cluster. Details: `url` (cluster name or a node, `https://…:5986`), `username`, `password`, `verify_tls_certificate`, `hyperv_cluster` (identity, below) |
| External host | One cluster node. Host details `url` (the node), `node` (short node name), `hyperv_cluster` |
| Instance | A Hyper-V VM, clustered (HA) or not |
| Pool `external://<storage>` | A Cluster Shared Volume (`C:\ClusterStorage\<name>`) or an SMB share registered in the mapping's `smb_storages` |
| Volume | A VHDX on that storage: `<storage>:<path below it with />` |
| ISO library | `iso_storage` = a storage above; ISOs in its `iso/` folder |

Node sessions: cluster cmdlets run on any node; Hyper-V cmdlets run in a session **on the VM's owner node** (no
`-ComputerName` second hop, so no CredSSP or delegation). The extension opens one session per node it needs.

## Identity

CloudStack requires a positive 32-bit integer VMID per cluster (`proxmox_vmid`) and never reuses it for another guest.
Hyper-V VMs have a GUID, never reused, which Hyper-V does not let a caller choose. So:

- **Adopted (existing) VMs:** `vmid = (first 8 bytes of SHA-256(lowercase GUID)) mod 2147483646 + 1`. Stateless, the
  same on every management server, and bound to the VM's incarnation: a new VM of the same name has a new GUID and
  so a new VMID. Adoption writes nothing to the VM.
- **VMs CloudStack creates:** `prepare` picks a random VMID unused in the cluster; `create` writes
  `[cloudstack-vmid:<VMID>:<GUID>]` into the VM's Notes as its first change. The marker counts only while its GUID is
  the VM's own, so a copy or export/import of the VM (new GUID, same notes) does not inherit the identity.

A VMID that two VMs share (a hash collision, about 1 in 10,000 for 500 VMs, or an edited marker) is detected: both
are left out of discovery and every action on either fails with the two GUIDs named.

Cluster identity: the detail `hyperv_cluster`, compared with the WSFC name (`(Get-Cluster).Name`, lowercase); a
mismatch fails closed. CloudStack's detail key is still `proxmox_cluster`; the extension accepts either and requires
them to agree.

## Configuration snapshot (`getconfig`)

- `identity`: cluster, vmid, VM name, owner node (short, lowercase).
- `power`: `running` / `stopped` (`Off`); any other state (Saved, Paused, Starting, …) refuses, as a locked guest does.
- `config_digest`: SHA-1 of the canonical JSON of everything reported plus generation, firmware, checkpoint ids and
  the VM's configuration version; read twice around the guest-agent read and refused if it changed.
- `cpus` = ProcessorCount, `memory_mb` = StartupMemory MiB (dynamic memory refused for now: CloudStack has one figure).
- NICs: `device_id` from an adapter named `netN`, otherwise the next free number in adapter order; `bridge` = virtual
  switch name; `vlan` = access VLAN, null when untagged; IPs from the Hyper-V KVP data (integration services);
  `agent_available` = KVP exchange running.
- Disks: VHD/VHDX only (pass-through disks reported without storage). Device: Generation 2 `scsi<location>` on SCSI
  controller 0 (controllers 1–3 refused for now, the contract allows 0–99); Generation 1 `ide<2*controller+location>`
  and `scsi<location>`. Root: first disk in the firmware boot order (Gen 2) or IDE 0:0 (Gen 1). Differencing disks
  (an existing checkpoint) are reported by their base file.

## Mapping of actions

| Action | Hyper-V |
|---|---|
| create | `New-VM` (Gen 2 for `uefi`/`uefi-secure`, Gen 1 for `bios`), VHDX copied from the template file (full copy) or new for ISO; `Set-VMProcessor`, `Set-VMMemory` (static), adapters on the mapped switch with access VLAN, `Add-ClusterVirtualMachineRole` when HA; start |
| start / stop / reboot | `Start-ClusterGroup` (clustered) or `Start-VM`; stop = guest shutdown through integration services for up to the wait less 40 s, then `Stop-VM -TurnOff`; reboot = `Msvm_ShutdownComponent.InitiateReboot`, confirmed by the heartbeat dropping and returning, never a reset |
| delete | `Remove-ClusterGroup -RemoveResources` (if clustered), `Remove-VM`, then the VM's own VHDX files and folder (exact paths read from the VM, never by name) |
| status / statuses | `Get-VM` on every node, cluster group state |
| discover | all nodes; `type` = `qemu` (compatibility, see gaps); `hastate` = `started`/`stopped` from the cluster group |
| setha | `Add-ClusterVirtualMachineRole` (running or stopped); removal `Remove-ClusterGroup -RemoveResources` (keeps the VM) only while stopped, until it is shown live that removal leaves a running VM alone |
| scale | stopped only, `Set-VMProcessor`, `Set-VMMemory -StartupBytes` |
| plugnic / unplugnic | `Add-VMNetworkAdapter -StaticMacAddress` + `Set-VMNetworkAdapterVlan -Access`; Gen 2 hot-add while running, Gen 1 stopped only |
| adddisk / resizedisk / deletedisk / movedisk | `New-VHD` + `Add-VMHardDiskDrive` on SCSI (hot-add); `Resize-VHD` (running: SCSI only); `Remove-VMHardDiskDrive` then the exact file; `Move-VMStorage -VHDs` (live) |
| liststorage | `Get-ClusterSharedVolume` + configured SMB shares; backing `csv/@<cluster>/<name>` (never shared with another cluster) or `cifs/<server>/<share>` |
| attachiso / detachiso / fetchiso / findiso / removeiso | `Set-VMDvdDrive -Path`; download on a node with `Invoke-WebRequest` to `iso/<name>.part`, `Get-FileHash`, rename |
| createsnapshot / revertsnapshot / deletesnapshot | `Checkpoint-VM`: with memory = Standard checkpoint of a running VM; disk only = Production checkpoint (running) or Standard (stopped). `Restore-VMCheckpoint`, `Remove-VMCheckpoint` |
| getipaddress | KVP addresses of the adapter with that MAC |
| getconsole | not supported (Hyper-V offers RDP/VMConnect, not VNC) |
| snapshotdisk etc. | not declared |

Firmware `uefi-secure`: Gen 2, Secure Boot with template `MicrosoftWindows` for Windows ISOs and
`MicrosoftUEFICertificateAuthority` otherwise, and a vTPM with a local key protector. A local key protector only
opens on the node that created it unless the cluster's guardian is shared; the extension refuses `uefi-secure` unless
`hyperv_shared_guardian=true` is set on the mapping.

## Safety rules carried over

Exact identity before every write (cluster, VMID → GUID, owner node, and for adopted guests the manifest name); stable
power; no write while a checkpoint operation, migration or other Hyper-V job is running on the VM (`Get-VM`'s
`Status`/`OperationalStatus`) or the cluster group is pending; digest checked before the write and the result read back
after. Exit 90 only when nothing was sent; anything after a sent write that cannot be confirmed exits 91. Credentials,
signed URLs and payloads are never printed.

## Contract gaps (CloudStack assumes Proxmox)

Recorded as findings of the exercise, each worked around without a CloudStack change:

1. Identity keys `proxmox_vmid`/`proxmox_cluster` and a 32-bit integer VMID: hashed GUID (above).
2. Discovery accepts only `qemu`/`lxc`: Hyper-V VMs are reported as `qemu`.
3. Config digest must be 40 hex: SHA-1 of a canonical rendering.
4. Disk slots `scsi|sata|virtio|ide` 0–99: Hyper-V controller/location squeezed into one number; controllers 1–3 not
   representable for SCSI.
5. NIC `device_id` is a number: Hyper-V adapters have none; the extension names its adapters `netN`.
6. `expected_proxmox_name` in status bindings: honoured as the expected Hyper-V VM name.
7. Console must be VNC or a URL: none.
8. Per-disk snapshots are Ceph-only: not offered.
9. ISO OS codes are Proxmox names (`win11`, `l26`): used only to choose the Secure Boot template.
10. The address observer sends integer VMIDs and expects Proxmox host details: not provided for Hyper-V (KVP
    addresses come through `getconfig` instead).

11. No compare-and-swap: Proxmox writes carry the config digest; Hyper-V has none, so each PowerShell script re-checks
    what it relies on (state, slot, file, name) immediately before its write, in the same session.

## Test status

- `test_hyperv_cluster.py`: 49 tests through the entry point against a simulated cluster (identity, native adoption
  context, exit 90/91, every action's result shape and refusals). The simulator was written with the extension, so it
  checks the Python logic, not Hyper-V's behaviour.
- `check_powershell_syntax.py`: every script parses (pwsh parser).
- CloudStack's own `ExternalDiscoveryManager.parse`, `ExternalVmConfiguration.parse` and `ExternalStorageInventory.parse`
  (release build `6fb6c184bc`) accept sample `discover`, `getconfig` and `liststorage` answers (`wirecheck/`).
- **Not yet run against Hyper-V.** Lab: domain controller, two Windows Server 2025 Hyper-V nodes, iSCSI CSVs, nested on HV08.
