/*
 * Active Discovery: physical disks, one instance per disk per node.
 *
 * /nodes/{node}/disks/list needs Sys.Audit on / or on /nodes/{node}. Partitions are not
 * requested (include-partitions defaults to 0): a partition's SMART health is the health
 * of the disk beneath it, so discovering both would duplicate every instance.
 *
 * The wildvalue drops the /dev/ prefix -- "pve1/sda" rather than "pve1//dev/sda" -- which
 * keeps it readable once the character fold has run.
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

        // One node failing must not lose the other nodes' disks.
        try {
            def disks = pveGet('/nodes/' + nodeName + '/disks/list') ?: []

            disks.each { disk ->
                def devpath = disk.devpath?.toString()
                if (!devpath) { return }

                def shortPath = devpath.replaceAll('^/dev/', '')
                def model = disk.model?.toString()?.trim()

                pveDiscover(
                    nodeName + '/' + shortPath,
                    nodeName + ' ' + shortPath,
                    ((model ?: 'Disk') + ' on ' + nodeName + ' at ' + devpath),
                    [
                        pve_node   : nodeName,
                        pve_devpath: devpath,
                        pve_model  : model,
                        pve_serial : disk.serial,
                        pve_vendor : disk.vendor,
                        pve_type   : disk.type
                    ]
                )
            }
        } catch (Exception nodeException) {
            System.err.println('Disk discovery skipped for node ' + nodeName + ': ' +
                nodeException.message)
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox disk discovery failed: ' + exception.message)
    return 2
}
