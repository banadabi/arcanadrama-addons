package dev.arcana.lidershort

import android.content.Context
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Normal first-party WebView session only; no Chrome profile import or raw response logging. */
internal class LiderShortHttp(context: Context, private val session: LiderShortSession) {
    private val limits = context.applicationContext.getSharedPreferences("arcana_lidershort_network", Context.MODE_PRIVATE)
    private val base = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .dns { host -> Dns.SYSTEM.lookup(host).also { if (it.isEmpty() || it.any(::privateAddress)) throw IOException("Public host required") } }
        .build()
    private val media = base.newBuilder().cookieJar(CookieJar.NO_COOKIES).callTimeout(5, TimeUnit.SECONDS).build()
    @Volatile private var denied = false
    @Volatile private var closed = false
    private fun permitted() {
        if (closed) throw ErrorLoadingException("LiderShort eklentisi kapalı.")
        if (denied) throw ErrorLoadingException("LiderShort kaynak erişimini kısıtladı.")
        if (System.currentTimeMillis() < limits.getLong("retry_until_ms", 0L))
            throw ErrorLoadingException("LiderShort istek sınırı bildirdi; daha sonra deneyin.")
    }
    private suspend fun <T> call(client: OkHttpClient, request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { c ->
        try { permitted() } catch (e: Exception) { c.resumeWithException(e); return@suspendCancellableCoroutine }
        val requestCall = client.newCall(request)
        c.invokeOnCancellation { requestCall.cancel() }
        requestCall.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (c.isActive) c.resumeWithException(ErrorLoadingException("LiderShort bağlantısı tamamlanamadı."))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use { r ->
                        if (LiderShortContract.own(request.url.toString()) != null) {
                            if (r.code == 403) denied = true
                            if (r.code == 429) LiderShortHold.remember(limits, r.header("Retry-After"))
                            permitted()
                        }
                        read(r)
                    }
                    if (c.isActive) c.resume(value, onCancellation = { _, _, _ -> })
                } catch (e: Exception) {
                    if (c.isActive) c.resumeWithException(if (e is ErrorLoadingException) e else ErrorLoadingException("LiderShort yanıtı doğrulanamadı."))
                }
            }
        })
    }
    private data class Reply(val body: String?, val redirect: String?) { override fun toString() = "Reply([redacted])" }
    suspend fun text(raw: String, referer: String = LiderShortContract.BASE + "/tr"): String {
        permitted()
        return session.text(raw, referer, json = reqApi(raw))
    }
    suspend fun publicMedia(raw: String): String = withContext(Dispatchers.IO) {
        val url = LiderShortContract.https(raw)?.toHttpUrlOrNull() ?: throw ErrorLoadingException("LiderShort medya adresi tanınmadı.")
        val ips = runCatching { Dns.SYSTEM.lookup(url.host) }.getOrElse { throw ErrorLoadingException("LiderShort medya sunucusuna erişilemedi.") }
        if (ips.isEmpty() || ips.any(::privateAddress)) throw ErrorLoadingException("LiderShort medya sunucusu tanınmadı.")
        url.toString()
    }
    suspend fun captionBytes(raw: String, referer: String): ByteArray? { return try {
        var url = publicMedia(LiderShortContract.captionURL(raw) ?: return null)
        var bytes: ByteArray? = null
        for (i in 0..3) {
            val sourceCookie = session.cookies(url)
            val request = Request.Builder().url(url).header("User-Agent", session.userAgent.ifBlank { UA })
                .apply { if (LiderShortContract.own(url) != null) { header("Referer", referer);if(sourceCookie.isNotBlank())header("Cookie",sourceCookie) } }.build()
            val reply = call(media, request) { r ->
                if (r.code in setOf(301, 302, 303, 307, 308)) Reply(null, r.header("Location")?.let { request.url.resolve(it) }?.toString())
                else {
                    if (!r.isSuccessful) return@call Reply("", null)
                    val s = r.body.source(); s.request(512_001)
                    if (s.buffer.size > 512_000) Reply("", null) else Reply(s.readUtf8(), null)
                }
            }
            if (reply.body != null) { bytes = CaptionBuffer.vtt(reply.body); break }
            // Apply the same policy before every follow-up GET, not just before callback emission.
            val target = LiderShortContract.captionURL(reply.redirect ?: break) ?: break
            url = publicMedia(target)
        }
        bytes
    } catch (e: CancellationException) { throw e } catch (_: Exception) { permitted(); null } }
    fun close() { closed = true; base.dispatcher.cancelAll(); base.connectionPool.evictAll() }
    companion object {
        const val UA = "ArcanaDrama-LiderShort/1"
        private fun reqApi(url: String) = java.net.URI(url).path.startsWith("/api/")
        private fun privateAddress(a: InetAddress): Boolean = a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress || a.isMulticastAddress || a.address.let {
                if (it.size == 16) (it[0].toInt() and 0xfe) == 0xfc else {
                    val first = it[0].toInt() and 255; val second = it[1].toInt() and 255
                    first == 0 || first >= 224 || (first == 100 && second in 64..127) || (first == 198 && second in 18..19)
                }
            }
    }
}
