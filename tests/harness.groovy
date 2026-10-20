/*
 * Runs the assembled module scripts against a mock Proxmox VE API.
 *
 * These scripts cannot be run against a real cluster on demand, and the only other
 * verification available is a live Collector. This harness closes that gap: it serves
 * recorded API shapes over HTTP, executes each script with the bindings a Collector
 * would provide, and asserts the output actually parses the way LogicMonitor expects --
 * discovery line format, BatchScript instance.datapoint=value format, numeric values,
 * and complete coverage of every datapoint the module declares.
 *
 * Run with: docker compose -f tests/docker-compose.yml run --rm tests
 */
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

final String TOKEN = 'monitor@pam!logicmonitor=00000000-0000-0000-0000-000000000000'
final File ROOT = new File(System.getenv('PVE_MODULE_ROOT') ?: '/work')

def fixtures = new JsonSlurper().parse(new File(ROOT, 'tests/fixtures/pve_api.json'))

// ------------------------------------------------------------------ mock API

def requested = [] as Set
// Every request in order, duplicates kept, so a case can look at only what it caused.
def requestLog = Collections.synchronizedList([])
def server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
server.createContext('/api2/json') { exchange ->
    def uri = exchange.requestURI
    def key = uri.path.replaceFirst('^/api2/json', '') + (uri.query ? '?' + uri.query : '')
    requested << key
    requestLog << key

    String body
    int status
    if (exchange.requestHeaders.getFirst('Authorization') != 'PVEAPIToken=' + TOKEN) {
        status = 401
        body = JsonOutput.toJson([data: null, errors: [auth: 'invalid token']])
    } else if (fixtures.containsKey(key)) {
        status = 200
        body = JsonOutput.toJson([data: fixtures[key]])
    } else {
        // A 501 rather than a 404 so a missing fixture is obviously a harness gap.
        status = 501
        body = JsonOutput.toJson([data: null, errors: [path: 'no fixture for ' + key]])
    }

    byte[] bytes = body.getBytes('UTF-8')
    exchange.responseHeaders.add('Content-Type', 'application/json')
    exchange.sendResponseHeaders(status, bytes.length)
    exchange.responseBody.withStream { it.write(bytes) }
}
server.executor = java.util.concurrent.Executors.newSingleThreadExecutor()

/*
 * Run body with some fixtures replaced. A null value removes that key, so the mock
 * answers it with 501. Everything is put back afterwards, keys that did not exist
 * included, so one case cannot leak its fixtures into the next.
 */
def withFixtures = { Map overrides, Closure body ->
    def saved = [:]
    def absent = []
    overrides.each { key, value ->
        if (fixtures.containsKey(key)) { saved[key] = fixtures[key] } else { absent << key }
        if (value == null) { fixtures.remove(key) } else { fixtures[key] = value }
    }
    try {
        return body()
    } finally {
        saved.each { key, value -> fixtures[key] = value }
        absent.each { fixtures.remove(it) }
    }
}
server.start()
def baseUrl = 'http://127.0.0.1:' + server.address.port

// ------------------------------------------------------------------- runner

def failures = []
int checks = 0

def note = { String scope, boolean ok, String message ->
    checks++
    if (!ok) { failures << (scope + ': ' + message) }
}

/* Execute a script with the bindings a Collector injects, capturing its streams. */
def runScript = { File file, Map bindings ->
    def outBuffer = new ByteArrayOutputStream()
    def errBuffer = new ByteArrayOutputStream()
    def realOut = System.out
    def realErr = System.err
    def result = null
    Throwable thrown = null
    try {
        System.setOut(new PrintStream(outBuffer, true, 'UTF-8'))
        System.setErr(new PrintStream(errBuffer, true, 'UTF-8'))
        result = new GroovyShell(new Binding(bindings)).evaluate(file)
    } catch (Throwable t) {
        thrown = t
    } finally {
        System.setOut(realOut)
        System.setErr(realErr)
    }
    [
        exit  : result,
        stdout: new String(outBuffer.toByteArray(), 'UTF-8'),
        stderr: new String(errBuffer.toByteArray(), 'UTF-8'),
        thrown: thrown,
    ]
}

