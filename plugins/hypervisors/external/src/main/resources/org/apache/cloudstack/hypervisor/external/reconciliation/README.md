# Periodic External instance refresh

`ExternalRefreshWorker` (registered in `META-INF/cloudstack/external-compute`) runs the same refresh as
`refreshExternalVirtualMachine` with `dryrun=false`, for every Running or Stopped External instance of the clusters
that opt in. It writes nothing itself; every change is the refresh's.

Settings:

- `external.refresh.periodic.enabled` (global, default false)
- `external.refresh.periodic.cluster.enabled` (cluster scope only, default false; no global fallback)
- `external.refresh.periodic.interval.seconds` (default 300, 30 to 86400; another value pauses the worker)

Each management server ticks every 30 s; a database `GlobalLock` lets one run at a time. A run takes up to 100
instances in ID order and starts no refresh after 60 s. When the pass over all instances is not finished, the next
tick continues it; a new pass starts one interval after the previous one began. The position is held in memory by
each server, not shared.

An instance is skipped when its host, cluster opt-in, extension capability, VMID or cluster identity is not certain,
or when it is adopted and its claim is not managed with no operation in progress (the refresh rechecks that before it
commits). A failed refresh is logged with the instance UUID and a one-line reason; the batch continues.
