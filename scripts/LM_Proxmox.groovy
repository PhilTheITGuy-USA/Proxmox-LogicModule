import groovy.json.JsonSlurper
import javax.net.ssl.*
import java.security.cert.X509Certificate

def prop = { String k, String d=null -> hostProps.get(k) ?: d }
def mode = args ? args[0] : prop('pve.mode','node')
def base = prop('pve.api.url')
def token = prop('pve.api.token')
if (!base || !token) { System.err.println('Missing pve.api.url or pve.api.token'); return 2 }
def timeout = (prop('pve.api.timeout','10000') as int)

if (prop('pve.api.insecure','false').toBoolean()) {
  def trust = [getAcceptedIssuers:{null}, checkClientTrusted:{c,a}, checkServerTrusted:{c,a}] as X509TrustManager
  def ctx = SSLContext.getInstance('TLS'); ctx.init(null,[trust] as TrustManager[],new java.security.SecureRandom())
  HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
  HttpsURLConnection.setDefaultHostnameVerifier({ h,s -> true } as HostnameVerifier)
}
def get = { String path ->
  def u = new URL(base.replaceAll('/+$','') + '/api2/json' + path)
  def c = u.openConnection() as HttpURLConnection
  c.connectTimeout=timeout; c.readTimeout=timeout; c.setRequestProperty('Authorization','PVEAPIToken-'+token)
  if (c.responseCode < 200 || c.responseCode >= 300) throw new IOException('HTTP '+c.responseCode+' '+path)
  new JsonSlurper().parse(c.inputStream).data
}
def clean = { v -> (v == null ? '' : v.toString()).replaceAll('[^A-Za-z0-9_.-]','_') }
def ad = { id, name, desc, props -> println(clean(id)+'##'+clean(name)+'##'+clean(desc)+'####'+props.collect{k,v -> 'auto.'+k+'='+clean(v)}.join('&') }
try {
  if (mode=='node' && args.size()>1 && args[1]=='discover') {
    get('/nodes').each { n -> ad(n.node,n.node,'Proxmox node',[pve_node:n.node]) }; return 0
  }
  if (mode=='guest' && args.size()>1 && args[1]=='discover') {
    get('/cluster/resources?type=vm').findAll{it.type in ['qemu','lxc']}.each { g -> ad(g.vmid.toString(),g.name ?: "${g.type}-${g.vmid}",g.type,[pve_node:g.node,pve_type:g.type,pve_vmid:g.vmid]) }; return 0
  }
  if (mode=='storage' && args.size()>1 && args[1]=='discover') {
    def nodes=get('/nodes'); def seen=[:] ; nodes.each{ n -> get('/nodes/'+n.node+'/storage').each{s -> seen[s.storage]=s} }; seen.each{k,s->ad(k,k,'Proxmox storage',[pve_storage:k])}; return 0
  }
  if (mode=='cluster') {
    def c=get('/cluster/status'); def qu=c.find{it.type=='cluster'} ?: [:]
    println 'quorate='+(qu.quorate ?: 0); println 'votes='+(qu.votes ?: 0); println 'expectedVotes='+(qu.expected_votes ?: 0); return 0
  }
  if (mode=='node') {
    def n=prop('pve_node'); if(!n) throw new IOException('pve_node instance property missing')
    def s=get('/nodes/'+n+'/status'); def r=get('/nodes/'+n+'/rrddata?timeframe=hour&cf=AVERAGE'); def x=r ? r[-1] : [:]
    println 'up=1'; println 'cpuPct='+(s.cpu ? s.cpu*100 : 0); println 'load1='+(s.loadavg?.getAt(0) ?: 0)
    println 'memUsedBytes='+(s.memory?.used ?: 0); println 'memTotalBytes='+(s.memory?.total ?: 0); println 'memUsedPct='+(s.memory?.total ? s.memory.used*100/s.memory.total : 0)
    println 'swapUsedPct='+(s.swap?.total ? s.swap.used*100/s.swap.total : 0); println 'uptimeSeconds='+(s.uptime ?: 0)
    println 'rootUsedBytes='+(s.rootfs?.used ?: 0); println 'rootTotalBytes='+(s.rootfs?.total ?: 0); println 'rootUsedPct='+(s.rootfs?.total ? s.rootfs.used*100/s.rootfs.total : 0)
    println 'netInBytes='+(x.netin ?: 0); println 'netOutBytes='+(x.netout ?: 0); return 0
  }
  if (mode=='guest') {
    def n=prop('pve_node'), type=prop('pve_type'), id=prop('pve_vmid'); def g=get('/nodes/'+n+'/'+type+'/'+id+'/status'); def r=get('/nodes/'+n+'/'+type+'/'+id+'/rrddata?timeframe=hour&cf=AVERAGE'); def x=r?r[-1]:[:]
    println 'up='+(g.status=='running'?1:0); println 'status='+(g.status=='running'?1:0); println 'cpuPct='+(g.cpu?g.cpu*100:0); println 'memUsedBytes='+(g.mem?:0); println 'memMaxBytes='+(g.maxmem?:0); println 'memUsedPct='+(g.maxmem?g.mem*100/g.maxmem:0); println 'diskUsedBytes='+(g.disk?:0); println 'diskMaxBytes='+(g.maxdisk?:0); println 'netInBytes='+(x.netin?:0); println 'netOutBytes='+(x.netout?:0); println 'uptimeSeconds='+(g.uptime?:0); return 0
  }
  if (mode=='storage') { def n=prop('pve_node'), st=prop('pve_storage'); def s=get('/nodes/'+n+'/storage/'+st+'/status'); println 'up=1'; println 'usedBytes='+(s.used?:0); println 'availableBytes='+(s.avail?:0); println 'totalBytes='+(s.total?:0); println 'usedPct='+(s.total?s.used*100/s.total:0); return 0 }
  throw new IOException('Unknown mode '+mode)
} catch(Exception e) { System.err.println('Proxmox API error: '+e.message); return 2 }