def hostProps = [
    'system.hostname': '127.0.0.1',
    'pve.api.url'    : baseUrl,
    'pve.api.token.credential'  : TOKEN,
    'pve.api.timeout': '5000',
]

def AD_LINE = ~/^([A-Za-z0-9_.\-]+)##([^#]*)##([^#]*)####(.*)$/
def BATCH_LINE = ~/^([A-Za-z0-9_.\-]+)\.([A-Za-z0-9_]+)=(.*)$/
def PLAIN_LINE = ~/^([A-Za-z0-9_]+)=(.*)$/
def isNumeric = { String v -> v ==~ /^-?\d+(\.\d+)?([eE][-+]?\d+)?$/ }

// Instance properties a Collector supplies for per-instance Script collection.
def instanceBindings = [
    'Proxmox_VE_NodeDetail': ['auto.pve_node': 'pve1', 'auto.pve_id': 'node/pve1'],
]

def modulesDir = new File(ROOT, 'modules')
def scriptsDir = new File(ROOT, 'dist/scripts')

// --------------------------------------------------- per-module conformance

modulesDir.listFiles({ f -> f.name.endsWith('.json') } as FileFilter).sort { it.name }.each { defFile ->
    def defn = new JsonSlurper().parse(defFile)
    String name = defn.name

    /*
     * A TopologySource has no datapoints and prints edges rather than metrics, and its
     * script is the one in the suite that cannot run here at all -- it works through
     * Collector-only snippet classes, which is why build.py emits it outside
     * dist/scripts/. Its Proxmox-side logic lives in the preamble helpers instead, and
     * those are asserted further down like anything else.
     */
    if (defn.moduleType in ['topologysource', 'propertysource']) {
        return
    }

    def declared = defn.datapoints.collectEntries { [(it.name): it.conditional as boolean] }
    def required = declared.findAll { key, conditional -> !conditional }.keySet()

    def discovered = []
    if (defn.discoveryScript) {
        def run = runScript(new File(scriptsDir, name + '.ad.groovy'), [hostProps: hostProps])

        note("${name}/ad", run.thrown == null, "threw ${run.thrown}")
        note("${name}/ad", run.exit == 0, "exit was ${run.exit}, expected 0")

        def lines = run.stdout.readLines().findAll { it.trim() }
        note("${name}/ad", !lines.isEmpty(), 'discovered no instances')

        lines.each { line ->
            def matcher = AD_LINE.matcher(line)
            if (!matcher.matches()) {
                note("${name}/ad", false, "malformed discovery line: ${line}")
                return
            }
            discovered << matcher.group(1)
            note("${name}/ad", !matcher.group(1).contains(' '),
                 "instance id contains a space: ${matcher.group(1)}")
            matcher.group(4).tokenize('&').each { pair ->
                note("${name}/ad", pair.startsWith('auto.') && pair.contains('='),
                     "instance property is not auto.key=value: ${pair}")
                note("${name}/ad", pair.split('=', 2)[1].trim() != '',
                     "instance property has a blank value: ${pair}")
            }
        }
    }

    def bindings = [hostProps: hostProps]
    if (instanceBindings[name]) { bindings.taskProps = instanceBindings[name] }
    def run = runScript(new File(scriptsDir, name + '.collect.groovy'), bindings)

    note("${name}/collect", run.thrown == null, "threw ${run.thrown}")
    note("${name}/collect", run.exit == 0, "exit was ${run.exit}, expected 0")

    def lines = run.stdout.readLines().findAll { it.trim() }
    note("${name}/collect", !lines.isEmpty(), 'produced no output')

    if (defn.collectionMethod == 'batchscript') {
        def perInstance = [:].withDefault { [] as Set }
        lines.each { line ->
            def matcher = BATCH_LINE.matcher(line)
            if (!matcher.matches()) {
                note("${name}/collect", false, "malformed batchscript line: ${line}")
                return
            }
            perInstance[matcher.group(1)] << matcher.group(2)
            note("${name}/collect", isNumeric(matcher.group(3)),
                 "non-numeric value for ${matcher.group(2)}: ${matcher.group(3)}")
            note("${name}/collect", declared.containsKey(matcher.group(2)),
                 "emitted undeclared datapoint ${matcher.group(2)}")
        }

        // A discovery/collection key mismatch is the defect that silently produces
        // instances with permanently empty graphs, so it is checked in both directions.
        discovered.each { id ->
            note("${name}/collect", perInstance.containsKey(id),
                 "instance ${id} was discovered but collected no data")
        }
        perInstance.each { id, keys ->
            note("${name}/collect", discovered.contains(id),
                 "collected data for undiscovered instance ${id}")
            def missing = required - keys
            note("${name}/collect", missing.isEmpty(),
                 "instance ${id} is missing required datapoints ${missing}")
        }
    } else {
        def seen = [] as Set
        lines.each { line ->
            def matcher = PLAIN_LINE.matcher(line)
            if (!matcher.matches()) {
                note("${name}/collect", false, "malformed script line: ${line}")
                return
            }
            seen << matcher.group(1)
            note("${name}/collect", isNumeric(matcher.group(2)),
                 "non-numeric value for ${matcher.group(1)}: ${matcher.group(2)}")
            note("${name}/collect", declared.containsKey(matcher.group(1)),
                 "emitted undeclared datapoint ${matcher.group(1)}")
        }
        def missing = required - seen
        note("${name}/collect", missing.isEmpty(), "missing required datapoints ${missing}")
    }
}

