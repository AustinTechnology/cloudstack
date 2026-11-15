# External VM disks in CloudStack: design (step 8)

**Status:** draft, 14 Nov 2026, with the four decisions of that day recorded (last section). Nothing is built.

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

- **Pool.** One zone-wide CloudStack primary storage pool per Proxmox storage that holds guest images, under a new storage provider in the External plugin. Every Proxmox cluster in the zone that reaches the same storage shares the one pool (decided). The KVM pools on the same Ceph pools stay separate pools. Node-local storage is not registered. The pool's path is the Proxmox storage name.
- **Volume.** One volume per data-bearing disk. Its path is the Proxmox volume ID exactly as the guest's config references it; a volume detail holds the Proxmox slot (`scsi1`). The first disk in the boot order is the root volume; the rest are data disks.
- **Not modelled.** EFI disks, TPM state, CD drives, unused disks and passthrough devices stay with Proxmox. The refresh reports them.
- **Identity.** Pool + volume ID as referenced by the guest's config. Clusters that share a pool share one set of image names, as they do in Ceph. Ownership is never inferred from the VMID inside an image name (see the hazard above).
- **Offerings and accounting.** Every volume has a disk offering, and creating, growing or removing one writes the usual usage event and updates the account's volume count and primary storage total, the same way the step 7 refresh handles CPU and RAM.
- **Capacity.** Read from Proxmox for display. Each Ceph pool is counted once for Proxmox and once by its KVM pool, so zone-wide capacity totals count it twice.

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

1. **See.** Pools registered; the refresh, the periodic worker and `adoptExternalInstance` import and update volumes. Disks show in the UI and API with size and storage. No Proxmox writes. A VM deployed through CloudStack gets its volume records at its next refresh. **Built and tested in the lab; see below.**
2. **Change attached disks.** Add a data disk, grow a disk, delete a data disk, move a disk between storages. **Built and tested in the lab; see below.**
3. **Whole-VM snapshots.** CloudStack VM snapshots mapped to Proxmox snapshots, replacing the custom actions. Built; see "Phase 3 as built".
4. **Per-disk snapshots on Ceph.** Built in the lab, 15 Nov 2026, as stock volume snapshots with the stock copy to secondary storage: `EXTERNAL-VOLUME-SNAPSHOT-PLAN.md`.

Deferred, not dropped: detaching a data disk and attaching it to another VM. Until then a data disk lives and dies with its VM. A Proxmox image always belongs to a guest, so this will need a decision on where a detached disk lives. Nothing in phases 1 and 2 may tie a volume's identity to its VM.

Not in scope: backup, templates made from volumes, volume upload and download, ISO attach, reinstalling a root disk, encryption, IOPS limits.

### Limits at import

Imported disks count toward account limits (decided). The import itself is never refused by a limit, because the disks exist whatever CloudStack thinks; it raises the counts and reports every account that lands over.

- Production defaults are 20 volumes and 200 GiB of primary storage per account. Five accounts have their own primary storage limit and three their own volume limit.
- The 29 accounts that own External VMs have nothing counted today. Over 100 TiB will be spread over them, an average of more than 4 TiB each.
- An account over its limit cannot deploy a VM or add a disk until the limit is raised. Limits therefore have to be raised before the import, from the per-account totals that the phase 1 dry run produces.

### Offerings

One custom-size disk offering per storage tier (decided), each tagged to its storage:

| Offering | Proxmox storage | Data disks there today |
|---|---|---|
| Standard SSD | `ssd-ec` | 150 |
| Premium HDD | `hdd10k-ec` | 27 |
| Standard HDD | `hdd-ec` | 31 |

Nine data disks are on storages with no tier: one on `ssd-replicated`, one on `mixed-replicated`, six on node-local LVM-thin and one on a node-local directory. A disk on a storage with no offering is reported and not imported.

Production has none of these offerings yet: its disk offerings are three fixed sizes, one untagged custom offering and one tagged `SAS`. The KVM pools already carry one tag per Ceph pool (`ceph-ssd-ec` and so on), which is the same tiering.

### Per-disk snapshots on Ceph (built; see `EXTERNAL-VOLUME-SNAPSHOT-PLAN.md`)

