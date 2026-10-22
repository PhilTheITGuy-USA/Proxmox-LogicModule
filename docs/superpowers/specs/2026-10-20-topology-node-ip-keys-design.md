# Topology without per-cluster properties, and a cluster vertex that is drawn

Status: implemented on branch `topology-node-ip-keys`. §3.4 was dropped after its first spike failed and restored after the re-run (DESIGN §7, spike 3 and its re-run): the cluster vertex is drawn. A node vertex carries one key, not both (DESIGN §7, 2026-10-21). Portal results are in DESIGN §7 only.

## 1. Goal

One dynamic group holding every Proxmox host in the portal, across any number of clusters,
carrying only `pve.topology.enabled=true`, draws every node under its cluster and every guest
under its node. No property is set per cluster or per node.

Today that fails in two places:

- **Token-less nodes need `pve.topology.cluster`.** A node without the API token cannot ask
  `/cluster/status`, so `pveTopoNodeIdentity` takes the cluster name from that property and
  nowhere else. One group holding two clusters can carry only one value, so the second cluster's
  nodes get keys that match nothing — or, worse, a node of the second cluster sharing a name with
  one of the first (`pve1` is the default everywhere) is stamped with the first cluster's key and
  drawn under the wrong cluster.
- **The cluster vertex is never drawn.** Its key, `pveTopoKey(cluster, null)`, matches no resource
  and no instance, and the portal drops a vertex that matches nothing (DESIGN §7, 2026-10-20). So
  the map shows nodes and guests but no cluster above them, and no Cluster edges.

`pve.topology.node` is not part of the problem: it is needed only where a resource has neither
`system.sysname` nor a non-IP `system.hostname`, which a node monitored over SNMP or SSH always has.

## 2. Evidence (2026-10-20, live two-node cluster unless marked)

- `Node-A` and `Node-B`, joined into one cluster: `ClusterConfigured` 1, two nodes; the map
  draws both nodes and their guests and no cluster vertex.
- `Node-B`'s `system.ips` is `fe80:0:0:0:be24:11ff:fe00:1,192.0.2.211`, and
  `pvesh get /cluster/status` reports `192.0.2.211` as Node-B's `ip`. So the address the
  cluster reports for a node is in that node's `system.ips`, and LogicMonitor writes IPv6 there
  uncompressed.
- The API schema documents a node row's `ip` in `/cluster/status` as "IP of the resolved nodename"
  (schema, not portal): one address per node, the one its name resolves to.
- Proxmox exposes no node MAC or hardware UUID anywhere in its API (already in the TopologySource
  and `addERI_Proxmox_VE` headers), so there is no hardware key to use instead.
- Spike 2 (DESIGN §7, 2026-10-19): a vertex resolves to a DataSource instance carrying
  `predef.externalResourceID`. Proven for guests (`VirtualMachine`); not yet for any other type.

## 3. Design

### 3.1 A node key both sides can build without the cluster name

New preamble helpers, harness-reachable like the existing topology helpers:

- `pveTopoIp(String)` — normalises one address, or returns null. Accepts only an IP literal
  (a dotted quad with every octet 0-255, or anything containing `:`), so it never triggers a DNS
  lookup — Java resolves anything else it is handed as a hostname; parses it with
  `InetAddress.getByName`, drops a `%scope` suffix, and returns null for loopback, link-local and
  wildcard addresses. Returns `getHostAddress()` lowercased, which writes IPv6 uncompressed —
  the form `system.ips` already uses, and what a compressed `/cluster/status` value is converted to.
- `pveTopoNodeKey(String node, String ip)` — `proxmoxve--node--<node>--<ip>`, folded through the
  same alphabet as `pveTopoKey` (`[a-z0-9_-]`, everything else `-`). Null when either part is
  missing or `pveTopoIp` rejects the address. The node part is the short name, lowercased.

`pveTopoKey` is unchanged, and so is the key it builds.

**Why name plus IP.** The name alone collides across clusters (`pve1`). The IP alone collides
across overlapping private networks. Together they are unique in practice. Where two environments
reuse both — an MSP monitoring copies of one lab — `topo.namespace` is LogicMonitor's own answer, and
both sides already honour it.

