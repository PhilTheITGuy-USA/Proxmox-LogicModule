/*
 * Active Discovery: the one instance Proxmox_VE_Cluster collects, the cluster itself.
 *
 * The wildvalue is the fixed "cluster", never the cluster's name, so renaming a cluster does
 * not orphan its history; the display name is the cluster name, or on a standalone host the
 * node's own (pveTopoClusterName).
 *
 * What it adds: on a cluster, the instance carries the key
 * Proxmox_VE_Topology draws the cluster vertex with, pveTopoKey(cluster, null), as type
 * Cluster -- the type that renders LogicMonitor's cluster icon (VirtualCluster renders
 * "??"). An instance vertex takes edges from every matched node resource, so the map draws
 * the cluster above all its nodes. A standalone host has
 * no cluster vertex and gets no key. With the token on several nodes of one cluster, each
 * carries this instance and the same key; INSTALL advises one token per cluster.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def status = pveGet('/cluster/status') ?: []
    def clusterRow = status.find { it.type?.toString() == 'cluster' }
    def name = pveTopoClusterName(status)

    /*
     * Written plain, not through lm.topo's eriPreProcessor, so this stays testable here --
     * the same choice and the same escape as GuestStatus's guest keys: topo.namespace or
     * topo.blacklist would rewrite the TopologySource's key, so the feature steps aside.
     */
    def raw = [:]
    if (clusterRow) {
        def blocking = ['topo.namespace', 'topo.blacklist'].findAll { pveHostProp(it) }
        if (blocking) {
            System.err.println('Cluster instance ERI skipped: ' + blocking.join(' and ') +
                ' is set, which rewrites the topology keys this would have to match.')
        } else {
            raw['predef.externalResourceID'] = pveTopoKey(name, null)
            raw['predef.externalResourceType'] = 'Cluster'
        }
    }

    pveDiscover('cluster', name, clusterRow ? 'Proxmox VE cluster' : 'Standalone Proxmox VE host',
        [pve_cluster: clusterRow?.name], raw)
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox cluster discovery failed: ' + exception.message)
    return 2
}
