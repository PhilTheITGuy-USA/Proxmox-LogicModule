/*
 * PropertySource: identify Proxmox VE hosts and tag them with a system category.
 *
 * Every module in the suite uses AppliesTo hasCategory("ProxmoxVE"), so this is what
 * makes the suite self-applying. Without it a user has to hand-set a property on every
 * resource, which is not how a published module behaves.
 *
 * Emits nothing at all when the host is not Proxmox, or when credentials are absent --
 * a PropertySource that guesses would apply the whole suite to unrelated Linux hosts.
 */
if (pveConfigError) {
    // Not an error: most resources in a portal are not Proxmox and have no token set.
    return 0
}

try {
    // /version is the cheapest authenticated endpoint that proves this is really Proxmox VE.
    def version = pveGet('/version')
    if (!version || !version.version) {
        return 0
    }

    println 'system.categories=ProxmoxVE'
    println 'pve.version=' + version.version
    if (version.release) { println 'pve.release=' + version.release }

    // Presence of a cluster entry distinguishes a cluster member from a standalone host,
    // which lets AppliesTo target cluster-only modules without a second probe.
    try {
        def clustered = (pveGet('/cluster/status') ?: []).any { it.type == 'cluster' }
        println 'pve.clustered=' + clustered
    } catch (Exception clusterException) {
        System.err.println('Could not determine cluster membership: ' + clusterException.message)
    }

    return 0
} catch (Exception exception) {
    System.err.println('Proxmox detection failed: ' + exception.message)
    return 0
}
