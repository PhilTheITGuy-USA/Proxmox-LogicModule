# CLAUDE.md

## What this repo is

A LogicMonitor **LogicModule suite** for Proxmox VE, monitored over its HTTPS JSON API
(`/api2/json/...`). There is no application here — Groovy scripts are embedded in DataSources and
executed by a LogicMonitor Collector. The target is a suite publishable to the LM Exchange that
works unchanged from a single node to a large enterprise cluster; see `docs/DESIGN.md` for the
parity analysis against LogicMonitor's VMware/Hyper-V/Nutanix suites and the backlog.

**`docs/DESIGN.md` §1-§6 is a design record, not outstanding work.** Its §6, "Things that must
change from the current implementation", reads like a to-do list but every one of its ten items is
implemented, and its §3 instruction to use the Proxmox `id` *verbatim* as the wildvalue is
superseded by `pveWildValue`'s fold to `[A-Za-z0-9_-]`. Everything in §4 past Tier 1 is unbuilt.

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
modules/<Module>.json            build-time module definition: metadata + datapoint declarations
build/build.py                   assembles preamble + body into importable module JSON
build/groovylint.py              bracket-balance check over the assembled scripts
tests/harness.groovy             runs the assembled scripts against a mock Proxmox API
tests/fixtures/pve_api.json      recorded API responses, keyed by path + query string
docs/DESIGN.md                   parity analysis, design rationale, Tier 2 backlog
dist/                            GENERATED, gitignored — never edit, never commit
```

**The bodies are not standalone scripts.** They assume the helpers the preamble defines, and
`build.py` concatenates the two. Edit shared behaviour in `pve_common.groovy` once; edit
`dist/` never.

**Discovery scripts are shared; collection scripts are not.** `Proxmox_VE_Guests.ad.groovy` backs
both GuestPerformance and GuestStatus; `Proxmox_VE_Nodes.ad.groovy` backs both Nodes and NodeDetail.
The build emits a *copy* per consuming module as `dist/scripts/<Module>.ad.groovy`, so those modules
discover identical instance sets and a single guest carries both a performance and a status
instance. Editing a shared AD body changes every module naming it in `discoveryScript`.

| Module | Method | Interval | Discovery |
|---|---|---|---|
| `Proxmox_VE_Cluster` | script | 5m | — (single instance) |
| `Proxmox_VE_Nodes` | batchscript | 5m | `Nodes.ad` |
| `Proxmox_VE_NodeDetail` | script, per instance | 5m | `Nodes.ad` |
| `Proxmox_VE_GuestPerformance` | batchscript | 5m | `Guests.ad` |
| `Proxmox_VE_GuestStatus` | batchscript | 3m | `Guests.ad` |
| `Proxmox_VE_StorageCapacity` | batchscript | 10m | `Storage.ad` |

**The suite is self-applying, and the PropertySource is the hinge.** Every module's AppliesTo is
`hasCategory("ProxmoxVE")`; `addCategory_Proxmox_VE.groovy` is what sets that category, by calling
`/version` and staying completely silent — exit 0, no output — on any host that is not Proxmox or
has no token. It is the one script with no module definition (`STANDALONE_SCRIPTS` in `build.py`),
because the PropertySource export schema could not be verified against a published sample, so it
ships as an assembled script to paste into the UI. Break its silence and the whole suite starts
applying itself to unrelated Linux hosts.

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
if brackets are unbalanced in an assembled script. Groovy is not installed on this machine — Docker
is how these get compiled and run, and `groovy:4-jdk17` matches the Collector's runtime.

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
Exchange review expects it. All five modules that discover override `discoveryInterval` to `60m`,
where the build default is `1440m`.

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
to the key. So a key printed by the script must match the datapoint name exactly, and a datapoint
declared in `modules/<Module>.json` must actually be printed on every successful run unless it is
marked `"conditional": true`.

**The post-processor key is not the datapoint name on a BatchScript.** One execution prints every
instance into a single stream, so a bare `CPUUsagePercent` matches no line — every key in that
stream is prefixed with an instance id. The key must be scoped to the instance as
`##WILDVALUE##.CPUUsagePercent`, which LogicMonitor substitutes per instance at poll time. `build.py`
applies the prefix for `batchscript` modules and withholds it for `script` modules, and refuses to
emit if a module's keys do not match its collection method.

This is the highest-consequence mistake in the repo, because every other signal stays green: the
build passes, the scripts compile, the harness goes green, Active Discovery populates instances, and
**Test Collection Script in the portal shows perfectly correct output** — while every datapoint on
every instance reads No Data. It shipped that way once. If a batchscript module collects nothing,
check `interpretExpr` in `dist/<Module>.json` before you touch anything else.

