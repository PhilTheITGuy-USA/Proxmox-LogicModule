# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A LogicMonitor **LogicModule suite** for Proxmox VE, monitored over its HTTPS JSON API
(`/api2/json/...`). There is no application here — Groovy scripts are embedded in DataSources and
executed by a LogicMonitor Collector. The target is a suite publishable to the LM Exchange that
works unchanged from a single node to a large enterprise cluster; see `docs/DESIGN.md` for the
parity analysis against LogicMonitor's VMware/Hyper-V/Nutanix suites and the backlog.

```sh
python build/build.py           # assemble dist/
python build/build.py --check   # validate only; prints the module and dashboard count

docker compose -f tests/docker-compose.yml run --rm compile   # groovyc, Groovy 4
docker compose -f tests/docker-compose.yml run --rm tests     # mock-API integration

python ../logicmonitor-modules-skill/skill/scripts/lm_lint_module.py dist
python ../logicmonitor-modules-skill/skill/scripts/lm_dashboards.py check dist/dashboards/*.json --modules modules
```

The skill checks are Bash syntax; the PowerShell form is in the workspace `..\CLAUDE.md`.
`lm_lint_module.py` prints 5 expected warnings, all on `addCategory_Proxmox_VE` (the four
`auto.pve.*` names and the `true()` AppliesTo, both explained below). Anything beyond those five is new.

Always in that order: both Docker jobs and both skill checks read `dist/`, so an un-rebuilt edit
is silently tested in its previous form. Groovy is not installed locally; Docker is the only way to
compile or run anything. There is no single-test mode — see Build and verification.

**Use the `logicmonitor-modules` skill** (`PhilTheITGuy-USA/logicmonitor-modules-skill`, its own
repo) for module, dashboard and API work; the last two commands above are its checks.

**`docs/DESIGN.md` §1-§6 is a design record, not outstanding work.** §6 reads like a to-do list,
but all ten of its items are implemented, and §3's *verbatim* wildvalue is superseded by
`pveWildValue`'s fold. §4's status column is the authority on what is built (Tier 2a and Tier 3
are not). §4 sorts the backlog by scope *and* by call cost, and cost is the one that constrains
design: an O(1) field can join a module that already makes the call, while anything O(guests) per
poll must be its own module on a long interval. No module is optional; the topology paragraphs
say why topology and ERI modules never can be.

```
scripts/lib/pve_common.groovy    shared preamble: properties, TLS, HTTP, output helpers
scripts/<Subject>.ad.groovy      Active Discovery bodies, named per subject and often shared
scripts/<Module>.collect.groovy  collection bodies, one per module
scripts/<Module>.topo.groovy     TopologySource body; Collector-only, see below
scripts/add*_Proxmox_VE.groovy   PropertySource bodies (addCategory, addERI), one per module
modules/<Module>.json            build-time module definition: metadata + datapoint declarations
build/build.py                   assembles preamble + body into importable module JSON,
                                 and renders dashboards/*.py into dist/dashboards/
build/groovylint.py              bracket-balance check over the assembled scripts
build/dashboards.py              dashboard widget builders + schema/reference validation
tests/harness.groovy             runs the assembled scripts against a mock Proxmox API
tests/fixtures/pve_api.json      recorded API responses, keyed by path + query string
docs/DESIGN.md                   design rationale, the backlog, and §7: the ONLY record
                                 of what is verified and what is not
docs/INSTALL.md                  Proxmox user/token/permissions, module import, which hosts
docs/superpowers/                design specs + implementation plans for past features;
                                 history, linked from DESIGN.md -- not outstanding work
dashboards/<Name>.py             dashboard definitions, one per dashboard — see below
dist/                            GENERATED, gitignored — never edit, never commit
```

**The bodies are not standalone scripts.** They assume the helpers the preamble defines, and
`build.py` concatenates the two. Edit shared behaviour in `pve_common.groovy` once; edit
`dist/` never. The preamble's helpers, by job:

- config: `pveHostProp`, `pveInstanceProp`, `pveConfigError` (set, never thrown)
- HTTP: `pveGet(path)`, which returns the unwrapped `data` and throws on any non-2xx
- discovery: `pveDiscover`, `pveWildValue`, `pveDisplayName`, `pvePropValue`, and the guest
  set both guest bodies share, `pveGuestList` / `pveGuestDiscover`
