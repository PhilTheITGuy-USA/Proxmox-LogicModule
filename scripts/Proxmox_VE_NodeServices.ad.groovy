/*
 * Active Discovery: Proxmox systemd services, one instance per service per node.
 *
 * /nodes/{node}/services returns the services Proxmox itself manages -- pveproxy,
 * pvedaemon, pve-cluster, corosync and friends -- with their systemd state. Needs
 * Sys.Audit on /nodes/{node}.
 *
 * Only online nodes are discovered: an offline node cannot answer, and an instance that
 * can never collect is worse than no instance. Already-discovered services survive,
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
            'filtered by token ACL rather than erroring, so verify the token has Sys.Audit.')
    }

    nodes.each { node ->
        def nodeName = node.node?.toString()
        if (!nodeName) { return }

        // One node failing must not lose the other nodes' services.
        try {
            def services = pveGet('/nodes/' + nodeName + '/services') ?: []

            services.each { service ->
                def name = service.name?.toString() ?: service.service?.toString()
                if (!name) { return }

                pveDiscover(
                    nodeName + '/' + name,
                    nodeName + ' ' + name,
                    (service.desc?.toString() ?: name) + ' on ' + nodeName,
                    [
                        pve_node    : nodeName,
                        pve_service : name,
                        pve_unit    : service.service,
                        pve_desc    : service.desc
                    ]
                )
            }
        } catch (Exception nodeException) {
            System.err.println('Service discovery skipped for node ' + nodeName + ': ' +
                nodeException.message)
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox service discovery failed: ' + exception.message)
    return 2
}
