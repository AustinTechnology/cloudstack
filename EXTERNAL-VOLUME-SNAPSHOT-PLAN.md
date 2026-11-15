# External volume snapshots on Ceph (phase 4)

Status: built and tested in the lab, 15 Nov 2026. Not in production. Lab build `vsnap-c`.

## What it gives

CloudStack's own volume snapshots for a disk of an External instance that is an RBD image: take, list, delete, revert, download, and snapshot schedules with retention, through the stock API and UI. Disks that are not on Ceph (NFS, node-local) get no volume snapshots, and say so.

It behaves as stock KVM on Ceph does:

- The snapshot is a Ceph snapshot of that one image, taken with the guest running or stopped (crash-consistent when running).
- With `snapshot.backup.to.secondary=true` (production's setting) it is then copied to secondary storage as a qcow2 file, in the stock place (`snapshots/<account>/<volume>/<snapshot uuid>`), and also stays on Ceph. With `false` it stays on Ceph only.
- Revert uses the copy on Ceph. Delete removes both copies.
- When the disk or the instance is deleted, the copy on Ceph goes with the image. A copy on secondary storage remains as a snapshot that can be downloaded and deleted, not reverted to.

## How it is wired

No new snapshot strategy. The stock strategies (`DefaultSnapshotStrategy` for take, delete and backup; `CephSnapshotStrategy` for revert) select External pools because they are recorded as RBD pools with RAW volumes. What is new:

| Piece | What it does |
|---|---|
| `ExternalPrimaryDataStoreDriver` | `takeSnapshot`, `revertSnapshot`, and `deleteAsync` of a snapshot, each one extension action |
| `ExternalSnapshotBackup` (a `DataMotionStrategy`) | the copy to secondary storage. A KVM host does this for KVM; here the management server mounts the NFS secondary storage (`NfsMountManager`, as stock does for VMware) and the extension writes the file with `qemu-img convert` |
| `ExternalVolumeSnapshots` | parameters, the per-instance lock, which host and cluster to ask, the rule that holds a disk |
| Extension actions | `snapshotdisk`, `revertdisksnapshot` (on an instance; under a lease when adopted), `deletedisksnapshot`, `backupdisksnapshot` (on the storage; run on the management server that was asked, not through a host's agent) |
| Capability | `instancevolumesnapshotsupported`, an extension detail. Volumes report it; the UI offers Take Snapshot and Recurring Snapshots only with it |
| Extension details | `ceph_client`, `ceph_keyring` (a file readable by `cloud`; the key is never in CloudStack), and optionally `rbd_command`, `qemu_img_command` |

The snapshot's name on Ceph is `cloudstack-<snapshot UUID>`. The pool, namespace and monitors come from the Proxmox storage definition. CloudStack records the snapshot as `<Proxmox volume>@<name>`, so it is always deleted from the image it was taken on.

Stock code changed: the three refusals "Snapshot operations are not allowed for External hypervisor type" now ask whether the volume can be snapshotted; a volume or a Template from such a snapshot is refused (see Not included); the volume and snapshot responses each gained one field for the UI.

## Rules

- **Take:** refuses a disk whose slot no longer holds the recorded volume. Adopted instances: under a lease.
- **Revert:** stock rules (instance stopped, no Instance Snapshots). The extension also requires the guest stopped in Proxmox, no Proxmox snapshots, nothing holding the image open, and the image the size it was when snapshotted. Ceph rewrites the image object by object, so the time grows with the disk; the action is given 6900 s, and past that the outcome is unknown and an adopted instance stays fenced until checked.
- **A disk with a snapshot on Ceph is not grown or moved** (including a tier change). A grown disk could not be reverted; a move leaves the snapshots with the old image, which Proxmox deletes. Delete the snapshots first.
- **Deleting the disk or the instance is allowed**, as stock.
- **Schedules and retention:** stock.
- **Counts:** stock. One snapshot each. With the copy to secondary storage, the size of the qcow2 file counts against secondary storage. Without it, the disk's full size counts against primary storage.
- **Changes made in Proxmox** (a disk moved or deleted there) destroy the copies on Ceph without CloudStack knowing. Revert then fails; delete succeeds.

## Tested in the lab

Ceph 20.2.5, two Proxmox clusters on the same pools, EC and replicated tiers, NFS secondary storage. Results in the operator notes.

| Case | Result |
|---|---|
| Take on a running ordinary guest and a running adopted guest | Ceph snapshot and qcow2 copy made; QEMU process unchanged; no lease left |
| The copy | raw contents identical (SHA-256) to the Ceph snapshot; a download URL is issued by the stock secondary storage VM |
| Revert while running | refused by stock |
| Revert after the disk was changed from outside, guest stopped | image identical to the snapshot again; guest starts |
| Guest started in Proxmox while CloudStack records it stopped, then revert | refused before any write; no lease left |
| Keyring that cannot be read | refused before any write; snapshot record in Error, deletable |
| Same VMID in both clusters, same pool | the snapshot is on the right image only |
| Grow, move with a snapshot | refused |
| Volume or Template from a snapshot | refused |
| Delete | both copies gone, counts back |
| Hourly schedule, keep 1 | fired twice; the second removed the first from Ceph and secondary storage |
| `asyncbackup=true` | works |
| Data disk deleted with a snapshot on it | image and Ceph snapshot gone; the secondary copy remains, can be deleted |
| Adopted instance expunged with a snapshot on its root disk | guest, images and Ceph snapshots gone; first cluster's guest with the same VMID untouched; secondary copies remain, deleted afterwards, counts back |
| `snapshot.backup.to.secondary=false` | Ceph only; take, delete; a disk deleted with such a snapshot takes the record and its counts with it |

796 Java tests, 90 extension tests, 9 getconfig tests, 35 volume UI checks.

Not tested: a lost answer during take or rollback on a live system (offline tests only); disks of real size (time of a rollback and of a copy); Windows; more than one management server; the UI in a browser; archive, and copy to another zone.

## Stock behaviour seen, left as it is

- `asyncbackup=true` leaves the account's secondary storage count high by about the disk's size.
- With `snapshot.backup.to.secondary=false`, deleting a snapshot does not return its primary storage count.
- A failed take reports only "an internal error"; the reason is in the management log.
- `snapshot.backup.to.secondary` takes effect after a management server restart.

## Not included

Volume from a snapshot and Template from a snapshot (the first belongs with detach and re-attach, which is deferred), freezing the guest's filesystems for the snapshot, secondary storage that is not NFS.

## Production prerequisites

- On the management server: `rbd` and `qemu-img` with RBD support, matching the cluster (Ceph 20.2.5: `ceph-common` from Ceph's noble repository, `qemu-utils` and `qemu-block-extra`). The lab runs exactly these from a noble tree, because its own Ubuntu release has no 20.2.5 packages.
- A Ceph client for the extension with `profile rbd` on the pools of the three tiers; keyring readable only by `cloud`. This client can write and delete every image in those pools, KVM volumes included (accepted).
- The NFS secondary storage exported to the management server.
- The copy runs through the management server: its CPU and network carry every snapshot backup.
- The capability and the Ceph details on the extension.
