# Proxmox VE LogicModule suite — design

Target: a module suite publishable to the LM Exchange, working unchanged from a single-node
homelab to a multi-hundred-node enterprise cluster, with coverage comparable to LogicMonitor's
VMware vSphere, Hyper-V, XenServer and Nutanix suites.

Everything below is derived from the published Proxmox API schema (454 GET endpoints) and from
LogicMonitor's own module conventions as observed in `logicmonitor/dashboards` and
`logicmonitor/monitoring-recipes`.

---

## 1. What the parity targets actually look like

Mined from LogicMonitor's official dashboards, which reference real module and datapoint names:

| Suite | Modules | Datapoint style |
|---|---|---|
| VMware vSphere | `VMware_vSphere_Clusters`, `VMware_vSphere_VirtualMachinePerformance`, `VMware_vSphere_VirtualMachineStatus`, `VMware_vSphere_VirtualMachineDiskCapacity`, `VMware_vSphere_DatastoreUsage`, `VMware_vSphere_DatastoreThroughput`, `VMware_vSphere_NetworkState` | `CpuUsagePercent`, `MemoryUsagePercent`, `DataRateRx`, `DataRateTx`, `vDiskReads`, `PercentUsed`, `Capacity`, `FreeSpace` |
| Nutanix | `Nutanix_Cluster_GlobalStats`, `Nutanix_Hypervisors`, `Nutanix_VirtualMachines`, `Nutanix_Containers`, `Nutanix_StoragePools`, `Nutanix_Controller_VMs` | `hypervisorCpuUsagePercentage`, `vmRxBytes`, `spitUsedPercentage`, `citAvgLatencyUsecs` |
| Hyper-V | `Win_HyperV_VirtualMachines` | `PercentGuestRunTime`, `MeanBytesReceivedPerSec`, `ReadBytesPersec` |

Three conventions to copy:

1. **`Vendor_Product_Component` module names**, with human display names ("VMware vSphere VM Performance").
2. **Capacity, performance and status are separate modules**, not one fat module. VMware splits
   datastore capacity from datastore throughput, and VM performance from VM status. Different
   collection intervals and different alert thresholds naturally belong to each.
3. **PascalCase datapoint names.** The current `cpuPct` / `memUsedPct` / `netInBytes` names are
   off-convention and would be flagged in Exchange review.

---

## 2. The one API call that changes the architecture

`GET /cluster/resources` returns, in a single request, every node, guest and storage object in the
cluster with `type`, `id`, `node`, `vmid`, `storage`, `name`, `status`, `cpu`, `maxcpu`, `mem`,
`maxmem`, `memhost`, `disk`, `maxdisk`, `netin`, `netout`, `diskread`, `diskwrite`, `uptime`,
`template`, `tags`, `pool`, `lock`, `level`, `shared`, `plugintype` and `hastate`. It works
identically on a standalone host. `memhost`, `level` and storage `enabled` are returned today and
not yet emitted — see Tier 1a in §4.

So Nodes, Guest Performance, Guest Status and Storage Capacity are all served by **one** call per
interval, as BatchScripts. On a 500-guest cluster that is 1 request per interval instead of ~1000.

**BatchScript does not cost us metric fidelity.** It changes *where* the loop lives, not what the
API returns — the same fields, at the same interval, at the same resolution. The real fidelity
limits are in the Proxmox API itself, and they apply equally to either collection method:

- Proxmox exposes **no per-guest storage latency or IOPS**. `diskread`/`diskwrite` are byte
  counters. Nutanix's `citAvgLatencyUsecs` / `vmReadIOPerSecond` have no Proxmox equivalent. This
  is a platform gap, not a design choice.
- Per-vDisk and per-vNIC breakdown is not in `/cluster/resources`. It requires per-guest config
  calls, and belongs in a separate opt-in module if we want it at all.
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

---

## 3. Instance IDs: use Proxmox's own `id`

**The current `qemu__<node>__<vmid>` scheme is broken for clusters.** It embeds the node, so the
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
| `Proxmox_VE_Cluster` | Proxmox VE Cluster | `/cluster/status` + `/cluster/ha/status/current` | O(1) | Single-instance. Quorum, expected vs actual votes, node count, HA manager state. Auto-disables on standalone. |
| `Proxmox_VE_Nodes` | Proxmox VE Nodes | `/cluster/resources?type=node` | O(1) | BatchScript. CPU, memory, uptime, online state. |
| `Proxmox_VE_NodeDetail` | Proxmox VE Node Detail | `/nodes/{node}/status` | O(nodes) | Per-node Script. Load average, swap, rootfs — the fields `/cluster/resources` omits. |
| `Proxmox_VE_GuestPerformance` | Proxmox VE Guest Performance | `/cluster/resources?type=vm` | O(1) | BatchScript. CPU, memory, network rates, disk IO rates. QEMU + LXC. |
| `Proxmox_VE_GuestStatus` | Proxmox VE Guest Status | same call | O(1) | BatchScript. Power state, HA state, lock state, uptime. |
| `Proxmox_VE_StorageCapacity` | Proxmox VE Storage Capacity | `/cluster/resources?type=storage` | O(1) | BatchScript. Used/total/percent, active, shared. |

