//!/lib-groovy/v4
/*
 * Shared preamble for the Proxmox VE LogicModule suite.
 *
 * This file is NOT a standalone script. build/build.py prepends it to every module
 * body, so the assembled script that ships inside the module JSON is self-contained.
 * Edit helpers here once rather than in each body.
 *
 * Resource properties consumed:
 *   pve.api.url       Base URL, e.g. https://pve.example.com:8006
 *                     Defaults to https://<system.hostname>:<pve.api.port> when unset.
 *   pve.api.port      Default 8006. Only used to build the fallback URL.
 *   pve.api.token.credential     Full token string: user@realm!tokenid=secret
 *   pve.api.timeout   Connect and read timeout in ms. Default 10000.
 *   pve.api.insecure  "true" to accept self-signed certificates. Lab use only.
 */
import groovy.json.JsonSlurper
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

// ---------------------------------------------------------------- properties

def pveHostProp = { String key, String fallback = null ->
    def value = hostProps.get(key)
    (value == null || value.toString().trim().isEmpty()) ? fallback : value.toString().trim()
}

/*
 * Instance properties are exposed as taskProps during collection and instanceProps
 * in some contexts, with or without the "auto." prefix that Active Discovery adds.
 * Neither binding exists during Active Discovery itself, hence the hasVariable guards.
 */
def pveInstanceProp = { String key ->
    def sources = []
    if (binding.hasVariable('taskProps') && taskProps != null) { sources << taskProps }
    if (binding.hasVariable('instanceProps') && instanceProps != null) { sources << instanceProps }
    for (source in sources) {
        for (candidate in [key, 'auto.' + key]) {
            def value = source.get(candidate)
            if (value != null && !value.toString().trim().isEmpty()) {
                return value.toString().trim()
            }
        }
    }
    return null
}

// ---------------------------------------------------------------- connection

def pveBaseUrl = pveHostProp('pve.api.url')
if (!pveBaseUrl) {
    def hostname = pveHostProp('system.hostname')
    if (hostname) {
        pveBaseUrl = 'https://' + hostname + ':' + pveHostProp('pve.api.port', '8006')
    }
}
def pveToken = pveHostProp('pve.api.token.credential')
def pveTimeout = pveHostProp('pve.api.timeout', '10000') as int

def pveConfigError = null
if (!pveBaseUrl) {
    pveConfigError = 'Cannot determine the Proxmox API URL: set pve.api.url, or ensure system.hostname resolves.'
} else if (!pveToken) {
    pveConfigError = 'Missing pve.api.token.credential. Expected the full token string: user@realm!tokenid=secret'
}

if (pveHostProp('pve.api.insecure', 'false').toBoolean()) {
    def trustAll = [
        getAcceptedIssuers: { -> new X509Certificate[0] },
        checkClientTrusted: { X509Certificate[] certs, String authType -> },
        checkServerTrusted: { X509Certificate[] certs, String authType -> }
    ] as X509TrustManager
    def sslContext = SSLContext.getInstance('TLS')
    sslContext.init(null, [trustAll] as TrustManager[], new java.security.SecureRandom())
    HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory())
    HttpsURLConnection.setDefaultHostnameVerifier({ String hostname, SSLSession session -> true } as HostnameVerifier)
}

/*
 * GET an /api2/json path and return the unwrapped "data" payload.
 *
 * 401/403 is called out separately because Proxmox returns it when the token exists
 * but lacks an ACL entry, which is the single most common misconfiguration and is
 * otherwise indistinguishable from a genuinely empty result.
 */
def pveGet = { String path ->
    def connection = new URL(pveBaseUrl.replaceAll('/+$', '') + '/api2/json' + path).openConnection()
    connection.setRequestMethod('GET')
    connection.setConnectTimeout(pveTimeout)
    connection.setReadTimeout(pveTimeout)
    connection.setRequestProperty('Authorization', 'PVEAPIToken=' + pveToken)
    connection.setRequestProperty('Accept', 'application/json')

    int status = connection.responseCode
    if (status == 401 || status == 403) {
        throw new IOException('HTTP ' + status + ' for ' + path +
            ' - the API token was rejected. Check the token secret, and that the token has a' +
            ' PVEAuditor ACL. A token with privilege separation enabled needs its own ACL entry,' +
            ' not just the one on its user.')
    }
    if (status < 200 || status >= 300) {
        throw new IOException('HTTP ' + status + ' for ' + path)
    }
    def payload = new JsonSlurper().parse(connection.inputStream)
    connection.disconnect()
    return payload?.data
}