// ------------------------------------- assertions that pin the design decisions

def guestAdFile = new File(scriptsDir, 'Proxmox_VE_GuestPerformance.ad.groovy')
def guestAd = runScript(guestAdFile, [hostProps: hostProps])
def guestIds = guestAd.stdout.readLines().findAll { it.trim() }.collect { it.split('##')[0] }

note('templates', !guestIds.contains('qemu-999'),
     "a template was discovered as an instance: ${guestIds}")
note('instance-id', guestIds.contains('qemu-101'),
     "expected the Proxmox-native id qemu-101, got ${guestIds}")
note('instance-id', guestIds.every { !it.contains('pve1') && !it.contains('pve2') },
     "guest instance ids embed a node name and would break on migration: ${guestIds}")

def guestCollect = runScript(new File(scriptsDir, 'Proxmox_VE_GuestPerformance.collect.groovy'),
                             [hostProps: hostProps])
def keysFor = { String prefix ->
    guestCollect.stdout.readLines().findAll { it.startsWith(prefix + '.') }
        .collect { it.substring(prefix.length() + 1).split('=')[0] } as Set
}
note('qemu-disk', !keysFor('qemu-101').contains('DiskUsedGB'),
     'DiskUsedGB was emitted for a QEMU guest, which reports no used-disk figure')
note('lxc-disk', keysFor('lxc-200').contains('DiskUsedGB'),
     'DiskUsedGB was not emitted for an LXC guest, which does report one')

// An unreachable API must fail loudly rather than report zeroes, so that LogicMonitor
// keeps existing instances instead of tearing them down during an outage.
def dead = runScript(new File(scriptsDir, 'Proxmox_VE_Nodes.collect.groovy'),
                     [hostProps: hostProps + ['pve.api.url': 'http://127.0.0.1:1']])
note('api-down', dead.exit == 2, "expected exit 2 when the API is unreachable, got ${dead.exit}")
note('api-down', dead.stdout.trim().isEmpty(), 'emitted datapoints while the API was unreachable')
note('api-down', dead.stderr.toLowerCase().contains('failed'), 'did not explain the failure on stderr')

// A rejected token must read as a permissions problem, not as an empty cluster.
def badAuth = runScript(guestAdFile, [hostProps: hostProps + ['pve.api.token.credential': 'wrong@pam!bad=nope']])
note('api-auth', badAuth.exit == 2, "expected exit 2 on HTTP 401, got ${badAuth.exit}")
note('api-auth', badAuth.stderr.contains('token'), 'did not mention the token on stderr')

// Missing configuration must be a clean, explained failure rather than an exception.
def unconfigured = runScript(new File(scriptsDir, 'Proxmox_VE_Nodes.collect.groovy'),
                             [hostProps: ['system.hostname': 'pve.example.com']])
note('unconfigured', unconfigured.exit == 2, "expected exit 2 with no token, got ${unconfigured.exit}")
note('unconfigured', unconfigured.stderr.contains('pve.api.token.credential'),
     'did not name the missing property on stderr')

