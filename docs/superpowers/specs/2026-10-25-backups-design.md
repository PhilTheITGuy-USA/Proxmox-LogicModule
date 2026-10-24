# Backups: jobs, their guests, and whether each guest's backup worked

Status: implemented on branch `backups-module`; portal-verified 2026-10-25, Tier 2 dashboard
tile included (DESIGN §7). Replaces `Proxmox_VE_BackupCoverage` and absorbs the Tier 2a
`Proxmox_VE_BackupStatus` entry (DESIGN §4).

## 1. Goal

One module, `Proxmox_VE_Backups`, that answers in the portal, with the suite's read-only
`PVEAuditor` token:

- which backup jobs exist, and whether each is enabled and when it runs next;
- which guests each job covers, which of their volumes it backs up and leaves out, and which
  guests no job covers;
- for every covered guest, when its last good backup was made and how big it was;
- whether a backup of that guest has failed since its last good one.

`Proxmox_VE_BackupCoverage` answers only the uncovered-guest half of the second question, as
three counts on one instance. It is deleted, not upgraded: the portal will not turn a
single-instance module into a multi-instance one (DESIGN §7, the `Proxmox_VE_Cluster` upgrade).

## 2. Evidence

From the published API schema (`apidoc.js`, read 2026-10-25):

- `/cluster/backup` lists the jobs: `id`, `enabled`, `schedule`, `next-run` (Unix time),
  `storage`, `node` (pins the job to one node), and the selection — `all`, `pool`, `vmid`,
  `exclude`. Needs `Sys.Audit` on `/`.
- `/cluster/backup/{id}/included_volumes` returns the guests a job actually includes, with the
  selection already applied: `id` (VMID), `name`, `type`, and each guest's volumes as `children`.
  Needs `Sys.Audit` on `/`.
- `/cluster/backup-info/not-backed-up` returns the guests no job covers: `vmid`, `name`, `type`.
  Already read by `Proxmox_VE_BackupCoverage` (verified live 2026-09-22).
- `/nodes/{node}/tasks` takes `typefilter`, `limit` and `errors`; `/nodes/{node}/tasks/{upid}/log`
  returns the log as `n` / `t` lines. Need `Sys.Audit` on the node.

From Proxmox's source (`pve-storage`, read 2026-10-25): the storage content listing
(`API2/Storage/Content.pm`) drops, without an error, every volume `check_volume_access` refuses,
and for a backup file that check (`Storage.pm`) requires `Datastore.AllocateSpace` on the storage
and `VM.Backup` on the guest, or `Datastore.Allocate`. `PVEAuditor` holds only `*.Audit`
privileges, so a read-only token lists no backups at all. Those privileges also allow creating
and restoring backups, so the module does not ask for them (§7).

From the lab (Node-A, 2026-10-25):

- One job, `backup-4e5f5bfc-094c`: enabled, `schedule` `21:00`, `node` `Node-A`, `storage`
  `media01-usb`, `vmid` `100,101,102,103,106,105`, `prune-backups` `keep-last=3`, `next-run` present.
- `included_volumes` for that job types its guests `lxc` and `qemu`, not the schema's `VM` and
  `CT`, and lists each guest's volumes under `children` with `included` and a `reason` (a bind
  mount reads `included: 0`, `not a volume`).
- As root, `media01-usb`'s content listing shows each backup with `vmid` and `ctime`; through the
  `PVEAuditor` token the module saw none — the filtering above.
- `vzdump` tasks carry an **empty `id`**: one task per run covers every guest in it, and its
  `status` is the run's, not a guest's. Four of five runs started 86,400 s apart (the 21:00
  job); the fifth was a manual run, which appears in the same list.
- Node names are case-sensitive in API paths: `/nodes/node-a/...` fails with "proxy loop
  detected" where `/nodes/Node-A/...` works. The module takes names from the API, never from
  a hostname.

A run with one failed guest (Node-A, 2026-10-25: container 101 locked, 102 not, one manual
run to `media01-usb`):

