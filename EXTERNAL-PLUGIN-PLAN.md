# External hypervisor plugin: extension plan

**Goal:** make CloudStack's External hypervisor plugin handle the operations AustinCloud's Proxmox clusters need, so CloudStack stops drifting from Proxmox and stops needing database repairs.

**Approach:** small, separately shippable changes on a branch cut from the release we run, each one also raised upstream so the carried patch set shrinks over time.

**Tech stack:** Java (CloudStack 4.23.0.0, Maven), bash extension script (`AustinTechnology/cloudstack-proxmox-extension`).

- Fork: `AustinTechnology/cloudstack` (fork of `apache/cloudstack`)
- Working branch: `external-plugin-4.23`, cut from tag `4.23.0.0`
- Upstream PRs target `apache/cloudstack` `main`

## What the code does today (read from tag 4.23.0.0)

Paths below are relative to the repository root. Line numbers are from the tag.

**Commands the plugin handles.** `plugins/hypervisors/external/.../resource/ExternalResource.java`, `executeRequest` (line 143) has explicit handlers for: routing update, ping, ready, health check, network check, network rule cleanup, VM stats, host stats, maintain, start, stop, reboot, console, prepare-provisioning and custom actions.

**Everything else is passed to the script with no context.** The fallback `execute(Command)` (line 301) wraps the command as `new RunCustomActionCommand(cmd.toString())`. That carries no VM and no connection details, so the script receives a payload containing only the Java class name. This is the source of the recurring `Missing required fields: url user token secret node` log lines.

**Two commands hit that fallback in normal running:**

| Command | Sent from | Carries | Effect today |
|---|---|---|---|
| `CheckVirtualMachineCommand` | `VirtualMachineManagerImpl.checkVmOnHost` (line 2770), used by HA | instance name only | CloudStack HA can never confirm a VM's state, so a powered-off VM stayed Running; CloudStack HA was switched off for External VMs |
| `GetVmIpAddressCommand` | `UserVmManagerImpl` `VmIpAddrFetchThread` (line 819) | instance name, network CIDR, MAC | CloudStack never learns guest IPs on networks without CloudStack DHCP |

**Operations refused before they reach the plugin.** These are hard-coded checks in the management server, not in the plugin:

| Operation | Where it is refused |
|---|---|
| Scale VM (change CPU/RAM) | `UserVmManagerImpl.upgradeVirtualMachine` (2117) |
| Attach / detach volume | `VolumeApiServiceImpl.attachVolumeToVM` (3019), `sendAttachVolumeCommand` (5077) |
| Migrate volume | `VolumeApiServiceImpl.migrateVolume` (3752) |
| Volume snapshot | `VolumeApiServiceImpl.takeSnapshotInternal` (4238) |
| VM snapshot | `VMSnapshotManagerImpl` (338, 543) |
| Reinstall / restore VM | `UserVmManagerImpl.restoreVM` (9107, 9144) |
| Reset user data, reset SSH key | `UserVmManagerImpl` (1005, 1064) |

**Other gaps.**
- `ExternalHypervisorGuru.trackVmHostChange()` returns `false`.
- Storage is skipped entirely for External VMs (`VirtualMachineManagerImpl` 1523, 2373). In production, External VMs have no rows in `cloud.volumes`, so CloudStack has no record of their disks.
- No handlers exist for migration, NIC plug/unplug, or VM disk/network statistics.

## HA model (decided 11 Nov 2026)

**The external orchestrator owns HA. CloudStack never restarts or fences an External VM. An offering's HA flag is a request, passed through the plugin, to turn HA on for that VM in the external orchestrator.** This is meant as the standard for every External integration, not only Proxmox.

It follows what CloudStack already does for VMware, where vSphere HA does the restarting (tag 4.23.0.0):

| Where | What it does for VMware |
|---|---|
| `HighAvailabilityManagerImpl.scheduleRestartForVmsOnHost` (327) | Returns without restarting VMs when a VMware or Hyper-V host goes down |
| `HighAvailabilityManagerImpl.scheduleRestart` (496, 541) | "Skip HA for VMware VM" |
| `VirtualMachineManagerImpl.handlePowerOffReportWithNoPendingJobsOnVM` (5533) | An unexpected power-off never takes the HA branch for VMware or Hyper-V |
| `VmwareInvestigator` | `isVmAlive` always answers true; host status is only ever Disconnected |
| `VmwareFencer` | `fenceOff` returns null, so CloudStack never fences |

CloudStack then follows the power and host reports. One difference: for VMware, CloudStack does not pass the offering's HA flag to vSphere (only virtual routers get a restart priority, `VmwareResource` 2579). Passing the flag through is new behaviour for External.

