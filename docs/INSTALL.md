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
| `pve.topology.enabled` | unset | `true` (lowercase) turns on the optional topology modules — see §3.3 |
| `pve.topology.cluster` | unset | Optional. The cluster's name, only on a group holding one cluster's nodes — see §3.3 |
| `pve.topology.node` | the short hostname | The node's Proxmox name, where the resource was added by IP — see §3.3. The name `pvecm nodes` shows, never the address `pvecm status` lists; an address is ignored |

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
That is all eighteen: fourteen DataSources, three PropertySources and the TopologySource. There is
no longer any module to create by hand.

`addCategory_Proxmox_VE` applies to every resource in the portal, which is safe only because it is
completely silent — exit 0, no output — on any host that is not Proxmox or has no token. Every
other module applies on `hasCategory("ProxmoxVE")`, so nothing applies to anything until Part 4
sets the token. The two optional modules in §3.3 additionally need their own property, so importing
them changes nothing until you opt in. `addERI_Proxmox_VE_Guest` is the other exception: it
applies to guest resources by their MAC address, needs no token, and works as soon as it is
imported (§3.3).

### 3.3 The topology modules, if you want a map

`Proxmox_VE_Topology` and `addERI_Proxmox_VE` are optional. They draw node → guest edges (the
cluster itself is not drawn; see below) so that a node's alerts can explain its guests', and every guest is drawn whether or not it is
monitored as its own LogicMonitor resource. Import `addERI_Proxmox_VE` first.

**Both are off by default.** One property, `pve.topology.enabled=true`, turns on both, and the
guest keys in Guest Status's discovery — lowercase, since AppliesTo compares the string exactly.
The TopologySource and, with the flag on, Guest Status's discovery are the only costs in the suite
that grow with guest count (one config read per guest each, hourly), which is a second reason they
are not applied unasked.

**Where to set it.** Every node needs to be a LogicMonitor resource, because a node is drawn only
through its own resource. Then put `pve.topology.enabled=true` on one dynamic group holding every
Proxmox host in the portal, of every cluster and every standalone host, with this AppliesTo:

```
(system.sysinfo =~ "-pve" || hasCategory("ProxmoxVE") || pve.topology.node) && !(auto.network.mac_address =~ "^[bB][cC]:24:11")
```

The first clause catches a node by its kernel string (`-pve`) whether or not it holds the token —
but `system.sysinfo` comes from SNMP, so a node without SNMP and without the token is caught only
by `pve.topology.node`, set on its own resource (below), or by adding it to a static group that
carries the flag. The last clause leaves out guests, which carry Proxmox's default MAC prefix. Scope the group to Proxmox
hosts like this rather than to a broad group such as "Linux Servers": `addERI_Proxmox_VE` stamps a
node key on every resource the flag reaches. A key on a non-Proxmox server matches nothing, but it
is clutter.

That is all — no per-cluster or per-node property. The API token stays on one node per cluster,
as §4.1 recommends:

- `Proxmox_VE_Topology` runs only where the token is, and reports the whole cluster from there.
- `addERI_Proxmox_VE` runs on every node and stamps a key built from the node's name and an IP
  address, `proxmoxve--node--<name>--<ip>`, one per address in the resource's `system.ips`. The
  TopologySource builds the same key from the address `/cluster/status` reports for that node, so
  a node without the token matches with nothing set on it. The name is the resource's
  `system.sysname`, else its hostname, short form: Proxmox requires a node's name to be its
  hostname, so `pve2.example.com` is node `pve2`. An IP address is never taken as a name.
- **The address must be one LogicMonitor knows for the node.** `/cluster/status` reports each
  node's cluster (corosync) address. If corosync runs on its own network, that address is in the
  node's `system.ips` only when LogicMonitor sees every interface, which SNMP gives it; a node
  added by its management IP with no SNMP is otherwise left unmatched.
- **Two clusters whose nodes share a name and an address** — lab copies, or a reused private range
  such as a dedicated corosync network — produce the same key, and the map joins them.
  `topo.namespace`, LogicMonitor's usual remedy, is not supported (below).

Two optional properties remain, for the cases that key cannot cover:

| Property | When |
|---|---|
| `pve.topology.node` | a node without SNMP that does not hold the token — its Proxmox node name, as `pvecm nodes` or `ls /etc/pve/nodes` shows it (the membership list of `pvecm status` shows addresses, not names). It brings the node into the group above, and names it where LogicMonitor knows no hostname (added by IP, with no SSH either) |
| `pve.topology.cluster` | the older cluster-scoped key, `proxmoxve--<cluster>--<node>`, on a node without the token. The map uses it only for a node `/cluster/status` reports no address for. Only on a group holding **one** cluster's nodes, exactly as the token holder reports it in `auto.pve.cluster.name`; **never on a group spanning clusters**, where a wrong value can attach a node to a same-named node of another cluster |

