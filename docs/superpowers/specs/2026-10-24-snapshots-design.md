# Snapshots: how many each guest has, and how old the oldest is

Status: built and portal-verified 2026-10-24 (DESIGN §7). The Tier 3 module (DESIGN §4), after the overlap check dropped
ballooning and pressure.

## 1. Goal

One module, `Proxmox_VE_Snapshots`, that answers for every guest, with the suite's read-only
`PVEAuditor` token:

- how many snapshots it has;
- how old its oldest snapshot is;
- how many of them saved the VM's RAM as well as its disks.

Snapshot sprawl fills storage with nothing alerting. A snapshot lives on the guest's own storage, so
it is not a backup and is not covered by `Proxmox_VE_Backups`. Guests can't see their own snapshots,
and no built module reads them (DESIGN §4, overlap check).

## 2. Evidence

From the published API schema (`apidoc.js`):

- `GET /nodes/{node}/qemu/{vmid}/snapshot` and `GET /nodes/{node}/lxc/{vmid}/snapshot` list a
  guest's snapshots. Each entry has `name`, `description`, `parent` and, for a real snapshot,
  `snaptime` (Unix time). QEMU entries also carry `vmstate` (1 when RAM was saved). Needs
  `VM.Audit` on the guest, which `PVEAuditor` has.
- The list always includes a synthetic entry named `current`. It stands for the running state,
  has no `snaptime` and is not a snapshot. Counting it would give every guest at least one
  snapshot.
- The call is made on the node that holds the guest. A guest on an offline node cannot be asked.

Lab-read on 9.2.3 (Node-A, 2026-10-24):

- VM 106, with two RAM snapshots: each real snapshot has `name`, `description`, `snaptime` and
  `vmstate`, both as **numbers** (`"snaptime" : 1792899553`, `"vmstate" : 1`); the newer one carries
  `parent` naming the older. The `current` entry has `description` "You are here!", `digest`,
  `running` and `parent`, and **no `snaptime`**, so it is told apart by `name == 'current'`.
- Container 100: `pct snapshot` refused with "snapshot feature is not available", because its
  root filesystem is on storage without snapshot support. Its list held only `current`. Such a
  guest reports `SnapshotCount` 0, which is true.
- Container 103, with two snapshots: the same fields as a VM's minus `vmstate`, `snaptime` a
  number. The list is not in age order (the older snapshot came first here, the newer on the VM),
  so the oldest age is the smallest `snaptime`, never the first entry.

## 3. Design

### 3.1 Instances: one per guest, grouped by node

The module reuses the guest discovery `Proxmox_VE_GuestPerformance` uses, `Proxmox_VE_Guests.ad`,
built on `pveGuestList` and `pveGuestDiscover`.

- **Same instances.** Every guest gets the same wildvalue (`qemu-106`, `lxc-100`), display name and
  description, including the root-disk size, as its Guest Status and Guest Performance instances.
- **No templates.** Templates are excluded, as everywhere in the suite.
- **No ERIs.** This discovery carries no instance ERIs; only Guest Status does.
- **Grouped by node.** `instanceGroupProperty: auto.pve_node`. A migrated guest moves to its new
  node's group at the next discovery and keeps its history.
- **Hourly discovery** (`discoveryInterval: 60m`), the same as the other guest modules.

### 3.2 Datapoints

| Datapoint | Meaning | When it's withheld |
|---|---|---|
| `SnapshotCount` | Snapshots the guest has, not counting `current` | When the guest's snapshot list could not be read |
| `OldestSnapshotAgeDays` | Days since the oldest snapshot's `snaptime`, rounded to 2 decimals | When the guest has no snapshots, or the list could not be read (`conditional`) |
| `SnapshotsWithRAM` | Snapshots that saved the VM's RAM (`vmstate` 1) | On containers, which cannot save RAM, and when the list could not be read (`conditional`) |

None ships with a threshold. How many snapshots, and how old, is too many depends on the site, and
some sites keep a long-lived "golden" snapshot on purpose. The `technicalNotes` suggest starting
points: `OldestSnapshotAgeDays > 7`, `SnapshotCount > 5`.

`OldestSnapshotAgeDays` is withheld rather than 0 when there are no snapshots, because 0 would read
as "a snapshot taken just now". `SnapshotsWithRAM` matters because a RAM snapshot also stores the
VM's full memory, which can be many GB per snapshot.