Current production state matches the split already: CloudStack HA is off on all External VMs, and Proxmox HA manages 87 VMs on `p3-cluster03` and 29 on `p2-cluster01`.

## Steps

Ordered so that each step is useful on its own and the early ones change External plugin classes only.

### Step 1: establish build and deploy (done in the lab, 12 Nov 2026)

**There is no separate plugin jar.** The 4.23 packages ship one server jar, `/usr/share/cloudstack-management/lib/cloudstack-4.23.0.0.jar`, which contains the External plugin classes together with the rest of the management server. The lab and production jars are byte-identical. The 4.23.0.0 artifacts are not on Maven Central, so a single-module Maven build has nothing to resolve against.

**Build method.** Compile the plugin's sources against the shipped jar, then write a copy of the shipped jar with only the plugin's entries replaced. Every other entry keeps its original bytes and order.

- Use JDK 11 to compile; the shipped jar was built with it (`Build-Jdk-Spec: 11`). The management server keeps running on Java 17.
- Unmodified source rebuilt this way: 14 of 17 plugin entries are byte-identical to the shipped ones. The other 3 classes differ only in how the newer JDK 11 compiler emits string concatenation; no other entry in the jar changes.
- The rebuilt unmodified jar runs in the lab in place of the shipped one: both hosts Up, VM stop and start work.

**Deploy and rollback.** Stop `cloudstack-management`, replace the jar, start it. Keep the shipped jar outside `lib/`, because everything in `lib/` is on the classpath. The deploy script refuses to run unless the live jar has the expected checksum, and has a rollback mode that restores the shipped jar.

**Lab.** CloudStack 4.23.0.0 management server plus a two-node nested Proxmox VE 9 cluster with shared storage, registered as one External cluster using the AustinTechnology extension. A VM deployed through the extension starts, stops and live-migrates.

**Lab limits.** No KVM hosts, so no system VMs: console access cannot be tested there. No adoption registry: adopted-VM paths and RefreshDetails cannot be tested there.

**Carried forward.** Every step replaces the same server jar and needs the same restart; the difference between steps is how many classes change, not which file is deployed.

### Step 2: follow VMs that the orchestrator moves (plugin classes only)

Everything in the HA model depends on this: when Proxmox HA or an operator moves a VM, CloudStack has to record the new host by itself.

**Result, 12 Nov 2026: no Java change is needed.** Tested in the lab on unmodified 4.23.0.0 with the AustinTechnology extension:

| Case | Result |
|---|---|
| Running VM live-migrated in Proxmox | CloudStack showed the new host within 2 to 4 minutes |
| Stopped VM moved in Proxmox, then started from CloudStack | Started on the node it was really on; CloudStack showed that host about 90 seconds later |
| Node killed, Proxmox HA restarted the VM on the other node | Proxmox recovered the VM in about 2 minutes; CloudStack showed the new host on its next power report and started nothing itself |

- Why it works: each host's power report lists the VMs on that node, `VMInstanceDaoImpl.updatePowerState` records which host reported the VM, and `handlePowerOnReportWithNoPendingJobsOnVM` moves a Running VM to that host ("Detected out of band VM migration"). Nothing in 4.23.0.0 calls `trackVmHostChange()`, so its return value changes nothing.
- This depends on the extension's `statuses` action listing VMs per node, and on its live-node lookup for start, stop and console.
- During the node loss CloudStack kept showing the dead host as Up, because an External host has no agent of its own to lose. Step 4 has to account for that.
- The lab cluster was given a third quorum vote (a qdevice on the management VM) for the HA test.

Validation: migrate a test VM between Proxmox nodes, and separately let Proxmox HA recover one from a fenced node, and confirm CloudStack shows the new host and Running with no manual step.

### Step 3: answer the two commands that hit the fallback (plugin classes only)

Files: `ExternalResource.java`, `ExternalPathPayloadProvisioner.java`, their existing tests under `plugins/hypervisors/external/src/test/`, and the Proxmox script.

- Add explicit handlers for `CheckVirtualMachineCommand` and `GetVmIpAddressCommand`.
- Neither command carries connection details. Resolve the VM by instance name and build the access details inside the provisioner, the same way `getHostVmStateReport` (line 458) already does with `hostDao` and `extensionsManager.getExternalAccessDetails`.
- `CheckVirtualMachineCommand`: reuse the script's existing `status` action and return a `CheckVirtualMachineAnswer` with the power state.
- `GetVmIpAddressCommand`: add a script action that returns the guest-agent IPv4 address for the given MAC, ignoring loopback and link-local addresses. Return success with no address when the agent has none, and failure when the agent is unreachable, so CloudStack does not blank a known IP because an agent is down.
- Change the fallback to return "unsupported" without invoking the script. A payload with no VM in it cannot be answered correctly.