### Tier 1a — fields already fetched and thrown away

Not new modules. These are omissions inside the six above: the responses are already being parsed
and these fields discarded. No new endpoint, no new call, no interval change. Each is a `pveEmit`
line plus a datapoint declaration.

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

All eight are built, and **none has yet been imported into a portal or run against a live host** —
they were written after the 2026-09-10 Tier 1 verification. Four of them could only be written
best-effort in the first place, because a single node cannot exercise them at all — Ceph, CephOSD,
Replication and Subscription. Disks is a fifth, partial case: the
module itself is straightforward, but the *direction* of its `wearout` value has never been checked
against a real SSD, and a threshold that is backwards would alert on healthy disks and stay silent
on dying ones. All five carry an UNVERIFIED note in their own `technicalNotes`, and
`docs/VALIDATION.md` is the checklist for whoever does have the hardware — what to look at, what to
compare it against, and what to send back.

| Module | Source | Cost | Status | Rationale |
|---|---|---|---|---|
| `Proxmox_VE_BackupCoverage` | `/cluster/backup-info/not-backed-up` | O(1) | built, **untested live** | Guests covered by no backup job — returns `vmid`, `type`, `name`; needs `Sys.Audit` on `/`. A compliance metric with no VMware equivalent, and the cheapest high-value item on either list. |
| `Proxmox_VE_Certificates` | `/nodes/{node}/certificates/info` | O(nodes) | built, **untested live** | Days until `notafter`, one instance per certificate: the cluster CA, `pve-ssl` and `pveproxy-ssl` expire independently. |
| `Proxmox_VE_NodeServices` | `/nodes/{node}/services` | O(nodes) | built, **untested live** | `pveproxy`, `pvedaemon`, `corosync`, `pve-cluster` systemd state — reported as three separate notions, see the module's notes. |
| `Proxmox_VE_Disks` | `/nodes/{node}/disks/list` | O(nodes) | built, **partly unverified** | Physical disk SMART `health`, size, wearout. `health` defaults to `UNKNOWN` and `wearout` is the string `N/A`; both are withheld rather than zeroed. |
| `Proxmox_VE_Ceph` | `/cluster/ceph/status` | O(1) | built, **unverified** | Ceph is the Proxmox equivalent of vSAN. The endpoint returns an untyped passthrough of `ceph status`, so every field is read defensively. |
| `Proxmox_VE_Replication` | `/nodes/{node}/replication` | O(nodes) | built, **unverified** | Job failures and replica staleness. Note the endpoint is the *node* one; `/cluster/replication` is `ReplicationConfig`, the job definitions, not their status. |
| `Proxmox_VE_Subscription` | `/nodes/{node}/subscription` | O(nodes) | built, **unverified** | Renewal date. The status half is already free from the `level` field in Tier 1a; this module exists for `nextduedate`. |
| `Proxmox_VE_CephOSD` | `/nodes/{node}/ceph/osd` | **O(1)** | built, **unverified** | Per-OSD up/in, fill, latency, CRUSH weight. Costed as O(nodes) when this table was written, which was wrong: the endpoint returns the whole cluster-wide CRUSH tree whichever node is asked, so one call covers every OSD. The tree is walked rather than assumed to be any particular depth. |

### Tier 2a — workload surfaces

The list above was written from "what do the competitor suites monitor". It is entirely
infrastructure-facing, and it misses what an operator running guests asks for. These fill that gap,
and all of them preserve the architecture.

| Module | Source | Cost | Rationale |
|---|---|---|---|
| `Proxmox_VE_BackupStatus` | `/nodes/{node}/tasks?typefilter=vzdump` with `since` / `statusfilter` / `limit` | O(nodes) | Age of last successful backup, and last backup failed. Coverage says a job exists; this says it worked. Both are needed. Tasks return `upid`, `type`, `id`, `starttime`, `endtime`, `status`, `user`. |
| `Proxmox_VE_Migrations` | same endpoint, migration `typefilter`; `source=active` for in-flight | O(nodes) | Migrations running, and migrations failed in the window. Also the natural place to see an HA failover storm, which the current HA datapoints imply but cannot show. |
| `Proxmox_VE_NodeNetwork` | `/cluster/metrics/export` `net_in` / `net_out` | O(1) | A real blind spot: node rows in `/cluster/resources` carry no network counters at all. See §2. |

### Tier 3 — per-guest detail, opt-in and throttled

Everything here is O(guests). It belongs in **one** module, on a 15m-or-longer interval, shipped
disabled, in the same spirit as VMware splitting VM Disk Capacity out of VM Performance. It must
never be folded into Guest Performance at 5m: that trades away the property that makes this suite
work at scale, in exchange for metrics that are either occasionally useful (snapshots) or frequently
unavailable (balloon).

