# Guest topology: correct labels, and every guest on the map

Date: 2026-10-19. Branch: `topology-optin-tokenless-eri` (builds on `ffe5652`), merged to `main`
as one piece once the portal check in §6 passes.

## 1. Goal

An Exchange-ready suite in which a customer gets correct guest topology with no per-guest steps:

1. A Proxmox guest monitored as its own LogicMonitor resource is labelled as a virtual machine,
   not `PhysicalServer`.
2. Every guest Proxmox reports is drawn on the topology map under its node, whether or not it is
   a LogicMonitor resource, and whether or not that resource answers SNMP or WMI. A cluster of 500
   ping-only or unmonitored containers must need no per-guest configuration.

Out of scope: guests whose LogicMonitor resource carries a MAC outside every configured prefix
(hand-set MACs), and getting LogicMonitor to change its own VM prefix list (an optional upstream
request, §7).

## 2. Evidence (all 2026-10-19, portal and live cluster unless marked)

**Why guests are `PhysicalServer`.** LogicMonitor's built-in `addERI_Windows` and `addERI_Linux`
default to `PhysicalServer` and upgrade a resource to `VirtualMachineResource` only when one of its
MACs starts with an OUI in `lm.data.topo`'s VM list (`00:50:56`, `00:0c:29`, `00:15:5d`,
`52:54:00`, `50:6b:8d`), when Windows reports `system.model` `Virtual Machine` (Hyper-V) or `AHV`,
or when Linux over SSH finds an Azure line in `dmesg`. Proxmox's default guest prefix `bc:24:11`
is on no list, and a Proxmox guest's model is `Standard PC (Q35 + ICH9, 2009)`. Observed: a Windows
11 Pro and a Windows Server guest (VMID 200), a Debian 13 VM over SNMP (VMID 201) and an LXC guest
over SNMP (`Nvr01`, CT 100) are all `PhysicalServer`, whatever their `system.sysinfo` says. A
physical Raspberry Pi (`88:a2:9e`) is `PhysicalServer` too, correctly; it is the control case.

**What a guest reports about itself.** Windows over WMI: `auto.endpoint.manufacturer = QEMU`,
`auto.processor.name = Common KVM processor`. Linux VM over SNMP: nothing that marks it virtual.
LXC over SNMP: the host's `-pve` kernel in `system.sysinfo`. Only the MAC prefix is common to all
three, and it is in `auto.network.mac_address` on every SNMP and WMI resource seen (uppercase on
Windows, lowercase over SNMP).

**Spike 1 -- which ERI sets the type.** A throwaway ERI PropertySource on Debian-test emitted one
key, category `virt.proxmoxve`, type `VirtualMachineResource`. At priority 1 the key merged into
`predef.externalResourceID` and the type stayed `PhysicalServer`. At priority 3 the type became
`VirtualMachineResource`, and the guest stayed a child of `Node-B` on the map, drawn as a VM.
So the type comes from the highest-priority entry *across* categories; the built-in `net.l2`
entry is priority 2. Keys from every category merge into the ID.

