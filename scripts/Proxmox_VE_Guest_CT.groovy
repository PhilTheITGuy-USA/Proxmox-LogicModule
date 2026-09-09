import groovy.json.JsonSlurper

def getHostProperty = { String key ->
    hostProps.get(key)
}

def getInstanceProperty = { String key ->
    if (taskProps != null && taskProps.get(key) != null) {
        return taskProps.get(key)
    }
    if (taskProps != null && taskProps.get('auto.' + key) != null) {
        return taskProps.get('auto.' + key)
    }
    if (instanceProps != null && instanceProps.get(key) != null) {
        return instanceProps.get(key)
    }
    return instanceProps != null ? instanceProps.get('auto.' + key) : null
}

def baseUrl = getHostProperty('pve.api.url')
def apiToken = getHostProperty('pve.api.token')
def timeout = (getHostProperty('pve.api.timeout') ?: '10000').toInteger()

if ((getHostProperty('pve.api.insecure') ?: 'false').toBoolean()) {
    def trustManager = [
        getAcceptedIssuers: { -> new java.security.cert.X509Certificate[0] },
        checkClientTrusted: { java.security.cert.X509Certificate[] c, String a -> },
        checkServerTrusted: { java.security.cert.X509Certificate[] c, String a -> }
    ] as javax.net.ssl.X509TrustManager
    def sslContext = javax.net.ssl.SSLContext.getInstance('TLS')
    sslContext.init(null, [trustManager] as javax.net.ssl.TrustManager[], new java.security.SecureRandom())
    javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
    javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier({ h, s -> true } as javax.net.ssl.HostnameVerifier)
}

if (!baseUrl || !apiToken) {
    System.err.println('Missing pve.api.url or pve.api.token')
    return 2
}

def apiGet = { String path ->
    def connection = new URL(baseUrl.replaceAll('/+$', '') + '/api2/json' + path).openConnection()
    connection.connectTimeout = timeout
    connection.readTimeout = timeout
    connection.setRequestProperty('Authorization', 'PVEAPIToken=' + apiToken)
    if (connection.responseCode < 200 || connection.responseCode >= 300) {
        throw new IOException('HTTP ' + connection.responseCode + ' for ' + path)
    }
    return new JsonSlurper().parse(connection.inputStream).data ?: [:]
}

try {
    def nodeName = getInstanceProperty('pve_node')
    def guestType = getInstanceProperty('pve_type')
    def vmid = getInstanceProperty('pve_vmid')

    if (!nodeName || !guestType || !vmid) {
        throw new IOException('Missing guest instance properties: pve_node, pve_type, pve_vmid')
    }

    def status = apiGet('/nodes/' + nodeName + '/' + guestType + '/' + vmid + '/status/current')
    def rrd = apiGet('/nodes/' + nodeName + '/' + guestType + '/' + vmid + '/rrddata?timeframe=hour&cf=AVERAGE')
    def latest = rrd instanceof List && !rrd.isEmpty() ? rrd[-1] : [:]
    def running = status.status?.toString() == 'running'

    println 'up=' + (running ? 1 : 0)
    println 'status=' + (running ? 1 : 0)
    println 'cpuPct=' + (status.cpu ? status.cpu * 100 : 0)
    println 'memUsedBytes=' + (status.mem ?: 0)
    println 'memMaxBytes=' + (status.maxmem ?: 0)
    println 'memUsedPct=' + (status.maxmem ? status.mem * 100 / status.maxmem : 0)
    println 'diskUsedBytes=' + (status.disk ?: 0)
    println 'diskMaxBytes=' + (status.maxdisk ?: 0)
    println 'netInBytes=' + (latest.netin ?: 0)
    println 'netOutBytes=' + (latest.netout ?: 0)
    println 'uptimeSeconds=' + (status.uptime ?: 0)

    return 0
} catch (Exception exception) {
    System.err.println('Proxmox Guest Collection error: ' + exception.message)
    return 2
}
