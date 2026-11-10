package dev.arcana.anyshort

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Immutable link contexts, no credentials. Every admission observes current hold, expiry and cancellation. */
internal class AnyMedia(private val http:AnyHttp,private val captions:CaptionBuffer) {
    private data class Session(val url:HttpUrl,val referer:String,val ua:String,val until:Long) {
        @Volatile var accessStopped=false
        override fun toString()="MediaSession([redacted])"
    }
    private data class Active(val owner:Call,val session:Session)
    private val sessions=linkedMapOf<String,Session>()
    private val active=ConcurrentHashMap<Call,Active>()
    @Volatile private var closed=false
    private val client=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .connectTimeout(7,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).dns { AnyHttp.publicAddresses(it) }.build()
    private fun allowed(session:Session,owner:Call?=null)=!closed && !session.accessStopped && System.currentTimeMillis()<session.until && owner?.isCanceled()!=true && runCatching { http.permitted() }.isSuccess
    private fun admit(session:Session,owner:Call?=null) { if(!allowed(session,owner))throw IOException("Source media access stopped or expired; reopen episode") }
    private val monitor=ScheduledThreadPoolExecutor(1){task->Thread(task,"Arcana-Any-media-cancel").apply { isDaemon=true }}.apply {
        scheduleAtFixedRate({active.entries.forEach { (upstream,a)->if(!allowed(a.session,a.owner))upstream.cancel() }},100,100,TimeUnit.MILLISECONDS)
    }
    @Synchronized fun register(url:String,referer:String,ua:String,expiry:Long):String {
        http.permitted()
        if(closed || ua.isBlank() || expiry<=System.currentTimeMillis()+1000)throw IOException("Source media context unavailable")
        val target=checked(url);val token="arcana.anyshort."+UUID.randomUUID().toString();val now=System.currentTimeMillis()
        sessions.entries.removeAll { it.value.until<=now }
        while(sessions.size>=128)sessions.remove(sessions.keys.first())
        sessions[token]=Session(target,referer,ua,minOf(expiry,now+8*60*60*1000L))
        return target.newBuilder().fragment(token).build().toString()
    }
    fun interceptor(link:com.lagradost.cloudstream3.utils.ExtractorLink):Interceptor? {
        val marker=runCatching { link.url.toHttpUrl().fragment }.getOrNull() ?: return null
        if(!marker.startsWith("arcana.anyshort."))return null
        val session=synchronized(this){sessions[marker]}
        return Interceptor { chain->
            if(session==null)throw IOException("Source media context unavailable; reopen episode")
            admit(session,chain.call())
            if(link.url.toHttpUrl().newBuilder().fragment(null).build()!=session.url)throw IOException("Source media identity mismatch")
            val caption=captions.response(chain.request())
            if(caption!=null)caption.newBuilder().body(guardBody(caption.body,session,chain.call())).build() else transfer(chain,session,marker)
        }
    }
    private fun checked(raw:String):HttpUrl=AnyContract.mediaUrl(raw)?.toHttpUrl() ?: throw IOException("HTTPS publisher required")
    private fun guardBody(original:ResponseBody,session:Session,owner:Call,upstream:Call?=null):ResponseBody {
        val source=object:ForwardingSource(original.source()) {
            override fun read(sink:Buffer,byteCount:Long):Long {
                try { admit(session,owner);return super.read(sink,byteCount) }
                catch(e:Exception){upstream?.let { active.remove(it);it.cancel() };original.close();throw if(e is IOException)e else IOException("Source media read stopped")}
            }
            override fun close(){try{super.close()}finally{upstream?.let { active.remove(it);it.cancel() }}}
        }.buffer()
        return object:ResponseBody(){override fun contentType()=original.contentType();override fun contentLength()=original.contentLength();override fun source():BufferedSource=source}
    }
    private fun transfer(chain:Interceptor.Chain,session:Session,marker:String):Response {
        val incoming=chain.request();admit(session,chain.call())
        if(incoming.method !in setOf("GET","HEAD"))throw IOException("Read-only media request required")
        if(incoming.url.fragment?.startsWith("arcana.anyshort.")==true && incoming.url.fragment!=marker)throw IOException("Source media context mismatch")
        if(incoming.url.fragment==marker && incoming.url.newBuilder().fragment(null).build()!=session.url)throw IOException("Source media identity mismatch")
        var target=checked(incoming.url.newBuilder().fragment(null).build().toString())
        val range=incoming.header("Range")?.also { if(!it.matches(Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")))throw IOException("Invalid media range") }
        for(hop in 0..3) {
            admit(session,chain.call())
            val builder=Request.Builder().url(target).header("User-Agent",session.ua).header("Referer",session.referer).header("Accept-Encoding","identity")
            range?.let { builder.header("Range",it) };incoming.header("If-Range")?.takeIf { it.length<256 }?.let { builder.header("If-Range",it) }
            if(incoming.method=="HEAD")builder.head()
            val call=client.newCall(builder.build());active[call]=Active(chain.call(),session)
            var response:Response?=null
            try {
                admit(session,chain.call());response=call.execute();admit(session,chain.call())
                if(response.code==429){http.rateLimited(response.header("Retry-After"));throw IOException("Publisher media rate limited")}
                if(response.code in setOf(401,403)){session.accessStopped=true;throw IOException("Publisher media access denied")}
                if(response.code in setOf(301,302,303,307,308)) {
                    val next=response.header("Location")?.let { target.resolve(it) } ?: throw IOException("Publisher redirect unavailable")
                    response.close();active.remove(call);call.cancel()
                    if(hop==3)throw IOException("Publisher redirect limit")
                    target=checked(next.toString());admit(session,chain.call());continue
                }
                if(response.code !in setOf(200,206,416))throw IOException("Publisher media HTTP ${response.code}")
                if(incoming.method!="HEAD")http.checkChallenge(response.peekBody(16_384).string(),response.header("Retry-After"))
                admit(session,chain.call())
                return response.newBuilder().request(response.request.newBuilder().removeHeader("Cookie").removeHeader("Authorization").build())
                    .removeHeader("Set-Cookie").body(guardBody(response.body,session,chain.call(),call)).build()
            }catch(e:Exception){response?.close();active.remove(call);call.cancel();throw if(e is IOException)e else IOException("Source media request stopped")}
        }
        throw IOException("Publisher media unavailable")
    }
    @Synchronized fun close(){closed=true;active.keys.forEach { it.cancel() };active.clear();sessions.clear();monitor.shutdownNow();client.dispatcher.cancelAll();client.connectionPool.evictAll()}
}
