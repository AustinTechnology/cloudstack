# External adoption registry: isolated source prototype

This is NOT registered as a CloudStack service, API, upgrade, or shell callback.
`schema.sql` is an uninstalled, fresh-schema MariaDB/MySQL DDL proposal, not a
numbered CloudStack migration. Review versioning, privileges, character-set/index
limits, time zone and upgrade/rollback before integrating. Java uses the existing
`com.cloud.utils.db.Transaction`, `TransactionCallback`, `TransactionLegacy`
and JDBC; import also uses the plugin's existing `jackson-databind` dependency.
The scoped test uses JUnit 4. No new POM dependency is declared here.

## In-process callback primitives

`AdoptionRegistry.Identity` carries claim UUID, generation, cluster, VMID,
manifest SHA-256, CloudStack UUID and internal instance name. It deliberately
does not include node: like the legacy lifecycle path, intra-cluster movement is
possible. Dispatcher must independently establish current node and guarantee
that the cluster/VMID in the identity is the actual actuator path. Available
methods are `validatedState`, `acquire`, `assertLease`, `complete`, `completeDelete`
and `finalizeRetirement`. Expunge deletes an adopted instance at its orchestrator under a
`delete` lease; `completeDelete` then leaves the claim retiring until the instance row is gone. A shared control-row lock serializes import and all
these transactions. The exact operation token remains on the claim and in a
uniquely claim-scoped lease row. Only the legacy managed actions are enabled.
A callback must acquire before any mutation or credential issuance, recheck the
same live token before each further actuator step, and complete only after all
postconditions. Never send the token as a shell bearer credential.

Unlike legacy takeover, expiration never automatically clears a lease or permits
retirement/replay; unknown operations stay fenced for future explicit recovery.
Even a nominal success response after expiry cannot complete. The duration is
two hours, as in the legacy source. `finalizeRetirement` depends on a trusted
manager-provided *authoritative post-commit* exact-UUID absence read, not a
user-supplied boolean, callback result, or a pre-commit snapshot; the callback
is only an integration contract and cannot prove that provenance itself.
Outer CloudStack rollback, VM recreation, and absence-check sequencing must be
proved when wiring it. No side effect is ever submitted by these primitives.

## Legacy export v1 and fenced import

`LegacyAdoptionImport.importExport(json, dryRun)` expects a JSON object with
exactly four arrays: `adoption_claims`, `adoption_operation_leases`,
`adoption_executions`, and `adoption_write_authority`. Every array member is a
JSON object containing source column names and values as returned from one
consistent source database snapshot. Claim rows MUST contain all 13 source
columns: `id`, `proxmox_cluster`, `proxmox_node`, `proxmox_vmid`,
`manifest_sha256`, `manifest_json`, `generation`, `state`, `operation_lease_id`,
`cloudstack_vm_ref`, `cloudstack_instance_name`, `created_at`, `updated_at`;
nullable values must be present as JSON null. Integers are JSON integers;
`manifest_json` is the exact stored canonical JSON string, not an object;
datetimes are ISO-8601, UTC when no offset is present, with microsecond
precision or null. Preserve all columns, including unknown/future columns, in
the four arrays; do not include secrets or a bearer proof. Duplicate JSON keys,
duplicate claim UUID/cluster+VMID/CS reference, invalid hash, and mismatched
manifest placement fail closed. The importer validates hash over exact stored
manifest bytes and placement, but does not recanonicalize legacy JSON. It does
not fully validate execution/lease/authority schemas or prove legacy data
consistency. A dry-run checks the envelope, claims and an empty destination; it
does not simulate all database type/length/collation constraints. A real import
is one database transaction and can still fail and roll back.

All original rows—including unknown states, execution plans, job IDs, authority,
lease tokens, orphan records and timestamps—are archived in the exact input JSON
in `external_adoption_legacy_export`; the archive is not an executable execution
or lease table. Claim rows are also materialized with `migration_fenced=TRUE`.
Every import, including an empty one, sets the shared global `legacy_fenced`
flag. No method here clears it. A separately designed, root-admin-only recovery
must read back and compare the archive, reconcile every execution/lease/authority
and coordinate a single-writer cutover before any new admissions; **never**
clear the fence simply because a token expired. Legacy JSON may contain sensitive
plan/identity data: protect SQL access and do not log or expose it.

Import API authentication is NOT implemented: any future API must enforce
root-admin access and hold an operator-controlled fence that stops all old
scheduler, executor, CLI and callbacks throughout snapshot and migration. The
new SQL lock does not lock the separate legacy DB and cannot provide that fence.
No credentials or remote migration are handled here.

## Explicitly unimplemented and unverified

Reservation, released-generation reuse, bind, activation with authoritative VM
verification, cleanup-delete authorization, status-map, global operator versus
automatic authority, durable execution/job recovery and API/dispatcher/shell
integration remain separate work. No method fabricates managed state. The global
fence deliberately prevents every imported claim from being used until a future
recovery implementation. Unknown/orphan lease rows remain in the archive, not
in active native lease tables. No automatic operation timeout replay is possible.
SQL uniqueness/collation behavior, transaction rollback, deadlock/lock-wait
handling, independent-session races, actual CloudStack context and migration
compatibility have not been exercised by the parser unit tests; they require a
separate isolated actual-dialect gate. Do not deploy this prototype.