// ------------------------------------------------------------ PropertySource

def propertySource = new File(scriptsDir, 'addCategory_Proxmox_VE.groovy')

def detected = runScript(propertySource, [hostProps: hostProps])
def detectedProps = detected.stdout.readLines().findAll { it.trim() }
                        .collectEntries { [(it.split('=', 2)[0]): it.split('=', 2)[1]] }
note('propertysource', detected.exit == 0, "exit was ${detected.exit}, expected 0")
note('propertysource', detectedProps['system.categories'] == 'ProxmoxVE',
     "did not set the category that every module's AppliesTo depends on: ${detectedProps}")
note('propertysource', detectedProps['pve.version'] == '8.2.2',
     "did not report the Proxmox version: ${detectedProps}")
note('propertysource', detectedProps['pve.clustered'] == 'true',
     "did not detect cluster membership: ${detectedProps}")
// The value a user copies into pve.topology.cluster on the token-less node resources. It
// has to be the name the TopologySource keys the cluster on, which is this same row.
note('propertysource', detectedProps['pve.cluster.name'] == 'pve-cluster',
     "did not publish the cluster name: ${detectedProps}")

// A portal is mostly non-Proxmox resources. The PropertySource must stay silent on them
// rather than applying the whole suite to unrelated hosts.
def notProxmox = runScript(propertySource, [hostProps: ['system.hostname': 'some-webserver']])
note('propertysource', notProxmox.exit == 0,
     "exit was ${notProxmox.exit} on a non-Proxmox host, expected 0")
note('propertysource', notProxmox.stdout.trim().isEmpty(),
     "set properties on a host with no Proxmox credentials: ${notProxmox.stdout}")

// ------------------------------------------------- GuestStatus instance ERIs
//
// GuestStatus has its own discovery so that, behind the topology opt-in, each guest's
// instance can carry the key the TopologySource draws it under. Flag off, it must be
// exactly the shared guest discovery: same lines, and no per-guest config reads.

def statusAd = new File(scriptsDir, 'Proxmox_VE_GuestStatus.ad.groovy')
def perfAd = new File(scriptsDir, 'Proxmox_VE_GuestPerformance.ad.groovy')
def on = hostProps + ['pve.topology.enabled': 'true']

// Run a discovery script and return the run plus only the requests it made.
def discoverWith = { File file, Map props ->
    int before = requestLog.size()
    def run = runScript(file, [hostProps: props])
    def log = new ArrayList(requestLog)
    run.requests = log.subList(before, log.size())
    run
}

// id -> [auto: [...], predef: [...]], values URL-decoded: pveDiscover encodes them, and
// the portal reads the decoded form.
def parseAd = { String stdout ->
    def instances = [:]
    stdout.readLines().findAll { it.trim() }.each { line ->
        def matcher = AD_LINE.matcher(line)
        if (!matcher.matches()) { return }
        def props = [auto: [:], predef: [:]]
        matcher.group(4).tokenize('&').each { pair ->
            def parts = pair.split('=', 2)
            def value = URLDecoder.decode(parts.length > 1 ? parts[1] : '', 'UTF-8')
            if (parts[0].startsWith('auto.')) { props.auto[parts[0]] = value }
            else if (parts[0].startsWith('predef.')) { props.predef[parts[0]] = value }
        }
        instances[matcher.group(1)] = props
    }
    instances
}

def extendedFixtures = [
    '/cluster/resources?type=vm': fixtures['/cluster/resources?type=vm'] + [
        [id: 'lxc/103', type: 'lxc', vmid: 103, name: 'relay', node: 'pve1', template: false],
        [id: 'qemu/104', type: 'qemu', vmid: 104, name: 'no-config', node: 'pve1', template: false],
        [id: 'qemu/105', type: 'qemu', vmid: 105, name: 'no-nic', node: 'pve1', template: false],
    ],
    '/nodes/pve1/lxc/103/config': [hostname: 'relay',
        net0: 'name=eth0,bridge=vmbr0,hwaddr=BC:24:11:55:CC:E5,type=veth'],
    '/nodes/pve1/qemu/105/config': [name: 'no-nic', cores: 1],
]

