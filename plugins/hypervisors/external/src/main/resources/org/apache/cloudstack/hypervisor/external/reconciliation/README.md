# Periodic External VM refresh integration

`ExternalRefreshWorker` is a CloudStack `ManagerBase`/`Configurable` bean. Register it once in the plugin's Spring context with the normal manager lifecycle and configuration discovery, and ensure `ExternalVmRefreshManager`, `ExtensionsManager`, `ExtensionHelper`, VM/details DAOs, and HostDao are injected. No Spring registration is included here.

* `external.refresh.periodic.enabled` is global and defaults false.
* `external.refresh.periodic.interval.seconds` defaults 300; valid range 30–86400. Changes are checked on the 30-second scheduler tick. Invalid values pause the worker.
* `external.refresh.periodic.cluster.enabled` is cluster-scoped and defaults false. The worker requires an explicit cluster-scoped true, never a global fallback.

Each management server schedules a managed-context task; CloudStack's DB-backed `GlobalLock` prevents simultaneous worker runs. A run scans at most 100 candidates, in ID order, and stops initiating additional refreshes after 60 seconds. One already-started synchronous refresh may exceed that deadline according to the underlying agent timeout. Cursor progress is in memory per management server and resets after a scan or restart; it is not a durable exactly-once queue. All writes are delegated to `refresh(id, false)` and retain its own authorization, fresh reads, accounting and transaction rules. Worker logs are aggregate counts or a VM UUID and a fixed failure category; lower layers retain their own logging behavior.

The adoption check and duplicate/source-identity checks are point-in-time filters, not a lock shared with adoption, a provider-incarnation fence, or a fence against the old fullsync. Do not enable this while adoption/binding can race the refresh, or while an old writer can race it. Establish and separately verify an approved cutover/fencing procedure before enabling any cluster; the native adoption control row alone is not evidence that old fullsync has stopped. The CloudStack `GetVmIpAddress` background writer is unchanged. This feature is not a replacement for its coordination.

Focused unit tests use mock DAOs and a shared in-memory lock double; they do not establish real DAO query behavior, database lock behavior across management servers, Spring lifecycle/config discovery, race-free adoption, or provider behavior. Those integration checks and review are required before any enablement. No deployment or production switch is performed by these source files.
