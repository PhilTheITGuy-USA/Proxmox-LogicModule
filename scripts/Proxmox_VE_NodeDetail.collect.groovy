/*
 * Script collection (per node instance): the node metrics that /cluster/resources omits.
 *
 * This is the one module in the Tier 1 set that cannot be a BatchScript, because
 * loadavg / swap / rootfs are only exposed per node at /nodes/{node}/status. Node counts
 * are small and bounded by cluster size, so one call per node per interval is acceptable
 * in a way that one call per guest would not be.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def node = pveInstanceProp('pve_node')
    if (!node) {
        throw new IOException('Instance property pve_node is missing. Re-run Active Discovery.')
    }

    def status = pveGet('/nodes/' + node + '/status') ?: [:]
    def memory = status.memory ?: [:]
    def swap = status.swap ?: [:]
    def rootfs = status.rootfs ?: [:]
    def cpuInfo = status.cpuinfo ?: [:]

    /*
     * loadavg entries are typed as strings in the Proxmox schema, not numbers. They are
     * coerced explicitly here; arithmetic on them without coercion silently concatenates.
     */
    def load = (status.loadavg ?: []).collect { it == null ? 0.0d : it.toString().toDouble() }
    pveEmit(null, 'LoadAverage1m', load.size() > 0 ? load[0] : 0)
    pveEmit(null, 'LoadAverage5m', load.size() > 1 ? load[1] : 0)
    pveEmit(null, 'LoadAverage15m', load.size() > 2 ? load[2] : 0)

    def cpuCount = cpuInfo.cpus ?: 0
    pveEmit(null, 'CPUCount', cpuCount)
    pveEmit(null, 'CPUSockets', cpuInfo.sockets ?: 0)
    pveEmit(null, 'CPUCores', cpuInfo.cores ?: 0)
    // Load per core is the figure that means the same thing across differently sized nodes.
    pveEmit(null, 'LoadPerCore', cpuCount ? pveRound((load ? load[0] : 0.0d) / (cpuCount as double)) : 0)

    pveEmit(null, 'MemoryUsedBytes', memory.used ?: 0)
    pveEmit(null, 'MemoryCapacityBytes', memory.total ?: 0)
    pveEmit(null, 'MemoryUsagePercent', pvePercent(memory.used, memory.total))

    pveEmit(null, 'SwapUsedBytes', swap.used ?: 0)
    pveEmit(null, 'SwapCapacityBytes', swap.total ?: 0)
    pveEmit(null, 'SwapUsagePercent', pvePercent(swap.used, swap.total))

    pveEmit(null, 'RootFSUsedBytes', rootfs.used ?: 0)
    pveEmit(null, 'RootFSCapacity', rootfs.total ?: 0)
    pveEmit(null, 'RootFSFreeSpace', rootfs.avail ?: 0)
    pveEmit(null, 'RootFSUsedPercent', pvePercent(rootfs.used, rootfs.total))

    // "wait" is the node's IO wait, reported on a 0..1 scale exactly like cpu. Sustained
    // IO wait is the clearest signal that storage, not CPU, is the constraint.
    pveEmit(null, 'IOWaitPercent', pveRound(((status.wait ?: 0) as double) * 100.0d))

    /*
     * KSM reclaims memory by merging identical pages across guests. A node with KSM
     * disabled reports no ksm block at all, and a zero there would read as "KSM is running
     * and saving nothing" rather than "KSM is off", so it is withheld.
     */
    def ksm = status.ksm ?: [:]
    if (ksm.shared != null) {
        pveEmit(null, 'KSMSharedBytes', ksm.shared)
    }

    pveEmit(null, 'UpTimeSeconds', status.uptime ?: 0)

    return 0
} catch (Exception exception) {
    System.err.println('Proxmox node detail collection failed: ' + exception.message)
    return 2
}