### 3.2 `Proxmox_VE_Topology`: every node vertex carries one key

Each node vertex carries `pveTopoNodeVertexKey(cluster, node, ip)`: the IP key
`pveTopoNodeKey(node, ip)`, from that node's `/cluster/status` row (the call the script already
makes), or `pveTopoKey(cluster, node)` only when the row has no usable address. Existing
deployments keep matching through the upgrade because `addERI_Proxmox_VE` still stamps the old
key alongside the new one.

As first written this section gave the vertex both keys, on the assumption that a vertex matches a
resource carrying any one of them. The portal disproved that: lm.topo joins a vertex's keys into
one ID, and only a resource carrying all of them matches, so the token-less node was left undrawn
(DESIGN §7, 2026-10-21).

**A standalone host gets no cluster vertex.** When `/cluster/status` has no `cluster` row the script
emits no cluster vertex and no Cluster edges; nodes and guests are unchanged. A one-node "cluster"
above a standalone host carries no information.

### 3.3 `addERI_Proxmox_VE`: stamp the IP keys on every path

The key list becomes:

1. `pveTopoKey(cluster, node)`, exactly as today, whenever the identity is known: from
   `/cluster/status` with a token, or from `pve.topology.cluster` without one.
2. One `pveTopoNodeKey(node, ip)` per address in `system.ips` that `pveTopoIp` accepts, plus the
   local row's `ip` on the token path, deduplicated.

The node name for (2) is the local row's `name` with a token; without one it is
`pve.topology.node`, else the short `system.sysname` / `system.hostname` — the rule
`pveTopoNodeIdentity` already applies, split out so it no longer depends on the cluster name.
The script prints nothing when it has no node name, or no key at all.

`pve.topology.cluster` stays honoured and becomes optional. Its documentation changes to: not
needed; set it only on a group holding one cluster's nodes, never on a group spanning clusters,
because a wrong value can attach a node to a same-named node of another cluster.

**Keys on non-Proxmox resources.** If `pve.topology.enabled` reaches a server that is not Proxmox,
it now gets name-plus-IP keys where today, without a cluster name, it got nothing. They can only match
a Proxmox node with the same name *and* address, i.e. never, and `PhysicalServer` at priority 1
cannot change a resource's type. The technicalNotes and INSTALL say to scope the opt-in to Proxmox
hosts, with the dynamic group below as the worked example.

### 3.4 The cluster vertex, drawn through a `Proxmox_VE_Cluster` instance

**Dropped.** The spike failed (DESIGN §7, spike 3); nothing below was built.

Gated on a spike (§6 item 1). If the spike fails, this section is dropped, §3.2's standalone rule
still ships, and DESIGN §7 records why the cluster vertex is not drawn.

`Proxmox_VE_Cluster` becomes `multiInstance` with collection method still `script` — the shape
`Proxmox_VE_NodeDetail` already has — so its collection script and datapoint keys do not change. A
new AD body, `Proxmox_VE_Cluster.ad.groovy`, emits exactly one instance:

- wildvalue `cluster`, fixed, so renaming the cluster does not orphan it;
- display name the cluster name, `pveTopoClusterName(status)`;
- when `pve.topology.enabled == "true"` **and** `/cluster/status` has a `cluster` row:
  `predef.externalResourceID = pveTopoKey(cluster, null)` and `predef.externalResourceType` set to
  the type the spike found to render as a cluster. On a standalone host, or with the opt-in off,
  neither property is set, and AD removes them where they were (shown for GuestStatus, DESIGN §7).

**Cost:** converting a single-instance module to multi-instance discards the module's history once.
The dashboards are unaffected: every widget already globs instances with `*`. A token on several
nodes of one cluster gives each a Cluster instance with the same key; that is the same unproven
case GuestStatus's instance ERIs already carry (DESIGN §7, item 7), and the docs already advise one
token per cluster.

### 3.5 What does not change

`pveTopoKey`, the guest keys (MAC, or the NIC-less fallback), GuestStatus's instance ERIs,
`addERI_Proxmox_VE_Guest`, every AppliesTo, intervals and call counts. The TopologySource's call
count is unchanged; `addERI_Proxmox_VE` still makes one call with a token and none without.