- output: `pveEmit`, `pveRound`, `pvePercent` (0 when the total is 0), `pveGB` / `pveMB` —
  decimal units, because the portal renders raw bytes as `5.001E11` — and `pveSizeLabel`, the
  size a storage or OSD instance carries in its display name and a guest or node in its
  description ("1.8 TB"), so a percentage graph reads against the size it is a share of
- topology: `pveTopoKey` and `pveTopoNodeKey` / `pveTopoNodeKeys` (the cluster-scoped and the
  name-plus-IP node keys; each must match what `addERI_Proxmox_VE` stamps), `pveTopoIp` (the
  address form both sides agree on), `pveGuestMac`, `pveTopoNodeName`, `pveTopoNodeIdentity`,
  `pveTopoClusterName`, and `pveGuestMacMatch` (what `addERI_Proxmox_VE_Guest` matches a
  guest's MAC prefix with)

**Discovery scripts are shared; collection scripts are not.** Twelve AD bodies back fourteen
multiInstance modules. `Proxmox_VE_OnlineNodes.ad.groovy` backs both NodeDetail and Subscription;
`Proxmox_VE_Guests.ad.groovy` backs both GuestPerformance and Snapshots.
The build emits a *copy* per consuming module as `dist/scripts/<Module>.ad.groovy`, so those
modules discover identical instance sets. Editing a shared AD body changes every module naming it
in `discoveryScript`.

**Seven modules group their instances by node.** GuestStatus, GuestPerformance, Snapshots, Disks,
NodeServices, Certificates and StorageCapacity set `"instanceGroupProperty": "auto.pve_node"`,
which the build turns into the `groupMethod: "ilp"` / `groupExpr` pair a portal export of a
hand-grouped module carries. A migrated guest moves to its new node's group on the next discovery
and keeps its history (DESIGN §7). The build refuses a name without the `auto.` prefix, and the
harness fails any instance of a grouped module that lacks the property. A module with one instance
per node gains nothing from it. `Proxmox_VE_Backups` groups the same way by job instead
(`auto.pve_backup_job`: the job's comment, else its id, or "No backup job").

**The two guest bodies share a helper instead.** `Proxmox_VE_Guests.ad.groovy` backs
GuestPerformance; GuestStatus has its own `Proxmox_VE_GuestStatus.ad.groovy`, because only it
carries the guests' instance ERIs (see the topology paragraphs).
Both build their output through `pveGuestList` and `pveGuestDiscover` in the preamble, so they
still discover the same guests under the same wildvalues and a single guest carries both a
performance and a status instance. Change the guest set or its properties there, never in one body.

**Two bodies discover nodes, and picking the wrong one is a silent bug.**
`Proxmox_VE_Nodes.ad.groovy` discovers *every* node, offline ones included, so a down node still has
an instance to alert on. `Proxmox_VE_OnlineNodes.ad.groovy` filters to `status == 'online'`, and
exists for modules that reach into a node's own API: an offline node cannot answer, and an instance
that can never collect is worse than no instance. A module reading node rows out of
`/cluster/resources` wants Nodes; a module calling `/nodes/{node}/...` wants OnlineNodes.
Both bodies emit the same wildvalue, the node's Proxmox `id`, so a module can move from one to
the other without orphaning its existing instances.

Fifteen DataSources, three PropertySources and one TopologySource. `python build/build.py
--check` prints the count, and is the fastest way to confirm this table has not drifted.

| Module | Method | Interval | Discovery | Calls per interval |
|---|---|---|---|---|
| `Proxmox_VE_Cluster` | script, one instance | 5m | `Cluster.ad` | 4 + 2 per online node (migration history); hourly discovery 1 |
| `Proxmox_VE_Ceph` | script | 5m | — (single instance) | 1, O(1) |
| `Proxmox_VE_Backups` | batchscript | 60m | `Backups.ad` | 3 + 1 per job + 1 per online node, + 1 log per backup run read (normally one) |
| `Proxmox_VE_NodeDetail` | script, per instance | 5m | `OnlineNodes.ad` | 1 per node |
| `Proxmox_VE_Nodes` | batchscript | 5m | `Nodes.ad` | 1, O(1) |
| `Proxmox_VE_GuestPerformance` | batchscript | 5m | `Guests.ad` | 1, O(1) |
| `Proxmox_VE_GuestStatus` | batchscript | 3m | `GuestStatus.ad` | 1, O(1); hourly discovery + 2 + 1 per guest on an online node |
| `Proxmox_VE_CephOSD` | batchscript | 5m | `CephOSD.ad` | 2, O(1) — see below |
| `Proxmox_VE_NodeServices` | batchscript | 5m | `NodeServices.ad` | 1 + 1 per online node |
| `Proxmox_VE_StorageCapacity` | batchscript | 10m | `Storage.ad` | 1, O(1) |
| `Proxmox_VE_Replication` | batchscript | 10m | `Replication.ad` | 1 + 1 per online node |
| `Proxmox_VE_Certificates` | batchscript | 240m | `Certificates.ad` | 1 + 1 per online node |
| `Proxmox_VE_Disks` | batchscript | 240m | `Disks.ad` | 1 + 1 per online node |
| `Proxmox_VE_Subscription` | batchscript | 720m | `OnlineNodes.ad` | 1 + 1 per online node |
| `Proxmox_VE_Snapshots` | batchscript | 60m | `Guests.ad` | 2 + 1 per guest on an online node |
| `addCategory_Proxmox_VE` | PropertySource, AppliesTo `true()` | PropertySource schedule | — | 1, or 2 on Proxmox |
| `addERI_Proxmox_VE` | ERI PropertySource, Collector-only, always on | 30m | — | 1 with a token, 0 without |
| `addERI_Proxmox_VE_Guest` | ERI PropertySource, Collector-only, always on | 30m | — | 0 |
| `Proxmox_VE_Topology` | TopologySource, Collector-only, always on | 60m | — | 3 + 1 per guest on an online node |

The TopologySource, GuestStatus's discovery and Snapshots are the three O(guests) costs, which is
why all three run hourly: the first two read every guest's `config` for its MAC, and Snapshots reads
every guest's snapshot list.

**Never make `Proxmox_VE_Topology` or `addERI_Proxmox_VE` optional.** An ERI module cannot be
turned off cleanly: the keys it stamped stay in `predef.externalResourceID` after it stops
applying, and the portal will not let anyone delete them (DESIGN §7). LogicMonitor's own topology
modules are not optional either. The harness fails if either is marked optional.

**Draw only relationships no other source can see.** The map merges every TopologySource's edges
onto the same vertices, so before adding an edge type, check what the portal already draws between
those resources and add only what is missing. A guest's mounts and shares are seen from inside the
guest by other modules, which is where Media01's Storage edge on the lab map comes from. Which Proxmox
storage holds a guest's disks (Ceph RBD, NFS, iSCSI, ZFS) is visible only through the Proxmox API,
so Storage edges for that are this suite's to draw if they are added. Today it emits Cluster and
Compute edges only.

**`addERI_Proxmox_VE` runs without the token**, and so is not gated
on `hasCategory("ProxmoxVE")` alone. The map needs its key on *every* node's resource, while the token
belongs on one node per cluster — put it on every node and every DataSource collects the whole
cluster once per node. Its AppliesTo,
`hasCategory("ProxmoxVE") || pve.topology.node || (system.sysinfo =~ "-pve" && !(auto.network.mac_address =~ "^[bB][cC]:24:11") && !pve.guest.mac_prefix)`,
finds a node by the token, by the property, or by its Proxmox kernel (`-pve`, from SNMP). An LXC
container reports its host's kernel, so the guest MAC prefix and its override keep containers out
(checked on two nodes, two containers and a Pi, 2026-10-25). It stamps two kinds of key. The name-plus-IP key,
`pveTopoNodeKeys(name, ips)`, needs no cluster name: one key per address in `system.ips`, plus the
local `/cluster/status` row's `ip` where a token is present, with the name from that row or else
`pveTopoNodeName` (`pve.topology.node`, else the short hostname). The TopologySource builds the same
key from each node's `/cluster/status` row, so nodes of every cluster match with no property set. The older `pveTopoKey(cluster, node)` is stamped too wherever the identity is known —
from `/cluster/status` with a token, or `pveTopoNodeIdentity` from the optional
`pve.topology.cluster` without. **A node vertex carries exactly one key**, chosen by
`pveTopoNodeVertexKey`: the IP key, or the cluster-scoped key only when its row has no usable
address. lm.topo joins a vertex's keys into one ID and the portal then matches only a resource
carrying all of them, so a two-key vertex leaves a token-less node unmatched (DESIGN §7,
2026-10-21); the harness refuses one. The key must equal the TopologySource's byte for byte, which
is why both sides go through the same preamble helpers. The cluster vertex is drawn through
`Proxmox_VE_Cluster`'s one instance, `cluster`, which on a cluster carries
`pveTopoKey(cluster, null)` as type `Cluster`; an instance vertex takes edges from every matched
node (DESIGN §7, spike 3 re-run). Do not put a
`pveConfigError` return back at the top of the script; the harness checks for it.

**`addERI_Proxmox_VE_Guest` runs on guests, not on Proxmox**, and is always on. Its AppliesTo,
`(auto.network.mac_address =~ "^[bB][cC]:24:11" || pve.guest.mac_prefix) && (isLinux() || isWindows())`,
reaches every Linux or Windows resource carrying Proxmox's default guest MAC prefix, in either
case, or the override property. The OS clause is deliberate: a virtual appliance (FortiGate-VM,
pfSense, a virtual router) running as a guest has its own ERI modules, and a priority-3 VM label
keyed on MAC prefix alone would override them. The script
matches through `pveGuestMacMatch`, makes no API call, and prints nothing on no match. It stamps
`proxmoxve--vm--<mac>` in category `virt.proxmoxve` at priority **3** with type
`VirtualMachineResource`: the resource type comes from the highest-priority ERI *across*
categories, and the built-in `net.l2` is priority 2, so anything lower leaves the guest
`PhysicalServer` with nothing failing (DESIGN §7, spike 1). A guest never carries the token, so the
preamble always sets `pveConfigError` there — returning on it would make the module a no-op, and
the harness checks for that too.

**There are two collection shapes.** Nodes, GuestPerformance, GuestStatus and StorageCapacity are
the `/cluster/resources` design DESIGN §2 was written for: one call, any cluster size. Five modules
instead fan out over nodes, and they all share
one idiom — filter to online nodes first, then a **per-node `try`**:

```groovy
def nodes = (pveGet('/cluster/resources?type=node') ?: [])
    .findAll { it.status?.toString() == 'online' }
nodes.each { node ->
    // Per-node try: one unreachable node must not cost the others their data.
    try { pveGet('/nodes/' + nodeName + '/disks/list') }
    catch (Exception nodeException) { System.err.println('... skipped for node ' + nodeName) }
}
```

That inner `try` is not the optional-surface `try` described under Style — it logs to `System.err`
and keeps going, so one sick node cannot blank the whole cluster's data. The `online` filter is what
stops the fan-out spending a `pve.api.timeout` on every dead node. Where the same object can be
reported by more than one node, discovery also carries a `seen` set: `Replication.ad` dedupes,
because a job appears on both its source and its target node.

**`Proxmox_VE_CephOSD` looks like fan-out and is not.** It loops online nodes but `break`s on the
first that answers, because `/nodes/{node}/ceph/osd` returns the whole cluster-wide CRUSH tree
whichever node is asked — so it is O(1), and DESIGN §4's table says so explicitly. When no node has
Ceph it prints nothing and returns 0: a fourth exit shape, meaning *nothing to report*, not failure.

**Dashboards are a second artifact with its own source-of-truth split.** `dashboards/<Name>.py`
is the definition — a `build()` returning a `Dashboard` — and `build.py` renders it to
`dist/dashboards/<Name>.json`, generated output like the rest of `dist/`. It imports through
**Dashboards → Add → From File**, not My Module Toolbox, and only after the modules it references.

`build/dashboards.py` holds the widget builders and the validation. Its schema (`REFERENCE_KEYS`,
the enum tuples, `COLUMN_*`, `*_TOP_X`) is copied from LogicMonitor's published Hyper-V and Nutanix
dashboard exports, and `check_dashboard` refuses any key *or value* not in them — the portal drops
either silently. Keep the tables as legacy `dynamicTable`, not the newer `table` widget; the skill's
`references/dashboards.md` has the vocabularies and the reason.

**The check that earns its keep is the module reference.** A widget addresses a module as the plain
string `"<displayedAs> (<name>)"` — `"Proxmox VE Nodes (Proxmox_VE_Nodes)"`. The build resolves
every widget reference against `modules/*.json`, and refuses overlapping widgets. Both checks are
mutation-tested: renaming a datapoint, renaming a module and moving a widget onto another each make
`build.py --check` exit 1.

Two things it cannot check, **both portal-confirmed** (DESIGN §7). Widgets legend on
`##INSTANCE##`, not the `##HOSTNAME##` LogicMonitor's VMware and Hyper-V dashboards use, because
this suite puts every node, guest and storage object on one resource as instances. And a widget
aimed at a *conditional* datapoint renders blank, which is why the cluster tile shows
`ClusterConfigured` (always emitted) rather than `Quorate` (withheld on a standalone host).

**A dashboard looking right in the portal is not evidence that it imported whole.** Count the
widgets — better, export it and count them there. Tier 1 was missing four widgets for a week before
an export showed it.

**The suite is self-applying, and the PropertySource is the hinge.** Every module's AppliesTo is
`hasCategory("ProxmoxVE")` (`addERI_Proxmox_VE` and `addERI_Proxmox_VE_Guest`, above, are the
exceptions); `addCategory_Proxmox_VE` is what sets that category, by calling `/version` and
staying completely silent — exit 0, no output — on any host that is not Proxmox or has no token. Its
own AppliesTo is `true()`, so it runs on every resource in the portal, and that is safe *only*
because of the silence. Break it and the whole suite starts applying itself to unrelated Linux
hosts.

On a host that *is* Proxmox it prints `system.categories=ProxmoxVE`, `pve.version`, `pve.release`
(when present), `pve.clustered` and, on a cluster, `pve.cluster.name`; the harness pins all but
`pve.release`. **The portal stores the last four as `auto.pve.version`, `auto.pve.release`,
`auto.pve.clustered` and `auto.pve.cluster.name`** — LogicMonitor prefixes every property a
PropertySource sets, except `system.categories`, with `auto.`. The script and harness deal in the
bare printed keys; anything that *reads* the property (an AppliesTo, a doc) must use the `auto.`
name, or it silently matches nothing. `auto.pve.clustered` exists so a cluster-only module can
target standalone-versus-cluster in its AppliesTo without a second probe — dropping it breaks the
harness and any future cluster-only module.

## Build and verification

The commands are at the top of this file. The build refuses to emit if a collection script prints an
undeclared datapoint, if a declared datapoint is never printed (unless marked `"conditional": true`
in the module definition), if a `batchscript` module is not `multiInstance` or a `multiInstance`
module has no discovery script, or if brackets are unbalanced in an assembled script, or if a
datapoint uses a name LogicMonitor reserves (`RESERVED_DATAPOINT_NAMES` — `In` was refused by a
portal, so the CephOSD datapoint is `OSDIn`), or if a definition's text field — in practice
`technicalNotes` — passes the portal's 4096-character limit (`check_field_lengths`; the portal's
refusal names no field), or if a module count in the docs disagrees with
`modules/` (`check_doc_counts`). The Docker image, `groovy:4-jdk17`, matches the
Collector's runtime.

**`modules/<Module>.json` is the build's input, not the export format.** `build.py` supplies the
defaults every datapoint shares (`gauge`, `useValue: output`, `interpretMethod: namevalue`,
`dataType 7`, `maxDigits 4`) and derives `interpretExpr` from the datapoint name, so an entry
carries only its name, description and what deviates — `threshold`, `type: derive`, `min`,
`alertBody`, `conditional`. A DataSource's `graphs` list is short too — `title`, `verticalLabel`,
optional `max` (makes the graph rigid: `100` for a percentage, `1` for a 0/1 state) and `scale1024`
(raw bytes only), and `lines` of `dp`, `legend` and optional `color`/`type` — and `expand_graph`
turns it into the key set of LogicMonitor's own VMware export. `check_graphs` refuses a line naming
an undeclared datapoint, an unknown key, a line type that export does not use, a colour outside
`GRAPH_COLORS` (six that stay distinguishable on screen), or one colour twice in a graph. A
percentage always has a graph of its own, never an axis shared with bytes, MB or GB; the other
figures about one subject (used, free, total) share one graph. The Active Discovery block, `deleteInactiveInstances: false` included,
is hardcoded in `build_module` rather than set per module.

**The interval field differs by module type**, so don't copy it across types. DataSources take
`collectionInterval` as a string (`"5m"`). The TopologySource takes `collectionIntervalSec` as an
integer (`3600`). An ERI PropertySource needs `"eri": true` plus `collectionInterval` as integer
seconds (`1800`). A plain PropertySource such as `addCategory_Proxmox_VE` carries neither, and
runs on the portal's PropertySource schedule. The build follows LogicMonitor's own exports here.

Several fields there are load-bearing and validated by nothing. `useWildValueAsUniqueIdentifier:
true` is set on every multiInstance module and checked by neither the build nor the harness — it is
what makes the wildvalue the instance's identity, which is the entire point of the migration-safe
IDs below. `threshold` is LogicMonitor's `"<op> warn error critical"` string (`"> 90 95 98"`).
`technicalNotes` carries the per-module rationale shown in the portal; every module has one and
Exchange review expects it. Of the fourteen modules that discover, ten override `discoveryInterval`
to `60m`; the four slow ones — Certificates, Disks, NodeServices, Subscription — state `1440m`
explicitly, which is also the build default.

**There is no way to run a single test.** `tests/harness.groovy` is one program: it walks every
`modules/*.json`, runs that module's assembled AD and collect scripts against an in-process mock
API, then adds fixed assertions that pin the design decisions (template exclusion, migration-safe
instance IDs, QEMU vs LXC disk, exit 2 on a dead API or bad token, bulk endpoint used and `rrddata`
not). It finishes in seconds — run all of it. To poke at one script instead, override the service
command: `docker compose -f tests/docker-compose.yml run --rm tests groovy /work/tests/scratch.groovy`.

**The mock API returns 501, not 404, for a path it has no fixture for**, so a missing fixture looks
like a harness gap rather than an empty result. Fixture keys in `tests/fixtures/pve_api.json` are
the path after `/api2/json` plus the query string exactly as the script requests it
(`/cluster/resources?type=vm`). A new endpoint needs a fixture before its module can be tested.

**The fixture cluster is `pve1` online and `pve2` offline.** That is why only `/nodes/pve1/*`
fixtures exist: every fan-out module filters to online nodes, so it reaches exactly one. Bringing
`pve2` online in the fixture means adding a second copy of every per-node fixture, or five modules
start taking the mock's 501.

The harness is mutation-tested. If you change it, re-verify it still fails when you deliberately
break something; a green suite that cannot go red is worse than no suite.

**What the harness does not prove:** that the module JSON imports into a real portal, or that
anything works against a real Proxmox host. Both need the user's environment. Do not describe a
change as verified end-to-end on the strength of a green harness run — say which of the two layers
was actually exercised.

For future live-cluster testing, use a read-only test token, measure Collector request counts,
and verify the four bulk DataSources issue one `/cluster/resources` request each per interval.
Do not replace this with a per-guest production test loop. For live HA testing, cover quorum
loss/recovery, node changes, guest migration or failover, HA error/fence states, and temporary
HA endpoint failure; confirm existing instances remain intact on non-zero collection exits.

## How this repo meets the Collector contract

The contract itself — bindings, Active Discovery line format, Script vs BatchScript output,
`##WILDVALUE##.` keys, numeric values, `derive` vs `gauge`, exit codes, `.credential` masking — is in
the `logicmonitor-modules` skill (`SKILL.md` and `references/collector-scripting.md`). This section
is only where this repo implements it, and what it has cost here.

**Property lookup.** Resource properties (`pve.api.url`, `pve.api.token.credential`, ...) come from
`hostProps`. Instance properties set by Active Discovery go through `pveInstanceProp` in the
preamble and nothing else — it handles the `taskProps`/`instanceProps` and `auto.` variants, and
the bindings' absence during AD.

**The token rename has no compatibility read, and it fails quietly.** The property is
`pve.api.token.credential` so the portal masks it, and `pveHostProp('pve.api.token.credential')` is
the only lookup. A resource still carrying the old `pve.api.token` sets `pveConfigError`, which
sends the PropertySource down its silent path, so the category is never set and every module stops
applying. The resource goes *quiet* rather than erroring. On an existing deployment, rename the
property first, then wait for the PropertySource's next scheduled run (the portal cannot force one).

**Active Discovery** goes through `pveDiscover`, which drops blank property values; `pvePropValue`
URL-encodes values, so names, pools and tags stay readable; `pveWildValue` folds only the wildvalue
to `[A-Za-z0-9_-]`.

**BatchScript keys.** `build.py` applies the `##WILDVALUE##.` prefix to `interpretExpr` for
`batchscript` modules, withholds it for `script` modules, and refuses to emit if they do not match.
This suite shipped once without it: build, compile, harness and the portal's Test Collection Script
all looked correct while every datapoint read No Data. If a batchscript module collects nothing,
check `interpretExpr` in `dist/<Module>.json` before touching anything else.

**Numeric values.** The harness asserts every emitted value against `isNumeric` in
`tests/harness.groovy`. Proxmox's string-typed fields — `loadavg` items, `status`, HA state — are
converted or mapped before `pveEmit`: NodeDetail does `toString().toDouble()`, GuestStatus maps
`running` to 1.

**Metric types.** Proxmox `netin` / `netout` / `diskread` / `diskwrite` are cumulative since guest
start, so they are `derive` with a min of 0; `cpu`, `mem`, `uptime`, storage `used` are `gauge`.

**Instance IDs must round-trip, and must not encode location.** Wildvalues come from Proxmox's own
resource id (`qemu/101`, `node/pve1`, `storage/pve1/local`) folded by `pveWildValue`. A guest id
built from the node name would change on every live migration or HA failover and lose the
instance's history, so never reintroduce the node name into a guest wildvalue. Changing an ID
scheme orphans every already-discovered instance in an end user's portal: change discovery and
collection together and say so explicitly.

**The rule is about things that move.** The fan-out modules deliberately *do* put the node in the
wildvalue — `pve1-sda`, `pve1-pveproxy-ssl` — because a physical disk, a certificate and a systemd
unit are node-bound and would collide across nodes without it. Guests migrate; disks do not.
Replication is the case worth copying when it is not obvious: its wildvalue is the bare job id
(`100-0`), with no node, because the job follows the guest.

## Style

Conventionally formatted Groovy, four-space indent, single quotes, explicit `return 0` / `return 2`.
The scripts are Groovy 4 clean (the Collector's runtime) — no `List.push`/`pop`, `XmlSlurper` or
`LazyMap` — keep them that way.
Datapoint names are PascalCase to match LogicMonitor's published suites (`CPUUsagePercent`,
`DataRateRxMB`, `UsedPercent`) — camelCase would be flagged in Exchange review.

**Use `HttpURLConnection`, never `com.santaba.agent.groovyapi.http.HTTP`.** The santaba class is
Collector-only and would cost us the compile check and the harness (DESIGN §6, item 9).
`docs/INSTALL.md`'s appendix covers the `Logic.Monitor` PowerShell module, whose
`Invoke-LMCollectorDebugCommand` runs `!groovy` on a real Collector — the closest thing to a live
test harness this project has.

**Three scripts break that rule, and only those three.** `Proxmox_VE_Topology`,
`addERI_Proxmox_VE` and `addERI_Proxmox_VE_Guest` import `com.logicmonitor.mod.Snippets`
(`lm.topo`), because the ERI and topology output formats are too poorly documented to reimplement
(DESIGN §6). `build.py` emits every ERI PropertySource and the TopologySource to
`dist/scripts/collector-only/`, which the compile glob does not reach, and the harness skips
`topologysource` and `propertysource` modules. That is why their Proxmox-side logic lives in
preamble helpers (`pveTopoKey`, `pveGuestMac`, `pveTopoNodeIdentity`, `pveGuestMacMatch`) that the
harness *can* reach. Do not add a fourth script without the same treatment, and do not move these
three back.

The opt-in `pve.api.insecure` TLS bypass is lab-only and must stay opt-in and default-off.

Every body opens with the same guard, because the preamble reports missing configuration through
`pveConfigError` rather than throwing: DataSource bodies print it to `System.err` and `return 2`,
and `addCategory_Proxmox_VE` returns 0 silently. The two ERI PropertySources are the deliberate
exceptions: `addERI_Proxmox_VE` and `addERI_Proxmox_VE_Guest` never return on `pveConfigError`,
because they must run without the token (see their paragraphs above). Everything after the guard
sits in a `try` whose `catch` writes the reason to `System.err` and returns 2 (the
PropertySources return 0 silently instead). An optional surface that may legitimately be absent — HA
status on a cluster without HA — gets its own inner `try` and simply omits its datapoints.

## Editing checklist

Adding or changing a datapoint touches four places: the `pveEmit` call in the collection script,
the `datapoints` array in `modules/<Module>.json`, a line in one of that file's `graphs` (related
datapoints share a graph; unrelated ones never do, or the graph becomes unreadable), and the coverage
table in `README.md`. The build enforces the first two agreeing and that every graph line names a
declared datapoint; nothing enforces the rest.

