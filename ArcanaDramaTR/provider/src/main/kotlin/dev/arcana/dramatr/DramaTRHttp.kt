package dev.arcana.dramatr

import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class DramaTRHttp {
    private data class Reply(val body: String?, val location: String?, val cookies: List<String>)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).callTimeout(14,TimeUnit.SECONDS)
        .dns { h -> Dns.SYSTEM.lookup(h).filterNot(::privateAddress).ifEmpty { throw IOException("Public source unavailable") } }.build()
    suspend fun text(raw: String, referer: String = DramaTRContract.BASE + "/"): String {
        var url = DramaTRContract.own(raw) ?: throw ErrorLoadingException("DramaTR kaynak adresi tanınmadı.")
        repeat(4) {
            val cookie = DramaTRLogin.cookies(url)
            val request = Request.Builder().url(url).header("User-Agent",UA).header("Referer",referer)
                .header("Accept","application/json, text/html;q=0.9, */*;q=0.8")
                .apply { if (cookie.isNotBlank()) header("Cookie",cookie) }.build()
            val call = client.newCall(request)
            val result = suspendCancellableCoroutine<Reply> { c ->
                c.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (c.isActive) c.resumeWithException(ErrorLoadingException("DramaTR bağlantısı tamamlanamadı."))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val reply = response.use { r ->
                                if (r.code in setOf(301,302,303,307,308)) {
                                    val target = r.header("Location")?.let { request.url.resolve(it) }?.toString()
                                    Reply(null,DramaTRContract.own(target.orEmpty()) ?: error("redirect"),r.headers.values("Set-Cookie"))
                                } else {
                                    if (r.code in setOf(401,403)) throw ErrorLoadingException("DramaTR kaynak erişimi veya üyelik istiyor. Eklenti ayarından kaynak sitesini açın.")
                                    if (r.code == 429) throw ErrorLoadingException("DramaTR istek sınırı bildirdi; daha sonra deneyin.")
                                    if (!r.isSuccessful) throw ErrorLoadingException("DramaTR HTTP ${r.code} yanıtı verdi.")
                                    val source = r.body.source(); source.request(3_000_001)
                                    if (source.buffer.size > 3_000_000) error("size")
                                    Reply(source.readUtf8(),null,r.headers.values("Set-Cookie"))
                                }
                            }
                            if (c.isActive) c.resume(reply,onCancellation={_,_,_->})
                        } catch (e: Exception) {
                            if (c.isActive) c.resumeWithException(if (e is ErrorLoadingException) e else ErrorLoadingException("DramaTR yanıtı tanınmadı."))
                        }
                    }
                })
            }
            DramaTRLogin.saveCookies(url,result.cookies)
            if (result.body != null) return result.body
            url = result.location ?: throw ErrorLoadingException("DramaTR yönlendirmesi tanınmadı.")
        }
        throw ErrorLoadingException("DramaTR yönlendirme sınırı.")
    }
    suspend fun publicMedia(raw: String): String = withContext(Dispatchers.IO) {
        val u = DramaTRContract.https(raw)?.toHttpUrlOrNull() ?: throw ErrorLoadingException("DramaTR medya adresi tanınmadı.")
        val addresses = try { Dns.SYSTEM.lookup(u.host) } catch (_: Exception) { throw ErrorLoadingException("DramaTR medya sunucusuna erişilemedi.") }
        if (addresses.isEmpty() || addresses.any(::privateAddress)) throw ErrorLoadingException("DramaTR medya sunucusu tanınmadı.")
        u.toString()
    }
    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    companion object {
        const val UA = "ArcanaDrama-DramaTR/1"
        private fun privateAddress(a: InetAddress) = a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress || a.isMulticastAddress || a.address.let { it.size == 16 && (it[0].toInt() and 0xfe) == 0xfc }
    }
}
