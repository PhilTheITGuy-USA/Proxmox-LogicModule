# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A LogicMonitor **LogicModule suite** for Proxmox VE, monitored over its HTTPS JSON API
(`/api2/json/...`). There is no application here — Groovy scripts are embedded in DataSources and
executed by a LogicMonitor Collector. The target is a suite publishable to the LM Exchange that
works unchanged from a single node to a large enterprise cluster; see `docs/DESIGN.md` for the
parity analysis against LogicMonitor's VMware/Hyper-V/Nutanix suites and the backlog.

**`docs/DESIGN.md` §1-§6 is a design record, not outstanding work.** Its §6, "Things that must
change from the current implementation", reads like a to-do list but every one of its ten items is
implemented, and its §3 instruction to use the Proxmox `id` *verbatim* as the wildvalue is
superseded by `pveWildValue`'s fold to `[A-Za-z0-9_-]`. In §4, Tier 1, Tier 1a and all eight Tier 2
modules are built, and so is the TopologySource; Tier 2a (BackupStatus, Migrations, NodeNetwork)
and Tier 3 are not. §4's own status column is the authority on what exists.

**§4 sorts the backlog on two axes, and they are independent.** *Scope* (Tier 1 core parity, Tier 2
infrastructure, Tier 2a workload, Tier 3 per-guest detail) says whether something is worth
monitoring; *cost* (O(1) per cluster, O(nodes), O(guests)) says what collecting it does to the
architecture. Cost is the one that constrains design: an O(1) field can join a module that already
makes the call — Tier 1a is a list of fields already fetched and discarded — while anything
O(guests) has to be its own opt-in module on a long interval, because one call per guest per
interval is precisely what §2 was written to avoid.

```
scripts/lib/pve_common.groovy    shared preamble: properties, TLS, HTTP, output helpers
scripts/<Subject>.ad.groovy      Active Discovery bodies, named per subject and often shared
scripts/<Module>.collect.groovy  collection bodies, one per module
scripts/<Module>.topo.groovy     TopologySource body; Collector-only, see below
scripts/addCategory_*.groovy     PropertySource bodies, likewise one per module
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
dashboards/<Name>.py             dashboard definitions, one per dashboard — see below
dist/                            GENERATED, gitignored — never edit, never commit
```

**The bodies are not standalone scripts.** They assume the helpers the preamble defines, and
`build.py` concatenates the two. Edit shared behaviour in `pve_common.groovy` once; edit
`dist/` never.

**Discovery scripts are shared; collection scripts are not.** Nine AD bodies back eleven
multiInstance modules. `Proxmox_VE_Guests.ad.groovy` backs both GuestPerformance and GuestStatus;
`Proxmox_VE_OnlineNodes.ad.groovy` backs both NodeDetail and Subscription. The build emits a *copy*
per consuming module as `dist/scripts/<Module>.ad.groovy`, so those modules discover identical instance sets and a
single guest carries both a performance and a status instance. Editing a shared AD body changes
every module naming it in `discoveryScript`.

**Two bodies discover nodes, and picking the wrong one is a silent bug.**
`Proxmox_VE_Nodes.ad.groovy` discovers *every* node, offline ones included, so a down node still has
an instance to alert on. `Proxmox_VE_OnlineNodes.ad.groovy` filters to `status == 'online'`, and
exists for modules that reach into a node's own API: an offline node cannot answer, and an instance
that can never collect is worse than no instance. A module reading node rows out of
`/cluster/resources` wants Nodes; a module calling `/nodes/{node}/...` wants OnlineNodes.
NodeDetail discovered through Nodes until 2026-09-23 and was moved; both bodies emit the same
wildvalue, the node's Proxmox `id`, which is what made the move safe for existing instances.

Fourteen DataSources, two PropertySources and one TopologySource. `python build/build.py
--check` prints the count, and
is the fastest way to confirm this table has not drifted.