// -------------------------------------------------------------------- output

/*
 * Instance IDs (wildvalues) are derived from Proxmox's own resource id -- "qemu/101",
 * "node/pve1", "storage/pve1/local". Those are cluster-unique and, for guests, survive
 * migration between nodes, so an HA failover does not orphan the instance.
 *
 * "/" and "." are folded to "-": "." separates instance from datapoint in BatchScript
 * output, and a storage name may legally contain one.
 */
def pveWildValue = { value ->
    (value == null ? '' : value.toString()).replaceAll('[^A-Za-z0-9_-]', '-')
}

def pveDisplayName = { value ->
    (value == null ? '' : value.toString()).replaceAll('#', ' ').trim()
}

// LogicMonitor's reference discovery scripts URL-encode property values rather than
// stripping characters, which keeps names, tags and pools readable and reversible.
def pvePropValue = { value ->
    URLEncoder.encode(value == null ? '' : value.toString(), 'UTF-8')
}

// instanceId##displayName##description####auto.key=value&auto.key2=value2
//
// rawProperties are emitted after the auto. pairs with their keys untouched. That is the
// only way to set a predef.* property such as predef.externalResourceID, which the
// portal reads under exactly that name and would never find as auto.predef.*.
def pveDiscover = { String id, String name, String description, Map properties,
                    Map rawProperties = [:] ->
    def pairs = properties
        .findAll { key, value -> value != null && !value.toString().trim().isEmpty() }
        .collect { key, value -> 'auto.' + key + '=' + pvePropValue(value) }
    pairs += rawProperties
        .findAll { key, value -> value != null && !value.toString().trim().isEmpty() }
        .collect { key, value -> key + '=' + pvePropValue(value) }
    println(pveWildValue(id) + '##' + pveDisplayName(name ?: id) + '##' +
            pveDisplayName(description) + '####' + pairs.join('&'))
}

// BatchScript emits "instanceId.datapoint=value"; plain Script omits the instance.
def pveEmit = { String instanceId, String key, Object value ->
    println((instanceId ? pveWildValue(instanceId) + '.' : '') + key + '=' + value)
}

def pveRound = { double value ->
    Math.round(value * 100.0d) / 100.0d
}

/*
 * Bytes are what the API reports and what a datapoint should hold for arithmetic, but
 * they are not what a dashboard can print: LogicMonitor renders 500107862016 as 5.001E11.
 * Capacity figures are therefore emitted in GB and rates in MB, decimal in both cases
 * (10^9 / 10^6) to match how drive and link capacities are labelled.
 */
def pveGB = { value ->
    pveRound(((value ?: 0) as double) / 1000000000.0d)
}

def pveMB = { value ->
    pveRound(((value ?: 0) as double) / 1000000.0d)
}

/*
 * Joins the parts of a topology key, folded to the alphabet an ERI key can carry. Both
 * node keys and the cluster key go through it, so the two sides that build each key
 * cannot disagree on case or punctuation.
 */
def pveTopoFold = { List parts ->
    parts.collect { it.toString().toLowerCase().replaceAll('[^a-z0-9_-]', '-') }.join('--')
}

/*
 * The synthesised topology key for a Proxmox vertex, used for the cluster and for every
 * node. Proxmox exposes no hardware identifier for a node anywhere in its API, so the node
 * vertex cannot be matched on something it already has the way a guest is matched on its
 * MAC. addERI_Proxmox_VE stamps the identical key on the node's own resource, and this is
 * the only place its shape is defined -- change it here and there together or the two stop
 * matching, which shows up as a map full of vertices that resolve to nothing.
 */
def pveTopoKey = { String cluster, String node ->
    def parts = node ? ['proxmoxve', cluster, node] : ['proxmoxve', cluster]
    pveTopoFold(parts)
}

