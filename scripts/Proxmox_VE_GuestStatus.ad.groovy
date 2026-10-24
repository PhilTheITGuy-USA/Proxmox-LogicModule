/*
 * Active Discovery: QEMU virtual machines and LXC containers, cluster-wide, for
 * Proxmox_VE_GuestStatus alone. Same instances and wildvalues as Proxmox_VE_Guests.ad,
 * which GuestPerformance uses, so a guest's status and performance instances match.
 *
 * What it adds: each guest's instance carries the key
 * Proxmox_VE_Topology draws that guest under, so the map can resolve every guest to an
 * instance -- including guests with no LogicMonitor resource of their own. The key is the
 * MAC of the first virtual NIC, or for a guest with no NIC the same pveTopoKey fallback
 * the TopologySource emits. Only this module carries it, as only VMware's status module
 * does, so each guest's key sits on one instance.
 *
 * The map is the optional extra; the instance is not. Every failure on the way to a key
 * -- gating calls, a config read, a guest on an offline node -- leaves the guest
 * discovered without one. After a node's first failed config read, that node's later
 * guests are not read at all this run. Only /cluster/resources?type=vm failing exits 2.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def guests = pveGuestList()

    /*
     * The keys are written plain, not through lm.topo's eriPreProcessor, so this script
     * stays compilable and testable here. With no namespace or blacklist the two are the
     * same; with either set the TopologySource's keys change and these would silently stop
     * matching, so the feature steps aside and says why.
     */
    def wanted = true
    def blocking = ['topo.namespace', 'topo.blacklist'].findAll { pveHostProp(it) }
    if (blocking) {
        System.err.println('Guest instance ERIs skipped: ' + blocking.join(' and ') +
            ' is set, which rewrites the topology keys these would have to match.')
        wanted = false
    }

    def clusterName = null
    def onlineNodes = [] as Set
    if (wanted) {
        try {
            clusterName = pveTopoClusterName(pveGet('/cluster/status') ?: [])
            (pveGet('/cluster/resources?type=node') ?: []).each { node ->
                if (node.status?.toString() == 'online' && node.node) {
                    onlineNodes << node.node.toString()
                }
            }
        } catch (Exception gateException) {
            System.err.println('Guest instance ERIs skipped: ' + gateException.message)
            wanted = false
        }
    }

    /*
     * Per-node circuit breaker. A node that fails one config read is treated as sick for the
     * rest of this run: its later guests are discovered without keys instead of each
     * spending a pve.api.timeout, and stderr gets one line per node rather than per guest.
     * The trade-off is accepted: a guest after the failure loses its key until the next run.
     */
    def failedNodes = [] as Set

    guests.each { guest ->
        def raw = [:]
        def nodeName = guest.node?.toString()
        // An offline node cannot answer for its guests; asking would spend a timeout each.
        if (wanted && guest.vmid && onlineNodes.contains(nodeName) && !failedNodes.contains(nodeName)) {
            /*
             * Per-guest try. A config that cannot be read is transient, so the guest gets no
             * key this run rather than the NIC-less fallback, which would change its identity
             * for one run -- the same choice the TopologySource makes.
             */
            def config = null
            try {
                config = pveGet('/nodes/' + nodeName + '/' + guest.type + '/' + guest.vmid + '/config')
            } catch (Exception guestException) {
                failedNodes << nodeName
                System.err.println('Config reads stopped for node ' + nodeName + ' after ' +
                    guest.type + '/' + guest.vmid + ' failed (' + guestException.message +
                    '); its remaining guests are discovered without keys this run.')
            }
            if (config instanceof Map) {
                raw['predef.externalResourceID'] = pveGuestMac(config) ?:
                    pveTopoKey(clusterName, guest.type + '-' + guest.vmid)
                raw['predef.externalResourceType'] = 'VirtualMachine'
            }
        }
        pveGuestDiscover(guest, raw)
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox guest discovery failed: ' + exception.message)
    return 2
}
