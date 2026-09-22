# Validating this suite against a real cluster

The suite is fourteen modules, and **all fourteen have now run against real hardware**. Tier 1's
six have been collecting against a live Proxmox host since 2026-09-10. Tier 2's eight were verified
on 2026-09-22 against a three-node cluster with Ceph — which also exercised the five a single node
cannot provide for at all: a Ceph cluster, per-OSD detail, a replication job, a subscribed node and
an SSD with a wear attribute.

So most of what follows is now a **regression checklist** rather than a list of open questions: it
says what each module should look like when it is right, which is what you need when something
starts reading No Data. The two things still genuinely unproven are in §2.0, and both need an event
to occur rather than hardware to exist.

Nothing here is dangerous. Every endpoint the suite touches is a GET, the token is read-only, and
no module writes anything to Proxmox.

---

## 1. Set up

**Create a read-only API token.** Datacenter → Permissions → API Tokens → Add, e.g.
`monitor@pam!logicmonitor`. Copy the secret; Proxmox shows it exactly once. Then Datacenter →
Permissions → Add → API Token Permission, granting the token `PVEAuditor` on `/` with **Propagate**.

Grant it to the *token*, not just the user. With privilege separation on (the default) a token
carries its own ACL and the user's permissions do not apply to it.

**Build and import.**

```sh
python build/build.py
```

Import each `dist/*.json` through Settings → LogicModules → My Module Toolbox → Add → Import from
file. Then create the PropertySource by hand — Add → PropertySource, name it
`addCategory_Proxmox_VE`, AppliesTo something that reaches your Proxmox hosts, and paste
`dist/scripts/addCategory_Proxmox_VE.groovy`.

Set `pve.api.token.credential` on the resource to the full token string
(`user@realm!tokenid=secret`) and run the PropertySource. It sets the category `ProxmoxVE`, and
every module applies itself from there.

**The fastest way to see a module's real output** is Test Collection Script in the module's
definition, against your Proxmox resource. It shows stdout and stderr without waiting for a poll.

---

## 2. What each module should look like when it is right

### 2.0 What is still actually unproven

Two branches have never executed against real conditions, because no healthy cluster produces them
on demand:

- **`Proxmox_VE_Cluster`'s HA datapoints.** They need a real failover, not merely an HA cluster.
  Covered in its own section below.
- **`Proxmox_VE_Disks` with unreadable SMART.** A disk whose `health` reads `UNKNOWN` must report
  `SmartHealthKnown=0` and **no** `SmartHealthOK` at all. That withholding is what stops an
  unreadable disk alerting exactly like a failing one, and every disk seen so far has had readable
  SMART. If you ever have a disk behind a RAID controller or a USB bridge, it is worth a look.

Everything else below has been confirmed. Keep it as the comparison for when something breaks —
the failure mode this suite has actually suffered is silent: discovery populates instances, Test
Collection Script shows perfect output, everything local is green, and every datapoint on every
instance reads No Data. See §4.

### 2.1 The modules that need no special hardware

**Backup Coverage, Certificates, Node Services and Disks.** After an import, let Active Discovery
run and confirm:

- **Certificates** — one instance per certificate per node (`pve-ssl`, `pveproxy-ssl`, and the
  cluster CA where present). `DaysUntilExpiry` should match the node's Certificates panel. It is
  computed against the *Collector's* clock, since the API returns `notafter` as an epoch and no
  days-remaining figure, so a wrong number here may be a Collector clock problem rather than a
  parsing one.
- **Node Services** — one instance per systemd unit per node. `pveproxy`, `pvedaemon` and
  `pve-cluster` should read `Running=1`, `Failed=0`, `Enabled=1`, `Installed=1`. On a standalone
  host `corosync` is installed but not running, which is correct and not a fault.
- **Backup Coverage** — `GuestsNotBackedUp` should equal what Datacenter → Backup leaves uncovered;
  with no backup job defined at all, that is every guest, not zero. `BackupInfoAvailable` is a
  separate question about the *endpoint*: it reads 0 only on Proxmox versions that do not expose
  `/cluster/backup-info/not-backed-up`, and then no counts are emitted at all. Check which of the
  two you are looking at before reading a low number as good news.
- **Disks** — `SizeGB`, `Mounted` and `SmartHealthKnown` on every physical disk, and
  `LifeRemainingKnown` 1 on SSD/NVMe and 0 on spinning disks.

All four were confirmed on 2026-09-22. If one of them stops carrying numbers later, §4 is the
first place to look.

### 2.2 `Proxmox_VE_CephOSD`

Never run against Ceph. The endpoint shape and every field name came from reading
`pve-manager`'s `PVE/API2/Ceph/OSD.pm`, not from a response.

- **Does discovery find every OSD?** Compare against `ceph osd tree`. The suite walks the CRUSH
  tree iteratively and collects leaves where `type == "osd"`, so a deeper tree — datacenter, rack,
  chassis buckets — should work, but that is untested. A cluster with more than host-level buckets
  is the interesting case.
- **What does a *down* OSD actually omit?** The suite assumes a down OSD may report `status` and
  `in` but no `total_space`, `bytes_used`, `percent_used` or latency, and withholds those rather
  than emitting zeroes. If a real down OSD reports usage anyway, the withholding is harmless but
  the comments are wrong. If a down OSD reports something else missing that we *do* emit, that is a
  bug worth knowing about.
- **Are the latencies milliseconds?** They are named `commit_latency_ms` / `apply_latency_ms` and
  taken at face value. Sanity-check the magnitudes against `ceph osd perf`.
