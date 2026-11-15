# Native adoption registry

Registered and in use. Tables: `schema.sql` in this directory, installed by hand (not part of a CloudStack upgrade).

A claim binds one provider guest (cluster + VMID; the node is not part of the identity, so the guest may move within
its cluster) to one CloudStack instance (UUID and internal name), with a generation and the SHA-256 of its manifest.
States: `managed` (idle), `operating` (one operation lease held), `retiring` (deleted at the provider, waiting for the
instance row to go), `released`.

Every change to an adopted guest's provider state runs under an operation lease: acquired before the extension runs,
completed on known success, released by itself on a failure the extension proves changed nothing, and kept on any
unknown outcome, even after it expires. A kept lease blocks further changes to that guest until a root admin inspects
the guest and calls `releaseExternalAdoptionOperationLease` with the exact claim, generation and lease. Nothing is
replayed.

APIs (root admin): `adoptExternalInstance` (dry run by default; records a running or stopped guest without touching
it), `importExternalAdoptionRegistry` and `handoverExternalAdoptionRegistry` (the one-time migration from the legacy
service; the export is read from `/etc/cloudstack/management/external-adoption-export.json`, never from a request
parameter, because CloudStack logs those), `releaseExternalAdoptionOperationLease`.

Import refuses an unsettled legacy registry (a claim not `managed` or `released`, an unfinished execution, a lease,
held authority). It keeps every exported row in `external_adoption_legacy_export`, imports the current claims fenced,
and archives finished history. Handover checks every managed claim against the live guest and CloudStack and clears
the fence only when all pass; a refusal changes nothing and names each failing guest.

Expunge of an adopted instance deletes the guest at the provider, as for a deployed one, under a `delete` lease; the
claim is released once the instance row is gone. Legacy export, manifests and plans can hold sensitive data: do not
log or print them.
