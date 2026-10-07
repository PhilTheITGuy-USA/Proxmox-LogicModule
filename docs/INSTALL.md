# Installing the Proxmox VE LogicModule suite

Three things have to happen, in this order:

1. **In Proxmox** — create a read-only user, give it audit permissions, and create an API token.
2. **In LogicMonitor** — import the modules. The PropertySources are ordinary modules too.
3. **On the resource** — set one property. Everything else applies itself.

The whole suite is read-only. Every endpoint it touches is a GET, and no module writes anything to
Proxmox.

---

## Part 1 — Proxmox: user, permissions, token

### 1.1 Create a user

Use the **`pve` realm**, not `pam`. A `pve`-realm user exists only inside Proxmox and has no Linux
account, no shell and no password to manage on the host — which is what you want for something that
only ever reads the API.

**Web UI:** Datacenter → Permissions → Users → Add.
- User name: `monitor`
- Realm: `Proxmox VE authentication server`
- Leave the password blank if you will only use a token (recommended)

**CLI, on any node:**

```sh
pveum user add monitor@pve --comment "LogicMonitor read-only monitoring"
```

### 1.2 Grant permissions

The suite needs `PVEAuditor` on `/` with propagation. That single role covers everything:

| Privilege | Needed for |
|---|---|
| `Sys.Audit` on `/` | `/cluster/status`, HA status, Ceph status, backup coverage |
| `Sys.Audit` on `/nodes/{node}` | node status, services, disks, certificates, subscription |
| `VM.Audit` on `/vms/{vmid}` | guest status and performance, replication jobs, guest config (topology) |
| `Datastore.Audit` on `/storage/{storage}` | storage capacity |

**Web UI:** Datacenter → Permissions → Add → User Permission.
- Path: `/`
- User: `monitor@pve`
- Role: `PVEAuditor`
- Propagate: **checked**

**CLI:**

```sh
pveum acl modify / --users monitor@pve --roles PVEAuditor
```

### 1.3 Create an API token

**Web UI:** Datacenter → Permissions → API Tokens → Add.
- User: `monitor@pve`
- Token ID: `logicmonitor`
- Privilege Separation: leave **checked** (the default)

**CLI:**

```sh
pveum user token add monitor@pve logicmonitor
```

**Copy the secret now.** Proxmox displays it exactly once and cannot show it again. If you lose it,
delete the token and create a new one.

The output looks like this:

```
┌──────────────┬──────────────────────────────────────┐
│ key          │ value                                │
├──────────────┼──────────────────────────────────────┤
│ full-tokenid │ monitor@pve!logicmonitor             │
│ value        │ 8f4e2a1b-7c3d-4e5f-9a8b-1c2d3e4f5a6b │
└──────────────┴──────────────────────────────────────┘
```

### 1.4 Grant permissions to the token as well

**This is the step people miss, and it is the single most common reason the suite discovers
nothing.**

With privilege separation enabled — the default — a token carries **its own ACL**, and its effective
permissions are the *intersection* of the user's permissions and the token's. Granting `PVEAuditor`
to `monitor@pve` in step 1.2 does **not** give the token anything. Until the token has its own ACL,
its effective permissions are empty.

**Web UI:** Datacenter → Permissions → Add → **API Token Permission**.
- Path: `/`
- API Token: `monitor@pve!logicmonitor`
- Role: `PVEAuditor`
- Propagate: **checked**

**CLI:**

```sh
pveum acl modify / --tokens 'monitor@pve!logicmonitor' --roles PVEAuditor
```

> Alternatively, create the token with `--privsep 0` and it inherits the user's permissions with no
> separate ACL. Granting the role to both the user and the token, as above, works either way — which
> is why it is the recommended path.

### 1.5 Verify the token works

From anywhere that can reach the Proxmox API:

```sh
curl -k -H "Authorization: PVEAPIToken=monitor@pve!logicmonitor=8f4e2a1b-7c3d-4e5f-9a8b-1c2d3e4f5a6b" \
  https://pve1.example.com:8006/api2/json/cluster/resources
```

You should get a JSON body with a `data` array listing your nodes, guests and storage.

