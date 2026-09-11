/*
 * Script collection (single instance): guests that no backup job covers.
 *
 * /cluster/backup-info/not-backed-up answers, in one call for the whole cluster, the
 * question every other hypervisor suite makes you assemble by hand: which guests would be
 * unrecoverable. It needs Sys.Audit on / and filters per-guest by VM.Audit, so an
 * under-privileged token under-reports rather than erroring -- the same trap as
 * /cluster/resources.
 *
 * The endpoint is not present on every Proxmox version, so its absence is reported as
 * BackupInfoAvailable=0 rather than as a collection failure. A zero count would be a
 * confident claim that everything is backed up, which is the worst possible wrong answer
 * for this particular metric.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def uncovered = null
    try {
        uncovered = pveGet('/cluster/backup-info/not-backed-up')
    } catch (Exception unavailable) {
        System.err.println('Backup coverage unavailable on this Proxmox version: ' +
            unavailable.message)
    }

    if (uncovered == null) {
        pveEmit(null, 'BackupInfoAvailable', 0)
        return 0
    }

    pveEmit(null, 'BackupInfoAvailable', 1)
    pveEmit(null, 'GuestsNotBackedUp', uncovered.size())
    pveEmit(null, 'VMsNotBackedUp', uncovered.count { it.type?.toString() == 'qemu' })
    pveEmit(null, 'ContainersNotBackedUp', uncovered.count { it.type?.toString() == 'lxc' })

    // Named on stderr so the portal's task log says which guests, not just how many.
    if (uncovered) {
        System.err.println('Guests covered by no backup job: ' +
            uncovered.collect { (it.name ?: it.type) + ' (' + it.vmid + ')' }.join(', '))
    }

    return 0
} catch (Exception exception) {
    System.err.println('Proxmox backup coverage collection failed: ' + exception.message)
    return 2
}