| Module | Method | Interval | Discovery | Calls per interval |
|---|---|---|---|---|
| `Proxmox_VE_Cluster` | script | 5m | — (single instance) | 3, O(1) |
| `Proxmox_VE_Ceph` | script | 5m | — (single instance) | 1, O(1) |
| `Proxmox_VE_BackupCoverage` | script | 60m | — (single instance) | 1, O(1) |
| `Proxmox_VE_NodeDetail` | script, per instance | 5m | `OnlineNodes.ad` | 1 per node |
| `Proxmox_VE_Nodes` | batchscript | 5m | `Nodes.ad` | 1, O(1) |
| `Proxmox_VE_GuestPerformance` | batchscript | 5m | `Guests.ad` | 1, O(1) |
| `Proxmox_VE_GuestStatus` | batchscript | 3m | `Guests.ad` | 1, O(1) |
| `Proxmox_VE_CephOSD` | batchscript | 5m | `CephOSD.ad` | 2, O(1) — see below |
| `Proxmox_VE_NodeServices` | batchscript | 5m | `NodeServices.ad` | 1 + 1 per online node |
| `Proxmox_VE_StorageCapacity` | batchscript | 10m | `Storage.ad` | 1, O(1) |
| `Proxmox_VE_Replication` | batchscript | 10m | `Replication.ad` | 1 + 1 per online node |
| `Proxmox_VE_Certificates` | batchscript | 240m | `Certificates.ad` | 1 + 1 per online node |
| `Proxmox_VE_Disks` | batchscript | 240m | `Disks.ad` | 1 + 1 per online node |
| `Proxmox_VE_Subscription` | batchscript | 720m | `OnlineNodes.ad` | 1 + 1 per online node |

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
`dist/dashboards/<Name>.json`. The rendered JSON is generated output like everything else under
`dist/`: never edit it, never commit it. `build/dashboards.py` holds the widget builders and the
validation. A dashboard is a different LogicMonitor resource from a LogicModule and imports
through **Dashboards → Add → From File**, not My Module Toolbox.

The schema in `build/dashboards.py` was taken from LogicMonitor's own published exports
(`logicmonitor/dashboards`, Apache-2.0 — `Virtualization/Hyper-V.json` for cgraph, dynamicTable,
noc and alert; `Virtualization/Nutanix.json` for bigNumber), the same way the module export field
names were. `REFERENCE_KEYS` and the enum tuples record what those files actually contain, and
`check_dashboard` refuses anything this project invented on its own — an unexpected key breaks an
import as readily as a missing one, and neither reports an error in the portal.

