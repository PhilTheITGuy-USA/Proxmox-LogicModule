/*
 * ERI PropertySource: give a Proxmox node's own resource an ERI key that
 * Proxmox_VE_Topology can construct independently, so a node vertex on the map resolves to
 * the real resource rather than floating unattached.
 *
 * Why this module has to exist. LogicMonitor matches topology vertices on ERI keys, and a
 * monitored Linux host already has MAC-based ones -- which is why a guest needs no help:
 * Proxmox reports the guest's virtual NIC MAC, LogicMonitor already keyed the guest's
 * resource on that same MAC, and the two meet. A node has no such shared key. Proxmox
 * exposes no hardware UUID and no MAC for a node anywhere in its API: /cluster/status gives
 * name, ip and nodeid, /nodes/{node}/status gives none at all, and /nodes/{node}/network
 * has no hwaddr field. VMware does not have this problem because vCenter hands out each
 * ESXi host's hardware UUID, which addERI_Vcenter puts on the host's resource.
 *
 * So the key is synthesised on both sides, and pveTopoKey in the preamble is its single
 * definition -- this script and Proxmox_VE_Topology both call it, which is what keeps them
 * from drifting apart. They must produce byte-identical keys or every node vertex silently
 * stops matching its resource.
 *
 * Two keys, and where their parts come from. The map needs a key on EVERY node's
 * resource, but only one resource per cluster normally carries the API token: give every
 * node the token and every DataSource collects the whole cluster once per node. So:
 *
 *   pveTopoKey(cluster, node)  -- with a token, both names from /cluster/status; without
 *                                 one, pve.topology.cluster plus pveTopoNodeName. Without
 *                                 that property this key is simply not stamped.
 *   pveTopoNodeKey(node, ip)   -- one per address in system.ips, plus the address
 *                                 /cluster/status reports on the token path. It needs no
 *                                 cluster name, which is what lets one group, with no
 *                                 per-cluster property, hold the nodes of every cluster.
 *
 * The node name is /cluster/status's local row with a token, and pveTopoNodeName (the
 * pve.topology.node property, else the short hostname) without. The API path falls back to
 * the property path when the API cannot answer, so a node that is briefly unreachable keeps
 * its keys rather than losing them until the next run.
 *
 * LIKE THE TOPOLOGYSOURCE, THIS CANNOT BE COMPILED OR RUN OUTSIDE A COLLECTOR. The ERI
 * output format is not hand-written JSON: LogicMonitor's own addERI_* modules build it
 * through lm.topo's emitEri and printEriArray, which also apply the topo.namespace and
 * topo.blacklist host properties. Writing the JSON by hand would mean inventing a format,
 * so build.py emits this outside dist/scripts/ where the compile job cannot reach it, and
 * the harness asserts pveTopoKey instead.
 *
 * Silence on any failure, exactly like addCategory_Proxmox_VE: this runs against hosts that
 * may not be Proxmox at all, and a PropertySource that prints on a host it does not
 * understand corrupts that resource's properties.
 */
import org.json.JSONArray
import com.santaba.agent.groovy.utils.GroovyScriptHelper as GSH
import com.logicmonitor.mod.Snippets

modLoader = GSH.getInstance(GroovySystem.version)
    .getScript('Snippets', Snippets.getLoader()).withBinding(getBinding())
lmtopo = modLoader.load('lm.topo', '0')

def keyNamespace = pveHostProp('topo.namespace') ? pveHostProp(pveHostProp('topo.namespace')) : ''
def keyBlacklist = (pveHostProp('topo.blacklist') ?: '').tokenize(',')

// The ERT chooses the icon on a rendered map. PhysicalServer is what a Proxmox node's
// resource already reports, and what LogicMonitor's own addERI_VMware_VeloCloud uses.
def ert = 'PhysicalServer'

try {
    def identity = null
    def localIp = null
    if (!pveConfigError) {
        try {
            def status = pveGet('/cluster/status') ?: []

            /*
             * The node this resource is, not some node in the cluster: /cluster/status
             * marks the responding node with local=true. Without that, every node in a
             * cluster would be stamped with the same key and every vertex would collide.
             */
            def localRow = status.find { it.local }
            def nodeName = localRow?.name?.toString()
            if (nodeName) {
                def clusterRow = status.find { it.type?.toString() == 'cluster' }
                identity = [cluster: clusterRow?.name?.toString() ?: nodeName, node: nodeName]
                localIp = localRow.ip?.toString()
            }
        } catch (Exception apiException) {
            // Fall through to the properties. Still silent: see the header.
        }
    }
    identity = identity ?: pveTopoNodeIdentity()
    def name = identity?.node ?: pveTopoNodeName()
    if (!name) {
        return 0
    }

    /*
     * Its own category, so this combines with the MAC-based ERIs the resource already has
     * rather than replacing them: keys from every category merge into the ID, and the
     * resource type comes from the highest-priority entry across all categories. This emits
     * PhysicalServer, the type a node already has, so priority 1 is
     * harmless. Stamping into net.l2 would fight the MACs.
     */
    def keys = []
    if (identity) {
        keys << pveTopoKey(identity.cluster, identity.node)
    }
    keys += pveTopoNodeKeys(name, (pveHostProp('system.ips') ?: '').tokenize(',') + [localIp])
    keys = keys.unique()
    if (!keys) {
        return 0
    }

    def eriArray = new JSONArray()
    lmtopo.emitEri('proxmoxve', 1, keys, ert, eriArray)
    lmtopo.printEriArray(eriArray, keyNamespace, keyBlacklist)
    return 0
} catch (Exception exception) {
    // Silent on purpose. See the header.
    return 0
}
