/*
 * BatchScript collection: guest performance, cluster-wide, in one API call.
 *
 * netin/netout/diskread/diskwrite are cumulative counters since guest start. They are
 * emitted raw and declared as "derive" datapoints so LogicMonitor computes the rate and
 * discards the negative delta produced when a guest restarts and its counters reset.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def guests = (pveGet('/cluster/resources?type=vm') ?: [])
        .findAll { it.type in ['qemu', 'lxc'] && !it.template }

    guests.each { guest ->
        def id = guest.id

        pveEmit(id, 'CPUUsagePercent', pveRound(((guest.cpu ?: 0) as double) * 100.0d))
        pveEmit(id, 'CPUCount', guest.maxcpu ?: 0)

        pveEmit(id, 'MemoryUsedBytes', guest.mem ?: 0)
        pveEmit(id, 'MemoryCapacityBytes', guest.maxmem ?: 0)
        pveEmit(id, 'MemoryUsagePercent', pvePercent(guest.mem, guest.maxmem))

        pveEmit(id, 'DiskCapacityBytes', guest.maxdisk ?: 0)

        /*
         * memhost is the host's view of the guest's memory footprint; mem is the guest's
         * own. The gap between them is the balloon doing its job, which is as close to
         * ballooning visibility as /cluster/resources gets. Older Proxmox omits the field,
         * so it is withheld rather than reported as a zero-sized gap.
         */
        if (guest.memhost != null) {
            pveEmit(id, 'MemoryHostBytes', guest.memhost)
        }

        pveEmit(id, 'DataRateRx', guest.netin ?: 0)
        pveEmit(id, 'DataRateTx', guest.netout ?: 0)
        pveEmit(id, 'DiskReadRate', guest.diskread ?: 0)
        pveEmit(id, 'DiskWriteRate', guest.diskwrite ?: 0)

        /*
         * Only LXC reports used disk. QEMU exposes maxdisk but never disk -- real usage
         * inside a VM needs the guest agent, exactly as VMware needs VMware Tools. These
         * datapoints are deliberately not emitted for QEMU so they read as "no data"
         * rather than a confident and wrong zero.
         */
        if (guest.type == 'lxc') {
            pveEmit(id, 'DiskUsedBytes', guest.disk ?: 0)
            pveEmit(id, 'DiskUsagePercent', pvePercent(guest.disk, guest.maxdisk))
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox guest performance collection failed: ' + exception.message)
    return 2
}
