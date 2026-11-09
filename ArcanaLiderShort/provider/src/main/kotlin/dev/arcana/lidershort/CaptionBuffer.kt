package dev.arcana.lidershort

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID

/** Online text buffer only; unknown/evicted routes never fall through to a publisher request. */
internal class CaptionBuffer {
    private val bodies = LinkedHashMap<String, ByteArray>()
    private var used = 0
    @Volatile private var closed = false
    private val marker = "arcana.lidershort." + UUID.randomUUID().toString()
    fun tagged(video: String) = "$video#$marker"
    fun owns(video: String) = video.substringAfter('#', "") == marker
    @Synchronized fun register(bytes: ByteArray): String {
        require(!closed && bytes.size <= 512_000)
        while (bodies.size >= 12 || used + bytes.size > 8 * 1024 * 1024) {
            val key = bodies.keys.first(); used -= bodies.remove(key)!!.size
        }
        val path = "/.arcana-lidershort/subtitles/" + UUID.randomUUID().toString() + ".vtt"
        bodies[path] = bytes.copyOf(); used += bytes.size
        return LiderShortContract.BASE + path
    }
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        val alias = url.encodedPath.startsWith("/.arcana-lidershort/subtitles/")
        if (alias) {
            val bytes = synchronized(this) {
                if (!closed && url.host == "www.lidershort.com" && url.scheme == "https" && url.port == 443 && url.encodedQuery == null)
                    bodies[url.encodedPath] else null
            }
            val allowedMethod = request.method in setOf("GET", "HEAD")
            val code = if (!allowedMethod) 405 else if (bytes == null) 410 else 200
            val body = if (code == 200 && request.method != "HEAD") bytes!! else byteArrayOf()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Unavailable")
                .header("Content-Type", "text/vtt; charset=utf-8").header("Cache-Control", "no-store")
                .header("Content-Length", if (code == 200) bytes!!.size.toString() else "0")
                .body(body.toResponseBody("text/vtt; charset=utf-8".toMediaType())).build()
        } else if (closed) {
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(410).message("Provider closed")
                .body(byteArrayOf().toResponseBody(null)).build()
        } else {
            // Only remove our own presentation tag; publisher path/query/signature stays unchanged.
            val outgoing = if (url.fragment == marker) request.newBuilder().url(url.newBuilder().fragment(null).build()).build() else request
            chain.proceed(outgoing)
        }
    }
    @Synchronized fun close() { closed = true; bodies.clear(); used = 0 }
    companion object {
        fun vtt(raw: String): ByteArray? {
            if (!LiderShortContract.timedText(raw)) return null
            val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").trimStart()
            val body = if (text.startsWith("WEBVTT")) text else "WEBVTT\n\n" + text.lines().joinToString("\n") { line ->
                if ("-->" in line) line.replace(',', '.') else line
            }
            return body.toByteArray(Charsets.UTF_8).takeIf { it.size <= 512_000 }
        }
    }
}
