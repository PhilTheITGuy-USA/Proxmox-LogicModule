# Backups: jobs, their guests, and whether each guest's backup worked

Status: draft, not implemented. Replaces `Proxmox_VE_BackupCoverage` and absorbs the Tier 2a
`Proxmox_VE_BackupStatus` entry (DESIGN §4).

## 1. Goal

One module, `Proxmox_VE_Backups`, that answers in the portal:

- which backup jobs exist, and whether each is enabled and when it runs next;
- which guests each job covers, and which guests no job covers;
- for every covered guest, when its last backup was made, how many it has, how big the last one
  was, whether it is protected and (on PBS) verified;
- whether a backup of that guest has failed since its last good one.

`Proxmox_VE_BackupCoverage` answers only the second half of the second question, as three counts
on one instance. It is deleted, not upgraded: the portal will not turn a single-instance module
into a multi-instance one (DESIGN §7, the `Proxmox_VE_Cluster` upgrade).

## 2. Evidence

From the published API schema (`apidoc.js`, read 2026-10-25):

- `/cluster/backup` lists the jobs: `id`, `enabled`, `schedule`, `next-run` (Unix time),
  `storage`, `node` (pins the job to one node), and the selection — `all`, `pool`, `vmid`,
  `exclude`. Needs `Sys.Audit` on `/`.
- `/cluster/backup/{id}/included_volumes` returns the guests a job actually includes, with the
  selection already applied: `id` (VMID), `name`, `type` (`VM`, `CT`, or `unknown` for a removed
  guest). Needs `Sys.Audit` on `/`.
- `/cluster/backup-info/not-backed-up` returns the guests no job covers: `vmid`, `name`, `type`.
  Already read by `Proxmox_VE_BackupCoverage` (verified live 2026-09-22).
- `/nodes/{node}/storage/{storage}/content?content=backup` lists backups: `vmid`, `ctime`,
  `size` (or `approximate-size`), `protected`, `notes`, and on PBS `verification.state`. Needs
  `Datastore.Audit` on the storage.
- `/nodes/{node}/tasks` takes `typefilter`, `since` and `errors` ("only tasks with an error
  status"); `/nodes/{node}/tasks/{upid}/log` returns the log as `n` / `t` lines. Need `Sys.Audit`
  on the node.
- `/cluster/resources?type=storage` gives each storage's `node`, `shared` and `content`.

`PVEAuditor`, the role INSTALL already asks for, carries `Sys.Audit` and `Datastore.Audit`, so no
new privilege is needed.

From the lab (ProxMox-A, 2026-10-25):

- One job, `backup-4e5f5bfc-094c`: enabled, `schedule` `21:00`, `node` `ProxMox-A`, `storage`
  `plex-usb`, `vmid` `100,101,102,103,106,105`, `prune-backups` `keep-last=3`, `next-run` present.
- `vzdump` tasks carry an **empty `id`**: one task per run covers every guest in it, and its
  `status` (`OK` on all five seen) is the run's, not a guest's. Four of the five started 86,400 s
  apart (the 21:00 job); the fifth was a manual run, which appears in the same list.
- Node names are case-sensitive in API paths: `/nodes/proxmox-a/...` fails with "proxy loop
  detected" where `/nodes/ProxMox-A/...` works. The module takes names from the API, never from
  a hostname.

A run with one failed guest (ProxMox-A, 2026-10-25: container 101 locked, 102 not, one manual
run to `plex-usb`):

- The task's `status` is `job errors`, and `tasks?typefilter=vzdump&errors=1` returns it (the only
  task listed, so successful runs are filtered out).
- The log names each guest's result. The failed guest:
  `ERROR: Backup of VM 101 failed - CT is locked (backup)` — "VM" for a container too. The
  successful one: `INFO: Finished Backup of VM 102 (00:00:50)`.
- Line 1 is the run's command line, with no job id:
  `INFO: starting new backup job: vzdump 101 102 --node ProxMox-A --mode snapshot --storage plex-usb`.
