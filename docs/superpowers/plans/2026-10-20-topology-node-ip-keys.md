# Topology node IP keys and a drawn cluster vertex Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw every Proxmox node under its cluster from one dynamic group carrying only `pve.topology.enabled=true`, across any number of clusters, and draw the cluster vertex itself. (As built: the cluster vertex is not drawn, the spike having failed — DESIGN §7, spike 3.)

**Architecture:** A second node key, `proxmoxve--node--<name>--<ip>`, built by new preamble helpers that both the TopologySource (from `/cluster/status`) and `addERI_Proxmox_VE` (from `system.ips`) call, so no cluster name is needed on a token-less node. The cluster vertex resolves to a one-instance `Proxmox_VE_Cluster` carrying `predef.externalResourceID`, gated on a portal spike.

**Tech Stack:** Groovy 4 (Collector runtime), Python 3 build (`build/build.py`), Docker (`groovy:4-jdk17`) for compile + harness, the `logicmonitor-modules` skill's lint scripts.

**Spec:** `docs/superpowers/specs/2026-10-20-topology-node-ip-keys-design.md`

## Global Constraints

- Branch `topology-node-ip-keys` (off `main` at `9418034`). Never edit `dist/`. Never merge or push without the user.
- Run order after any edit (Git Bash, repo root): `python build/build.py` → `docker compose -f tests/docker-compose.yml run --rm compile` → `docker compose -f tests/docker-compose.yml run --rm tests` → `python ../logicmonitor-modules-skill/skill/scripts/lm_lint_module.py dist` (5 expected warnings, all on `addCategory_Proxmox_VE`) → `python ../logicmonitor-modules-skill/skill/scripts/lm_dashboards.py check dist/dashboards/*.json --modules modules`.
- New key, exactly: `proxmoxve--node--<short node name>--<normalised ip>`, every part folded like `pveTopoKey` (lowercase; anything outside `[a-z0-9_-]` becomes `-`). `pveTopoKey` and its keys do not change.
- IP normalisation: only literals (dotted quad with octets 0-255, or containing `:`) reach `InetAddress.getByName`; strip `%scope`; reject loopback, link-local, wildcard; output `getHostAddress().toLowerCase()` (IPv6 uncompressed, e.g. `fd00:0:0:0:0:0:0:12`).
- Wildvalues of existing modules do not change. The new Cluster instance's wildvalue is the fixed string `cluster`.
- Instance ERI gate (Cluster AD): `pve.topology.enabled == 'true'`, a `cluster` row in `/cluster/status`, and neither `topo.namespace` nor `topo.blacklist` set — the same gate GuestStatus's AD uses.
- Module count stays 18 — fourteen DataSources, three PropertySources, one TopologySource.
- **DESIGN §7 must not lose a line** except the ones a task names. Before the first §7 edit: `sed -n '/^## 7\./,$p' docs/DESIGN.md > "$SCRATCH/s7-before.md"`; after each: `sed -n '/^## 7\./,$p' docs/DESIGN.md > "$SCRATCH/s7-after.md"; grep -vxFf "$SCRATCH/s7-after.md" "$SCRATCH/s7-before.md"` must print only the named lines. `$SCRATCH` is the session scratchpad.
- Groovy style per CLAUDE.md: four-space indent, single quotes, explicit `return 0` / `return 2`, `HttpURLConnection` only; only the three existing Collector-only scripts import `Snippets`.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A `/cluster/status` node row with no `ip` (offline or older node) must leave that vertex with only the old key — a `null` in a key list handed to `lmTopo.eriPreProcessor` or `emitEri` is untested Collector territory. (Task 1: `pveTopoNodeKey('pve2', null) == null`; Task 2: reviewer checks both scripts drop nulls before the lm.topo call.)
2. `system.ips` as LogicMonitor writes it — comma-separated, IPv6 uncompressed, link-local first, possibly with spaces — must yield exactly the key the TopologySource builds from a compressed `/cluster/status` address. (Task 1: `topo-ipkey` round-trip assertions.)
3. A hostname or malformed address in `system.ips` (`999.1.1.1`, `pve1.example.com`) must return null without DNS. (Task 1: assertions; reviewer confirms the literal gate precedes `getByName`.)
4. Upgrades: a deployment still relying on `pve.topology.cluster` keeps matching — the vertex and the ERI both still carry `pveTopoKey(cluster, node)`. (Task 1: existing `topo-identity` assertions stay green; Task 2: reviewer.)
5. Cluster AD with the opt-in on but `topo.namespace` set, or on a standalone host, must carry no `predef.*`. (Task 4: `cluster-ad` assertions.)

---

### Task 1: Preamble helpers and fixtures

