package dev.arcana.liderdrama

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.Closeable
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Standard Cloudstream interceptor transport. HTTPS endpoints remain HTTPS; opaque
 * fragments are client-only routing metadata and never change a publisher signature.
 * Only registered resources use the provider's scoped CookieJar. */
internal class LiderMedia(private val check: (String) -> HttpUrl, private val permitted: () -> Unit, private val denied: (Int, String?) -> Unit) : Interceptor, Closeable {
    private fun admit() {
        if(closed)throw IOException("Lider media transport closed")
        try { permitted() } catch(_: Exception) { throw IOException("Publisher access is stopped") }
    }
    private fun stop(code: Int,retry: String?): Nothing {
        try { denied(code,retry) } catch(_: Exception) { throw IOException("Publisher access is stopped") }
        throw IOException("Publisher access is stopped")
    }
    private data class Session(val client: OkHttpClient, val referer: String, val ua: String, val origin: String?, val created: Long = System.currentTimeMillis())
    private data class Node(val session: Session, val url: HttpUrl, val kind: String, val bytes: ByteArray? = null)
    private val random = SecureRandom()
    private val nodes = ConcurrentHashMap<String, Node>()
    private val active = ConcurrentHashMap<Call, Call>()
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed
    @Volatile private var touched = System.currentTimeMillis()
    private val keys = linkedMapOf<Triple<Session,String,String>, String>()
    private var textBytes = 0L
    private val textTokens = java.util.ArrayDeque<String>()
    private val monitor = ScheduledThreadPoolExecutor(1) { task -> Thread(task,"Arcana-HTTPS-cancel").apply { isDaemon=true } }.apply {
        scheduleAtFixedRate({
            active.entries.forEach { (upstream, owner) -> if(owner.isCanceled() || closed || runCatching { admit() }.isFailure)upstream.cancel() }
            if(active.isEmpty() && System.currentTimeMillis()-touched>15*60_000L)close()
        },100,100,TimeUnit.MILLISECONDS)
    }
    fun register(url: String, referer: String, client: OkHttpClient, kind: String, ua: String, origin: String? = null, bytes: ByteArray? = null): String =
        link(Session(client,referer,ua,origin),check(url),kind,bytes)
    @Synchronized private fun link(session: Session, url: HttpUrl, kind: String, bytes: ByteArray? = null): String {
        admit();if(closed)throw IOException("Media transport closed")
        val now=System.currentTimeMillis()
        val expired=nodes.filterValues { now-it.session.created>8*60*60*1000L }.keys
        expired.forEach(::remove)
        val key=Triple(session,url.toString(),kind)
        touched=now
        keys[key]?.takeIf { nodes.containsKey(it) }?.let { existing ->
            val ext=when(kind){"hls"->"m3u8";"vtt","srt","mp4"->kind;else->"bin"}
            return url.newBuilder().fragment("arcana.lider.$existing.$ext").build().toString()
        }
        if(bytes!=null){
            if(bytes.isEmpty() || bytes.size>3_000_000)throw IOException("Caption cache size")
            while(textTokens.size>=12 || textBytes+bytes.size>8_000_000)remove(textTokens.first())
        }
        if(nodes.size>=16384)throw IOException("Media transport capacity")
        val token=ByteArray(24).also(random::nextBytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        val ext=when(kind){"hls"->"m3u8";"vtt","srt","mp4"->kind;else->"bin"}
        nodes[token]=Node(session,url.newBuilder().fragment(null).build(),kind,bytes?.copyOf())
        keys[key]=token
        if(bytes!=null){textBytes+=bytes.size;textTokens.addLast(token)}
        return url.newBuilder().fragment("arcana.lider.$token.$ext").build().toString()
    }
    override fun intercept(chain: Interceptor.Chain): Response {
        admit();touched=System.currentTimeMillis()
        val incoming=chain.request()
        val fragment=incoming.url.fragment ?: return chain.proceed(incoming)
        if(!fragment.startsWith("arcana.lider."))return chain.proceed(incoming)
        val token=fragment.split('.').getOrNull(2) ?: throw IOException("Invalid media route")
        val node=nodes[token] ?: throw IOException("Unknown or expired media route")
        if(closed || System.currentTimeMillis()-node.session.created>8*60*60*1000L)throw IOException("Media session expired")
        if(incoming.url.newBuilder().fragment(null).build()!=node.url)throw IOException("Media identity mismatch")
        if(incoming.method !in listOf("GET","HEAD"))throw IOException("Unsupported media method")
        node.bytes?.let { return textResponse(incoming,it,if(node.kind=="srt")"application/x-subrip" else "text/vtt") }
        val caption=node.kind in listOf("vtt","srt")
        val builder=Request.Builder().url(node.url).header("User-Agent",node.session.ua).header("Accept-Encoding","identity")
        if(node.session.referer.isNotEmpty())builder.header("Referer",node.session.referer)
        node.session.origin?.let{builder.header("Origin",it)}
        if(!caption){
            incoming.header("Range")?.let { if(!it.matches(Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")))throw IOException("Invalid range");builder.header("Range",it) }
            incoming.header("If-Range")?.takeIf{it.length<256}?.let{builder.header("If-Range",it)}
            if(incoming.method=="HEAD")builder.head()
        }
        val call=node.session.client.newCall(builder.build())
        if(node.kind=="mp4"&&incoming.method=="GET") {
            // A progressive body remains open while the player buffers or pauses.
            // Keep API/caption/playlist/key/HEAD deadlines intact; this call may only
            // use the remainder of its existing eight-hour media-session lease.
            val remaining=8*60*60*1000L-(System.currentTimeMillis()-node.session.created)
            if(remaining<=0)throw IOException("Media session expired")
            call.timeout().timeout(remaining.coerceAtMost(8*60*60*1000L),TimeUnit.MILLISECONDS)
        }
        active[call]=chain.call()
        var response:Response?=null
        try {
            if(chain.call().isCanceled())throw IOException("Canceled")
            response=call.execute();if(response.code in listOf(401,403,429))stop(response.code,response.header("Retry-After"));admit()
            if(incoming.method != "HEAD" && LiderPolicy.inspectMediaDocument(response.code,node.kind,response.header("Content-Type"))) {
                val sample=response.peekBody(16384).string()
                LiderPolicy.bodyDenial(sample)?.let { stop(it,response.header("Retry-After") ?: LiderPolicy.retryHint(sample)) }
                if(LiderPolicy.errorDocument(sample))throw IOException("Publisher returned a non-media document")
            }
            if(response.code !in listOf(200,206,416))throw IOException("Publisher media HTTP ${response.code}")
            if(caption && response.code==200){
                val raw=bounded(response.body,3_000_000).toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\n','\r','\t')
                if(!raw.contains("-->"))throw IOException("Subtitle has no timed cues")
                val vtt=if(raw.startsWith("WEBVTT"))raw else {
                    if(!Regex("[0-9]{2}:[0-9]{2}:[0-9]{2},[0-9]{3}\\s*-->").containsMatchIn(raw))throw IOException("Unsupported caption format")
                    "WEBVTT\n\n"+raw.lineSequence().joinToString("\n"){line->if(line.contains("-->"))line.replace(Regex("(?<=[0-9]),(?=[0-9]{3}(?:\\s|$))"),".") else line}
                }
                return textResponse(incoming,vtt.toByteArray(Charsets.UTF_8),"text/vtt").also{response.close();active.remove(call)}
            }
            if(incoming.method!="HEAD" && response.code==200 && node.kind=="hls" && response.peekBody(16).string().trimStart('\uFEFF').startsWith("#EXTM3U")){
                val base=check(response.request.url.toString())
                fun route(value:String,kindHint:String?=null):String {
                    val target=base.resolve(value) ?: throw IOException("Invalid playlist URI")
                    return link(node.session,check(target.toString()),kindHint ?: if(target.encodedPath.endsWith(".m3u8"))"hls" else "bin")
                }
                val uri=Regex("URI=\"([^\"]+)\"")
                var variantNext=false
                val rewritten=bounded(response.body,2_000_000).toString(Charsets.UTF_8).lineSequence().joinToString("\n"){row->
                    when {
                        row.isBlank()->row
                        row.startsWith("#")-> {
                            if(row.startsWith("#EXT-X-STREAM-INF:"))variantNext=true
                            val kind=when {
                                row.startsWith("#EXT-X-KEY:") || row.startsWith("#EXT-X-SESSION-KEY:")->"key"
                                row.startsWith("#EXT-X-MEDIA:") || row.startsWith("#EXT-X-I-FRAME-STREAM-INF:") || row.startsWith("#EXT-X-RENDITION-REPORT:")->"hls"
                                else->null
                            }
                            uri.replace(row){"URI=\"${route(it.groupValues[1],kind)}\""}
                        }
                        else->{val kind=if(variantNext)"hls" else null;variantNext=false;route(row.trim(),kind)}
                    }
                }
                return textResponse(incoming,rewritten.toByteArray(Charsets.UTF_8),"application/vnd.apple.mpegurl").also{response.close();active.remove(call)}
            }
            val upstream=response
            val original=upstream.body
            val source=object:ForwardingSource(original.source()){
                override fun read(sink:Buffer,byteCount:Long):Long {
                    try {
                        admit();if(chain.call().isCanceled())throw IOException("Canceled")
                        touched=System.currentTimeMillis()
                        return super.read(sink,byteCount)
                    } catch(e: Exception) {
                        active.remove(call);call.cancel();original.close()
                        throw if(e is IOException)e else IOException("Publisher media read stopped")
                    }
                }
                override fun close(){try{super.close()}finally{active.remove(call);call.cancel()}}
            }.buffer()
            val body=object:ResponseBody(){
                override fun contentType()=original.contentType()
                override fun contentLength()=original.contentLength()
                override fun source():BufferedSource=source
            }
            return upstream.newBuilder().request(incoming).removeHeader("Set-Cookie").body(body).build()
        } catch(e:Exception){response?.close();active.remove(call);call.cancel();throw if(e is IOException)e else IOException("Publisher media request failed")}
    }
    private fun bounded(body:ResponseBody,limit:Long):ByteArray {
        if(body.contentLength()>limit)throw IOException("Media text too large")
        val source=body.source();source.request(limit+1)
        if(source.buffer.size>limit)throw IOException("Media text too large")
        return source.readByteArray()
    }
    private fun textResponse(request:Request,bytes:ByteArray,mime:String):Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .header("Content-Type",mime).header("Content-Length",bytes.size.toString()).header("Cache-Control","no-store")
        .body((if(request.method=="HEAD")ByteArray(0) else bytes).toResponseBody(mime.toMediaType())).build()
    @Synchronized private fun remove(token:String){
        textBytes-=nodes.remove(token)?.bytes?.size ?: 0
        textTokens.remove(token);keys.entries.removeAll{it.value==token}
    }
    @Synchronized override fun close(){closed=true;active.keys.forEach{it.cancel()};active.clear();monitor.shutdownNow();nodes.clear();keys.clear();textTokens.clear();textBytes=0}
}
