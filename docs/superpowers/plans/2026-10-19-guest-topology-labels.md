# Guest topology labels and instance ERIs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Label Proxmox guests that are LogicMonitor resources as virtual machines, and draw every guest Proxmox reports on the topology map through GuestStatus instance ERIs.

**Architecture:** A new always-on ERI PropertySource (`addERI_Proxmox_VE_Guest`) matches a resource's MAC against the Proxmox guest prefix and emits a priority-3 `VirtualMachineResource` ERI. GuestStatus gets its own discovery body which, when `pve.topology.enabled` is `true`, adds `predef.externalResourceID` (the guest MAC, or the TopologySource's fallback key) and `predef.externalResourceType=VirtualMachine` to each instance. Shared logic lives in preamble helpers the harness can reach.

**Tech Stack:** Groovy 4 (Collector runtime), Python 3 build (`build/build.py`), Docker (`groovy:4-jdk17`) for compile + harness, the `logicmonitor-modules` skill's lint scripts.

**Spec:** `docs/superpowers/specs/2026-10-19-guest-topology-labels-design.md`

## Global Constraints

- Branch `topology-optin-tokenless-eri` (main already merged in at `591e023`). Never edit `dist/`.
- **DESIGN §7 must not lose a line.** Every task that edits `docs/DESIGN.md` re-runs the §7 guard in Task 1 Step 3 before committing.
- Run order after any edit: `python build/build.py` → `docker compose -f tests/docker-compose.yml run --rm compile` → `docker compose -f tests/docker-compose.yml run --rm tests` → `python ../logicmonitor-modules-skill/skill/scripts/lm_lint_module.py dist` → `python ../logicmonitor-modules-skill/skill/scripts/lm_dashboards.py check dist/dashboards/*.json --modules modules` (Git Bash).
- Groovy style: four-space indent, single quotes, explicit `return 0` / `return 2`; no `List.push/pop`, `XmlSlurper`, `LazyMap`; `HttpURLConnection` only. Only `Proxmox_VE_Topology`, `addERI_Proxmox_VE` and the new `addERI_Proxmox_VE_Guest` may import `com.logicmonitor.mod.Snippets`.
- Default guest prefix `bc:24:11`; override property `pve.guest.mac_prefix` (comma-separated). AppliesTo exactly: `auto.network.mac_address =~ "^[bB][cC]:24:11" || pve.guest.mac_prefix`.
- Label ERI: category `virt.proxmoxve`, priority `3`, type `VirtualMachineResource`, key `proxmoxve--vm--<lowercase mac>`.
- Instance ERI properties, unprefixed: `predef.externalResourceID`, `predef.externalResourceType` = `VirtualMachine`. Gate: `pve.topology.enabled` == `true` (lowercase, exact) and neither `topo.namespace` nor `topo.blacklist` set.
- Wildvalues must not change: `pveWildValue(guest.id)` (`qemu-101`, `lxc-200`).
- Module count after this work: 18 — fourteen DataSources, three PropertySources, one TopologySource.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Flag off must be byte-identical to today's discovery and request no `config` path — any leak of predef properties or extra calls costs every customer on upgrade. (Task 3: `guest-eri-off` assertions.)
2. `pveDiscover` URL-encodes values; a `predef.externalResourceID` written as `bc%3A24…` that the portal does not decode would silently draw nothing. Harness decodes and compares; the portal check (Task 7) is the real proof.
3. The fallback key for a NIC-less guest must equal the TopologySource's byte for byte, including the standalone-host case where there is no cluster row. (Task 2: `pveTopoClusterName`; Task 3: `guest-eri-on` no-NIC assertion.)
4. Uppercase Windows MACs and override lists with spaces/uppercase (`" BC:24:11 , 02:00:00"`) must match; the Pi's `88:a2:9e` must not. (Task 2: `guest-mac` assertions.)
5. A config read that fails (mock 501) must still discover the guest, without keys, and must not exit non-zero. (Task 3: `guest-eri-on` 501 assertion.)

---

### Task 1: Baseline the merged branch

**Files:** none modified.

