import groovy.json.JsonSlurper
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

def getHostProperty = { String key ->
    hostProps.get(key)
}

def baseUrl = getHostProperty('pve.api.url')
def apiToken = getHostProperty('pve.api.token')
def timeout = (getHostProperty('pve.api.timeout') ?: '10000').toInteger()

if (!baseUrl || !apiToken) {
    System.err.println('Missing pve.api.url or pve.api.token')
    return 2
}

if ((getHostProperty('pve.api.insecure') ?: 'false').toBoolean()) {
    X509TrustManager trustManager = [
        getAcceptedIssuers: { -> new X509Certificate[0] },
        checkClientTrusted: { X509Certificate[] certs, String authType -> },
        checkServerTrusted: { X509Certificate[] certs, String authType -> }
    ] as X509TrustManager

    SSLContext sslContext = SSLContext.getInstance('TLS')
    sslContext.init(
        null,
        [trustManager] as TrustManager[],
        new java.security.SecureRandom()
    )

    HttpsURLConnection.setDefaultSSLSocketFactory(
        sslContext.getSocketFactory()
    )

    HttpsURLConnection.setDefaultHostnameVerifier(
        { String hostname, SSLSession session -> true } as HostnameVerifier
    )
}

try {
    def connection = new URL(
        baseUrl.replaceAll('/+$', '') + '/api2/json/nodes'
    ).openConnection()

    connection.setConnectTimeout(timeout)
    connection.setReadTimeout(timeout)
    connection.setRequestProperty(
        'Authorization',
        'PVEAPIToken=' + apiToken
    )
    connection.setRequestProperty('Accept', 'application/json')

    if (connection.responseCode < 200 ||
        connection.responseCode >= 300) {
        throw new IOException(
            'HTTP ' + connection.responseCode + ' from /nodes'
        )
    }

    def response = new JsonSlurper().parse(connection.inputStream)
    def nodes = response.data ?: []

    nodes.each { node ->
        String nodeName = node.node.toString()
        String safeNodeName = nodeName.replaceAll(
            '[^A-Za-z0-9_.-]',
            '_'
        )

        println(
            safeNodeName + '##' +
            safeNodeName + '##' +
            'Proxmox node####' +
            'auto.pve_node=' + safeNodeName
        )
    }

    return 0

} catch (Exception exception) {
    System.err.println(
        'Proxmox Node Active Discovery error: ' +
        exception.getMessage()
    )
    return 2
}
