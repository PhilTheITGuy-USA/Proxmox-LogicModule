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
| addCategory_Proxmox_VE | PropertySource | Detects Proxmox and sets the category the suite applies to |

The four BatchScript modules each make **one** API call per collection interval no matter
how large the cluster is: `/cluster/resources` returns every node, guest and storage
object in a single response. A 500-guest cluster costs one request per interval, not five
hundred.

The four calls are intentional: LogicMonitor executes each DataSource independently, so
there is no dependable cross-DataSource response cache. Combining them into one DataSource
could reduce the count further, but would couple unrelated alerting and collection intervals.
This is the practical low-load boundary while preserving separate modules.

## Install

**[`docs/INSTALL.md`](docs/INSTALL.md) is the full guide** — creating the Proxmox user, the
permissions it needs, creating the API token, building the credential string, importing the
modules, and choosing which hosts to apply it to. The short version:

```sh
# On any Proxmox node
pveum user add monitor@pve --comment "LogicMonitor read-only monitoring"
pveum acl modify / --users monitor@pve --roles PVEAuditor
pveum user token add monitor@pve logicmonitor          # copy the secret, shown once
pveum acl modify / --tokens 'monitor@pve!logicmonitor' --roles PVEAuditor
```

```sh
# Locally
python build/build.py
```

1. Import each `dist/*.json` through **Settings → LogicModules → My Module Toolbox → Add → Import
   from file**.
2. Create the PropertySource by hand: **Add → PropertySource**, name it `addCategory_Proxmox_VE`,
   set AppliesTo to something that reaches your Proxmox hosts, and paste
   `dist/scripts/addCategory_Proxmox_VE.groovy`. It ships as a script rather than an importable
   JSON because the PropertySource export schema could not be verified against a published sample.
3. Set **`pve.api.token.credential`** on the resource to the whole token string —
   `monitor@pve!logicmonitor=<secret>` — and run the PropertySource. It adds the category
   `ProxmoxVE`, and every module applies itself from there.

Two things that account for most failed installs:

- **The token needs its own ACL.** With privilege separation on (the default) a token's effective
  permissions are the intersection of the user's and its own, so granting `PVEAuditor` to the user
  alone leaves the token with nothing. `/cluster/resources` filters by ACL rather than erroring, so
  the symptom is *empty discovery with no error*, not a failure.
- **The property name ends in `.credential` on purpose.** LogicMonitor masks the value of any
  property whose name ends that way. Call it anything else and the token secret is readable by
  anyone who can view the resource.

### Which hosts to set the property on

One call to `/cluster/resources` returns the whole cluster, so **a single Proxmox resource carrying
the token monitors every node, guest and storage object in the cluster**. Setting the property on
every node instead gives you redundancy at the cost of collecting the same cluster-wide data once
per node — every guest becomes an instance under every resource. `docs/INSTALL.md` §4.1 lays out
the trade-off. Because the PropertySource stays silent without a token, the property is the switch:
whichever hosts have it are exactly the hosts the suite applies to.

## Resource properties

| Property | Required | Default | Notes |
|---|---|---|---|
| `pve.api.token.credential` | yes | — | Full token string: `user@realm!tokenid=secret`. The `.credential` suffix makes LogicMonitor mask it |
| `pve.api.url` | no | `https://<system.hostname>:8006` | Override if the API is on another address or port |
| `pve.api.port` | no | `8006` | Used only when building the default URL |
| `pve.api.timeout` | no | `10000` | Connect and read timeout, milliseconds |
| `pve.api.insecure` | no | `false` | `true` accepts self-signed certificates. **Lab use only** |

`PVEAuditor` on `/` with propagate supplies every privilege the suite needs: `Sys.Audit` for node,
cluster, Ceph and backup data, `VM.Audit` for guests and replication, `Datastore.Audit` for storage.

## Alerting

Thresholds ship on the datapoints where a default is defensible: node and storage
availability, cluster quorum, HA errors, filesystem and memory utilisation.