| Surface | Source | Caveat |
|---|---|---|
| Ballooning, properly | per-guest `status/current` → `balloon` (actual), `ballooninfo`, `freemem`, `balloon_min`, and `maxmem` overwritten from the balloon's own `max_mem` | `QemuServer.pm` notes the balloon query "fails if balloon driver is not loaded, so this must be the last command". A guest without the driver yields nothing, and it must read as no-data — exactly like QEMU used-disk. |
| Snapshot count and age of oldest | `/nodes/{node}/{qemu,lxc}/{vmid}/snapshot` | Snapshot sprawl silently consumes storage until a datastore fills. The response carries a synthetic `current` entry that must be filtered out, or every guest reports at least one snapshot. |
| CPU pressure | `pressurecpusome`, `pressurecpufull` (also `pressureio*`, `pressurememory*`) from `vmstatus()` | See below. |

**There is no CPU ready time in Proxmox.** It is an ESXi scheduler metric; KVM does not expose it
and neither does the PVE API. PSI — time tasks spent stalled waiting for CPU — is a different
measurement doing the same diagnostic job, and is the only honest substitute. Confirm it is
populated on the target version before designing around it.

### Supporting modules

- **`addCategory_Proxmox_VE` (PropertySource)** — probes the API and sets `system.categories` to
  include `ProxmoxVE`. Every module then uses `AppliesTo: hasCategory("ProxmoxVE")`.
  **This is required for Exchange.** The current design makes the user hand-set `pve.monitor=true`
  on every resource, which no published module does.
- **`Proxmox_VE_Topology` (TopologySource)** — guest → node → cluster edges, so Proxmox appears in
  topology maps the way vSphere does. This is a large part of what "hybrid observability" means in
  the LM product.

---

## 5. Datapoint conventions

PascalCase, with metric type chosen deliberately:

| Datapoint | Type | Source field |
|---|---|---|
| `CPUUsagePercent` | gauge | `cpu * 100` |
| `MemoryUsagePercent` | gauge | `mem / maxmem * 100` |
| `MemoryUsedBytes`, `MemoryCapacityBytes` | gauge | `mem`, `maxmem` |
| `DataRateRx`, `DataRateTx` | **derive** | `netin`, `netout` |
| `DiskReadRate`, `DiskWriteRate` | **derive** | `diskread`, `diskwrite` |
| `UpTimeSeconds` | gauge | `uptime` |
| `Status` | gauge (status) | `running` → 1 |
| `UsedPercent`, `Capacity`, `FreeSpace` | gauge | storage |

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
instance in a customer's portal.

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
10. Handle Proxmox paging/permission behaviour: `/cluster/resources` is `user: all` and silently
    returns only what the token may see, so an under-privileged token yields *empty discovery*
    rather than an error. Discovery must distinguish "no permission" from "nothing there".


---

## 7. Build status

Tier 1, Tier 1a, all eight Tier 2 modules and the PropertySource are implemented and assembled.
Tier 1's six DataSources have been collecting against a live cluster since 2026-09-10. The Tier 2
eight were written afterwards and have not yet been through a portal or a live host.

| | |
|---|---|
| Modules built | 14 DataSources + 1 PropertySource |
| Scripts assembled | 26, compiled on Groovy 4 (the Collector's runtime) |
| Harness checks | 959, against a mock Proxmox API |
| Portal import | **verified for Tier 1's six** — they import and apply via `hasCategory("ProxmoxVE")`. Tier 2 is unproven at this layer |
| Real Proxmox host | **verified for Tier 1's six** — collecting, 2026-09-10 |
| Tier 1a datapoints | **verified** — 16 added, reporting as expected, 2026-09-10 |
| Tier 2 | built and green on the harness; **none imported to a portal or run live**. Five carry an explicit UNVERIFIED note, see `docs/VALIDATION.md` |
| Tier 1 dashboard | **verified** — imports, lays out and populates with live data in a portal, 2026-09-15 |
| Tier 2 dashboard | built, 15 widgets, validated by the build; **not yet imported to a portal** |

**The dashboard's two unenforced conventions are now portal-confirmed.** A widget addresses a
module by the plain string `"<displayedAs> (<name>)"`, and these widgets legend on
`##INSTANCE##` rather than the `##HOSTNAME##` LogicMonitor's own VMware and Hyper-V dashboards
use — a departure made because this suite puts every node, guest and storage object on a single
resource as instances, where those suites give each hypervisor its own resource. Either being
wrong would have produced a dashboard that imported cleanly and rendered empty tiles, which is
the same shape of defect as the `##WILDVALUE##` post-processor bug above and equally invisible to
every local check. All twenty widgets carry real data, so both conventions hold. The Tier 2
dashboard (`dashboards/Proxmox_VE_Tier2.py`) is built on them.

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
Disks) is built — §4's status column carries each module's state. Tier 2a, Tier 3 and the
TopologySource are designed above but not built.
