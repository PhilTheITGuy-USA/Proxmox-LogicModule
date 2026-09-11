/*
 * Script collection (single instance): Ceph cluster health, OSDs, PGs and capacity.
 *
 * UNVERIFIED against a real Ceph cluster. /cluster/ceph/status is declared in the Proxmox
 * API as an untyped object -- it passes through whatever `ceph status` returns -- so every
 * field below is read defensively and nothing is assumed to exist. Ceph has also moved
 * these keys between releases, which is why osdmap is read from both the flat and the
 * nested shape.
 *
 * Nothing here is emitted as a guess. A cluster with no Ceph configured, or a Proxmox that
 * cannot reach the Ceph monitors, reports CephAvailable=0 and withholds everything else,
 * rather than reporting a healthy cluster with zero OSDs.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def status = null
    try {
        status = pveGet('/cluster/ceph/status')
    } catch (Exception unavailable) {
        // Expected on every cluster that does not run Ceph, which is most of them.
        System.err.println('Ceph status unavailable (expected when Ceph is not configured): ' +
            unavailable.message)
    }

    if (!status) {
        pveEmit(null, 'CephAvailable', 0)
        return 0
    }
    pveEmit(null, 'CephAvailable', 1)

    /*
     * health.status is HEALTH_OK / HEALTH_WARN / HEALTH_ERR. Reported as three separate
     * flags rather than an ordinal, because "worse than warn" is not a number anyone
     * should have to remember the encoding of.
     */
    def health = (status.health instanceof Map) ? status.health : [:]
    def healthStatus = health.status?.toString() ?: ''
    if (healthStatus) {
        pveEmit(null, 'HealthOK', healthStatus == 'HEALTH_OK' ? 1 : 0)
        pveEmit(null, 'HealthWarn', healthStatus == 'HEALTH_WARN' ? 1 : 0)
        pveEmit(null, 'HealthError', healthStatus == 'HEALTH_ERR' ? 1 : 0)

        def checks = (health.checks instanceof Map) ? health.checks : [:]
        pveEmit(null, 'HealthChecks', checks.size())
        if (checks) {
            System.err.println('Ceph health checks: ' + checks.keySet().join(', '))
        }
    }

    /*
     * Ceph has reported osdmap both flat and nested under a second "osdmap" key depending
     * on release. Both shapes are accepted rather than picking one and being wrong on the
     * other.
     */
    def osdmap = (status.osdmap instanceof Map) ? status.osdmap : [:]
    if (osdmap.osdmap instanceof Map) { osdmap = osdmap.osdmap }

    if (osdmap.num_osds != null) {
        def total = osdmap.num_osds ?: 0
        def up = osdmap.num_up_osds ?: 0
        def inCluster = osdmap.num_in_osds ?: 0

        pveEmit(null, 'OSDsTotal', total)
        pveEmit(null, 'OSDsUp', up)
        pveEmit(null, 'OSDsIn', inCluster)
        pveEmit(null, 'OSDsDown', (total as int) - (up as int))
        pveEmit(null, 'OSDsOut', (total as int) - (inCluster as int))
    }

    def pgmap = (status.pgmap instanceof Map) ? status.pgmap : [:]
    if (pgmap.num_pgs != null) {
        pveEmit(null, 'PGsTotal', pgmap.num_pgs ?: 0)

        /*
         * pgs_by_state is a list of {state_name, count}. state_name is a compound like
         * "active+clean" or "active+undersized+degraded", so cleanliness is decided by
         * whether every component of the state is one of the healthy ones, not by string
         * equality against "active+clean".
         */
        def healthyComponents = ['active', 'clean']
        def byState = (pgmap.pgs_by_state instanceof List) ? pgmap.pgs_by_state : []
        def clean = 0
        byState.each { entry ->
            def name = entry?.state_name?.toString() ?: ''
            def count = (entry?.count ?: 0) as int
            if (name && name.tokenize('+').every { healthyComponents.contains(it) }) {
                clean += count
            }
        }
        if (byState) {
            pveEmit(null, 'PGsClean', clean)
            pveEmit(null, 'PGsNotClean', ((pgmap.num_pgs ?: 0) as int) - clean)
        }
    }

    if (pgmap.bytes_total != null) {
        def total = (pgmap.bytes_total ?: 0) as double
        def used = (pgmap.bytes_used ?: 0) as double
        pveEmit(null, 'Capacity', total as long)
        pveEmit(null, 'UsedBytes', used as long)
        pveEmit(null, 'FreeSpace', (pgmap.bytes_avail ?: 0) as long)
        pveEmit(null, 'UsedPercent', pvePercent(used, total))
    }

    // Monitor quorum. A Ceph cluster that has lost mon quorum is down regardless of OSDs.
    def quorum = (status.quorum_names instanceof List) ? status.quorum_names : null
    def monmap = (status.monmap instanceof Map) ? status.monmap : [:]
    def mons = (monmap.mons instanceof List) ? monmap.mons.size() : monmap.num_mons
    if (quorum != null && mons != null) {
        pveEmit(null, 'MonitorsTotal', mons)
        pveEmit(null, 'MonitorsInQuorum', quorum.size())
    }

    return 0
} catch (Exception exception) {
    System.err.println('Proxmox Ceph collection failed: ' + exception.message)
    return 2
}
