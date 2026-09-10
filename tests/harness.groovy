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
def server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
server.createContext('/api2/json') { exchange ->
    def uri = exchange.requestURI
    def key = uri.path.replaceFirst('^/api2/json', '') + (uri.query ? '?' + uri.query : '')
    requested << key

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
    'pve.api.token'  : TOKEN,
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
note('qemu-disk', !keysFor('qemu-101').contains('DiskUsedBytes'),
     'DiskUsedBytes was emitted for a QEMU guest, which reports no used-disk figure')
note('lxc-disk', keysFor('lxc-200').contains('DiskUsedBytes'),
     'DiskUsedBytes was not emitted for an LXC guest, which does report one')

// An unreachable API must fail loudly rather than report zeroes, so that LogicMonitor
// keeps existing instances instead of tearing them down during an outage.
def dead = runScript(new File(scriptsDir, 'Proxmox_VE_Nodes.collect.groovy'),
                     [hostProps: hostProps + ['pve.api.url': 'http://127.0.0.1:1']])
note('api-down', dead.exit == 2, "expected exit 2 when the API is unreachable, got ${dead.exit}")
note('api-down', dead.stdout.trim().isEmpty(), 'emitted datapoints while the API was unreachable')
note('api-down', dead.stderr.toLowerCase().contains('failed'), 'did not explain the failure on stderr')

// A rejected token must read as a permissions problem, not as an empty cluster.
def badAuth = runScript(guestAdFile, [hostProps: hostProps + ['pve.api.token': 'wrong@pam!bad=nope']])
note('api-auth', badAuth.exit == 2, "expected exit 2 on HTTP 401, got ${badAuth.exit}")
note('api-auth', badAuth.stderr.contains('token'), 'did not mention the token on stderr')

// Missing configuration must be a clean, explained failure rather than an exception.
def unconfigured = runScript(new File(scriptsDir, 'Proxmox_VE_Nodes.collect.groovy'),
                             [hostProps: ['system.hostname': 'pve.example.com']])
note('unconfigured', unconfigured.exit == 2, "expected exit 2 with no token, got ${unconfigured.exit}")
note('unconfigured', unconfigured.stderr.contains('pve.api.token'),
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

// A portal is mostly non-Proxmox resources. The PropertySource must stay silent on them
// rather than applying the whole suite to unrelated hosts.
def notProxmox = runScript(propertySource, [hostProps: ['system.hostname': 'some-webserver']])
note('propertysource', notProxmox.exit == 0,
     "exit was ${notProxmox.exit} on a non-Proxmox host, expected 0")
note('propertysource', notProxmox.stdout.trim().isEmpty(),
     "set properties on a host with no Proxmox credentials: ${notProxmox.stdout}")

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
