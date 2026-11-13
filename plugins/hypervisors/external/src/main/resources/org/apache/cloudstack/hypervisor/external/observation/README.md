# Passive external guest IPv4 observations (isolated source, not deployed)

Source implementation only: the beans, database migration and refresh write
path are not wired here. Passive evidence is never adoption or lifecycle authority.

## Contract

`POST /client/api` with normal CloudStack signed form parameters, command
`submitExternalIpObservations`, `response=json`, `hostid=<CloudStack host UUID>`,
`captureid=<lowercase canonical UUID>`, and `observations=<JSON array>`. Example
array: `[{"vmid":100,"mac":"02:00:00:00:00:01","ips":["10.0.0.2"]}]`.
A multi-address array invalidates that VMID/MAC rather than hiding a conflict.
Empty array changes no candidate. At most 256 unique VMID/MAC entries, 32
addresses per entry, and 60000 UTF-8 payload bytes are accepted. Success is
`{"submitexternalipobservationsresponse":{"success":true}}`; replay of the
same host/capture UUID succeeds without applying it twice. Never reuse a UUID
for a different capture. Errors do not clear any VM or NIC IP.

The caller must be a CloudStack root-admin-type account allowed by its effective
API role and separately have a row in `external_observation_sender` binding its
CloudStack *user ID* to exactly one active External host and its cluster. Use a
separate dedicated node credential; restrict its effective role to this API and
verify role enforcement in the target version. Root-admin credentials remain
high value even with API role restrictions; isolate nodes and transport and do
not reuse a general admin key. No credential is added to the shell extension.
The receiver ignores caller-supplied cluster identity: the shared DB host and
sender mapping establish it. Sender mappings are administratively provisioned;
the API cannot change them.

Rows are keyed by CloudStack cluster ID + Proxmox VMID + exact MAC, and carry
sender host. A new matching host/IP capture increments at most to two; changed
IP, expired prior data, changed host or binding fingerprint starts at one;
multi-address resets to zero. Both latest distinct captures must be within 24
hours at lookup. `SYSDATE(6)` enforces server-side freshness. Records of
capture UUIDs are deliberately retained indefinitely to prevent replay after
expiry. Plan safe retention/partitioning and collector rate controls before
production; deleting UUIDs makes old requests replayable. These observations
are hints, not verified guest identity or proof of IP ownership.

`ExternalObservationService.fallback(cloudStackVmId, exactMac, agentIp)` returns
null when the guest agent has a usable unicast IPv4, when data is absent,
ambiguous, conflicting, expired, or a binding has moved; null/error MUST NOT
clear an existing IP. It rereads the native and provider binding both before and
after the SQL lookup. A caller that writes anything must revalidate identity and
all relevant freshness/ownership conditions immediately before its own guarded
CloudStack update. There is still a race between the second read and that
update unless integrated with the refresh manager's transaction/fencing.
Call fallback outside an existing JDBC transaction: it returns null inside one
to avoid reusing a stale repeatable-read snapshot. Submission requires its own
transaction and selects READ COMMITTED for that transaction only.

## Required integration (not supplied or enabled)

* Install `schema.sql` through the actual CloudStack upgrade/migration mechanism,
  including rollback/upgrade review and shared-DB permissions; it is not a
  registered migration and has not been applied.
* Register `ExternalObservationService` as an injectable management-server bean;
  it declares `SubmitExternalIpObservationsCmd` via `getCommands()`. Verify command scanning, POST, request
  size, sensitive request logging and role mappings on the exact target version.
* Register `NativeObservationBindingResolver` as the unique
  `ObservationBindingResolver` bean. It requires the native adoption registry
  table even for ordinary guests, and fails closed if it is unavailable. It
  rejects adoption claims/details pending native adoption authority integration.
  Ordinary deployed guests require unique cluster/VMID and exact native UUID,
  host, NIC UUID and network UUID, native state version and tagged VLAN binding;
  the host's configured `network_bridge` and NIC/network VLAN must agree with
  a fresh provider snapshot's node, internal name, MAC, bridge and VLAN. Each
  current stable field is fingerprinted; changed bindings restart evidence.
  Untagged or otherwise unrecognized networks are not supported by this resolver.
  A current snapshot and native version cannot prove absence of an invisible
  provider A→B→A move or identical VMID reuse. A fingerprint is not a provider
  incarnation or history fence. The hint must never authorize adoption or lifecycle.
* Integrate `fallback` into refresh only when agent data lacks a usable IP and
  enforce the refresh manager's freshness, allocation and write guards.
  Refresh wiring is not performed here.
* Validate exact CloudStack API signing/POST behavior, role boundaries, concurrent
  MariaDB transactions, duplicate capture races, age boundary and migration
  fences in a disposable lab before enabling. The Java tests are isolated
  source tests, not integration evidence. No legacy global-MAC observations may
  be imported blindly: current cluster/VMID/host/NIC provenance and freshness
  must be independently established, or recollect fresh observations.

The node script has anonymous print mode; `--post` reads a JSON config with
`url`, `api_key`, `secret_key`, `host_id` from a current-user-owned mode-0600
regular file (default `/etc/cloudstack/external-observer.json`). The URL must
be HTTPS; default TLS certificate validation is used and redirects are refused.
The collector uses local `timeout`, `tcpdump`, and Python 3; it installs and
schedules nothing. It waits 60 seconds, filters `Out` traffic, identifies
`tap<VMID>i<nic>`, and reports all observed IPv4s for conflict detection. A
capture command failure fails the whole run; a capture with no packets leaves
old candidates untouched until they expire. Output and errors omit credentials.