- **A `401`** means the token secret is wrong, or the token has no ACL — go back to 1.4.
- **`{"data":[]}`**, an empty array with no error, means the token authenticated but can see
  nothing. That is also 1.4. `/cluster/resources` filters by ACL rather than erroring, so an
  under-privileged token looks exactly like an empty cluster.

---

## Part 2 — The credential string

The suite reads **one** property, whose value is the **whole token string** — user, realm, token ID
and secret, joined exactly as below:

```
user@realm!tokenid=secret
```

For the example above:

```
monitor@pve!logicmonitor=8f4e2a1b-7c3d-4e5f-9a8b-1c2d3e4f5a6b
```

Note the punctuation, all of which matters: `@` before the realm, `!` before the token ID, `=`
before the secret. The suite builds the HTTP header as `PVEAPIToken=` plus this string, so
everything after `PVEAPIToken=` is what you paste.

### The property name

```
pve.api.token.credential
```

The `.credential` suffix is not decoration. **LogicMonitor treats any property whose name ends in
`.credential` as sensitive and masks its value in the UI.** Name it anything else and the token
secret is readable by anyone who can view the resource. Do not shorten it to `pve.api.token`.

### Optional properties

| Property | Default | Notes |
|---|---|---|
| `pve.api.url` | `https://<system.hostname>:8006` | Set only if the API is on a different address or port |
| `pve.api.port` | `8006` | Used only when building the default URL |
| `pve.api.timeout` | `10000` | Connect and read timeout, milliseconds |
| `pve.api.insecure` | `false` | `true` accepts self-signed certificates. **Lab use only** |

Proxmox ships a self-signed certificate by default. If your Collector does not trust it, either
install a trusted certificate on the node or set `pve.api.insecure=true` — the latter only where the
network between Collector and Proxmox is trusted.

---

## Part 3 — LogicMonitor: import the modules

### 3.1 Build

```sh
python build/build.py
```

This writes `dist/*.json` (the importable modules) and `dist/scripts/` (the assembled scripts,
which you do not need for an install). `dist/` is generated and is not in git — always build
before installing.

### 3.2 Import every module

**Settings → LogicModules → My Module Toolbox → Add → Import from file**, once per `dist/*.json`.
That is all seventeen: fourteen DataSources, two PropertySources and the TopologySource. There is
no longer any module to create by hand.

`addCategory_Proxmox_VE` applies to every resource in the portal, which is safe only because it is
completely silent — exit 0, no output — on any host that is not Proxmox or has no token. Every
other module applies on `hasCategory("ProxmoxVE")`, so nothing applies to anything until Part 4
sets the token.

### 3.3 The topology modules, if you want a map

`Proxmox_VE_Topology` and `addERI_Proxmox_VE` are optional and only earn their place if the guests
are **also monitored as their own LogicMonitor resources**. They draw cluster → node → guest edges
so that a node's alerts can explain its guests'. Import `addERI_Proxmox_VE` first.

A guest is matched to its own resource by the MAC address of its first virtual NIC, which both
sides already know, so no naming convention is required. A node cannot be matched that way —
Proxmox exposes no MAC or hardware UUID for a node anywhere in its API — so `addERI_Proxmox_VE`
stamps a synthesised key on the node's resource for the map to attach to.

**The map draws only what matches a resource.** A guest appears only when its own resource carries
the guest's MAC in `predef.externalResourceID`, and LogicMonitor fills that in from SNMP — so a
guest that is not monitored, or is monitored but does not answer SNMP, is left off the map. The
TopologySource still reports it; the portal has nothing to draw it as. Check a missing guest's
resource for `auto.snmp.operational` and `predef.externalResourceID` before suspecting the module.
**Test Script** on `Proxmox_VE_Topology` lists every edge it emits, one `Compute` edge per guest,
which separates "not emitted" from "not drawn".

**That PropertySource only runs where the token is set.** On the one-resource-per-cluster layout
below, that is a single node: the other nodes' resources carry no matching key, so by the same rule
they are not drawn, and their alerts cannot explain their guests'. Setting the token on every node
fixes it, at the duplication cost §4.1 describes.

---

## Part 4 — Apply it to your hosts

### 4.1 Which hosts to put the property on

**Read this before setting the property everywhere.** It changes how much data you collect.