**Adding a module drifts more than that table.** Module counts are written out in prose in
`README.md`, `docs/INSTALL.md`, `docs/DESIGN.md` §4 and §7, and this file. `check_doc_counts` in
`build.py` fails the build when a numbered phrase disagrees with `modules/` — `fourteen
DataSources`, `3 PropertySources`, `all 19 modules`, `all nineteen:` — but only those shapes; a
count phrased any other way (`the fourteen modules that discover`) is still unchecked.

**Verification status belongs in exactly one place: `docs/DESIGN.md` §7** — including this file,
which says *what* to verify but not *when* it was. Copies elsewhere go stale unnoticed; record it
in §7 and link.

**The drift check is a regex, not an interpreter.** `build.py` finds emitted names with
`pveEmit(<anything>, '<Literal>'`. A datapoint name held in a variable or built by concatenation is
invisible to it and will pass the build while printing an undeclared datapoint on a live Collector.
Always pass datapoint names as single-quoted literals.

**Adding a dashboard** means a new `dashboards/<Name>.py` exposing `build() -> Dashboard`; the
build finds it by glob and validates it in the same pass as the modules. Reuse the widget helpers
in `build/dashboards.py` rather than hand-writing widget dicts — they are what keep the key sets
matching the reference exports.

**Adding a module** means a new `modules/<Module>.json` (the build finds definitions by glob), a
collect body, an AD body if it is `multiInstance` — reuse an existing one where the instance set is
the same — a row in the README table, and a fixture for every endpoint it calls. Anything
O(guests) per poll goes on a long interval; no module is optional. If the module
cannot be verified against the user's own environment, it also needs a row in `docs/DESIGN.md` §7
and a `Limitation:` sentence in its `technicalNotes` telling the end user what has not been tested,
in their terms (no dates, design references or verification history). A green harness on a
hand-written fixture proves the parsing, not the shape, so a new module keeps the sentence until
someone runs it. `grep -l Limitation: modules/*.json` lists the modules carrying one.