**"Invented" means the values too, not just the keys, and that lesson cost a portal round trip.**
On 2026-09-22 a portal silently discarded nine of the Tier 2 dashboard's fifteen widgets and four
of Tier 1's twenty, reporting only `Some widgets could not be created due to incompatible version
or configuration errors` — once, on first load, naming no widget. The dashboard imported, the
survivors looked perfect, and the gaps closed up because the grid floats widgets upward. Every
casualty carried a value that appears nowhere in the reference exports: `displayType: "number"` on
a column (the real vocabulary is `percent` and `raw`), `topX: 20` on a cgraph (only `10` and `25`
exist), `topX` of `50`/`100` on a table (only `25` and `-1`), `colorThresholds: null` where the
references always carry a list, and per-column `minValue`/`maxValue` other than `0`/`100` — the
references use `0..100` on *every* column, including `raw` ones like Nutanix's `IOPs` whose values
run far past 100, so the bounds are read for the percent bar and ignored otherwise. `COLUMN_*`,
`CGRAPH_TOP_X` and `TABLE_TOP_X` in `build/dashboards.py` now pin all of it and `_check_column`
enforces it, mutation-tested one field at a time. The `column()` helper no longer accepts bounds
at all, which is the only way to keep them from drifting back. A dashboard `description` is
capped at **256 characters** — the portal truncates past it and says nothing — so
`check_dashboard` measures it; Tier 2's was 266 and lost its last sentence.

**Do not migrate the tables to the portal's newer `table` widget.** A current portal builds tables
as `type: "table"` with `displaySettings.columnsV4` and serialises its rows as resolved integers —
`deviceId`, `instanceId`, `dataPointId`, `dataSourceId`. Those are account-specific, so such a
dashboard cannot ship to anyone else's portal. The legacy `dynamicTable` still imports and is the
only form that expresses "every instance, wherever this resource lives" as globs.

**The check that earns its keep is the module reference.** A widget addresses a module as
`"<displayedAs> (<name>)"` — `"Proxmox VE Nodes (Proxmox_VE_Nodes)"` — a plain string with
nothing in LogicMonitor enforcing it. Rename a module, its `displayedAs`, or a datapoint, and
every widget pointing at it is **discarded at import** — confirmed on 2026-09-22, when the Tier 2
dashboard was imported one module-rebuild too early and lost exactly the one widget naming a
datapoint (`SizeGB`) the portal's copy of that module did not yet have. It fails the same silent
way as the invented values above, which also means **a module the dashboard depends on must be
imported before the dashboard**, not after. So the build resolves
every widget reference against `modules/*.json` and fails if one does not exist. It also refuses
overlapping widgets, which render on top of each other rather than erroring. Both checks are
mutation-tested: renaming a datapoint, renaming a module and moving a widget onto another each
make `build.py --check` exit 1.

Two things it cannot check, **both confirmed in a portal on 2026-09-15** rather than inferred.
Widgets legend on `##INSTANCE##`, not the `##HOSTNAME##` that LogicMonitor's own VMware and
Hyper-V dashboards use — those suites give each hypervisor its own resource, while this one puts
every node, guest and storage object on a single resource as instances, so legending on hostname
gives every series the same label. And a widget aimed at a *conditional* datapoint renders blank
rather than erroring, which is why the cluster tile shows `ClusterConfigured` (always emitted)
rather than `Quorate` (withheld on a standalone host).

`Proxmox_VE_Tier1` imported and populated with live data, which is the only proof available that
the `"<displayedAs> (<name>)"` reference form and the `##INSTANCE##` legend are right — both are
plain strings LogicMonitor does not validate, and either being wrong yields a dashboard that
imports cleanly and renders nothing. A new dashboard should copy those two conventions rather
than re-deriving them.

It populated **sixteen of its twenty widgets**, not all twenty as this file claimed until
2026-09-22; the four missing were `dynamicTable`s dropped at import for the invented values
above, and a portal export is what revealed it. The lesson is that *a dashboard looking right in
the portal is not evidence that it imported whole* — count the widgets, or better, export it and
count them there. `Proxmox_VE_Tier2` reached 15 of 15 on 2026-09-22, once the module it depends
on was re-imported first.

**The suite is self-applying, and the PropertySource is the hinge.** Every module's AppliesTo is
`hasCategory("ProxmoxVE")`; `addCategory_Proxmox_VE` is what sets that category, by calling
`/version` and staying completely silent — exit 0, no output — on any host that is not Proxmox or
has no token. Its own AppliesTo is `true()`, so it runs on every resource in the portal, and that
is safe *only* because of the silence. Break it and the whole suite starts applying itself to
unrelated Linux hosts.

It used to ship as a script to paste into the UI, because no published sample was available to
verify the PropertySource export schema against. Two exports settled that on 2026-09-22: `type` 5
for every PropertySource, with `script` as `{type, content}`, and an **ERI** one additionally
carrying `propertySourceType: 1` and a `collectionInterval` that a plain one omits entirely. Both
kinds are now ordinary importable modules.

On a host that *is* Proxmox it prints `system.categories=ProxmoxVE`, `pve.version`, `pve.release`
(when present) and `pve.clustered`; the harness pins all but `pve.release`. `pve.clustered` exists
so a cluster-only module can target standalone-versus-cluster in its AppliesTo without a second
probe — dropping it breaks the harness and any future cluster-only module.

## Build and verification

```sh
python build/build.py           # assemble dist/
python build/build.py --check   # validate only

docker compose -f tests/docker-compose.yml run --rm compile   # groovyc, Groovy 4
docker compose -f tests/docker-compose.yml run --rm tests     # mock-API integration
```

**Rebuild before you verify.** Both Docker jobs read `dist/scripts/`, never `scripts/`, so an
un-rebuilt edit is silently tested in its previous form. The loop is always
`python build/build.py` → `compile` → `tests`.

The build refuses to emit if a collection script prints an undeclared datapoint, if a declared
datapoint is never printed (unless marked `"conditional": true` in the module definition), if a
`batchscript` module is not `multiInstance` or a `multiInstance` module has no discovery script, or
if brackets are unbalanced in an assembled script, or if a datapoint uses a name LogicMonitor
reserves (`RESERVED_DATAPOINT_NAMES` — `In` was refused by a portal, so the CephOSD datapoint is
`OSDIn`). Groovy is not installed on this machine — Docker is how these get compiled and run, and
`groovy:4-jdk17` matches the Collector's runtime.

**`modules/<Module>.json` is the build's input, not the export format.** `build.py` supplies the
defaults every datapoint shares (`gauge`, `useValue: output`, `interpretMethod: namevalue`,
`dataType 7`, `maxDigits 4`) and derives `interpretExpr` from the datapoint name, so an entry
carries only its name, description and what deviates — `threshold`, `type: derive`, `min`,
`alertBody`, `conditional`. The Active Discovery block, `deleteInactiveInstances: false` included,
is hardcoded in `build_module` rather than set per module.

Several fields there are load-bearing and validated by nothing. `useWildValueAsUniqueIdentifier:
true` is set on every multiInstance module and checked by neither the build nor the harness — it is
what makes the wildvalue the instance's identity, which is the entire point of the migration-safe
IDs below. `threshold` is LogicMonitor's `"<op> warn error critical"` string (`"> 90 95 98"`).
`technicalNotes` carries the per-module rationale shown in the portal; every module has one and
Exchange review expects it. Of the eleven modules that discover, seven override `discoveryInterval`
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

## The Collector contract (get these wrong and the DataSource silently breaks)

**Property lookup.** Resource-level properties (`pve.api.url`, `pve.api.token.credential`, ...) come from
`hostProps`. Instance-level properties set by Active Discovery come from `taskProps` /
`instanceProps`, and may be prefixed `auto.`. `pveInstanceProp` in the preamble is the only correct
way to read them: it tries `taskProps[key]`, `taskProps['auto.'+key]`, `instanceProps[key]`,
`instanceProps['auto.'+key]` in order, guarded by `binding.hasVariable` because neither binding
exists during Active Discovery. Reading an instance property out of `hostProps` returns null and the
script fails at runtime, not at edit time.

**Secrets are named by suffix.** LogicMonitor treats a property whose name ends in `.credential` as
sensitive and masks its value in the UI. That is the whole reason the token property is
`pve.api.token.credential` rather than `pve.api.token`: renaming it to anything without the suffix
silently exposes the secret in the portal. Any future secret this suite consumes takes the same
suffix.

**That rename has no compatibility read, and it fails quietly.** `pveHostProp('pve.api.token.credential')`
is the only lookup — nothing falls back to the old name. A resource still carrying `pve.api.token`
therefore sets `pveConfigError`, which sends the PropertySource down its silent path (exit 0, no
output), so the category is never set and every module stops applying. The resource goes *quiet*
rather than erroring, which looks nothing like a credentials problem. On an existing deployment,
rename the property first, then re-run the PropertySource.

**Active Discovery output.** One instance per line, exactly:

```
instanceId##displayName##description####auto.key=value&auto.key2=value2
```

Four `#` before the property list, two elsewhere. The minimal form is just `wildvalue##wildalias`.

Only the **instance ID (wildvalue)** is character-restricted — no spaces, colons, equals signs,
backslashes or hashes. The display name and description may contain spaces and punctuation, and
LogicMonitor's own reference discovery script URL-encodes *property values* (`URLEncoder.encode`),
which is what `pvePropValue` does — names, pools and tags stay readable and reversible. Only the
wildvalue is character-folded, by `pveWildValue`. Never emit a property with a blank value — it logs
an error on the discovery task, which is why `pveDiscover` drops empty ones. All instance properties on a resource share a
49,000-character budget.

**Collection output.** Depends on the collection method:

- **Script** (one execution *per instance*, per interval): `key=value` lines for that one instance.
- **BatchScript** (one execution *per resource*, per interval): `instanceId.key=value` lines, where
  `instanceId` is the wildvalue the AD script emitted.

Datapoints are configured with Raw Metric `output` and Post Processor `namevalue(<key>)` — in the
export JSON, `"useValue": "output"` with `"interpretMethod": "namevalue"` and `"interpretExpr"` set
to the key. On a Script module the key is the bare datapoint name. On a BatchScript it is not: one
execution prints every instance into a single stream, so a bare `CPUUsagePercent` matches no line,
and the key must be `##WILDVALUE##.CPUUsagePercent`, which LogicMonitor substitutes per instance at
poll time. `build.py` applies the prefix for `batchscript` modules, withholds it for `script`
modules, and refuses to emit if a module's keys do not match its collection method. Either way, a
datapoint declared in `modules/<Module>.json` must be printed on every successful run unless it is
marked `"conditional": true`.

This is the highest-consequence mistake in the repo, because every other signal stays green: the
build passes, the scripts compile, the harness goes green, Active Discovery populates instances, and
**Test Collection Script in the portal shows perfectly correct output** — while every datapoint on
every instance reads No Data. It shipped that way once. If a batchscript module collects nothing,
check `interpretExpr` in `dist/<Module>.json` before you touch anything else.

**Every emitted value must be a number.** The harness asserts each one matches
`^-?\d+(\.\d+)?([eE][-+]?\d+)?$` (`isNumeric` in `tests/harness.groovy`). Proxmox fields that
are typed as strings — `loadavg` items, `status`, HA state — must be converted or mapped before
`pveEmit`: NodeDetail does `toString().toDouble()`, GuestStatus maps `running` to 1. A string reaches
LogicMonitor as no-data and fails the harness.

**Datapoint metric type is not always Gauge.** The API enum (`type` on the datapoint object) is
`0 unknown, 1 counter, 2 gauge, 3 derive, 5 status, 6 compute, 7 counter32, 8 counter64`; `dataType`
is separately `1 boolean, 2 byte, 3 short, 4 int, 5 long, 6 float, 7 double, 8 ulong`. Proxmox
`netin` / `netout` / `diskread` / `diskwrite` are **cumulative counters since guest start**, so they
belong as `derive` (resets to 0 on guest restart are handled by a min of 0), not `gauge`. Anything
that is a point-in-time reading — `cpu`, `mem`, `uptime`, storage `used` — is genuinely `gauge`.

**Exit codes.** `return 0` on success, `return 2` on any API/config failure — and always write the
reason to `System.err`. This is deliberate: a non-zero exit makes LogicMonitor keep existing
instances through a transient Proxmox outage instead of deleting them. Never "handle" an API error
by returning 0 with zeroed datapoints.

**Auth header.** Proxmox expects `Authorization: PVEAPIToken=USER@REALM!TOKENID=SECRET` — the
separator after `PVEAPIToken` is `=`, not `-`. The `pve.api.token.credential` property already contains the
whole `user@realm!tokenid=secret` string, so the header is built as `'PVEAPIToken=' + apiToken`.

**Instance IDs must round-trip, and must not encode location.** Wildvalues come from Proxmox's own
resource id (`qemu/101`, `node/pve1`, `storage/pve1/local`) with `/` and `.` folded to `-`. Two
reasons, both load-bearing: a guest id built from the node name changes on every live migration or
HA failover, which makes LogicMonitor delete the instance and lose its history; and `.` separates
instance from datapoint in BatchScript output, so a storage name containing one would be ambiguous.
Never reintroduce the node name into a guest wildvalue. Changing an ID scheme orphans every
already-discovered instance in a customer's portal, so change discovery and collection together and
say so explicitly.

**The rule is about things that move.** The fan-out modules deliberately *do* put the node in the
wildvalue — `pve1-sda`, `pve1-pveproxy-ssl` — because a physical disk, a certificate and a systemd
unit are node-bound and would collide across nodes without it. Guests migrate; disks do not.
Replication is the case worth copying when it is not obvious: its wildvalue is the bare job id
(`100-0`), with no node, because the job follows the guest.

## Style

Conventionally formatted Groovy, four-space indent, single quotes, explicit `return 0` / `return 2`.
Datapoint names are PascalCase to match LogicMonitor's published suites (`CPUUsagePercent`,
`DataRateRxMB`, `UsedPercent`) — camelCase would be flagged in Exchange review.

`HttpURLConnection` is used rather than LogicMonitor's `com.santaba.agent.groovyapi.http.HTTP`. That
is a deliberate departure from house style: the santaba class is Collector-only, and depending on it
would make every script impossible to compile or run outside a Collector, costing us the compile
check and the whole test harness. See `docs/DESIGN.md` §6.

**Two scripts break that rule, and only those two.** `Proxmox_VE_Topology` and
`addERI_Proxmox_VE` import `com.logicmonitor.mod.Snippets`, because LogicMonitor's own topology and
ERI modules produce their output through the `lm.topo` snippet — `eriPreProcessor`, `isMac`,
`emitEri`, `printEriArray`, `generateTopology` — and those formats are not documented well enough
to reimplement. Hand-writing them would mean inventing a format, which is the failure this suite
has actually suffered. `build.py` emits both to `dist/scripts/collector-only/`, a directory the
compile job's `dist/scripts/*.groovy` glob does not reach, and the harness skips modules whose
`moduleType` is `topologysource` or `propertysource`. Their Proxmox-side logic lives in preamble
helpers — `pveTopoKey`, `pveGuestMac` — precisely so the harness can still reach it. Do not add a
third without the same treatment, and do not move these two back.

The opt-in `pve.api.insecure` TLS bypass is lab-only and must stay opt-in and default-off.

Every body opens with the same guard, because the preamble reports missing configuration through
`pveConfigError` rather than throwing: DataSource bodies print it to `System.err` and `return 2`,
the PropertySource returns 0 silently. Everything after it sits in a `try` whose `catch` writes the
reason to `System.err` and returns 2. An optional surface that may legitimately be absent — HA
status on a cluster without HA — gets its own inner `try` and simply omits its datapoints.

## Editing checklist

Adding or changing a datapoint touches three places: the `pveEmit` call in the collection script,
the `datapoints` array in `modules/<Module>.json`, and the coverage table in `README.md`. The build
enforces the first two agreeing; nothing enforces the third.

**Adding a module drifts more than that table.** Module counts are written out in prose in
`README.md`, `docs/INSTALL.md` and `docs/DESIGN.md` §4 and §7. Nothing checks any of them. Grep
the docs for the previous count before claiming a module is done.

**Verification status belongs in exactly one place: `docs/DESIGN.md` §7.** It used to be restated
in README, INSTALL and a whole `docs/VALIDATION.md`, which is how "Tier 1 imports all twenty
widgets" stayed written down for a week after it stopped being true. Record it in §7 and link.

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
the same — a row in the README table, and a fixture for every endpoint it calls. If the module
cannot be verified against the user's own environment, it also needs an `UNVERIFIED` paragraph in
its `technicalNotes` naming what is unproven, and a row in `docs/DESIGN.md` §7. No module is in
that state today; the last two, `Proxmox_VE_Topology` and `addERI_Proxmox_VE`, were confirmed in a
portal on 2026-09-23. A green
harness on a hand-written fixture proves the parsing, not the shape, so a new module keeps the
note until someone runs it.

**A per-instance `script` module needs one thing more.** `Proxmox_VE_NodeDetail` is the only module
that is both `script` and `multiInstance`: it executes once per node and reads its instance
properties through `pveInstanceProp` (see Property lookup). The harness fakes those in the
`instanceBindings` map in `tests/harness.groovy`, hardcoded to `pve1` so it lines up with the
`/nodes/pve1/status` fixture. A new module that calls `pveInstanceProp` needs an entry there plus a
fixture keyed to the same node name — without it the collect script runs with no `taskProps`, and
the harness reports a collection failure rather than a missing binding.

Mark a datapoint `"conditional": true` only when the API genuinely has no value to report — QEMU
used-disk, cluster quorum on a standalone host — so the datapoint reads as no-data. Do not emit a
zero to keep the build quiet; a confident wrong number is worse than an absent one.

## Proxmox API facts (verified against the published API schema, 454 endpoints)

Base `https://host:8006/api2/json`. Auth header is exactly
`Authorization: PVEAPIToken=USER@REALM!TOKENID=SECRET`. Every endpoint this module uses has
`allowtoken: 1`, so API tokens work throughout — but a token with **privilege separation enabled**
gets its own ACL and will 401/403 until permissions are granted to the token itself, not just the
user. Responses are always enveloped as `{"data": ...}`.

Required privileges: `Sys.Audit` on `/` for `/cluster/status`, `Sys.Audit` on `/nodes/{node}` for
node status and rrddata, `VM.Audit` on `/vms/{vmid}` for guest status, and `Datastore.Audit` on
`/storage/{storage}` for storage status. `PVEAuditor` on `/` with propagate covers all of them.
`/nodes`, `/nodes/{node}/qemu` and `/cluster/resources` are `user: all` — they silently return only
what the token may see, so an under-privileged token produces *empty discovery*, not an error.

**`/cluster/resources` returns everything in one call.** For every node, guest and storage it
carries `type`, `id`, `node`, `vmid`, `storage`, `name`, `status`, `cpu`, `maxcpu`, `mem`, `maxmem`,
`disk`, `maxdisk`, `netin`, `netout`, `diskread`, `diskwrite`, `uptime`, `template`, `tags`, `pool`.
It works on a standalone host, not just a cluster. This is the single most important fact for this
module: it makes per-instance polling unnecessary.

Other schema details that bite:

- `status/current` for a guest already returns `netin`/`netout`/`diskread`/`diskwrite`, so the extra
  `rrddata` call per guest is redundant — and `rrddata` returns an averaged historical series, not
  a current reading, which is the wrong thing to graph as a current value anyway.
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

## LogicMonitor platform facts

**Collectors run Groovy 4.** A script may pin the runtime with a first line of `//!/lib-groovy/v4`.
Migration from Groovy 2 removed `List.push`/`pop` (use `add` / `remove(size-1)`) and moved
`XmlSlurper`/`XmlParser` to `groovy.xml.*` and `LazyMap` to `org.apache.groovy.json.internal.*`.
Nothing in this repo uses those, so the scripts are Groovy 4 clean — keep it that way.
LogicMonitor publishes `logicmonitor/GroovyRemix` to automate that migration.

**There is a supported HTTP client — which this repo deliberately does not use (see Style).**
`com.santaba.agent.groovyapi.http.HTTP` (`HTTP.open(host, port)`, `.get(url)`, `.getStatusCode()`,
`.getResponseBody()`, `.close()`) is what LogicMonitor's own reference DataSources use, instead of
raw `URL.openConnection()`. Reference examples live in `logicmonitor/monitoring-recipes` (see
`DataSources/Groovy/HTTP/`).

**Modules are distributed as JSON**, exported and imported through **My Module Toolbox** as a single
`.json`. `build/build.py` emits that format into `dist/`; the field names were taken from real
exported modules, not guessed. The import API, REST API v3 / LMv1 signing, and the `Logic.Monitor`
PowerShell module are in `docs/INSTALL.md`'s appendix — its `Invoke-LMCollectorDebugCommand` runs
`!groovy` on a real Collector, the closest thing to a live test harness this project has.
