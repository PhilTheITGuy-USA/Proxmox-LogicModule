/*
 * Active Discovery: Ceph OSDs.
 *
 * /nodes/{node}/ceph/osd returns the whole CRUSH tree, not that node's OSDs -- every node
 * answers with the same cluster-wide map. So one call discovers every OSD in the cluster,
 * and this is O(1) rather than O(nodes). Online nodes are tried in turn only until one
 * answers, so a node being down costs nothing.
 *
 * The response shape is {flags, root}, where root is a recursive bucket tree: datacenter
 * and host buckets hold "children" lists, and OSDs are the leaves. Proxmox's own source
 * carries a "fixme: return a list instead of extjs tree format?" against it, so the tree
 * is walked rather than assumed to be any particular depth.
 *
 * The wildvalue is the OSD name ("osd.12"), which is cluster-unique, deliberately without
 * the host. An OSD's host is a CRUSH placement that can change, and an id built from it
 * would be destroyed by a rebalance -- the same reasoning as guest instance ids.
 *
 * UNVERIFIED against a real Ceph cluster.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

/*
 * Walk the CRUSH tree depth-first, collecting the OSD leaves. Written iteratively: the
 * tree is shallow in practice, but a closure cannot recurse into itself by name in Groovy
 * without being declared first, and a stack is clearer than the workaround.
 */
def collectOSDs = { root ->
    def found = []
    def stack = [root]
    while (stack) {
        def entry = stack.remove(stack.size() - 1)
        if (!(entry instanceof Map)) { continue }
        if (entry.type?.toString() == 'osd') {
            found << entry
        }
        if (entry.children instanceof List) {
            entry.children.each { stack << it }
        }
    }
    return found
}

try {
    def nodes = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' }

    def tree = null
    for (node in nodes) {
        def nodeName = node.node?.toString()
        if (!nodeName) { continue }
        try {
            tree = pveGet('/nodes/' + nodeName + '/ceph/osd')
            if (tree) { break }
        } catch (Exception nodeException) {
            // Expected on a cluster without Ceph, and on any single node that is busy.
            System.err.println('Ceph OSD tree unavailable from ' + nodeName + ': ' +
                nodeException.message)
        }
    }

    if (!tree || !(tree.root instanceof Map)) {
        System.err.println('No Ceph OSD tree available from any online node. This is expected ' +
            'on a cluster that does not run Ceph.')
        return 0
    }

    collectOSDs(tree.root).each { osd ->
        def name = osd.name?.toString()
        if (!name) { return }

        pveDiscover(
            name,
            name,
            'Ceph OSD ' + name + (osd.host ? ' on ' + osd.host : ''),
            [
                pve_osd         : name,
                pve_osd_id      : osd.id,
                pve_host        : osd.host,
                pve_device_class: osd.device_class,
                pve_osd_type    : osd.osdtype
            ]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox Ceph OSD discovery failed: ' + exception.message)
    return 2
}