**The capacity rollups ship with no thresholds.** `VCPUOvercommitRatio` and
`MemoryOvercommitRatio` are the figures worth watching cluster-wide, but a defensible number is
entirely site-specific: 4:1 vCPU oversubscription is routine on some clusters and reckless on
others, and memory overcommit above 1.0 is safe exactly to the extent that ballooning and KSM are
configured to absorb it. Set them against your own capacity plan. `Subscribed` is unthresholded for
the same reason in reverse — an unsubscribed node is a licensing fact, not a fault.

**`Status` on Guest Status deliberately ships with no threshold.** A stopped guest is
usually stopped on purpose, and alerting on every powered-off VM produces the kind of
noise that gets a whole suite disabled. Apply `< 1` to the instances or instance groups
that are genuinely expected to stay running, or alert on `HAError`, which only fires for
guests the cluster itself considers broken.

## Validating against a real cluster

Six modules are verified against a live Proxmox host. Five are not, because they need something a
single node cannot provide — a Ceph cluster, a replication job, a subscribed node, an SSD with a
wear attribute. Those carry an UNVERIFIED note in their own technical notes.

**`docs/VALIDATION.md` is the checklist for anyone with a cluster to test against**: what is
unproven in each module, what to compare it against, and what to send back. The most useful thing
is a raw API capture, which becomes a test fixture.

## Known limits

These are Proxmox API limits, not implementation shortcuts:

- **No per-guest storage latency or IOPS.** Proxmox exposes byte counters only, so there
  is no equivalent of Nutanix's `citAvgLatencyUsecs` or `vmReadIOPerSecond`.
- **QEMU reports no used-disk figure.** `DiskUsedBytes` and `DiskUsagePercent` are
  collected for LXC containers only. Real usage inside a VM requires the guest agent, in
  the same way VMware requires VMware Tools. Those datapoints are left as no-data for VMs
  rather than reported as a misleading zero.
- **Templates are excluded from discovery.** They are never running and would otherwise
  appear as permanently down instances.

## Development

`scripts/` is the source of truth. `dist/` is generated and not committed.

Each module body is assembled with the shared preamble `scripts/lib/pve_common.groovy`,
because LogicMonitor has no include mechanism and the script embedded in a module must be
self-contained.

```sh
python build/build.py           # assemble dist/*.json and dist/scripts/*.groovy
python build/build.py --check   # validate without writing
```

The build fails if a collection script prints a datapoint the module does not declare, if
a declared datapoint is never printed, or if brackets are unbalanced in any assembled
script.

Verification runs in the same Groovy 4 runtime the Collector uses, and needs no Proxmox
host:

```sh
docker compose -f tests/docker-compose.yml run --rm compile   # groovyc every script
docker compose -f tests/docker-compose.yml run --rm tests     # run against a mock API
```

The test harness serves recorded Proxmox API shapes over HTTP, executes each script with
the bindings a Collector injects, and asserts discovery line format, BatchScript
`instance.datapoint=value` format, numeric values, that every discovered instance receives
data and vice versa, that failures exit non-zero without emitting datapoints, and that
collection uses the bulk endpoint.

Adding or changing a datapoint means editing three things: the `pveEmit` call in the
collection script, the `datapoints` array in `modules/<Module>.json`, and the coverage
table above. The build enforces the first two agreeing.

For live-cluster testing, use a non-production read-only `PVEAuditor` token and record the
Collector API request count. Verify that each batch module makes one bulk request per interval
and that Node Detail makes one `/nodes/{node}/status` request per discovered node. Do not use
a per-guest production test loop.

For live-HA-cluster testing, cover quorum loss and recovery, node membership changes, guest
migration/failover, HA service error/fence states, and temporary HA endpoint unavailability.
Confirm that HA-optional datapoints becoming no-data is understood and that failed collection
returns non-zero without deleting existing instances. Restore healthy quorum before ending.

End users should receive prebuilt `dist/*.json` imports and the assembled PropertySource script
from a tagged release; they should not need Python, Docker, or a local build. Maintainers should
still rebuild and run the complete validation loop before publishing release artifacts. `dist/`
remains generated output and is intentionally not committed to the development branch.
