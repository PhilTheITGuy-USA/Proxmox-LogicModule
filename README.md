# Proxmox VE LogicModule suite

LogicMonitor modules for Proxmox VE, built to work unchanged from a single-node host to a
multi-hundred-node enterprise cluster, with coverage comparable to LogicMonitor's VMware
vSphere, Hyper-V and Nutanix suites.

Monitoring is over the Proxmox HTTPS JSON API (`/api2/json`) using a read-only API token.
SNMP can be layered alongside for the underlying Linux hardware.

## Modules

| Module | Collection | Covers |
|---|---|---|
| Proxmox VE Cluster | Script, single instance | Corosync quorum, votes, node counts, HA manager state, cluster-wide vCPU and memory allocation and overcommit |
| Proxmox VE Nodes | BatchScript | Per-node CPU, memory, uptime, online state, subscription |
| Proxmox VE Node Detail | Script, per node | Load average, load per core, swap, root filesystem, IO wait, KSM sharing, socket and core counts |
| Proxmox VE Guest Performance | BatchScript | Per-guest CPU, memory, network and disk throughput (QEMU + LXC) |
| Proxmox VE Guest Status | BatchScript | Power state, configuration lock, HA state |
| Proxmox VE Storage Capacity | BatchScript | Per-storage capacity, free space, availability |
| Proxmox VE Backup Coverage | Script, single instance | Guests that no backup job covers |
| Proxmox VE Certificates | BatchScript | TLS certificate expiry, per certificate per node |
| Proxmox VE Node Services | BatchScript | systemd state of pveproxy, pvedaemon, corosync and the rest |
| Proxmox VE Disks | BatchScript | Physical disk SMART health, size, SSD life remaining |
| Proxmox VE Ceph | Script, single instance | Ceph health, OSD and placement group state, capacity |
| Proxmox VE Ceph OSD | BatchScript | Per-OSD up/in state, fill level, latency, CRUSH weight |
| Proxmox VE Replication | BatchScript | Replication job failures, replica staleness, run duration |
| Proxmox VE Subscription | BatchScript | Subscription status and renewal date, per node |
| Proxmox VE Topology | TopologySource | Cluster, node and guest vertices and the edges between them |
| addCategory_Proxmox_VE | PropertySource | Detects Proxmox and sets the category the suite applies to |
| addERI_Proxmox_VE | PropertySource (ERI) | Gives a node's resource the ERI the topology map attaches to |

**Four modules carry the bulk of the suite on one API call each.** Nodes, Guest Performance,
Guest Status and Storage Capacity each make **one** call per collection interval no matter how
large the cluster is: `/cluster/resources` returns every node, guest and storage object in a
single response. A 500-guest cluster costs one request per interval, not five hundred.

Those four calls are deliberately not consolidated: LogicMonitor executes each DataSource
independently, so there is no dependable cross-DataSource response cache. Combining them into one
DataSource could reduce the count further, but would couple unrelated alerting and collection
intervals. This is the practical low-load boundary while preserving separate modules.

**Nothing in the suite scales with guest count.** Cluster, Ceph, Backup Coverage and Ceph OSD are
also fixed-cost. Node Detail, Node Services, Replication, Certificates, Disks and Subscription make
one call per *online* node, because that data is only available from the node that owns it — and
the expensive ones sit on long intervals for exactly that reason (Certificates and Disks at 240m,
Subscription at 720m). Node counts are small and grow slowly; guest counts are neither.

## Dashboards

`dist/dashboards/Proxmox_VE_Tier1.json`, built by `python build/build.py`, is a LogicMonitor
dashboard covering the six Tier 1 modules: cluster health and capacity, per-node utilisation and
load, guest CPU/memory/network/disk, storage capacity, guest status, and an alert table. Twenty
widgets, in the same widget types and theme LogicMonitor's own VMware, Hyper-V and Nutanix
dashboards use — the schema was taken from their published exports rather than guessed.

Import through **Dashboards → Add → From File** — a different path from the modules, which go
through My Module Toolbox. It lands in a dashboard group called `Proxmox VE`; the import dialog
lets you redirect that. The `defaultResourceGroup` token ships as `*`, so it matches whatever
resource carries the token; scope it to a group if you have more than one cluster.

Unlike the parity suites, widgets legend on the **instance** rather than the hostname, because a
single Proxmox resource carries every node, guest and storage object as instances rather than as
separate resources. That is what makes one tile show the whole cluster.

`dist/dashboards/Proxmox_VE_Tier2.json` (**Proxmox VE Infrastructure**) covers the eight Tier 2
modules in fifteen widgets: Ceph health, OSD and monitor counts, raw utilisation, per-OSD fill and
latency; backup coverage; certificate and subscription expiry; replication jobs; physical disk
SMART and wear; node service state; and the same alert table. Most Tier 2 datapoints are
conditional, so on a cluster without Ceph the Ceph tiles are blank by design — `CephAvailable`
sits on the Ceph Health tile to show why. It imports the same way and lands in the same group.

Each definition lives in `dashboards/<Name>.py`; the JSON is generated. The build
resolves every widget's module and datapoint reference against `modules/*.json`, because a widget
addresses a module as `"<displayedAs> (<name>)"` with nothing in LogicMonitor enforcing it — a
rename would otherwise leave the dashboard importing cleanly and rendering empty tiles.