def eriOff = discoverWith(statusAd, hostProps)
def perfOff = discoverWith(perfAd, hostProps)
note('guest-eri-off', eriOff.thrown == null && eriOff.exit == 0,
     "exit was ${eriOff.exit} / ${eriOff.thrown}")
note('guest-eri-off', eriOff.stdout == perfOff.stdout && !eriOff.stdout.trim().isEmpty(),
     "flag-off discovery differs from the shared guest discovery:\n${eriOff.stdout}")
note('guest-eri-off', !eriOff.requests.any { it.contains('/config') },
     "flag-off discovery read guest configs: ${eriOff.requests}")
def offIds = parseAd(eriOff.stdout).keySet()

def eriOn = withFixtures(extendedFixtures) { discoverWith(statusAd, on) }
def onAd = parseAd(eriOn.stdout)
note('guest-eri-on', eriOn.thrown == null && eriOn.exit == 0,
     "exit was ${eriOn.exit} / ${eriOn.thrown}: ${eriOn.stderr}")
[
    'qemu-101': 'bc:24:11:f8:1e:58',
    'lxc-103' : 'bc:24:11:55:cc:e5',
    // No NIC: the TopologySource's fallback key, which must match it byte for byte.
    'qemu-105': 'proxmoxve--pve-cluster--qemu-105',
].each { id, key ->
    note('guest-eri-on', onAd[id]?.predef?.get('predef.externalResourceID') == key,
         "${id} externalResourceID was ${onAd[id]?.predef}, expected ${key}")
    note('guest-eri-on', onAd[id]?.predef?.get('predef.externalResourceType') == 'VirtualMachine',
         "${id} externalResourceType was ${onAd[id]?.predef}")
}
// A config that cannot be read, and a guest on an offline node, are still instances.
['qemu-104', 'lxc-200', 'qemu-102'].each { id ->
    note('guest-eri-on', onAd.containsKey(id) && onAd[id].predef.isEmpty(),
         "${id} should be discovered without keys: ${onAd[id]}")
}
note('guest-eri-on', !eriOn.requests.any { it.startsWith('/nodes/pve2/') },
     "asked an offline node for guest configs: ${eriOn.requests}")
note('guest-eri-on', !onAd.containsKey('qemu-999'), 'a template was discovered')
note('guest-eri-on', onAd.keySet().containsAll(offIds),
     "the flag lost instances: ${offIds - onAd.keySet()}")

// A standalone host keys its NIC-less guests under the local node, as the TopologySource does.
def eriStandalone = withFixtures(extendedFixtures +
        ['/cluster/status': [[type: 'node', name: 'pve-a', local: 1, online: 1]]]) {
    discoverWith(statusAd, on)
}
def standaloneKey = parseAd(eriStandalone.stdout)['qemu-105']?.predef?.get('predef.externalResourceID')
note('guest-eri-standalone', standaloneKey == 'proxmoxve--pve-a--qemu-105',
     "standalone fallback key was ${standaloneKey}")

// lm.topo's namespace and blacklist rewrite the TopologySource's keys; the discovery
// keys would no longer match, so the feature steps aside and says why.
['topo.namespace', 'topo.blacklist'].each { prop ->
    def run = withFixtures(extendedFixtures) { discoverWith(statusAd, on + [(prop): 'x']) }
    note('guest-eri-namespace', run.thrown == null && run.exit == 0,
         "${prop}: exit was ${run.exit} / ${run.thrown}")
    note('guest-eri-namespace', parseAd(run.stdout).values().every { it.predef.isEmpty() },
         "${prop} set but keys were still emitted:\n${run.stdout}")
    note('guest-eri-namespace', run.stderr.contains(prop),
         "${prop} set but stderr did not say so: ${run.stderr}")
    note('guest-eri-namespace', !run.requests.any { it.contains('/config') },
         "${prop} set but guest configs were still read: ${run.requests}")
}

// The gating calls are the optional extra; when they fail, discovery is today's.
def gateFails = withFixtures(['/cluster/status': null]) { discoverWith(statusAd, on) }
note('guest-eri-gate-fails', gateFails.thrown == null && gateFails.exit == 0,
     "exit was ${gateFails.exit} / ${gateFails.thrown}")