Built and tested in the lab 15 Nov 2026: the as-built record is `EXTERNAL-VOLUME-SNAPSHOT-PLAN.md`. The notes below are the earlier starting point and are superseded where they differ; in particular the copy to secondary storage is kept, as stock.

Proxmox has no per-disk snapshot, but 210 of the 217 disks are RBD images, and Ceph can snapshot one image.

What is known:

- CloudStack does not talk to Ceph from the management server. Its KVM host agents do, with the Ceph libraries (`KVMStorageProcessor`). Production has 8 KVM hosts and 5 KVM pools on the same Ceph pool names, so the machinery and the credentials already exist there.
- Proxmox does not know about a snapshot made outside it. When it deletes a disk it removes every snapshot of the image first, protected ones included (`RBDPlugin.pm`, `free_image`, read on PVE 9.2). Moving a disk to another storage is expected to copy only the current state and delete the source the same way; that path was not read.
- Proxmox's own whole-VM snapshots are RBD snapshots on each disk, named after the VM snapshot. Names must not collide.
- Rolling one image back is only safe with the guest stopped. A snapshot of one disk of a running guest is crash-consistent.
- Production has `snapshot.backup.to.secondary=true`, so a CloudStack volume snapshot is copied to secondary storage after it is taken. For multi-terabyte disks these snapshots would have to stay on Ceph instead.

Two ways it could be wired, neither traced in the code yet:

| | How | For | Against |
|---|---|---|---|
| Through the KVM hosts | The External storage driver hands snapshot, revert and delete to a KVM host that has the matching Ceph pool | No new Ceph credential; CloudStack's snapshot schedules and retention come with it | Unknown whether the snapshot framework allows this routing; several snapshot paths are written for KVM VMs only (`SnapshotManagerImpl`) |
| From the extension | The script runs `rbd` itself | Independent of KVM hosts | Needs a Ceph client and keyring on the management server |

**Decided 14 Nov 2026: the extension runs `rbd` itself, with its own Ceph client identity.** Ceph is independent of Proxmox here; Proxmox is only a client of it.

Either way CloudStack must refuse or clean up when a disk with snapshots is moved or deleted, because Proxmox will remove them.

The lab has had both since 15 Nov 2026 (see "Ceph and a second cluster"), so this can be tried there.

## Phase 1 as built (14 Nov 2026, lab only)

Setting up a cluster:

1. Declare `instancestoragesupported=true` on the extension. Without it nothing below is accepted.
2. Register each storage once for the zone: `createStoragePool` with `provider=External`, `scope=zone`, `hypervisor=External`, `url=external://<Proxmox storage name>` and a tag. Every External cluster in the zone is asked. On each cluster that has the storage it must be enabled, active, shared and hold guest disks, and it must be the same storage on all of them; otherwise nothing is registered.

