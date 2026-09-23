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

if (pveConfigError) {
    return 0
}

modLoader = GSH.getInstance(GroovySystem.version)
    .getScript('Snippets', Snippets.getLoader()).withBinding(getBinding())
lmtopo = modLoader.load('lm.topo', '0')

def keyNamespace = pveHostProp('topo.namespace') ? pveHostProp(pveHostProp('topo.namespace')) : ''
def keyBlacklist = (pveHostProp('topo.blacklist') ?: '').tokenize(',')

// The ERT chooses the icon on a rendered map. PhysicalServer is what a Proxmox node's
// resource already reports, and what LogicMonitor's own addERI_VMware_VeloCloud uses.
def ert = 'PhysicalServer'

try {
    def status = pveGet('/cluster/status') ?: []

    /*
     * The node this resource is, not some node in the cluster: /cluster/status marks the
     * responding node with local=true. Without that, every node in a cluster would be
     * stamped with the same key and every vertex would collide.
     */
    def localRow = status.find { it.local }
    def nodeName = localRow?.name?.toString()
    if (!nodeName) {
        return 0
    }

    def clusterRow = status.find { it.type?.toString() == 'cluster' }
    def clusterName = clusterRow?.name?.toString() ?: nodeName

    /*
     * Its own category, so this combines with the MAC-based ERIs the resource already has
     * rather than replacing them: LogicMonitor keeps the highest priority within a
     * category and merges across categories. Stamping into net.l2 would fight the MACs.
     */
    def keys = []
    keys << pveTopoKey(clusterName, nodeName)

    def eriArray = new JSONArray()
    lmtopo.emitEri('proxmoxve', 1, keys, ert, eriArray)
    lmtopo.printEriArray(eriArray, keyNamespace, keyBlacklist)
    return 0
} catch (Exception exception) {
    // Silent on purpose. See the header.
    return 0
}