- The last line is `TASK ERROR: job errors`.
- The two-guest run logged 33 lines; `/log` returns 50 unless `limit` is raised, so a six-guest
  run would be cut short.
- A manual run prunes with the storage's retention (`keep-daily=2, keep-last=2, keep-weekly=2`),
  not the job's `keep-last=3`.

## 3. Design

### 3.1 Instances: one per guest per job, grouped by job

- **Covered guests:** one instance per (job, guest) pair from `included_volumes`. Wildvalue
  `pveWildValue(<job id> + '-' + <vmid>)`, e.g. `backup-4e5f5bfc-094c-101`. A guest in two jobs
  has two instances, one under each job.
- **Uncovered guests:** one instance per guest from `not-backed-up`, wildvalue `none-<vmid>`.
- **Display name:** `<guest name> (<vmid>)`.
- **Grouping:** `instanceGroupProperty` `auto.pve_backup_job` — the job's `comment` when set,
  else its `id`; `No backup job` for uncovered guests. The portal shows each job as a group with
  its guests under it.
- **Instance properties:** `auto.pve_backup_job`, `auto.pve_backup_job_id`,
  `auto.pve_backup_schedule`, `auto.pve_backup_storage`, `auto.pve_vmid`, `auto.pve_type`
  (`qemu` / `lxc`, mapped from `VM` / `CT`).

A guest moving from "No backup job" into a job becomes a new instance. They are different facts
(uncovered versus covered by a job), so this is correct rather than an orphaning.

### 3.2 Datapoints

| Datapoint | On | Value | Threshold |
|---|---|---|---|
| `NotCovered` | every instance | 1 on `No backup job` instances, 0 on job instances | none — uncovered guests are often deliberate, as today |
| `JobEnabled` | job instances | 1 / 0 from `enabled` | none |
| `HoursUntilNextRun` | job instances | from `next-run`; withheld when absent | none |
| `BackupCount` | job instances | backups of this guest on the job's storage; 0 is a real answer | none |
| `LastBackupAgeHours` | job instances | now − newest `ctime`; withheld when there is no backup | none shipped; the site sets it per job (e.g. `> 26` on a daily job) |
| `LastBackupSizeGB` | job instances | newest backup's `size` or `approximate-size`; withheld when absent | none |
| `LastBackupProtected` | job instances | 1 / 0; withheld when there is no backup | none |
| `LastBackupVerified` | job instances on PBS | 1 for `verification.state` `ok`, 0 for any other state; withheld when absent | none |
| `FailedSinceLastBackup` | job instances | 1 when a failed run after the newest backup logged this guest as failed, else 0 | `> 0 0 1`, with an alert message naming the guest |

`NotCovered` summed across instances is the old `GuestsNotBackedUp`; that is what the dashboard
tile uses (§5). `LastBackupAgeHours` ships unthresholded because the right value depends on the
job's schedule, which the module does not parse — a daily threshold would alert forever on a
weekly job.

### 3.3 Discovery (hourly)

1. `/cluster/backup` — the jobs.
2. `/cluster/backup/{id}/included_volumes` per job — its guests. A guest of type `unknown`
   (removed, not purged) is skipped.
3. `/cluster/backup-info/not-backed-up` — the uncovered guests. If the endpoint is absent, no
   uncovered instances are discovered and stderr says so; covered instances are unaffected.

Calls: 2 + jobs. Any failure of call 1 exits 2, so existing instances are kept.

### 3.4 Collection (60 minutes, BatchScript)

1. `/cluster/backup` again, for `enabled` and `next-run`.
2. `/cluster/resources?type=storage` — which node holds each job's storage, and whether it is
   shared.
3. **Backups per storage.** For each distinct job storage: a shared storage is listed once, from
   any online node; a local storage is listed on the job's `node` when the job is pinned, else on
   every online node that has it. Per-node `try`, as in the fan-out modules: an unreachable node
   costs only its own storage. Content is matched to instances by `vmid`.
