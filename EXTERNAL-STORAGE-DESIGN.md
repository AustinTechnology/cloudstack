# External VM disks in CloudStack: design (step 8)

**Status:** first draft, 14 Nov 2026. Nothing is built. Four decisions are open (last section).

**Goal:** the disks of External (Proxmox) VMs are visible in CloudStack as volumes, and can be added, grown, moved and deleted from CloudStack, without CloudStack ever damaging a disk it does not own.

**Approach:** each Proxmox storage becomes a CloudStack primary storage pool and each guest disk a normal CloudStack volume. A storage driver in the External plugin sends volume operations to the cluster's existing Orchestrator extension. Existing disks are imported by the step 7 refresh, which never writes to Proxmox.

**Tech stack:** Java (CloudStack 4.23.0.0 fork, branch `step7-native-sync`), bash extension script (`cloudstack-proxmox-extension`).

## What exists today

Read from the 4.23.0.0 source and from production on 14 Nov 2026. Scripts and outputs: the operator notes.

**The extension framework has no storage support.**

- Extension types are `Orchestrator` and `NetworkOrchestrator` only (`api/.../extension/Extension.java`). Upstream `main` is the same today.
- `ExternalProvisioner` has methods for prepare, start, stop, reboot, expunge, power report, console and custom actions. None takes a volume.
- The management server skips storage for External VMs: `VirtualMachineManagerImpl` 1523 (`volumeMgr.prepare`) and 2373 (`volumeMgr.release`), and the pool check at deploy (`UserVmManagerImpl` 4694).
- It refuses volume operations outright: attach and detach (`VolumeApiServiceImpl` 3019, 5077), migrate (3752), volume snapshot (4238), VM snapshot (`VMSnapshotManagerImpl` 338, 543).
- The script creates a new VM's one disk from two VM details, `storage` and `disk_size_gb`. `getconfig` already returns a guest's disks; the refresh reports them and records nothing.

**CloudStack records no disks for External VMs.**

- Production: 159 External VMs in 29 accounts, no rows in `volumes`. 152 carry a `rootdisksize` detail.
- Production already has 5 zone-wide primary storage pools for KVM. Their Ceph pool names are the same ones the Proxmox storages use.
- Lab: no volumes and no primary storage pool of any kind.

**What Proxmox holds (both clusters, 166 guests).**

| | Count |
|---|---|
| Disks that hold guest data | 217, over 100 TiB provisioned |
| On Ceph RBD (shared) | 210 |
| On node-local LVM-thin (P2, one per node) | 6 |
| On a node-local directory (P3) | 1 |
| Guests with 1 disk / 2 / 3 or more | 133 / 27 / 6 (one has 11) |
| Disk bus: SCSI / SATA / VirtIO / IDE | 135 / 63 / 15 / 4 |
| EFI disks / TPM state volumes / CD drives / unused disks | 102 / 17 / 117 / 1 |
| Guests with a Proxmox snapshot | 1 |

These are all Proxmox guests, including the few that have no CloudStack record.

**Hazard found while reading: image names are not unique to a guest.**

- Both Proxmox clusters' storages and the CloudStack KVM pools name the same Ceph pools, with no RBD namespace. That they are one Ceph cluster was not checked separately.
- Proxmox names an image after the VMID (`vm-110-disk-0`), and 16 VMIDs are in use in both clusters.
- For 7 of them, both clusters' guests keep disks in the same Ceph pool: 106, 107, 110, 112, 113, 114 and 118. For example VMID 110 is a tenant guest in one cluster and the CloudStack database VM in the other.
- No config in one cluster references an image the other cluster uses. But each cluster's Proxmox sees the other guest's images as belonging to its own VMID.
- Not tested: what Proxmox's "destroy unreferenced disks owned by guest" option, or a disk rescan, does with those images. This exists today, independent of this design.

## Design

### Model

