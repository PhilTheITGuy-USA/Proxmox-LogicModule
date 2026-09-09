import groovy.json.JsonSlurper

def getHostProperty = { String key -> hostProps.get(key) }
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

if (!baseUrl || !apiToken) { System.err.println('Missing pve.api.url or pve.api.token'); return 2 }

def apiGet = { String path ->
    def c = new URL(baseUrl.replaceAll('/+$', '') + '/api2/json' + path).openConnection()
    c.connectTimeout = timeout
    c.readTimeout = timeout
    c.setRequestProperty('Authorization', 'PVEAPIToken=' + apiToken)
    if (c.responseCode < 200 || c.responseCode >= 300) { throw new IOException('HTTP ' + c.responseCode + ' for ' + path) }
    return new JsonSlurper().parse(c.inputStream).data ?: []
}

def clean = { value -> value.toString().replaceAll('[^A-Za-z0-9_.-]', '_') }

try {
    def nodes = apiGet('/nodes')
    nodes.each { node ->
        String nodeName = node.node.toString()
        ['qemu', 'lxc'].each { String guestType ->
            apiGet('/nodes/' + nodeName + '/' + guestType).each { guest ->
                String vmid = guest.vmid.toString()
                String name = guest.name ?: guestType + '-' + vmid
                String instanceId = guestType + '__' + nodeName + '__' + vmid
                println clean(instanceId) + '##' + clean(name) + '##Proxmox ' + guestType + ' guest####auto.pve_node=' + clean(nodeName) + '&auto.pve_type=' + clean(guestType) + '&auto.pve_vmid=' + clean(vmid)
            }
        }
    }
    return 0
} catch (Exception e) {
    System.err.println('Proxmox API error: ' + e.message)
    return 2
}
