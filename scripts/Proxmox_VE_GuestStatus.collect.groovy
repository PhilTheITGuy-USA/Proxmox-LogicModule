/*
 * BatchScript collection: guest power, lock and HA state.
 *
 * Split from performance because it wants a different alert posture: status flaps are
 * interesting immediately, whereas a CPU spike usually needs a trigger interval.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

// HA states that mean the cluster is actively unhappy about this guest, as opposed to
// merely having it stopped on purpose.
def haErrorStates = ['error', 'fence']

try {
    def guests = (pveGet('/cluster/resources?type=vm') ?: [])
        .findAll { it.type in ['qemu', 'lxc'] && !it.template }

    guests.each { guest ->
        def id = guest.id
        def status = guest.status?.toString() ?: ''
        def haState = guest.hastate?.toString() ?: ''

        pveEmit(id, 'Status', status == 'running' ? 1 : 0)
        pveEmit(id, 'UpTimeSeconds', guest.uptime ?: 0)
        pveEmit(id, 'Locked', guest.lock ? 1 : 0)
        pveEmit(id, 'HAManaged', haState ? 1 : 0)
        pveEmit(id, 'HAError', haErrorStates.contains(haState) ? 1 : 0)
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox guest status collection failed: ' + exception.message)
    return 2
}
