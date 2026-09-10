# CLAUDE.md

## What this repo is

A LogicMonitor **LogicModule suite** for Proxmox VE, monitored over its HTTPS JSON API
(`/api2/json/...`). There is no application here — Groovy scripts are embedded in DataSources and
executed by a LogicMonitor Collector. The target is a suite publishable to the LM Exchange that
works unchanged from a single node to a large enterprise cluster; see `docs/DESIGN.md` for the
parity analysis against LogicMonitor's VMware/Hyper-V/Nutanix suites and the Tier 2 backlog.

```
scripts/lib/pve_common.groovy   shared preamble: properties, TLS, HTTP, output helpers
scripts/<Module>.ad.groovy      Active Discovery bodies
scripts/<Module>.collect.groovy collection bodies
modules/<Module>.json           module metadata + datapoint declarations
build/build.py                  assembles preamble + body into importable module JSON
tests/harness.groovy            runs the scripts against a mock Proxmox API
dist/                           GENERATED, gitignored — never edit, never commit
```

**The bodies are not standalone scripts.** They assume the helpers the preamble defines, and
`build.py` concatenates the two. Edit shared behaviour in `pve_common.groovy` once; edit
`dist/` never.

## Build and verification

```sh
python build/build.py           # assemble dist/
python build/build.py --check   # validate only

docker compose -f tests/docker-compose.yml run --rm compile   # groovyc, Groovy 4
docker compose -f tests/docker-compose.yml run --rm tests     # mock-API integration
```

The build refuses to emit if a collection script prints an undeclared datapoint, if a declared
datapoint is never printed (unless marked `"conditional": true` in the module definition), or if
brackets are unbalanced in an assembled script. Groovy is not installed on this machine — Docker
is how these get compiled and run, and `groovy:4-jdk17` matches the Collector's runtime.

The harness is mutation-tested. If you change it, re-verify it still fails when you deliberately
break something; a green suite that cannot go red is worse than no suite.

**What the harness does not prove:** that the module JSON imports into a real portal, or that
anything works against a real Proxmox host. Both need the user's environment. Do not describe a
change as verified end-to-end on the strength of a green harness run — say which of the two layers
was actually exercised.

## The Collector contract (get these wrong and the DataSource silently breaks)

**Property lookup.** Resource-level properties (`pve.api.url`, `pve.api.token`, ...) come from
`hostProps`. Instance-level properties set by Active Discovery come from `taskProps` /
`instanceProps`, and may be prefixed `auto.`. `pveInstanceProp` in the preamble is the only correct
way to read them: it tries `taskProps[key]`, `taskProps['auto.'+key]`, `instanceProps[key]`,
`instanceProps['auto.'+key]` in order, guarded by `binding.hasVariable` because neither binding
exists during Active Discovery. Reading an instance property out of `hostProps` returns null and the
script fails at runtime, not at edit time.

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
separator after `PVEAPIToken` is `=`, not `-`. The `pve.api.token` property already contains the
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

## Editing checklist

Adding or changing a datapoint touches three places: the `pveEmit` call in the collection script,
the `datapoints` array in `modules/<Module>.json`, and the coverage table in `README.md`. The build
enforces the first two agreeing; nothing enforces the third.

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
- `loadavg` items are typed **string**, not number. Printing them is fine; arithmetic on them is not.
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
