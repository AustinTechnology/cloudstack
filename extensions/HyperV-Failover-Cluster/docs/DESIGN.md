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
| External cluster (extension resource map) | One failover cluster. Details: `username`, `secret` (the password: a `password` detail is hidden by CloudStack and never passed on), `verify_tls_certificate`, `node_addresses` or `dns_suffix` |
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

## Read-only metrics (`hoststats`, `vmstats`)

These draft-v1 actions use one WinRM call to the host's configured node and reject a returned short-node identity
mismatch. They do not query FailoverClusters, enable metering, persist baselines, or validate cluster membership on
this fast path. `vmstats` returns only local running VMs, applies the same VMID and native-status bindings as
`statuses`, omits a bound VM on provider-name drift, and fails closed on duplicate VMIDs or result keys. Java owns
counter deltas and must treat reset/decrease as a new baseline. The extension does not enable CloudStack's capability.

* Host CPU: formatted `Hyper-V Hypervisor Logical Processor(_Total) % Total Run Time`; CPU count from
  `Win32_ComputerSystem.NumberOfLogicalProcessors`; memory total/free KB from `Win32_OperatingSystem`.
  Host network RX/TX: sum `Get-NetAdapterStatistics` for all `Get-NetAdapter -Physical` adapters; if incomplete,
  both are null. Load average is null. This measures physical interfaces, not guest vNIC traffic.
* VM CPUs and assigned bytes: local `Get-VM`; bytes divided by 1024. CPU utilization is the average of all
  `Msvm_Processor.LoadPercentage` vCPUs, mapped by exact `SystemName` VM GUID; missing/duplicate/incomplete vCPU
  samples yield null. `Get-VM.CPUUsage` is not used as the contract asks for percent of allocated vCPUs, not a
  possibly host-normalized percentage. Free guest memory and balloon target are null: MemoryDemand is not free RAM
  and is not asserted to be a balloon target.
* VM network: cumulative `Win32_PerfRawData_NvspNicStats_HyperVVirtualNetworkAdapter` bytes; every adapter must
  match exactly by both VM GUID and adapter GUID from `Get-VMNetworkAdapter.Id`, uniquely, or both directions are
  null. No adapter also yields null. Legacy/unavailable counters and partial multisets are not treated as zero.
* All VM disk byte/IO counters are null. In the lab, raw `Win32_PerfRawData_Counters_HyperVVirtualStorageDevice`
  has cumulative counts, but its instance name transforms `\\` to `-`, losing the distinction from literal hyphens
  in paths. Exact VHD-path identity cannot be proven from that name; assigning these counts would risk attribution
  to the wrong VM. This is a material metrics-contract limitation, not a measured zero.

Lab direct action runs on both Windows Server 2025 nodes (not CloudStack polling): hoststats 15.68s/14.22s;
vmstats 21.27s and 17.84s on hv01, 8.51s and 4.01s on hv02. On hv01 a bound running Windows guest had two CPUs,
1.5% then 6% vCPU utilization, 2,097,152 KB assigned, and RX 11,970 then 12,312 bytes (TX 12,207 in both);
all four disk fields and both guest-memory fields were null. The hv02 result correctly excluded that guest. Host
hv01 reported four logical CPUs, 14,679,496 KB total and 8,364,844 KB free; live values are snapshots, not expected
constants. Direct raw CIM, `Get-VM`, `Get-NetAdapterStatistics`, and Hyper-V `Get-Counter` probes confirmed source
availability and scale, not simultaneous equality. VM network RX increased during naturally occurring traffic; no
load was generated inside the guest and disk-I/O increments were not verified. CloudStack Java integration, UI,
reset handling and a controlled guest-I/O exercise remain untested.

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
| setha | `Add-ClusterVirtualMachineRole`; removal `Remove-ClusterGroup -RemoveResources`, which leaves the VM as it is, running or not (confirmed by Damian on production Hyper-V) |
| scale | stopped only, `Set-VMProcessor`, `Set-VMMemory -StartupBytes` |
| plugnic / unplugnic | `Add-VMNetworkAdapter -StaticMacAddress` + `Set-VMNetworkAdapterVlan -Access`; Gen 2 hot-add while running, Gen 1 stopped only |
| adddisk / resizedisk / deletedisk / movedisk | `New-VHD` + `Add-VMHardDiskDrive` on SCSI (hot-add); `Resize-VHD` (running: SCSI only); `Remove-VMHardDiskDrive` then the exact file; `Move-VMStorage -VHDs` (live) |
| liststorage | `Get-ClusterSharedVolume` + configured SMB shares; backing `csv/@<cluster>/<name>` (never shared with another cluster) or `cifs/<server>/<share>` |
| attachiso / detachiso / fetchiso / findiso / removeiso | `Set-VMDvdDrive -Path`; download on a node with `Invoke-WebRequest` to `iso/<name>.part`, `Get-FileHash`, rename |
| createsnapshot / revertsnapshot / deletesnapshot | `Checkpoint-VM`: with memory = Standard checkpoint of a running VM; disk only = Production checkpoint (running) or Standard (stopped). `Restore-VMCheckpoint`, `Remove-VMCheckpoint` |
| getipaddress | KVP addresses of the adapter with that MAC |
| getconsole | The console helper (`console_helper/`) on the management server: per request it starts Xvfb, `xfreerdp3 /vmconnect:<GUID>` to the owner node's port 2179 (the VMConnect channel, so it works without guest networking, from boot) and `x11vnc` with a one-time 8-character password, one client, 60 s to connect. The extension returns that host/port/password as a VNC console |
| snapshotdisk etc. | not declared |