**Files:**
- Modify: `scripts/lib/pve_common.groovy` (topology helpers, after `pveTopoKey`)
- Modify: `tests/fixtures/pve_api.json` (`/cluster/status` node rows)
- Test: `tests/harness.groovy` (topology section, after the `topo-identity` block)

**Interfaces:**
- Produces:
  - `pveTopoIp(String raw) -> String|null`
  - `pveTopoNodeKey(String node, String ip) -> String|null`
  - `pveTopoNodeKeys(String node, List ips) -> List<String>` — `pveTopoNodeKey` over each, nulls dropped, deduplicated, input order kept
  - `pveTopoNodeName() -> String|null` — the node-name half of `pveTopoNodeIdentity` (`pve.topology.node`, else short non-IP `system.sysname` / `system.hostname`), needing no cluster
  - `pveTopoNodeIdentity()` unchanged in behaviour, now built on `pveTopoNodeName`

- [ ] **Step 1: Fixture.** In `/cluster/status`, add `"ip": "192.168.10.11"` to the `pve1` row and `"ip": "fd00::12"` to the `pve2` row.
- [ ] **Step 2: Write the failing assertions** (scope `topo-ipkey`, one `runSource(preamble + ...)` like the `helpers` block):
  - `pveTopoIp`: `'192.0.2.211'`→`'192.0.2.211'`; `' 192.0.2.211 '`→`'192.0.2.211'`; `'fd00::12'`, `'FD00:0:0:0:0:0:0:12'` and `'fd00::12%vmbr0'` all →`'fd00:0:0:0:0:0:0:12'`; `null`, `''`, `'127.0.0.1'`, `'::1'`, `'fe80::1'`, `'fe80:0:0:0:be24:11ff:fe00:1'`, `'0.0.0.0'`, `'999.1.1.1'`, `'pve1.example.com'` all →`null`.
  - `pveTopoNodeKey('pve1', '192.168.10.11') == 'proxmoxve--node--pve1--192-168-10-11'`; `pveTopoNodeKey('PVE1.example.com', '192.168.10.11')` same; `pveTopoNodeKey('pve2', 'fd00::12') == 'proxmoxve--node--pve2--fd00-0-0-0-0-0-0-12'`; `pveTopoNodeKey('pve2', null)`, `pveTopoNodeKey(null, '192.168.10.11')`, `pveTopoNodeKey('', '192.168.10.11')`, `pveTopoNodeKey('pve2', 'fe80::1')` all `null`.
  - Round trip: for each node row of the fixture `/cluster/status`, `pveTopoNodeKey(row.name, row.ip)` equals the single element of `pveTopoNodeKeys(row.name, sysIps.tokenize(','))` where `sysIps` is `'fe80:0:0:0:be24:11ff:fe00:1,192.168.10.11'` for pve1 and `'fe80:0:0:0:be24:11ff:fe00:1, fd00:0:0:0:0:0:0:12'` for pve2.
  - `pveTopoNodeKeys('pve1', ['192.168.10.11', '192.168.10.11', '10.0.0.5'])` == the two keys for `192.168.10.11` then `10.0.0.5`.
  - `pveTopoNodeName()` with `['system.hostname': 'pve2.example.com']` (no cluster property) == `'pve2'`; with `['system.hostname': '10.0.0.12']` == `null`; with `['pve.topology.node': 'pve4', 'system.sysname': 'pve3']` == `'pve4'`.
- [ ] **Step 3: Run** the build then the harness. Expected: the new `topo-ipkey` notes FAIL (helpers undefined); everything else passes.
- [ ] **Step 4: Implement** the four helpers in `pve_common.groovy` with header comments in the file's style (why name plus IP; why the literal gate exists — Java resolves anything else as a hostname). Refactor `pveTopoNodeIdentity` to call `pveTopoNodeName`. Add the helpers to CLAUDE.md's preamble list (topology line).
- [ ] **Step 5: Run** the full sequence. Expected: harness ALL checks pass, including the unchanged `topo-identity` block; lint 5 expected warnings.
- [ ] **Step 6: Mutation check.** Temporarily make `pveTopoIp` return the raw string untrimmed and un-normalised; harness must go red on `topo-ipkey`. Temporarily add a cluster part to `pveTopoNodeKey`; harness must go red. Revert both; green again.
- [ ] **Step 7: Commit** `scripts/lib/pve_common.groovy tests/harness.groovy tests/fixtures/pve_api.json CLAUDE.md`.

### Task 2: TopologySource and `addERI_Proxmox_VE` emit the IP keys

**Files:**
- Modify: `scripts/Proxmox_VE_Topology.topo.groovy`
- Modify: `scripts/addERI_Proxmox_VE.groovy`
- Modify: `modules/Proxmox_VE_Topology.json`, `modules/addERI_Proxmox_VE.json` (`technicalNotes`)

**Interfaces:**
- Consumes: `pveTopoNodeKey`, `pveTopoNodeKeys`, `pveTopoNodeName` (Task 1).