**Spike 2 -- instance-level ERIs.** LogicMonitor's `VMware_vSphere_VirtualMachineStatus` sets
instance properties `predef.externalResourceID` (the VM's MACs) and `predef.externalResourceType
= VirtualMachine` in Active Discovery, through `lm.emit` and `lm.topo.eriPreProcessor`. A
throwaway DataSource did the same for two guests: the ping-only `Relay01` (CT 103 on
`Node-A`), which had never been drawn, was drawn under its node; `Debian-test` (VMID 201), which
has its own resource with the same MAC, stayed drawn once and correctly. So a vertex can resolve
to a DataSource instance, not only a resource.

## 3. Design

### 3.1 `addERI_Proxmox_VE_Guest` -- label guests that are resources

New ERI PropertySource, group `ERI`, `collectionInterval` 1800, **always on** (not `optional`).

AppliesTo, as shipped:

```
(auto.network.mac_address =~ "^[bB][cC]:24:11" || pve.guest.mac_prefix) && (isLinux() || isWindows())
```

> **Amended 2026-10-20 (final review).** The approved AppliesTo was the MAC clause alone,
> `auto.network.mac_address =~ "^[bB][cC]:24:11" || pve.guest.mac_prefix` -- superseded. An
> always-on priority-3 `VirtualMachineResource` ERI keyed only on MAC prefix would relabel virtual
> appliances running as Proxmox guests (FortiGate-VM, pfSense/OPNsense, virtual routers), which
> LogicMonitor's own modules label; and LogicMonitor's own VM labelling lives only in
> `addERI_Linux` / `addERI_Windows`. So the module is limited to Linux and Windows resources. The
> cost if `isLinux()` / `isWindows()` misses a real guest is that guest staying `PhysicalServer`,
> which the §6 check shows; reverting is one string.

The character classes cover both cases without relying on an inline `(?i)` flag.
`pve.guest.mac_prefix` lets a customer whose cluster uses a different prefix (Datacenter >
Options) bring their guests in by setting it on a group; it may hold a comma-separated list.

Script:

1. Prefixes: `pve.guest.mac_prefix` split on commas, trimmed and lowercased, else `bc:24:11`.
2. MAC: `auto.network.mac_address`, lowercased.
3. No match: exit 0, no output. This is what keeps a broad override group and the Pi untouched.
4. Match: emit one key `proxmoxve--vm--<mac>` in category `virt.proxmoxve`, priority **3**, type
   `VirtualMachineResource`, through `lm.topo.emitEri` / `printEriArray` (honouring
   `topo.namespace` and `topo.blacklist` as the other ERI modules do).

The match is a preamble helper, `pveGuestMacMatch(mac, prefixProperty)`, returning the lowercase
MAC or null, so the harness can test it. The script itself is Collector-only, exactly like
`addERI_Proxmox_VE` (`dist/scripts/collector-only/`, skipped by the harness). It makes no API call
and never reads `pveConfigError`: a guest has no token, so the preamble always sets it.

The key carries no join: the guest already joins its vertex through the MAC that `net.l2` holds.
It exists to carry the type at a priority that wins.

### 3.2 GuestStatus instance ERIs -- every guest on the map

GuestStatus gets its own discovery body, `Proxmox_VE_GuestStatus.ad.groovy`, ending its shared use
of `Proxmox_VE_Guests.ad.groovy`, which GuestPerformance keeps. The guest listing, template filter
and display properties move into a preamble helper both bodies call, so the instance set and the
wildvalue (`pveWildValue(guest.id)`) are unchanged -- no instance and no history is orphaned. Only
GuestStatus carries the keys, as only VMware's status module does, so each guest's key sits on one
instance per Proxmox resource.

Gated on the topology opt-in: only when `pve.topology.enabled` is `true` on the resource, and
neither `topo.namespace` nor `topo.blacklist` is set, discovery additionally:

1. calls `/cluster/status` once for the cluster name, and `/cluster/resources?type=node` to know
   which nodes are online;
2. for each guest on an online node, reads `/nodes/{node}/{type}/{vmid}/config` inside a
   per-guest `try` and takes the MAC with `pveGuestMac`;
3. adds two instance properties, unprefixed:
   - `predef.externalResourceID` = the MAC, or for a guest with no NIC
     `pveTopoKey(cluster, kind + '-' + vmid)` -- the same fallback key the TopologySource emits;
   - `predef.externalResourceType` = `VirtualMachine` (VMware's instance value, distinct from the
     resource type `VirtualMachineResource`).

`pveDiscover` gains an optional map of properties written without the `auto.` prefix.

The value is the plain lowercase MAC, not `lm.topo.eriPreProcessor`'s output, so the discovery body
stays compiled and harness-tested. With no namespace or blacklist the two are the same (the
Windows guest's built-in ERI was the lowercase MAC, and the map joined it to the TopologySource's
key). With either property set they would differ and the guest would silently fail to draw, so in
that case discovery adds no keys and writes the reason to `System.err`. Documented as unsupported.

Failure handling: the map is the optional extra, the instance is not. A failed config read or a
guest on an offline node still discovers, without keys. If the gating calls fail, discovery falls
back to exactly today's output. Only `/cluster/resources?type=vm` failing exits 2, as now.

Cost: with the flag off, unchanged -- one call. With it on, 2 + 1 per guest on an online node, at
the existing 60m discovery interval; the same O(guests) the TopologySource already pays hourly.

### 3.3 What does not change

- `Proxmox_VE_Topology` already emits the guest's MAC, or the `pveTopoKey` fallback, and needs no
  change beyond its `technicalNotes` (§5).
- `addERI_Proxmox_VE` is unchanged in code. Its documentation gains the group warning in §5.
- GuestPerformance discovers exactly as before and never carries the keys.

## 4. Tests

Harness (`tests/harness.groovy`), mutation-checked afterwards per CLAUDE.md:

- `pveGuestMacMatch`: default prefix matches; uppercase Windows MAC matches; override list (two
  prefixes) matches either; the Pi's `88:a2:9e:0e:00:09` is rejected; null and blank return null.
- GuestStatus discovery, flag off: output identical to `Proxmox_VE_Guests.ad.groovy`'s, and no
  `config` path requested.
- Flag on: every guest on online `pve1` carries `predef.externalResourceID` = its lowercase MAC
  and `predef.externalResourceType=VirtualMachine`; guests on offline `pve2` are discovered with
  neither; the template stays excluded.
- Flag on with `topo.namespace` set: no keys, a stderr message, exit 0.
- Flag on with one guest's config fixture missing (mock 501): that guest is still discovered,
  without keys, and the others keep theirs.
- GuestPerformance discovery never carries either property.

Fixtures: the fixture cluster has one non-template guest on `pve1` (`qemu/101`, which has a
config fixture). The cases above need at least an LXC guest on `pve1` with an `hwaddr=` config
and a `pve1` guest with no config fixture. Adding guests to `/cluster/resources?type=vm` changes
what every guest module discovers, so existing assertions are updated in the same change; the
implementation plan settles whether that, or per-case fixture overrides, is cleaner.

Local checks: `build.py --check`, both Docker jobs, `lm_lint_module.py dist` (its expected-warning
count in CLAUDE.md is updated if the new module adds any), and `lm_dashboards.py check`.

## 5. Documentation

- Module count 17 -> 18: fourteen DataSources, three PropertySources, one TopologySource.
  `check_doc_counts` fails until README, INSTALL, DESIGN §4/§7 and CLAUDE.md agree; unchecked
  phrasings are fixed by hand. CLAUDE.md's module table gains a row; its "Nine AD bodies back
  eleven multiInstance modules" becomes ten.
- README coverage table: the new module, and GuestStatus's instance ERIs.
- INSTALL §3.3: guests are drawn through GuestStatus once the flag is on, with no SNMP needed;
  `pve.guest.mac_prefix` for a changed prefix; the namespace/blacklist limitation; and the group
  warning: set `pve.topology.enabled` and `pve.topology.cluster` on a group holding only that
  cluster's nodes, never on a broad or dynamic group such as "Linux Servers", or
  `addERI_Proxmox_VE` stamps a node key on every server in it.
- `technicalNotes`: the new module; GuestStatus (instance ERIs, gating, cost); `addERI_Proxmox_VE`
  (the group warning); `Proxmox_VE_Topology` (vertices resolve to a resource *or an instance*).
  The new module and GuestStatus carry an `UNVERIFIED` paragraph until §6 passes.
- DESIGN §4: this design replaces the "Guests without SNMP" follow-up. DESIGN §7: both spikes, and
  "the portal draws only vertices that match a resource" corrected to "a resource or an instance".
- Skill (`logicmonitor-modules-skill`, its own repo), per the workspace rule: the ERI type is the
  highest priority across categories; DataSource instances can carry `predef.externalResourceID`
  and be drawn; the "only resources are drawn" line corrected; a lint check or `test_tools.py`
  mutation case where one is possible; `python tests/test_tools.py` prints ALL PASSED.

## 6. Portal verification before merging

1. Import the changed and new modules (`addERI_Proxmox_VE_Guest`, GuestStatus, and the branch's
   existing changes), then delete the two spike modules.
2. Set `pve.topology.enabled=true` on a group holding only the nodes. Both lab hosts are
   standalone and each holds its own token, so neither needs `pve.topology.cluster` (INSTALL §3.3).
3. Debian-test, the Windows Server guest and Nvr01 read `VirtualMachineResource`; the Pi reads
   `PhysicalServer`. Added 2026-10-20: this also proves the narrowed AppliesTo's
   `isLinux() || isWindows()` matches all three guests (SNMP Linux VM, LXC, Windows over WMI); and
   a Proxmox node resource, as a second control, must stay `PhysicalServer`.
4. Every guest on `Node-A` and `Node-B` is drawn under its node, including ones with no
   resource of their own.
5. An instance's `predef.externalResourceID` reads as a MAC with colons, not `bc%3A24%3A11...`:
   `pveDiscover` URL-encodes values, where the spike used `lm.emit`.
6. Turn the flag off and re-run discovery: record whether the two instance properties are removed
   or left behind.
7. Not reachable in this lab, so recorded as unproven: the token on several nodes of one cluster,
   which puts the same key on several instances.

Results go in DESIGN §7; `UNVERIFIED` notes come off what passed.

## 7. Separately

A short request to LogicMonitor to add `bc:24:11` to `lm.data.topo`'s VM OUI list, drafted for the
user to file. The suite does not depend on it.