## 4. Tests

- **Fixture:** `/cluster/status` node rows gain `ip` (`pve1` an IPv4 address, `pve2` an IPv6 address
  in compressed form, to exercise normalisation).
- **`pveTopoIp`:** IPv4 passes; compressed and uncompressed IPv6 normalise to the same string;
  `%scope` stripped; `127.0.0.1`, `::1`, `fe80::…` and `0.0.0.0` rejected; a hostname rejected
  without a lookup.
- **`pveTopoNodeKey`:** exact key for IPv4 and IPv6; null on a missing part; folds an FQDN-ish or
  mixed-case name the way `pveTopoKey` does.
- **Round trip:** the key built from a fixture `/cluster/status` row equals the key built from a
  `system.ips` string containing the same address in LogicMonitor's form, for both IPv4 and IPv6.
  This is the byte-for-byte property the whole design rests on.
- **Node name without a cluster:** the split-out name rule returns the short hostname with no
  `pve.topology.cluster` set, and null for an IP-only resource without `pve.topology.node`.
- **Cluster AD** (if §3.4 ships): exactly one instance, wildvalue `cluster`; ERI properties present
  with the opt-in on a cluster, absent with the opt-in off, absent on a standalone fixture; the
  existing collection assertions still pass with an instance binding.
- **Mutation check:** breaking the IPv6 normalisation, or adding the node's cluster to
  `pveTopoNodeKey`, must turn the harness red.

The TopologySource and `addERI_Proxmox_VE` still cannot run outside a Collector; as today, their
Proxmox-side logic is what the harness reaches, through the helpers.

## 5. Documentation

- **INSTALL §3.3 / topology:** one dynamic group, `pve.topology.enabled=true`, no per-cluster
  property; the AppliesTo `(system.sysinfo =~ "-pve" || hasCategory("ProxmoxVE")) &&
  !(auto.network.mac_address =~ "^[bB][cC]:24:11")` as the worked example; `pve.topology.cluster`
  demoted to an optional override with the multi-cluster warning; `pve.topology.node` only for a
  resource added by IP with no SNMP or SSH.
- **README** install paragraph and Known limits; **CLAUDE.md** paragraphs on `addERI_Proxmox_VE`,
  the preamble helper list, and the module table row for `Proxmox_VE_Cluster` (discovery column)
  if §3.4 ships.
- **technicalNotes** of `addERI_Proxmox_VE`, `Proxmox_VE_Topology` and, if it ships,
  `Proxmox_VE_Cluster`. The TopologySource header's "matches nothing on purpose, like VMware" and
  "renders only vertices that match a resource" are corrected.
- **DESIGN §4/§7:** new rows per §6 below; §7's unproven-list entry for the token-less node is
  replaced by the outcome.

## 6. Portal verification before merging

1. **Spike (before §3.4 is built):** a throwaway DataSource on the token-holding resource discovers
   one instance carrying `predef.externalResourceID = proxmoxve--<auto.pve.cluster.name>` and a
   candidate `predef.externalResourceType`. Pass: the cluster vertex is drawn with Cluster edges to
   both nodes. Try `Cluster` first; if it does not render as a cluster, try LogicMonitor's other
   types seen in its own exports and record which. Delete the spike module afterwards.
2. **Token on one node only**, `pve.topology.cluster` removed from the group: after the next
   `addERI_Proxmox_VE` run, the token-less node's resource carries
   `proxmoxve--node--node-b--192-0-2-211` (its short hostname, lowercased), and after the next topology run it is still drawn
   with its guests.
3. **Cluster vertex** (if §3.4 ships): drawn, with a Cluster edge to each node; Cluster dashboard
   tiles still populate.
4. **Opt-in off** on the token holder: Cluster AD removes its ERI properties.
5. **A second cluster in the same group** — not reachable in this lab; stays unproven in §7 unless
   one becomes available.

## 7. Separately

Not in scope: per-cluster dynamic groups, publishing membership from the token node to the others,
and dropping the old `pveTopoKey(cluster, node)` key. The old key costs nothing, keeps existing
deployments matching through the upgrade, and is the exact key where a token is present.