/*
 * Which node, and for pveTopoNodeIdentity which cluster, a resource stands for, read from
 * its properties alone -- for a node resource that carries no API token, and so cannot
 * ask /cluster/status. Giving every node the token would make every DataSource collect
 * the whole cluster once per node, which is the duplication docs/INSTALL.md 4.1 warns
 * against.
 *
 * The node name is pve.topology.node when set to a name, and otherwise the short form of
 * system.sysname or system.hostname: Proxmox requires a node's name to be its hostname,
 * so the short hostname is the node name. An IP address says nothing about the name and
 * is skipped, in the property too. The cluster name, which only the older cluster-scoped
 * key needs, has no source but the optional pve.topology.cluster. Returns null rather than guessing: a wrong key matches no vertex,
 * which is indistinguishable on the map from no key at all, but costs a stray ERI.
 */
def pveTopoNodeName = { ->
    // An address is never a node name: pvecm status lists members by corosync address, and
    // an address copied into pve.topology.node would key the node as "192".
    def isName = { String value -> value && !(value.trim() ==~ /\d{1,3}(\.\d{1,3}){3}/) && !value.contains(':') }
    def node = pveHostProp('pve.topology.node')
    if (!isName(node)) {
        def hostname = ['system.sysname', 'system.hostname']
            .collect { pveHostProp(it) }
            .find { isName(it) }
        node = hostname?.tokenize('.')?.getAt(0)
    }
    return node ?: null
}

// The cluster and node together, for pveTopoKey's node key; null without pve.topology.cluster.
def pveTopoNodeIdentity = { ->
    def cluster = pveHostProp('pve.topology.cluster')
    if (!cluster) { return null }
    def node = pveTopoNodeName()
    return node ? [cluster: cluster, node: node] : null
}

/*
 * One address in the form both sides of the node IP key agree on, or null.
 *
 * The TopologySource reads a node's address from /cluster/status, which may write IPv6
 * compressed (fd00::12); addERI_Proxmox_VE reads system.ips, where LogicMonitor writes it
 * in full (fd00:0:0:0:0:0:0:12). InetAddress settles both to the full form. It is handed
 * only a literal: given anything else Java resolves it as a hostname, and a PropertySource
 * that runs on every opted-in resource must not make DNS lookups. A dotted quad is checked
 * octet by octet for that reason, and an IPv6 candidate goes in bracketed, which makes Java
 * refuse rather than resolve one it cannot parse. Loopback, link-local and the wildcard
 * name no reachable node, and every host has the first two, so they are dropped.
 */
def pveTopoIp = { String raw ->
    def text = raw?.trim()?.replaceFirst(/%.*$/, '')
    if (!text) { return null }
    def quad = text ==~ /\d{1,3}(\.\d{1,3}){3}/ && text.tokenize('.').every { (it as int) <= 255 }
    if (!quad && !text.contains(':')) { return null }
    try {
        def address = InetAddress.getByName(quad ? text : '[' + text + ']')
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
            return null
        }
        return address.getHostAddress().toLowerCase()
    } catch (Exception ignored) {
        return null
    }
}

/*
 * The node key that needs no cluster name: the node's short name and one of its
 * addresses. A token-less node cannot learn its cluster's name, but it knows its own name
 * and addresses, and /cluster/status gives the TopologySource the same two for every node.
 * The name alone collides across clusters (pve1 is everyone's first node) and the address
 * alone across overlapping private networks; together they are unique in practice, and
 * topo.namespace covers the environment that reuses both. Null when either part is
 * missing or the address is not one pveTopoIp accepts.
 */
def pveTopoNodeKey = { String node, String ip ->
    def name = node?.trim()?.tokenize('.')?.getAt(0)
    def address = pveTopoIp(ip)
    return (name && address) ? pveTopoFold(['proxmoxve', 'node', name, address]) : null
}

// pveTopoNodeKey over every address a node holds: unusable ones dropped, duplicates once.
def pveTopoNodeKeys = { String node, List ips ->
    (ips ?: []).collect { pveTopoNodeKey(node, it?.toString()) }.findAll { it }.unique()
}

/*
 * The one key a node vertex carries. Not both: lm.topo joins a vertex's keys into a single
 * ID, and the portal then matches only a resource carrying every one of them, so a
 * token-less node stamped with the IP key alone would go unmatched. The IP key whenever
 * /cluster/status gives a usable address, since every node's resource carries it; the
 * cluster-scoped key otherwise.
 */
def pveTopoNodeVertexKey = { String cluster, String node, String ip ->
    pveTopoNodeKey(node, ip) ?: pveTopoKey(cluster, node)
}

