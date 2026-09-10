/*
 * Active Discovery: QEMU virtual machines and LXC containers, cluster-wide.
 * Shared by Proxmox_VE_GuestPerformance and Proxmox_VE_GuestStatus.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def resources = pveGet('/cluster/resources?type=vm') ?: []

    // Templates are never running. Discovering them creates instances that alert as
    // permanently down, so they are excluded rather than filtered at alert time.
    def guests = resources.findAll { it.type in ['qemu', 'lxc'] && !it.template }

    if (resources.isEmpty()) {
        System.err.println('/cluster/resources returned no guests. This endpoint is filtered by ' +
            'token ACL rather than erroring, so an under-privileged token looks identical to an ' +
            'empty cluster. Verify the token has VM.Audit on /vms.')
    }

    guests.each { guest ->
        def vmid = guest.vmid?.toString()
        def kind = guest.type == 'qemu' ? 'virtual machine' : 'container'
        pveDiscover(
            guest.id,
            guest.name ?: (guest.type + '-' + vmid),
            'Proxmox ' + kind + ' ' + vmid,
            [
                pve_id  : guest.id,
                pve_node: guest.node,
                pve_type: guest.type,
                pve_vmid: vmid,
                pve_pool: guest.pool,
                pve_tags: guest.tags
            ]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox guest discovery failed: ' + exception.message)
    return 2
}
