/*
 * BatchScript collection: per-OSD state, fill and latency.
 *
 * One call for the whole cluster -- see the discovery body for why the CRUSH tree is
 * cluster-global rather than per node.
 *
 * Which fields an OSD carries depends on what Ceph could report for it. status, in,
 * crush_weight, reweight, device_class and pgs come from `osd df tree`; total_space,
 * bytes_used and percent_used from `osd df`; the two latencies from the perf dump. A down
 * OSD commonly reports state but no usage and no latency, so every one of those is
 * withheld when absent rather than reported as zero. A zero fill on a down OSD would read
 * as an empty, healthy disk.
 *
 * UNVERIFIED against a real Ceph cluster.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

def collectOSDs = { root ->
    def found = []
    def stack = [root]
    while (stack) {
        def entry = stack.remove(stack.size() - 1)
        if (!(entry instanceof Map)) { continue }
        if (entry.type?.toString() == 'osd') {
            found << entry
        }
        if (entry.children instanceof List) {
            entry.children.each { stack << it }
        }
    }
    return found
}

try {
    def nodes = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' }

    def tree = null
    for (node in nodes) {
        def nodeName = node.node?.toString()
        if (!nodeName) { continue }
        try {
            tree = pveGet('/nodes/' + nodeName + '/ceph/osd')
            if (tree) { break }
        } catch (Exception nodeException) {
            System.err.println('Ceph OSD tree unavailable from ' + nodeName + ': ' +
                nodeException.message)
        }
    }

    if (!tree || !(tree.root instanceof Map)) {
        System.err.println('No Ceph OSD tree available from any online node. This is expected ' +
            'on a cluster that does not run Ceph.')
        return 0
    }

    // "noout" suppresses rebalancing when an OSD goes down. It is set deliberately during
    // maintenance and forgotten about afterwards, which is how a cluster ends up degraded
    // and quiet, so it is worth reporting alongside the OSDs it affects.
    def flags = tree.flags?.toString() ?: ''
    def nooutSet = flags.tokenize(',').collect { it.trim() }.contains('noout')

    collectOSDs(tree.root).each { osd ->
        def id = osd.name?.toString()
        if (!id) { return }

        pveEmit(id, 'Up', osd.status?.toString() == 'up' ? 1 : 0)
        pveEmit(id, 'In', (osd.in ?: 0) as int > 0 ? 1 : 0)
        pveEmit(id, 'NoOutFlagSet', nooutSet ? 1 : 0)

        if (osd.crush_weight != null) {
            pveEmit(id, 'CrushWeight', pveRound((osd.crush_weight as double)))
        }
        if (osd.reweight != null) {
            pveEmit(id, 'Reweight', pveRound((osd.reweight as double)))
        }
        if (osd.pgs != null) {
            pveEmit(id, 'PlacementGroups', osd.pgs)
        }

        // Usage is absent for an OSD Ceph could not read. Zero would look like a healthy
        // empty disk, which is the opposite of what a missing reading means.
        if (osd.total_space != null) {
            pveEmit(id, 'Capacity', osd.total_space)
            pveEmit(id, 'UsedBytes', osd.bytes_used ?: 0)
        }
        if (osd.percent_used != null) {
            pveEmit(id, 'UsedPercent', pveRound((osd.percent_used as double)))
        }

        if (osd.commit_latency_ms != null) {
            pveEmit(id, 'CommitLatencyMs', pveRound((osd.commit_latency_ms as double)))
        }
        if (osd.apply_latency_ms != null) {
            pveEmit(id, 'ApplyLatencyMs', pveRound((osd.apply_latency_ms as double)))
        }
    }

    if (nooutSet) {
        System.err.println('Ceph OSD flags are set on this cluster: ' + flags)
    }

    return 0
} catch (Exception exception) {
    System.err.println('Proxmox Ceph OSD collection failed: ' + exception.message)
    return 2
}
