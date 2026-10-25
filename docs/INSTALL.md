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
│ value        │ <token>                              │
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
curl -k -H 'Authorization: PVEAPIToken=monitor@pve!logicmonitor=<token>' \
  https://pve1.example.com:8006/api2/json/cluster/resources
```

You should get a JSON body with a `data` array listing your nodes, guests and storage.

- **A `401`** means authentication failed: the token ID or secret is wrong, or the token was
  deleted. A token with no ACL still authenticates — that is the next case.
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
monitor@pve!logicmonitor=<token>
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
That is all nineteen: fifteen DataSources, three PropertySources and the TopologySource. There is
no longer any module to create by hand.

`addCategory_Proxmox_VE` applies to every resource in the portal, which is safe only because it is
completely silent — exit 0, no output — on any host that is not Proxmox or has no token. Every
other module applies on `hasCategory("ProxmoxVE")`, so nothing applies to anything until Part 4
sets the token. The two ERI modules are the exceptions, and need no token: `addERI_Proxmox_VE`
finds Proxmox nodes by their kernel, and `addERI_Proxmox_VE_Guest` finds guest resources by their
MAC address. Both work as soon as they are imported (§3.3).

**Upgrading an install that has `Proxmox_VE_BackupCoverage`: delete it, then import
`Proxmox_VE_Backups`.** Backups replaces it with one instance per guest per job, and the portal
cannot turn a single-instance module into a multi-instance one. Re-import the Tier 2 dashboard
afterwards, since its Backups tile points at the new module, and count its 15 widgets.

**Upgrading an install from before 2026-10-22: delete `Proxmox_VE_Cluster` first.** It changed
from a single-instance to a one-instance multi-instance module, and LogicMonitor fixes that when a
module is created: importing over the old one fails with "Field value cannot be modified", naming
no field. Delete the module (its history goes with it), import the new one, then re-import the
Tier 1 dashboard, whose cluster tiles pointed at the deleted module, and count its 20 widgets.

### 3.3 The topology map

`Proxmox_VE_Topology` and `addERI_Proxmox_VE` draw cluster → node → guest edges
so that a node's alerts can explain its guests', and every guest is drawn whether or not it is
monitored as its own LogicMonitor resource. Import `addERI_Proxmox_VE` first.

**Both apply as soon as they are imported**; there is no property to set. The TopologySource and Guest Status's discovery are the only costs in the
suite that grow with guest count: one config read per guest each, hourly, on the one node per
cluster that holds the token.

**Which resources it reaches.** Every node needs to be a LogicMonitor resource, because a node is
drawn only through its own resource. `addERI_Proxmox_VE` finds the nodes itself, by its AppliesTo:

```
hasCategory("ProxmoxVE") || pve.topology.node || (system.sysinfo =~ "-pve" && !(auto.network.mac_address =~ "^[bB][cC]:24:11") && !pve.guest.mac_prefix)
```

A node holding the token has the category. Any other node is caught by its kernel string (`-pve`)
in `system.sysinfo`, which comes from SNMP. An LXC container reports its host's kernel too, so a
resource carrying Proxmox's guest MAC prefix, or `pve.guest.mac_prefix`, is left out of that test.
A node with neither SNMP nor the token, or nested Proxmox (a Proxmox VM, whose MAC is a guest's),
is brought in by `pve.topology.node` on its own resource (below).

No per-cluster or per-node property is needed. The API token stays on one node per cluster,
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
| `pve.topology.node` | a node without SNMP that does not hold the token — its Proxmox node name, as `pvecm nodes` or `ls /etc/pve/nodes` shows it (the membership list of `pvecm status` shows addresses, not names). It brings the node into `addERI_Proxmox_VE`'s AppliesTo above, and names it where LogicMonitor knows no hostname (added by IP, with no SSH either) |
| `pve.topology.cluster` | the older cluster-scoped key, `proxmoxve--<cluster>--<node>`, on a node without the token. The map uses it only for a node `/cluster/status` reports no address for. Only on a group holding **one** cluster's nodes, exactly as the token holder reports it in `auto.pve.cluster.name`; **never on a group spanning clusters**, where a wrong value can attach a node to a same-named node of another cluster |

A **standalone host** needs nothing. It holds its own token, so `addERI_Proxmox_VE`
asks the API, and it is drawn without a cluster above it. `auto.pve.clustered` reads `false` on a
standalone host, which is how to tell which case you have.

**The cluster is drawn through Proxmox VE Cluster's one instance**, which on a cluster carries
the cluster's key (`predef.externalResourceID = proxmoxve--<cluster>`, type `Cluster`). It is drawn
with LogicMonitor's cluster icon above every node. Its discovery runs hourly, so after importing,
run **Run Active Discovery** on Proxmox VE Cluster rather than wait. A standalone host has
no cluster, so its instance carries no key and the map starts at the node.

**If you do not want the map, do not import `Proxmox_VE_Topology` and `addERI_Proxmox_VE`.**
Removing them later leaves the keys `addERI_Proxmox_VE` stamped in each node's
`predef.externalResourceID`, and the portal does not let you delete that property (DESIGN §7). Guest Status's and Proxmox VE Cluster's instances still carry their keys, which
draw nothing without the TopologySource.

Keep `pve.topology.node` and `pve.topology.cluster` off guest resources: `pve.topology.node` on a
guest brings it into `addERI_Proxmox_VE`, which would stamp a node key the map never asks for.

A guest is matched to its own resource by the MAC address of its first virtual NIC, which both
sides already know, so no naming convention is required. A node cannot be matched that way —
Proxmox exposes no MAC or hardware UUID for a node anywhere in its API — so `addERI_Proxmox_VE`
stamps a synthesised key on the node's resource for the map to attach to.

**Every guest is drawn, with no SNMP and no per-guest setup.** The map draws a vertex only when it
matches something in the portal: a resource, or a DataSource instance. Guest Status's discovery
gives each guest on an online node an instance carrying the guest's MAC — or,
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
silently fail to match. On a resource carrying either, Guest Status's discovery adds no keys,
and a guest with no resource of its own is not drawn.

**Guests are labelled as virtual machines, always.** `addERI_Proxmox_VE_Guest` needs no token. LogicMonitor labels a monitored guest `PhysicalServer`,
because Proxmox's guest MAC prefix is not on its list of virtual-machine prefixes; this module
applies to any Linux or Windows resource whose `auto.network.mac_address` starts with Proxmox's
default prefix `bc:24:11` and relabels it `VirtualMachineResource`. Its AppliesTo is
`(auto.network.mac_address =~ "^[bB][cC]:24:11" || pve.guest.mac_prefix) && (isLinux() || isWindows())`;
the OS clause leaves virtual appliances such as FortiGate-VM or pfSense to their own ERI modules. If your cluster hands out a different prefix
(**Datacenter → Options → MAC address prefix**), set `pve.guest.mac_prefix` on the group holding
the guests: a comma-separated list such as `bc:24:11,02:00:00`, which replaces the default, so
list `bc:24:11` too if it is still in use. A resource the property reaches whose MAC matches no
prefix is left alone, and a guest with a hand-set MAC outside every prefix stays `PhysicalServer`.

**What the guest pictures on the map mean.** Every guest is a virtual machine, but the map draws it
one of two ways, depending on how much LogicMonitor knows about it:

| Picture | Label when you point at it | What it means |
|---|---|---|
| Light, flat hexagon | `VirtualMachineResource` | LogicMonitor watches this guest **directly** — you added it as its own resource, and it answers SNMP or WMI. It has its own page in Resources. |
| Dark box | `VirtualMachine` | LogicMonitor knows this guest **only from Proxmox**. It is on the map, and its status is under Proxmox VE Guest Status on its node, but it has no page of its own. |

Think of it like a school: the light hexagon is a student who checked in at the front desk
themselves, and the dark box is a student whose teacher says they are in class. Both are there; the
front desk just knows more about the first one.

The kind of guest does not decide the picture — a QEMU VM and an LXC container look the same. Only
"is it its own resource?" does. To turn a dark box into a light hexagon, add the guest as a resource
with SNMP (Linux) or WMI (Windows); the map changes at its next hourly refresh.

The line from a node to a guest is a **Compute** line: "this guest runs on this node". Other kinds of
line, such as **Storage**, come from other LogicMonitor modules that look at the guest from the
inside, not from this suite.

**A node missing from the map** carries no matching key. First check that its resource's
`predef.externalResourceID` holds a `proxmoxve--node--` key; if not, `addERI_Proxmox_VE` did not
reach it — look for `-pve` in its `system.sysinfo`, or set `pve.topology.node`. Then check that the address `pvesh get
/cluster/status` reports for it is among its resource's `system.ips`, and that its hostname's short
form is its Proxmox node name; if it was added by IP with no SNMP or SSH, set `pve.topology.node`.
A name that differs from Proxmox's own in anything but case or punctuation matches nothing,
silently. If the token is on
every node, the TopologySource runs on every node too — the same map, built once per node.

Proxmox VE Snapshots needs nothing beyond the token either. It reads each guest's snapshot list
every hour; a guest whose storage cannot take snapshots always reports 0.

### 3.4 Optional: backup failures in LM Logs as well

Proxmox VE Backups needs nothing beyond the token. It reads the backup runs' task logs every hour
and reports, per guest, the age of its last good backup and whether a backup has failed since —
an alert that clears itself once the next backup succeeds. That is the source of truth for
"is this guest protected", and it works without LM Logs.

If your portal has LM Logs (licensed separately), sending Proxmox's own notifications there adds
two things the module cannot give:

- **Immediate notice.** A failed run reaches LM Logs within moments, instead of at the module's
  next hourly collection.
- **Searchable history**, in Proxmox's own wording, kept for LM Logs' retention period.

Proxmox's notification system (**Datacenter → Notifications**) sends a message when a backup job
finishes, if the job's notification mode is `notification-system`. Forward those messages to LM
Logs through a webhook target where your Proxmox version offers one, or through syslog to a
Collector. Before relying on syslog alone, check that it carries the per-guest lines you need: a
run's per-guest results are certainly in its task log, which is what the module reads.

Keep the module's alert as the one that pages someone. An alert on a log line fires when the line
arrives and does not clear when the next backup works, and it says nothing about a backup that
never ran at all — `LastBackupAgeHours` is what catches that.

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
| `pve.api.token.credential` | `monitor@pve!logicmonitor=<token>` |

Setting it on a **resource group** is the cleaner option if you have several clusters: create a
group per cluster, set the property once on the group, and add the node to it.

The topology map of §3.3 needs no group at all. Only the optional `pve.topology.cluster` is per cluster, and never goes on a group
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

### 4.4 Creating a cluster, or adding a standalone host to one

**Creating a cluster on a monitored standalone host** (`pvecm create`) needs no change in
LogicMonitor. The host keeps its users and token, and on the PropertySource's next run
`auto.pve.clustered` turns `true` and `auto.pve.cluster.name` appears. Proxmox VE Cluster keeps its
one instance — its wildvalue is the fixed `cluster`, so its history carries over — and its next
discovery renames it from the host's name to the cluster's. With the topology flag on, run
**Run Active Discovery** on Proxmox VE Cluster to draw the cluster above the node.

**Adding a standalone host to a cluster** (`pvecm add`) replaces the joining host's `/etc/pve` with
the cluster's, so Proxmox requires it to hold no guests, and its own users and tokens are gone
afterwards. Users and tokens are cluster-wide: the cluster's `monitor@pve!logicmonitor` token now
works against the joined host too. In LogicMonitor:

1. **Remove `pve.api.token.credential` from the joined host's resource**, if it had its own. Its old
   token no longer exists, and putting the cluster's token there instead makes it collect the whole
   cluster a second time (§4.1). Once the PropertySource has run (§4.3), the suite stops applying
   to that resource.
2. **The token holder picks the new node up by itself.** Its next discovery adds the node to Nodes,
   and to the per-node modules once the node is online. The joined host's earlier history stays on
   its own resource and does not move to these instances.
3. **For the map**, keep the joined host's resource in the topology group of §3.3. It needs no
   token: `addERI_Proxmox_VE` stamps its name-plus-IP key from its own properties. The address
   `pvesh get /cluster/status` reports for it must be among its `system.ips` (§3.3).

The result is the layout §4.1 recommends: one token on one node of the cluster. The other nodes
need to be resources only if you use the map.

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
| Guest Status | 3m | one per guest, templates excluded, grouped by node | there are no guests |
| Cluster | 5m | one, the cluster (a standalone host's own name) | — |
| Nodes | 5m | one per node, offline ones included | — |
| Node Detail | 5m | one per online node | — |
| Guest Performance | 5m | one per guest, templates excluded, grouped by node | there are no guests |
| Node Services | 5m | one per systemd unit per online node, grouped by node | — |
| Ceph | 5m | single instance | — (reports `CephAvailable=0` without Ceph) |
| Ceph OSD | 5m | one per OSD | **Ceph is not installed — expected on most hosts** |
| Storage Capacity | 10m | one per storage, grouped by node | — |
| Replication | 10m | one per replication job | **no replication job is defined — expected on most hosts** |
| Backups | 60m | one per guest per backup job, grouped by job, plus one per guest no job covers | — |
| Certificates | 240m | one per certificate per online node, usually 2–3, grouped by node | — |
| Disks | 240m | one per physical disk per online node, grouped by node | — |
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
most installs and neither fails. If the
other modules discovered instances, the token is fine.

**A module has instances but no data yet.** Check its collection interval before anything else.
Backups is 60m, Certificates and Disks are 240m, and Subscription is 720m — these endpoints
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