- [ ] **Step 1: Start Docker Desktop**, then confirm: `docker info --format '{{.ServerVersion}}'` prints a version.
- [ ] **Step 2: Run the full check sequence** (Global Constraints). Expected: build `Built 17 modules and 2 dashboards`; compile succeeds; harness ends `PASSED  all N checks` — record N; lint `0 error(s), 5 warning(s)`, all five on `addCategory_Proxmox_VE`; both dashboards `OK`. If the harness fails, stop: the merge at `591e023` is at fault, not this plan.
- [ ] **Step 3: Record the §7 guard** used by every later DESIGN edit:

```bash
G="$SCRATCHPAD/s7-guard"; mkdir -p "$G"   # SCRATCHPAD = the session scratchpad directory, not /tmp
git show main:docs/DESIGN.md | sed -n '/^## 7\. Build status/,$p' > "$G/main_s7.md"
sed -n '/^## 7\. Build status/,$p' docs/DESIGN.md > "$G/cur_s7.md"
grep -vxFf "$G/cur_s7.md" "$G/main_s7.md"   # must print nothing, except lines a task deliberately rewrote
```

A deliberate rewrite of a main §7 line (Task 6 corrects "draws only vertices that match a resource") is listed in that task; anything else printed is a loss — restore it before committing.

---

### Task 2: Preamble helpers

**Files:**
- Modify: `scripts/lib/pve_common.groovy` (append helpers after `pveGuestMac`; extend `pveDiscover`)
- Modify: `scripts/Proxmox_VE_Topology.topo.groovy:66-69` (use `pveTopoClusterName`)
- Modify: `scripts/Proxmox_VE_Guests.ad.groovy` (use `pveGuestList` / `pveGuestDiscover`)
- Test: `tests/harness.groovy` (topology section, after the `topo-identity` block)

**Interfaces:**
- Produces:
  - `pveGuestMacMatch(String mac, String prefixProperty) -> String|null` — lowercase trimmed MAC when it starts with any prefix; prefixes = `prefixProperty` split on `,`, trimmed, lowercased, blanks dropped, else `['bc:24:11']`. Null/blank MAC → null.
  - `pveTopoClusterName(List status) -> String` — `type == 'cluster'` row's `name`, else the `local` row's `name`, else `'standalone'`. The TopologySource's existing logic, moved verbatim.
  - `pveDiscover(String id, String name, String description, Map properties, Map rawProperties = [:])` — `rawProperties` emitted after the `auto.` pairs, keys unprefixed, values through `pvePropValue`, blanks dropped.
  - `pveGuestList() -> List` — `/cluster/resources?type=vm` filtered to `qemu`/`lxc` and not `template`; prints the existing under-privileged-token warning to `System.err` when the response is empty.
  - `pveGuestDiscover(Map guest, Map rawProperties = [:])` — the existing per-guest `pveDiscover` call from `Proxmox_VE_Guests.ad.groovy` (same id, name, description, `pve_*` properties), plus `rawProperties`.

- [ ] **Step 1: Write the failing tests** in the harness topology section, using `runSource(preamble + ...)` like `helpers`:

```groovy
def guestMac = runSource(preamble + """
return [
    lower   : pveGuestMacMatch('bc:24:11:0e:00:01', null),
    upper   : pveGuestMacMatch('BC:24:11:0E:00:04', null),
    pi      : pveGuestMacMatch('88:a2:9e:0e:00:09', null),
    custom  : pveGuestMacMatch('02:00:00:12:34:56', ' BC:24:11 , 02:00:00'),
    notDflt : pveGuestMacMatch('bc:24:11:0e:00:01', '02:00:00'),
    blank   : pveGuestMacMatch('  ', null),
    none    : pveGuestMacMatch(null, null),
    cluster : pveTopoClusterName([[type: 'cluster', name: 'pve-cluster'], [type: 'node', name: 'pve1', local: 1]]),
    single  : pveTopoClusterName([[type: 'node', name: 'pve-a', local: 1]]),
    empty   : pveTopoClusterName([]),
]
""", [hostProps: hostProps])
```

Assertions (scope `guest-mac`): `lower == 'bc:24:11:0e:00:01'`; `upper == 'bc:24:11:0e:00:04'`; `pi == null`; `custom == '02:00:00:12:34:56'`; `notDflt == null` (an override replaces the default); `blank == null`; `none == null`; `cluster == 'pve-cluster'`; `single == 'pve-a'`; `empty == 'standalone'`; `thrown == null`.

