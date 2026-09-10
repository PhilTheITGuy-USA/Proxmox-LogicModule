/*
 * Script collection (single instance): cluster quorum and HA manager health.
 *
 * Works on a standalone host. When no cluster is configured, /cluster/status returns only
 * the local node and no entry of type "cluster"; the quorum datapoints are then withheld
 * entirely rather than reported as fake-healthy, so they read as "no data" and cannot
 * raise a misleading alert.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def entries = pveGet('/cluster/status') ?: []
    def cluster = entries.find { it.type == 'cluster' }
    def nodes = entries.findAll { it.type == 'node' }

    pveEmit(null, 'ClusterConfigured', cluster ? 1 : 0)

    if (cluster) {
        pveEmit(null, 'Quorate', cluster.quorate ? 1 : 0)
        pveEmit(null, 'Votes', cluster.votes ?: 0)
        pveEmit(null, 'ExpectedVotes', cluster.expected_votes ?: 0)
        pveEmit(null, 'NodesExpected', cluster.nodes ?: 0)
    }

    // A standalone node reports no explicit "online" flag for itself.
    def online = nodes.count { it.online == null ? true : it.online as boolean }
    pveEmit(null, 'NodesTotal', nodes.size())
    pveEmit(null, 'NodesOnline', online)
    pveEmit(null, 'NodesOffline', nodes.size() - online)

    /*
     * HA is optional and needs Sys.Audit on /. Its absence is not a collection failure,
     * so it is scoped to its own try and simply omits its datapoints when unavailable.
     */
    try {
        def ha = pveGet('/cluster/ha/status/current') ?: []
        def services = ha.findAll { it.type == 'service' }
        def errored = services.count { (it.state ?: it.status)?.toString() in ['error', 'fence'] }

        pveEmit(null, 'HAServicesTotal', services.size())
        pveEmit(null, 'HAServicesError', errored)
        pveEmit(null, 'HAMasterPresent', ha.any { it.type == 'master' } ? 1 : 0)
    } catch (Exception haException) {
        System.err.println('HA status unavailable (this is expected when HA is not configured): ' +
            haException.message)
    }

    return 0
} catch (Exception exception) {
    System.err.println('Proxmox cluster collection failed: ' + exception.message)
    return 2
}