Firmware is chosen at deployment (the stock BIOS/UEFI and Legacy/Secure fields, for an ISO deployment):
`bios` → generation 1 (IDE root disk, IDE CD drives), `uefi` → generation 2 with Secure Boot off, `uefi-secure` →
generation 2 with Secure Boot on. The generation is fixed at creation, as Hyper-V requires. A template's generation is
fixed by its disk (MBR/BIOS or GPT/UEFI), so templates carry a `generation` detail instead.

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
7. Console must be VNC or a URL: the console helper serves VMConnect (RDP) as VNC.
8. Per-disk snapshots are Ceph-only: not offered.
9. ISO OS codes are Proxmox names (`win11`, `l26`): used only to choose the Secure Boot template.
10. The address observer sends integer VMIDs and expects Proxmox host details: not provided for Hyper-V (KVP
    addresses come through `getconfig` instead).

11. No compare-and-swap: Proxmox writes carry the config digest; Hyper-V has none, so each PowerShell script re-checks
    what it relies on (state, slot, file, name) immediately before its write, in the same session.

## Test status

- `test_hyperv_cluster.py`: 53 tests through the entry point against a simulated cluster (identity, native adoption
  context, exit 90/91, every action's result shape and refusals). The simulator was written with the extension, so it
  checks the Python logic, not Hyper-V's behaviour.
- `check_powershell_syntax.py`: every script parses (pwsh parser).
- CloudStack's own `ExternalDiscoveryManager.parse`, `ExternalVmConfiguration.parse` and `ExternalStorageInventory.parse`
  (release build `6fb6c184bc`) accept sample `discover`, `getconfig` and `liststorage` answers (`wirecheck/`).
- `console_helper/test_hyperv_console_helper.py`: 6 tests (what it starts, no secret on a command line, teardown).
- Console helper installed on the lab management server and smoke-tested with real Xvfb/x11vnc/xfreerdp3 against an
  unreachable RDP target: one-time password accepted, a wrong one refused, sessions torn down when RDP ends. The RDP
  half (VMConnect to a real node) is untested until the Hyper-V lab exists.
- **Live lab (from 16 Nov 2026):** see "Live results" below.

## Live results (nested lab, `hvc01`)

Lab: `hvlab-dc01` (AD `hvlab.lab.guppy.au`, DNS, iSCSI target), `hvlab-hv01`/`hvlab-hv02` (Windows Server 2025
Datacenter Evaluation Core, Hyper-V nested in KVM on HV08), cluster `hvc01`, CSVs `Volume1` (60 GiB) and `Volume2`
(120 GiB), virtual switch `vSwitch` on an isolated trunk. Registered in the lab CloudStack (release build
`6fb6c184bc`, unchanged) as extension "Hyper-V Failover Cluster", cluster `hvc01`, hosts `hvlab-hv01`/`hvlab-hv02`,
pools `ext-Volume1`/`ext-Volume2`. Test guests: `hvtest01` (Gen 1, not clustered) and `hvtest02` (Gen 2, clustered),
Alpine with no Hyper-V integration services.

### Defects found and fixed

| Finding | Fix |
|---|---|
| CloudStack hides a `password` detail and `getExternalAccessDetails` passes on only displayed details, so the mapping password never reached the extension | The mapping carries it as `secret` |
| `Move-VMStorage -VHDs` rejected the hashtable: `Join-Path` returns a PSObject-wrapped string, which fails the cmdlet's key check ("must contain 'SourceFilePath'") | Plain `[string]` values. The failed move had sent no change, but the extension cannot tell an exception from a partial move, so it rightly reported an unknown outcome and the lease stayed until released |
| On a running VM Hyper-V reports a DVD eject only after the guest releases the disc (~4 s); the extension read back too early and reported an unknown outcome | `detach` waits up to 60 s for the drive to show empty |
| Hyper-V **dynamic MAC addresses change** when a VM is started on another node (`hvtest02` came back from a stop/start on `hvlab-hv02` with a new MAC). CloudStack knows a NIC by its MAC; refresh then offered to import the "new" NIC and left the recorded one report-only | `start` makes every dynamic adapter address static (its present value) before a CloudStack start. Adopted guests started only outside CloudStack can still drift: make their MACs static before adoption |
| Each PowerShell run pays the module load (~1.5 s WinRM + PowerShell, ~2 s Hyper-V, ~4 s FailoverClusters) | Cluster, storage and the host node's inventory are read in one run (`overview`) |
| Graceful stop with `Stop-VM -AsJob` intermittently ended the whole PowerShell process (exit 2, no output) while the guest shut down; the VM was off but CloudStack saw an unknown outcome (seen twice in a row, reproduced directly) | Guest shutdown through `Msvm_ShutdownComponent.InitiateShutdown` and a poll, as reboot already did; 3/3 direct and the CloudStack cycles below then passed |
| `prepare`/`create` refused every deployment while any node was down ("inventory would be incomplete") | They check VMID uniqueness against the nodes that are up: a random 31-bit VMID meeting one of a down node's VMs is very unlikely, and duplicates are refused once the node returns |
| A template deployment ignored the root disk size asked for (`rootdisksize`); the disk got the template's `disk_size_gb` | The larger of the two. The Proxmox extension has the same gap (it reads only `disk_size_gb`) |
| Console helper: the management server (user `cloud`) could not reach the socket (runtime directory 0750) | Directory 0751; the socket stays 0660 `hvconsole:cloud` |
| Console helper: FreeRDP asked for user, domain and password on stdin read none of them; with them given, VMConnect never finished activation | `/u:` and `/d:` on the command line (not secret), only the password on stdin; `/sec:nla -gfx` |

### Confirmed through CloudStack

- Host registration, status batches every minute, `listExternalInstances` (`hastate` from the cluster role).
- Pool registration: CSV backing `csv/@hvc01/<name>`, mapped only to `hvc01` (the Proxmox clusters are not).
- `adoptExternalInstance` dry run and apply of a running Gen 1 and a running clustered Gen 2 guest: no restart, root
  VHDX imported as a volume on the right pool and offering.
- Refresh dry run: no drift (and the MAC drift above, before the fix).
- Stop, stopped scale (1/512 → 2/1024), start. Start ejected the provider-only ISO, as stock CloudStack-start media
  semantics require.
- Reboot of a guest without integration services refused before any write (exit 90), as designed. With them (Alpine
  on `hvnew-uefi`): guest restart through the shutdown component, heartbeat seen dropping and returning, no reset.
- After the stop fix: five start/stop cycles and a reboot through CloudStack, all successful, no leases left.
- Gen 2 running: NIC hot-add and remove; data disk add (SCSI hot-add), grow, live move CSV1 → CSV2
  (`Move-VMStorage`), delete; no restart.
- Gen 1 running: ISO attach (copied once from secondary storage to `Volume2\iso\cloudstack-<uuid>.iso`, checksum
  verified) and detach, repeated; attach refused when the only drive holds a provider disc.
- Instance Snapshot with memory: create, revert (restarts the VM, as on Proxmox), delete. Disk-only on a running guest
  without integration services: Hyper-V refuses a Production checkpoint; the extension forces `ProductionOnly` rather
  than silently taking a crash-consistent Standard one, and reports a known failure.
- HA: `updateVirtualMachine haenable` on an HA offering adds and removes the cluster role while running (uptime
  continuous); an offering without HA is refused by stock CloudStack.
- Out-of-band live migration (`Move-ClusterVirtualMachineRole`): CloudStack showed the new host within ~15 s; later
  actions went to the new owner node.
- Deployment from a CloudStack ISO: BIOS → Gen 1 (IDE), UEFI legacy → Gen 2 with Secure Boot off and the ISO first in
  the boot order; VMID marker in Notes, CloudStack's static MACs, root volume bound on the chosen pool; the UEFI
  guest booted Alpine to a login prompt (seen on the console).
- Template deployment (Alpine cloud image converted to VHDX on `Volume2\templates`, template details
  `template_volume`, `generation=2`, `disk_size_gb`): full copy to the chosen pool, root grown to the asked size,
  booted (seen on the console); deployed while `hvlab-hv02` was powered off.
- Node failure: `hvlab-hv02` hard powered off with clustered `hvtest02` running on it. The cluster restarted the guest
  on `hvlab-hv01` after ~4 min (WSFC's default 240 s resiliency period), CloudStack showed it there within ~30 s, its
  (static) MAC unchanged, no CloudStack HA work queued, no leases. When the node came back it rejoined and status
  batches resumed. **CloudStack kept `hvlab-hv02` Up/Enabled for the whole 15 minutes it was off**: the status
  batch failed every minute (logged as a failed ping task) but nothing changed the host's state. That is the stock
  External host path, the same for Proxmox hosts, not this extension.
- Expunge of an adopted and of a CloudStack-created guest: VM and its folder removed from the CSV.
- Console: `createConsoleEndpoint` → console proxy → helper → VMConnect: authenticated WebSocket with an RFB banner;
  a frame read from the helper directly showed the guest's real screen. The session ends when the VNC client leaves.
- Cluster-role changes over plain NTLM with the mapping account; no CredSSP or delegation needed.

### Host state (CloudStack side, lab build `hosthealth-b`)

Before: a hard-off node stayed `Up` in CloudStack indefinitely. The status batch failed every minute, the ping timeout
investigation sent `CheckHealthCommand`, and `ExternalResource` answered it "healthy" whenever the extension was merely
configured; the ping investigator then "pinged" the host address `External` successfully. Now (`health` action, gated by
`hosthealthsupported=true`):

- Hard power-off of hv02: CloudStack `Up` → `Down` ~2 min later (ping timeout + investigation; hv01 confirmed hv02
  `Down` in the cluster). No CloudStack HA work queued; the cluster's own role failover is unaffected.
- While the node is off (or quarantined by WSFC after repeated hard stops) every reconnect attempt is refused
  (`Not connecting External host ...: its node health is unknown`), so the host stays `Down` instead of flapping.
- Node back and an up cluster member: CloudStack `Up` within ~90 s (next direct-agent scan).
- Without a confirming peer the host goes `Alert` instead of `Down` (seen in build `hosthealth-a`, before the ping-test
  fix).

### Windows guest (Windows Server 2025 Core, Gen 2, Secure Boot, no vTPM)

`hvwin01`, built out of band, adopted natively (running, no restart) as `i-5-203-VM`. Through CloudStack, all passed
with no leases left: refresh took the IP 10.100.0.50 from the host (integration-services KVP, no agent install);
disk-only Instance Snapshot while running (a Production checkpoint, VSS in the guest) and delete; memory snapshot,
revert, delete; data disk add 1 GiB, grow to 2 GiB, delete while running; graceful reboot, stop and start through
the guest shutdown service; console (websocket 101 + RFB 3.8). Adoption first failed: its provider read had a fixed
60 s wait and two settled reads of the busy nested host took ~45-65 s; it now uses the agent wait, as refresh does.
vTPM is not tested (needs the shared guardian).

### Not yet tested live

KVP addresses and Production checkpoints of an integration-services guest, Secure Boot +
vTPM, Windows guests, two management
servers, and anything at production speed: `getconfig` takes ~30 s on this nested lab and a refresh (four
`getconfig`s) ~2 min.