One structural assertion (scope `guest-mac`): `topoScript` contains `pveTopoClusterName(` — the fallback key must be built from the same cluster name in both places.

And one `pveDiscover` raw-property assertion (scope `discover-raw`): `runSource(preamble + "pveDiscover('qemu/1', 'n', 'd', [a: 'x'], ['predef.externalResourceID': 'bc:24:11:00:00:01'])", ...)` prints exactly `qemu-1##n##d####auto.a=x&predef.externalResourceID=bc%3A24%3A11%3A00%3A00%3A01`.

- [ ] **Step 2: Run** `python build/build.py && docker compose -f tests/docker-compose.yml run --rm tests`. Expected: FAIL, `guest-mac` / `discover-raw` threw `MissingMethodException` / no such property.
- [ ] **Step 3: Implement** the five helpers in `pve_common.groovy`, each with a comment block in the file's existing style (why, not what). Move the TopologySource's cluster-name expression into `pveTopoClusterName` and call it there; rewrite `Proxmox_VE_Guests.ad.groovy` as guard + `pveGuestList().each { pveGuestDiscover(it) }` inside the existing `try`/`catch`.
- [ ] **Step 4: Run the full check sequence.** Expected: harness PASSED, N + 13 checks; existing `templates` / `instance-id` assertions still pass (wildvalues unchanged); lint unchanged (5 warnings).
- [ ] **Step 5: Commit** — `git add scripts/lib/pve_common.groovy scripts/Proxmox_VE_Topology.topo.groovy scripts/Proxmox_VE_Guests.ad.groovy tests/harness.groovy`; message `Add guest MAC, cluster-name and guest-discovery helpers to the preamble`.

---

### Task 3: GuestStatus discovery with instance ERIs

**Files:**
- Create: `scripts/Proxmox_VE_GuestStatus.ad.groovy`
- Modify: `modules/Proxmox_VE_GuestStatus.json` (`discoveryScript`, `technicalNotes`)
- Modify: `tests/harness.groovy` (mock request log; new `guest-eri-*` block after the PropertySource section)

**Interfaces:**
- Consumes: `pveGuestList`, `pveGuestDiscover`, `pveGuestMac`, `pveTopoClusterName`, `pveTopoKey`, `pveHostProp` (Task 2).
- Produces: discovery lines identical to `Proxmox_VE_Guests.ad.groovy`'s when gated off; with the gate on, each guest on an online node whose config reads gains `&predef.externalResourceID=<encoded key>&predef.externalResourceType=VirtualMachine`.

- [ ] **Step 1: Harness plumbing.** In the mock, also append every key to a new `def requestLog = []` (keep `requested`). Add a helper `withFixtures(Map overrides, Closure body)` that puts each override into `fixtures` — a `null` value removes that key, so the mock answers 501 — runs `body`, and restores the previous state (re-adding removed keys, removing keys that did not exist) in a `finally`. Request assertions below look only at the `requestLog` entries added during that run (take `requestLog.size()` before, slice after).
- [ ] **Step 2: Write the failing tests.** Let `statusAd = new File(scriptsDir, 'Proxmox_VE_GuestStatus.ad.groovy')`, `on = hostProps + ['pve.topology.enabled': 'true']`, and an extended guest list = the fixture `/cluster/resources?type=vm` plus `[id: 'lxc/103', type: 'lxc', vmid: 103, name: 'relay', node: 'pve1', template: false]`, `[id: 'qemu/104', type: 'qemu', vmid: 104, name: 'no-config', node: 'pve1', template: false]` and `[id: 'qemu/105', type: 'qemu', vmid: 105, name: 'no-nic', node: 'pve1', template: false]`, with fixtures `/nodes/pve1/lxc/103/config` = `[hostname: 'relay', net0: 'name=eth0,bridge=vmbr0,hwaddr=BC:24:11:0E:00:03,type=veth']` and `/nodes/pve1/qemu/105/config` = `[name: 'no-nic', cores: 1]` (no fixture for 104, so the mock answers 501). Parse each line with `AD_LINE` into `id -> [auto: [..], predef: [..]]`, URL-decoding values.

  - `guest-eri-off` (default `hostProps`, default fixtures): stdout equals `Proxmox_VE_GuestPerformance.ad.groovy`'s stdout; no new `requestLog` entry contains `/config`; exit 0.
  - `guest-eri-on` (`on`, extended fixtures): exit 0; `qemu-101` predef ID `bc:24:11:0e:00:02`; `lxc-103` predef ID `bc:24:11:0e:00:03`; `qemu-105` predef ID `proxmoxve--pve-cluster--qemu-105`; each of those three has `predef.externalResourceType == 'VirtualMachine'`; `qemu-104` discovered with no predef keys; `lxc-200` and `qemu-102` (offline `pve2`) discovered with no predef keys; no `/nodes/pve2/` path requested; `qemu-999` absent; every instance id from the off run present (no instance lost).
  - `guest-eri-namespace` (`on + ['topo.namespace': 'x']`): no predef keys anywhere; stderr mentions `topo.namespace`; exit 0. Same for `topo.blacklist`.
  - `guest-eri-gate-fails` (`on`, `withFixtures(['/cluster/status': null])` → 501): exit 0; output equals the off run's.
  - `guest-eri-perf`: GuestPerformance discovery run with `on` carries no predef keys.

