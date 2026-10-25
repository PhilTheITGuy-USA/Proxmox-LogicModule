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

// Modules whose collection calls each guest's own node, so a guest on an offline node reports nothing.
def GUEST_NODE_READ_MODULES = ['Proxmox_VE_Snapshots']
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
    def onOfflineNode = [] as Set
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
            if (matcher.group(4).tokenize('&').contains('auto.pve_node=pve2')) { onOfflineNode << matcher.group(1) }
            // An instance without the grouping property falls outside every node group.
            if (defn.instanceGroupProperty) {
                note("${name}/ad", matcher.group(4).tokenize('&')
                         .any { it.startsWith(defn.instanceGroupProperty + '=') },
                     "instance ${matcher.group(1)} lacks ${defn.instanceGroupProperty}, " +
                     'which its instance group is built from')
            }
            note("${name}/ad", !matcher.group(1).contains(' '),
                 "instance id contains a space: ${matcher.group(1)}")
            // The two predef ERI properties are the only others: GuestStatus and Cluster put
            // the topology keys on their instances, by default since 2026-10-25.
            matcher.group(4).tokenize('&').each { pair ->
                note("${name}/ad", (pair.startsWith('auto.') ||
                                    pair.startsWith('predef.externalResourceID=') ||
                                    pair.startsWith('predef.externalResourceType=')) && pair.contains('='),
                     "instance property is not auto.key=value: ${pair}")
                note("${name}/ad", pair.split('=', 2)[1].trim() != '',
                     "instance property has a blank value: ${pair}")
            }
        }
    }

    // The built export carries the grouping LogicMonitor reads: groupMethod ilp plus the
    // property in groupExpr, exactly as a portal export of a hand-grouped module shows it.
    if (defn.discoveryScript) {
        def built = new JsonSlurper().parse(new File(ROOT, 'dist/' + name + '.json')).activeDiscovery
        def wantMethod = defn.instanceGroupProperty ? 'ilp' : 'none'
        note("${name}/ad", built.groupMethod == wantMethod && built.groupExpr == defn.instanceGroupProperty,
             "built groupMethod/groupExpr were ${built.groupMethod}/${built.groupExpr}, " +
             "expected ${wantMethod}/${defn.instanceGroupProperty}")
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
            // A module that asks each guest's own node has nothing for a guest on the offline
            // node pve2, and must not pretend otherwise.
            if (name in GUEST_NODE_READ_MODULES && id in onOfflineNode) {
                note("${name}/collect", !perInstance.containsKey(id),
                     "instance ${id} is on offline node pve2 but collected data")
                return
            }
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
// The value a user copies into the optional pve.topology.cluster. It has to be the name
// the TopologySource keys the cluster on, which is this same row.
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
// GuestStatus has its own discovery so that each guest's instance can carry the key the
// TopologySource draws it under. It must discover exactly the shared guest discovery's
// instances, and where it can add no keys its output must be the shared discovery's.

def statusAd = new File(scriptsDir, 'Proxmox_VE_GuestStatus.ad.groovy')
def perfAd = new File(scriptsDir, 'Proxmox_VE_GuestPerformance.ad.groovy')

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
        [id: 'qemu/105', type: 'qemu', vmid: 105, name: 'no-nic', node: 'pve1', template: false],
        // Last on pve1 on purpose: its config read fails, and the per-node breaker skips
        // every later config read on the same node.
        [id: 'qemu/104', type: 'qemu', vmid: 104, name: 'no-config', node: 'pve1', template: false],
    ],
    '/nodes/pve1/lxc/103/config': [hostname: 'relay',
        net0: 'name=eth0,bridge=vmbr0,hwaddr=BC:24:11:0E:00:03,type=veth'],
    '/nodes/pve1/qemu/105/config': [name: 'no-nic', cores: 1],
]

def perfOff = discoverWith(perfAd, hostProps)
note('guest-eri-perf', perfOff.exit == 0 && !perfOff.stdout.trim().isEmpty(),
     "the shared guest discovery failed: ${perfOff.exit} / ${perfOff.stderr}")
def offIds = parseAd(perfOff.stdout).keySet()

def eriOn = withFixtures(extendedFixtures) { discoverWith(statusAd, hostProps) }
def onAd = parseAd(eriOn.stdout)
note('guest-eri-on', eriOn.thrown == null && eriOn.exit == 0,
     "exit was ${eriOn.exit} / ${eriOn.thrown}: ${eriOn.stderr}")
[
    'qemu-101': 'bc:24:11:0e:00:02',
    'lxc-103' : 'bc:24:11:0e:00:03',
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
     "the keys cost instances: ${offIds - onAd.keySet()}")

// Per-node circuit breaker. A node whose first config read fails is assumed sick for the
// rest of the run: every later guest on it is discovered without keys rather than spending
// a pve.api.timeout each. qemu-106 follows qemu-104 on pve1 and also has no config, so only
// qemu-104's read may happen; the guests read before it keep their keys.
def breakerFixtures = extendedFixtures + [
    '/cluster/resources?type=vm': extendedFixtures['/cluster/resources?type=vm'] + [
        [id: 'qemu/106', type: 'qemu', vmid: 106, name: 'after-failure', node: 'pve1', template: false],
    ],
]
def breaker = withFixtures(breakerFixtures) { discoverWith(statusAd, hostProps) }
def breakerAd = parseAd(breaker.stdout)
def configReads = breaker.requests.findAll { it.endsWith('/config') }
note('guest-eri-breaker', breaker.thrown == null && breaker.exit == 0,
     "exit was ${breaker.exit} / ${breaker.thrown}: ${breaker.stderr}")