- **Pool.** One CloudStack primary storage pool per Proxmox storage that holds guest images, registered for the External cluster under a new storage provider in the External plugin. Shared storages (RBD) are cluster-scoped; node-local ones (LVM-thin, directory) are host-scoped. The pool's path is the Proxmox storage name.
- **Volume.** One volume per data-bearing disk. Its path is the Proxmox volume ID exactly as the guest's config references it; a volume detail holds the Proxmox slot (`scsi1`). The first disk in the boot order is the root volume; the rest are data disks.
- **Not modelled.** EFI disks, TPM state, CD drives, unused disks and passthrough devices stay with Proxmox. The refresh reports them.
- **Identity.** Cluster + storage + volume ID as referenced by the guest's config. Ownership is never inferred from the VMID inside an image name (see the hazard above).
- **Offerings and accounting.** Every volume has a disk offering, and creating, growing or removing one writes the usual usage event and updates the account's volume count and primary storage total, the same way the step 7 refresh handles CPU and RAM.
- **Capacity.** Read from Proxmox for display. The same Ceph pools are already counted by the KVM pools, so zone-wide capacity totals will count them twice.

### Who writes what

Proxmox stays authoritative for which disks exist. There are two writers and they never overlap:

- **Refresh** changes CloudStack to match Proxmox and never changes Proxmox. It imports a disk it has not seen, updates a size that changed, and removes a volume record only when the disk is gone from the guest's config and the image no longer exists on the storage. A disk that was detached but still exists is reported, not removed.
- **Volume operations from CloudStack** change Proxmox through the extension and then record the result. They follow the rules already used for NICs: exact identity, a configuration-digest guard, no pending changes, the expected power state, and an operation lease for adopted guests. An unknown outcome keeps the CloudStack record and is never replayed.

Destructive calls name one exact volume ID. The extension never asks Proxmox to clean up unreferenced disks, and passes that option explicitly as off when deleting a VM.

### Extension contract

The existing Orchestrator extension gains actions; no new extension type. Each capability is declared by an extension detail that defaults to unsupported, as with scale and NIC changes.

| Action | Does |
|---|---|
| `getconfig` (extended) | Per disk: slot, storage, volume ID, size in bytes, boot flag |
| `liststorage` | Storages that hold guest images, with type, scope and capacity |
| `createdisk` | Allocates an image for the guest on a storage and attaches it; returns volume ID and slot |
| `resizedisk` | Grows one disk (Proxmox cannot shrink) |
| `deletedisk` | Detaches and destroys one exact volume |
| `movedisk` | Moves one disk to another storage |

On the Java side: a storage provider and driver in the External plugin, volume methods on `ExternalProvisioner`, and the management-server refusals lifted for External only where the VM's extension declares the capability. `volumeMgr.prepare` and `release` stay skipped: the disks already exist at the provider and there is nothing to copy.

### Phases

Each phase is usable alone.

1. **See.** Pools registered; the refresh, the periodic worker and `adoptExternalInstance` import and update volumes. Disks show in the UI and API with size and storage. No Proxmox writes. New VMs deployed through CloudStack get a root volume record.
2. **Change attached disks.** Add a data disk, grow a disk, delete a data disk, move a disk between storages.
3. **Detached volumes.** Detach a data disk and attach it to another VM. A Proxmox image always belongs to a guest, so this needs a decision on where a detached disk lives.
4. **Snapshots.** CloudStack VM snapshots mapped to Proxmox snapshots, replacing the four custom actions. Proxmox snapshots cover the whole guest, so there are no per-volume snapshots.

Not in scope: backup, templates made from volumes, volume upload and download, ISO attach, reinstalling a root disk, encryption, IOPS limits.

## Risks

- **Core changes to carry.** Lifting the refusals touches `VolumeApiServiceImpl` and `VMSnapshotManagerImpl`, and the pool may need a new pool type. Each is another patch to re-apply on upgrade until upstream takes it.
- **Billing starts at import.** Phase 1 creates usage records and raises account storage totals for over 100 TiB that CloudStack has never counted.
- **Shared Ceph pools.** A wrong delete can reach another cluster's guest. The identity rule and the explicit "no unreferenced cleanup" are the controls; the lab cannot reproduce two clusters on one pool as it stands.
- **The lab has no Ceph.** Its storages are NFS and a local directory. RBD behaviour (erasure-coded data pools, move between pools) would first be exercised in production unless the lab gets a Ceph pool.

## Open decisions

1. **Usage and limits at import.** Should imported disks produce usage records and count against account limits from the day they are imported?
2. **Disk offerings.** One custom-size offering per storage tier (SSD, 10K, HDD), or a single offering for everything?
3. **Detached volumes (phase 3).** Is moving a data disk between VMs needed? If not, phase 3 is dropped and a data disk lives and dies with its VM.
4. **Snapshots (phase 4).** Is whole-VM snapshot through CloudStack's VM snapshot feature the right mapping, with no per-volume snapshots?
