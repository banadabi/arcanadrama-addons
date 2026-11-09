package dev.arcana.directdrama

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
internal class DirectHttp(context: Context, private val session: DirectSession) {
    private val limits = context.applicationContext.getSharedPreferences("arcana_directdrama_network", Context.MODE_PRIVATE)
    private val base = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(6,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(5,TimeUnit.SECONDS)
        .dns { host -> publicAddresses(host) }.build()
    @Volatile private var denied = false
    @Volatile private var closed = false
    fun permitted() {
        if (closed) throw ErrorLoadingException("DirectDrama eklentisi kapalı.")
        if (denied) throw ErrorLoadingException("DirectDrama kaynak erişimini kısıtladı.")
        if (System.currentTimeMillis() < limits.getLong("retry_until_ms",0L))
            throw ErrorLoadingException("DirectDrama istek sınırı bildirdi; daha sonra deneyin.")
    }
    fun rateLimited(header: String?) { DirectHold.remember(limits,header) }
    fun checkChallenge(raw: String) {
        val body=raw.trimStart('\uFEFF',' ','\r','\n','\t')
        if(body.startsWith("<") && DirectContract.deniedHtml(body)) {
            denied=true
            throw ErrorLoadingException("DirectDrama kaynak doğrulaması istiyor.")
        }
    }
    private fun checkedReply(reply: DirectSession.Reply): DirectSession.Reply {
        checkChallenge(reply.body)
        return reply
    }
    private suspend fun reply(url: String, referer: String, headers: Map<String,String> = emptyMap()): DirectSession.Reply {
        permitted()
        return checkedReply(session.reply(url,referer,headers))
    }

    suspend fun api(url: String): String {
        val r = reply(url,DirectContract.BASE + "/")
        if (r.status != 200) throw ErrorLoadingException("DirectDrama HTTP ${r.status} yanıtı verdi.")
        if (r.body.trimStart().startsWith("<") && DirectContract.deniedHtml(r.body)) {
            denied = true
            throw ErrorLoadingException("DirectDrama kaynak doğrulaması istiyor.")
        }
        return r.body
    }
    suspend fun html(url: String): String { permitted(); return session.text(url) }
    suspend fun play(c: DirectContract.Chapter): org.json.JSONObject {
        // Normal episode navigation establishes this app's own guest viewing context.
        // Bind the fresh published episode ID before requesting the read-only stream API.
        val html=html(c.url)
        DirectContract.verifyWatch(html,c)
        val url=(DirectContract.BASE+"/api/stream/"+c.id).toHttpUrl().newBuilder()
            .addQueryParameter("locale","tr").build().toString()
        return withTimeoutOrNull(25_000) {
            val deadline=android.os.SystemClock.elapsedRealtime()+25_000
            for(attempt in 0..5) {
                permitted()
                val r=reply(url,c.url)
                val j=runCatching { org.json.JSONObject(r.body) }.getOrNull()
                if(r.status==200)return@withTimeoutOrNull j ?: throw ErrorLoadingException("DirectDrama oynatma yanıtı tanınmadı.")
                val preparing=r.status==503 && j?.optJSONObject("error")?.optString("code")=="media_preparing"
                if(!preparing || attempt==5)throw ErrorLoadingException(if(preparing)"DirectDrama video hazırlıyor; daha sonra deneyin."else"DirectDrama HTTP ${r.status} yanıtı verdi.")
                val wait=DirectContract.preparingDelay(r.retryAfter,System.currentTimeMillis())
                if(wait>=deadline-android.os.SystemClock.elapsedRealtime())throw ErrorLoadingException("DirectDrama video hazırlıyor; daha sonra deneyin.")
                delay(wait)
            }
            throw ErrorLoadingException("DirectDrama video henüz hazır değil.")
        } ?: throw ErrorLoadingException("DirectDrama video hazırlığı zamanında tamamlanmadı.")
    }
    suspend fun publicMedia(raw: String): String = withContext(Dispatchers.IO) {
        val url = DirectContract.https(raw)?.toHttpUrl() ?: throw ErrorLoadingException("DirectDrama medya adresi tanınmadı.")
        publicAddresses(url.host)
        url.toString()
    }
    private suspend fun captionCall(request: Request): Pair<Int,Pair<String?,String?>> = suspendCancellableCoroutine { c ->
        try { permitted() } catch(e:Exception) { c.resumeWithException(e); return@suspendCancellableCoroutine }
        val call = base.newCall(request)
        c.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) { if(c.isActive)c.resumeWithException(ErrorLoadingException("DirectDrama altyazı bağlantısı tamamlanamadı.")) }
            override fun onResponse(call: Call,response: Response) {
                try {
                    val result = response.use { r ->
                        if(r.code==429) { rateLimited(r.header("Retry-After")); permitted() }
                        if(r.code==403 && DirectContract.own(request.url.toString())!=null) { denied=true;permitted() }
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
        var url=publicMedia(DirectContract.captionURL(raw) ?: return null)
        var redirects=0;var pending=0
        while(true) {
            permitted()
            val agent=session.userAgent.takeIf { it.isNotBlank() } ?: throw ErrorLoadingException("DirectDrama normal oturumu hazır değil.")
            // The publisher uses same-origin captions; send only this app's own cookie to the canonical origin.
            val request=Request.Builder().url(url).header("User-Agent",agent).header("Referer",referer).apply {
                if(DirectContract.own(url)!=null)session.cookies(url).takeIf { it.isNotBlank() }?.let { header("Cookie",it) }
            }.build()
            val (status,value)=captionCall(request)
            if(status in setOf(301,302,303,307,308)) {
                if(redirects++>=3)break
                url=publicMedia(DirectContract.captionURL(value.second ?: break) ?: break)
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
