/*
 * BatchScript collection: storage capacity and availability, cluster-wide.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def stores = pveGet('/cluster/resources?type=storage') ?: []

    stores.each { store ->
        def id = store.id
        def used = store.disk ?: 0
        def total = store.maxdisk ?: 0

        // "available" is the status Proxmox reports for a storage it can actually reach.
        pveEmit(id, 'Status', store.status?.toString() == 'available' ? 1 : 0)
        pveEmit(id, 'UsedBytes', used)
        pveEmit(id, 'Capacity', total)
        pveEmit(id, 'FreeSpace', total > used ? total - used : 0)
        pveEmit(id, 'UsedPercent', pvePercent(used, total))
        pveEmit(id, 'Shared', store.shared ? 1 : 0)
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox storage collection failed: ' + exception.message)
    return 2
}
