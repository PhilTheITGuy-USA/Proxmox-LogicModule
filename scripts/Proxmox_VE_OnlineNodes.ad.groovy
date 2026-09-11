/*
 * Active Discovery: online nodes.
 *
 * Distinct from Proxmox_VE_Nodes.ad.groovy, which discovers every node including offline
 * ones so their down state can be alerted on. This body is for modules that reach into a
 * node's own API: an offline node cannot answer, and an instance that can never collect is
 * worse than no instance. Instances already discovered survive a node going offline,
 * because deleteInactiveInstances is false.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodes = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' }

    if (!nodes) {
        System.err.println('No online nodes returned by /cluster/resources. This endpoint is ' +
            'filtered by token ACL rather than erroring, so an under-privileged token looks ' +
            'identical to a cluster with every node down. Verify the token has Sys.Audit.')
    }

    nodes.each { node ->
        def nodeName = node.node?.toString()
        if (!nodeName) { return }

        pveDiscover(
            node.id,
            nodeName,
            'Proxmox node ' + nodeName,
            [
                pve_id  : node.id,
                pve_node: nodeName
            ]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox online node discovery failed: ' + exception.message)
    return 2
}
