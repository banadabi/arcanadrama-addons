package dev.arcana.minidiziizle

import android.content.Context
import android.os.SystemClock
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import org.json.JSONObject

/** Isolated normal guest cookies; API pace and access holds never change a worker or browser profile. */
internal class MiniHttp(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arcana_mini_access",Context.MODE_PRIVATE)
    @Volatile private var closed = false
    @Volatile private var holdUntil = prefs.getLong("until",0L)
    private val pace = Mutex()
    private var previous = 0L
    private val cookies = linkedMapOf<String,Cookie>()
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) = synchronized(cookies) {
            val now = System.currentTimeMillis();cookies.entries.removeAll { it.value.expiresAt <= now }
            for (v in values) {
                if (!v.matches(url)) continue
                val key = "${v.domain}|${v.path}|${v.name}"
                if(v.expiresAt > now) cookies[key] = v else cookies.remove(key)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
            cookies.values.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        }
    }
    val ua: String = System.getProperty("http.agent")?.takeIf { it.isNotBlank() } ?: "ArcanaDrama-MiniDiziIzle/1"
    val mediaClient: OkHttpClient = OkHttpClient.Builder().cookieJar(jar)
        .followRedirects(false).followSslRedirects(false).connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(22,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS)
        .dns { publicAddresses(it) }.build()
    fun permitted() {
        if(closed) throw IOException("Mini source session closed")
        if(maxOf(holdUntil,prefs.getLong("until",0L)) > System.currentTimeMillis()) throw ErrorLoadingException("MiniDiziİzle erişimi beklemede. Kaynak erişimi düzelince daha sonra yeniden deneyin.")
    }
    fun denied(code: Int, retry: String?) {
        if(code !in setOf(401,403,429)) return
        val now = System.currentTimeMillis()
        val deadline = if(code == 429)MiniPolicy.retryDeadline(retry,now) else now+1800000
        synchronized(prefs) {
            holdUntil=maxOf(holdUntil,prefs.getLong("until",0),deadline)
            if(!prefs.edit().putLong("until",holdUntil).commit())throw ErrorLoadingException("MiniDiziİzle bekleme kaydı yazılamadı. Erişim durduruldu.")
        }
        throw ErrorLoadingException("MiniDiziİzle HTTP $code ile erişimi durdurdu. Daha sonra yeniden deneyin.")
    }
    suspend fun text(raw: String, referer: String = MiniContract.BASE+"/", body: JSONObject? = null): String = pace.withLock {
        permitted();currentCoroutineContext().ensureActive()
        MiniPolicy.request(raw,body != null)
        val target = raw.toHttpUrl()
        val wait = 3000-(SystemClock.elapsedRealtime()-previous)
        if(previous > 0 && wait > 0)delay(wait)
        permitted();previous = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(target).header("User-Agent",ua).header("Accept-Encoding","identity")
                    .header("Referer",referer).header("Origin",MiniContract.BASE).header("Accept","application/json,text/html;q=0.9,*/*;q=0.8")
                if(body != null)builder.post(body.toString().toRequestBody("application/json".toMediaType()))
                val call = mediaClient.newCall(builder.build())
                // Keep cancellation attached until the response body is fully read and closed.
                suspendCancellableCoroutine<String> { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call,e: IOException) {
                            if(continuation.isActive)continuation.resumeWithException(ErrorLoadingException("MiniDiziİzle bağlantısı tamamlanamadı."))
                        }
                        override fun onResponse(call: Call,response: Response) {
                            if(!continuation.isActive) { response.close();return }
                            try {
                                val value = response.use { r ->
                                    denied(r.code,r.header("Retry-After"));permitted()
                                    if(!r.isSuccessful)throw ErrorLoadingException("MiniDiziİzle HTTP ${r.code} yanıtı verdi.")
                                    if(r.body.contentLength() > 4000000)throw ErrorLoadingException("MiniDiziİzle yanıtı boyut sınırını aştı.")
                                    val input = r.body.source();input.request(4000001)
                                    if(input.buffer.size > 4000000)throw ErrorLoadingException("MiniDiziİzle yanıtı boyut sınırını aştı.")
                                    val text = input.readUtf8()
                                    MiniPolicy.bodyDenial(text)?.let { denied(it,r.header("Retry-After") ?: MiniPolicy.retryHint(text)) }
                                    permitted()
                                    if(!continuation.isActive)throw CancellationException("Mini source response canceled")
                                    text
                                }
                                if(continuation.isActive)continuation.resume(value,onCancellation={_,_,_->})
                            } catch(e: Exception) {
                                response.close()
                                if(continuation.isActive)continuation.resumeWithException(if(e is ErrorLoadingException)e else ErrorLoadingException("MiniDiziİzle yanıtı tamamlanamadı."))
                            }
                        }
                    })
                }
            } catch(e: CancellationException) { throw e }
            catch(e: ErrorLoadingException) { throw e }
            catch(_: Exception) { throw ErrorLoadingException("MiniDiziİzle bağlantısı tamamlanamadı.") }
        }
    }
    suspend fun json(raw: String, referer: String, body: JSONObject? = null): JSONObject {
        val value = text(raw,referer,body)
        val data = try { JSONObject(value) } catch(_: Exception) { throw ErrorLoadingException("MiniDiziİzle API yanıtı değişmiş veya eksik.") }
        MiniPolicy.denial(data)?.let { denied(it,MiniPolicy.retryHint(value)) }
        if(MiniPolicy.error(data))throw ErrorLoadingException("MiniDiziİzle API isteği tamamlanmadı.")
        return data
    }
    fun close() { closed=true;mediaClient.dispatcher.cancelAll();mediaClient.connectionPool.evictAll();synchronized(cookies){cookies.clear()} }
    companion object {
        fun publicAddresses(host: String): List<InetAddress> = Dns.SYSTEM.lookup(host).also { list ->
            if(list.isEmpty() || list.any { !MiniPolicy.publicAddress(it) })throw IOException("Public source address required")
        }
    }
}
