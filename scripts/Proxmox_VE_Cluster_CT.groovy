import groovy.json.JsonSlurper
if(hostProps.get('pve.api.insecure')?.toString()?.toBoolean()){def tm=[getAcceptedIssuers:{->[] as java.security.cert.X509Certificate[]},checkClientTrusted:{c,a->},checkServerTrusted:{c,a->}] as javax.net.ssl.X509TrustManager;
def sc=javax.net.ssl.SSLContext.getInstance('TLS');
sc.init(null,[tm] as javax.net.ssl.TrustManager[],new java.security.SecureRandom());
javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory);
javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier({h,s->true} as javax.net.ssl.HostnameVerifier)}
def p = { String k, String d = null ->hostProps.get(k)?:d};
def b=p('pve.api.url');
def a=p('pve.api.token');
def t=(p('pve.api.timeout','10000') as int)
if(!b||!a){System.err.println('Missing pve.api.url or pve.api.token');
return 2}
try{def c=new URL(b.replaceAll('/+$','')+'/api2/json/cluster/status').openConnection();
c.connectTimeout=t;
c.readTimeout=t;
c.setRequestProperty('Authorization','PVEAPIToken='+a);
if(c.responseCode<200||c.responseCode>=300)throw new IOException('HTTP '+c.responseCode);
def x=new JsonSlurper().parse(c.inputStream).data.find{x->x.type=='cluster'}?:[:];
println 'quorate='+(x.quorate?:0);
println 'votes='+(x.votes?:0);
println 'expectedVotes='+(x.expected_votes?:0);
return 0}catch(e){System.err.println('Proxmox API error: '+e.message);
return 2}
