package dev.arcana.liderdrama

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
internal class LiderHttp(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arcana_lider_access",Context.MODE_PRIVATE)
    @Volatile private var closed = false
    @Volatile private var holdUntil = prefs.getLong("until",0L)
    private val pace = Mutex()
    private var previous = 0L
    private val cookies = linkedMapOf<String,Cookie>()
    private val captions=LiderCaptionSession(context,::permitted,{retry->denied(429,retry)},{
        pace.withLock {
            permitted()
            val wait=3000-(SystemClock.elapsedRealtime()-previous)
            if(previous>0&&wait>0)delay(wait)
            permitted();previous=SystemClock.elapsedRealtime()
        }
    })
    private val automaticCaptions=LiderAutomaticCaptions(context,::permitted,{retry->denied(429,retry)},{
        pace.withLock {
            permitted()
            val wait=3000-(SystemClock.elapsedRealtime()-previous)
            if(previous>0&&wait>0)delay(wait)
            permitted();previous=SystemClock.elapsedRealtime()
        }
    })
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) = synchronized(cookies) {
            if(url.host != "liderdrama.com")return@synchronized
            val now = System.currentTimeMillis();cookies.entries.removeAll { it.value.expiresAt <= now }
            for (v in values) {
                if (!v.matches(url)) continue
                val key = "${v.domain}|${v.path}|${v.name}"
                if(v.expiresAt > now) cookies[key] = v else cookies.remove(key)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
            if(url.host != "liderdrama.com")emptyList() else cookies.values.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        }
    }
    val ua: String = System.getProperty("http.agent")?.takeIf { it.isNotBlank() } ?: "ArcanaDrama-LiderDrama/1"
    val mediaClient: OkHttpClient = OkHttpClient.Builder().cookieJar(jar)
        .followRedirects(false).followSslRedirects(false).connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(22,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS)
        .dns { publicAddresses(it) }.build()
    fun permitted() {
        if(closed) throw IOException("Lider source session closed")
        if(maxOf(holdUntil,prefs.getLong("until",0L)) > System.currentTimeMillis()) throw ErrorLoadingException("LiderDrama erişimi beklemede. Kaynak erişimi düzelince daha sonra yeniden deneyin.")
    }
    fun denied(code: Int, retry: String?)=reject(code,retry,null)
    private fun reject(code:Int,retry:String?,afterCommitted:(()->Unit)?) {
        if(code !in setOf(401,403,429)) return
        val now = System.currentTimeMillis()
        val deadline = if(code == 429)LiderPolicy.retryDeadline(retry,now) else now+1800000
        synchronized(prefs) {
            holdUntil=maxOf(holdUntil,prefs.getLong("until",0),deadline)
            if(!prefs.edit().putLong("until",holdUntil).commit())throw ErrorLoadingException("LiderDrama bekleme kaydı yazılamadı. Erişim durduruldu.")
        }
        // A failed commit exits above with the original error and never samples the response.
        try { afterCommitted?.invoke() } catch(_:Exception) { /* Keep the original denial authoritative. */ }
        throw ErrorLoadingException("LiderDrama HTTP $code ile erişimi durdurdu. Daha sonra yeniden deneyin.")
    }
    private fun logFailure(response:Response,phase:LiderFailureContext.Phase,denial:Int?=null,alreadyRead:String?=null) {
        val sample=alreadyRead?.take(4096)?:runCatching{response.peekBody(4096).string()}.getOrNull()
        val kind=LiderFailureContext.kind(response.header("Content-Type"),response.header("cf-mitigated").equals("challenge",true),sample)
        val logical=denial?.let{" denial=$it"}.orEmpty()
        Log.w("ArcanaLiderAccess","phase=${phase.name} http=${response.code}$logical response=${kind.name}")
    }
    suspend fun captionText(caption:LiderContract.Caption,expectedSlug:String,expectedNumber:Int,budgetMs:Long):String {
        permitted()
        return automaticCaptions.resolve(caption,expectedSlug,expectedNumber,mediaClient,ua,budgetMs)
    }
    suspend fun text(raw: String, referer: String = LiderContract.BASE+"/", body: JSONObject? = null): String = pace.withLock {
        permitted();currentCoroutineContext().ensureActive()
        LiderPolicy.request(raw,body != null)
        val target = raw.toHttpUrl()
        val phase=LiderFailureContext.phase(target.encodedPath)
        val caption=phase==LiderFailureContext.Phase.SUBTITLE
        val requestContext=if(caption)captions.requestContext(raw,mediaClient,ua)else null
        val client=requestContext?.client?:mediaClient
        val wait = 3000-(SystemClock.elapsedRealtime()-previous)
        if(previous > 0 && wait > 0)delay(wait)
        permitted();if(caption)captions.beforeRequest(raw);previous = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(target).header("User-Agent",requestContext?.ua?:ua).header("Accept-Encoding","identity")
                    .header("Referer",referer).header("Origin",LiderContract.BASE).header("Accept","application/json,text/html;q=0.9,*/*;q=0.8")
                requestContext?.cookie?.let{builder.header("Cookie",it)}
                if(body != null)builder.post(body.toString().toRequestBody("application/json".toMediaType()))
                val call = client.newCall(builder.build())
                // Keep cancellation attached until the response body is fully read and closed.
                suspendCancellableCoroutine<String> { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call,e: IOException) {
                            if(continuation.isActive)continuation.resumeWithException(if(caption)LiderCaptionUnavailable()else ErrorLoadingException("LiderDrama bağlantısı tamamlanamadı."))
                        }
                        override fun onResponse(call: Call,response: Response) {
                            if(!continuation.isActive) { response.close();return }
                            try {
                                val value = response.use { r ->
                                    if(LiderCaptionPolicy.scope(phase,r.code)==LiderCaptionPolicy.Scope.CAPTION) {
                                        captions.blocked(raw);logFailure(r,phase)
                                        throw LiderCaptionUnavailable()
                                    }
                                    // Keep the original rejection fence BEFORE reading any diagnostic sample.
                                    reject(r.code,r.header("Retry-After")){logFailure(r,phase)}
                                    permitted()
                                    if(caption&&!r.isSuccessful) { logFailure(r,phase);throw LiderCaptionUnavailable() }
                                    if(!r.isSuccessful) { logFailure(r,phase);throw ErrorLoadingException("LiderDrama HTTP ${r.code} yanıtı verdi.") }
                                    val limit=if(caption)2000000L else 4000000L
                                    if(r.body.contentLength() > limit)throw if(caption)LiderCaptionUnavailable()else ErrorLoadingException("LiderDrama yanıtı boyut sınırını aştı.")
                                    val input = r.body.source();input.request(limit+1)
                                    if(input.buffer.size > limit)throw if(caption)LiderCaptionUnavailable()else ErrorLoadingException("LiderDrama yanıtı boyut sınırını aştı.")
                                    val text = input.readUtf8()
                                    LiderPolicy.bodyDenial(text)?.let {
                                        if(LiderCaptionPolicy.scope(phase,it)==LiderCaptionPolicy.Scope.CAPTION) {
                                            captions.blocked(raw);logFailure(r,phase,it,text);throw LiderCaptionUnavailable()
                                        }
                                        reject(it,r.header("Retry-After") ?: LiderPolicy.retryHint(text)){logFailure(r,phase,it,text)}
                                    }
                                    permitted()
                                    if(!continuation.isActive)throw CancellationException("Lider source response canceled")
                                    text
                                }
                                if(continuation.isActive)continuation.resume(value,onCancellation={_,_,_->})
                            } catch(e: Exception) {
                                response.close()
                                if(continuation.isActive)continuation.resumeWithException(if(e is ErrorLoadingException||e is LiderCaptionUnavailable)e else if(caption)LiderCaptionUnavailable()else ErrorLoadingException("LiderDrama yanıtı tamamlanamadı."))
                            }
                        }
                    })
                }
            } catch(e: CancellationException) { throw e }
            catch(e: ErrorLoadingException) { throw e }
            catch(e: LiderCaptionUnavailable) { throw e }
            catch(_: Exception) { throw ErrorLoadingException("LiderDrama bağlantısı tamamlanamadı.") }
        }
    }
    suspend fun json(raw: String, referer: String, body: JSONObject? = null): JSONObject {
        val value = text(raw,referer,body)
        val data = try { JSONObject(value) } catch(_: Exception) { throw ErrorLoadingException("LiderDrama API yanıtı değişmiş veya eksik.") }
        LiderPolicy.denial(data)?.let { denied(it,LiderPolicy.retryHint(value)) }
        if(LiderPolicy.error(data))throw ErrorLoadingException("LiderDrama API isteği tamamlanmadı.")
        return data
    }
    fun close() { closed=true;automaticCaptions.close();captions.close();mediaClient.dispatcher.cancelAll();mediaClient.connectionPool.evictAll();synchronized(cookies){cookies.clear()} }
    companion object {
        fun publicAddresses(host: String): List<InetAddress> = Dns.SYSTEM.lookup(host).also { list ->
            if(list.isEmpty() || list.any { !LiderPolicy.publicAddress(it) })throw IOException("Public source address required")
        }
    }
}
