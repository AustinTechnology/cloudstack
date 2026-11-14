# CD/DVD ISOs on External (Proxmox) instances: plan

**Goal:** attach and detach CloudStack ISOs on Proxmox guests through the stock `attachIso` / `detachIso` calls and UI, with up to two CD/DVD drives per guest as CloudStack 4.23 does on KVM, and create a new guest straight from a CloudStack ISO, with a BIOS or UEFI choice, so the ISO-template workaround is no longer needed.

**Approach:** stock behaviour is kept wherever Proxmox allows it. CloudStack's records decide which ISOs a guest has (`user_vm.iso_id` is drive one, `vm_iso_map` drive two, `vm.iso.max.count` the cap). A change on a running guest is sent to the host at once; a change on a stopped guest is only recorded and takes effect at the next start through CloudStack. The External plugin turns those stock commands into extension actions. An ISO is copied once from CloudStack's secondary storage to the Proxmox ISO storage the first time it is used.

**Tech stack:** CloudStack 4.23.0.0 fork (`external-plugin-4.23`, Java 11 overlay build), the Proxmox shell extension (`proxmox_adoption_registry/proxmox.sh`, Bash and jq, Python unittest with a fake `curl`), Proxmox VE 9.2 API, Vue UI.

Status: plan only. Nothing here is built. The read-only checks behind the facts below were run on 14 Nov 2026; their outputs are in the operator notes.

## Decided (14 Nov 2026)

1. **Source.** Any Ready CloudStack ISO, tenants' own included, copied to the Proxmox ISO storage on first use.
2. **Stock operation.** Behave as stock does unless Proxmox makes that impossible for the task in hand. Improving on stock (last section) is separate work for upstream, not part of this.
3. **Drives.** As stock: at every start through CloudStack a guest is given CD drives up to the cap, existing guests included. A drive is only ever added while the guest is stopped. A running guest with no free drive (one not yet started through CloudStack since this was installed) is refused with "stop the instance".
4. **Existing guests.** Whatever is mounted or recorded today is put right once, by hand, when this is installed. The code does not reconcile it.
5. **Checksums.** A copy is always verified. An ISO without a checksum gets one generated and recorded before it can be used.
6. **Deploy from ISO.** A new guest can be created straight from a CloudStack ISO. The ISO-template route keeps working but is no longer needed.
7. **Firmware.** Chosen per guest with the stock Boot type and Boot mode fields, not by offering:
   - BIOS: SeaBIOS, as today's ISO template builds.
   - UEFI, Legacy: OVMF on q35 with an EFI disk, no Secure Boot keys.
   - UEFI, Secure: the same with Microsoft's keys enrolled, plus a TPM 2.0 (Windows 11, Server 2025).
   - The deploy form defaults to UEFI for External. Firmware is fixed at creation.
   - The EFI disk and TPM state go on the storage chosen for the root disk at creation.
8. **Lab.** A small KVM host and the system VMs are added, so real ISOs become Ready there. Addresses: any above 192.168.64.200 that does not answer ping. On 14 Nov only .240 answered in .201 to .254. The plan uses .201 for the KVM host, .202 to .205 for the system VMs' management range and .206 to .210 for their public range; each is pinged again before use.

### What keeping stock means

These are the stock behaviours on KVM and VMware, accepted here as they are:

- An ISO attached to a stopped guest reaches Proxmox only at the next start **through CloudStack**. A guest started from Proxmox does not get it.
- At every start through CloudStack the guest's CD drives are set to what CloudStack has recorded. **An ISO mounted directly in Proxmox is ejected at that start.**
- CloudStack never learns of an ISO mounted or ejected in Proxmox.

9. **Boot order.** Stock boots from the CD when drive one holds a bootable ISO. At a start through CloudStack, if drive one holds a bootable ISO, that drive is put first in the Proxmox boot order. The order is not touched otherwise. An empty CD drive left first is harmless: Proxmox moves on to the next device.
10. **Deleting an ISO.** Stock refuses to delete an ISO while CloudStack has it attached to any instance, and that is unchanged. When the delete goes ahead, the copy on the Proxmox storage is deleted too; a guest that had it mounted directly in Proxmox has it ejected first.
11. **A guest created from an ISO** has both drives from creation: the ISO in drive one, drive two empty.

## Context

### Stock 4.23 (read from the source at the release tag)