4. **Failures.** One `/nodes/{node}/tasks?typefilter=vzdump&errors=1&since=<now − 8 days>` per
   online node — normally empty. For each failed task found, one `.../log` read with a `limit`
   large enough for the whole log (paged with `start` if needed). A guest's
   `FailedSinceLastBackup` is 1 when such a log, from a run whose `starttime` is after that
   guest's newest backup, has `^ERROR: Backup of VM (\d+) failed` for its VMID; otherwise 0.
   Timestamps come from the task, never from the log text.

Calls: 2 + backup storages (local ones once per node that holds them) + online nodes + failed
runs in the window. Nothing grows with guest count.

A job with no `storage` (it falls back to the node's `vzdump.conf` default) gets no storage-based
datapoints, and stderr names the job.

### 3.5 Limitations (worded for `technicalNotes`)

- Two jobs writing the same guest to the same storage are indistinguishable in the storage
  listing, so both instances report the same backups.
- A failed run is tied to guests through its log only; a failure the log does not attribute to a
  guest raises nothing until `LastBackupAgeHours` passes its threshold.
- Listing a large PBS datastore can be slow; the 60-minute interval allows for it.

## 4. Tests (harness)

Fixtures for every endpoint above, built around the lab's job (six guests, `plex-usb`, pinned to
`pve1`), with the failed log copied from §2 and padded past 50 lines, plus a second, unpinned job on shared storage, one uncovered guest, one guest with no
backups, one PBS backup with `verification`, and one failed task whose log names one guest.

Assertions:

- instances = (job, guest) pairs + uncovered guests, with the wildvalues and group values of §3.1;
- `LastBackupAgeHours`, `BackupCount`, `LastBackupSizeGB` from the newest backup; withheld when
  there is none, and `BackupCount` 0;
- `LastBackupVerified` only on the PBS backup;
- `FailedSinceLastBackup` 1 only for the guest the failed log names, and 0 when the failure is
  older than the guest's newest backup;
- log reads only for tasks returned by `errors=1`, each asking for the whole log; no request to
  an offline node;
- a shared storage listed once, a local storage on its job's node;
- discovery exits 2 when `/cluster/backup` fails; collection exits 2 on a dead API.

## 5. Documentation and dashboard

- `modules/Proxmox_VE_Backups.json` replaces `modules/Proxmox_VE_BackupCoverage.json`; the module
  count stays fourteen DataSources.
- README coverage table, CLAUDE.md module table, DESIGN §4 (Tier 2 row; Tier 2a's BackupStatus
  folded in) and §7 (a new row).
- INSTALL: replacing the old module is a delete-then-import, as for `Proxmox_VE_Cluster`, then
  re-import the Tier 2 dashboard.
- Tier 2 dashboard: the "Backup Coverage" tile becomes "Backups" — guests not backed up (sum of
  `NotCovered`), failed backups (sum of `FailedSinceLastBackup`), oldest last backup (max of
  `LastBackupAgeHours`). Check first that the widget helpers aggregate across instances; today's
  tile reads a single-instance module.

## 6. Portal verification before merging

1. ~~A failed guest, first~~ — done 2026-10-25; the results are in §2 and set the parser and
   the fixture.
2. Delete `Proxmox_VE_BackupCoverage`, import `Proxmox_VE_Backups`, run Active Discovery: one
   group for the job with guests 100, 101, 102, 103, 105 and 106, and a `No backup job` group
   with every other guest.
3. After a collection: `LastBackupAgeHours` matches the time since the last 21:00 run,
   `BackupCount` matches the guest's files on `plex-usb` (retention differs between the job and
   manual runs), and a guest with a failed run after its newest backup reads
   `FailedSinceLastBackup` 1 until its next good backup.
4. Re-import the Tier 2 dashboard and count its widgets.
