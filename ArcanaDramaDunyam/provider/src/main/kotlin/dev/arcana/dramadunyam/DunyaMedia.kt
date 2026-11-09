package dev.arcana.dramadunyam

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Per-link immutable source context. Runtime routes/guest cookies remain in this process. */
internal class DunyaMedia(private val http: DunyaHttp,private val captions: CaptionBuffer) {
    private data class Session(val url: HttpUrl,val referer: String,val ua: String,val until: Long,val client: OkHttpClient) { @Volatile var accessStopped=false; override fun toString()="MediaSession([redacted])" }
    private val sessions=linkedMapOf<String,Session>()
    private val active=ConcurrentHashMap<Call,Call>()
    @Volatile private var closed=false
    private val base=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(7,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
        .dns { host -> DunyaHttp.publicAddresses(host) }.build()
    private val monitor=ScheduledThreadPoolExecutor(1) { task -> Thread(task,"Arcana-Dunya-media-cancel").apply { isDaemon=true } }.apply {
        scheduleAtFixedRate({ active.entries.forEach { (upstream,owner) -> if(closed || owner.isCanceled())upstream.cancel() } },100,100,TimeUnit.MILLISECONDS)
    }
    @Synchronized fun register(url: String,referer: String,ua: String,cookie: String,expiry: Long?): String {
        if(closed || ua.isBlank())throw IOException("Source media context unavailable")
        val target=checked(url)
        val token="arcana.dramadunyam."+UUID.randomUUID().toString()
        val source=(DunyaContract.BASE+"/").toHttpUrl()
        val jar=object:CookieJar {
            private val values=ConcurrentHashMap<String,Cookie>().apply {
                cookie.split(';').mapNotNull { Cookie.parse(source,it.trim()+"; Path=/; Secure") }.forEach { put(it.name+"|"+it.path,it) }
            }
            override fun loadForRequest(url: HttpUrl):List<Cookie> = if(url.isHttps && url.host=="dramadunyam.com")
                values.values.filter { it.matches(url) && it.expiresAt>System.currentTimeMillis() } else emptyList()
            override fun saveFromResponse(url: HttpUrl,cookies: List<Cookie>) {
                if(!url.isHttps || url.host!="dramadunyam.com")return
                cookies.filter { it.matches(url) && it.domain=="dramadunyam.com" }.forEach { values[it.name+"|"+it.path]=it }
            }
        }
        val now=System.currentTimeMillis()
        sessions.entries.removeAll { it.value.until<=now }
        while(sessions.size>=128)sessions.remove(sessions.keys.first())
        sessions[token]=Session(target,referer,ua,minOf(expiry ?: Long.MAX_VALUE,now+8*60*60*1000L),base.newBuilder().cookieJar(jar).build())
        return target.newBuilder().fragment(token).build().toString()
    }
    fun interceptor(link: com.lagradost.cloudstream3.utils.ExtractorLink): Interceptor? {
        val marker=runCatching { link.url.toHttpUrl().fragment }.getOrNull() ?: return null
        if(!marker.startsWith("arcana.dramadunyam."))return null
        val session=synchronized(this) { sessions[marker] }
        return Interceptor { chain ->
            if(closed || session==null || session.accessStopped || System.currentTimeMillis()>=session.until)throw IOException("Source media route expired; reopen episode")
            if(link.url.toHttpUrl().newBuilder().fragment(null).build()!=session.url)throw IOException("Source media identity mismatch")
            captions.response(chain.request()) ?: transfer(chain,session,marker)
        }
    }
    private fun checked(raw: String):HttpUrl = (DunyaContract.https(raw)?.toHttpUrl() ?: throw IOException("HTTPS publisher required")).also {
    }
    private fun transfer(chain: Interceptor.Chain,session: Session,marker: String):Response {
        val incoming=chain.request()
        if(incoming.method !in setOf("GET","HEAD"))throw IOException("Read-only media request required")
        if(incoming.url.fragment?.startsWith("arcana.dramadunyam.")==true && incoming.url.fragment!=marker)throw IOException("Source media context mismatch")
        if(incoming.url.fragment==marker && incoming.url.newBuilder().fragment(null).build()!=session.url)throw IOException("Source media identity mismatch")
        var target=checked(incoming.url.newBuilder().fragment(null).build().toString())
        val range=incoming.header("Range")?.also { if(!it.matches(Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")))throw IOException("Invalid media range") }
        for(redirect in 0..3) {
            http.permitted()
            if(closed || chain.call().isCanceled())throw IOException("Canceled")
            if(session.accessStopped)throw IOException("Source media access stopped")
            val builder=Request.Builder().url(target).header("User-Agent",session.ua).header("Referer",session.referer).header("Accept-Encoding","identity")
            range?.let { builder.header("Range",it) }
            incoming.header("If-Range")?.takeIf { it.length<256 }?.let { builder.header("If-Range",it) }
            if(incoming.method=="HEAD")builder.head()
            val call=session.client.newCall(builder.build());active[call]=chain.call()
            var response:Response?=null
            try {
                response=call.execute()
                if(response.code==429) { http.rateLimited(response.header("Retry-After"));http.permitted() }
                if(response.code in setOf(301,302,303,307,308)) {
                    val next=response.header("Location")?.let { target.resolve(it) } ?: throw IOException("Publisher redirect unavailable")
                    response.close();active.remove(call);call.cancel()
                    if(redirect==3)throw IOException("Publisher redirect limit")
                    target=checked(next.toString());continue
                }
                if(response.code in setOf(401,403))session.accessStopped=true
                if(response.code !in setOf(200,206,416))throw IOException("Publisher media HTTP ${response.code}")
                if(incoming.method!="HEAD") {
                    val peek=response.peekBody(16_384).string().trimStart('\uFEFF',' ','\r','\n','\t')
                    http.checkChallenge(peek)
                }
                if(incoming.method!="HEAD" && DunyaCipher.hasMagic(response.peekBody(8).bytes())) {
                    if(range!=null && range!="bytes=0-")throw IOException("Encrypted fragment range unavailable")
                    val body=response.body
                    if(body.contentLength()>16*1024*1024)throw IOException("Fragment size limit")
                    val source=body.source();source.request(16*1024*1024L+1)
                    if(source.buffer.size>16*1024*1024L)throw IOException("Fragment size limit")
                    val decoded=DunyaCipher.decode(source.readByteArray()) ?: throw IOException("Encrypted fragment unavailable")
                    response.close();active.remove(call);call.cancel()
                    return Response.Builder().request(incoming).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Type","video/mp2t").header("Content-Length",decoded.size.toString()).header("Cache-Control","no-store")
                        .body(decoded.toResponseBody("video/mp2t".toMediaType())).build()
                }
                val upstream=response
                val original=upstream.body
                val source=object:ForwardingSource(original.source()) {
                    override fun read(sink: Buffer,byteCount: Long):Long {
                        if(closed || chain.call().isCanceled()) { call.cancel();throw IOException("Canceled") }
                        return try { super.read(sink,byteCount) } catch(e:IOException) { active.remove(call);throw e }
                    }
                    override fun close() { try { super.close() } finally { active.remove(call);call.cancel() } }
                }.buffer()
                val body=object:ResponseBody() {
                    override fun contentType()=original.contentType()
                    override fun contentLength()=original.contentLength()
                    override fun source():BufferedSource=source
                }
                // Preserve the final response URI: relative HLS children must resolve after any real redirect.
                return upstream.newBuilder().request(upstream.request.newBuilder().removeHeader("Cookie").removeHeader("Authorization").build())
                    .removeHeader("Set-Cookie").body(body).build()
            } catch(e:Exception) { response?.close();active.remove(call);call.cancel();throw e }
        }
        throw IOException("Publisher media unavailable")
    }
    @Synchronized fun close() {
        closed=true;active.keys.forEach { it.cancel() };active.clear();sessions.clear();monitor.shutdownNow()
        base.dispatcher.cancelAll();base.connectionPool.evictAll()
    }
}