"The same storage" is decided by what is behind it, not its name: for Ceph the pool, data pool and namespace, with at least one monitor in common (production's two clusters list different monitors for the same Ceph); for NFS the server and export. Storage only one cluster can reach never matches another cluster's. A cluster added later is compared the first time one of its guests is refreshed, and its disks are left out and reported if its storage of that name is something else.

A pool is removed by putting it into maintenance mode and deleting it. Maintenance mode is refused while the pool records any volume, and moves or stops nothing.
3. Create one custom-size disk offering carrying the same tag. A storage whose tag matches no offering, or more than one, has its disks reported and not recorded.

The tag of an External pool must not be on any KVM pool. A volume created loose from a tier's offering is only a record until phase 2 gives it somewhere to go, and a shared tag would let CloudStack place it on KVM storage.

What a refresh or adoption then does, for each disk in the guest's configuration:

- A disk on a registered storage with an offering becomes a volume: the boot disk is the root volume, the others data disks. Its identity is the pool and the Proxmox volume ID (`<storage>:<volume>`). It is found by that ID in the guest's own configuration, never by scanning the storage or matching a VMID in an image name.
- A size change at Proxmox updates the volume, the account's total and writes a resize usage record.
- A volume whose disk is no longer in the configuration is reported and left alone.
- Limits never stop a disk being recorded. An account that lands over a limit is named in the response, in the dry run too.
- The dry run of both calls lists exactly what would be recorded and writes nothing.

Removing the instance removes its volume records, counts and usage with it. The guest is destroyed at Proxmox with its disks, adopted or not (changed 15 Nov 2026: before that an adopted guest and its disks stayed at Proxmox; see "Expunge of an adopted instance").

The ordinary volume API on these volumes: detach, extract, snapshot and snapshot schedule are refused before anything is recorded; resize, migrate, delete, attach elsewhere and reinstall were already refused. Making a template from the root disk of a stopped guest fails at the host and leaves nothing behind.

Verified in the lab (`storage-v8`, 736 unit tests and 45 extension tests passing), all of it on a zone-wide pool: registration and its four refusals (cluster scope, node-local storage, unknown storage, duplicate); a cluster forgotten by the pool compared again and remembered on the next refresh; a pool whose record was changed to a different storage refusing to record that cluster's disks and leaving the existing volumes alone; removing an empty pool through maintenance mode; dry run, import, repeat with no change; a disk grown at Proxmox, running and stopped; an adopted guest expunged with both disks kept at Proxmox and re-adopted into an account limited to one volume and 1 GiB, which recorded two volumes and 5 GiB and said so; a deployed guest imported then destroyed with its disk; destroy and recover; stop, start and reboot of guests with volumes; the periodic worker importing the guests not yet done, once; capacity and used bytes following Proxmox every minute.

Not tested: two Proxmox clusters sharing one pool (the lab has one cluster; the sharing rules are covered by unit tests only, and production's own storage definitions were read and satisfy them), Ceph (the lab storage is NFS), a guest with an EFI or TPM disk, a disk on an unregistered storage alongside registered ones on a real guest, and anything at production scale.

## Phase 2 as built (14 Nov 2026, lab only)

Lab build `storage-p2g`. Not installed in production and not pushed.

Every change is made at Proxmox first and recorded only when Proxmox shows the result that was asked for. The ordinary volume API is used; there are no new API calls.

| To | Call | What happens |
|---|---|---|
| Add a data disk | `createVolume` with a tier's offering and a size, then `attachVolume` | A new disk of that size is created on the tier's storage, in the next free slot on the bus of the boot disk (SATA when the boot disk is IDE), with the boot disk's discard, SSD and iothread settings. |
| Grow a disk | `resizeVolume` with the new size | Root or data disk. Whole GiB, larger only. |
| Delete a data disk | `deleteVolume` or `destroyVolume` on the attached volume | The disk leaves the guest and its image is deleted in one Proxmox task. The volume is expunged at once: there is nothing to recover. The root disk is refused. |
| Move a disk | `changeOfferingForVolume` to another tier's offering with `automigrate=true` (owner), or `migrateVolume` to the pool (admin) | Proxmox copies the disk to the other storage and deletes the source. The volume takes the destination tier's offering. Without `automigrate` a change of tier is refused. |

Rules:

- **Running guests.** Add and delete work on a running guest only on a SCSI or VirtIO slot with disk hotplug on. Anything else would leave a pending change, so it is refused with "stop the instance". Grow and move work running or stopped.
- **Identity.** A change names the slot and the exact Proxmox volume ID recorded for the volume. The extension refuses if that slot does not hold that volume. Delete names one volume; nothing is ever found by VMID or by scanning storage.
- **Before any write** the extension refuses a template, a locked guest, a guest with pending changes and an unstable power state, and sends the configuration digest with the write so Proxmox refuses it if the guest changed in between.
- **One change at a time.** A disk change and a refresh of the same instance exclude each other on every management server (a database lock named for the instance). A disk change waits 30 seconds for the other to finish, a refresh 1 second, then each is refused with "in progress". The lock holds one database connection for as long as the change runs.
- **Adopted guests** also take an operation lease, as for every other change. A failure Proxmox reported releases it; an unknown outcome keeps it for the operator.
- **Unknown outcome.** Nothing is recorded. The next refresh records what Proxmox has: a new size, or a disk now on other storage (same slot, different storage is recorded as a move of the same volume, with its offering).
- **Limits.** An added disk is counted when its volume is created, the stock way, so an account at its limit is refused there. A grow is checked against the limit. A move is never checked, so an account over its limit after the import can still move a disk.
- **Wait.** A move is given 6900 seconds, the longest any action may take. A disk that needs longer is moved in Proxmox itself and the next refresh records it.
- **Still refused:** detach, download, snapshot, snapshot schedule, shrink, and attaching a volume that already has a disk.

The capability `instancestoragesupported` on the extension covers phases 1 and 2. The instance response now carries it, and a volume on External storage reports `hypervisor: External` to every caller, which is what the UI goes by.

UI: "Create and attach volume" on the instance's Volumes tab works for an External instance whose extension has the capability. On such a volume, detach, snapshot, recurring snapshot, download, create template and migrate are hidden; delete is offered for an attached data disk with its own warning; Change Offering lists only the other tiers.

Proxmox privileges the extension's role needs for this phase: `VM.Config.Disk`, `Datastore.AllocateSpace`, `Datastore.Audit`. The lab role has them, and so does production's role on both clusters (read 14 Nov 2026).

Verified in the lab (745 unit tests, 53 extension tests), on two NFS storages with a tier each:

- Adopted running guest: add, grow twice, shrink refused, move to the other storage, tier changed back with a grow, delete. The guest's QEMU process was the same throughout; the images appeared and disappeared on the right storage; counts and usage records followed each step.
- Root disk of a running adopted guest grown; root delete refused.
- Guest CloudStack deployed, running: add on the second tier, delete. Stopped guest: add, grow, delete. Guest in Proxmox HA: add, delete, HA state unchanged.
- A locked guest: grow refused before any write, lease released, record unchanged.
- An offering with no tier: attach refused. An account over its limits: add and grow refused, move allowed.
- As the owning ordinary user: the tiers listed for a disk, a tier change refused without `automigrate` and done with it, `migrateVolume` not available.
- A disk moved in Proxmox behind CloudStack: the refresh recorded the move and the new offering once, and nothing on repeat.
- A 111 second move: a grow and a refresh sent during it were both refused, and counts were unchanged by the move.
- Detach, snapshot, snapshot schedule, download and shrink still refused, with no row changed.
- After all of it: no drift on any guest, no lease, no fence, no leftover image or unused disk.

Not tested:

- Ceph and two Proxmox clusters on one pool were not tested at the time; both have been since. See "Ceph and a second cluster".
- An unknown outcome on a real guest. The lease and recording rules for it are covered by the extension's tests against a fake Proxmox, not live.
- A move that outlasts the wait.
- SATA, IDE and VirtIO slots on a real guest (extension tests only); a guest with EFI or TPM disks; Windows.
- The UI by hand. The button rules were checked from the source and the built UI is installed in the lab, but nobody has clicked through it.
- A management server restart in the middle of a change.

Known gaps:

- When an attach is refused (wrong offering, no free slot), the volume that was just created stays behind unattached, as with any failed attach. It is only a record and can be deleted.
- A guest deployed through CloudStack still gets its root volume record at its first refresh, not at deploy.
- The stock resize form asks for IOPS when the first disk offering in the zone has custom IOPS. A resize that carries IOPS is refused for these volumes.

## Ceph and a second cluster (lab, 15 Nov 2026)

The lab gained a one-node Ceph (`dg-acs-ceph01`, 192.168.64.211, cephadm, Ceph 20, independent of Proxmox) and a second Proxmox cluster of one node (`acs-lab2`, `dg-acs-pve03`, 192.168.64.212), registered as a second External cluster of the zone with the same extension. Both Proxmox clusters define the same three storages, each cluster with its own Ceph client, as production does:

| Proxmox storage | Behind it | CloudStack pool, tag, offering |
|---|---|---|
| `lab.ssd.ec21` | RBD, metadata pool `rbd.lab.ssd.ec21.meta`, erasure-coded data pool `rbd.lab.ssd.ec21` | `ext-lab.ssd.ec21`, `ext-lab-ssd-ec21`, "Ceph SSD EC" |
| `lab.hdd.rep` | RBD, replicated pool `rbd.lab.hdd.rep` | `ext-lab.hdd.rep`, `ext-lab-hdd-rep`, "Ceph HDD" |
| `iso-library` | CephFS `lab.library`, one subvolume | the ISO storage (`iso_storage` on the extension) |

The NFS storages stay on the first cluster only. No code changed for any of this except the disk delete below.

| Check | Result |
|---|---|
| Register the two RBD storages | One zone-wide pool each, bound to both clusters by what is behind the storage |
| Running guests, first cluster: add on the erasure-coded tier, grow, move to the replicated tier, tier change back with a grow | Pass; the image has the data pool; same QEMU process |
| Running adopted guest: root disk moved NFS (qcow2) to Ceph; a data disk moved Ceph to NFS | Pass; source removed each time; same QEMU process |
| Guest created in the second cluster while the first has a guest with the same VMID on the same Ceph pool | Proxmox names the new image with the next free number in the pool (`vm-100-disk-1` beside the other cluster's `vm-100-disk-0`); each volume is recorded under its own name |
| Add, grow, move on the second cluster's guest; move to a storage its cluster does not have | Pass; the last is refused: "The instance's cluster does not reach that storage" |
| Expunge the second cluster's guest 100 | Its two images are deleted; the first cluster's guest 100 keeps both of its images on the same pools and keeps running |
| Adopt a running guest in the second cluster whose VMID (103) is also an adopted guest of the first | Two claims, one per cluster; both disks imported under the right names; same QEMU process; stop, start, disk add and delete work |
| Create from an ISO with UEFI, and with UEFI Secure, root disk on Ceph | EFI disk and TPM state are RBD images on the root disk's pool; the root volume is the boot disk, not the first image; the signed Debian installer boots; expunge removes all of them |
| Refresh of every guest afterwards (dry run) | No change, nothing unresolved; account counts equal the volumes; no lease or fence |

### Found and changed: a disk delete the guest does not complete

A running guest that has only just started (or that holds the device) does not release a disk. Proxmox then fails the task but has already taken the disk away from the guest, and keeps the delete waiting; it deletes the image when the guest next stops. Before this change CloudStack was left with a record it could neither delete (the waiting change refused every later disk action) nor, after the stop, find.

`deletedisk` now:

- says "The guest has not released the disk. Delete it again once the guest has finished starting, or stop the instance first" and, for an adopted guest, releases the lease, because the state is known;
- accepts being asked again while only its own delete is waiting, and sends the delete again;
- succeeds without a write when the disk is in no slot of the instance, however it left;
- deletes the disk when Proxmox holds it as an unused disk of the instance.

It still refuses when the recorded volume is in a different slot (the record is stale; refresh). Shown live on the second cluster's adopted guest: failure with the message, then a second delete that succeeded; and failure, a stop (Proxmox deleted the image), then a delete that succeeded with nothing to do. Counts were right after each.

### Found, not changed

- **Which cluster a new instance goes to.** Left as stock by decision (15 Nov 2026): every storage of a zone is mapped to all of its clusters. Stock External planning takes the first cluster that has a host Up, every time, and does not look at storage. With no host named, every new instance went to the first cluster. An instance created from an ISO on a tier whose storage only the other cluster has would fail at its first start with the stock "Unable to orchestrate the start" (nothing is created in Proxmox). Production's three tiers are on both clusters, so this only bites a storage that one cluster lacks.
- **Adoption as root admin where two networks share a VLAN.** Refused, because root admin may use both. The lab has overlapping VLANs only because its test accounts were given them on purpose; adopting for the owning account works.
- **A refresh of a guest with a change waiting** fails with the general "External configuration collection failed", not the reason.

## Phase 3 as built (15 Nov 2026, lab only)

CloudStack's own Instance Snapshots (`createVMSnapshot`, `revertToVMSnapshot`, `deleteVMSnapshot`, `listVMSnapshot`, and the same buttons in the UI) work for an External instance whose extension declares `instancesnapshotsupported=true`. Nothing new was added to the API. The stock snapshot strategy runs unchanged: it sends its three ordinary commands to the instance's host, and the External host hands them to the extension as `createsnapshot`, `revertsnapshot` and `deletesnapshot`.

| | |
|---|---|
| Name | Proxmox holds the snapshot under the name CloudStack generates (`i-2-7-VM_VS_20261115050901`). The name the owner typed, and the description, go into the Proxmox description. |
| With memory | A snapshot with memory is taken from a running instance; Proxmox writes the memory to a state volume on one of the instance's storages. |
| Revert | As stock: a snapshot without memory is reverted to with the instance stopped, and it stays stopped; one with memory with the instance running, and it runs on from the saved memory (a new QEMU process). The extension refuses either on an instance in the other power state, so a revert never stops or starts a guest that CloudStack did not expect to change. |
| Delete | Removes that one snapshot. A snapshot already gone from Proxmox needs no write, so its record can still be deleted, as can the record of a snapshot that failed to be taken. |
| Limits and usage | Stock: `vmsnapshot.max` per instance, usage events per volume. |
| Adopted instances | Each action runs under the claim's `create_snapshot`, `restore_snapshot` or `delete_snapshot` lease. A refusal before any write, or a Proxmox task that failed, releases the lease; anything unconfirmed keeps it. |
| Locking | A snapshot action, a disk change and a refresh of one instance never run together. |
| Other snapshots | A snapshot CloudStack did not make is never touched: every action names one snapshot. It is not imported either. |

What an instance with snapshots can no longer do, as for every other hypervisor: add or remove a NIC (stock check), and change its disks. `adddisk`, `resizedisk`, `deletedisk`, `movedisk` and a tier change are refused, by CloudStack while it records a snapshot of the instance, and by the extension while Proxmox holds any snapshot of it, including one CloudStack does not record. The reason is the same in both: Proxmox keeps the image of a deleted disk, will not move a disk, and a revert undoes whatever was done since. CPU and memory may still be changed with the instance stopped; a revert brings back those of the snapshot, in Proxmox and in the record.

Destroy leaves the snapshots, as for VMware; expunge removes the records without a call per snapshot. Proxmox destroys the instance's snapshots with the instance, adopted or not.

Changed outside the plugin: `VMSnapshotManagerImpl` (the two refusals became the capability check; the hypervisor capabilities table has no External row and is not asked), `VMSnapshotHelperImpl` (the host for an External instance is its own host or, when that is down, another of its cluster: its storage is zone-wide and says nothing of the cluster), `VirtualMachineManagerImpl` (snapshots on destroy, as VMware), `UserVmManagerImpl` (see the first finding), `UserVmResponse`/`UserVmJoinDaoImpl` (the capability), `ui/src/config/section/compute.js` (the button).

Live in the lab (jar `snap-f`, extension script `c86e59d7`):

| Check | Result |
|---|---|
| Without the capability | Refused: "Operation not supported for instance" |
| Adopted guest, all disks on Ceph, second cluster: snapshot running; snapshot with memory; revert to memory while running; stop, revert without memory, start; delete both | Pass. Each disk image has an RBD snapshot of the same name; the memory state is an RBD image `vm-103-state-<name>`; all are gone after the deletes. No lease or fence left at any step |
| Revert in the wrong power state | Refused by stock, both ways, with its own message |
| A disk on storage that cannot snapshot (raw file on NFS) | Proxmox's task fails: "snapshot feature is not available". The record is left in Error, as stock, and is deleted without any call to Proxmox |
| A disk change while a snapshot exists | Refused; no Proxmox call |
| A guest locked in Proxmox | Refused before any task; the claim is managed with no lease |
| A snapshot deleted in Proxmox behind CloudStack | Its record is deleted; no Proxmox task |
| Stopped: snapshot, scale 1 CPU/256 MiB to 2/512, revert | Proxmox and the record are back at 1/256; account CPU and memory counts follow |
| Running: memory snapshot at 1/256, stop, scale to 2/512, start, revert | Pass after the change below; the record and the counts are back at 1/256 |
| HA-managed adopted guest (qcow2 on NFS): both kinds, revert to memory while running, revert without memory while stopped | Pass; the HA service stays `started` (or `stopped`) throughout; no HA work in CloudStack |
| Ordinary instance on the second cluster with VMID 100, which the first cluster also uses on the same Ceph pools: both kinds, revert, then expunge with both snapshots present | One destroy task. Its image, the image's snapshots and the memory state image are gone. The first cluster's guest 100 keeps its two images and keeps running in the same process |
| Adopted instance with both kinds of snapshot: expunge its records (build `snap-f`, superseded below) | The records and counts are gone; the claim is released; Proxmox keeps the guest, stopped, with both snapshots |
| The same guest adopted again | A disk add is refused by the extension: "The instance has snapshots in Proxmox; its disks cannot be changed until they are deleted" |
| Every guest afterwards | Refresh (dry run) reports no change; account counts equal the volumes; no snapshot records, no RBD snapshots or state images left; four claims managed, no lease, no fence |

Tests: 783 Java, 77 extension, 9 getconfig, and 11 checks of when the UI offers the button.

### Found and changed

- **Revert to a memory snapshot after CPU or memory was changed.** Stock then changes the offering of the running instance, which for External is refused ("Scaling the VM dynamically is not supported"): Proxmox had reverted and CloudStack reported failure and kept the newer CPU and memory. Now `scale` succeeds without a write when Proxmox already has the CPU and memory asked for, running or stopped, and the offering change of a running External instance goes through that. Anything else is still refused for a running instance. (Stock has the same failure for KVM and VMware when the snapshot has less CPU or memory than now; that is left alone.)
- **Volumes had no image format, and that stopped volume statistics for the whole zone.** The stock statistics task (every ten minutes) throws on the first volume without a format and gives up, so no pool after an External pool got statistics, KVM pools included. This came in with phase 1 and would have stopped KVM volume statistics in production. Every External volume now has a format (qcow2 or vmdk by its file name, otherwise raw), and a refresh gives one to a volume recorded without. With formats in place the task then sent every External host a statistics command it cannot answer, one warning per host and pool each run; the External storage driver now takes the question itself and has nothing to report, so External volumes show no physical size or utilisation.

### Not done, and limits

- **The old custom actions.** `CreateSnapshot`, `RestoreSnapshot` and `DeleteSnapshot` are deleted from the lab's extension; the extension script still has their code, unreachable. `ListSnapshots` is kept: it is read-only and is the only way to see, from CloudStack, a snapshot that Proxmox holds and CloudStack does not record. Production needs the same three deletions at install.
- **A snapshot made outside CloudStack** (the one production guest that has one, or one made in Proxmox later) is not imported. It blocks disk changes until it is deleted in Proxmox.
- **Where the memory goes.** Proxmox chooses the storage of the state volume from the instance's disks; it was the replicated pool for one guest and the erasure-coded pool for another. Stock adds the instance's memory size to the snapshot's primary-storage usage event; nothing is counted against the account's storage limit, as stock.
- **Two clusters, same VMID, same name.** A state volume is named from the VMID and the snapshot name. CloudStack's names contain the instance name, so two instances never share one. The old custom actions took a name from the user, which could collide across clusters on a shared pool.
- **CD drives.** A revert brings back the CD drives of the snapshot in Proxmox. CloudStack's record of attached ISOs is not changed; it is applied again at the next start through CloudStack, as it is after any change made in Proxmox.
- **Quiesce.** Not offered. Proxmox freezes the guest's filesystems itself when the guest agent is enabled.
- **Not shown live:** a lost answer during a snapshot action on an adopted guest (the lease rules are the ones every other action uses, and are covered by the extension's tests); Windows guests; a second management server; the UI clicked in a browser (the rule that shows the button is checked, and the built bundle is deployed).

## Risks

- **Core changes to carry.** Phases 1 and 2 touch `VolumeApiServiceImpl`, `SnapshotManagerImpl`, `UserVmManagerImpl`, `VolumeOrchestrator`, `QueryManagerImpl`, `VolumeJoinDaoImpl`, `UserVmJoinDaoImpl` and three UI files. Phase 3 adds `VMSnapshotManagerImpl`, `VMSnapshotHelperImpl` and `VirtualMachineManagerImpl`. Each is another patch to re-apply on upgrade until upstream takes it.
- **Billing starts at import.** Phase 1 creates usage records and raises account storage totals for over 100 TiB that CloudStack has never counted.
- **Shared Ceph pools.** A wrong delete can reach another cluster's guest. The identity rule and the explicit "no unreferenced cleanup" are the controls. The lab now has two clusters on one pool and a guest with the same VMID in each: deleting one left the other's images alone.
- **The lab's Ceph is one node.** It proves RBD behaviour (erasure-coded data pools, moves between pools) but nothing about speed, and both clusters list the same single monitor, so "one monitor in common" between different monitor lists is still covered by unit tests only.

## Decided (14 Nov 2026)

0. **Pools.** One Proxmox pool per storage, shared by all Proxmox clusters. KVM keeps its own pools on the same Ceph pools. Moving a guest to KVM later is then a rewrite of each volume's pool and path, with no copy of data.

1. **Usage and limits.** Imported disks produce usage records and count against account limits from the day they are imported.
2. **Disk offerings.** One custom-size offering per storage tier.
3. **Detached volumes.** Not needed now; possibly later. Deferred.
4. **Snapshots.** Whole-VM snapshots through CloudStack's VM snapshot feature are acceptable. Per-disk snapshots go through Ceph, run by the extension with its own Ceph client.
5. **Offering names.** Standard SSD (`ssd-ec`), Premium HDD (`hdd10k-ec`), Standard HDD (`hdd-ec`).
6. **Order.** Storage is built before the production install, so that production gets one deployment with everything in it.
7. **Placement (15 Nov 2026).** The planner stays stock. The same storages are mapped to every cluster of a zone, so any cluster can hold any tier.

## Open

- Whether the nine disks on storages with no tier get an offering.
- What each account's limits are raised to before the import.
- Per-disk Ceph snapshots: the Ceph client's capabilities, how CloudStack's snapshot calls reach the extension, and what happens when Proxmox moves or deletes a snapshotted disk.

## Expunge of an adopted instance (15 Nov 2026, lab only)

Decision: once adopted, an instance is treated as an ordinary one wherever possible. Expunging it in CloudStack deletes it at Proxmox: the guest, every disk in its configuration (including ones CloudStack does not record: EFI, TPM, disks on storage without a tier) and its snapshots. Before this an adopted guest was kept.

How:

- The delete runs under a `delete` lease on the claim, like every other change. When Proxmox confirms the guest is gone the claim becomes `retiring`, and `released` once the instance row is gone (next host poll).
- The extension deletes only when the VMID still holds the guest that was adopted (its name is the one in the claim), it is stopped, and it is neither locked nor a template. Otherwise it refuses and nothing is deleted. It then removes the HA resource and destroys the guest, the same two calls an ordinary instance gets, and never asks Proxmox to destroy unreferenced disks.
- A refusal or a failed Proxmox task releases the lease. An answer that does not arrive keeps it: the operator inspects Proxmox, releases the lease, and expunges again; a guest already gone needs no further call.
- Once a claim is `retiring` a repeated expunge sends nothing, because Proxmox may since have given the VMID to another guest.
- Stock order is unchanged: CloudStack removes the instance's NIC and volume records and counts before it asks the hypervisor to delete. So after a refused or failed delete the instance is left in Error or Expunging with those records already gone, as for an ordinary External instance. Expunge it again once the cause is fixed.

There is no longer a way to take an adopted guest out of CloudStack and keep it in Proxmox. If that is needed it has to be built (the stock name for it is "unmanage").

Lab results (jar `adel-a`, extension script `42765d86`; 785 unit tests, 81 extension tests, 9 getconfig tests):

| Case | Result |
|---|---|
| Running adopted guest, second cluster, VMID 104 (also an adopted guest of the first cluster), disks on both Ceph pools, a Proxmox snapshot and a CloudStack snapshot with memory: destroy with expunge | Guest, both images, the memory image and the snapshots gone; records and counts gone; claim `retiring`, `released` within ninety seconds; the first cluster's 104 ran on in the same process |
| The same VMID holding a guest of another name | Refused, nothing deleted, lease released. The guest had already been stopped and its volume and NIC records removed (stock order) |
| Delete runs, answer lost | Job fails as unknown; lease kept; a second expunge is refused and sends nothing; after the operator releases the lease the next expunge finds nothing to delete and completes |
| HA-managed adopted guest, first cluster | HA resource removed, guest destroyed; the other HA guest untouched |

