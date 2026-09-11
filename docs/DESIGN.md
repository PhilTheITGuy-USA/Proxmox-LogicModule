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
`maxmem`, `disk`, `maxdisk`, `netin`, `netout`, `diskread`, `diskwrite`, `uptime`, `template`,
`tags`, `pool` and `hastate`. It works identically on a standalone host.

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

`GET /cluster/metrics/export` is worth evaluating as a second-phase source: it returns a
timestamp-sorted metric series designed for external metrics servers and may give better resolution
than polling `/cluster/resources`. Not in the initial build.

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

Use that `id` verbatim as the wildvalue, set `useWildValueAsUuid`, and carry the current node as an
*instance property* (`auto.pve_node`) which is allowed to change freely. Storage keeps the node in
its id because a non-shared storage genuinely is per-node.

---

## 4. Proposed module suite

### Tier 1 — core parity

| Module | Display name | Source | Notes |
|---|---|---|---|
| `Proxmox_VE_Cluster` | Proxmox VE Cluster | `/cluster/status` + `/cluster/ha/status/current` | Single-instance. Quorum, expected vs actual votes, node count, HA manager state. Auto-disables on standalone. |
| `Proxmox_VE_Nodes` | Proxmox VE Nodes | `/cluster/resources?type=node` | BatchScript. CPU, memory, uptime, online state. |
| `Proxmox_VE_NodeDetail` | Proxmox VE Node Detail | `/nodes/{node}/status` | Per-node Script. Load average, swap, rootfs — the fields `/cluster/resources` omits. |
| `Proxmox_VE_GuestPerformance` | Proxmox VE Guest Performance | `/cluster/resources?type=vm` | BatchScript. CPU, memory, network rates, disk IO rates. QEMU + LXC. |
| `Proxmox_VE_GuestStatus` | Proxmox VE Guest Status | same call | BatchScript. Power state, HA state, lock state, uptime. |
| `Proxmox_VE_StorageCapacity` | Proxmox VE Storage Capacity | `/cluster/resources?type=storage` | BatchScript. Used/total/percent, active, shared. |

### Tier 2 — enterprise surfaces the parity suites have and we currently don't

| Module | Source | Rationale |
|---|---|---|
| `Proxmox_VE_Ceph` | `/cluster/ceph/status` | Ceph is the Proxmox equivalent of vSAN. Health state, PG states, capacity. Mandatory for enterprise credibility. |
| `Proxmox_VE_CephOSD` | `/nodes/{node}/ceph/osd` | Per-OSD in/out/up/down, fill percentage. |
| `Proxmox_VE_Replication` | `/cluster/replication` + `/nodes/{node}/replication/{id}/status` | Storage replication job failures and lag. |
| `Proxmox_VE_BackupCoverage` | `/cluster/backup-info/not-backed-up` | Count of guests covered by no backup job. A compliance metric with no VMware equivalent — a genuine differentiator. |
| `Proxmox_VE_NodeServices` | `/nodes/{node}/services` | `pveproxy`, `pvedaemon`, `corosync`, `pve-cluster` systemd state. |
| `Proxmox_VE_Subscription` | `/nodes/{node}/subscription` | Support level and expiry. Enterprise ops cares; trivial to collect. |
| `Proxmox_VE_Certificates` | `/nodes/{node}/certificates/info` | Days until `notafter`. |
| `Proxmox_VE_Disks` | `/nodes/{node}/disks/list` | Physical disk SMART `health`, size, wearout. |

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
gauge (as the current README instructs) they graph as an ever-climbing total instead of throughput,
which is the single most visible defect in the module today. As `derive` with a minimum of 0, a
guest restart's counter reset is discarded rather than producing a negative spike.

---

## 6. Things that must change from the current implementation

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

Tier 1 and the PropertySource are implemented, assembled and verified, and as of 2026-09-10 all
six DataSources are collecting against a live cluster.

| | |
|---|---|
| Modules built | 6 DataSources + 1 PropertySource |
| Scripts compiled | 12, on Groovy 4.0.33 (the Collector's runtime) |
| Harness checks | 415, against a mock Proxmox API |
| Portal import | **verified** — all six import and apply via `hasCategory("ProxmoxVE")` |
| Real Proxmox host | **verified** — all six collecting, 2026-09-10 |

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

Tier 2 (Ceph, CephOSD, Replication, BackupCoverage, NodeServices, Subscription, Certificates,
Disks) and the TopologySource are designed above but not built.
