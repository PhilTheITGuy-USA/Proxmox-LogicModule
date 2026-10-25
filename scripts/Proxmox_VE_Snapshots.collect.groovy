/*
 * BatchScript collection: each guest's snapshots -- how many, how old the oldest is, and how
 * many saved a VM's RAM as well as its disks.
 *
 * One /snapshot call per guest on an online node, so this module runs hourly: it is one of
 * the suite's O(guests) costs, and snapshot counts change slowly. A guest on an offline node
 * cannot be asked and reports nothing until its node returns.
 *
 * Every list carries an entry named "current", which stands for the running state and is not
 * a snapshot; it is never counted. A snapshot's snaptime is Unix seconds.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def guests = pveGuestList()
    def onlineNodes = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' && it.node }
        .collect { it.node.toString() } as Set

    /*
     * Per-node circuit breaker. A node that fails one snapshot read is treated as sick for the
     * rest of this run: its later guests are skipped instead of each spending a
     * pve.api.timeout, and stderr gets one line per node rather than per guest.
     */
    def failedNodes = [] as Set
    def nowSeconds = System.currentTimeMillis() / 1000.0d

    guests.each { guest ->
        def nodeName = guest.node?.toString()
        if (!guest.vmid || !onlineNodes.contains(nodeName) || failedNodes.contains(nodeName)) {
            return
        }
        // Per-guest try: one unreadable list withholds only this guest's datapoints.
        def entries
        try {
            entries = pveGet('/nodes/' + nodeName + '/' + guest.type + '/' + guest.vmid + '/snapshot') ?: []
        } catch (Exception guestException) {
            failedNodes << nodeName
            System.err.println('Snapshot reads stopped for node ' + nodeName + ' after ' +
                guest.type + '/' + guest.vmid + ' failed (' + guestException.message +
                '); its remaining guests report nothing this run.')
            return
        }

        def snapshots = entries.findAll { it instanceof Map && it.name?.toString() != 'current' }
        def id = guest.id
        pveEmit(id, 'SnapshotCount', snapshots.size())

        // No snapshots means no age: a 0 would read as a snapshot taken just now.
        def times = snapshots.findAll { it.snaptime != null }.collect { it.snaptime.toString().toDouble() }
        if (times) {
            pveEmit(id, 'OldestSnapshotAgeDays', pveRound((nowSeconds - times.min()) / 86400.0d))
        }
        // Only a VM can save its RAM; a container has no vmstate to report.
        if (guest.type == 'qemu') {
            pveEmit(id, 'SnapshotsWithRAM', snapshots.count { it.vmstate?.toString() in ['1', 'true'] })
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox snapshot collection failed: ' + exception.message)
    return 2
}
