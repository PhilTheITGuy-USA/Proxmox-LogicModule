/*
 * Script collection, one instance (the cluster, from Proxmox_VE_Cluster.ad): cluster quorum
 * and HA manager health.
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
     * Capacity rollups. One unfiltered /cluster/resources call returns every node, guest
     * and storage object, so the whole-environment view costs one request whatever the
     * cluster size -- the same argument that makes the BatchScript modules work.
     *
     * Overcommit is the figure worth watching: allocated vCPU and assigned memory mean
     * little without the physical capacity they are drawn from. Templates are excluded
     * because they are never running and their allocation is not really committed.
     */
    def resources = pveGet('/cluster/resources') ?: []
    def guests = resources.findAll { it.type in ['qemu', 'lxc'] && !it.template }
    def nodeRows = resources.findAll { it.type == 'node' }

    def vcpuAllocated = (guests.sum { (it.maxcpu ?: 0) as double } ?: 0.0d) as double
    def vcpuCapacity = (nodeRows.sum { (it.maxcpu ?: 0) as double } ?: 0.0d) as double
    def memAllocated = (guests.sum { (it.maxmem ?: 0) as double } ?: 0.0d) as double
    def memCapacity = (nodeRows.sum { (it.maxmem ?: 0) as double } ?: 0.0d) as double

    pveEmit(null, 'GuestsTotal', guests.size())
    pveEmit(null, 'GuestsRunning', guests.count { it.status?.toString() == 'running' })
    pveEmit(null, 'VCPUsAllocated', pveRound(vcpuAllocated))
    pveEmit(null, 'VCPUsCapacity', pveRound(vcpuCapacity))
    pveEmit(null, 'VCPUOvercommitRatio', vcpuCapacity > 0 ? pveRound(vcpuAllocated / vcpuCapacity) : 0)
    pveEmit(null, 'MemoryAllocatedBytes', memAllocated as long)
    pveEmit(null, 'MemoryCapacityBytes', memCapacity as long)
    pveEmit(null, 'MemoryOvercommitRatio', memCapacity > 0 ? pveRound(memAllocated / memCapacity) : 0)

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

        /*
         * A service whose current state has not caught up with what the CRM requested is
         * mid-transition. Briefly that is normal; persistently it is a stuck service, which
         * is why this ships without a threshold -- the alert belongs on duration, set per
         * site, not on the instantaneous count.
         */
        def mismatched = services.count { service ->
            def requested = service.request_state?.toString()
            requested && service.state?.toString() != requested
        }
        pveEmit(null, 'HAServicesRequestMismatch', mismatched)

        /*
         * Fencing is what makes HA recovery possible: a cluster that cannot fence a failed
         * node cannot safely recover its guests. Only reported by Proxmox versions that
         * expose a fencing entry, so it is withheld rather than guessed at.
         */
        def fencing = ha.find { it.type == 'fencing' }
        if (fencing) {
            pveEmit(null, 'HAFencingArmed', fencing['armed-state']?.toString() == 'armed' ? 1 : 0)
        }
    } catch (Exception haException) {
        System.err.println('HA status unavailable (this is expected when HA is not configured): ' +
            haException.message)
    }

    /*
     * Migrations. Each is its own task -- vzmigrate for a container, qmigrate for a VM --
     * with the guest's vmid as its id and its own status, recorded on the node it started
     * from. /cluster/tasks shows running tasks cluster-wide but keeps only about 25 recent
     * tasks of every type, so finished migrations are counted from each online node's own,
     * much longer history. A node whose history cannot be read withholds the hourly counts
     * rather than undercounting them; running migrations are reported either way.
     */
    def migrationTypes = ['vzmigrate', 'qmigrate']
    try {
        def running = (pveGet('/cluster/tasks') ?: []).count { task ->
            task.type?.toString() in migrationTypes && task.endtime == null && task.status == null
        }
        pveEmit(null, 'MigrationsRunning', running)
    } catch (Exception tasksException) {
        System.err.println('Running migrations not read: ' + tasksException.message)
    }

    def hourAgo = System.currentTimeMillis() / 1000L - 3600
    def completed = 0
    def failed = []
    def counted = true
    nodes.findAll { (it.online == null ? true : it.online as boolean) && it.name }.each { node ->
        migrationTypes.each { type ->
            // Per-node try: one unreachable node must not cost the others their data.
            try {
                def path = '/nodes/' + node.name + '/tasks?typefilter=' + type + '&limit=100'
                (pveGet(path) ?: []).findAll { it.starttime != null && (it.starttime as long) >= hourAgo }.each { task ->
                    if (task.status?.toString() == 'OK') {
                        completed++
                    } else if (task.status) {
                        failed << ('guest ' + task.id + ' from ' + node.name + ' (' + task.status + ')')
                    }
                }
            } catch (Exception nodeException) {
                counted = false
                System.err.println('Migrations not read from node ' + node.name + ': ' + nodeException.message)
            }
        }
    }
    if (counted) {
        pveEmit(null, 'MigrationsCompletedLastHour', completed)
        pveEmit(null, 'MigrationsFailedLastHour', failed.size())
        if (failed) {
            System.err.println('Migrations failed in the last hour: ' + failed.join(', '))
        }
    }

    return 0
} catch (Exception exception) {
    System.err.println('Proxmox cluster collection failed: ' + exception.message)
    return 2
}
