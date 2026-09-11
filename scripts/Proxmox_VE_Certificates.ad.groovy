/*
 * Active Discovery: TLS certificates, one instance per certificate per node.
 *
 * Proxmox reports up to three per node -- the cluster CA, the internal pve-ssl cert, and
 * the pveproxy cert a browser or API client actually sees. They expire independently and
 * on different schedules, so they are discovered separately rather than collapsed into a
 * single soonest-expiry figure per node: knowing *which* certificate is about to expire is
 * most of the value.
 *
 * Only online nodes are discovered. An offline node cannot answer, and discovering
 * instances that can never collect would fill the portal with permanent no-data. Instances
 * already discovered survive, because deleteInactiveInstances is false.
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

        /*
         * One node failing must not lose the other nodes' certificates, so each is scoped
         * to its own try. A node that went offline between the resource listing and this
         * call is the common case.
         */
        try {
            def certificates = pveGet('/nodes/' + nodeName + '/certificates/info') ?: []

            certificates.each { certificate ->
                def filename = certificate.filename?.toString()
                if (!filename) { return }

                // "pveproxy-ssl.pem" -> "pveproxy-ssl", which reads better as an alias and
                // keeps the wildvalue stable if Proxmox ever changes the extension.
                def shortName = filename.replaceAll('\\.pem$', '')

                pveDiscover(
                    nodeName + '/' + shortName,
                    nodeName + ' ' + shortName,
                    'TLS certificate ' + filename + ' on ' + nodeName,
                    [
                        pve_node     : nodeName,
                        pve_cert_file: filename,
                        pve_subject  : certificate.subject,
                        pve_issuer   : certificate.issuer
                    ]
                )
            }
        } catch (Exception nodeException) {
            System.err.println('Certificate discovery skipped for node ' + nodeName + ': ' +
                nodeException.message)
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox certificate discovery failed: ' + exception.message)
    return 2
}