**End-user-facing text explains, it does not narrate.** The scripts and `technicalNotes` ship to
every end user. Comments say what the code does and the constraint that keeps it that way, stated
as current fact; how a decision was reached, when something was learned, and what is verified
belong in `docs/DESIGN.md`, never in a script or a note. `build.py` and the harness are
developer tooling and may point into DESIGN freely.

**A per-instance `script` module needs one thing more.** `Proxmox_VE_NodeDetail` and
`Proxmox_VE_Cluster` are both `script` and `multiInstance`. Cluster has one instance and reads no
instance property; NodeDetail executes once per node and reads its instance properties through
`pveInstanceProp` (see Property lookup). The harness fakes those in the
`instanceBindings` map in `tests/harness.groovy`, hardcoded to `pve1` so it lines up with the
`/nodes/pve1/status` fixture. A new module that calls `pveInstanceProp` needs an entry there plus a
fixture keyed to the same node name — without it the collect script runs with no `taskProps`, and
the harness reports a collection failure rather than a missing binding.

Mark a datapoint `"conditional": true` only when the API genuinely has no value to report — QEMU
used-disk, cluster quorum on a standalone host — so the datapoint reads as no-data. Do not emit a
zero to keep the build quiet; a confident wrong number is worse than an absent one.

## Proxmox API facts

The auth header, required privileges and the schema quirks live in `docs/DESIGN.md` §2 (*API
details that bite*), verified against the published schema. Read it before touching an HTTP call
or a field parse. The ones that fail silently: the header is `'PVEAPIToken=' + apiToken` (`=`, not
`-`); a privilege-separated token needs its own ACL; an under-privileged token yields *empty
discovery*, not an error; `loadavg` items are strings; templates must be filtered from guest
discovery; and QEMU guests have no used-disk figure.
