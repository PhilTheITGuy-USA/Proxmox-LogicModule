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
def pveDiscover = { String id, String name, String description, Map properties ->
    def pairs = properties
        .findAll { key, value -> value != null && !value.toString().trim().isEmpty() }
        .collect { key, value -> 'auto.' + key + '=' + pvePropValue(value) }
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

def pvePercent = { used, total ->
    def totalValue = (total ?: 0) as double
    totalValue > 0 ? pveRound(((used ?: 0) as double) * 100.0d / totalValue) : 0
}