- [ ] **Step 3: Run** build + harness. Expected: FAIL — `Proxmox_VE_GuestStatus.ad.groovy` not found.
- [ ] **Step 4: Implement** `scripts/Proxmox_VE_GuestStatus.ad.groovy`: the same guard as `Proxmox_VE_Guests.ad.groovy`; gate = `pveHostProp('pve.topology.enabled') == 'true'`; when either topo property is set, write one `System.err` line naming it and treat the gate as off; inside the gate, an inner `try` for `/cluster/status` + `/cluster/resources?type=node` (failure → gate off, stderr line); per guest on an online node, a per-guest `try` around the config read (failure → no keys, stderr line); key = `pveGuestMac(config) ?: pveTopoKey(pveTopoClusterName(status), guest.type + '-' + guest.vmid)` when the config read returned a map; emit via `pveGuestDiscover(guest, raw)`. Point `modules/Proxmox_VE_GuestStatus.json` `discoveryScript` at it and add to its `technicalNotes`: instance ERIs, the `pve.topology.enabled` gate, the cost (2 + 1 per guest on an online node, hourly), the namespace/blacklist limitation, and an `UNVERIFIED` paragraph naming spec §6 items 4–6.
- [ ] **Step 5: Run the full check sequence.** Expected: harness PASSED; the per-module conformance block still passes for GuestStatus (it runs with the gate off, so its `auto.`-only property rule doubles as a leak check); lint 0 errors, 5 warnings.
- [ ] **Step 6: Commit** — message `Give GuestStatus its own discovery, with instance ERIs behind the topology opt-in`.

---

### Task 4: `addERI_Proxmox_VE_Guest`

**Files:**
- Create: `modules/addERI_Proxmox_VE_Guest.json`, `scripts/addERI_Proxmox_VE_Guest.groovy`
- Test: `tests/harness.groovy` (structural block beside the `addERI` assertions)

**Interfaces:**
- Consumes: `pveGuestMacMatch`, `pveHostProp` (Task 2).
- Produces: module `addERI_Proxmox_VE_Guest` (`moduleType` `propertysource`, `eri: true`, `group` `ERI`, `collectionInterval` 1800, no `optional`), AppliesTo per Global Constraints.