- `attachIso` refuses External outright (`TemplateManagerImpl` 1351). `detachIso` has no such check.
- The cap is `vm.iso.max.count` (cluster scope, default 1) clamped by the host detail `host.cdrom.max.count`. KVM hosts advertise 2; a host that advertises nothing counts as 1.
- Running guest: an `AttachCommand` or `DettachCommand` carrying the ISO goes to the host, and the record changes only if the host succeeds. Stopped guest: the record changes and nothing is sent.
- At every start `prepareIsoForVmProfile` puts every CD slot into the start profile, with its ISO or empty, and marks the guest to boot from CD when drive one's ISO is bootable.
- An ISO cannot be deleted while a record references it.
- `deployVirtualMachine` with an ISO as the image needs a `hypervisor` and a disk offering for the root disk, records the ISO as drive one, and allocates the instance "from scratch". The storage-pool check is skipped for External, so the call itself probably goes through. Not run.
- Firmware: `boottype` and `bootmode` on the deploy call, both in the deploy form's advanced settings. Stock places a UEFI instance only on a host that advertises `host.uefi.enable`.
- The UI hides both ISO actions for External (`isoActionAvailable` in `ui/src/config/section/compute.js`).

### The extension today

- An ISO reaches a guest only at creation: an External template with details `template_type=ISO`, `iso_path` (a Proxmox volume ID) and `iso_os_type` makes `create` build the guest with that ISO in `ide2`. The template also supplies `storage` and `disk_size_gb`.
- A real CloudStack ISO carries none of those details, and no extension. Today `create` would be asked to clone a template that is not named, and fail.

### Production (read-only)

- `vm.iso.max.count` is already 2. The 13 External hosts advertise no drive count, so they count as 1.
- 21 user ISOs, all public, owned by one account. 17 are Ready, and all 17 have a SHA-512 checksum. The 4 without a checksum never finished downloading and cannot be attached to anything.
- Both Proxmox clusters define `iso-library` on the same CephFS subvolume, shared by all nodes: 32 ISO files, 130 GiB, 48 TiB free. One copy serves both clusters.
- 166 guests: 53 have no CD drive, 109 have one, 4 have two. Slots in use: `ide2` 77, `sata0` 26, `ide0` 12, `scsi1` 2. No cloud-init drives. 31 drives hold an ISO, all from `iso-library`. 108 of the 113 guests with a drive have it in their boot order.
- 87 guests are q35, where only `ide0` and `ide2` exist. 102 have an EFI disk, 17 a TPM.
- Nodes of both clusters reach the secondary storage VM's public address on ports 80 and 443. `secstorage.encrypt.copy` is false, so download links are plain HTTP.
- The extension's Proxmox role can change CD media (`VM.Config.CDROM`, `Datastore.Audit`). It lacks `Datastore.AllocateTemplate` and `Sys.AccessNetwork`, which Proxmox requires for a download to a storage.
- 9 External instances already have an ISO recorded (8 running, 1 stopped). How is not known.

### Proxmox (read from the 9.2 source on the lab node, not yet exercised)

- Changing the medium of an existing CD drive on a running guest applies at once, on any bus.
- A new IDE or SATA drive on a running guest is left pending.
- A download to a storage takes a URL, a file name and a checksum (MD5 to SHA-512).

## Model

- **Drives.** A guest's CD drives are its slots with `media=cdrom`, cloud-init drives excluded, in a fixed order (IDE, SATA, SCSI, each by number). The first is CloudStack's drive one, the second drive two. Any further drive is left alone.
- **New drive.** `ide2`, then `ide0`, then the first free SATA slot.
- **Where an ISO is on Proxmox.** The ISO's own `iso_path` detail if it has one (the detail today's templates use, so a file already on `iso-library` can be used with no copy). Otherwise `<ISO storage>:iso/cloudstack-<ISO UUID>.iso`, copied on first use.
- **ISO storage.** One per Proxmox cluster, named by a new setting `iso_storage` on the extension's cluster mapping. Production: `iso-library` for both. Lab: `labnfs`.
- **Copy.** CloudStack asks its image store for a download link the stock way and passes the link and the ISO's checksum to the extension; the Proxmox node downloads and verifies it. One copy at a time per ISO, under a database lock, because two clusters share the storage. An ISO with no checksum is refused with a message saying so.
- **Running guest.** Attach loads the first empty drive; with none, it is refused. Detach empties the drive holding that ISO; if no drive holds it there is nothing to do and it succeeds.
- **Start.** The extension is given the slots as CloudStack records them, one per drive up to the cap. For each: the drive is added if the guest lacks it, then set to the recorded ISO or emptied.
- **Capability.** `instanceisosupported` on the extension, checked at the API and at dispatch, and carried on the instance response for the UI.
- **Cap.** External hosts advertise `host.cdrom.max.count=2`; `vm.iso.max.count` then decides, as on KVM.
- **Safety, as for disks.** A CD change excludes a refresh or disk change of the same instance. Adopted guests take an operation lease. The extension refuses a template, a locked guest and pending changes, sends the configuration digest with the write and checks the result afterwards.

