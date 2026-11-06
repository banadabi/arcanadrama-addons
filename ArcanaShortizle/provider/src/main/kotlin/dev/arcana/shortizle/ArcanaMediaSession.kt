package dev.arcana.shortizle

import okhttp3.*
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Private transport inside the CS3. Only opaque registered media nodes are served;
 * credentials stay in the upstream CookieJar. No host database, reflection or APK changes. */
internal class ArcanaMediaSession(private val check: (String) -> HttpUrl) : Closeable {
    private data class Session(val client: OkHttpClient, val referer: String, val created: Long = System.currentTimeMillis())
    private data class Node(val session: Session, val url: HttpUrl, val text: ByteArray? = null, val mime: String? = null)
    private class Transfer(val socket: Socket) {
        @Volatile var call: Call? = null
        @Volatile var touched = System.currentTimeMillis()
        fun close() { call?.cancel(); runCatching { socket.close() } }
    }
    private val random = SecureRandom()
    private val closed = AtomicBoolean(false)
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
    private val nodes = ConcurrentHashMap<String, Node>()
    private val keys = linkedMapOf<Pair<Session, String>, String>()
    private val textKeys=java.util.ArrayDeque<String>()
    private var textBytes=0L
    private val transfers = ConcurrentHashMap<Socket, Transfer>()
    private val threads = ThreadPoolExecutor(0, 6, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
        Thread(task, "Shortizle-media").apply { isDaemon = true }
    })
    private val monitor = ScheduledThreadPoolExecutor(1) { task -> Thread(task,"Shortizle-session").apply { isDaemon = true } }
    @Volatile private var touched = System.currentTimeMillis()
    val isClosed: Boolean get() = closed.get()

    init {
        monitor.scheduleAtFixedRate({
            val now = System.currentTimeMillis()
            transfers.values.filter { now-it.touched > 45_000 }.forEach { it.close() }
            if(transfers.isEmpty() && now-touched > 15*60*1000L) close()
        }, 10, 10, TimeUnit.SECONDS)
        Thread({
            while(!closed.get()) {
                val socket = try { listener.accept() } catch(_:java.net.SocketTimeoutException) { continue } catch(_:IOException) { break }
                val transfer=Transfer(socket); transfers[socket]=transfer
                try { threads.execute { try { serve(transfer) } catch(e:Exception) { android.util.Log.w("ArcanaShortizle","Media transfer: "+e.javaClass.simpleName) } finally {
                    transfer.close();transfers.remove(socket)
                } } } catch(_:java.util.concurrent.RejectedExecutionException) { transfer.close();transfers.remove(socket) }
            }
        }, "Shortizle-listener").apply { isDaemon=true;start() }
    }

    fun register(url: String, referer: String, client: OkHttpClient, kind: String): String {
        check(!closed.get()) { "Shortizle medya oturumu kapandı." }
        return link(Session(client,referer),check(url),if(kind=="hls")"m3u8" else "mp4")
    }
    private fun link(session: Session, url: HttpUrl, kind: String? = null, text: ByteArray? = null, mime: String? = null): String = synchronized(keys) {
        if(closed.get())throw IOException("Shortizle medya oturumu kapandı.")
        val key=session to url.toString()
        val token=keys[key] ?: run {
            if(nodes.size>=2048)throw IOException("Shortizle medya oturumu sınırı.")
            (ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }+"."+(kind ?: url.encodedPath.substringAfterLast('.',"bin").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,6}")) } ?: "bin")).also {
                keys[key]=it;nodes[it]=Node(session,url,text,mime)
            }
        }
        touched=System.currentTimeMillis()
        "http://127.0.0.1:${listener.localPort}/$token"
    }

    fun registerText(bytes:ByteArray,format:String,url:String,referer:String,client:OkHttpClient):String = synchronized(keys) {
        if(format !in listOf("vtt","srt") || bytes.isEmpty() || bytes.size>3_000_000)throw IOException("Subtitle format or size invalid")
        while(textKeys.size>=12 || textBytes+bytes.size>8_000_000) {
            val token=textKeys.removeFirst()
            textBytes-=nodes.remove(token)?.text?.size ?: 0
            keys.entries.removeAll { it.value==token }
        }
        val result=link(Session(client,referer),check(url),format,bytes.copyOf(),if(format=="vtt")"text/vtt; charset=utf-8" else "application/x-subrip; charset=utf-8")
        val token=result.substringAfterLast('/')
        if(!textKeys.contains(token)){textKeys.addLast(token);textBytes+=bytes.size}
        result
    }
    private fun sendText(transfer:Transfer,node:Node,method:String,range:String?) {
        val data=node.text ?: throw IOException("Subtitle cache missing")
        var first=0L;var last=data.lastIndex.toLong();var status=200
        if(range!=null) {
            val m=Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(range)
            if(m==null || (m.groupValues[1].isEmpty() && m.groupValues[2].isEmpty()))throw IOException("Subtitle range invalid")
            if(m.groupValues[1].isEmpty()) {
                val suffix=m.groupValues[2].toLongOrNull() ?: throw IOException("Subtitle range invalid")
                if(suffix<=0)throw IOException("Subtitle range invalid")
                first=(data.size.toLong()-suffix).coerceAtLeast(0)
            } else {
                first=m.groupValues[1].toLongOrNull() ?: throw IOException("Subtitle range invalid")
                if(m.groupValues[2].isNotEmpty())last=minOf(last,m.groupValues[2].toLongOrNull() ?: throw IOException("Subtitle range invalid"))
            }
            if(first>last) {
                transfer.socket.getOutputStream().write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${data.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray());return
            }
            status=206
        }
        val headers=StringBuilder("HTTP/1.1 $status OK\r\nContent-Type: ${node.mime}\r\nContent-Length: ${last-first+1}\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\n")
        if(status==206)headers.append("Content-Range: bytes $first-$last/${data.size}\r\n")
        headers.append("\r\n")
        transfer.socket.getOutputStream().apply {
            write(headers.toString().toByteArray())
            if(method=="GET")write(data,first.toInt(),(last-first+1).toInt())
        }
        touched=System.currentTimeMillis();transfer.touched=touched
    }

    private fun line(socket: Socket): String {
        val input=socket.getInputStream();val out=StringBuilder()
        while(out.length<4096) {
            val c=input.read();if(c<0)throw IOException("Incomplete request")
            if(c==10)return out.toString().removeSuffix("\r")
            out.append(c.toChar())
        }
        throw IOException("Request too long")
    }
    private fun serve(transfer: Transfer) {
        val socket=transfer.socket;socket.soTimeout=10_000
        val start=line(socket).split(' ')
        if(start.size!=3 || start[0] !in listOf("GET","HEAD") || start[2] !in listOf("HTTP/1.0","HTTP/1.1"))return
        val headers=linkedMapOf<String,String>();var size=0
        while(true) {
            val row=line(socket);if(row.isEmpty())break
            size+=row.length;if(size>16384)return
            val colon=row.indexOf(':');if(colon<=0)return
            val key=row.substring(0,colon).lowercase();if(headers.containsKey(key) && key in listOf("host","range","if-range","content-length","transfer-encoding"))throw IOException("Duplicate request control header")
            headers[key]=row.substring(colon+1).trim()
        }
        if(headers["host"]!="127.0.0.1:${listener.localPort}")throw IOException("Local host mismatch")
        if(headers.containsKey("transfer-encoding") || headers["content-length"]?.let { it!="0" }==true)throw IOException("Request body not supported")
        val token=start[1].removePrefix("/")
        if(!token.matches(Regex("[a-f0-9]{64}\\.[a-zA-Z0-9]{1,6}")))throw IOException("Invalid registered media route")
        val node=nodes[token] ?: throw IOException("Unknown media node")
        android.util.Log.d("ArcanaShortizle","Media request "+start[0]+" range="+headers.containsKey("range"))
        if(System.currentTimeMillis()-node.session.created>2*60*60*1000L)return
        if(node.text!=null){sendText(transfer,node,start[0],headers["range"]);return}
        val request=Request.Builder().url(node.url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Referer",node.session.referer).header("Origin","https://shortizle.com")
            .header("Accept-Encoding","identity")
        headers["range"]?.let { if(!it.matches(Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")))return;request.header("Range",it) }
        headers["if-range"]?.let { if(it.length<256)request.header("If-Range",it) }
        if(start[0]=="HEAD")request.head()
        val call=node.session.client.newCall(request.build());transfer.call=call
        call.execute().use { response ->
            touched=System.currentTimeMillis();transfer.touched=touched
            android.util.Log.d("ArcanaShortizle","Media upstream status="+response.code+" type="+response.header("Content-Type").orEmpty().substringBefore(';'))
            val output=socket.getOutputStream()
            if(response.code !in listOf(200,206,416)) {
                output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());return
            }
            val body=response.body
            val manifest=start[0]!="HEAD" && response.code==200 && response.peekBody(16).string().trimStart('\uFEFF').startsWith("#EXTM3U")
            if(manifest) {
                val input=body.byteStream();val data=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {
                    val n=input.read(buffer);if(n<0)break
                    if(data.size()+n>2_000_000)throw IOException("Playlist too large")
                    data.write(buffer,0,n);transfer.touched=System.currentTimeMillis()
                }
                val finalUrl=check(response.request.url.toString())
                fun rewrite(value:String):String {
                    val resolved=finalUrl.resolve(value) ?: throw IOException("Invalid media URI")
                    return link(node.session,check(resolved.toString()))
                }
                val uri=Regex("URI=\"([^\"]+)\"")
                val rewritten=data.toString("UTF-8").lineSequence().joinToString("\n") { row ->
                    when { row.isBlank()->row; row.startsWith("#")->uri.replace(row) { match -> "URI=\"${rewrite(match.groupValues[1])}\"" };else->rewrite(row.trim()) }
                }.toByteArray(Charsets.UTF_8)
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${rewritten.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                output.write(rewritten);return
            }
            val safe=StringBuilder("HTTP/1.1 ${response.code} OK\r\nConnection: close\r\nCache-Control: no-store\r\n")
            for(name in listOf("Content-Type","Content-Range","Accept-Ranges"))response.header(name)?.let { value ->
                if(value.length<=512 && '\r' !in value && '\n' !in value)safe.append(name).append(": ").append(value).append("\r\n")
            }
            val length=if(start[0]=="HEAD")response.header("Content-Length")?.toLongOrNull() ?: -1 else body.contentLength();if(length>=0)safe.append("Content-Length: ").append(length).append("\r\n")
            safe.append("\r\n");output.write(safe.toString().toByteArray())
            if(start[0]=="HEAD")return
            body.byteStream().use { input ->
                val buffer=ByteArray(32768)
                while(!closed.get()) {
                    val n=input.read(buffer);if(n<0)break
                    transfer.touched=System.currentTimeMillis();output.write(buffer,0,n)
                    touched=System.currentTimeMillis();transfer.touched=touched
                }
            }
        }
    }
    override fun close() {
        if(!closed.compareAndSet(false,true))return
        android.util.Log.d("ArcanaShortizle","Media session closed")
        runCatching { listener.close() };transfers.values.forEach { it.close() }
        threads.shutdownNow();monitor.shutdownNow();nodes.clear();synchronized(keys) { keys.clear();textKeys.clear();textBytes=0 }
    }
}
