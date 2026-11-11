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
| `CheckVirtualMachineCommand` | `VirtualMachineManagerImpl.checkVmOnHost` (line 2770), used by HA | instance name only | HA can never confirm a VM's state; this is why HA had to be switched off |
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

## Steps

Ordered so that each step is useful on its own and the early ones change one jar only.

### Step 1: establish build and deploy for one jar

- Build `cloud-plugin-hypervisor-external` (`plugins/hypervisors/external/pom.xml`) from the branch against 4.23.0.0 and confirm the unmodified build is functionally equivalent to the shipped jar before changing anything.
- Write down the install and rollback procedure for the jar on P2-ACS-MGMT01. It needs a `cloudstack-management` restart.
- Validation: unmodified rebuilt jar runs in place of the shipped one with no behaviour change (console canary, power reports).

**Not yet established:** the jar's install path on the management server, and whether there is a non-production CloudStack to trial on. See open questions.

### Step 2: answer the two commands that hit the fallback (plugin jar only)

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
- With HA re-enabled on one test VM: power it off in Proxmox and confirm CloudStack shows Stopped rather than staying Running.

Outcome: HA can be turned back on for External VMs, and IPs populate natively.

### Step 3: follow VMs that Proxmox moves

- Read how `host_id` is updated from power reports for External VMs (`VirtualMachinePowerStateSyncImpl`, `updatePowerState`) and what `trackVmHostChange()` gates. This was not traced for this plan.
- Then either return `true` from `ExternalHypervisorGuru.trackVmHostChange()` or make the smallest change that lets CloudStack record the new host when a VM is reported from a different one.

Validation: migrate a test VM between Proxmox nodes and confirm CloudStack shows the new host with no manual step.

### Step 4: scale VM (first management-server change)

Files: `UserVmManagerImpl.java` (2117), `ExternalResource.java`, the provisioner, the script.

- Allow scale for External VMs and add a handler for `ScaleVmCommand` that calls a new script action to set cores and memory in Proxmox.
- This changes the `cloud-server` jar as well as the plugin jar, so it is a larger deployment than steps 1 to 3.

Validation: change a custom-offering VM's CPU/RAM in CloudStack and confirm Proxmox matches; confirm a fixed-offering change of offering does the same.

### Step 5: NIC add and remove

- Add handlers for `PlugNicCommand` and `UnPlugNicCommand`, with script actions that add or remove a Proxmox network device on the right bridge and VLAN.
- Check whether the management server refuses these for External VMs before they reach the plugin. This was not traced for this plan.

Validation: add and remove a NIC from CloudStack on a test VM and confirm the Proxmox device, MAC and VLAN match.

### Step 6: let CloudStack pull a VM's real configuration

- Add a plugin action that asks the script for a VM's current CPU, RAM, NICs and IPs and updates CloudStack through its own services.
- This replaces the sync tool's direct database writes behind the "RefreshDetails" action and the periodic reconcile.

Validation: change CPU/RAM and an IP in Proxmox, run the action, confirm CloudStack matches and the sync tool made no write.

### Step 7: disks (needs a design decision first)

CloudStack has no volume records for External VMs. Volume attach, detach, resize, migrate and snapshot all depend on that model, so lifting the refusals alone would not work.

Decide first whether External VM disks should become CloudStack volumes at all. Until then, Proxmox snapshots stay available through the existing custom actions and disk changes are made in Proxmox.

### Step 8: upstream

- Raise step 2 as the first PR to `apache/cloudstack`; it is self-contained and fixes behaviour any External user hits.
- Raise later steps as they prove out in production.
- Rebase `external-plugin-4.23` onto each release we upgrade to, dropping patches that have merged.

## Risks

- **Carried patches.** Every step not accepted upstream must be re-applied on each CloudStack upgrade. Steps 1 to 3 touch one small jar; steps 4 onward touch the main server jar and cost more to carry.
- **Restart per deployment.** Each jar change needs a management server restart.
- **HA re-enable.** Step 2 makes HA able to act on External VMs. A wrong power answer could cause an unwanted restart, so it is re-enabled on one VM first.
- **Step 6 and the sync tool overlap.** Run one writer at a time for any given field.

## Open questions

1. Is there a non-production CloudStack to trial jar changes on, or does each step go to production in a maintenance window?
2. Which missing operations matter most? The order above assumes HA and IPs first, then migration tracking, then scale, then NICs.
3. Should External VM disks be modelled as CloudStack volumes (step 7)?
4. The fork is public because GitHub forks of public repositories are public. Is that acceptable for the working branch?