note('guest-eri-gate-fails', gateFails.stdout == eriOff.stdout,
     "output differs from flag-off discovery:\n${gateFails.stdout}")

// Only GuestStatus carries the keys, so each guest's key sits on one instance.
def perfOn = withFixtures(extendedFixtures) { discoverWith(perfAd, on) }
note('guest-eri-perf', perfOn.exit == 0 &&
                       parseAd(perfOn.stdout).values().every { it.predef.isEmpty() },
     "GuestPerformance discovery carried instance ERIs:\n${perfOn.stdout}")

// --------------------------------------------------------------- topology
//
// Proxmox_VE_Topology itself cannot run here. It works through Collector-only snippet
// classes, which is the whole reason build.py emits it outside dist/scripts/. What can
// be tested is the half that is ours: the two preamble helpers that decide a vertex's
// identity. Get either wrong and the map draws vertices that resolve to nothing, which
// looks exactly like a working topology until you click one.

def runSource = { String source, Map bindings ->
    def outBuffer = new ByteArrayOutputStream()
    def errBuffer = new ByteArrayOutputStream()
    def realOut = System.out
    def realErr = System.err
    def result = null
    Throwable thrown = null
    try {
        System.setOut(new PrintStream(outBuffer, true, 'UTF-8'))
        System.setErr(new PrintStream(errBuffer, true, 'UTF-8'))
        result = new GroovyShell(new Binding(bindings)).evaluate(source)
    } catch (Throwable t) {
        thrown = t
    } finally {
        System.setOut(realOut)
        System.setErr(realErr)
    }
    [exit: result, stdout: new String(outBuffer.toByteArray(), 'UTF-8'),
     stderr: new String(errBuffer.toByteArray(), 'UTF-8'), thrown: thrown]
}

def preamble = new File(ROOT, 'scripts/lib/pve_common.groovy').text
def helpers = runSource(preamble + """
return [
    cluster : pveTopoKey('pve-cluster', null),
    node    : pveTopoKey('PVE Cluster', 'pve1.example.com'),
    qemu    : pveGuestMac(['net0': 'virtio=BC:24:11:F8:1E:58,bridge=vmbr0,firewall=1']),
    lxc     : pveGuestMac(['net0': 'name=eth0,bridge=vmbr0,hwaddr=BC:24:11:0A:1B:2C,type=veth']),
    ordered : pveGuestMac(['net1': 'virtio=AA:BB:CC:DD:EE:02', 'net0': 'virtio=AA:BB:CC:DD:EE:01']),
    noNic   : pveGuestMac(['name': 'nothing-here', 'cores': 2]),
    nothing : pveGuestMac(null),
]
""", [hostProps: hostProps])

note('topo-helpers', helpers.thrown == null, "preamble helpers threw ${helpers.thrown}")
def helper = helpers.exit ?: [:]

note('topo-helpers', helper.cluster == 'proxmoxve--pve-cluster',
     "cluster vertex key was ${helper.cluster}")
// Folded to the ERI-safe alphabet: a node is routinely an FQDN and a cluster name can
// carry spaces, and neither may reach a key that addERI_Proxmox_VE has to reproduce.
note('topo-helpers', helper.node == 'proxmoxve--pve-cluster--pve1-example-com',
     "node vertex key was ${helper.node}")

// QEMU writes the MAC after the model, LXC after hwaddr. Both must yield the same
// lowercase form LogicMonitor keys a resource's ERI on.
note('topo-helpers', helper.qemu == 'bc:24:11:f8:1e:58',
     "QEMU guest MAC was ${helper.qemu}")
note('topo-helpers', helper.lxc == 'bc:24:11:0a:1b:2c',
     "LXC guest MAC was ${helper.lxc}")
// net0 wins over net1 whatever order the map iterates in; a guest that changed which
// adapter it was identified by would change identity and move on the map.
note('topo-helpers', helper.ordered == 'aa:bb:cc:dd:ee:01',
     "did not take the lowest-numbered adapter: ${helper.ordered}")
// No NIC is a permanent fact and must read as absent, not as a wrong MAC.
note('topo-helpers', helper.noNic == null,
     "invented a MAC for a guest with no adapter: ${helper.noNic}")
