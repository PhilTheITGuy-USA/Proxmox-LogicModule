/*
 * TopologySource: cluster -> node -> guest edges, so Proxmox appears in topology maps and
 * a node's alerts can explain its guests'.
 *
 * THIS SCRIPT CANNOT BE COMPILED OR RUN OUTSIDE A COLLECTOR. Every LogicMonitor
 * TopologySource works through com.logicmonitor.mod.Snippets: lmTopo.eriPreProcessor normalises keys and drops the blocked ones, lmTopo.isMac
 * validates a MAC, and lmTopo.generateTopology emits the output format. Reimplementing those
 * would mean inventing a format LogicMonitor does not document fully, and an output the
 * portal does not recognise is dropped silently. So this script takes the Collector
 * dependency. Everything it does with the Proxmox API uses
 * the same preamble as every other module, so that half stays conventional.
 *
 * The identity that makes this work:
 *
 *   guest  -- the MAC of its first virtual NIC, read from the guest config. LogicMonitor
 *             already gives a monitored guest a MAC-keyed ERI, so the two match with no
 *             name guessing. This is what VMware_vSphere_VirtualMachine_Topology does.
 *   node   -- Proxmox exposes no hardware UUID or MAC for a node anywhere in its API
 *             (/cluster/status gives name, ip, nodeid; /nodes/{node}/status gives none;
 *             /nodes/{node}/network has no hwaddr). So the node keys are synthesised,
 *             and addERI_Proxmox_VE stamps the identical keys on each node's own
 *             resource. The vertex carries one: pveTopoNodeKey(node, ip), which needs only
 *             the node's name and the address /cluster/status reports for it -- among
 *             the resource's system.ips, so a token-less node matches with no property
 *             set. Only a row with no usable address falls back to pveTopoKey(cluster,
 *             node). Never both: lm.topo joins a vertex's keys into one ID, and the portal
 *             then matches only a resource carrying all of them. A node whose resource
 *             lacks the key is not drawn: the portal
 *             renders only vertices that match a resource or a DataSource instance.
 *   cluster - synthesised, pveTopoKey(cluster, null). Proxmox_VE_Cluster's one instance
 *             carries the same key as type Cluster, and an instance
 *             vertex takes edges from every matched node, so the cluster is drawn above all
 *             its nodes. A standalone host has no
 *             cluster, and gets no cluster vertex and no Cluster edges.
 */
import com.logicmonitor.mod.Snippets
import com.santaba.agent.groovy.utils.GroovyScriptHelper as GSH

if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

def debug = false
def log = false
def logCacheContext = 'Proxmox_VE_Topology'

// Namespacing and the key blacklist are host properties LogicMonitor's own topology
// scripts honour; a portal with duplicate keys across environments relies on them.
def keyNamespace = pveHostProp('topo.namespace') ? pveHostProp(pveHostProp('topo.namespace')) : ''
def keyBlacklist = (pveHostProp('topo.blacklist') ?: '').tokenize(',')

def modLoader = GSH.getInstance(GroovySystem.version)
    .getScript('Snippets', Snippets.getLoader()).withBinding(getBinding())
def lmDebugSnip = modLoader.load('lm.debug', '1')
def lmDebug = lmDebugSnip.debugSnippetFactory(out, debug, log, logCacheContext)
def lmTopo = modLoader.load('lm.topo', '0')
def lmTopoData = modLoader.load('lm.data.topo', '1').create()

def eri = { List keys ->
    lmTopo.eriPreProcessor(keys, lmTopoData.blockedKeys, keyNamespace, keyBlacklist)
}

try {
    def edges = []

    /*
     * The cluster's own name, which /cluster/status reports as the single row of type
     * "cluster". A standalone host has no such row: it gets no cluster vertex, and its
     * name falls back to the node's for the guests' fallback keys, which still have to be
     * unique across a portal that monitors several standalone hosts.
     */
    def status = pveGet('/cluster/status') ?: []
    def clusterName = pveTopoClusterName(status)
    def clustered = status.any { it.type?.toString() == 'cluster' }
    def clusterEri = clustered ? eri([pveTopoKey(clusterName, null)]) : null

    // The address /cluster/status reports for each node: the second half of its IP key.
    def nodeIps = status.findAll { it.type?.toString() == 'node' && it.name }
        .collectEntries { [(it.name.toString()): it.ip?.toString()] }

    def nodes = (pveGet('/cluster/resources?type=node') ?: [])
    def nodeEris = [:]
    def onlineNodes = [] as Set
    nodes.each { node ->
        def nodeName = node.node?.toString()
        if (!nodeName) { return }
        // One key, never two (see the header); a row with no usable address falls back
        // to the cluster-scoped key, so no null reaches eriPreProcessor.
        nodeEris[nodeName] = eri([pveTopoNodeVertexKey(clusterName, nodeName, nodeIps[nodeName])])
        if (node.status?.toString() == 'online') { onlineNodes << nodeName }
        // Every node gets its cluster edge, offline included: a node that is down is
        // still part of the cluster, and dropping it would make the map forget the
        // thing that has just gone wrong.
        if (clusterEri) {
            lmTopo.registerEdge('Cluster', clusterEri, nodeEris[nodeName], edges)
        }
    }

    /*
     * One config read per guest, O(guests) -- acceptable because a TopologySource runs
     * hourly, not every five minutes, and the MAC lives nowhere else: /cluster/resources
     * carries no network detail at all.
     */
    def guests = (pveGet('/cluster/resources?type=vm') ?: [])
        .findAll { it.type in ['qemu', 'lxc'] && !it.template }

    guests.each { guest ->
        def nodeName = guest.node?.toString()
        def vmid = guest.vmid
        def kind = guest.type?.toString()
        def nodeEri = nodeEris[nodeName]
        if (!nodeEri || !vmid || !kind) { return }

        /*
         * An offline node cannot answer for its guests, and the online filter is what
         * stops the fan-out spending a pve.api.timeout on each of them -- the same
         * reasoning as every other module that reaches into a node's own API.
         */
        if (!onlineNodes.contains(nodeName)) { return }

        /*
         * Per-guest try, and the two failures it distinguishes are not the same thing.
         *
         * A config that reads but has no MAC is a guest with no virtual NIC. That is a
         * permanent fact, so the synthesised fallback key is stable -- what VMware's VM
         * topology does. It resolves to no resource, and the portal draws no vertex that
         * matches nothing, so such a guest is emitted but stays off the map.
         *
         * A config that cannot be read is transient. Falling back there would change the
         * guest's identity for one run and change it back on the next, rewriting the map
         * around a blip. Omitting the edge is the smaller error, so the guest is skipped.
         */
        def config = null
        try {
            config = pveGet('/nodes/' + nodeName + '/' + kind + '/' + vmid + '/config')
        } catch (Exception guestException) {
            System.err.println('Config read skipped for ' + kind + '/' + vmid + ': ' +
                guestException.message)
            return
        }
        if (config == null) { return }

        def mac = pveGuestMac(config)
        def guestEri = (mac && lmTopo.isMac(mac, lmTopoData.blockedKeys, false)) ?
            eri([mac]) : eri([pveTopoKey(clusterName, kind + '-' + vmid)])

        lmTopo.registerEdge('Compute', nodeEri, guestEri, edges)
    }

    println lmTopo.generateTopology(edges, keyNamespace, keyBlacklist, null, debug)
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox topology collection failed: ' + exception.message)
    return 2
}
