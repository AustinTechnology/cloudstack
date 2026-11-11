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

Ordered so that each step is useful on its own and the early ones change one jar only.

### Step 1: establish build and deploy for one jar

- Build a non-production CloudStack 4.23.0.0 with one External (Proxmox) cluster to trial every jar on. None exists today; it is to be built on HV08.
- Build `cloud-plugin-hypervisor-external` (`plugins/hypervisors/external/pom.xml`) from the branch against 4.23.0.0 and confirm the unmodified build is functionally equivalent to the shipped jar before changing anything.
- Write down the install and rollback procedure for the jar on P2-ACS-MGMT01. It needs a `cloudstack-management` restart.
- Validation: unmodified rebuilt jar runs in place of the shipped one with no behaviour change (console canary, power reports).

**Not yet established:** the jar's install path on the management server.

### Step 2: follow VMs that the orchestrator moves (plugin jar only)

Everything in the HA model depends on this: when Proxmox HA or an operator moves a VM, CloudStack has to record the new host by itself.

- Read how `host_id` is updated from power reports for External VMs (`VirtualMachinePowerStateSyncImpl`, `updatePowerState`) and what `trackVmHostChange()` gates. This was not traced for this plan.
- Then either return `true` from `ExternalHypervisorGuru.trackVmHostChange()` or make the smallest change that lets CloudStack record the new host when a VM is reported from a different one.

Validation: migrate a test VM between Proxmox nodes, and separately let Proxmox HA recover one from a fenced node, and confirm CloudStack shows the new host and Running with no manual step.

### Step 3: answer the two commands that hit the fallback (plugin jar only)

Files: `ExternalResource.java`, `ExternalPathPayloadProvisioner.java`, their existing tests under `plugins/hypervisors/external/src/test/`, and the Proxmox script.

- Add explicit handlers for `CheckVirtualMachineCommand` and `GetVmIpAddressCommand`.
- Neither command carries connection details. Resolve the VM by instance name and build the access details inside the provisioner, the same way `getHostVmStateReport` (line 458) already does with `hostDao` and `extensionsManager.getExternalAccessDetails`.
- `CheckVirtualMachineCommand`: reuse the script's existing `status` action and return a `CheckVirtualMachineAnswer` with the power state.
- `GetVmIpAddressCommand`: add a script action that returns the guest-agent IPv4 address for the given MAC, ignoring loopback and link-local addresses. Return success with no address when the agent has none, and failure when the agent is unreachable, so CloudStack does not blank a known IP because an agent is down.
- Change the fallback to return "unsupported" without invoking the script. A payload with no VM in it cannot be answered correctly.

Validation:
- Unit tests for both handlers and the fallback.
- On a running External VM with a guest agent: CloudStack populates the NIC IP without the sync tool.
- The `Missing required fields` log lines stop.

### Step 4: HA handed to the orchestrator (first management-server change)

Files: `HighAvailabilityManagerImpl.java` (327, 496, 541), `VirtualMachineManagerImpl.java` (5533), a new investigator and fencer in the External plugin, `ExternalResource.java`, the provisioner, the script.

- **CloudStack stands down.** Add External to the same skips VMware has, and add an External investigator and fencer that behave like the VMware ones. With this in place it is safe for `ha_enabled` to be true on an External VM.
- **The flag becomes a request.** When an External VM is deployed or started, and when its HA setting changes (offering change, or `haenable` on update), the plugin calls a new script action `set-ha` with on or off. Where the HA setting change is hooked in the management server was not traced for this plan.
- **Proxmox script.** `set-ha` on adds the VM as a Proxmox HA resource in state `started`; off removes it. A stop requested through CloudStack must set the HA resource to `stopped` first, or Proxmox HA will start the VM again.
- **Report back.** The status action returns whether the orchestrator has HA on for the VM, so CloudStack can show a mismatch and never assumes.
- **Permissions.** The `CloudStackExtension` Proxmox role has no HA rights today. The privilege needed to manage HA resources has to be confirmed and added to the role.
- **Existing VMs.** Set each VM's CloudStack HA flag from what Proxmox HA has today, so turning this on changes nothing in Proxmox. The "(no HA)" offerings created on 11 Nov then mean what they say.

Validation:
- HA on in the offering: the VM appears in Proxmox HA; fence its node and Proxmox restarts it; CloudStack follows to the new host and starts nothing itself.
- HA off: the VM leaves Proxmox HA.
- Power an HA-on VM off inside the guest: CloudStack shows Stopped and schedules no restart.
- Stop an HA-on VM from CloudStack: it stays stopped.

### Step 5: scale VM

Files: `UserVmManagerImpl.java` (2117), `ExternalResource.java`, the provisioner, the script.

- Allow scale for External VMs and add a handler for `ScaleVmCommand` that calls a new script action to set cores and memory in Proxmox.
- This changes the `cloud-server` jar as well as the plugin jar, like step 4.

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

- **Carried patches.** Every step not accepted upstream must be re-applied on each CloudStack upgrade. Steps 1 to 3 touch one small jar; steps 4 onward touch the main server jars and cost more to carry.
- **Restart per deployment.** Each jar change needs a management server restart.
- **HA flag before step 4.** Until step 4 is deployed, turning CloudStack HA on for an External VM brings back the stuck-Running fault. Keep it off until then.
- **Two HA managers.** Step 4 is safe only if every CloudStack restart path is skipped for External. The fence-a-node test in step 4 is the check.
- **Step 6 and the sync tool overlap.** Run one writer at a time for any given field.

## Open questions

1. Should External VM disks be modelled as CloudStack volumes (step 8)?
2. Which Proxmox privilege does managing HA resources need, and is it acceptable on the extension's token?

## Decided

- Plan approved 11 Nov 2026. Trials run on a non-production CloudStack, to be built on HV08.
- HA follows the model above.
- The fork is public because GitHub forks of public repositories are public.