### 3.3 Graphs

| Graph | Lines |
|---|---|
| Snapshots | `SnapshotCount` (blue), `SnapshotsWithRAM` (orange) |
| Oldest Snapshot Age | `OldestSnapshotAgeDays` (blue), in days |

### 3.4 Collection (60 minutes, BatchScript)

1. `pveGuestList()` gives the guests (1 call). `/cluster/resources?type=node` gives the online
   nodes (1 call).
2. For each guest on an online node, read `/nodes/{node}/{type}/{vmid}/snapshot`, inside a
   **per-guest try**. A failure withholds that guest's three datapoints and is logged to stderr.
3. **Per-node circuit breaker**, as in Guest Status's discovery. After one failed read on a node,
   that node's remaining guests are skipped this run. Without it, each guest would spend a
   `pve.api.timeout`. stderr gets one line per node, not per guest.
4. Ignore the entry named `current`. Count the rest. The oldest age is now minus the smallest
   `snaptime`, in days; an entry without `snaptime` still counts but doesn't set the age. On QEMU,
   count the entries with `vmstate` truthy.
5. Exit 0 when the per-guest reads fail and the two list calls succeeded. Exit 2 when either list
   call fails.

**Cost:** 2 calls, plus 1 per guest on an online node, every hour. This is the suite's third hourly
cost that grows with the guest count, after `Proxmox_VE_Topology` and Guest Status's discovery. At
500 guests that is about one request every 7 seconds, averaged over the hour.

### 3.5 Limitations (worded for `technicalNotes`)

None expected, provided the lab confirms the response shape in §2: a container and a VM, with and
without snapshots, can all be tested there. If something can't be confirmed, it gets a
`Limitation:` sentence here and in the notes.

## 4. Tests (harness)

- **Fixtures:**
  - `/nodes/pve1/lxc/<id>/snapshot` and `/nodes/pve1/qemu/<id>/snapshot` for the pve1 guests:
    - one guest with no snapshots (only `current`);
    - one with three snapshots of known ages, one of them with `vmstate`;
    - one whose read returns an error.
  - `/cluster/resources?type=node` already exists.
- **Assertions:**
  - `current` is never counted.
  - Counts and oldest ages are correct; ages are built relative to now, as in the Migrations tests.
  - `OldestSnapshotAgeDays` is absent for the guest with no snapshots.
  - `SnapshotsWithRAM` is absent on every container.
  - A failing guest withholds only its own datapoints.
  - After a failure, the breaker skips that node's remaining guests.
  - No call is made for a guest on pve2, the offline node, or for a template.
  - Exit 2 when `/cluster/resources?type=vm` fails.
- **Mutation:** count `current`, and confirm a test fails.

## 5. Documentation and dashboard

- **Counts drift:** fifteen DataSources and 19 modules, in README, INSTALL, DESIGN §4/§7 and
  CLAUDE.md. `check_doc_counts` catches the numbered phrases.
- **Module table:** a CLAUDE.md table row (`batchscript`, 60m, `Guests.ad`, 2 + 1 per guest on an
  online node), and its cost added to the O(guests) paragraph under the table. A README coverage row.
- **INSTALL:** Snapshots needs nothing beyond the token.
- **DESIGN §4:** status column → built. **DESIGN §7:** a row recording what was verified.
- **Dashboard:** a Tier 2 table tile, "Snapshots", listing each guest's `SnapshotCount`,
  `SnapshotsWithRAM` and `OldestSnapshotAgeDays`, sorted oldest first. Optional; decide at build time.

## 6. Portal verification before merging

1. Import the module. Discovery lists every non-template guest, grouped by node, with the same names
   as Guest Status.
2. With no snapshots anywhere: `SnapshotCount` 0 on every guest; `OldestSnapshotAgeDays` and
   `SnapshotsWithRAM` empty.
3. Take a snapshot of a container and a RAM snapshot of the VM:
   - after the next collection, both show `SnapshotCount` 1;
   - both show a small `OldestSnapshotAgeDays`;
   - the VM shows `SnapshotsWithRAM` 1, and the container shows nothing for it.
4. Delete the snapshots. The counts return to 0 and the ages go empty.

Only the scheduled collection stores values; Poll Now shows them without storing them. For a quick
test, set the collection interval to a few minutes, then set it back to 60.