**Every emitted value must be a number.** The harness asserts each one matches
`^-?\d+(\.\d+)?([eE][-+]?\d+)?$` (`tests/harness.groovy:100`). Proxmox fields that are typed as
strings — `loadavg` items, `status`, HA state — must be converted or mapped before `pveEmit`:
NodeDetail does `toString().toDouble()`, GuestStatus maps `running` to 1. A string reaches
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

## Style

Conventionally formatted Groovy, four-space indent, single quotes, explicit `return 0` / `return 2`.
Datapoint names are PascalCase to match LogicMonitor's published suites (`CPUUsagePercent`,
`DataRateRx`, `UsedPercent`) — camelCase would be flagged in Exchange review.

`HttpURLConnection` is used rather than LogicMonitor's `com.santaba.agent.groovyapi.http.HTTP`. That
is a deliberate departure from house style: the santaba class is Collector-only, and depending on it
would make every script impossible to compile or run outside a Collector, costing us the compile
check and the whole test harness. See `docs/DESIGN.md` §6.

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

**The drift check is a regex, not an interpreter.** `build.py` finds emitted names with
`pveEmit(<anything>, '<Literal>'`. A datapoint name held in a variable or built by concatenation is
invisible to it and will pass the build while printing an undeclared datapoint on a live Collector.
Always pass datapoint names as single-quoted literals.

**Adding a module** means a new `modules/<Module>.json` (the build finds definitions by glob), a
collect body, an AD body if it is `multiInstance` — reuse an existing one where the instance set is
the same — a row in the README table, and a fixture for every endpoint it calls.

**A per-instance `script` module needs one thing more.** `Proxmox_VE_NodeDetail` is the only module
that is both `script` and `multiInstance`: it executes once per node and reads its instance
properties. The harness fakes those in the `instanceBindings` map (`tests/harness.groovy:103`),
hardcoded to `pve1` so it lines up with the `/nodes/pve1/status` fixture. A new module that calls
`pveInstanceProp` needs an entry there plus a fixture keyed to the same node name — without it the
collect script runs with no `taskProps`, and the harness reports a collection failure rather than a
missing binding.

A datapoint that is legitimately not emitted on every run needs `"conditional": true` in the module
definition. Use it when the API genuinely has no value to report — QEMU used-disk, cluster quorum on
a standalone host — so the datapoint reads as no-data. Do not emit a zero to keep the build quiet;
a confident wrong number is worse than an absent one.

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
  `maxdisk` but no `disk`; only LXC reports `disk`. `diskUsedBytes` is therefore always 0 for VMs.
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

**There is a supported HTTP client.** `com.santaba.agent.groovyapi.http.HTTP` (`HTTP.open(host, port)`,
`.get(url)`, `.getStatusCode()`, `.getResponseBody()`, `.close()`) is what LogicMonitor's own
reference DataSources use, instead of raw `URL.openConnection()`. Reference examples live in
`logicmonitor/monitoring-recipes` (see `DataSources/Groovy/HTTP/`).

**Modules are distributed as JSON.** Real modules are exported and imported through
**My Module Toolbox → Export** / **Add → Import from file** as a single `.json` (XML is the older
format). The import API takes a type of `datasources`, `configsources`, `eventsources`, `batchjobs`,
`logsources`, `oids`, `topologysources`, `functions` or `diagnosticsources`, plus a conflict policy
(`FORCE_OVERWRITE` / `ERROR`) and a `FieldsToPreserve` list covering `NAME`, `APPLIES_TO_SCRIPT`,
`COLLECTION_INTERVAL`, `ACTIVE_DISCOVERY_INTERVAL`, `MODULE_GROUP`, `DISPLAY_NAME`,
`USE_WILD_VALUE_AS_UUID`, `DATAPOINT_ALERT_THRESHOLDS` and `TAGS`. `build/build.py` emits this
format into `dist/`; the field names were taken from real exported modules, not guessed.

**REST API v3.** Base `https://<portal>.logicmonitor.com/santaba/rest`, header `X-Version: 3`.
Either `Authorization: Bearer <token>`, or LMv1: build `METHOD + epochMillis + body + resourcePath`
(body omitted for GET/DELETE, resourcePath excludes the query string), HMAC-SHA256 it with the
access key, lowercase-hex the digest, Base64 **the hex string**, and send
`Authorization: LMv1 <accessId>:<base64>:<epochMillis>`. The `Logic.Monitor` PowerShell module
implements this and ships `Export-LMLogicModule`, `Import-LMLogicModuleFromFile`,
`Invoke-LMActiveDiscovery` and `Invoke-LMCollectorDebugCommand` — the last one runs `!groovy` against
a real Collector, which is the closest thing to a test harness this project can have.
