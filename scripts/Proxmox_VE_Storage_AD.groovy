import groovy.json.JsonSlurper
if(hostProps.get('pve.api.insecure')?.toString()?.toBoolean()){def tm=[getAcceptedIssuers:{->[] as java.security.cert.X509Certificate[]},checkClientTrusted:{c,a->},checkServerTrusted:{c,a->}] as javax.net.ssl.X509TrustManager;
def sc=javax.net.ssl.SSLContext.getInstance('TLS');
sc.init(null,[tm] as javax.net.ssl.TrustManager[],new java.security.SecureRandom());
javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory);
javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier({h,s->true} as javax.net.ssl.HostnameVerifier)}
def p = { String k, String d = null ->hostProps.get(k)?:d};
def b=p('pve.api.url');
def a=p('pve.api.token');
def t=(p('pve.api.timeout','10000') as int);
if(!b||!a){System.err.println('Missing pve.api.url or pve.api.token');
return 2};
def g = { String q ->def c=new URL(b.replaceAll('/+$','')+'/api2/json'+q).openConnection();
c.connectTimeout=t;
c.readTimeout=t;
c.setRequestProperty('Authorization','PVEAPIToken='+a);
if(c.responseCode<200||c.responseCode>=300)throw new IOException('HTTP '+c.responseCode);
new JsonSlurper().parse(c.inputStream).data};
def cl = { value -> value.toString().replaceAll('[^A-Za-z0-9_.-]','_')};
try{g('/nodes').each{n->def nn=n.node.toString();
g('/nodes/'+nn+'/storage').each{s->def sn=s.storage.toString();
println cl(nn+'__'+sn)+'##'+cl(sn)+'##Proxmox storage on '+cl(nn)+'####auto.pve_storage='+cl(sn)+'&auto.pve_node='+cl(nn)}};
return 0}catch(e){System.err.println('Proxmox API error: '+e.message);
return 2}