- [ ] **Step 1: Write the failing structural tests** (scope `addERI-guest`, reading `scripts/addERI_Proxmox_VE_Guest.groovy` as text, as the `addERI` block does): contains `pveGuestMacMatch(`; contains `lmtopo.emitEri('virt.proxmoxve', 3,` and `'VirtualMachineResource'` and `lmtopo.printEriArray(`; does not contain `rawERIs`; does not contain `if (pveConfigError) {`; contains `pveHostProp('auto.network.mac_address')` and `pveHostProp('pve.guest.mac_prefix')`. Also: `modules/addERI_Proxmox_VE_Guest.json` `appliesTo` equals the Global Constraints string exactly and has no `optional` key.
- [ ] **Step 2: Run** build + harness. Expected: FAIL — file not found.
- [ ] **Step 3: Implement** the script on `addERI_Proxmox_VE.groovy`'s pattern (imports, `lm.topo` load, `topo.namespace` / `topo.blacklist` handling): `mac = pveGuestMacMatch(pveHostProp('auto.network.mac_address'), pveHostProp('pve.guest.mac_prefix'))`; null → `return 0` with no output; otherwise emit per Global Constraints and `return 0`; whole body in a `try` whose `catch` returns 0 silently. Header comment states why priority 3 (spike 1) and why it never reads `pveConfigError`. Module JSON `technicalNotes`: purpose, the priority finding, the override property, what it does not cover (hand-set MACs, guests reporting a non-Proxmox primary MAC, resources with no MAC), and an `UNVERIFIED` paragraph naming spec §6 item 3.
- [ ] **Step 4: Run the full check sequence.** Expected: build fails `check_doc_counts` (17 → 18) — that is Task 5's job; confirm with `python build/build.py --check` that the only errors are doc-count lines, then continue to Task 5 before committing. Lint: 0 errors.
- [ ] **Step 5: Commit together with Task 5.**

---

### Task 5: Documentation and counts

**Files:** `README.md`, `docs/INSTALL.md`, `docs/DESIGN.md` (§4 and §7), `CLAUDE.md`, `modules/addERI_Proxmox_VE.json`, `modules/Proxmox_VE_Topology.json`

- [ ] **Step 1: Counts.** Fix every line `python build/build.py --check` reports (17 → 18, two → three PropertySources), then grep for unchecked phrasings (`seventeen`, `17 modules`, `two PropertySources`, `Nine AD bodies back eleven` → `Ten AD bodies back eleven`) and fix them.
- [ ] **Step 2: CLAUDE.md.** Module table row for `addERI_Proxmox_VE_Guest` (ERI PropertySource, Collector-only, always on, 30m, 0 calls); GuestStatus row's discovery becomes `GuestStatus.ad` with "+ 2 + 1 per guest on an online node when `pve.topology.enabled`"; the shared-AD paragraph (GuestStatus no longer shares `Guests.ad`); the Collector-only paragraph says three scripts and names the new one; the helper map gains `pveGuestMacMatch`, `pveTopoClusterName`, `pveGuestList`, `pveGuestDiscover`; expected lint warnings updated if Task 4 changed the count.
- [ ] **Step 3: README** coverage table: the new module; GuestStatus's instance ERIs; correct the "unmonitored guest … is left off the map" text (README:153) — guests are drawn through GuestStatus when the topology opt-in is set.
- [ ] **Step 4: INSTALL §3.3 / §4:** guests drawn with no SNMP once the flag is on; `pve.guest.mac_prefix`; namespace/blacklist limitation; the group warning, verbatim from spec §5 ("set `pve.topology.enabled` and `pve.topology.cluster` on a group holding only that cluster's nodes, never on a broad or dynamic group such as "Linux Servers", or `addERI_Proxmox_VE` stamps a node key on every server in it").
- [ ] **Step 5: technicalNotes.** `addERI_Proxmox_VE`: the group warning. `Proxmox_VE_Topology`: a vertex resolves to a resource *or a DataSource instance*; guests with no resource are drawn through GuestStatus.
- [ ] **Step 6: DESIGN §4:** replace the "Guests without SNMP" follow-up with a pointer to this design and the spec. **DESIGN §7:** add both spikes (spec §2) as dated paragraphs; add table rows `addERI_Proxmox_VE_Guest` and `GuestStatus instance ERIs`, both **not verified** naming spec §6; update `Modules built` to 14 / 3 / 1 and `Portal import` wording to "all 17 previously; 1 new pending"; rewrite the one sentence "**The portal draws only vertices that match a resource.**" paragraph opener to say a resource *or an instance*, keeping the rest of that paragraph (it is the historical record of 2026-09-23). This rewrite is the only permitted §7 line change.
- [ ] **Step 7: Run the §7 guard** (Task 1 Step 3). Expected: prints only the rewritten opener line(s) from Step 6. Then the full check sequence: build `Built 18 modules`; harness PASSED; lint 0 errors.
- [ ] **Step 8: Commit** Tasks 4 and 5 together — message `Add addERI_Proxmox_VE_Guest and document guest topology (18 modules)`.

