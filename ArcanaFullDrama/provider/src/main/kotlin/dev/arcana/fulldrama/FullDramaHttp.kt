package dev.arcana.fulldrama

import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class FullDramaHttp(private val session: FullDramaSession) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).callTimeout(14,TimeUnit.SECONDS)
        .dns { host -> Dns.SYSTEM.lookup(host).filterNot(::privateAddress).ifEmpty { throw IOException("Public source unavailable") } }.build()
    suspend fun text(raw: String): String {
        session.bootstrap()
        return withContext(Dispatchers.IO) {
            try {
                var url = FullDramaContract.own(raw).toASCIIString().toHttpUrlOrNull() ?: throw IllegalArgumentException()
                repeat(4) {
                    val cookie = FullDramaLogin.cookies(url.toString())
                    val request = Request.Builder().url(url).header("User-Agent",session.userAgent)
                        .header("Referer",FullDramaContract.BASE + "/").header("Accept","text/html, application/json;q=0.9, */*;q=0.8")
                        .apply { if (cookie.isNotBlank()) header("Cookie",cookie) }.build()
                    val call = client.newCall(request)
                    val reply: Response = suspendCancellableCoroutine { c ->
                        c.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, error: IOException) { if (c.isActive) c.resumeWithException(error) }
                            override fun onResponse(call: Call, response: Response) { c.resume(response,onCancellation={_,r,_->r.close()}) }
                        })
                    }
                    reply.use { r ->
                        FullDramaLogin.saveCookies(url.toString(),r.headers.values("Set-Cookie"))
                        if (r.code in setOf(301,302,303,307,308)) {
                            val target = r.header("Location")?.let(url::resolve) ?: throw IllegalArgumentException()
                            FullDramaContract.own(target.toString()); url = target; return@use
                        }
                        if (r.code in setOf(401,403)) throw ErrorLoadingException("FullDrama bu istek için giriş veya doğrulama istiyor. Eklenti ayarından kaynak sitesini açın.")
                        if (r.code == 429) throw ErrorLoadingException("FullDrama istek sınırı bildirdi. Daha sonra tekrar deneyin.")
                        if (!r.isSuccessful) throw ErrorLoadingException("FullDrama HTTP ${r.code} yanıtı verdi.")
                        val body = r.body.source(); body.request(3_000_001)
                        if (body.buffer.size > 3_000_000) throw ErrorLoadingException("FullDrama yanıt boyutu sınırını aştı.")
                        return@withContext body.readUtf8()
                    }
                }
                throw ErrorLoadingException("FullDrama yönlendirme sınırı.")
            } catch (e: CancellationException) { throw e }
            catch (e: ErrorLoadingException) { throw e }
            catch (_: Exception) { throw ErrorLoadingException("FullDrama bağlantısı tamamlanamadı.") }
        }
    }
    /** Follow the publisher's one wait-token retry for bare arrays and object API responses. */
    suspend fun api(raw: String): String {
        var url = raw
        repeat(2) { attempt ->
            val body = text(url)
            if (!body.trimStart().startsWith("{")) return body
            val data = try { JSONObject(body) } catch (_: Exception) { throw ErrorLoadingException("FullDrama API yanıtı tanınmadı.") }
            when (data.optString("action")) {
                "wait" -> {
                    val ms = data.optLong("after_ms",3000)
                    val token = data.optString("wait_token")
                    if (attempt != 0 || token.isBlank() || ms !in 0..15_000) throw ErrorLoadingException("FullDrama bekleme istiyor. Biraz sonra yeniden deneyin.")
                    delay(ms + 200)
                    url = (FullDramaContract.own(raw).toASCIIString().toHttpUrlOrNull() ?: throw ErrorLoadingException("FullDrama istek adresi tanınmadı.")).newBuilder().setQueryParameter("wait_token",token).build().toString()
                }
                "captcha","block" -> throw ErrorLoadingException("FullDrama doğrulama istiyor. Eklenti ayarından kaynak sitesini açın.")
                else -> return body
            }
        }
        throw ErrorLoadingException("FullDrama bekleme tamamlanamadı.")
    }
    suspend fun json(raw: String): JSONObject {
        val body = api(raw)
        return try { JSONObject(body) } catch (_: Exception) { throw ErrorLoadingException("FullDrama API yanıtı tanınmadı.") }
    }
    class MediaInfo(val format: String, val totalBytes: Long?)
    private class MediaReadUnavailable : Exception("FullDrama medya okuması tamamlanamadı.")
    private class PrefixReply(val code: Int, val totalBytes: Long?, val bytes: ByteArray)
    /** Media-only request: no source cookies/Referer, redirects disabled, public DNS guard retained. */
    private suspend fun mediaPrefix(raw: String, range: String, limit: Int): PrefixReply = withContext(Dispatchers.IO) {
        val url = FullDramaContract.publicHttps(raw) ?: throw ErrorLoadingException("FullDrama medya adresi tanınmadı.")
        val request = Request.Builder().url(url).header("User-Agent",session.userAgent)
            .header("Range",range).header("Accept","*/*").build()
        val call = client.newCall(request)
        suspendCancellableCoroutine<PrefixReply> { c ->
            c.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (c.isActive) c.resumeWithException(MediaReadUnavailable())
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { reply ->
                            if (reply.code !in setOf(200,206)) throw ErrorLoadingException("FullDrama medya denetimi HTTP ${reply.code} yanıtı verdi.")
                            val total = reply.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                                ?: reply.body.contentLength().takeIf { it > 0 }
                            val source = reply.body.source(); source.request(limit.toLong())
                            PrefixReply(reply.code,total,source.readByteArray(minOf(limit.toLong(),source.buffer.size)))
                        }
                        if (c.isActive) c.resume(result,onCancellation={_,_,_->})
                    } catch (error: Exception) {
                        if (c.isActive) c.resumeWithException(if (error is ErrorLoadingException) error else MediaReadUnavailable())
                    }
                }
            })
        }
    }
    suspend fun mediaInfo(raw: String): MediaInfo {
        val result = mediaPrefix(raw,"bytes=0-511",512)
        val format = FullDramaContract.mediaFormat(result.bytes) ?: throw ErrorLoadingException("FullDrama medya yanıtı desteklenen video biçiminde değil.")
        return MediaInfo(format,result.totalBytes)
    }
    /** The observed broken NetShort file resets after 1MiB; check only 32KiB there, once. */
    suspend fun netshortTailReadable(raw: String): Boolean = try {
        val result = mediaPrefix(raw,"bytes=1048576-1081343",32768)
        // An origin which ignores Range has not proven a broken file; keep its normal player path.
        result.code != 206 || result.bytes.size == 32768
    } catch (error: CancellationException) { throw error }
      catch (_: MediaReadUnavailable) { false }

    private class RefreshReply(val data: JSONObject, val cookies: List<String>)
    /** Exact normal publisher POST: never fabricate its HMAC, fingerprint or an account. */
    suspend fun refresh(ticket: FullDramaContract.RefreshTicket): JSONObject = withContext(Dispatchers.IO) {
        session.bootstrap()
        val url = FullDramaContract.BASE + "/ajax/refresh.php"
        val cookie = FullDramaLogin.cookies(url)
        val payload = JSONObject().put("episode_id",ticket.episodeId).put("background",1).put("sig",ticket.signature)
        val request = Request.Builder().url(url).header("User-Agent",session.userAgent)
            .header("Referer",FullDramaContract.BASE + "/")
            .apply { if (cookie.isNotBlank()) header("Cookie",cookie) }
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        val result = suspendCancellableCoroutine<RefreshReply> { c ->
            c.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (c.isActive) c.resumeWithException(ErrorLoadingException("FullDrama yenileme yanıtı alınamadı. Biraz sonra yeniden açın."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val data = response.use { reply ->
                            if (!reply.isSuccessful) throw ErrorLoadingException("FullDrama yenileme HTTP ${reply.code} yanıtı verdi.")
                            val source = reply.body.source();source.request(128001)
                            if (source.buffer.size > 128000) throw ErrorLoadingException("FullDrama yenileme yanıtı tanınmadı.")
                            RefreshReply(JSONObject(source.readUtf8()),reply.headers.values("Set-Cookie"))
                        }
                        if (c.isActive) c.resume(data,onCancellation={_,_,_->})
                    } catch (error: Exception) {
                        if (c.isActive) c.resumeWithException(if (error is ErrorLoadingException) error else ErrorLoadingException("FullDrama yenileme yanıtı tanınmadı."))
                    }
                }
            })
        }
        FullDramaLogin.saveCookies(url,result.cookies)
        result.data
    }
    fun close() { session.close(); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    companion object {
        private fun privateAddress(a: InetAddress) = a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress || a.address.let { it.size == 16 && (it[0].toInt() and 0xfe) == 0xfc }
    }
}