note('topo-helpers', helper.nothing == null,
     "invented a MAC from a null config: ${helper.nothing}")

// A node resource without the API token names itself from its properties. The key it
// arrives at must be the one the TopologySource builds from /cluster/status for the same
// node, or that node is not drawn -- and nothing reports the mismatch.
def identityOf = { Map props ->
    def run = runSource(preamble + '\nreturn pveTopoNodeIdentity()', [hostProps: props])
    note('topo-identity', run.thrown == null, "pveTopoNodeIdentity threw ${run.thrown}")
    run.exit
}
def keyOf = { Map props ->
    def run = runSource(preamble + '''
def id = pveTopoNodeIdentity()
return id ? pveTopoKey(id.cluster, id.node) : null''', [hostProps: props])
    run.exit
}
def clusterProp = ['pve.topology.cluster': 'pve-cluster']

note('topo-identity', keyOf(clusterProp + ['system.hostname': 'pve2.example.com']) ==
                      'proxmoxve--pve-cluster--pve2',
     'an FQDN hostname did not yield the key the TopologySource builds for that node')
note('topo-identity', identityOf(clusterProp + ['system.hostname': 'pve2']) ==
                      [cluster: 'pve-cluster', node: 'pve2'],
     'a short hostname was not taken as the node name')
// sysname is what the host calls itself; hostname is whatever the resource was added as.
note('topo-identity', identityOf(clusterProp + ['system.sysname': 'pve3',
                                                'system.hostname': 'proxmox-c.lan'])?.node == 'pve3',
     'system.hostname was preferred over system.sysname')
note('topo-identity', identityOf(clusterProp + ['system.sysname': 'pve3',
                                                'system.hostname': '10.0.0.13'])?.node == 'pve3',
     'an IP hostname displaced a usable sysname')
note('topo-identity', identityOf(clusterProp + ['pve.topology.node': 'pve4',
                                                'system.sysname': 'pve3'])?.node == 'pve4',
     'pve.topology.node did not override the derived name')
// An address names nothing. Guessing would stamp a key that matches no vertex.
note('topo-identity', identityOf(clusterProp + ['system.hostname': '10.0.0.12']) == null,
     'an IPv4 address was taken as a node name')
note('topo-identity', identityOf(clusterProp + ['system.hostname': 'fd00::12']) == null,
     'an IPv6 address was taken as a node name')
// No cluster name, no key: the TopologySource keys every node under the cluster's name.
note('topo-identity', identityOf(['system.hostname': 'pve2']) == null,
     'produced an identity with no pve.topology.cluster')

// The guest MAC prefix test and the cluster-name rule are shared by the TopologySource,
// the guest ERI PropertySource and the guest discovery, none of which can run here.
def guestMac = runSource(preamble + """
return [
    lower   : pveGuestMacMatch('bc:24:11:62:dd:94', null),
    upper   : pveGuestMacMatch('BC:24:11:B1:E6:5D', null),
    pi      : pveGuestMacMatch('88:a2:9e:a2:07:33', null),
    custom  : pveGuestMacMatch('02:00:00:12:34:56', ' BC:24:11 , 02:00:00'),
    notDflt : pveGuestMacMatch('bc:24:11:62:dd:94', '02:00:00'),
    blank   : pveGuestMacMatch('  ', null),
    none    : pveGuestMacMatch(null, null),
    cluster : pveTopoClusterName([[type: 'cluster', name: 'pve-cluster'], [type: 'node', name: 'pve1', local: 1]]),
    single  : pveTopoClusterName([[type: 'node', name: 'pve-a', local: 1]]),
    empty   : pveTopoClusterName([]),
]
""", [hostProps: hostProps])
note('guest-mac', guestMac.thrown == null, "guest MAC helpers threw ${guestMac.thrown}")
def gm = guestMac.exit ?: [:]
note('guest-mac', gm.lower == 'bc:24:11:62:dd:94', "lower MAC was ${gm.lower}")
note('guest-mac', gm.upper == 'bc:24:11:b1:e6:5d', "upper MAC was ${gm.upper}")
note('guest-mac', gm.pi == null, "a non-Proxmox MAC matched: ${gm.pi}")
note('guest-mac', gm.custom == '02:00:00:12:34:56', "custom prefixes did not match: ${gm.custom}")
note('guest-mac', gm.notDflt == null, "an override did not replace the default prefix: ${gm.notDflt}")
note('guest-mac', gm.blank == null, "a blank MAC matched: ${gm.blank}")
note('guest-mac', gm.none == null, "a null MAC matched: ${gm.none}")
note('guest-mac', gm.cluster == 'pve-cluster', "cluster name was ${gm.cluster}")
note('guest-mac', gm.single == 'pve-a', "standalone cluster name was ${gm.single}")
note('guest-mac', gm.empty == 'standalone', "empty status cluster name was ${gm.empty}")
// The fallback key must be built from the same cluster name in both places.
note('guest-mac', new File(ROOT, 'scripts/Proxmox_VE_Topology.topo.groovy').text.contains('pveTopoClusterName('),
     'the TopologySource derives its cluster name without pveTopoClusterName')