## Steps

1. **Lab: KVM host and system VMs.**
   A small nested KVM guest on `dg-hv08` at .201, CloudStack agent 4.23, a KVM cluster in the lab zone with NFS primary storage, the KVM system VM template, and the two address ranges. Add `Datastore.AllocateTemplate` and `Sys.AccessNetwork` to the lab's Proxmox role.
   *Check:* secondary storage VM and console proxy Running; a registered ISO reaches Ready; a lab Proxmox node can fetch a CloudStack download link. Rerun the console test on an adopted guest while here.

2. **Extension: ISO copies.** `fetchiso` and `removeiso`, cluster-level actions like `liststorage`. `fetchiso` does nothing if the file is already there at the expected size; otherwise it starts the Proxmox download with the checksum, waits for the task and confirms the file is listed. `removeiso` ejects the copy from every guest in the cluster that has it mounted, then deletes it.
   *Files:* `proxmox_adoption_registry/proxmox.sh`, new `test_iso_actions.py` in the style of `test_disk_actions.py`.

3. **Extension: CD drives.** `attachiso` and `detachiso` for a running guest, and the two slots applied at `start`, as the model describes. Boot order per decision 9.
   *Files:* `proxmox.sh`, `test_iso_actions.py`, `test_native_lifecycle.py`.

4. **Plugin: stock commands to extension actions.**
   - The External host handles the stock `AttachCommand` and `DettachCommand` for an ISO, the way it already handles the NIC commands, and the start payload carries the two slots from the stock start profile.
   - Each ISO in a command or a start is first turned into its Proxmox volume ID, copying it if needed.
   - The two instance actions join the adoption lease list and the provisioner's action map; the capability joins `ExtensionHelper` and `ExtensionsManagerImpl`.
   - External hosts advertise `host.cdrom.max.count=2` and `host.uefi.enable`.

   *Files:* `ExternalPathPayloadProvisioner.java`, `ExternalResource.java`, a new class and test in the plugin's storage package, `AdoptionRegistry.java`, `ExtensionHelper.java`, `ExtensionsManager.java`, `ExtensionsManagerImpl.java`, `spring-external-core-context.xml`, `step7-files.txt`.
   *Check before coding:* what a stock start sends today for an External instance with an ISO recorded, and that host details set at startup reach `host_details`.

5. **Core hooks, kept small.**
   - `TemplateManagerImpl`: allow External when the capability is declared; on ISO delete, ask the plugin to remove copies (decision 10).
   - `UserVmResponse` and `UserVmJoinDaoImpl`: carry `instanceisosupported`.

6. **Deploy from ISO, with firmware.**
   The stock call: `deployVirtualMachine` with the ISO as the image, `hypervisor=External`, a compute offering, a tier's disk offering and size for the root disk, and optionally boot type and mode. At creation the plugin supplies what a template used to:
   - the storage, from the root disk offering's tier (the mapping storage phase 2 uses);
   - the root disk size;
   - the ISO's Proxmox volume ID;
   - the Proxmox OS type, from the ISO's guest OS;
   - the firmware (decision 7), with the EFI disk and TPM state on that same storage.

   The extension's ISO `create` branch builds the guest with both drives (decision 11). The root disk is recorded as one volume on the tier's pool when the guest is created, not at a later refresh.
   *Check before coding, each by running it in the lab:*
   - that stock accepts the call for External, and how far it gets;
   - how the host is chosen when the image carries no extension (the lab has four orchestrator extensions; the caller may have to name the cluster, or only clusters whose extension declares the capability are eligible);
   - whether stock allocates a root volume record on this path, so the guest ends with exactly one;
   - that a Secure Boot guest with enrolled keys and a TPM starts a Windows 11 installer.

   *Files:* `ExternalPathPayloadProvisioner.java`, the plugin's storage package, `proxmox.sh` (`create`), `UserVmManagerImpl.java` only if stock refuses something, tests for each.