---

### Task 6: Prove the harness can still go red

**Files:** none committed.

- [ ] **Step 1:** In `scripts/Proxmox_VE_GuestStatus.ad.groovy`, change the gate to always-on (`if (true)`); rebuild; harness. Expected: FAIL in `guest-eri-off`, and in GuestStatus conformance (non-`auto.` property). Revert.
- [ ] **Step 2:** In `pveGuestMacMatch`, drop the lowercasing of the MAC; rebuild; harness. Expected: FAIL `guest-mac` (`upper`). Revert.
- [ ] **Step 3:** In `pveTopoClusterName`, change the fallback `'standalone'` to `'none'`; expected FAIL `guest-mac` (`empty`). Revert.
- [ ] **Step 4:** In `addERI_Proxmox_VE_Guest.groovy`, change priority `3` to `1`; expected FAIL `addERI-guest`. Revert.
- [ ] **Step 5:** `git status` shows a clean tree; full check sequence PASSED.

---

### Task 7: Skill lessons (separate repo `../logicmonitor-modules-skill`)

**Files:** `skill/references/collector-scripting.md` (§9 ERI/topology), `skill/references/suite-design.md` (§7), `skill/scripts/lm_lint_module.py` (`check_propertysource`), `tests/test_tools.py`

- [ ] **Step 1: References** (mark each **[portal]**, dated 2026-10-19): the ERI type comes from the highest-priority entry across *all* categories — a second module that must override the built-in `net.l2` (priority 2) needs priority 3; a DataSource instance carrying `predef.externalResourceID` / `predef.externalResourceType` (set in Active Discovery, as `VMware_vSphere_VirtualMachineStatus` does) is a vertex the map draws, so objects that are only instances can be mapped; correct both "draws only vertices that match a resource" lines.
- [ ] **Step 2: Failing test** in `tests/test_tools.py`, following the existing pattern for a WARN-level case: mutate the `addERI_Proxmox_VE` fixture's `emitEri('proxmoxve', 1, keys, ert` to `emitEri('virt.x', 1, keys, 'VirtualMachineResource'` and expect a WARN containing `priority`; the unmodified fixture must not produce it.
- [ ] **Step 3: Implement** in `check_propertysource`, for ERI PropertySources: for each `emitEri('<cat>', <n>, ..., '<ERT>'` with `<n> < 3` and `<ERT> != 'PhysicalServer'`, `report.warn` that the type will not override the built-in `net.l2` entry (priority 2) and needs priority 3 or more.
- [ ] **Step 4: Run** `python tests/test_tools.py` in the skill repo. Expected: `ALL PASSED`. Then re-run the suite's lint on `dist`: the new module must not trigger the warning (it uses priority 3); the 5 expected warnings unchanged.
- [ ] **Step 5: Commit** in the skill repo — message `Record ERI priority and instance-level ERI behaviour; warn on low-priority ERT overrides`.

---

### Task 8: Hand-off for portal verification

**Files:** none (results recorded later in DESIGN §7).

- [ ] **Step 1: Full check sequence one last time;** report the harness check count, lint result and `git log --oneline main..HEAD`.
- [ ] **Step 2: Give the user** spec §6's checklist with the import list: `dist/addERI_Proxmox_VE_Guest.json`, `dist/Proxmox_VE_GuestStatus.json`, `dist/Proxmox_VE_Topology.json`, `dist/addERI_Proxmox_VE.json`, `dist/addCategory_Proxmox_VE.json`, plus every module main changed since the last import (the nine alert-message modules), and the reminder to delete `addERI_Proxmox_VE_Spike` and `Proxmox_VE_Spike_InstanceERI`.
- [ ] **Step 3: Draft** the upstream request to LogicMonitor (spec §7) as text for the user to file.
- [ ] **Step 4: Stop.** Merging to `main` waits for the user's portal results, which go into DESIGN §7 first (and `UNVERIFIED` notes come off what passed). Do not merge or push without the user.