These scripts are Collector-only: no compile or harness reaches them. The reviewer reads them against this task.

- [ ] **Step 1: TopologySource.** Build `name -> ip` from the `/cluster/status` rows of type `node`. Each node vertex becomes `eri([pveTopoKey(clusterName, nodeName), pveTopoNodeKey(nodeName, ipOf[nodeName])].findAll { it })`. When `/cluster/status` has no row of type `cluster`, register no `Cluster` edges (and so no cluster vertex); nodes and guests unchanged, and `clusterName` still comes from `pveTopoClusterName` for guest fallback keys. Header: replace the `cluster -` paragraph's "matches nothing on purpose" with "resolves to the `Proxmox_VE_Cluster` instance carrying its key; on a standalone host it is not emitted", and "the portal renders only vertices that match a resource" with "...a resource or a DataSource instance (DESIGN section 7)". Describe the node's second key in the `node --` paragraph.
- [ ] **Step 2: `addERI_Proxmox_VE`.** Node name: `localRow.name` on the token path, else `pveTopoNodeName()`. Keys: `pveTopoKey(identity.cluster, identity.node)` when an identity exists (token path, or `pveTopoNodeIdentity()`), then `pveTopoNodeKeys(name, (pveHostProp('system.ips') ?: '').tokenize(',') + [localRow?.ip?.toString()])`, deduplicated. Print nothing and `return 0` when the key list is empty. Header: replace the "Where the cluster and node names come from" section with the two keys and their sources; say a token-less node needs no property when it has a hostname.
- [ ] **Step 3: technicalNotes.** `addERI_Proxmox_VE`: drop "together with pve.topology.cluster" and "Without a cluster name it stays silent"; describe the name+IP keys from `system.ips`; `pve.topology.cluster` optional, and only on a group holding one cluster's nodes; scope `pve.topology.enabled` to Proxmox hosts (a non-Proxmox server gets keys that match nothing); an UNVERIFIED paragraph naming spec §6 item 2. `Proxmox_VE_Topology`: node vertices carry both keys; no cluster vertex on a standalone host; UNVERIFIED paragraph naming spec §6 items 2-3.
- [ ] **Step 4: Run** the full sequence. Expected: green; `dist/scripts/collector-only/` has both scripts with the preamble helpers present (`grep -c pveTopoNodeKeys dist/scripts/collector-only/addERI_Proxmox_VE.groovy` ≥ 2).
- [ ] **Step 5: Commit.**

### Task 3: Spike — does the portal draw a cluster vertex from an instance?

**Files:** none in the repo. Throwaway module under `$SCRATCH/spike-cluster/`.

- [ ] **Step 1: Build** `$SCRATCH/spike-cluster/Proxmox_VE_Spike_ClusterVertex.json` from `dist/Proxmox_VE_NodeDetail.json` (a built `script` + `multiInstance` module): rename to `Proxmox_VE_Spike_ClusterVertex`, displayedAs `Proxmox VE Spike Cluster Vertex`, one datapoint `Up`; collect script `println 'Up=1'; return 0`; AD script = the preamble followed by a body that prints one line: wildvalue `cluster`, display name `auto.pve.cluster.name`, properties `predef.externalResourceID=` `pveTopoKey(<auto.pve.cluster.name>, null)` and `predef.externalResourceType=` `hostProps.get('spike.ert') ?: 'Cluster'`. Lint it with `lm_lint_module.py`.
- [ ] **Step 2: Hand to the user:** import it; on the token-holding resource run AD; wait for the next hourly topology run; check the map. Pass = a cluster vertex with a Cluster edge to each node. If not drawn as a cluster, set `spike.ert` on the resource to `VirtualCluster`, then `PhysicalServer`, re-running AD each time, and report which rendered and how. Then delete the module.
- [ ] **Step 3: STOP** until the user reports. Record the outcome as a dated "Spike 3" paragraph in DESIGN §7 (the §7 guard must print nothing) and commit. **If no ERT draws the vertex:** skip Task 4; in Task 5 the TopologySource header and §7 say why the cluster vertex is not drawn.

### Task 4: `Proxmox_VE_Cluster` discovers one instance carrying the cluster key

Only if Task 3 passed. `<ERT>` below is the type Task 3 recorded.

**Files:**
- Create: `scripts/Proxmox_VE_Cluster.ad.groovy`
- Modify: `modules/Proxmox_VE_Cluster.json`, `scripts/Proxmox_VE_Cluster.collect.groovy` (header comment only)
- Test: `tests/harness.groovy` (after the guest-ERI blocks)

**Interfaces:**
- Consumes: `pveTopoKey`, `pveTopoClusterName`, `pveDiscover`, `pveHostProp`, `pveGet`.