Most of this suite is *cluster-wide from any single node*. One call to `/cluster/resources` returns
every node, guest and storage object in the cluster, whichever node answers it. So a single Proxmox
resource carrying the token monitors the **entire cluster** — every guest appears as an instance,
every node appears as an instance, without adding the other nodes to LogicMonitor at all.

That gives you two sensible layouts:

**One resource per cluster (recommended for most).** Set `pve.api.token.credential` on one node.
You get complete cluster coverage with the smallest possible API load. The cost is that if that
specific node is down, the cluster's monitoring goes with it — though `Proxmox_VE_Nodes` will show
the other nodes' state right up until that point.

**The property on every node.** More resilient: any surviving node keeps reporting. But every node
then collects the *same* cluster-wide data, so each guest appears as an instance under every
Proxmox resource. On a 3-node cluster that is 3× the instances and 3× the API calls for identical
numbers. Choose this only if you have decided the redundancy is worth the duplication.

For a **standalone host**, the question does not arise — set it on that host.

Because the PropertySource stays silent when there is no token, **whichever hosts you set the
property on are exactly the hosts the suite applies to.** The property is the switch.

### 4.2 Set the property

On the resource (or on a group containing it): **Manage → Properties → Add**.

| Name | Value |
|---|---|
| `pve.api.token.credential` | `monitor@pve!logicmonitor=8f4e2a1b-7c3d-4e5f-9a8b-1c2d3e4f5a6b` |

Setting it on a **resource group** is the cleaner option if you have several clusters: create a
group per cluster, set the property once on the group, and add the node to it.

### 4.3 Run the PropertySource

On the resource: the PropertySource runs on its own schedule, but you can force it. Once it has run,
the resource gains:

| Property | Meaning |
|---|---|
| `system.categories` | includes `ProxmoxVE` — this is what every module's AppliesTo matches |
| `pve.version` | the Proxmox version, e.g. `8.2.2` |
| `pve.release` | the release, e.g. `8.2` |
| `pve.clustered` | `true` on a cluster member, `false` on a standalone host |

As soon as `ProxmoxVE` appears in `system.categories`, the rest of the suite applies itself. Active
Discovery then populates instances, and collection starts on each module's own interval.

---

## Part 5 — Verify

1. **The category is set.** Resource → Info → look for `ProxmoxVE` in `system.categories`. If it is
   missing, the PropertySource found no token or the host is not Proxmox.
2. **Instances appear.** Resource tree → the resource → the Proxmox VE modules. What you should see
   per module is in the table below. Empty discovery with no error is usually the token ACL — step
   1.4 — but for two modules it is the normal result; the table says which.
3. **Data arrives.** Open any instance's **Raw Data** tab. **Use Poll Now rather than waiting**:
   four modules are on intervals of an hour or more, and Subscription will not collect on its own
   for half a day. A module that is simply not due yet looks exactly like a broken one.

### What each module should show

| Module | Interval | Instances | Empty is normal when |
|---|---|---|---|
| Guest Status | 3m | one per guest, templates excluded | there are no guests |
| Cluster | 5m | single instance | — |
| Nodes | 5m | one per node, offline ones included | — |
| Node Detail | 5m | one per online node | — |
| Guest Performance | 5m | one per guest, templates excluded | there are no guests |
| Node Services | 5m | one per systemd unit per online node | — |
| Ceph | 5m | single instance | — (reports `CephAvailable=0` without Ceph) |
| Ceph OSD | 5m | one per OSD | **Ceph is not installed — expected on most hosts** |
| Storage Capacity | 10m | one per storage | — |
| Replication | 10m | one per replication job | **no replication job is defined — expected on most hosts** |
| Backup Coverage | 60m | single instance | — |
| Certificates | 240m | one per certificate per online node, usually 2–3 | — |
| Disks | 240m | one per physical disk per online node | — |
| Subscription | 720m | one per online node | — |

Modules that reach into a node's own API — Node Detail, Node Services, Certificates, Disks,
Subscription, Replication — discover **online** nodes only. An offline node cannot answer, and an instance that
can never collect is worse than none. Instances discovered earlier survive a node going offline.

---

## Troubleshooting

**No `ProxmoxVE` category.** The PropertySource is silent by design when `pve.api.token.credential`
is absent or the host is not Proxmox. Check the property name character for character — the
`.credential` suffix is part of it, and a resource carrying the old `pve.api.token` name will be
treated as having no token at all and will go quiet rather than erroring.

