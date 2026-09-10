/*
 * Active Discovery: Proxmox VE cluster nodes.
 * Shared by Proxmox_VE_Nodes and Proxmox_VE_NodeDetail.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodes = pveGet('/cluster/resources?type=node') ?: []

    if (nodes.isEmpty()) {
        System.err.println('/cluster/resources returned no nodes. Verify the API token has an ACL ' +
            'granting at least PVEAuditor.')
    }

    nodes.each { node ->
        pveDiscover(
            node.id,
            node.node,
            'Proxmox VE node ' + node.node,
            [pve_id: node.id, pve_node: node.node]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox node discovery failed: ' + exception.message)
    return 2
}