note('guest-eri-breaker', configReads.contains('/nodes/pve1/qemu/104/config') &&
                          !configReads.contains('/nodes/pve1/qemu/106/config'),
     "config reads continued on pve1 after its first failure: ${configReads}")
note('guest-eri-breaker', configReads == ['/nodes/pve1/qemu/101/config',
                                          '/nodes/pve1/lxc/103/config',
                                          '/nodes/pve1/qemu/105/config',
                                          '/nodes/pve1/qemu/104/config'],
     "unexpected config reads: ${configReads}")
['qemu-104', 'qemu-106'].each { id ->
    note('guest-eri-breaker', breakerAd.containsKey(id) && breakerAd[id].predef.isEmpty(),
         "${id} should be discovered without keys: ${breakerAd[id]}")
}
['qemu-101', 'lxc-103', 'qemu-105'].each { id ->
    note('guest-eri-breaker', breakerAd[id]?.predef?.get('predef.externalResourceID') as boolean,
         "${id}, read before the failure, lost its key: ${breakerAd[id]}")
}
note('guest-eri-breaker', breaker.stderr.readLines().count { it.contains('pve1') } == 1,
     "expected one stderr line for pve1, got: ${breaker.stderr}")

// A standalone host keys its NIC-less guests under the local node, as the TopologySource does.
def eriStandalone = withFixtures(extendedFixtures +
        ['/cluster/status': [[type: 'node', name: 'pve-a', local: 1, online: 1]]]) {
    discoverWith(statusAd, hostProps)
}
def standaloneKey = parseAd(eriStandalone.stdout)['qemu-105']?.predef?.get('predef.externalResourceID')
note('guest-eri-standalone', standaloneKey == 'proxmoxve--pve-a--qemu-105',
     "standalone fallback key was ${standaloneKey}")

