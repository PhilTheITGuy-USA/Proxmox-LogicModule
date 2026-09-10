/*
 * Active Discovery: storage, per node.
 *
 * A shared storage (Ceph, NFS, iSCSI) is visible from every node and is therefore
 * discovered once per node. That is intentional: availability and free space are
 * reported from the perspective of the node doing the asking, and a shared store can
 * be reachable from one node and not another.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def stores = pveGet('/cluster/resources?type=storage') ?: []

    if (stores.isEmpty()) {
        System.err.println('/cluster/resources returned no storage. Verify the API token has ' +
            'Datastore.Audit on /storage.')
    }

    stores.each { store ->
        def scope = store.shared ? 'shared' : 'local'
        pveDiscover(
            store.id,
            store.storage + ' on ' + store.node,
            'Proxmox ' + scope + ' storage "' + store.storage + '" (' + (store.plugintype ?: 'unknown') + ')',
            [
                pve_id     : store.id,
                pve_node   : store.node,
                pve_storage: store.storage,
                pve_type   : store.plugintype,
                pve_shared : store.shared ? 'true' : 'false'
            ]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox storage discovery failed: ' + exception.message)
    return 2
}
