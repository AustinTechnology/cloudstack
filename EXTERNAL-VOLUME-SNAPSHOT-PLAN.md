# External volume snapshots on Ceph (phase 4): findings and plan

Status: investigated 15 Nov 2026 in the lab and, read-only, in production. Nothing is built. Three decisions are needed before building; they are at the end.

## What it gives

CloudStack's own volume snapshots (API and UI: take, list, delete, revert, and snapshot schedules) for a disk of an External instance that is an RBD image. The snapshot is a Ceph snapshot of that one image. It stays on Ceph; nothing is copied to secondary storage.

Disks that are not on Ceph (NFS, node-local) get no volume snapshots.

## Findings

Lab: Ceph 20.2.5, two Proxmox clusters on the same pools, a throwaway guest (`rbd-snap-probe.txt`, `rbd-snap-probe2.txt`). Production: read-only.

| Question | Finding |
|---|---|
| Can a separate Ceph client snapshot a disk of a running guest | Yes. 0.2 s on the EC tier, 1.1 s on the replicated tier. The guest is not paused; the snapshot is crash-consistent |
| Does Proxmox see such a snapshot | No. `qm listsnapshot` does not list it. Proxmox's own snapshot and snapshot delete leave it alone |
| Roll back with the guest stopped | Works: the image is byte-identical to the snapshot afterwards and the guest boots. A stopped guest's image has no watcher (`rbd status`), a running one has; that is a second check before a rollback |
| Proxmox grows a disk that has a snapshot | Allowed by Proxmox and Ceph. A later rollback shrinks the image back while Proxmox's configuration keeps the larger size |
| Proxmox moves a disk that has a snapshot | The copy has no snapshots. The source image is deleted with its snapshots, protected or not |
| Proxmox deletes a disk, or destroys the guest | The image is deleted with its snapshots, protected or not |
| Space used by a snapshot | `rbd du` answers in 0.1 s (the images have fast-diff) |
| What the Ceph client can be limited to | Its pools, not less. There is no snapshot-only capability: a client that can snapshot and roll back can also write and delete every image in those pools. In production those pools also hold the KVM volumes |
| Does stock CloudStack already have this path | Yes, for KVM on Ceph with `snapshot.backup.to.secondary=false`: `DefaultSnapshotStrategy` (take, delete) and `CephSnapshotStrategy` (revert) call the storage driver's `takeSnapshot`, `deleteAsync` and `revertSnapshot`. External pools are recorded as RBD pools and their volumes as RAW, so by their code both strategies select them (read, not run) |
| Production: can the management server reach Ceph | Yes. The management server (Ubuntu 24.04) has an address on the Ceph public network and reaches the monitors on 3300 and 6789. It has no `rbd` command; its package is `ceph-common` 19.2.3 |
| Production: will that `rbd` authenticate | Yes. Production Ceph (20.2.4) accepts both key types and prefers the classic one. The lab's Ceph accepts only the new type (`aes256k`), which the `rbd` of Ubuntu 26.04 (20.2.0) cannot read, so the lab management server cannot authenticate with its packaged `rbd`; Proxmox's 19.2.6 can |

## How it would be wired

1. **No new strategy.** The stock strategies stay as they are. The External storage driver implements the three calls, each one extension action: `snapshotdisk`, `deletedisksnapshot`, `revertdisksnapshot`.
2. **The extension runs `rbd`** (`snap create`, `snap rm`, `snap rollback`) on the management server as its own Ceph client. The pool, data pool and monitors are the ones the pool registration already recorded. New extension details name the client and its keyring file; the key itself is never in CloudStack.
3. **Core changes, small:**
   - a snapshot of a volume on an External pool is never backed up to secondary storage, whatever `snapshot.backup.to.secondary` says (allocation, take, and where the snapshot's store is looked up);
   - the two stock refusals "Snapshot operations are not allowed for External hypervisor type" become a check of a new capability, `instancevolumesnapshotsupported`, and of the pool being RBD;
   - expunge of an External instance removes the snapshot records of its disks (see decision 1).
4. **The snapshot's name on Ceph** is `cloudstack-<snapshot UUID>`. It cannot be a CloudStack instance snapshot's name, and Proxmox would only produce it if someone typed it.
5. **Adopted instances** run the three actions under a lease, with the same known/unknown rule as every other change. All three take the per-instance lock that disk changes and refresh take.

## Rules

- **Take:** instance running or stopped. Crash-consistent when running, as stock KVM on Ceph.
- **Revert:** stock rules (instance stopped, no instance snapshots). The extension also requires the guest stopped in Proxmox and no watcher on the image. A rollback rewrites the whole image, so its time grows with the disk; it was not measured on a large disk. Past the extension's limit (6900 s) the outcome is unknown and the instance stays fenced until checked.
- **A disk with snapshots is frozen:** no grow, move, tier change or delete until its snapshots are deleted. This is the rule already agreed for instance snapshots, for the reasons in the findings.
- **Delete:** a snapshot already gone on Ceph can still be deleted in CloudStack.
- **Counts:** as stock on Ceph (read from the code). Each snapshot counts once against the account's snapshot limit and, at the disk's full size, against its primary storage limit. A 500 GiB disk with seven snapshots counts 4 TiB.
- **Changes made in Proxmox** (a disk moved or deleted there) destroy the snapshots without CloudStack knowing. The records remain; revert then fails and delete succeeds.

## Not included

Volume from a snapshot, template from a snapshot, download, copy to another zone or pool, archive to secondary storage, freezing the guest's filesystems for the snapshot. Volume from a snapshot belongs with detach and re-attach, which is deferred.

## Production prerequisites

- `ceph-common` on the management server.
- A Ceph client for the extension with `profile rbd` on the six pools of the three tiers, classic key type, keyring readable only by `cloud`.
- The three extension details and the capability.

## Lab prerequisite

An `rbd` on the lab management server that can read the lab's key type: a newer `ceph-common`, or the lab Ceph changed to accept the classic type as production does.

## Decisions needed

1. **Expunging an instance deletes the snapshots of its disks.** They are part of the images Proxmox destroys, so they cannot outlive the instance (stock volume snapshots, kept on secondary storage, do). Proposed: expunge removes their records and counts with the instance. The alternative is to refuse expunge until they are deleted.
2. **Snapshot schedules.** Proposed: allowed. They use the same path, and stock retention deletes the oldest.
3. **The Ceph key on the management server can write and delete every image in the tier pools**, KVM volumes included. This follows from running `rbd` from the extension, which is already decided; it is listed so that it is accepted knowingly.

## Build steps

1. Extension: three actions and their tests (fake `rbd`), `rbd` invocation without the key on the command line or in output.
2. Driver, capability, core changes, unit tests.
3. UI: snapshot actions for volumes of External instances with the capability; hide what is not included.
4. Lab: both tiers, both clusters, running and stopped, adopted and ordinary, duplicate VMIDs, schedule with retention, frozen-disk refusals, expunge, lost answer on a revert, counts.
