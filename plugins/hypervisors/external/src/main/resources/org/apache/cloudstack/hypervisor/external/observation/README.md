# Observed guest IPv4 addresses (ARP fallback)

Registered: `ExternalObservationService` (API `submitExternalIpObservations`) and
`NativeObservationBindingResolver`. Tables: `schema.sql` in this directory, installed by hand (not part of a
CloudStack upgrade).

A collector on each Proxmox node (`node-collector/observe_external_ips.py` in the extension repository) watches guest
tap interfaces for 60 s and posts what each VMID/MAC sent from. The refresh uses an observed address only when the
guest agent gives none, and only after two matching captures within 24 hours, with the guest's binding (cluster,
VMID, host, NIC, network, VLAN, bridge) unchanged since. A conflict invalidates the candidate; nothing here ever
clears a known address, adopts a guest, or authorises a lifecycle action. Adopted guests are included.

Sender: each node posts as its own CloudStack user, of a root-admin-type account whose role allows only this API,
mapped to exactly one External host by a row in `external_observation_sender` (`user_id`, `host_id`, `cluster_id`).
The API cannot create that row; an administrator inserts it. The receiver takes the cluster from that row, never from
the request. Limits: 60000 bytes, 256 entries, 32 addresses each; a capture UUID is accepted once.

The collector reads `url`, `api_key`, `secret_key`, `host_id` from a mode-600 file (default
`/etc/cloudstack/external-observer.json`), requires HTTPS with certificate validation, and refuses redirects.
Capture UUIDs are kept indefinitely so an old request cannot be replayed; plan their retention.