- **Is `flags` comma-joined?** `NoOutFlagSet` splits `flags` on commas and looks for `noout`. Set
  `noout` (`ceph osd set noout`), confirm the datapoint goes to 1, then unset it
  (`ceph osd unset noout`).

### 2.3 `Proxmox_VE_Ceph`

The API declares `/cluster/ceph/status` as an **untyped object** — it passes through whatever
`ceph status` produces, and the keys have moved between Ceph releases.

- **Which datapoints actually appear?** Almost all are conditional and are withheld when their
  source key is missing. A large number of no-data datapoints means the shape differs from what was
  assumed. `CephAvailable` should be 1.
- **Is `osdmap` flat or nested?** The suite accepts `status.osdmap.num_osds` and
  `status.osdmap.osdmap.num_osds`. Whichever your release uses, `OSDsTotal` should match
  `ceph osd stat`.
- **Do the PG counts add up?** `PGsClean + PGsNotClean` should equal `PGsTotal`. Cleanliness is
  decided by decomposing the compound state name, so a PG in `active+clean+scrubbing` counts as
  *not* clean — deliberate, but worth confirming you agree with it.
- **Is monitor quorum right?** `MonitorsInQuorum` against `ceph quorum_status`.

### 2.4 `Proxmox_VE_Replication`

Needs a cluster with at least one replication job.

- **Does a job that has never run report no `SecondsSinceLastSync`?** It must read as no-data. Zero
  would mean "replicated just now", the opposite of the truth.
- **Does `FailCount` match** the failure count in the Proxmox UI's Replication tab?
- **Does `Running` go to 1 during a run?** It is derived from `pid`, which Proxmox only populates
  while the process is alive. Trigger a job (Schedule Now) and poll during it.
- **Do job ids survive a migration?** The wildvalue is the job id (`100-0`) without a node name,
  specifically so a migration does not orphan the instance. Migrate a replicated guest and confirm
  the instance and its history persist.

### 2.5 `Proxmox_VE_Subscription`

Needs a subscribed node.

- **Does `DaysUntilDue` look right?** `nextduedate` is a `YYYY-MM-DD` string parsed as UTC
  midnight. Compare against the date in the node's Subscription panel.
- **Does an unsubscribed node report no `DaysUntilDue` at all?** It must be absent, not zero — zero
  reads as "expires today".

### 2.6 `Proxmox_VE_Disks` — the wearout question, now answered

`LifeRemainingPercent` comes from Proxmox's `wearout`. Reading `pve-storage`'s `Diskmanage.pm`, that
is computed as `100 − percentage-used` for NVMe and as the normalised SMART wear attribute for SATA
SSDs — both of which are life **remaining**, so the shipped threshold alerts when it *falls*
(`< 20 10 5`).

**Confirmed against real SSD and NVMe disks on 2026-09-22**: the values read as life remaining and
the threshold is the correct way round. This was the single highest-consequence unknown in the
suite and it is closed.

Spinning disks expose no wear attribute, so `LifeRemainingPercent` is withheld there and
`LifeRemainingKnown` reads 0. That pairing is the only way to say "not applicable" — a LogicMonitor
datapoint carries numbers, never text, so no datapoint can print `N/A`, and a lone blank cell
cannot distinguish a spinning disk from a collection failure.

Still worth confirming: a disk whose SMART cannot be read reports `SmartHealthKnown=0` and **no**
`SmartHealthOK` at all, rather than `SmartHealthOK=0`.

### 2.7 `Proxmox_VE_Cluster` — HA on a real HA cluster, still unproven

The HA datapoints are verified only against a mock.

- `HAFencingArmed` is only reported by Proxmox versions that expose a `fencing` entry in
  `/cluster/ha/status/current`. If it is permanently no-data, your version does not expose it.
- `HAServicesRequestMismatch` counts services whose `state` differs from `request_state`. It should
  be briefly non-zero during a failover and settle to 0.

---

## 3. The most useful thing you can send back

Raw API responses. They become test fixtures, which means every future change is checked against
the shape a real cluster actually returns instead of one that was guessed at.

On any node, as root:

```sh
for p in \
  "/cluster/ceph/status" \
  "/nodes/$(hostname)/ceph/osd" \
  "/nodes/$(hostname)/replication" \
  "/nodes/$(hostname)/subscription" \
  "/nodes/$(hostname)/disks/list" \
  "/cluster/ha/status/current" \
  "/cluster/backup-info/not-backed-up"
do
  echo "=== $p"
  pvesh get "$p" --output-format json
done > proxmox-api-capture.json 2>&1
```

**Check it before sending.** `subscription` includes your subscription key and server id, and
`disks/list` includes disk serial numbers. Both are worth redacting. Nothing else in that capture is
sensitive — it is inventory and health data.

Those go into `tests/fixtures/pve_api.json`, keyed by the path after `/api2/json` plus any query
string exactly as the script requests it.

Alongside that, the Test Collection Script output for any module that looks wrong, and which
datapoints show No Data in the portal that you expected to have values.

---

## 4. If a module collects nothing

Check the datapoint post-processor before anything else. In `dist/<Module>.json`, a `batchscript`
module's datapoints must have `interpretExpr` of `##WILDVALUE##.<name>`, and a `script` module's
must be the bare `<name>`.

This suite shipped that wrong once, and it is worth knowing what it looked like: discovery
populated instances, Test Collection Script showed perfectly correct output, the build and the
whole test harness were green — and every datapoint on every instance read No Data. The build now
refuses to emit a module whose keys do not match its collection method, but the symptom is
distinctive enough to recognise.

---

## 5. Driving a portal from a script

For importing modules and running scripts against a real Collector without clicking through the UI.

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