**Status, 12 Nov 2026: built and running in the lab; not reviewed, not in production.**

- The change also adds two methods to the `ExternalProvisioner` interface in `engine/components-api`, so it is not plugin classes only. That class is in the same server jar.
- The script action is `getipaddress`. It receives the NIC's MAC and the network CIDR as parameters and prefers an address inside that CIDR.
- Unit tests: 114 pass (103 before the change).
- Lab: CloudStack filled in the NIC address (10.100.0.5) from the guest agent on its next poll, with no sync tool in the lab. With no guest agent the action fails and the stored address is left alone.
- Since the new jar was started there are no `Missing required fields` lines and no unsupported-command lines in the lab log.
- Not exercised live: `CheckVirtualMachineCommand`. CloudStack only sends it during HA investigation and migration checks, which step 4 covers.

Validation:
- Unit tests for both handlers and the fallback.
- On a running External VM with a guest agent: CloudStack populates the NIC IP without the sync tool.
- The `Missing required fields` log lines stop.

### Step 4: HA handed to the orchestrator (first management-server change; done in the lab, 12 Nov 2026)

Files: `HighAvailabilityManagerImpl.java` (327, 496, 541), `VirtualMachineManagerImpl.java` (5533), a new investigator and fencer in the External plugin, `ExternalResource.java`, the provisioner, the script.

- **CloudStack stands down.** Add External to the same skips VMware has, and add an External investigator and fencer that behave like the VMware ones. With this in place it is safe for `ha_enabled` to be true on an External VM.
- **The flag becomes a request.** When an External VM is deployed or started, and when its HA setting changes (offering change, or `haenable` on update), the plugin calls a new script action `set-ha` with on or off. Where the HA setting change is hooked in the management server was not traced for this plan.
- **Proxmox script.** `set-ha` on adds the VM as a Proxmox HA resource in state `started`; off removes it. A stop requested through CloudStack must set the HA resource to `stopped` first, or Proxmox HA will start the VM again.
- **Report back.** The status action returns whether the orchestrator has HA on for the VM, so CloudStack can show a mismatch and never assumes.
- **Permissions.** The `CloudStackExtension` Proxmox role has no HA rights today. The privilege needed to manage HA resources has to be confirmed and added to the role.
- **Existing VMs.** Set each VM's CloudStack HA flag from what Proxmox HA has today, so turning this on changes nothing in Proxmox. The "(no HA)" offerings created on 11 Nov then mean what they say.

**Status, 12 Nov 2026: built and running in the lab; not reviewed, not in production.**

What was built:
- External added to the three existing skips: `HighAvailabilityManagerImpl.scheduleRestartForVmsOnHost` and `scheduleRestart`, and the power-off branch in `VirtualMachineManagerImpl`. The VMware-only skip further down `scheduleRestart` is unreachable for External because the earlier one returns first.
- `ExternalInvestigator` and `ExternalFencer` in the plugin, copies of the VMware ones, registered in `spring-external-compute-context.xml`.
- `ExtensionsManager.setInstanceHa(vm, enabled)` sends the script action `setha` with the parameter `enabled`. `UserVmManagerImpl.updateVirtualMachine` calls it when `haenable` changes on an External VM and saves the flag only if the script succeeds.
- Deploy and start need no Java: the payload already carries `enableHA`, and the script applies it on `create` and `start`.
- Script: `setha` adds or removes the Proxmox HA resource and keeps the VM's present power state. `delete` removes the HA resource first, because Proxmox refuses to delete an HA-managed VM. `start` and `stop` wait until the VM has really changed state, because Proxmox answers at once for an HA-managed VM. `status` also returns `ha_enabled`.
- Proxmox needs `Sys.Console` on `/` to add, change or remove HA resources. It was added to the lab role only.

Lab results (two-node cluster plus qdevice, HA offering `lab-tiny (HA)`, VM `lab-ha01`):

| Test | Result |
|---|---|
| Deploy from an HA offering | VM is in Proxmox HA, state started |
| Stop from CloudStack | Stays stopped (checked after 150 s); Proxmox HA state stopped |
| Start from CloudStack | Running; Proxmox HA state started |
| `haenable` off, then on, VM running | Leaves and rejoins Proxmox HA; VM keeps running |
| `haenable` on for a VM from a no-HA offering | Refused by CloudStack, as before |
| Guest process killed | Proxmox restarted it in 12 s; CloudStack scheduled nothing |
| Node killed | Proxmox restarted the VM on the other node in about 3 min; CloudStack followed on the next power report and scheduled nothing |
| HA resource set to stopped in Proxmox | CloudStack showed Stopped about 2 min later and scheduled no restart |
| Expunge an HA VM | VM and HA resource both gone |

