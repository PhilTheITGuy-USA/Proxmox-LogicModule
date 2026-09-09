# CLAUDE.md

## What this repo is

A bundle of LogicMonitor **LogicModule** scripts that monitor Proxmox VE over its HTTPS JSON API
(`/api2/json/...`). There is no application here — the `.groovy` files are pasted into LogicMonitor
DataSources as **Embedded Groovy** and executed by a LogicMonitor Collector.

- `scripts/*_AD.groovy` — Active Discovery (emit instances)
- `scripts/*_CT.groovy` — Collection (emit datapoints)
- `logicmodule.json` — a hand-written *design manifest* (DataSource names, script/arg mapping, datapoint lists). It is **not** a LogicMonitor account export and cannot be imported as one.
- `README.md` — install/setup instructions for the LogicMonitor side

## No build, no tests, no local run

There is no build system, package manager, test suite, or CI. Groovy and Java are **not installed on
this machine**, so scripts cannot even be syntax-checked locally as things stand.

More importantly, these scripts can never run standalone: they depend on bindings the Collector
injects (`hostProps`, `taskProps`, `instanceProps`) and on `return <int>` at script top level.
The only real verification is Collector-side — LogicMonitor's *Test Active Discovery* / *Poll Now*,
or the `!groovy` debug console. Do not claim a script change is verified without one of those.

## The Collector contract (get these wrong and the DataSource silently breaks)

**Property lookup.** Resource-level properties (`pve.api.url`, `pve.api.token`, ...) come from
`hostProps`. Instance-level properties set by Active Discovery come from `taskProps` /
`instanceProps`, and may be prefixed `auto.`. `Guest_CT` and `Storage_CT` show the correct pattern —
a `getInstanceProperty` helper that tries `taskProps[key]`, `taskProps['auto.'+key]`,
`instanceProps[key]`, `instanceProps['auto.'+key]` in order. Reading an instance property out of
`hostProps` returns null and the script fails at runtime, not at edit time.

**Active Discovery output.** One instance per line, exactly:

```
instanceId##displayName##description####auto.key=value&auto.key2=value2
```

Four `#` before the property list, two elsewhere. Every field is passed through a
`replaceAll('[^A-Za-z0-9_.-]', '_')` sanitizer.

**Collection output.** One `key=value` line per datapoint on stdout. Datapoints are configured in
LogicMonitor with Raw Metric `output`, Post Processor `namevalue(<key>)`, Metric Type `Gauge`, so a
key printed here must match the datapoint name exactly, and a datapoint listed in `logicmodule.json`
must actually be printed on every successful run.

**Exit codes.** `return 0` on success, `return 2` on any API/config failure — and always write the
reason to `System.err`. This is deliberate: a non-zero exit makes LogicMonitor keep existing
instances through a transient Proxmox outage instead of deleting them. Never "handle" an API error
by returning 0 with zeroed datapoints.

**Auth header.** Proxmox expects `Authorization: PVEAPIToken=USER@REALM!TOKENID=SECRET` — the
separator after `PVEAPIToken` is `=`, not `-`. The `pve.api.token` property already contains the
whole `user@realm!tokenid=secret` string, so the header is built as `'PVEAPIToken=' + apiToken`.

**Instance IDs must round-trip.** The `instanceId` an AD script emits is what the matching CT script
later resolves through instance properties. `Guest_AD` emits `qemu__<node>__<vmid>` with
`auto.pve_node` / `auto.pve_type` / `auto.pve_vmid`; `Storage_AD` emits `<node>__<storage>` with
`auto.pve_storage` / `auto.pve_node`. Changing an ID scheme orphans every already-discovered
instance in the customer's account, so change AD and CT together and say so explicitly.

## Style

Two styles coexist. `Node_AD`, `Guest_AD`, `Guest_CT`, `Storage_CT` are conventionally formatted and
readable. `Node_CT`, `Cluster_CT`, `Storage_AD` are dense semicolon-per-line one-liners — an artifact
of pasting into LogicMonitor's script box. **Match the file you are editing**; do not reformat a
dense file wholesale as a side effect of an unrelated change, since the diff then hides the real edit.

Common to all: `groovy.json.JsonSlurper` for parsing, plain `URL.openConnection()` (no HTTP library),
`pve.api.timeout` defaulting to `10000` ms applied to both connect and read, and an opt-in
`pve.api.insecure` block that installs an all-trusting `X509TrustManager` and hostname verifier.
That TLS bypass is lab-only and must stay opt-in and default-off.

## Editing checklist

When adding or changing a datapoint, update all three: the `println` in the CT script, the
`datapoints` array in `logicmodule.json`, and the Coverage section of `README.md`.

## Repo hygiene

There is no `.gitignore`, and a macOS `.DS_Store` is committed at the root. Worth cleaning up if you
touch repo structure; leave it alone otherwise.