7. **UI.** `isoActionAvailable` in `ui/src/config/section/compute.js` allows External when `instanceisosupported` is true. In `ui/src/views/compute/DeployVM.vue`, an ISO with hypervisor External offers the tier disk offerings for the root disk and defaults Boot type to UEFI. A source check in the style of `check-volume-ui.cjs`.

8. **Lab verification.** On the lab's two Proxmox nodes with a real, Ready ISO:
   - First use copies the ISO once and verifies it; a second guest using it copies nothing. An ISO with no checksum is refused.
   - Running guest with an empty drive: attach, medium visible at once, same QEMU process; detach. Running guest with no free drive: refused.
   - Stopped guest: attach is recorded and Proxmox is unchanged; the start through CloudStack loads the ISO. Detach while stopped, then start: the drive is empty.
   - A guest with no CD drive, and one with a single drive, each have two after a start through CloudStack and none added by a reboot.
   - Second ISO goes to drive two; a third is refused by the cap.
   - An ISO mounted directly in Proxmox is ejected at the next start through CloudStack, and is untouched by a reboot.
   - q35 guest, and a guest whose `ide2` is a disk: the drive lands in a valid slot.
   - Bootable ISO in drive one: the guest boots it after a start; the boot order is otherwise as it was.
   - Adopted guest: lease taken and released; a locked guest refused before any write.
   - Detach while the guest holds the tray locked, with and without `forced`.
   - As an ordinary user with a private ISO: own guest allowed, another account's ISO refused.
   - Delete the ISO in CloudStack: refused while attached; once detached the copy is removed; a guest holding it mounted directly in Proxmox has it ejected first.
   - Deploy from an ISO as BIOS, UEFI Legacy and UEFI Secure: storage, both drives, machine type, EFI disk, keys and TPM as decided; each boots its installer; one root volume record with the right size, offering and counts; a failed deploy leaves nothing behind; expunge removes the guest, its disk, EFI disk and TPM state.
   - Deploy as an ordinary user from a private ISO; over the account's limit refused.
   - Today's create-from-ISO template still works.

9. **Records.** Add the built contract and test record to this file, the decisions to `EXTERNAL-PLUGIN-PLAN.md`, and lessons to the skill reference.

## Before production

Not part of building this; listed so the install has them.

- Add `Datastore.AllocateTemplate` (on `iso-library`) and `Sys.AccessNetwork` (on the nodes) to the extension's role on both clusters.
- Set `iso_storage=iso-library` on both cluster mappings and declare `instanceisosupported`.
- **One-off, by hand (decision 4).** From a read-only report of every guest's recorded and mounted ISOs:
  - the 9 instances with an ISO already recorded: detach each through CloudStack, which clears the record and changes nothing in Proxmox, unless the ISO really should be there;
  - the 31 ISOs mounted in Proxmox: each is ejected at its guest's next start through CloudStack. Decide per guest whether that is fine or whether the ISO should be attached through CloudStack first.
- Optionally set `iso_path` on CloudStack ISOs that already have a file on `iso-library`, to avoid a second copy of each.

## Risks

- **The copy depends on the secondary storage VM.** If it is down, attaching or starting with an ISO that has not been copied yet fails cleanly. ISOs already copied are unaffected.
- **Download links are plain HTTP** to a public address, with an unguessable path valid for 4 hours. The checksum is always verified.
- **A start through CloudStack ejects media CloudStack does not know about.** That is stock, and the one-off above covers what is mounted today, but it will surprise anyone who mounts an ISO in Proxmox later.
- **New drives change guest hardware.** Every existing guest gains drives up to the cap at its first start through CloudStack. Windows will see new devices.
- **Tray locked by the guest.** How Proxmox behaves on eject is not known yet; step 8 tests it.
- **Tenant ISOs land on a storage every Proxmox admin and both clusters can read.** File names are CloudStack UUIDs; CloudStack's own access check still decides who can attach what.
- **Deploy from ISO rests on stock behaviour that has only been read.** Step 6 runs it first; if stock refuses External at a point that cannot be lifted cleanly, that step is redesigned before anything is built on it.
- **The EFI disk and TPM state do not follow a later move of the root disk.** Storage phase 2 moves one disk at a time.
- **Not provable in the lab:** CephFS as the ISO storage, two clusters sharing it, and TPM state on Ceph. The lab has one cluster and NFS.

## For upstream later, not this work

- Writing a stopped guest's ISO change to the orchestrator at once.
- Not rebuilding CD drives at every start on orchestrators that keep a guest's configuration.
- Recording media changed at the orchestrator.