- "Scheduled nothing" means no rows in `op_ha_work` and no HA lines in the management log.
- Unit tests: 570 pass (548 on unmodified source for the same files).
- Found and fixed in the lab: turning HA off and on again quickly left the Proxmox service in an error state, because Proxmox removes a resource in the background. The script now waits for the removal to finish.

Not done or not tested:
- **Offering change.** `upgradeVmDb` sets the flag from the new offering, but External VMs cannot change offering until step 5. The flag is applied on the next start.
- **Report back.** The script returns `ha_enabled`, but CloudStack does not read it yet.
- **Shutdown inside the guest** of an HA VM was not tested.
- **Adopted VMs.** The lab has no adoption registry, so `setha` on an adopted VM is untested.
- **Other restart paths.** Host maintenance, host degraded and forced host delete also call `scheduleRestart`, which now returns early for External. They were not exercised.

Before production:
- **Order matters.** The new script applies CloudStack's flag on every start. Production has about 116 VMs in Proxmox HA with the CloudStack flag off, so the first start of each from CloudStack would try to remove it from Proxmox HA. Set each VM's flag from Proxmox first (the "Existing VMs" item above).
- **Role.** Add `Sys.Console` to the production `CloudStackExtension` role first. Without it a start that needs to change HA fails. Review what else that privilege allows before granting it.
- **Jar first, flag second.** With the old jar, an External VM with the flag on makes CloudStack compete with Proxmox HA.

### Step 5: scale VM

Files: `UserVmManagerImpl.java` (2117), `ExternalResource.java`, the provisioner, the script.

- Allow scale for External VMs and add a handler for `ScaleVmCommand` that calls a new script action to set cores and memory in Proxmox.
- This changes core management-server classes as well as the plugin classes, like step 4.

Validation: change a custom-offering VM's CPU/RAM in CloudStack and confirm Proxmox matches; confirm a fixed-offering change of offering does the same.

### Step 6: NIC add and remove

- Add handlers for `PlugNicCommand` and `UnPlugNicCommand`, with script actions that add or remove a Proxmox network device on the right bridge and VLAN.
- Check whether the management server refuses these for External VMs before they reach the plugin. This was not traced for this plan.

Validation: add and remove a NIC from CloudStack on a test VM and confirm the Proxmox device, MAC and VLAN match.

### Step 7: let CloudStack pull a VM's real configuration

- Add a plugin action that asks the script for a VM's current CPU, RAM, NICs and IPs and updates CloudStack through its own services.
- This replaces the sync tool's direct database writes behind the "RefreshDetails" action and the periodic reconcile.

Validation: change CPU/RAM and an IP in Proxmox, run the action, confirm CloudStack matches and the sync tool made no write.

### Step 8: disks (needs a design decision first)

CloudStack has no volume records for External VMs. Volume attach, detach, resize, migrate and snapshot all depend on that model, so lifting the refusals alone would not work.

Decide first whether External VM disks should become CloudStack volumes at all. Until then, Proxmox snapshots stay available through the existing custom actions and disk changes are made in Proxmox.

### Step 9: upstream

- Raise step 3 as the first PR to `apache/cloudstack`; it is self-contained and fixes behaviour any External user hits.
- Raise step 4 with the HA model above as a design proposal on the dev list first, since it changes behaviour for every External integration.
- Raise later steps as they prove out in production.
- Rebase `external-plugin-4.23` onto each release we upgrade to, dropping patches that have merged.

## Risks

- **Carried patches.** Every step not accepted upstream must be re-applied on each CloudStack upgrade. Steps 2 and 3 change only External plugin classes; steps 4 onward also change core management-server classes and cost more to carry.
- **A package upgrade replaces the jar.** Any `cloudstack-management` package upgrade or reinstall overwrites the patched jar with the shipped one.
- **Restart per deployment.** Each jar change needs a management server restart.
- **HA flag before step 4.** Until step 4 is deployed, turning CloudStack HA on for an External VM brings back the stuck-Running fault. Keep it off until then.
- **Two HA managers.** Step 4 is safe only if every CloudStack restart path is skipped for External. The fence-a-node test in step 4 is the check.
- **Step 6 and the sync tool overlap.** Run one writer at a time for any given field.

## Open questions

1. Should External VM disks be modelled as CloudStack volumes (step 8)?
2. Which Proxmox privilege does managing HA resources need, and is it acceptable on the extension's token?

## Decided

- Plan approved 11 Nov 2026. Trials run on the non-production CloudStack built on HV08 on 12 Nov 2026.
- HA follows the model above.
- The fork is public because GitHub forks of public repositories are public.