**Instances discovered, every datapoint No Data.** Check the datapoint post-processor. In
`dist/<Module>.json` a `batchscript` module's datapoints must have `interpretExpr` of
`##WILDVALUE##.<name>`; a `script` module's must be the bare `<name>`. Re-import from a fresh
`python build/build.py` if they disagree.

**Empty discovery, no error.** Usually the token ACL — see 1.4, then re-run the check in 1.5.
**Two modules are the exception.** Ceph OSD discovers nothing where Ceph is not installed, and
Replication discovers nothing where no replication job is defined. Both are the normal result on
most installs and neither fails; Ceph OSD also says so on stderr in the discovery task log. If the
other modules discovered instances, the token is fine.

**A module has instances but no data yet.** Check its collection interval before anything else.
Backup Coverage is 60m, Certificates and Disks are 240m, and Subscription is 720m — these endpoints
are expensive (listing disks makes Proxmox run `smartctl` on every disk) or the data changes daily
at most, so they are deliberately slow. Press **Poll Now** instead of waiting out the interval.

**HTTP 401 in the task log.** The secret is wrong or the token was deleted. Tokens cannot be
recovered — delete and recreate.

**Certificate errors.** Proxmox self-signs by default. Install a trusted certificate, or set
`pve.api.insecure=true` on the resource for lab use.

**Some datapoints permanently No Data.** Often correct rather than broken — the suite withholds a
value rather than emitting a zero, because a confident wrong number is worse than an absent one:

- `DiskUsedGB` / `DiskUsagePercent` on QEMU guests. Proxmox reports no used-disk figure for VMs;
  LXC containers do report one. Real VM usage needs the guest agent.
- Cluster quorum datapoints on a standalone host.
- Every Ceph datapoint where Ceph is not configured. `CephAvailable` reads 0.
- `DaysUntilDue` on an unsubscribed node. Zero would read as "expires today".
- `LifeRemainingPercent` on anything that is not an SSD reporting a wear attribute. Proxmox returns
  the literal string `N/A`, and zero would read as a disk with no life left.
- `SmartHealthOK` on a disk whose SMART cannot be read. `SmartHealthKnown` goes to 0 instead, so an
  unreadable disk is never mistaken for a failing one.
- `SecondsSinceLastSync` on a replication job that has never run. Zero would mean "replicated just
  now", the opposite of the truth.

Each module's technical notes say which of its datapoints are conditional and why.

---

## Appendix — driving a portal from a script

For importing modules and running scripts against a real Collector without clicking through the UI.

**Modules are distributed as JSON**, exported and imported through **My Module Toolbox → Export** /
**Add → Import from file** as a single `.json` (XML is the older format). The import API takes a
type of `datasources`, `propertyrules`, `configsources`, `eventsources`, `batchjobs`, `logsources`,
`oids`, `topologysources`, `functions` or `diagnosticsources`, plus a conflict policy
(`FORCE_OVERWRITE` / `ERROR`) and a `FieldsToPreserve` list covering `NAME`, `APPLIES_TO_SCRIPT`,
`COLLECTION_INTERVAL`, `ACTIVE_DISCOVERY_INTERVAL`, `MODULE_GROUP`, `DISPLAY_NAME`,
`USE_WILD_VALUE_AS_UUID`, `DATAPOINT_ALERT_THRESHOLDS` and `TAGS`.

**REST API v3.** Base `https://<portal>.logicmonitor.com/santaba/rest`, header `X-Version: 3`.
Either `Authorization: Bearer <token>`, or LMv1: build `METHOD + epochMillis + body + resourcePath`
(body omitted for GET/DELETE, resourcePath excludes the query string), HMAC-SHA256 it with the
access key, lowercase-hex the digest, Base64 **the hex string**, and send
`Authorization: LMv1 <accessId>:<base64>:<epochMillis>`. The `Logic.Monitor` PowerShell module
implements this and ships `Export-LMLogicModule`, `Import-LMLogicModuleFromFile`,
`Invoke-LMActiveDiscovery` and `Invoke-LMCollectorDebugCommand` — the last runs `!groovy` against a
real Collector, which is the closest thing to a live test harness this project has.