**Both dashboards are portal-verified.** Tier 1 on 2026-09-15 and again on 2026-09-22, when a
widget-schema fix took it from the sixteen widgets it had silently been importing to all twenty.
Tier 2 on 2026-09-22: all fifteen widgets import and populate with live data.

## Install

**[`docs/INSTALL.md`](docs/INSTALL.md) is the guide** — Proxmox user, permissions, token,
credential string, importing, and which hosts to apply it to. The short version:

```sh
# On any Proxmox node
pveum user add monitor@pve --comment "LogicMonitor read-only monitoring"
pveum acl modify / --users monitor@pve --roles PVEAuditor
pveum user token add monitor@pve logicmonitor          # copy the secret, shown once
pveum acl modify / --tokens 'monitor@pve!logicmonitor' --roles PVEAuditor
```

```sh
python build/build.py       # writes dist/
```

Import every `dist/*.json` through **My Module Toolbox → Add → Import from file**, then set
**`pve.api.token.credential`** on one Proxmox resource to the whole token string,
`monitor@pve!logicmonitor=<secret>`. The PropertySource adds the `ProxmoxVE` category and every
module applies itself from there.

Two things account for most failed installs:

- **The token needs its own ACL.** With privilege separation on (the default) a token's effective
  permissions are the intersection of the user's and its own, so granting `PVEAuditor` to the user
  alone leaves the token with nothing. `/cluster/resources` filters by ACL rather than erroring, so
  the symptom is *empty discovery with no error*, not a failure.
- **The property name ends in `.credential` on purpose.** LogicMonitor masks the value of any
  property whose name ends that way. Call it anything else and the token secret is readable by
  anyone who can view the resource.

One `/cluster/resources` call returns the whole cluster, so a single resource carrying the token
monitors every node, guest and storage object in it. Because the PropertySource stays silent
without a token, the property is the switch: whichever hosts have it are exactly the hosts the
suite applies to. `docs/INSTALL.md` §4.1 covers the trade-off against setting it on every node.

## Alerting

Thresholds ship on the datapoints where a default is defensible: node and storage availability,
cluster quorum, HA errors, filesystem and memory utilisation.

**The capacity rollups ship with no thresholds.** `VCPUOvercommitRatio` and
`MemoryOvercommitRatio` are the figures worth watching cluster-wide, but a defensible number is
entirely site-specific: 4:1 vCPU oversubscription is routine on some clusters and reckless on
others, and memory overcommit above 1.0 is safe exactly to the extent that ballooning and KSM are
configured to absorb it. Set them against your own capacity plan. `Subscribed` is unthresholded for
the same reason in reverse — an unsubscribed node is a licensing fact, not a fault.

**`Status` on Guest Status deliberately ships with no threshold.** A stopped guest is usually
stopped on purpose, and alerting on every powered-off VM produces the kind of noise that gets a
whole suite disabled. Apply `< 1` to the instances or instance groups that are genuinely expected
to stay running, or alert on `HAError`, which only fires for guests the cluster itself considers
broken.

## Known limits

These are Proxmox API limits, not implementation shortcuts:

- **No per-guest storage latency or IOPS.** Proxmox exposes byte counters only, so there is no
  equivalent of Nutanix's `citAvgLatencyUsecs` or `vmReadIOPerSecond`.
- **QEMU reports no used-disk figure.** `DiskUsedGB` and `DiskUsagePercent` are collected for LXC
  containers only. Real usage inside a VM requires the guest agent, in the same way VMware requires
  VMware Tools. Those datapoints are left as no-data for VMs rather than reported as a misleading
  zero.
- **No node hardware identifier.** Nothing in the API returns a node's MAC or UUID, which is why
  the topology map needs `addERI_Proxmox_VE` to give a node's resource a key it can match on.
- **Templates are excluded from discovery.** They are never running and would otherwise appear as
  permanently down instances.

## Verification status

The DataSources and both dashboards are verified against real hardware — Tier 1 collecting since
2026-09-10, Tier 2 against a three-node cluster with Ceph on 2026-09-22. The TopologySource and
`addERI_Proxmox_VE` were confirmed in a portal on 2026-09-23.

`docs/DESIGN.md` §7 is the single record of what is proven and what is not; nothing else in the
repository restates it.

## Development

`scripts/` is the source of truth; `dist/` is generated and not committed. Module bodies are
assembled with the shared preamble `scripts/lib/pve_common.groovy`, because LogicMonitor has no
include mechanism and a script embedded in a module must be self-contained.

```sh
python build/build.py                                        # assemble dist/
docker compose -f tests/docker-compose.yml run --rm compile  # groovyc every script
docker compose -f tests/docker-compose.yml run --rm tests    # run against a mock API
```

The build refuses to emit a module whose script and datapoint declarations disagree, a dashboard
widget that references something that does not exist or overlaps another, or a widget whose shape
does not match LogicMonitor's own exported dashboards. The harness runs every script against a
recorded API and asserts the output formats a Collector actually parses. Two scripts —
`Proxmox_VE_Topology` and `addERI_Proxmox_VE` — depend on Collector-only classes and so are
emitted to `dist/scripts/collector-only/`, outside the reach of both.

**[`CLAUDE.md`](CLAUDE.md) is the contributor detail**: the Collector contract, which conventions
are load-bearing, and what each check exists to prevent.
