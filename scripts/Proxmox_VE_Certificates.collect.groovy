/*
 * BatchScript collection: TLS certificate expiry, every certificate on every online node.
 *
 * One call per node rather than one per certificate: nodes are few and bounded by cluster
 * size, certificates are three per node. The instance ids match what the shared discovery
 * body emits, "<node>/<certificate>" folded to the wildvalue character set.
 *
 * notafter is a UNIX epoch. Days remaining is computed against the Collector's clock, not
 * Proxmox's -- a Collector with a badly wrong clock will report badly wrong expiry, which
 * is preferable to trusting a timestamp the API does not provide.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

def SECONDS_PER_DAY = 86400.0d

try {
    def nodes = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' }

    def now = System.currentTimeMillis() / 1000L

    nodes.each { node ->
        def nodeName = node.node?.toString()
        if (!nodeName) { return }

        // Per-node try: one unreachable node must not cost the others their data.
        try {
            def certificates = pveGet('/nodes/' + nodeName + '/certificates/info') ?: []

            certificates.each { certificate ->
                def filename = certificate.filename?.toString()
                if (!filename) { return }

                def id = nodeName + '/' + filename.replaceAll('\\.pem$', '')
                def notAfter = certificate.notafter

                if (notAfter == null) {
                    // A certificate with no expiry timestamp is not a zero-day certificate.
                    System.err.println('No notafter on ' + filename + ' for ' + nodeName)
                    return
                }

                def daysRemaining = ((notAfter as double) - now) / SECONDS_PER_DAY

                pveEmit(id, 'DaysUntilExpiry', pveRound(daysRemaining))
                pveEmit(id, 'Expired', daysRemaining <= 0 ? 1 : 0)
                pveEmit(id, 'PublicKeyBits', certificate['public-key-bits'] ?: 0)
            }
        } catch (Exception nodeException) {
            System.err.println('Certificate collection skipped for node ' + nodeName + ': ' +
                nodeException.message)
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox certificate collection failed: ' + exception.message)
    return 2
}