A **standalone host** needs nothing beyond the flag. It holds its own token, so `addERI_Proxmox_VE`
asks the API, and it is drawn without a cluster above it. `auto.pve.clustered` reads `false` on a
standalone host, which is how to tell which case you have.

**The cluster itself is not drawn**, only its nodes and guests: the portal draws no vertex that
matches nothing, no resource stands for the cluster, and an instance carrying the cluster's key is
drawn joined to its own node only, which misdraws the cluster (DESIGN §7, spike 3).

Keep `pve.topology.enabled`, `pve.topology.node` and `pve.topology.cluster` off guest resources: on
a guest `addERI_Proxmox_VE` would stamp a node key the map never asks for. The group's AppliesTo
above already leaves guests out.

A guest is matched to its own resource by the MAC address of its first virtual NIC, which both
sides already know, so no naming convention is required. A node cannot be matched that way —
Proxmox exposes no MAC or hardware UUID for a node anywhere in its API — so `addERI_Proxmox_VE`
stamps a synthesised key on the node's resource for the map to attach to.

**Every guest is drawn, with no SNMP and no per-guest setup.** The map draws a vertex only when it
matches something in the portal: a resource, or a DataSource instance. With the flag on, Guest
Status's discovery gives each guest on an online node an instance carrying the guest's MAC — or,
for a guest with no NIC, the same synthesised key the TopologySource emits — in
`predef.externalResourceID`, so an unmonitored or ping-only guest is drawn through that instance.
A guest that is also its own resource, holding the same MAC, is drawn once. Discovery runs hourly,
so a new guest appears within the hour, or at once after **Run Active Discovery** on Guest Status.
A guest on an offline node, or whose config cannot be read, keeps its instance without the key
until a later discovery finds it. **Test Script** on `Proxmox_VE_Topology` lists every edge it
emits, one `Compute` edge per guest, which separates "not emitted" from "not drawn"; for a guest
that is emitted and not drawn, check its Guest Status instance for `predef.externalResourceID`.

**Not supported with `topo.namespace` or `topo.blacklist`.** Those properties rewrite the keys
LogicMonitor's topology code emits, and Guest Status writes its keys without them, so they would
silently fail to match. On a resource carrying either, Guest Status's discovery adds no keys and
says so on stderr, and a guest with no resource of its own is not drawn.

**Guests are labelled as virtual machines, always.** `addERI_Proxmox_VE_Guest` is not optional and
needs neither the token nor the flag. LogicMonitor labels a monitored guest `PhysicalServer`,
because Proxmox's guest MAC prefix is not on its list of virtual-machine prefixes; this module
applies to any Linux or Windows resource whose `auto.network.mac_address` starts with Proxmox's
default prefix `bc:24:11` and relabels it `VirtualMachineResource`. Its AppliesTo is
`(auto.network.mac_address =~ "^[bB][cC]:24:11" || pve.guest.mac_prefix) && (isLinux() || isWindows())`;
the OS clause leaves virtual appliances such as FortiGate-VM or pfSense to their own ERI modules. If your cluster hands out a different prefix
(**Datacenter → Options → MAC address prefix**), set `pve.guest.mac_prefix` on the group holding
the guests: a comma-separated list such as `bc:24:11,02:00:00`, which replaces the default, so
list `bc:24:11` too if it is still in use. A resource the property reaches whose MAC matches no
prefix is left alone, and a guest with a hand-set MAC outside every prefix stays `PhysicalServer`.

**A node missing from the map** carries no matching key. First check that its resource carries
`pve.topology.enabled=true` — that the group's AppliesTo reached it (see above). Then check that the address `pvesh get
/cluster/status` reports for it is among its resource's `system.ips`, and that its hostname's short
form is its Proxmox node name; if it was added by IP with no SNMP or SSH, set `pve.topology.node`.
A name that differs from Proxmox's own in anything but case or punctuation matches nothing,
silently. If the token is on
every node, the TopologySource runs on every node too — the same map, built once per node.

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

The topology flag of §3.3 does not follow that grouping: one dynamic group holding every Proxmox
host carries it. Only the optional `pve.topology.cluster` is per cluster, and never goes on a group
spanning clusters.

### 4.3 Run the PropertySource

The PropertySource runs on its own schedule, and the portal offers no way to force it: there is a
manual rematch for a DataSource, but none for a PropertySource. Expect a delay after setting or
removing the token, and wait rather than re-importing. Once it has run, the resource gains:

| Property | Meaning |
|---|---|
| `system.categories` | includes `ProxmoxVE` — this is what every module's AppliesTo matches |
| `auto.pve.version` | the Proxmox version, e.g. `8.2.2` |
| `auto.pve.release` | the release, e.g. `8.2` |
| `auto.pve.clustered` | `true` on a cluster member, `false` on a standalone host |
| `auto.pve.cluster.name` | the cluster's name, on a cluster member only — what §3.3's `pve.topology.cluster` copies |

LogicMonitor prefixes every property a PropertySource sets with `auto.`, except
`system.categories`. Use the full `auto.pve.*` names in any AppliesTo, filter or report of your own.

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
