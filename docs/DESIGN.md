# Proxmox VE LogicModule suite — design

Target: a module suite publishable to the LM Exchange, working unchanged from a single-node
homelab to a multi-hundred-node enterprise cluster, with coverage comparable to LogicMonitor's
VMware vSphere, Hyper-V, XenServer and Nutanix suites.

Everything below is derived from the published Proxmox API schema (454 GET endpoints) and from
LogicMonitor's own module conventions as observed in `logicmonitor/dashboards` and
`logicmonitor/monitoring-recipes`.

---

## 1. What the parity targets look like

Mined from LogicMonitor's own published dashboards, which reference real module and datapoint
names: VMware vSphere, Nutanix and Hyper-V. Three conventions came from them and are still
enforced here:

1. **`Vendor_Product_Component` module names**, with human display names ("Proxmox VE Guest
   Performance").
2. **Capacity, performance and status are separate modules**, not one fat module. VMware splits
   datastore capacity from datastore throughput, and VM performance from VM status; different
   collection intervals and alert thresholds naturally belong to each.
3. **PascalCase datapoint names.** Anything else would be flagged in Exchange review.

---

## 2. The one API call that changes the architecture

`GET /cluster/resources` returns, in a single request, every node, guest and storage object in the
cluster with `type`, `id`, `node`, `vmid`, `storage`, `name`, `status`, `cpu`, `maxcpu`, `mem`,
`maxmem`, `memhost`, `disk`, `maxdisk`, `netin`, `netout`, `diskread`, `diskwrite`, `uptime`,
`template`, `tags`, `pool`, `lock`, `level`, `shared`, `plugintype` and `hastate`. It works
identically on a standalone host. `memhost` and `level` were returned and discarded until Tier 1a
in §4 put them to use; storage `enabled`, once thought to be here, is not (see §4).

So Nodes, Guest Performance, Guest Status and Storage Capacity are all served by **one** call per
interval, as BatchScripts. On a 500-guest cluster that is 1 request per interval instead of ~1000.

**BatchScript does not cost us metric fidelity.** It changes *where* the loop lives, not what the
API returns — the same fields, at the same interval, at the same resolution. The real fidelity
limits are in the Proxmox API itself, and they apply equally to either collection method:

- Proxmox exposes **no per-guest storage latency or IOPS**. `diskread`/`diskwrite` are byte
  counters. Nutanix's `citAvgLatencyUsecs` / `vmReadIOPerSecond` have no Proxmox equivalent. This
  is a platform gap, not a design choice.
- Per-vDisk and per-vNIC breakdown is not in `/cluster/resources`. It requires per-guest config
  calls, and belongs in a separate module on a long interval if we want it at all.
- **QEMU reports no used-disk figure** — `maxdisk` only. LXC reports `disk`. VM disk usage needs
  the guest agent, exactly as VMware needs VMware Tools.

`GET /cluster/metrics/export` was left open as a possible second-phase source. **Resolved: it is a
narrower set than `/cluster/resources`, not a richer one.** `PullMetric.pm` defines exactly what it
emits — `cpu_current`, `cpu_max`, `cpu_avg1/5/15`, `cpu_iowait`, `mem_used`, `mem_total`,
`swap_used`, `swap_total`, `disk_used`, `disk_total`, `net_in`, `net_out`, `uptime` — per object,
keyed `node/<name>`, `qemu/<vmid>`, `lxc/<vmid>` or `storage/<node>/<sid>`. No ballooning, no PSI,
no HA, no snapshots.

It is worth adopting for exactly two things `/cluster/resources` cannot supply: **node network
counters** and **node `cpu_iowait`**. One useful property — each entry carries its own metric type
as `gauge`, `counter` or `derive`, which maps directly onto the datapoint `type` field in §5. It
needs `Sys.Audit` on `/`, and takes `local-only`, `node-list`, `start-time` and `history`.

### API details that bite

Verified against the published API schema (454 endpoints).

Base `https://host:8006/api2/json`. Auth header is exactly
`Authorization: PVEAPIToken=USER@REALM!TOKENID=SECRET` — `=` after `PVEAPIToken`, not `-`; the
credential property holds the whole `user@realm!tokenid=secret`, so the header is
`'PVEAPIToken=' + apiToken`. Every endpoint this module uses has
`allowtoken: 1`, so API tokens work throughout — but a token with **privilege separation enabled**
gets its own ACL and will 401/403 until permissions are granted to the token itself, not just the
user. Responses are always enveloped as `{"data": ...}`.

Required privileges: `Sys.Audit` on `/` for `/cluster/status`, `Sys.Audit` on `/nodes/{node}` for
node status and rrddata, `VM.Audit` on `/vms/{vmid}` for guest status, and `Datastore.Audit` on
`/storage/{storage}` for storage status. `PVEAuditor` on `/` with propagate covers all of them.
`/nodes`, `/nodes/{node}/qemu` and `/cluster/resources` are `user: all` — they silently return only
what the token may see, so an under-privileged token produces *empty discovery*, not an error.

Schema details:

- Don't use `rrddata` for current values. It returns an averaged historical series, and guest
  `status/current` already carries the network and disk counters.
- **QEMU guests have no used-disk figure.** `/nodes/{node}/qemu` and qemu `status/current` expose
  `maxdisk` but no `disk`; only LXC reports `disk`. `GuestPerformance` therefore emits
  `DiskUsedGB` / `DiskUsagePercent` for LXC only (both `conditional`), leaving VMs at no-data.
  That is Proxmox behaviour, not a bug to fix — real VM disk usage needs the guest agent.
- `template: true` marks templates. They are never running, so discovering them creates instances
  that alert as permanently down. Filter them out of guest discovery.
- `/nodes/{node}/status` declares `additionalProperties: 1` — `uptime`, `swap`, `ksm` and friends
  are returned but absent from the published schema, so don't "correct" code that reads them.
- `loadavg` items are typed **string**, not number. Convert with `toDouble()` before arithmetic and
  before emitting — an unconverted string reaches LogicMonitor as no-data and fails the harness.
- Storage list entries carry `active`, `enabled` and `used_fraction`; storage `status` omits
  `used_fraction`. A disabled or inactive storage still discovers, so `up` should reflect `active`.
- **Backup files are invisible to `PVEAuditor`.** A storage's content listing drops, without an
  error, every backup file the token may not access, and access to a backup needs
  `Datastore.AllocateSpace` on the storage and `VM.Backup` on the guest (`pve-storage`,
  `check_volume_access`). So a read-only token sees an empty listing. Backup results come from
  vzdump task logs instead, which `Sys.Audit` covers.
- **`included_volumes` types guests `qemu` / `lxc`**, not the `VM` / `CT` its schema describes
  (lab, 2026-10-25).
- **A vzdump task is a whole run**, with an empty `id`; which guest failed is only in its log, and
  `/log` returns 50 lines unless `limit` is raised.

---

## 3. Instance IDs: use Proxmox's own `id`

**The original `qemu__<node>__<vmid>` scheme was broken for clusters.** It embeds the node, so the
moment a guest migrates — vMotion equivalent, HA failover, or a manual move — the wildvalue changes.
LogicMonitor deletes the old instance and discovers a new one: alert history gone, SDTs gone,
thresholds gone, and a spurious "instance deleted" churn on every migration. In an HA cluster that
is constant.

Proxmox already publishes a stable, cluster-unique identifier as `id` on every `/cluster/resources`
row: `qemu/101`, `lxc/200`, `storage/pve1/local`, `node/pve1`. VMIDs are unique cluster-wide and do
not change on migration. `/` is legal in a wildvalue (only space, colon, `=`, `\` and `#` are not).

Use that `id` as the wildvalue, set `useWildValueAsUniqueIdentifier`, and carry the current node as
an *instance property* (`auto.pve_node`) which is allowed to change freely. Storage keeps the node
in its id because a non-shared storage genuinely is per-node.

**As implemented, the `id` is folded rather than taken verbatim.** `pveWildValue` replaces anything
outside `[A-Za-z0-9_-]` with `-`, so `qemu/101` becomes `qemu-101` and `storage/pve1/local` becomes
`storage-pve1-local`. That fold is required by BatchScript output rather than by LogicMonitor's
wildvalue character rules: `.` separates the instance from the datapoint in
`instance.datapoint=value`, and a storage name may legally contain one. The property that actually
matters — that the id does not change when a guest migrates — survives the fold intact.

---

## 4. Proposed module suite

Everything below is sorted on **two independent axes**. Conflating them is what produced this
backlog's original blind spot, so they are kept apart deliberately.

**Scope tier** answers *is this worth monitoring* — judged against the parity targets in §1, and
against what someone actually running guests asks for. Tier 1 is core parity, Tier 2 is the
surfaces the parity suites have that we do not, Tier 3 is detail only some sites want.

**Cost tier** answers *what does it cost to collect*, in API calls per collection interval:

| Cost | Meaning | Examples |
|---|---|---|
| **O(1)** | One call, any cluster size | `/cluster/resources`, `/cluster/status`, `/cluster/ha/status/current`, `/cluster/backup-info/not-backed-up`, `/cluster/ceph/status`, `/cluster/metrics/export` |
| **O(nodes)** | One call per node | `/nodes/{node}/status`, `/nodes/{node}/tasks`, services, certificates, disks |
| **O(guests)** | One call per guest | per-guest `status/current`, `snapshot` |

Cost decides architecture; scope decides priority. An O(1) addition can usually join a module that
already makes the call. An O(guests) addition must be its own module, on its own interval, disabled
by default — at 500 guests on a 5m interval that is ~1.7 requests/second sustained against
`pvedaemon`, and a QEMU `status/current` is a QMP round trip into the VM, not a cheap read. §2
exists because the first implementation did exactly this.

**O(nodes) is not O(guests).** Node counts are small and grow slowly; guest counts are neither.
NodeDetail is already O(nodes) and that is fine.

### Tier 1 — core parity (built, and collecting against a live cluster)

| Module | Display name | Source | Cost | Notes |
|---|---|---|---|---|
| `Proxmox_VE_Cluster` | Proxmox VE Cluster | `/cluster/status` + `/cluster/ha/status/current` + `/cluster/resources` | O(1) | One instance, the cluster (wildvalue `cluster`), which on a cluster carries the cluster's topology key. Quorum, expected vs actual votes, node count, HA manager state, and the Tier 1a allocation rollups. On a standalone host `ClusterConfigured` reads 0 and the quorum datapoints are withheld. |
| `Proxmox_VE_Nodes` | Proxmox VE Nodes | `/cluster/resources?type=node` | O(1) | BatchScript. CPU, memory, uptime, online state. |
| `Proxmox_VE_NodeDetail` | Proxmox VE Node Detail | `/nodes/{node}/status` | O(nodes) | Per-node Script. Load average, swap, rootfs — the fields `/cluster/resources` omits. CPU count, memory and uptime stay in `Proxmox_VE_Nodes` only, and the subscription flag in `Proxmox_VE_Subscription` only: each datapoint lives in one module. |
| `Proxmox_VE_GuestPerformance` | Proxmox VE Guest Performance | `/cluster/resources?type=vm` | O(1) | BatchScript. CPU, memory, network rates, disk IO rates. QEMU + LXC. |
| `Proxmox_VE_GuestStatus` | Proxmox VE Guest Status | same call | O(1) | BatchScript. Power state, HA state, lock state, uptime. |
| `Proxmox_VE_StorageCapacity` | Proxmox VE Storage Capacity | `/cluster/resources?type=storage` | O(1) | BatchScript. Used/total/percent, active, shared. |

### Tier 1a — fields already fetched and thrown away

Not new modules. These were omissions inside the six above: the responses were already being
parsed and these fields discarded. No new endpoint, no new call, no interval change. Each is a
`pveEmit` line plus a datapoint declaration. **All built, and verified live on 2026-09-10** (§7).

| What | Field | Belongs in | Why it matters |
|---|---|---|---|
| **Total allocated vCPU** | sum `maxcpu` over non-template guests | Cluster | The rollup the suite has no answer for today |
| **Total assigned memory** | sum `maxmem` over non-template guests | Cluster | As above |
| **vCPU / memory overcommit ratio** | those sums over node-row `maxcpu` / `maxmem` | Cluster | The form people actually alert on, rather than raw totals |
| Guest host-side memory | `memhost` on guest rows | GuestPerformance | `mem` vs `memhost` is the ballooning delta at the resolution `/cluster/resources` can express — turns ballooning from invisible into visible, for free |
| Node IO wait | `wait` in `/nodes/{node}/status` | NodeDetail | Arguably the most diagnostic node metric currently missing |
| KSM page sharing | `ksm.shared`, same response | NodeDetail | Memory reclaimed by dedup |
| Sockets and cores | `cpuinfo.sockets`, `cpuinfo.cores` | NodeDetail | We emit total threads only |
| HA service detail | `crm_state`, `request_state`, `max_restart`, `max_relocate`, `failback`, `group` | Cluster | Already fetched; only `state` is read. A service stuck between requested and actual state is invisible today |
| HA fencing armed | `fencing` entry type, `armed-state` enum (`armed`/`standby`/`disarming`/`disarmed`) | Cluster | A cluster whose fencing is not armed is a cluster that will not recover |
| Node subscription level | `level` on node rows | Nodes | Most of Tier 2's `Proxmox_VE_Subscription`, free, minus expiry |

**Not free, despite appearances.** Storage `enabled` — which would distinguish an administratively
disabled store from a broken one — is *not* on `/cluster/resources`; `Cluster.pm`'s declared return
schema carries `content`, `shared` and `plugintype` but no `enabled`. That field is on
`/nodes/{node}/storage`, making it O(nodes), not free. Same for storage `used_fraction`.

### Tier 2 — infrastructure surfaces the parity suites have and we do not

All eight are built and **all eight were verified against a real cluster on 2026-09-22** — a
three-node cluster with Ceph, which also exercised the five that a single node cannot: Ceph,
CephOSD, Replication, Subscription, and the `wearout` direction in Disks. That last one was the
highest-consequence unknown in the suite, because a backwards threshold would have alerted on
healthy disks and stayed silent on dying ones; it reads as life *remaining*, as shipped.

| Module | Source | Cost | Status | Rationale |
|---|---|---|---|---|
| `Proxmox_VE_Backups` | `/cluster/backup`, `/cluster/backup/{id}/included_volumes`, `/cluster/backup-info/not-backed-up`, `/nodes/{node}/tasks?typefilter=vzdump` and the runs' logs | O(jobs + nodes), plus one log per run read (normally one) | built, not verified in a portal | Backup jobs and the guests each covers, their included and excluded volumes, each guest's last good backup (age, size) and failures since, and guests no job covers — one instance per guest per job, grouped by job, with only `PVEAuditor`. Replaces `Proxmox_VE_BackupCoverage`. Design: `docs/superpowers/specs/2026-10-25-backups-design.md`. |
| `Proxmox_VE_Certificates` | `/nodes/{node}/certificates/info` | O(nodes) | built, **verified live** 2026-09-22 | Days until `notafter`, one instance per certificate: the cluster CA, `pve-ssl` and `pveproxy-ssl` expire independently. |
| `Proxmox_VE_NodeServices` | `/nodes/{node}/services` | O(nodes) | built, **verified live** 2026-09-22 | `pveproxy`, `pvedaemon`, `corosync`, `pve-cluster` systemd state — reported as three separate notions, see the module's notes. |
| `Proxmox_VE_Disks` | `/nodes/{node}/disks/list` | O(nodes) | built, **verified live** 2026-09-22 | Physical disk SMART `health`, size, wearout. `health` defaults to `UNKNOWN` and `wearout` is the string `N/A`; both are withheld rather than zeroed. |
| `Proxmox_VE_Ceph` | `/cluster/ceph/status` | O(1) | built, **verified live** 2026-09-22 | Ceph is the Proxmox equivalent of vSAN. The endpoint returns an untyped passthrough of `ceph status`, so every field is read defensively. |
| `Proxmox_VE_Replication` | `/nodes/{node}/replication` | O(nodes) | built, **verified live** 2026-09-22 | Job failures and replica staleness. Note the endpoint is the *node* one; `/cluster/replication` is `ReplicationConfig`, the job definitions, not their status. |
| `Proxmox_VE_Subscription` | `/nodes/{node}/subscription` | O(nodes) | built, **verified live** 2026-09-22 | Renewal date. The status half is already free from the `level` field in Tier 1a; this module exists for `nextduedate`. |
| `Proxmox_VE_CephOSD` | `/nodes/{node}/ceph/osd` | **O(1)** | built, **verified live** 2026-09-22 | Per-OSD up/in, fill, latency, CRUSH weight. Costed as O(nodes) when this table was written, which was wrong: the endpoint returns the whole cluster-wide CRUSH tree whichever node is asked, so one call covers every OSD. The tree is walked rather than assumed to be any particular depth. |

### Tier 2a — workload surfaces

The list above was written from "what do the competitor suites monitor". It is entirely
infrastructure-facing, and it misses what an operator running guests asks for. These fill that gap,
and all of them preserve the architecture.

Both are now part of built modules:

Backup status — age of the last good backup and failed runs — is folded into `Proxmox_VE_Backups` (Tier 2 row above) rather than built as its own module.

**Migrations are folded into existing modules: built 2026-10-25.** A migration is its own task — `vzmigrate` for a container, `qmigrate` for a VM — with the guest's VMID as its `id` and its own `status`, recorded on the node it started from (lab-read). `Proxmox_VE_Cluster` gains `MigrationsRunning` from `/cluster/tasks`, which lists running tasks cluster-wide but keeps only about 25 recent tasks of every type, and `MigrationsCompletedLastHour` / `MigrationsFailedLastHour` from each online node's own history (`typefilter`, `limit=100`), so it now costs 4 + 2 per online node every 5 minutes. `Proxmox_VE_GuestStatus` gains `Migrating` from the `migrate` lock it already reads, at no cost. A separate module would have repeated the Cluster module's single instance for three counts.

### Might want, not needed

Not on the build list. Each has a reason to exist but duplicates something most deployments already have.

| Module | Source | Cost | Why it waits |
|---|---|---|---|
| `Proxmox_VE_NodeNetwork` | `/cluster/metrics/export` | O(1) | Node rows in `/cluster/resources` carry no network counters, but a node monitored over SNMP already gets per-interface traffic from LogicMonitor's standard interface module, in more detail. Only a node without SNMP gains anything. The schema does not list the export's metric names, so `net_in` / `net_out` are unconfirmed. |

### Tier 3 — per-guest detail, throttled

Everything here is O(guests). It belongs in **one** module, on a 15m-or-longer interval, in the same
spirit as VMware splitting VM Disk Capacity out of VM Performance. It must
never be folded into Guest Performance at 5m: that trades away the property that makes this suite
work at scale, in exchange for metrics that change slowly, such as snapshots.

**Overlap check: done 2026-10-24.** Each surface was checked against what the built modules emit,
so no metric is collected twice. What remains is snapshots, as one module, `Proxmox_VE_Snapshots`:
**built 2026-10-24** (spec: `docs/superpowers/specs/2026-10-24-snapshots-design.md`).

| Surface | Source | Caveat |
|---|---|---|
| Snapshot count and age of oldest | `/nodes/{node}/{qemu,lxc}/{vmid}/snapshot` | No built module reads snapshots, and in-guest monitoring cannot see them. Snapshot sprawl silently consumes storage until a datastore fills. The response carries a synthetic `current` entry that must be filtered out, or every guest reports at least one snapshot. |

**Pressure (PSI) is dropped.** On 9.2.3 the six `pressure*` fields are on each guest's
`status/current` and nowhere cheaper: not on `/cluster/resources`, and not on `/nodes/{node}/status`
(lab-read), so neither Guest Performance nor Node Detail can carry them for free. Pressure is a short
rolling average, so it means something only when sampled every few minutes, and one call per guest
every few minutes is the cost this suite is built to avoid (§2). A node monitored over SSH can still
read `/proc/pressure` through a Linux module, outside this suite.

**Ballooning is dropped.** Guest Performance already emits `mem`, `maxmem` and `memhost`, and with the
balloon driver loaded Proxmox derives `mem` from the balloon's own free-memory figure, so `freemem`
and `maxmem` from `status/current` would be collected twice. The balloon's current target is the
only new figure, and one value does not justify a per-guest QMP round trip that fails on every guest
without the driver.

### Supporting modules

- **`addCategory_Proxmox_VE` (PropertySource)** — probes the API and sets `system.categories` to
  include `ProxmoxVE`. Every module then uses `AppliesTo: hasCategory("ProxmoxVE")`.
  **This is required for Exchange.** It replaced the pre-rebuild design, which made the user
  hand-set `pve.monitor=true` on every resource, as no published module does. It ships as an
  importable module like the rest.
- **`Proxmox_VE_Topology` (TopologySource)** and **`addERI_Proxmox_VE`** — built 2026-09-22,
  verified in a portal 2026-09-23 (§7). Cluster → node → guest edges, so Proxmox appears in topology
  maps the way vSphere does, and a node's alerts can explain its guests'. A guest matches its own
  resource on the MAC of its first virtual NIC, which both sides already know. A node cannot:
  Proxmox exposes no MAC or hardware UUID for a node anywhere in its API, so `addERI_Proxmox_VE`
  stamps a synthesised key on the node's resource and `pveTopoKey` is the single definition of its
  shape. Modelled on `VMware_vSphere_VirtualMachine_Topology`, which identifies a VM the same way.
  **Never optional:** an ERI module cannot be turned off cleanly, because its keys outlive it (§7),
  and LogicMonitor's own topology modules are not optional either. `Proxmox_VE_Topology` applies on `hasCategory("ProxmoxVE")`;
  `addERI_Proxmox_VE` on `hasCategory("ProxmoxVE") || pve.topology.node || (system.sysinfo =~ "-pve"
  && !(auto.network.mac_address =~ "^[bB][cC]:24:11") && !pve.guest.mac_prefix)` — a Proxmox kernel
  finds a token-less node, and the guest MAC prefix keeps out LXC containers, which report their
  host's kernel. `addERI_Proxmox_VE` is not gated on `hasCategory("ProxmoxVE")` alone, because every node needs its
  key while only one node per cluster should carry the token (INSTALL §4.1). (Nor are
  `addCategory_Proxmox_VE`, which sets that category, or `addERI_Proxmox_VE_Guest`, which runs on
  guests.) Where the resource has a token it asks `/cluster/status`; where it does not,
  `pveTopoNodeIdentity` reads `pve.topology.cluster` and takes the node name from
  `pve.topology.node` or the hostname.
  - **Name-plus-IP node keys: built 2026-10-20**
    (`docs/superpowers/specs/2026-10-20-topology-node-ip-keys-design.md`). Each node vertex
    carries `proxmoxve--node--<name>--<ip>` from its `/cluster/status` row, and only that key (the
    cluster-scoped one only where the row has no address; see §7, 2026-10-21), and `addERI_Proxmox_VE`
    stamps the same key from every address in `system.ips`, so a token-less node matches without
    `pve.topology.cluster` and one group can hold every cluster's nodes. The cluster vertex is drawn
    through the spec's one-instance `Proxmox_VE_Cluster`, first dropped on spike 3 and restored
    after its re-run drew the cluster to every node (§7, 2026-10-22).
  - **Instance groups by node: built 2026-10-22.** GuestStatus, GuestPerformance, Disks,
    NodeServices, Certificates and StorageCapacity group their instances by `auto.pve_node`
    (`groupMethod: ilp`), so a 12-node cluster's guests, disks and units read per node.
  - **Guests without SNMP, and guest labels: built 2026-10-19**, replacing the follow-up that was
    here. `Proxmox_VE_GuestStatus`'s discovery puts each guest's MAC (or
    its `pveTopoKey` fallback) on the guest's instance as `predef.externalResourceID`, so every guest
    is drawn whether or not it is a resource or answers SNMP; and the always-on
    `addERI_Proxmox_VE_Guest` labels a Linux or Windows guest that is its own resource
    `VirtualMachineResource` by its Proxmox MAC prefix (AppliesTo
    `(auto.network.mac_address =~ "^[bB][cC]:24:11" || pve.guest.mac_prefix) && (isLinux() || isWindows())`;
    the OS clause, added at final review 2026-10-20, keeps virtual appliances on their own ERIs). Design and evidence:
    `docs/superpowers/specs/2026-10-19-guest-topology-labels-design.md`; what is proven is in §7.
  - **Storage edges (guest → Proxmox storage): not built; the end user validates them.** Which Proxmox storage holds a guest's
    disks (Ceph RBD, NFS, iSCSI, ZFS) is visible only through the Proxmox API, so no other source
    can draw these edges; a guest's own mounts and shares are drawn by other modules already, and
    must not be duplicated (CLAUDE.md, "Draw only relationships no other source can see").
    - *Data: free.* The guest `config` the TopologySource already reads for the MAC names each
      disk's storage (`scsi0: ceph-pool:vm-101-disk-0`), so the edges cost no extra calls.
    - *Storage vertices need a carrier.* A vertex is drawn only if it matches something, as the
      cluster vertex does through `Proxmox_VE_Cluster`'s instance. The natural carriers are
      `Proxmox_VE_StorageCapacity`'s instances, and `Proxmox_VE_Ceph`'s one instance for the Ceph
      cluster.
    - *Shared storage is the hard part.* StorageCapacity discovers a shared store once per node
      (`storage/pve1/nfs`, `storage/pve2/nfs`), so one key would sit on several instances — the
      same multi-match as the token on several nodes. Which instance stands for the store has to
      be decided first.
    - *Validated by the end user*: the lab has no Ceph and no shared storage.
  - **Backups from the storage listing, where permitted: not built.** Where the token is given
    `Datastore.AllocateSpace` on the backup storages and `VM.Backup` on `/vms`, the listing adds
    each guest's backup count, protection and PBS verification to `Proxmox_VE_Backups`. Ask
    `/access/permissions` first, so "not allowed" is never read as "no backups". Spec §7.
  - **One guest-config read per hour, not two: not built; revisit after testing at scale.** `Proxmox_VE_Topology` and
    `Proxmox_VE_GuestStatus`'s discovery each read every online guest's `config` hourly for the
    same MAC, so the suite's only O(guests) cost is paid twice. Both run on the token holder's
    Collector, so one could hand the other its MAC map — the likely route is the Collector's
    ScriptCache, written by whichever runs first with a TTL just over an hour, and read by the
    other with the API as the fallback on a miss. Unproven: that ScriptCache is shared between a
    TopologySource and a DataSource's discovery, and how it behaves on a Collector failover.
    Halves the per-guest calls; changes no output, so the harness's key assertions still hold.

---

## 5. Datapoint conventions

PascalCase, with metric type chosen deliberately:

| Datapoint | Type | Source field |
|---|---|---|
| `CPUUsagePercent` | gauge | `cpu * 100` |
| `MemoryUsagePercent` | gauge | `mem / maxmem * 100` |
| `MemoryUsedMB`, `MemoryCapacityMB` | gauge | `mem`, `maxmem` |
| `DataRateRxMB`, `DataRateTxMB` | **derive** | `netin`, `netout` |
| `DiskReadRateMB`, `DiskWriteRateMB` | **derive** | `diskread`, `diskwrite` |
| `UpTimeSeconds` | gauge | `uptime` |
| `Status` | gauge (status) | `running` → 1 |
| `UsedPercent`, `CapacityGB`, `FreeGB` | gauge | storage |

`netin`/`netout`/`diskread`/`diskwrite` are **cumulative counters since guest start**. Configured as
gauge — as the pre-rebuild module was — they graph as an ever-climbing total instead of throughput,
which was the single most visible defect in it. As `derive` with a minimum of 0, a guest restart's
counter reset is discarded rather than producing a negative spike. Implemented; see §6 item 3.

---

## 6. Decisions taken in the rebuild (historical)

**Every item in this section is implemented.** It reads as a to-do list because it was one, in the
rebuild that produced the current Tier 1; it is kept as the record of *why* each decision went the
way it did, since several of them are load-bearing and cheap to undo by accident. Nothing here is
outstanding work — for that, see §4. Item 3's `derive` choice and item 2's instance IDs in
particular are the two most expensive to reverse: changing an ID scheme orphans every discovered
instance in an end user's portal.

1. Collection method → BatchScript for the four `/cluster/resources`-backed modules.
2. Instance IDs → Proxmox `id`, so migration doesn't destroy instances.
3. `netin`/`netout`/`diskread`/`diskwrite` → `derive`, not gauge.
4. Filter `template: true` out of guest discovery — templates are never running and would alert as
   permanently down.
5. Drop the per-guest `rrddata` call. `status/current` already carries the counters, and `rrddata`
   returns an averaged *historical* series, which is the wrong thing to report as a current value.
6. Replace manual `pve.monitor` with a PropertySource-set category.
7. Datapoint names → PascalCase.
8. Ship a real importable module `.json`, not the hand-written `logicmodule.json` manifest.
9. Add a `//!/lib-groovy/v4` header so the runtime is pinned rather than inferred.

   `com.santaba.agent.groovyapi.http.HTTP` was considered and **not** adopted. It is what
   LogicMonitor's reference DataSources use, but it is a Collector-only class: depending on it
   would make every script impossible to compile or execute outside a Collector, which would cost
   us the compile check and the mock-API test harness that now cover this suite. Plain
   `HttpURLConnection` also gives per-request control over timeouts and the TLS trust manager,
   which the opt-in `pve.api.insecure` path needs. This is a deliberate departure from house
   style, in exchange for the scripts being testable.

   **That rule has exactly one exception, added with the TopologySource.** `Proxmox_VE_Topology`
   and `addERI_Proxmox_VE` both work through `com.logicmonitor.mod.Snippets`, because the ERI and
   topology output formats are produced by LogicMonitor's own `lm.topo` snippet — `eriPreProcessor`,
   `isMac`, `emitEri`, `printEriArray`, `generateTopology` — and are not documented well enough to
   reimplement. Hand-writing them would mean inventing a format, which is the failure this suite
   has actually suffered. So those two take the dependency, the build emits them to
   `dist/scripts/collector-only/` where `groovyc` and the harness cannot reach them, and their
   Proxmox-side logic lives in preamble helpers that the harness *can* reach.
10. Handle Proxmox paging/permission behaviour: `/cluster/resources` is `user: all` and silently
    returns only what the token may see, so an under-privileged token yields *empty discovery*
    rather than an error. Discovery must distinguish "no permission" from "nothing there".


---

## 7. Build status

The single record of what is proven and what is not. Nothing else in the repository restates it.

| | |
|---|---|
| Modules built | 15 DataSources, 3 PropertySources, 1 TopologySource |
| Scripts assembled | 26 compiled on Groovy 4, plus 3 Collector-only that cannot be |
| Harness checks | 1053, against a mock Proxmox API |
| Portal import | **verified** for all 18 — the 14 changed since `fb8aa9b`, `addERI_Proxmox_VE_Guest` among them, re-imported 2026-10-20 |
| Real Proxmox host | **verified** — Tier 1 collecting since 2026-09-10, Tier 2 on 2026-09-22 against a three-node cluster with Ceph |
| Tier 1a datapoints | **verified** — 16 added, reporting as expected, 2026-09-10 |
| Tier 1 dashboard | **verified** — 20 of 20 widgets, 2026-09-22. It had silently imported 16 since 2026-09-15; see below |
| Tier 2 dashboard | **verified** — 15 of 15 widgets, populating with live data, 2026-09-22 |
| TopologySource, ERI PropertySource | **verified** — both import, and node and guest vertices resolve to their own resources, 2026-09-23; QEMU guests (Windows and Linux) join by MAC too, 2026-10-19 |
| Node Detail on online-only discovery | **verified** — re-imported 2026-09-23; existing instances and their history kept, since both discovery bodies emit the node's `id` |
| PropertySource property names | **verified** — `addCategory_Proxmox_VE` prints bare `pve.version` / `pve.release` / `pve.clustered`; the portal stores them as `auto.pve.*`, matching LogicMonitor's docs. Docs corrected 2026-10-14 |
| `Proxmox_VE_Backups` | **verified** 2026-10-25 — on Node-A with the token holding only `PVEAuditor`: discovery puts the job's six guests (100–103, 105, 106) in the group `backup-4e5f5bfc-094c` and Webapp01 (104) in `No backup job`, with the volume counts (automation01 2 included; nvr01, Media01 and Kiosk01 1 excluded each). Collection: `LastBackupAgeHours` 18.41 for the guests last backed up by the 21:00 run and 1.29 for audio01, whose newest backup was a later manual run; `LastBackupSizeGB` 3.77 for audio01 (logged `3.51GB`, binary) and 7.41 for nvr01, matching its 7,407,222,279-byte archive; Media01 `FailedSinceLastBackup` 1 after a failed run newer than its last good backup; Webapp01 `NotCovered` 1. The Tier 2 dashboard re-imports with 15 widgets, and its Backups tile fills in once the module has stored data (Poll Now stores none) |
| Migrations (`Proxmox_VE_Cluster`, `Proxmox_VE_GuestStatus`) | **portal-verified** on a two-node cluster: Cluster `MigrationsRunning` 1 during a migration and 0 after, `MigrationsCompletedLastHour` 1 after it, `MigrationsFailedLastHour` 1 after a migration refused for a locked guest. Guest Status `Migrating` portal-verified: 1, with `Locked` 1, on the moving container during its migration, and both 0 after; every other guest read 0 |
| `Proxmox_VE_Snapshots` | **portal-verified** 2026-10-24: imported, every non-template guest discovered and grouped by node under its Guest Status name; `SnapshotCount`, `OldestSnapshotAgeDays` and `SnapshotsWithRAM` (VMs only) collected as designed, and followed a snapshot taken and deleted. Response shapes lab-read on 9.2.3: `snaptime` and `vmstate` as numbers, `current` without `snaptime`, no `vmstate` on a container, list not in age order |
| stderr on a successful run | **portal-confirmed: not shown.** Poll Now and Test Script on `Proxmox_VE_Cluster` left `errOutput` empty while `MigrationsFailedLastHour` read 1, from the branch that writes the failed guests to stderr. End-user text therefore points at Proxmox's own Task History, Datacenter > Replication and Ceph views, never at the collection log |
| Instance graphs | **portal-verified** 2026-10-24 on all fifteen DataSources: each graph shows only its own lines, percentages on their own 0-100 graphs, colours distinct within every graph. Storage and OSD instances carry their size in the display name and guests and nodes their root disk or root filesystem in the description, after discovery |
| Alert messages on 17 datapoints | **for end-user review** — added 2026-10-13 (`4d78666`) across Ceph, CephOSD, Certificates, Disks, GuestPerformance, NodeDetail, Nodes, Replication and Subscription; local checks only. To review: confirm a raised alert shows its message with `##INSTANCE##` / `##VALUE##` / `##HOST##` filled in |
| Topology AppliesTo | **verified** 2026-10-25 — `Proxmox_VE_Topology` applies on `hasCategory("ProxmoxVE")`; `addERI_Proxmox_VE` on the token, `pve.topology.node`, or a `-pve` kernel without a guest MAC; GuestStatus and Cluster discovery always carry their keys. The selector's inputs: `system.sysinfo` carries `-pve` and `PMX` on `Node-A` and `Node-B` and on the LXC guests `Nvr01` and `Music Assistant`, whose `auto.network.mac_address` starts `bc:24:11` and the nodes' does not (`e0:51:d8…`, `dc:0e:a1…`); a Raspberry Pi's kernel (`6.18.39+rpt-rpi-v8`) has neither. After re-import, Test AppliesTo on `addERI_Proxmox_VE` matches exactly `Node-A` and `Node-B` (no guest, no Pi), and on `Proxmox_VE_Topology` the same two, each holding its own token (`Node-B` is standalone, `auto.pve.clustered=false`); with no topology property anywhere, the Cluster instance carries `proxmoxve--labcluster`, Nvr01's Guest Status instance carries `bc:24:11:0e:00:03` as type `VirtualMachine`, and the map draws correctly |
| Stamped ERI keys after the module stops applying | **verified** 2026-10-25 — they **persist**: with `addERI_Proxmox_VE` no longer applying to `Node-B` from 21:15 to 11:56 the next day, longer than a full cycle of every other ERI module on the resource (`addERI_Linux`, 12 hours, also run at 30 minutes in that window), its `predef.externalResourceID` still carried `proxmoxve--node--node-b--192-0-2-211` and `proxmoxve--node-b--node-b`. When the module applies again it re-stamps them. `predef.externalResourceID` is an auto property the portal will not let a user delete |
| `addERI_Proxmox_VE_Guest` | **verified** 2026-10-20 (guest topology labels spec §6 item 3) — Debian-test (Linux VM, SNMP), the Windows Server guest (VMID 200, WMI) and the LXC containers Nvr01, Music-Assistant and Kiosk01-Display (SNMP) read `VirtualMachineResource`, so the `isLinux()` / `isWindows()` clause matches all three kinds; the Pi and a Proxmox node resource stay `PhysicalServer`. Three of the LXC labels were still `PhysicalServer` at first look and landed on their own — the module runs every 30 minutes and the map redraws hourly, so allow both before calling a label missing |
| GuestStatus instance ERIs | **verified** 2026-10-20 (spec §6 items 4-6) on `Node-A` and `Node-B`, both standalone: every guest is drawn under its node, including ones with no resource of their own; the portal decodes discovery values (`auto.pve_id` reads `qemu/201`), so `predef.externalResourceID` stores `bc:24:11:0e:00:06` with its colons; and turning the flag off and re-running discovery removes both instance properties. Unproven (item 7, not reachable in this lab): the token on several nodes of one cluster, which puts the same key on several instances |
| Cluster-scoped node key (`pve.topology.cluster`) | **limitation, not testable** — a node vertex uses the fallback key `proxmoxve--<cluster>--<node>` only when `/cluster/status` reports no usable address for that node. Every cluster member has a corosync address, so no real cluster reaches it. The harness covers `pveTopoNodeIdentity` and `pveTopoNodeVertexKey`; the scripts have not run that path against a portal. Every node with an address uses the name+IP key below |
| Node name+IP keys | **partly verified** — built 2026-10-20; the harness covers the helpers, including the round trip from a `/cluster/status` address to `system.ips`, but cannot run either script. 2026-10-22: spec §6 item 2 holds on the two-node cluster `Labcluster` — the token-less `Node-B`, with no `pve.topology.cluster` anywhere, carries `proxmoxve--node--node-b--192-0-2-211` and is drawn with a Compute edge to its guest `Debian-Test` (see below). Unproven: item 5, two clusters in one group, not reachable in this lab |
| Cluster vertex (`Proxmox_VE_Cluster` instance ERI) | **verified** — built 2026-10-22; the harness covers the discovery (key, type, standalone, namespace, API down). 2026-10-22, on `Labcluster` with the spike module deleted: Active Discovery on `Node-A` gives one instance `Labcluster` carrying `predef.externalResourceID=proxmoxve--labcluster` and type `Cluster`; after a topology run the map draws `Labcluster` with Cluster edges to `Node-A` and the token-less `Node-B`, each with its guests under it (eight under A; `Debian Test` and the instance-only `CT-Test` under B). The Tier 1 dashboard, reimported, has 20 widgets and its cluster tiles read data from the multi-instance module. Unproven: a standalone host, which should get no key (harness only). Importing it over the single-instance version failed with "Field value cannot be modified" (no field named); after deleting the old module the import succeeded (2026-10-22), so `multiInstance` cannot change on an existing module. INSTALL §3.2 carries the upgrade step |
| Instance groups by node | **verified** — 2026-10-22, GuestStatus grouped by hand in the portal with Instance Level Property `auto.pve_node`: guests show under their node's group, and `CT-Test`, migrated, moved group and kept its history; the export carries `groupMethod: ilp`, `groupExpr: auto.pve_node`, which the build now writes for six modules. Later the same day the six built modules, imported, group their instances under `Node-A` and `Node-B`. 2026-10-23, user-confirmed: an LXC guest migrated twice under the built modules, and its instances followed it to the new node's group each time. Unproven: scale (deferred, below) |

**The TopologySource took one portal round trip.** The first import, on 2026-09-23, was refused with
`non empty field value required`, naming no field. The build had emitted `collectionAttrs` as an
object holding only `scriptgroovy`; LogicMonitor's own exports carry it as a JSON-encoded *string*
of eight keys, `scripttype: "embed"` among them, with `type` and `collectionIntervalSec` as integers
rather than strings. Matching that shape fixed it — the same lesson as the dashboard values: copy
the reference's types, not just its key names. It also settled that an import does not need the
`registryMetadata` / `integrationMetadata` lineage blocks. Once imported, both identity schemes held
on a standalone host (`Node-A`): the node resource carries `proxmoxve--node-a--node-a` from
`addERI_Proxmox_VE` and its map vertex opens that resource, and the vertex for the LXC guest `Media01`
opens the separately monitored `Media01 Media Server` resource, whose ERI is the guest's MAC
`bc:24:11:0e:00:02` — no naming convention involved.

**A two-node cluster, 2026-10-20.** `Node-A` and `Node-B` were joined into one cluster, with
`pve.topology.cluster` set on their group. `Proxmox_VE_Cluster` reads `ClusterConfigured` 1 with two
nodes, and the map draws both nodes, each with its own guests under it. **The cluster vertex is not
drawn** (it is since 2026-10-22, through `Proxmox_VE_Cluster`'s instance; see the row above), and so neither are its Cluster edges: `pveTopoKey(cluster, null)` matches no resource and no
instance, and the portal drops a vertex that matches nothing, just as it drops a guest with no MAC.
The VMware comparison in the TopologySource's header ("matches nothing on purpose") does not hold
here. Which node held the token during this check was not recorded, so the token-less path is still
unproven (row above).

**QEMU guests join the same way, 2026-10-19.** A QEMU guest's MAC is read from the `virtio=` form
rather than LXC's `hwaddr=`, and two QEMU guests on `Node-B`, each monitored as its own resource,
now draw as that node's children: VMID 200 (Windows 11 Pro over WMI, then rebuilt as Windows Server,
ERI `bc:24:11:0e:00:04`) and a Debian 13 VM over SNMP (ERI `bc:24:11:0e:00:01`). Windows'
built-in ERI PropertySource reports the MAC lowercase, the form `pveGuestMac` emits, so the two keys
compare equal.

**Every guest is drawn as `PhysicalServer`, and that is LogicMonitor's labelling, not this suite's.**
Both QEMU guests, both editions of Windows and the LXC guest `Nvr01` (CT 100) all carry
`predef.externalResourceType = PhysicalServer`, whatever their `system.sysinfo` says. LogicMonitor
labels a resource `VirtualMachineResource` only when its MAC's prefix is on the VM prefix list in
its own topology code (`00:50:56`, `00:0c:29`, `00:15:5d`, `52:54:00`, `50:6b:8d`, read from a
Collector), and Proxmox's default `bc:24:11` is not on it. A physical Raspberry Pi on the same
portal is `PhysicalServer` too, correctly, and is the case any fix must leave alone. Fixing the
label is open design work; the evidence for it is the properties of these guests: Windows over WMI
reports `auto.endpoint.manufacturer = QEMU`, a Linux VM over SNMP reports nothing comparable, and
an LXC guest's `sysinfo` shows the host's `-pve` kernel.

That design work has since been done: spike 1 below found which ERI sets the type, and
`addERI_Proxmox_VE_Guest` (built 2026-10-19, portal-verified 2026-10-20 in the table above) labels
Linux and Windows guests `VirtualMachineResource` by their Proxmox MAC prefix.

**Spike 1, 2026-10-19 — which ERI sets the resource type.** A throwaway ERI PropertySource on
Debian-test (VMID 201) emitted one key in category `virt.proxmoxve` with type
`VirtualMachineResource`. At priority 1 the key merged into `predef.externalResourceID` and the
type stayed `PhysicalServer`; at priority 3 the type became `VirtualMachineResource`, and the guest
stayed a child of `Node-B` on the map, drawn as a VM. So the type comes from the
highest-priority entry *across* categories — the built-in `net.l2` entry is priority 2 — while keys
from every category merge into the ID. `addERI_Proxmox_VE_Guest` is built on this.

**Spike 2, 2026-10-19 — a vertex can resolve to a DataSource instance.** LogicMonitor's
`VMware_vSphere_VirtualMachineStatus` sets the instance properties `predef.externalResourceID`
(the VM's MACs) and `predef.externalResourceType = VirtualMachine` in Active Discovery. A throwaway
DataSource did the same for two guests: the ping-only `Relay01` (CT 103 on `Node-A`), which
had never been drawn, was drawn under its node; `Debian-test`, which has its own resource with the
same MAC, stayed drawn once and correctly. GuestStatus's instance ERIs are built on this. Both
spike modules are deleted at the portal check (spec §6 item 1).

**Spike 3, 2026-10-21 — a cluster vertex drawn from an instance reaches only its own resource.** A
throwaway DataSource on the token holder `Node-A` discovered one instance carrying
`predef.externalResourceID = proxmoxve--labcluster`, the TopologySource's cluster key. With
`predef.externalResourceType = Cluster` the map draws the vertex `Labcluster` with LogicMonitor's
cluster icon; `VirtualCluster` draws it with the unknown-type "??" icon. Either way it is drawn with
a Cluster edge to `Node-A` only. The TopologySource emits the same edge to `Node-B`, which is
drawn with its guests (so its key matches), yet that edge appears neither on the "Proxmox VE" dynamic
map nor on `Node-B`'s own map. One cluster, one observation: an instance vertex is drawn with
edges to the resource that holds the instance and to no other. That is consistent with spike 2,
whose instance vertices were all guests of the instance's own resource. A cluster vertex joined to
one node of many would misdraw the cluster, so `Proxmox_VE_Cluster` stays single-instance (spec §3.4
dropped) and the cluster vertex stays undrawn. Test Script of the TopologySource exits 2 on
`Node-B` and succeeds on `Node-A`, consistent with only `Node-A` holding the token; the
error text was not recorded. The rule does **not** hold: it was an artefact of the two-key mismatch, and
the re-run below draws the cluster to every node.

**Spike 3 re-run, 2026-10-22 — the cluster vertex is drawn to every node.** The first run was
confounded: it came before the one-key fix, when `Node-B`'s node vertex carried two joined keys
it did not hold, so the Cluster edge to `Node-B` had nothing to land on (its guests looked drawn
because `Node-B` itself held their instances). The same throwaway DataSource, re-imported onto
`Node-A` with one instance carrying `predef.externalResourceID = proxmoxve--labcluster` and
`predef.externalResourceType = Cluster`, now draws `Labcluster` with the cluster icon and Cluster
edges to both `Node-A` and `Node-B`, each node with its own guests below: Compute edges from
`Node-B` to `Debian-Test` and `CT-Test`, from `Node-A` to its seven. So an instance vertex takes
edges from any matched resource, whatever it stands for, and the TopologySource's existing Cluster
edges need only a real module to carry the key. Spike module deleted after the check.

**A vertex with two keys matches only a resource carrying both, 2026-10-21.** The first import of
the name-plus-IP keys gave each node vertex both keys, the cluster-scoped and the IP one, on the
spec's assumption that a vertex matches a resource carrying any one of them. Test Script showed
lm.topo joining them into a single ID,
`proxmoxve--labcluster--proxmox-b_proxmoxve--node--node-b--192-0-2-211`. With
`pve.topology.cluster` removed, `Node-B` carried only the IP key — correctly, with no key from
its `fe80:` address — and was left unmatched: on the map without an edge, its guests gone.
`Node-A`, holding the token and so both keys, still matched. A node vertex now carries one key
(`pveTopoNodeVertexKey`), and the harness refuses a second.

**A token-less node draws its own guests, 2026-10-22.** On `Labcluster`, `Node-A` holds the token
and runs the TopologySource; `Node-B` holds none and carries only the name-plus-IP key. Its guest
`Debian-Test` was added as an SNMP resource, so its `predef.externalResourceID` carries its MAC
`bc:24:11:0e:00:05`, while `Node-A`'s GuestStatus instance for the same guest carries the same
MAC. After one topology run the map draws `Node-B` with a Compute edge to `Debian-Test`. So an
edge between two resources, neither of them the one running the TopologySource, is drawn; and where a
resource and an instance on another resource carry the same key, the edge reaches the resource.

A guest that is only an instance is drawn under its own node too. `CT-Test` (CT108), a new container
on `Node-B` with no resource of its own, got a GuestStatus instance on `Node-A` carrying its MAC.
After the next topology run the "Proxmox VE" map and `Node-B`'s own map both draw `Node-B`
with Compute edges to `CT-Test` and `Debian-Test`, and `Node-A` with its own seven guests. So on a
cluster every guest is drawn under the node it runs on, with no guest resource and no per-node token.
Not shown: a second cluster.

The first runs of this check failed for a configuration reason worth keeping. `Node-B` had
`pve.topology.node = 192.0.2.211`, copied from the Name column of `pvecm status`'s membership list,
which shows each member's corosync ring address and not its node name. `pveTopoNodeName` then took that
property as given and the key cut it at the first dot, so the resource carried
`proxmoxve--node--192--192-0-2-211` and matched nothing. The node name is the one `pvecm nodes`
and `/etc/pve/nodes` show. Deleting the property let `system.sysname` (`Node-B`) supply it. `pveTopoNodeName` now skips an
address in the property the same way, so the mistake falls back to `system.sysname`.

**The portal draws only vertices that match a resource or, as spike 2 later showed, a DataSource
instance.** What follows is the record of 2026-09-23. Test Script on `Node-A` emitted all
seven guests' edges, but the map drew four. The unmonitored LXC guest `Webapp01` was not drawn
at all, so a guest reaches the map only when it is its own resource *and* that resource's
`predef.externalResourceID` carries the guest's MAC — which LogicMonitor's own discovery supplies
over SNMP. Two monitored guests, `Relay01` and the QEMU `automation01`, were also left off the
map, and for the same reason: neither answers SNMP, so neither resource carries
`predef.externalResourceID`, `auto.network.mac_address` or `auto.snmp.operational` at all. The
module is behaving correctly in both cases; what a guest needs to reach the map is on the guest's
own resource, not in this suite.

Five things are for end-user review. Each needs something this lab cannot produce, or is left
to the end user (user rulings, 2026-10-22, 2026-10-23 and 2026-10-25):

- **Alert messages on 17 datapoints** — that a raised alert shows its message with
  `##INSTANCE##`, `##VALUE##` and `##HOST##` filled in (the row above).

- **Two clusters in one topology group** (spec §6 item 5) — deferred until an end user with a
  second cluster. Until then the name+IP key's claim to cover any number of clusters rests on the
  harness and on one cluster.
- **`Proxmox_VE_Cluster`'s HA datapoints** need a real failover, not merely an HA cluster. Not
  reproducible in this lab; for end-user review.
- **`Proxmox_VE_Disks` with unreadable SMART** — a disk whose `health` reads `UNKNOWN` must report
  `SmartHealthKnown=0` and *no* `SmartHealthOK` at all. That withholding is what stops an
  unreadable disk alerting exactly like a failing one. The 2026-09-22 live check covered real SSD
  and NVMe disks on `Node-A`, all with readable SMART: it settled the wearout direction, not
  this branch. A USB disk or one behind a hardware RAID controller usually reads `UNKNOWN`; none is
  available in this lab (2026-10-23), so for end-user review.
- **A cluster large enough to matter.** The bulk-endpoint design is argued from the API's shape in
  §2, not measured at scale. For end-user review.

**A standalone host with a stopped guest** is verified (2026-10-23). On `Node-B`, standalone and
holding the token, with its only guest `Debian Test` powered off, the map draws `Node-B` with a
Compute edge to `Debian Test`, the guest marked dead: guests are filtered by their node's state and
by `template`, not by their own, so a node's down guests stay drawn under it.

**A token-less standalone node** with no `pve.topology.cluster` is verified (2026-10-23):
`addERI_Proxmox_VE` stamps exactly one Proxmox key, `proxmoxve--node--node-b--192-0-2-211`,
from `system.sysname` and `system.ips`, beside the resource's own MAC and Docker ERIs, and no
cluster-scoped key; the type stays `PhysicalServer`.

**The dashboard's two unenforced conventions are now portal-confirmed.** A widget addresses a
module by the plain string `"<displayedAs> (<name>)"`, and these widgets legend on
`##INSTANCE##` rather than the `##HOSTNAME##` LogicMonitor's own VMware and Hyper-V dashboards
use — a departure made because this suite puts every node, guest and storage object on a single
resource as instances, where those suites give each hypervisor its own resource. Either being
wrong would have produced a dashboard that imported cleanly and rendered empty tiles, which is
the same shape of defect as the `##WILDVALUE##` post-processor bug below and equally invisible to
every local check. All twenty widgets carry real data, so both conventions hold. The Tier 2
dashboard (`dashboards/Proxmox_VE_Tier2.py`) is built on them.

**The dashboards lost widgets at import, silently, twice over.** On 2026-09-22 a portal discarded
nine of Tier 2's fifteen widgets and four of Tier 1's twenty, reporting only `Some widgets could
not be created due to incompatible version or configuration errors` — once, on first load, naming
no widget. Both dashboards imported, the survivors rendered correctly, and the grid floated the
rest upward to close the gaps, so Tier 1 had looked complete since 2026-09-15. A portal export is
what showed it. Every casualty carried a value found nowhere in the reference exports:
`displayType: "number"` on a column, `topX: 20` on a cgraph, `topX` of `50`/`100` on a table,
`colorThresholds: null`, and column bounds other than `0..100`. `build/dashboards.py` now pins
each of those, mutation-tested one field at a time. Tier 2's description was also 266 characters
and the portal had truncated its last sentence, hence the 256-character check.

The same day, Tier 2 was imported one module rebuild too early and lost exactly one widget — the
one naming `SizeGB`, a datapoint the portal's copy of the module did not yet have. Re-importing the
module first brought it to 15 of 15. A dashboard's modules must be imported before the dashboard.

The harness is mutation-tested: reintroducing the template-discovery bug and the QEMU
used-disk bug both make it fail, so a green run means something.

**The live portal caught what nothing local could.** Every batchscript module imported, discovered
its instances, and returned correct output from Test Collection Script while recording No Data on
every datapoint of every instance. The cause was the datapoint post-processor key: `build.py` was
emitting a bare `CPUUsagePercent`, which matches a Script DataSource's output but never a
BatchScript's, where one stream carries every instance and each key is prefixed with an instance id.
The key has to be `##WILDVALUE##.CPUUsagePercent`. Node Detail was the only module collecting, and
only because it is the one Script-method module in the suite.

That is worth recording as a boundary rather than a footnote: the build, the compile check and the
harness all validate what the *scripts* emit, and none of them validate how the *module JSON* tells
LogicMonitor to parse it. The build now checks post-processor keys against the collection method,
but the class of defect — module metadata that is wrong in a way no local check can see — is still
only reachable from a portal.

Tier 1a went the other way: 16 datapoints across four modules, verified locally by build, compile,
harness and a hand-check of the rollup arithmetic against the fixtures, and they landed in the
portal with no corrections needed. The difference is that Tier 1a changed only *what the scripts
emit*, which is exactly the layer the local checks do cover.

Tier 2 (Ceph, CephOSD, Replication, BackupCoverage, NodeServices, Subscription, Certificates,
Disks) is built — §4's status column carries each module's state. Tier 2a and Tier 3 are
designed above but not built.
