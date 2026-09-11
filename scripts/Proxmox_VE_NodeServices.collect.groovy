/*
 * BatchScript collection: systemd state of the Proxmox services, per node.
 *
 * Three separate systemd notions are reported, because collapsing them loses the
 * distinction that matters when something is wrong:
 *
 *   active-state  what the process is doing now   (active / inactive / failed / ...)
 *   unit-state    what systemd is configured to do (enabled / disabled / not-found)
 *   state         the substate                     (running / dead / exited / ...)
 *
 * A service that is "inactive" because it is deliberately disabled is not the same
 * problem as one that is "failed", and neither is the same as one that is not installed.
 * Alerting on Running alone would page for all three.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodes = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' }

    nodes.each { node ->
        def nodeName = node.node?.toString()
        if (!nodeName) { return }

        // Per-node try: one unreachable node must not cost the others their data.
        try {
            def services = pveGet('/nodes/' + nodeName + '/services') ?: []

            services.each { service ->
                def name = service.name?.toString() ?: service.service?.toString()
                if (!name) { return }

                def id = nodeName + '/' + name
                def activeState = service.get('active-state')?.toString() ?: 'unknown'
                def unitState = service.get('unit-state')?.toString() ?: 'unknown'

                pveEmit(id, 'Running', activeState == 'active' ? 1 : 0)
                pveEmit(id, 'Failed', activeState == 'failed' ? 1 : 0)
                pveEmit(id, 'Enabled', unitState == 'enabled' ? 1 : 0)
                pveEmit(id, 'Installed', unitState == 'not-found' ? 0 : 1)
            }
        } catch (Exception nodeException) {
            System.err.println('Service collection skipped for node ' + nodeName + ': ' +
                nodeException.message)
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox service collection failed: ' + exception.message)
    return 2
}