def discoverRaw = runSource(preamble + "pveDiscover('qemu/1', 'n', 'd', [a: 'x'], " +
    "['predef.externalResourceID': 'bc:24:11:00:00:01'])", [hostProps: hostProps])
note('discover-raw', discoverRaw.thrown == null && discoverRaw.stdout.trim() ==
     'qemu-1##n##d####auto.a=x&predef.externalResourceID=bc%3A24%3A11%3A00%3A00%3A01',
     "raw properties output was ${discoverRaw.stdout.trim()} / ${discoverRaw.thrown}")

// The ERI PropertySource is the other half of the node vertex, and it cannot run here
// either: LogicMonitor's own addERI_* modules build the ERI output through lm.topo's
// emitEri and printEriArray rather than printing JSON, so this script imports the same
// Collector-only classes the TopologySource does.
//
// What is left to assert is structural, and it is the thing that actually breaks. Both
// halves must derive the node key from pveTopoKey. If either ever inlines its own
// version, they can drift apart and every node vertex stops matching its resource --
// with nothing failing anywhere, because each half is individually correct.
def eriScript = new File(ROOT, 'scripts/addERI_Proxmox_VE.groovy').text
def topoScript = new File(ROOT, 'scripts/Proxmox_VE_Topology.topo.groovy').text

note('addERI', eriScript.contains('pveTopoKey('),
     'the ERI PropertySource builds its node key without pveTopoKey')
note('addERI', topoScript.contains('pveTopoKey('),
     'the TopologySource builds its node key without pveTopoKey')
note('addERI', !eriScript.contains('rawERIs'),
     'the ERI PropertySource hand-writes the rawERIs JSON instead of using emitEri')
note('addERI', eriScript.contains('lmtopo.emitEri(') &&
               eriScript.contains('lmtopo.printEriArray('),
     'the ERI PropertySource does not use the documented emitEri/printEriArray pair')

// Silence is the contract addCategory_Proxmox_VE also keeps: most resources in a portal
// are not Proxmox, and a PropertySource that prints on one it does not understand
// corrupts that resource's properties. Asserted by reading the guard, since the script
// cannot be executed here.
note('addERI', eriScript.contains('if (!identity) {') &&
               eriScript.contains('return 0'),
     'the ERI PropertySource does not return silently when it cannot name the node')
// The point of the property path: a node with no token still gets its key. If the script
// ever returns on pveConfigError again, only the token-holding node can be drawn.
note('addERI', eriScript.contains('pveTopoNodeIdentity()') &&
               !eriScript.contains('if (pveConfigError) {'),
     'the ERI PropertySource requires the API token again')

// The entire justification for the BatchScript design.
note('call-efficiency', requested.contains('/cluster/resources?type=vm'),
     'guest collection did not use the bulk endpoint')
note('call-efficiency', !requested.any { it.contains('rrddata') },
     "collection used the redundant rrddata endpoint: ${requested}")

server.stop(0)

// -------------------------------------------------------------------- report

println ''
println 'API paths exercised:'
requested.sort().each { println '  ' + it }
println ''

if (failures) {
    println "FAILED  ${failures.size()} of ${checks} checks"
    failures.each { println '  - ' + it }
    System.exit(1)
}
println "PASSED  all ${checks} checks"
System.exit(0)