/*
 * The MAC of a guest's first virtual NIC, out of a guest config.
 *
 * QEMU writes it as "virtio=BC:24:11:0E:00:02,bridge=vmbr0" and LXC as
 * "name=eth0,bridge=vmbr0,hwaddr=BC:24:11:...,type=veth", so the key differs but the shape
 * of the value does not: pull the first thing that looks like a MAC. Lowercased, because
 * that is how LogicMonitor stores an ERI key and the two have to compare equal.
 */
def pveGuestMac = { config ->
    if (!config) { return null }
    def interfaces = config.keySet().findAll { it.toString() ==~ /net\d+/ }.sort()
    for (name in interfaces) {
        def matcher = (config[name].toString() =~ /([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})/)
        if (matcher.find()) { return matcher.group(1).toLowerCase() }
    }
    return null
}

/*
 * Whether a resource's MAC is one Proxmox itself assigned to a guest. This is what the
 * label PropertySource, addERI_Proxmox_VE_Guest, matches with; the topology edges and
 * GuestStatus's instance ERIs read the MAC from the guest config and do not use it.
 * Proxmox generates guest MACs under BC:24:11 by default. A guest with a hand-set MAC (a
 * passed-through or migrated NIC) is somebody else's hardware identity and must not be
 * claimed, and a physical host such as the 88:a2:9e Raspberry Pi on the test portal is
 * the control case that must never match. A site that changed Proxmox's MAC prefix lists
 * its own in prefixProperty (comma separated), and that list REPLACES the default rather
 * than adding to it. The match is a plain string prefix, so give whole octets
 * (02:00:00, not 02:0). Returns the lowercased MAC on a match, else null.
 */
def pveGuestMacMatch = { String mac, String prefixProperty ->
    if (mac == null || mac.trim().isEmpty()) { return null }
    def prefixes = (prefixProperty ?: '').tokenize(',')
        .collect { it.trim().toLowerCase() }
        .findAll { !it.isEmpty() }
    if (prefixes.isEmpty()) { prefixes = ['bc:24:11'] }
    def normalised = mac.trim().toLowerCase()
    return prefixes.any { normalised.startsWith(it) } ? normalised : null
}

/*
 * The cluster's own name, which /cluster/status reports as the single row of type
 * "cluster". A standalone host has no such row, so it falls back to the local node's
 * name -- the vertex still has to be unique across a portal that monitors several of
 * them. One definition, because the TopologySource and the guest fallback key must name
 * the same cluster or the two stop matching.
 */
def pveTopoClusterName = { List status ->
    def clusterRow = status.find { it.type?.toString() == 'cluster' }
    def localRow = status.find { it.local }
    return clusterRow?.name?.toString() ?: localRow?.name?.toString() ?: 'standalone'
}

/*
 * The guests discovery and topology both work on: every QEMU VM and LXC container, never
 * a template. Templates are never running, so discovering them creates instances that
 * alert as permanently down.
 *
 * /cluster/resources is filtered by token ACL rather than erroring, so an
 * under-privileged token looks identical to an empty cluster; say so on stderr.
 */
def pveGuestList = { ->
    def resources = pveGet('/cluster/resources?type=vm') ?: []
    if (resources.isEmpty()) {
        System.err.println('/cluster/resources returned no guests. This endpoint is filtered by ' +
            'token ACL rather than erroring, so an under-privileged token looks identical to an ' +
            'empty cluster. Verify the token has VM.Audit on /vms.')
    }
    return resources.findAll { it.type in ['qemu', 'lxc'] && !it.template }
}

/*
 * A size for an instance's name or description, so a percentage graph reads against the
 * size it is a share of: "1.8 TB", "215 GB", "8.6 GB". Decimal units, like pveGB. Empty
 * for a missing or zero size, such as unavailable storage, so nothing misleading is shown.
 */
def pveSizeLabel = { bytes ->
    def value = (bytes ?: 0) as double
    if (value <= 0) { return '' }
    if (value >= 1e12d) { return String.format(Locale.ROOT, '%.1f TB', value / 1e12d) }
    if (value >= 1e10d) { return String.format(Locale.ROOT, '%.0f GB', value / 1e9d) }
    if (value >= 1e9d) { return String.format(Locale.ROOT, '%.1f GB', value / 1e9d) }
    return String.format(Locale.ROOT, '%.0f MB', value / 1e6d)
}

