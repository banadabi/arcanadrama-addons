package dev.arcana.dramadunyam

import android.content.Context
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** The app's own normal guest source context; no desktop profile or credential import. */
internal class DunyaHttp(context: Context, private val session: DunyaSession) {
    private val limits = context.applicationContext.getSharedPreferences("arcana_dramadunyam_network", Context.MODE_PRIVATE)
    private val ticketLock = Mutex()
    @Volatile private var ticketGeneration = 0L
    private val base = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(6,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(5,TimeUnit.SECONDS)
        .dns { host -> publicAddresses(host) }.build()
    @Volatile private var denied = false
    @Volatile private var closed = false
    fun permitted() {
        if (closed) throw ErrorLoadingException("DramaDünyam eklentisi kapalı.")
        if (denied) throw ErrorLoadingException("DramaDünyam kaynak erişimini kısıtladı.")
        if (System.currentTimeMillis() < limits.getLong("retry_until_ms",0L))
            throw ErrorLoadingException("DramaDünyam istek sınırı bildirdi; daha sonra deneyin.")
    }
    fun rateLimited(header: String?) { DunyaHold.remember(limits,header) }
    fun checkChallenge(raw: String) {
        val body=raw.trimStart('\uFEFF',' ','\r','\n','\t')
        if(body.startsWith("<") && DunyaContract.deniedHtml(body)) {
            denied=true
            throw ErrorLoadingException("DramaDünyam kaynak doğrulaması istiyor.")
        }
    }
    private fun checkedReply(reply: DunyaSession.Reply): DunyaSession.Reply {
        checkChallenge(reply.body)
        return reply
    }
    private suspend fun reply(url: String, referer: String, headers: Map<String,String> = emptyMap()): DunyaSession.Reply {
        permitted()
        val version = ticketGeneration
        val first = checkedReply(session.reply(url,referer,headers))
        if (first.status != 412) return first
        ticketLock.withLock {
            if (version == ticketGeneration) {
                val config = withTimeout(8_000) { checkedReply(session.reply(DunyaContract.BASE + "/api/config")) }
                if (config.status != 200) throw ErrorLoadingException("DramaDünyam normal izleme bileti yenilenemedi.")
                ticketGeneration++
            }
        }
        permitted()
        return checkedReply(session.reply(url,referer,headers))
    }
    suspend fun api(url: String): String {
        val r = reply(url,DunyaContract.BASE + "/")
        if (r.status != 200) throw ErrorLoadingException("DramaDünyam HTTP ${r.status} yanıtı verdi.")
        if (r.body.trimStart().startsWith("<") && DunyaContract.deniedHtml(r.body)) {
            denied = true
            throw ErrorLoadingException("DramaDünyam kaynak doğrulaması istiyor.")
        }
        return r.body
    }
    suspend fun html(url: String): String { permitted(); return session.text(url) }
    suspend fun play(c: DunyaContract.Chapter): org.json.JSONObject {
        val url = DunyaContract.BASE.toHttpUrl().newBuilder().addPathSegment("play").addPathSegment(c.id).addPathSegment(c.number.toString()).build().toString()
        // These are actual implemented format capabilities, not authorization or an identity override.
        val headers = mapOf("X-DD-Native-MP4" to "1", "X-DD-Segment-Decrypt" to "1")
        return withTimeoutOrNull(25_000) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 25_000
                var transientRetries = 0
                for (attempt in 0..5) {
                    permitted()
                    val r = reply(url,c.url,headers)
                    val body = runCatching { org.json.JSONObject(r.body) }.getOrNull()
                    if (r.status == 200) return@withTimeoutOrNull body ?: throw ErrorLoadingException("DramaDünyam oynatma yanıtı tanınmadı.")
                    if (r.status == 410) throw ErrorLoadingException("DramaDünyam bu bölümü yayından kaldırdı.")
                    val reason = body?.optString("neden").orEmpty()
                    val preparing = r.status == 202 && body?.optString("mod") == "native" && body?.optString("hata") == "yenileniyor" ||
                        r.status == 503 && body?.optString("mod") != "native" && reason in setOf("partner_pending","source_preparing","publication_pending","stored_expired","stored_no_url")
                    val transient = r.status == 503 && body?.optString("mod") != "native" && body?.optBoolean("retryable") == true &&
                        reason in setOf("local_capacity","local_cache_busy","local_error","upstream_connection","upstream_unusable","upstream_error","upstream_deadline","upstream_timeout","source_busy","source_upstream_busy","source_capacity","server_busy","near_expiry","source_generation_changed")
                    if (!preparing && !transient || transient && transientRetries++ >= 3 || attempt == 5)
                        throw ErrorLoadingException(if (r.status in setOf(202,503)) "DramaDünyam video hazırlıyor; biraz sonra tekrar deneyin." else "DramaDünyam HTTP ${r.status} yanıtı verdi.")
                    val numeric = r.retryAfter.toDoubleOrNull() ?: body?.optDouble("retry_after",if(r.status==202)2.0 else 3.0) ?: 3.0
                    val wait = if (r.retryAfter.isNotBlank() && r.retryAfter.toDoubleOrNull() == null) {
                        runCatching { (java.time.ZonedDateTime.parse(r.retryAfter,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis()).coerceAtLeast(1000) }.getOrDefault(3000)
                    } else if (numeric.isFinite() && numeric >= 0) kotlin.math.ceil(numeric * 1000).toLong().coerceAtLeast(1000) else 3000
                    if (wait >= deadline - android.os.SystemClock.elapsedRealtime()) throw ErrorLoadingException("DramaDünyam video hazırlıyor; biraz sonra tekrar deneyin.")
                    delay(wait)
                }
                throw ErrorLoadingException("DramaDünyam video henüz hazır değil.")
        } ?: throw ErrorLoadingException("DramaDünyam video hazırlığı zamanında tamamlanmadı.")
    }
    suspend fun publicMedia(raw: String): String = withContext(Dispatchers.IO) {
        val url = DunyaContract.https(raw)?.toHttpUrl() ?: throw ErrorLoadingException("DramaDünyam medya adresi tanınmadı.")
        publicAddresses(url.host)
        url.toString()
    }
    private suspend fun captionCall(request: Request): Pair<Int,Pair<String?,String?>> = suspendCancellableCoroutine { c ->
        try { permitted() } catch(e:Exception) { c.resumeWithException(e); return@suspendCancellableCoroutine }
        val call = base.newCall(request)
        c.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) { if(c.isActive)c.resumeWithException(ErrorLoadingException("DramaDünyam altyazı bağlantısı tamamlanamadı.")) }
            override fun onResponse(call: Call,response: Response) {
                try {
                    val result = response.use { r ->
                        if(r.code==429) { rateLimited(r.header("Retry-After")); permitted() }
                        if(r.code==403 && DunyaContract.own(request.url.toString())!=null) { denied=true;permitted() }
                        when {
                            r.code in setOf(301,302,303,307,308) -> r.code to (null to r.header("Location")?.let { request.url.resolve(it)?.toString() })
                            r.code==503 -> r.code to (r.header("Retry-After") to null)
                            r.code==200 -> {
                                val source=r.body.source();source.request(512_001)
                                val text=if(source.buffer.size<=512_000)source.readUtf8() else ""
                                checkChallenge(text)
                                r.code to (text to null)
                            }
                            else -> r.code to ("" to null)
                        }
                    }
                    if(c.isActive)c.resume(result,onCancellation={_,_,_->})
                } catch(e:Exception) { if(c.isActive)c.resumeWithException(e) }
            }
        })
    }
    suspend fun captionBytes(raw: String,referer: String): ByteArray? { return try {
        var url=publicMedia(DunyaContract.captionURL(raw) ?: return null)
        var redirects=0;var pending=0
        while(true) {
            permitted()
            val agent=session.userAgent.takeIf { it.isNotBlank() } ?: throw ErrorLoadingException("DramaDünyam normal oturumu hazır değil.")
            // The publisher's caption client explicitly omits credentials, including on its own origin.
            val request=Request.Builder().url(url).header("User-Agent",agent).header("Referer",referer).build()
            val (status,value)=captionCall(request)
            if(status in setOf(301,302,303,307,308)) {
                if(redirects++>=3)break
                url=publicMedia(DunyaContract.captionURL(value.second ?: break) ?: break)
                continue
            }
            if(status==503 && pending++<2) {
                val seconds=value.first?.toDoubleOrNull()
                val wait=if(seconds!=null && seconds.isFinite() && seconds>=0)maxOf(2500,kotlin.math.ceil(seconds*1000).toLong()) else if(!value.first.isNullOrBlank())runCatching { (java.time.ZonedDateTime.parse(value.first,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis()).coerceAtLeast(2500) }.getOrDefault(Long.MAX_VALUE) else 2500
                if(wait>5000)break
                delay(wait);continue
            }
            return if(status==200)CaptionBuffer.vtt(value.first.orEmpty())else null
        }
        null
    } catch(e:CancellationException) { throw e } catch(_:Exception) { permitted();null } }
    fun close() { closed=true;base.dispatcher.cancelAll();base.connectionPool.evictAll() }
    companion object {
        fun publicAddresses(host:String):List<InetAddress> = Dns.SYSTEM.lookup(host).also { if(it.isEmpty() || it.any(::privateAddress))throw IOException("Public publisher required") }
        private fun privateAddress(a:InetAddress):Boolean = a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress || a.address.let {
            if(it.size==16)(it[0].toInt() and 0xfe)==0xfc else {
                val first=it[0].toInt() and 255;val second=it[1].toInt() and 255
                first==0 || first>=224 || first==100 && second in 64..127 || first==198 && second in 18..19
            }
        }
    }
}