- [ ] **Step 1: Write the failing assertions** (scope `cluster-ad`, `def clusterAd = new File(scriptsDir, 'Proxmox_VE_Cluster.ad.groovy')`, using `discoverWith` and `parseAd`):
  - flag off (`hostProps`): exit 0; exactly one line, starting `cluster##pve-cluster##`; `predef` empty.
  - flag on (`on`): `predef.externalResourceID == 'proxmoxve--pve-cluster'`, `predef.externalResourceType == '<ERT>'`.
  - flag on, standalone (`withFixtures(['/cluster/status': [[type: 'node', name: 'pve-a', local: 1, online: 1]]])`): one line starting `cluster##pve-a##`; `predef` empty.
  - flag on with `topo.namespace` and, separately, `topo.blacklist` set: `predef` empty; stderr names the property.
  - `/cluster/status` failing (`withFixtures(['/cluster/status': null])`): exit 2, stderr non-empty.
- [ ] **Step 2: Run.** Expected: `cluster-ad` FAILs (file missing); the per-module loop is unchanged.
- [ ] **Step 3: Implement** the AD body on `Proxmox_VE_GuestStatus.ad.groovy`'s pattern (guard, gate, `try`/`catch` → stderr + `return 2`): one `pveDiscover('cluster', pveTopoClusterName(status), 'Proxmox VE cluster', [pve_cluster: <name>], raw)`. Module JSON: `multiInstance: true`, `useWildValueAsUniqueIdentifier: true`, `discoveryScript: "Proxmox_VE_Cluster.ad.groovy"`, `discoveryInterval: "60m"`; collection method stays `script`. technicalNotes: replace "Single-instance." with one instance, why (the map's cluster vertex), the one-time history loss on upgrade, the gate, and an UNVERIFIED paragraph naming spec §6 items 3-4. Collect header: "(one instance)" instead of "(single instance)".
- [ ] **Step 4: Run** the full sequence. Expected: green, including the per-module `Proxmox_VE_Cluster/ad` and `/collect` notes; `build.py --check` still 18 modules; dashboard check passes unchanged.
- [ ] **Step 5: CLAUDE.md:** module table row (Discovery `Cluster.ad`, calls "3, O(1); hourly discovery + 1"), the AD-body counts ("Ten AD bodies back eleven multiInstance modules", "Of the eleven modules that discover, seven override ... 60m"), and the NodeDetail paragraph's "the only module that is both `script` and `multiInstance`". README table: "Script, one instance". Commit.

### Task 5: Documentation

**Files:** `docs/INSTALL.md`, `README.md`, `CLAUDE.md`, `docs/DESIGN.md`.

- [ ] **Step 1: INSTALL** topology section (§3.3 and wherever `pve.topology.cluster` is required): one dynamic group with AppliesTo `(system.sysinfo =~ "-pve" || hasCategory("ProxmoxVE")) && !(auto.network.mac_address =~ "^[bB][cC]:24:11")` carrying `pve.topology.enabled=true`; no per-cluster property; `pve.topology.cluster` optional and never on a group spanning clusters, with the reason; `pve.topology.node` only for a resource added by IP with neither SNMP nor SSH.
- [ ] **Step 2: README** install paragraph (drop "plus `pve.topology.cluster` on the nodes of a cluster that do not hold the token") and Known limits (node identifier bullet mentions the name+IP key). **CLAUDE.md** `addERI_Proxmox_VE` paragraph: the two keys and their sources.
- [ ] **Step 3: DESIGN §4:** topology rows reflect the new key and the cluster vertex. **DESIGN §7** (guard snapshot first): add rows "Node name+IP keys" and, if Task 4 shipped, "Cluster vertex instance ERI", both **not verified**, naming spec §6. Permitted changed lines: the "Token-less node ERIs" row (append that it is superseded by the name+IP keys and still unproven) and the unproven-list bullet "A token-less node on a multi-node map". If Task 4 was skipped, the cluster-vertex sentence says why.
- [ ] **Step 4: Run** the full sequence and the §7 guard. Commit.

### Task 6: Hand over for the portal check

**Files:** none.

- [ ] **Step 1: Full sequence once more;** report harness check count, lint result and `git log --oneline main..HEAD`.
- [ ] **Step 2: Give the user** spec §6 items 2-5 with the import list: `dist/Proxmox_VE_Topology.json`, `dist/addERI_Proxmox_VE.json`, and, if Task 4 shipped, `dist/Proxmox_VE_Cluster.json` (warn: its history resets). Remind them to remove `pve.topology.cluster` from the group and keep the token on one node.
- [ ] **Step 3: STOP.** Results go into DESIGN §7 and UNVERIFIED notes come off what passed; any portal lesson (e.g. the cluster ERT) goes into the skill with a rule, a lint check where possible and a `tests/test_tools.py` mutation, ALL PASSED. No merge or push without the user.