/*
 * One guest's Active Discovery line. The wildvalue comes from Proxmox's own id (qemu/101)
 * and never from the node, so a live migration does not orphan the instance.
 * rawProperties passes straight through to pveDiscover.
 */
def pveGuestDiscover = { Map guest, Map rawProperties = [:] ->
    def vmid = guest.vmid?.toString()
    def kind = guest.type == 'qemu' ? 'virtual machine' : 'container'
    def disk = pveSizeLabel(guest.maxdisk)
    pveDiscover(
        guest.id,
        guest.name ?: (guest.type + '-' + vmid),
        'Proxmox ' + kind + ' ' + vmid + (disk ? ', root disk ' + disk : ''),
        [
            pve_id  : guest.id,
            pve_node: guest.node,
            pve_type: guest.type,
            pve_vmid: vmid,
            pve_pool: guest.pool,
            pve_tags: guest.tags
        ],
        rawProperties
    )
}

def pvePercent = { used, total ->
    def totalValue = (total ?: 0) as double
    totalValue > 0 ? pveRound(((used ?: 0) as double) * 100.0d / totalValue) : 0
}

/*
 * The instances Proxmox_VE_Backups discovers and collects: one per guest per backup job,
 * and one per guest no job covers. Discovery and collection both build them here, so the
 * two always agree on the wildvalues.
 *
 * /cluster/backup/{id}/included_volumes applies a job's all / pool / vmid / exclude
 * selection, so each job's guests are exactly the ones it will back up. Proxmox types them
 * "qemu" and "lxc"; its schema documents "VM" and "CT", which are accepted too. Any other
 * type, such as "unknown" for a guest removed but not purged, is skipped. Each guest's
 * volumes come with it, marked included or not, with Proxmox's reason ("not a volume" for a
 * bind mount, "backup=no" for a disk excluded by hand). /cluster/backup-info/not-backed-up
 * is absent on old Proxmox versions; then no uncovered guests are returned and stderr says
 * so, while the job instances are unaffected.
 */
def pveBackupInstances = { ->
    def instances = []
    (pveGet('/cluster/backup') ?: []).each { job ->
        def jobId = job.id?.toString()
        if (!jobId) { return }
        def label = job.comment?.toString()?.trim() ?: jobId
        def included = pveGet('/cluster/backup/' + URLEncoder.encode(jobId, 'UTF-8') + '/included_volumes')
        def guests = (included instanceof Map ? included.children : included) ?: []
        guests.each { guest ->
            def type = [qemu: 'qemu', lxc: 'lxc', VM: 'qemu', CT: 'lxc'][guest.type?.toString()]
            if (!type || guest.id == null) { return }
            def vmid = guest.id.toString()
            def volumes = (guest.children ?: []).findAll { it.id != null }
            def isIncluded = { volume -> volume.included?.toString() in ['1', 'true'] }
            def volumeKey = { volume -> volume.id.toString().tokenize(':').last() }
            instances << [
                id      : jobId + '-' + vmid,
                vmid    : vmid,
                name    : guest.name?.toString(),
                type    : type,
                covered : true,
                jobId   : jobId,
                job     : label,
                schedule: job.schedule?.toString(),
                storage : job.storage?.toString(),
                // Proxmox treats a job without "enabled" as enabled.
                enabled : !(job.enabled?.toString() in ['0', 'false']),
                nextRun : job.'next-run',
                included: volumes.findAll { isIncluded(it) }
                    .collect { volumeKey(it) + ': ' + it.name },
                excluded: volumes.findAll { !isIncluded(it) }
                    .collect { volumeKey(it) + ': ' + it.name + (it.reason ? ' (' + it.reason + ')' : '') }
            ]
        }
    }
    try {
        (pveGet('/cluster/backup-info/not-backed-up') ?: []).each { guest ->
            if (guest.vmid == null) { return }
            def vmid = guest.vmid.toString()
            instances << [id: 'none-' + vmid, vmid: vmid, name: guest.name?.toString(),
                          type: guest.type?.toString(), covered: false, job: 'No backup job']
        }
    } catch (Exception unavailable) {
        System.err.println('Guests covered by no backup job are not listed on this Proxmox ' +
            'version: ' + unavailable.message)
    }
    return instances
}