- The task's `status` is `job errors`.
- The log names each guest's result. The failed guest:
  `ERROR: Backup of VM 101 failed - CT is locked (backup)` — "VM" for a container too. The
  successful one: `INFO: Starting Backup of VM 102 (lxc)`, later `INFO: archive file size: 3.51GB`
  and `INFO: Finished Backup of VM 102 (00:00:50)`. The size is binary: 3,768,760,320 bytes
  written (3.6 GiB) for an archive logged as 3.51GB.
- Line 1 is the run's command line, with no job id:
  `INFO: starting new backup job: vzdump 101 102 --node Node-A --mode snapshot --storage media01-usb`.
- The last line is `TASK ERROR: job errors`.
- The two-guest run logged 33 lines; `/log` returns 50 unless `limit` is raised, so a six-guest
  run would be cut short.

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
  (`qemu` / `lxc`, as `included_volumes` returns it; the schema's `VM` / `CT` are mapped too),
  `auto.pve_backup_included_volumes` and `auto.pve_backup_excluded_volumes` — the guest's
  volumes the job backs up, and the ones it leaves out with Proxmox's reason, e.g.
  `rootfs: local-lvm:vm-101-disk-0` and `mp0: /mnt/media01data (not a volume)`.

A guest moving from "No backup job" into a job becomes a new instance. They are different facts
(uncovered versus covered by a job), so this is correct rather than an orphaning.

### 3.2 Datapoints

| Datapoint | On | Value | Threshold |
|---|---|---|---|
| `NotCovered` | every instance | 1 on `No backup job` instances, 0 on job instances | none — uncovered guests are often deliberate |
| `JobEnabled` | job instances | 1 / 0 from `enabled` | none |
| `IncludedVolumes` | job instances | volumes the job backs up, from `included_volumes` | none |
| `ExcludedVolumes` | job instances | volumes it leaves out (bind mounts, `backup=no` disks) | none — a bind mount is never backed up |
| `HoursUntilNextRun` | job instances | from `next-run`; withheld when absent | none |
| `LastBackupAgeHours` | job instances | now − start of the guest's newest successful run to the job's storage; withheld when none is found | none shipped; the site sets it per job (e.g. `> 26` on a daily job) |
| `LastBackupSizeGB` | job instances | that run's logged archive size; withheld when it logged none (PBS) | none |
| `FailedSinceLastBackup` | job instances | 1 when a newer run to the same storage logged this guest as failed, else 0 | `> 0 0 1`, with an alert message naming the guest |

`NotCovered` summed across instances is the old `GuestsNotBackedUp`; the dashboard tile uses it
(§5). `LastBackupAgeHours` ships unthresholded because the right value depends on the job's
schedule, which the module does not parse — a daily threshold would alert forever on a weekly job.

### 3.3 Discovery (hourly)

1. `/cluster/backup` — the jobs.
2. `/cluster/backup/{id}/included_volumes` per job — its guests and their volumes. A guest of
   any type other than `qemu`, `lxc`, `VM` or `CT` (such as `unknown`, removed but not purged)
   is skipped.
3. `/cluster/backup-info/not-backed-up` — the uncovered guests. If the endpoint is absent, no
   uncovered instances are discovered and stderr says so; covered instances are unaffected.

Calls: 2 + jobs. Any failure of call 1 exits 2, so existing instances are kept.

### 3.4 Collection (60 minutes, BatchScript)

1. The instance list again, built by the same preamble helper as discovery
   (`pveBackupInstances`): `/cluster/backup`, `included_volumes` per job and `not-backed-up`. It
   carries each job's `enabled`, `next-run`, `storage` and volumes.
2. `/cluster/resources?type=node` — the online nodes.
3. **Runs.** One `/nodes/{node}/tasks?typefilter=vzdump&limit=50` per online node, merged and
   sorted newest first, at most 50 runs in all. Per-node `try`; a node whose list cannot be read
   marks the result incomplete.
4. **Logs, newest first, until done.** Each run's log is read whole, 1000 lines a page
   (`start` / `limit`). The run's storage is the `--storage` on its `starting new backup job`
   line. `Starting Backup of VM <id>` opens a guest, `archive file size: <n><unit>B` gives its
   size (binary units), `Finished Backup of VM <id>` records a success and `ERROR: Backup of VM
   <id> failed` a failure, each at the task's `starttime`. Reading stops as soon as every job
   guest has a success on its job's storage — normally after the newest run. A log that cannot be
   read marks the result incomplete and stops reading.
5. **Per instance.** The newest success for the guest on the job's storage gives
   `LastBackupAgeHours` and `LastBackupSizeGB`; `FailedSinceLastBackup` is 1 when a failure for
   the guest on that storage is newer. A run without `--storage` (the node's default) matches any
   job, and a job without a storage matches any run. When the result is incomplete, those three
   are withheld for every guest rather than guessed.

Calls: 3 + jobs + online nodes + runs read (normally one). Nothing grows with guest count.

### 3.5 Limitations (worded for `technicalNotes`)

- A guest whose last good backup is older than the newest 50 runs reads as having none.
- Proxmox Backup Server runs log no archive size, so `LastBackupSizeGB` is empty there.
- A run that fails before reaching any guest names none; `LastBackupAgeHours` catches it once it
  passes its threshold.

## 4. Tests (harness)

Fixtures built around the lab's job and the lab's shapes: two jobs (a daily one on `local`
pinned to `pve1`, guests typed `qemu` / `lxc` with a bind mount; a disabled weekly one on
`pbs-store`, typed `VM` / `VM`), a removed guest of type `unknown`, two uncovered guests, and five
runs listed out of order: the newest failed for one guest past line 50 of its log, two older
successful daily runs, a successful weekly run with no size line, and an oldest failed weekly run.

Assertions:

- instances = (job, guest) pairs + uncovered guests, with the wildvalues, group values, types and
  volume properties of §3.1;
- `LastBackupAgeHours` and `LastBackupSizeGB` from the guest's newest success on the job's
  storage, including a success inside a run that failed for another guest; no size for the PBS
  run;
- `FailedSinceLastBackup` 1 only where a newer run to the same storage failed for the guest; 0
  for a failure on another storage or older than the last success;
- logs read newest first, each whole, and the oldest run's log never requested; no storage
  listing; no request to an offline node;
- an unreadable task list or log leaves the three last-backup datapoints empty, and collection
  still exits 0;
- discovery without `not-backed-up` still finds the job instances; discovery exits 2 when
  `/cluster/backup` fails; collection exits 2 on a dead API.

## 5. Documentation and dashboard

- `modules/Proxmox_VE_Backups.json` replaces `modules/Proxmox_VE_BackupCoverage.json`; the module
  count stays fourteen DataSources.
- README coverage table, CLAUDE.md module table, DESIGN §4 (Tier 2 row; Tier 2a's BackupStatus
  folded in) and §7 (a new row).
- INSTALL: replacing the old module is a delete-then-import, as for `Proxmox_VE_Cluster`, then
  re-import the Tier 2 dashboard.
- Tier 2 dashboard: the "Backup Coverage" tile becomes "Backups" — guests not backed up (sum of
  `NotCovered`), failed since last backup (sum of `FailedSinceLastBackup`) and the latest
  backups' total size (sum of `LastBackupSizeGB`). The numbers widget only sums, so there is no
  "oldest backup" figure.

## 6. Portal verification before merging

1. ~~A failed guest, first~~ — done 2026-10-25; the results are in §2 and set the parser and
   the fixture.
2. Delete `Proxmox_VE_BackupCoverage`, import `Proxmox_VE_Backups`, run Active Discovery: one
   group for the job with guests 100, 101, 102, 103, 105 and 106, and a `No backup job` group
   with every other guest.
3. After a collection: `LastBackupAgeHours` matches the time since the last 21:00 run,
   `LastBackupSizeGB` matches the size that run logged for each guest, and a guest with a failed
   run after its last good backup reads `FailedSinceLastBackup` 1 until its next good backup.
4. Re-import the Tier 2 dashboard and count its widgets.

## 7. Follow-up: storage listing where the token may see backups

Where an end user grants the token `Datastore.AllocateSpace` on the backup storages and
`VM.Backup` on `/vms` (a custom role holding just those two), the storage listing adds what the
logs cannot: each guest's backup count, whether the newest is protected, and its Proxmox Backup
Server verification state. The module would ask `/access/permissions` which privileges its token
holds before listing, so "not allowed" is never mistaken for "no backups", and add those
datapoints only where the listing is permitted. Nothing the log-based design reports changes.
