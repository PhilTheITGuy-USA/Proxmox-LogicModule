/*
 * BatchScript collection: node headline metrics, cluster-wide, in one API call.
 * Load average, swap and root filesystem live in Proxmox_VE_NodeDetail because they
 * require a per-node call that /cluster/resources cannot serve.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodes = pveGet('/cluster/resources?type=node') ?: []

    nodes.each { node ->
        def id = node.id
        def online = node.status?.toString() == 'online'

        pveEmit(id, 'Status', online ? 1 : 0)
        pveEmit(id, 'CPUUsagePercent', pveRound(((node.cpu ?: 0) as double) * 100.0d))
        pveEmit(id, 'CPUCount', node.maxcpu ?: 0)
        pveEmit(id, 'MemoryUsedBytes', node.mem ?: 0)
        pveEmit(id, 'MemoryCapacityBytes', node.maxmem ?: 0)
        pveEmit(id, 'MemoryUsagePercent', pvePercent(node.mem, node.maxmem))
        pveEmit(id, 'UpTimeSeconds', node.uptime ?: 0)

        /*
         * "level" is the subscription level, empty on an unsubscribed node. Reported as a
         * boolean because the level itself is a string and not a metric, and shipped
         * without a threshold because an unsubscribed node is a licensing fact, not a
         * fault -- a homelab would alert forever.
         */
        pveEmit(id, 'Subscribed', node.level?.toString() ? 1 : 0)
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox node collection failed: ' + exception.message)
    return 2
}