// lm.topo's namespace and blacklist rewrite the TopologySource's keys; the discovery
// keys would no longer match, so the feature steps aside and says why.
['topo.namespace', 'topo.blacklist'].each { prop ->
    def run = withFixtures(extendedFixtures) { discoverWith(statusAd, hostProps + [(prop): 'x']) }
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
def gateFails = withFixtures(['/cluster/status': null]) { discoverWith(statusAd, hostProps) }
note('guest-eri-gate-fails', gateFails.thrown == null && gateFails.exit == 0,
     "exit was ${gateFails.exit} / ${gateFails.thrown}")
note('guest-eri-gate-fails', gateFails.stdout == perfOff.stdout,
     "output differs from the shared guest discovery:\n${gateFails.stdout}")

// Only GuestStatus carries the keys, so each guest's key sits on one instance.
def perfOn = withFixtures(extendedFixtures) { discoverWith(perfAd, hostProps) }
note('guest-eri-perf', perfOn.exit == 0 &&
                       parseAd(perfOn.stdout).values().every { it.predef.isEmpty() },
     "GuestPerformance discovery carried instance ERIs:\n${perfOn.stdout}")

// ---------------------------------------------------- Cluster instance ERI
//
// Proxmox_VE_Cluster discovers one instance, the cluster, and on a cluster it carries the key the TopologySource draws the cluster vertex with (DESIGN section 7, spike
// 3 re-run). The wildvalue is fixed, so renaming the cluster orphans nothing.

def clusterAd = new File(scriptsDir, 'Proxmox_VE_Cluster.ad.groovy')
def clusterOff = discoverWith(clusterAd, hostProps)
def clusterOffAd = parseAd(clusterOff.stdout)
note('cluster-eri-default', clusterOff.thrown == null && clusterOff.exit == 0,
     "exit was ${clusterOff.exit} / ${clusterOff.thrown}: ${clusterOff.stderr}")
note('cluster-eri-default', clusterOffAd.keySet() == ['cluster'] as Set,
     "expected exactly the instance 'cluster', got ${clusterOffAd.keySet()}")
note('cluster-eri-default', clusterOff.stdout.startsWith('cluster##pve-cluster##'),
     "the instance is not named after the cluster: ${clusterOff.stdout}")
// On a cluster the key is there with no topology property set.
note('cluster-eri-default', clusterOffAd['cluster']?.predef?.get('predef.externalResourceID') == 'proxmoxve--pve-cluster',
     "no property set but the cluster carried no key: ${clusterOffAd['cluster']}")

def clusterOn = discoverWith(clusterAd, hostProps)
def clusterOnAd = parseAd(clusterOn.stdout)
note('cluster-eri-on', clusterOn.exit == 0 && clusterOnAd.keySet() == ['cluster'] as Set,
     "exit ${clusterOn.exit}, instances ${clusterOnAd.keySet()}: ${clusterOn.stderr}")
// Byte for byte the TopologySource's cluster vertex, pveTopoKey(cluster, null).
note('cluster-eri-on', clusterOnAd['cluster']?.predef?.get('predef.externalResourceID') == 'proxmoxve--pve-cluster',
     "cluster key was ${clusterOnAd['cluster']?.predef}")
// Cluster renders the cluster icon; VirtualCluster renders "??" (DESIGN section 7, spike 3).
note('cluster-eri-on', clusterOnAd['cluster']?.predef?.get('predef.externalResourceType') == 'Cluster',
     "cluster type was ${clusterOnAd['cluster']?.predef}")

// A standalone host has no cluster vertex to carry, but keeps its instance and its data.
def clusterStandalone = withFixtures(['/cluster/status': [[type: 'node', name: 'pve-a', local: 1, online: 1]]]) {
    discoverWith(clusterAd, hostProps)
}
def clusterStandaloneAd = parseAd(clusterStandalone.stdout)
note('cluster-eri-standalone', clusterStandalone.exit == 0 &&
                               clusterStandaloneAd.keySet() == ['cluster'] as Set &&
                               clusterStandaloneAd['cluster'].predef.isEmpty(),
     "standalone discovery was ${clusterStandalone.exit}: ${clusterStandalone.stdout}")
note('cluster-eri-standalone', clusterStandalone.stdout.startsWith('cluster##pve-a##'),
     "a standalone instance is not named after its node: ${clusterStandalone.stdout}")

['topo.namespace', 'topo.blacklist'].each { prop ->
    def run = discoverWith(clusterAd, hostProps + [(prop): 'x'])
    note('cluster-eri-namespace', run.exit == 0 && parseAd(run.stdout)['cluster']?.predef?.isEmpty() == true,
         "${prop} set but the cluster still carried keys: ${run.stdout}")
    note('cluster-eri-namespace', run.stderr.contains(prop), "${prop} set but stderr did not say so")
}

// Discovery that cannot reach the API exits 2, so LogicMonitor keeps the instance.
def clusterDown = withFixtures(['/cluster/status': null]) { discoverWith(clusterAd, hostProps) }
note('cluster-eri-down', clusterDown.exit == 2 && clusterDown.stdout.trim().isEmpty(),
     "API down: exit ${clusterDown.exit}, output ${clusterDown.stdout}")

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
    qemu    : pveGuestMac(['net0': 'virtio=BC:24:11:0E:00:02,bridge=vmbr0,firewall=1']),
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
note('topo-helpers', helper.qemu == 'bc:24:11:0e:00:02',
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

// The second node key needs no cluster name: the node's name plus an address it holds.
// The TopologySource takes both from /cluster/status; addERI_Proxmox_VE takes the name
// from the resource and the address from system.ips, which LogicMonitor writes with IPv6
// uncompressed. The two must meet byte for byte, or a token-less node is not drawn.
def ipKey = runSource(preamble + """
def rows = statusRows.findAll { it.type == 'node' }
def sysIps = [pve1: 'fe80:0:0:0:be24:11ff:fe00:1,192.168.10.11',
              pve2: 'fe80:0:0:0:be24:11ff:fe00:1, fd00:0:0:0:0:0:0:12']
return [
    ip       : ['192.0.2.211', ' 192.0.2.211 ', 'fd00::12', 'FD00:0:0:0:0:0:0:12',
                'fd00::12%vmbr0'].collectEntries { [(it): pveTopoIp(it)] },
    rejected : [null, '', '127.0.0.1', '::1', 'fe80::1', 'fe80:0:0:0:be24:11ff:fe00:1',
                '0.0.0.0', '999.1.1.1', 'pve1.example.com',
                // The container's own name resolves offline, through /etc/hosts, to a usable
                // address: only the literal-only gate keeps it out, so this pins that no name
                // is ever looked up.
                InetAddress.localHost.hostName].collectEntries { [(it): pveTopoIp(it)] },
    v4       : pveTopoNodeKey('pve1', '192.168.10.11'),
    fqdn     : pveTopoNodeKey('PVE1.example.com', '192.168.10.11'),
    v6       : pveTopoNodeKey('pve2', 'fd00::12'),
    missing  : [pveTopoNodeKey('pve2', null), pveTopoNodeKey(null, '192.168.10.11'),
                pveTopoNodeKey('', '192.168.10.11'), pveTopoNodeKey('pve2', 'fe80::1')],
    trips    : rows.collect { [it.name, pveTopoNodeKey(it.name, it.ip),
                               pveTopoNodeKeys(it.name, sysIps[it.name].tokenize(','))] },
    dedupe   : pveTopoNodeKeys('pve1', ['192.168.10.11', '192.168.10.11', '10.0.0.5']),
    vertex   : [pveTopoNodeVertexKey('pve-cluster', 'pve1', '192.168.10.11'),
                pveTopoNodeVertexKey('pve-cluster', 'pve2', null),
                pveTopoNodeVertexKey('pve-cluster', 'pve2', 'fe80::1')],
]
""", [hostProps: hostProps, statusRows: fixtures['/cluster/status']])
note('topo-ipkey', ipKey.thrown == null, "IP key helpers threw ${ipKey.thrown}")
def ik = ipKey.exit ?: [:]
note('topo-ipkey', ik.ip == ['192.0.2.211': '192.0.2.211', ' 192.0.2.211 ': '192.0.2.211',
                             'fd00::12': 'fd00:0:0:0:0:0:0:12',
                             'FD00:0:0:0:0:0:0:12': 'fd00:0:0:0:0:0:0:12',
                             'fd00::12%vmbr0': 'fd00:0:0:0:0:0:0:12'],
     "addresses did not normalise: ${ik.ip}")
// Loopback, link-local and wildcard name no reachable node; a hostname or a malformed
// quad must not reach InetAddress, which would resolve it through DNS.
note('topo-ipkey', ik.rejected && ik.rejected.values().every { it == null },
     "accepted an address that names no node: ${ik.rejected}")
note('topo-ipkey', ik.v4 == 'proxmoxve--node--pve1--192-168-10-11', "IPv4 node key was ${ik.v4}")
note('topo-ipkey', ik.fqdn == ik.v4, "an FQDN node name was not shortened: ${ik.fqdn}")
note('topo-ipkey', ik.v6 == 'proxmoxve--node--pve2--fd00-0-0-0-0-0-0-12', "IPv6 node key was ${ik.v6}")
note('topo-ipkey', ik.missing == [null, null, null, null], "keyed a missing part: ${ik.missing}")
note('topo-ipkey', ik.trips?.size() == 2 && ik.trips.every { name, vertex, eri ->
                       vertex != null && eri == [vertex] },
     "the vertex key and the ERI key built from system.ips differ: ${ik.trips}")
// One key per node vertex: lm.topo joins a vertex's keys into one ID ('a_b'), and the
// portal then matches only a resource carrying all of them, not any one (2026-10-21, a
// token-less node carrying only the IP key was left unmatched). The IP key when the
// /cluster/status row has a usable address, else the cluster-scoped key.
note('topo-ipkey', ik.vertex == ['proxmoxve--node--pve1--192-168-10-11',
                                 'proxmoxve--pve-cluster--pve2', 'proxmoxve--pve-cluster--pve2'],
     "node vertex keys were ${ik.vertex}")
note('topo-ipkey', ik.dedupe == ['proxmoxve--node--pve1--192-168-10-11',
                                 'proxmoxve--node--pve1--10-0-0-5'],
     "node keys were not deduplicated in order: ${ik.dedupe}")

// The node's own name, which the IP key needs, without the cluster name.
def nodeNameOf = { Map props ->
    def run = runSource(preamble + '\nreturn pveTopoNodeName()', [hostProps: props])
    note('topo-ipkey', run.thrown == null, "pveTopoNodeName threw ${run.thrown}")
    run.exit
}
note('topo-ipkey', nodeNameOf(['system.hostname': 'pve2.example.com']) == 'pve2',
     'a hostname without pve.topology.cluster did not name the node')
note('topo-ipkey', nodeNameOf(['system.hostname': '10.0.0.12']) == null,
     'an IP address was taken as a node name')
note('topo-ipkey', nodeNameOf(['pve.topology.node': 'pve4', 'system.sysname': 'pve3']) == 'pve4',
     'pve.topology.node did not override the derived name')
// An address there is the pvecm status mistake (DESIGN section 7, 2026-10-22): it would key the
// node as "192", so it is ignored and the derived name used instead.
note('topo-ipkey', nodeNameOf(['pve.topology.node': '192.0.2.211', 'system.sysname': 'Node-B']) == 'Node-B',
     'an IPv4 address in pve.topology.node was taken as the node name')
note('topo-ipkey', nodeNameOf(['pve.topology.node': 'fd00::12', 'system.sysname': 'Node-B']) == 'Node-B',
     'an IPv6 address in pve.topology.node was taken as the node name')

// The guest MAC prefix test and the cluster-name rule are shared by the TopologySource,
// the guest ERI PropertySource and the guest discovery, none of which can run here.
def guestMac = runSource(preamble + """
return [
    lower   : pveGuestMacMatch('bc:24:11:0e:00:01', null),
    upper   : pveGuestMacMatch('BC:24:11:0E:00:04', null),
    pi      : pveGuestMacMatch('88:a2:9e:0e:00:09', null),
    custom  : pveGuestMacMatch('02:00:00:12:34:56', ' BC:24:11 , 02:00:00'),
    notDflt : pveGuestMacMatch('bc:24:11:0e:00:01', '02:00:00'),
    blank   : pveGuestMacMatch('  ', null),
    none    : pveGuestMacMatch(null, null),
    cluster : pveTopoClusterName([[type: 'cluster', name: 'pve-cluster'], [type: 'node', name: 'pve1', local: 1]]),
    single  : pveTopoClusterName([[type: 'node', name: 'pve-a', local: 1]]),
    empty   : pveTopoClusterName([]),
]
""", [hostProps: hostProps])
note('guest-mac', guestMac.thrown == null, "guest MAC helpers threw ${guestMac.thrown}")
def gm = guestMac.exit ?: [:]
note('guest-mac', gm.lower == 'bc:24:11:0e:00:01', "lower MAC was ${gm.lower}")
note('guest-mac', gm.upper == 'bc:24:11:0e:00:04', "upper MAC was ${gm.upper}")
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
// The same for the key that needs no cluster name, which is what draws a token-less node.
note('addERI', eriScript.contains('pveTopoNodeKeys('),
     'the ERI PropertySource builds its name-plus-IP keys without pveTopoNodeKeys')
note('addERI', topoScript.contains('pveTopoNodeVertexKey('),
     'the TopologySource builds its node vertex key without pveTopoNodeVertexKey')
note('addERI', !(topoScript =~ /eri\(\[pveTopoKey\([^)]*\),\s*pveTopoNode/),
     'a node vertex carries two keys again; lm.topo joins them and the portal then matches neither alone')
note('addERI', !eriScript.contains('rawERIs'),
     'the ERI PropertySource hand-writes the rawERIs JSON instead of using emitEri')
note('addERI', eriScript.contains('lmtopo.emitEri(') &&
               eriScript.contains('lmtopo.printEriArray('),
     'the ERI PropertySource does not use the documented emitEri/printEriArray pair')

// Silence is the contract addCategory_Proxmox_VE also keeps: most resources in a portal
// are not Proxmox, and a PropertySource that prints on one it does not understand
// corrupts that resource's properties. Asserted by reading the guard, since the script
// cannot be executed here.
note('addERI', eriScript.contains('if (!name) {') && eriScript.contains('if (!keys) {') &&
               eriScript.contains('return 0'),
     'the ERI PropertySource does not return silently when it cannot name the node')
// The point of the property path: a node with no token still gets its key. If the script
// ever returns on pveConfigError again, only the token-holding node can be drawn.
note('addERI', eriScript.contains('pveTopoNodeIdentity()') &&
               !eriScript.contains('if (pveConfigError) {'),
     'the ERI PropertySource requires the API token again')

// The guest-labelling ERI PropertySource is Collector-only for the same reason, so its
// matching logic lives in pveGuestMacMatch (asserted above) and what is asserted here is
// the wiring. Priority 3 is the finding of spec spike 1: the resource type comes from
// the highest-priority ERI across categories, and the built-in net.l2 entry is 2, so
// anything lower leaves a guest drawn as PhysicalServer with nothing failing anywhere.
def guestEriScript = new File(ROOT, 'scripts/addERI_Proxmox_VE_Guest.groovy').text
def guestEriDefn = new JsonSlurper().parse(new File(ROOT, 'modules/addERI_Proxmox_VE_Guest.json'))

note('addERI-guest', guestEriScript.contains('pveGuestMacMatch('),
     'the guest ERI PropertySource matches the MAC without pveGuestMacMatch')
note('addERI-guest', guestEriScript.contains("lmtopo.emitEri('virt.proxmoxve', 3,") &&
                     guestEriScript.contains("'VirtualMachineResource'") &&
                     guestEriScript.contains('lmtopo.printEriArray('),
     'the guest ERI PropertySource does not emit virt.proxmoxve at priority 3 as VirtualMachineResource')
note('addERI-guest', !guestEriScript.contains('rawERIs'),
     'the guest ERI PropertySource hand-writes the rawERIs JSON instead of using emitEri')
// A guest has no token, so the preamble always sets pveConfigError: returning on it would
// make the module a no-op on every resource it applies to.
note('addERI-guest', !guestEriScript.contains('if (pveConfigError) {'),
     'the guest ERI PropertySource returns on pveConfigError, which every guest sets')
note('addERI-guest', guestEriScript.contains("pveHostProp('auto.network.mac_address')") &&
                     guestEriScript.contains("pveHostProp('pve.guest.mac_prefix')"),
     'the guest ERI PropertySource does not read the MAC and the prefix override')
note('addERI-guest', guestEriDefn.appliesTo ==
         '(auto.network.mac_address =~ "^[bB][cC]:24:11" || pve.guest.mac_prefix) && ' +
         '(isLinux() || isWindows())',
     "the guest ERI PropertySource AppliesTo is ${guestEriDefn.appliesTo}")
// Always on: labelling a guest correctly is not something an end user should opt into.
note('addERI-guest', !guestEriDefn.containsKey('optional'),
     'the guest ERI PropertySource is marked optional')

// Never optional: an ERI module cannot be turned off cleanly, because keys it stamped stay in
// predef.externalResourceID after it stops applying, and the portal refuses to delete them
// (DESIGN section 7). addERI_Proxmox_VE finds a node by the token, by pve.topology.node, or
// by a Proxmox kernel in system.sysinfo; an LXC guest reports its host's kernel, so the
// guest MAC prefix and its override keep containers out.
def topoDefn = new JsonSlurper().parse(new File(ROOT, 'modules/Proxmox_VE_Topology.json'))
def eriDefn = new JsonSlurper().parse(new File(ROOT, 'modules/addERI_Proxmox_VE.json'))
[topoDefn, eriDefn].each { defn ->
    note('topology-never-optional', !defn.containsKey('optional'),
         "${defn.name} is marked optional")
}
note('topology-applies-to', topoDefn.appliesTo == 'hasCategory("ProxmoxVE")',
     "the TopologySource AppliesTo is ${topoDefn.appliesTo}")
note('topology-applies-to', eriDefn.appliesTo ==
         'hasCategory("ProxmoxVE") || pve.topology.node || (system.sysinfo =~ "-pve" && ' +
         '!(auto.network.mac_address =~ "^[bB][cC]:24:11") && !pve.guest.mac_prefix)',
     "the node ERI PropertySource AppliesTo is ${eriDefn.appliesTo}")

// ------------------------------------------------------------------- Backups
//
// One instance per guest per job, grouped by job, plus one per guest no job covers. Last
// backups and failures come from vzdump run logs, read newest first until every job guest
// has a success, and matched to a job by the run's --storage.

def backupsAd = new File(scriptsDir, 'Proxmox_VE_Backups.ad.groovy')
def backupsCollect = new File(scriptsDir, 'Proxmox_VE_Backups.collect.groovy')
def backupLog = { int n -> String.format('/nodes/pve1/tasks/UPID:pve1:0000%04X:00F00D00:6AD0%04X:vzdump::root@pam:/log?start=0&limit=1000', n, n) }
def parseBatch = { String stdout ->
    def data = [:].withDefault { [:] }
    stdout.readLines().each { line ->
        def matcher = BATCH_LINE.matcher(line)
        if (matcher.matches()) { data[matcher.group(1)][matcher.group(2)] = matcher.group(3) as double }
    }
    data
}

def backupAdRun = discoverWith(backupsAd, hostProps)
def backupInstances = parseAd(backupAdRun.stdout)
note('backups-ad', backupAdRun.exit == 0 && backupInstances.keySet() ==
         ['backup-4e5f5bfc-094c-101', 'backup-4e5f5bfc-094c-103', 'backup-weekly-101',
          'backup-weekly-105', 'none-102', 'none-200'] as Set,
     "instances were ${backupInstances.keySet()}: ${backupAdRun.stderr}")
// Grouped by the job's comment, else its id; uncovered guests in their own group.
['backup-4e5f5bfc-094c-101': 'backup-4e5f5bfc-094c',
 'backup-weekly-105'       : 'Weekly to PBS',
 'none-102'                : 'No backup job'].each { id, group ->
    note('backups-ad', backupInstances[id]?.auto?.get('auto.pve_backup_job') == group,
         "${id} group was ${backupInstances[id]?.auto}")
}
// The guest's volumes, named: included ones by key and volume, excluded ones with the reason.
note('backups-ad', backupInstances['backup-4e5f5bfc-094c-103']?.auto?.get('auto.pve_backup_included_volumes') ==
                       'rootfs: local-lvm:vm-103-disk-0' &&
                   backupInstances['backup-4e5f5bfc-094c-103']?.auto?.get('auto.pve_backup_excluded_volumes') ==
                       'mp0: /mnt/data (not a volume)' &&
                   !backupInstances['backup-4e5f5bfc-094c-101']?.auto?.containsKey('auto.pve_backup_excluded_volumes'),
     "volume properties were ${backupInstances['backup-4e5f5bfc-094c-103']?.auto} / ${backupInstances['backup-4e5f5bfc-094c-101']?.auto}")
note('backups-ad', backupAdRun.stdout.contains('backup-4e5f5bfc-094c-101##web-01 (101)##'),
     'the display name is not "<guest> (<vmid>)"')
// Proxmox types a job's guests qemu / lxc (the lab's shape); its schema's VM / CT is accepted too.
note('backups-ad', backupInstances['backup-4e5f5bfc-094c-103']?.auto?.get('auto.pve_type') == 'lxc' &&
                   backupInstances['backup-weekly-105']?.auto?.get('auto.pve_type') == 'qemu',
     "guest types were ${backupInstances.collectEntries { k, v -> [(k): v.auto['auto.pve_type']] }}")

def backupRun = discoverWith(backupsCollect, hostProps)
def backupData = parseBatch(backupRun.stdout)
def nowHours = System.currentTimeMillis() / 3600000.0d
def ageNear = { Double hours, long start -> hours != null && Math.abs(hours - (nowHours - start / 3600.0d)) < 0.1 }
note('backups-collect', backupRun.exit == 0, "exit was ${backupRun.exit}: ${backupRun.stderr}")

// Last good backup from the daily run before the failed one; the failed run is newer.
def daily101 = backupData['backup-4e5f5bfc-094c-101']
note('backups-collect', daily101.LastBackupSizeGB == 2.15 && daily101.JobEnabled == 1 &&
                        daily101.FailedSinceLastBackup == 1 && daily101.NotCovered == 0 &&
                        daily101.IncludedVolumes == 1 && daily101.ExcludedVolumes == 0 &&
                        daily101.HoursUntilNextRun > 0 && ageNear(daily101.LastBackupAgeHours, 1792803602L),
     "daily 101 was ${daily101}")
// Succeeded inside the run that failed for 101: that run is its last good backup.
def daily103 = backupData['backup-4e5f5bfc-094c-103']
note('backups-collect', daily103.LastBackupSizeGB == 1.61 && daily103.FailedSinceLastBackup == 0 &&
                        daily103.IncludedVolumes == 1 && daily103.ExcludedVolumes == 1 &&
                        ageNear(daily103.LastBackupAgeHours, 1792865236L),
     "daily 103 was ${daily103}")
// The same guest in the disabled weekly job: its PBS run logged no size, and the daily
// failure (another storage) is not this job's.
def weekly101 = backupData['backup-weekly-101']
note('backups-collect', weekly101.JobEnabled == 0 && weekly101.FailedSinceLastBackup == 0 &&
                        !weekly101.containsKey('LastBackupSizeGB') && !weekly101.containsKey('HoursUntilNextRun') &&
                        ageNear(weekly101.LastBackupAgeHours, 1792461600L),
     "weekly 101 was ${weekly101}")
// A failure older than the guest's last good backup is over.
def weekly105 = backupData['backup-weekly-105']
note('backups-collect', weekly105.FailedSinceLastBackup == 0 && ageNear(weekly105.LastBackupAgeHours, 1792461600L),
     "weekly 105 was ${weekly105}")
note('backups-collect', backupData['none-102'] == [NotCovered: 1.0d],
     "an uncovered guest reported ${backupData['none-102']}")

// Newest first, each log read whole, and no further once every job guest has a success:
// the oldest run's log is never requested. No storage listing, no offline node.
def logReads = backupRun.requests.findAll { it.contains('/log') }
note('backups-calls', logReads == [backupLog(1), backupLog(2), backupLog(5), backupLog(3)],
     "unexpected log reads: ${logReads}")
note('backups-calls', !backupRun.requests.any { it.contains('/content') || it.startsWith('/nodes/pve2/') },
     "listed storage or asked an offline node: ${backupRun.requests}")

// A node whose runs cannot be read leaves the last-backup datapoints empty, not guessed.
def backupNoRuns = withFixtures(['/nodes/pve1/tasks?typefilter=vzdump&limit=50': null]) {
    discoverWith(backupsCollect, hostProps)
}
def unread = parseBatch(backupNoRuns.stdout)['backup-4e5f5bfc-094c-101']
note('backups-collect', backupNoRuns.exit == 0 && unread.JobEnabled == 1 &&
                        !unread.containsKey('LastBackupAgeHours') && !unread.containsKey('FailedSinceLastBackup'),
     "unread runs reported ${unread}")

// So does a run whose log cannot be read: collection still succeeds.
def backupNoLog = withFixtures([(backupLog(2)): null]) { discoverWith(backupsCollect, hostProps) }
def unreadLog = parseBatch(backupNoLog.stdout)['backup-4e5f5bfc-094c-101']
note('backups-collect', backupNoLog.exit == 0 && unreadLog.JobEnabled == 1 &&
                        !unreadLog.containsKey('LastBackupAgeHours') && backupNoLog.stderr.contains('not read'),
     "an unread log reported ${unreadLog}: ${backupNoLog.stderr}")

// Without not-backed-up (older Proxmox) the job instances are still discovered.
def backupNoInfo = withFixtures(['/cluster/backup-info/not-backed-up': null]) {
    discoverWith(backupsAd, hostProps)
}
note('backups-ad', backupNoInfo.exit == 0 && parseAd(backupNoInfo.stdout).keySet().every { !it.startsWith('none-') } &&
                   parseAd(backupNoInfo.stdout).size() == 4 && backupNoInfo.stderr.contains('not listed'),
     "without not-backed-up: ${backupNoInfo.exit} ${backupNoInfo.stdout} ${backupNoInfo.stderr}")

// No job list means no instances can be trusted: exit 2 keeps the existing ones.
def backupNoJobs = withFixtures(['/cluster/backup': null]) { discoverWith(backupsAd, hostProps) }
note('backups-ad', backupNoJobs.exit == 2, "discovery without /cluster/backup exited ${backupNoJobs.exit}")
def backupDead = runScript(backupsCollect, [hostProps: hostProps + ['pve.api.url': 'http://127.0.0.1:1']])
note('backups-collect', backupDead.exit == 2, "collection with the API down exited ${backupDead.exit}")

// ---------------------------------------------------------------- Sizes in names
//
// Storage and OSD instances carry their size in the display name, guests and nodes their root
// disk in the description, so a percentage graph reads against the size it is a share of.
def adRows = { String module ->
    discoverWith(new File(scriptsDir, module + '.ad.groovy'), hostProps).stdout.readLines()
        .collect { AD_LINE.matcher(it) }.findAll { it.matches() }
        .collect { [name: it.group(2), description: it.group(3)] }
}
def storageNames = adRows('Proxmox_VE_StorageCapacity')*.name
note('sizes', storageNames.contains('local on pve1 (107 GB)') && storageNames.contains('ceph-vm on pve1 (11.0 TB)') &&
              storageNames.contains('backup.nfs on pve2'),
     "storage names were ${storageNames}")
def osdNames = adRows('Proxmox_VE_CephOSD')*.name
// An OSD whose tree entry reports no size keeps its bare name.
note('sizes', osdNames.count { it ==~ /osd\.\d+ \(4\.0 TB\)/ } == 2 && osdNames.contains('osd.2'), "OSD names were ${osdNames}")
def guestDescriptions = adRows('Proxmox_VE_GuestStatus')*.description
note('sizes', guestDescriptions.any { it.endsWith(', root disk 54 GB') } && guestDescriptions.any { it.endsWith(', root disk 8.6 GB') },
     "guest descriptions were ${guestDescriptions}")
def nodeDescriptions = adRows('Proxmox_VE_NodeDetail')*.description + adRows('Proxmox_VE_Nodes')*.description
note('sizes', nodeDescriptions.count { it.endsWith('pve1, root filesystem 101 GB') } == 2 &&
              nodeDescriptions.any { it.endsWith('pve2') },
     "node descriptions were ${nodeDescriptions}")

// ---------------------------------------------------------------- Migrations
//
// Running migrations from /cluster/tasks; finished ones from each online node's vzmigrate and
// qmigrate history over the last hour. Times are relative to now, so they are built here.

def clusterCollect = new File(scriptsDir, 'Proxmox_VE_Cluster.collect.groovy')
long nowSec = System.currentTimeMillis().intdiv(1000L)
def migTask = { String type, String id, long ago, String status ->
    def task = [upid: "UPID:pve1:00000000:00000000:00000000:${type}:${id}:root@pam:".toString(), node: 'pve1',
                type: type, id: id, user: 'root@pam', starttime: nowSec - ago]
    if (status) { task.endtime = nowSec - ago + 60; task.status = status }
    task
}
def parsePlain = { String stdout ->
    stdout.readLines().collectEntries { line ->
        def matcher = PLAIN_LINE.matcher(line)
        matcher.matches() ? [(matcher.group(1)): matcher.group(2) as double] : [:]
    }
}
def migrationFixtures = [
    '/cluster/tasks': [migTask('vzmigrate', '108', 30, null), migTask('vzdump', '', 30, null),
                       migTask('qmigrate', '101', 200, 'OK')],
    '/nodes/pve1/tasks?typefilter=vzmigrate&limit=100': [migTask('vzmigrate', '108', 600, 'OK'),
                                                        migTask('vzmigrate', '109', 1200, 'migration aborted'),
                                                        migTask('vzmigrate', '110', 7200, 'migration aborted')],
    '/nodes/pve1/tasks?typefilter=qmigrate&limit=100': [migTask('qmigrate', '101', 300, 'OK')],
]
def migRun = withFixtures(migrationFixtures) { discoverWith(clusterCollect, hostProps) }
def migData = parsePlain(migRun.stdout)
// One running (no end time); two finished OK and one failed in the hour; one failure older.
note('migrations', migRun.exit == 0 && migData.MigrationsRunning == 1 &&
                   migData.MigrationsCompletedLastHour == 2 && migData.MigrationsFailedLastHour == 1,
     "migration counts were ${migData.subMap(['MigrationsRunning', 'MigrationsCompletedLastHour', 'MigrationsFailedLastHour'])}: ${migRun.stderr}")
note('migrations', migRun.stderr.contains('guest 109 from pve1 (migration aborted)') && !migRun.stderr.contains('guest 110'),
     "the failed guest was not named, or an old failure was: ${migRun.stderr}")
note('migrations', !migRun.requests.any { it.startsWith('/nodes/pve2/') },
     "asked an offline node: ${migRun.requests}")
// A node whose history cannot be read withholds the hourly counts; running is still reported.
def migPartial = withFixtures(migrationFixtures + ['/nodes/pve1/tasks?typefilter=qmigrate&limit=100': null]) {
    discoverWith(clusterCollect, hostProps)
}
def partialData = parsePlain(migPartial.stdout)
note('migrations', migPartial.exit == 0 && partialData.MigrationsRunning == 1 &&
                   !partialData.containsKey('MigrationsCompletedLastHour') && !partialData.containsKey('MigrationsFailedLastHour'),
     "an unread history reported ${partialData}")

// Guest Status: Migrating follows Proxmox's migrate lock, and only that lock.
def statusCollect = new File(scriptsDir, 'Proxmox_VE_GuestStatus.collect.groovy')
def migratingVms = fixtures['/cluster/resources?type=vm'].collect { guest ->
    guest.vmid == 101 ? guest + [lock: 'migrate'] : guest
}
def migratingRun = withFixtures(['/cluster/resources?type=vm': migratingVms]) { discoverWith(statusCollect, hostProps) }
def migrating = parseBatch(migratingRun.stdout)
note('migrations', migrating['qemu-101']?.Migrating == 1 && migrating['qemu-101']?.Locked == 1 &&
                   migrating['qemu-102']?.Migrating == 0 && migrating['qemu-102']?.Locked == 1,
     "Migrating was 101 ${migrating['qemu-101']} / 102 (backup lock) ${migrating['qemu-102']}")

// The entire justification for the BatchScript design.
note('call-efficiency', requested.contains('/cluster/resources?type=vm'),
     'guest collection did not use the bulk endpoint')
note('call-efficiency', !requested.any { it.contains('rrddata') },
     "collection used the redundant rrddata endpoint: ${requested}")

// ---------------------------------------------------------------- Snapshots
//
// One instance per guest. Proxmox's "current" entry is never counted, ages are relative to
// now, RAM snapshots are VM-only, and one unreadable list stops that node's reads this run.

def snapCollect = new File(scriptsDir, 'Proxmox_VE_Snapshots.collect.groovy')
long snapNow = System.currentTimeMillis().intdiv(1000L)
def snapCurrent = [description: 'You are here!', digest: 'abc', name: 'current', running: 1]
def snapAt = { String name, double daysAgo, Integer vmstate = null ->
    def entry = [description: '', name: name, snaptime: snapNow - (long) (daysAgo * 86400)]
    if (vmstate != null) { entry.vmstate = vmstate }
    entry
}
def snapGuests = fixtures['/cluster/resources?type=vm'] + [
    [id: 'lxc/103', type: 'lxc', vmid: 103, node: 'pve1', name: 'ct-snap', status: 'running', template: 0],
    [id: 'lxc/104', type: 'lxc', vmid: 104, node: 'pve1', name: 'ct-none', status: 'running', template: 0]]
def snapFixtures = [
    '/cluster/resources?type=vm'  : snapGuests,
    '/nodes/pve1/qemu/101/snapshot': [snapAt('newer', 2.0, 1), snapAt('older', 10.0, 1), snapAt('disk-only', 5.0, 0), snapCurrent],
    '/nodes/pve1/lxc/103/snapshot' : [snapAt('a', 3.0), snapCurrent, snapAt('b', 1.0)],
    '/nodes/pve1/lxc/104/snapshot' : [snapCurrent],
]
def snapRun = withFixtures(snapFixtures) { discoverWith(snapCollect, hostProps) }
def snap = parseBatch(snapRun.stdout)
note('snapshots', snapRun.exit == 0 && snap['qemu-101'].SnapshotCount == 3 && snap['qemu-101'].SnapshotsWithRAM == 2 &&
                  Math.abs(snap['qemu-101'].OldestSnapshotAgeDays - 10.0) < 0.02,
     "qemu-101 was ${snap['qemu-101']}: ${snapRun.stderr}")
note('snapshots', snap['lxc-103'].SnapshotCount == 2 && Math.abs(snap['lxc-103'].OldestSnapshotAgeDays - 3.0) < 0.02 &&
                  !snap['lxc-103'].containsKey('SnapshotsWithRAM'),
     "lxc-103 was ${snap['lxc-103']}")
// Only "current": a count of 0 and no age, never an age of 0.
note('snapshots', snap['lxc-104'] == [SnapshotCount: 0.0d], "lxc-104 was ${snap['lxc-104']}")
note('snapshots', !snapRun.requests.any { it.startsWith('/nodes/pve2/') || it.contains('/999/') },
     "asked an offline node's guest or a template: ${snapRun.requests}")
// One failure stops that node's reads: 101 fails first, so 103 and 104 are skipped.
def snapBroken = withFixtures(snapFixtures + ['/nodes/pve1/qemu/101/snapshot': null]) { discoverWith(snapCollect, hostProps) }
note('snapshots', snapBroken.exit == 0 && parseBatch(snapBroken.stdout).isEmpty() &&
                  !snapBroken.requests.any { it.contains('/lxc/10') } &&
                  snapBroken.stderr.readLines().count { it.contains('Snapshot reads stopped for node pve1') } == 1,
     "after a failure: ${snapBroken.stdout} / ${snapBroken.requests} / ${snapBroken.stderr}")
def snapDead = withFixtures(['/cluster/resources?type=vm': null]) { discoverWith(snapCollect, hostProps) }
note('snapshots', snapDead.exit == 2, "an unreadable guest list exited ${snapDead.exit}")

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
