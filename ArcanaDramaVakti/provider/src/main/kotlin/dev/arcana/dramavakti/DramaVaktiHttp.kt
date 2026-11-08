package dev.arcana.dramavakti

import android.content.Context
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** No hidden WebView in catalogue/playback. Guest cookies live only in this provider's RAM. */
internal class DramaVaktiHttp(private val context:Context) {
    data class Document(val body:String,val agent:String) { override fun toString()="Document(body=[redacted])" }
    private data class Reply(val body:String?,val redirect:String?,val cookies:List<String>) {
        override fun toString()="Reply(body=[redacted],cookies=[redacted])"
    }
    private val jar=object:CookieJar {
        private var cookies=emptyList<Cookie>()
        @Synchronized override fun saveFromResponse(url:HttpUrl,values:List<Cookie>) {
            if(DramaVaktiContract.own(url.toString())==null)return
            val changed=values.associateBy { it.name to it.path }
            cookies=(cookies.filter { (it.name to it.path) !in changed && it.expiresAt>System.currentTimeMillis() }+
                values.filter { it.value.length<=8192 && it.expiresAt>System.currentTimeMillis() }).takeLast(32)
        }
        @Synchronized override fun loadForRequest(url:HttpUrl)=
            if(DramaVaktiContract.own(url.toString())==null)emptyList()else cookies.filter { it.expiresAt>System.currentTimeMillis() && it.matches(url) }
    }
    private val base=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS)
        .dns { host -> Dns.SYSTEM.lookup(host).also { values -> if(values.isEmpty() || values.any(::privateAddress))throw IOException("Public host required") } }.build()
    private val guest=base.newBuilder().cookieJar(jar).build()
    private val member=base.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    private val media=base.newBuilder().cookieJar(CookieJar.NO_COOKIES).callTimeout(6,TimeUnit.SECONDS).build()
    private val limits=context.applicationContext.getSharedPreferences("arcana_dramavakti_network",Context.MODE_PRIVATE)
    @Volatile private var retryAfter=limits.getLong("retry_until_ms",0L)

    suspend fun text(raw:String,referer:String=DramaVaktiContract.BASE+"/"):Document {
        checkSourceHold()
        var url=DramaVaktiContract.own(raw) ?: throw ErrorLoadingException("DramaVakti kaynak adresi tanınmadı.")
        require(DramaVaktiContract.own(referer)!=null)
        val optedIn=DramaVaktiLogin.enabled(context)
        val agent=if(optedIn)DramaVaktiLogin.userAgent(context)else UA
        repeat(4) {
            val cookies=if(optedIn)DramaVaktiLogin.cookies(url)else ""
            val request=Request.Builder().url(url).header("User-Agent",agent).header("Referer",referer)
                .header("Accept",if(java.net.URI(url).path.startsWith("/api/"))"application/json"else"text/html, */*;q=0.8")
                .apply { if(cookies.isNotBlank())header("Cookie",cookies) }.build()
            val reply=call(if(optedIn)member else guest,request) { r ->
                if(r.code in setOf(301,302,303,307,308)) {
                    val next=r.header("Location")?.let { request.url.resolve(it) }?.toString()
                    Reply(null,DramaVaktiContract.own(next.orEmpty()) ?: error("foreign redirect"),r.headers.values("Set-Cookie"))
                } else {
                    if(r.code in setOf(401,403))throw ErrorLoadingException("DramaVakti kaynak erişimi veya kaynak hesabı gerekiyor.")
                    if(!r.isSuccessful)throw ErrorLoadingException("DramaVakti HTTP ${r.code} yanıtı verdi.")
                    val source=r.body.source();source.request(3_000_001)
                    if(source.buffer.size>3_000_000)error("body limit")
                    Reply(source.readUtf8(),null,r.headers.values("Set-Cookie"))
                }
            }
            if(optedIn)DramaVaktiLogin.saveCookies(url,reply.cookies)
            reply.body?.let { return Document(it,agent) }
            url=reply.redirect ?: throw ErrorLoadingException("DramaVakti yönlendirmesi tanınmadı.")
        }
        throw ErrorLoadingException("DramaVakti yönlendirme sınırı.")
    }
    private fun checkSourceHold() {
        if(System.currentTimeMillis()<maxOf(retryAfter,limits.getLong("retry_until_ms",0L)))
            throw ErrorLoadingException("DramaVakti istek sınırı bildirdi; daha sonra deneyin.")
    }
    private fun rememberSourceHold(header:String?) = synchronized(HOLD_LOCK) {
        retryAfter=maxOf(retryAfter,limits.getLong("retry_until_ms",0L),
            DramaVaktiContract.retryUntil(header,System.currentTimeMillis()))
        limits.edit().putLong("retry_until_ms",retryAfter).apply()
    }
    private suspend fun <T> call(client:OkHttpClient,request:Request,read:(Response)->T):T =
        suspendCancellableCoroutine { c ->
            val ownOrigin=DramaVaktiContract.own(request.url.toString())!=null
            try { if(ownOrigin)checkSourceHold() } catch(e:Exception) {
                c.resumeWithException(e);return@suspendCancellableCoroutine
            }
            val call=client.newCall(request);c.invokeOnCancellation { call.cancel() }
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException) {
                    if(c.isActive)c.resumeWithException(ErrorLoadingException("DramaVakti bağlantısı tamamlanamadı."))
                }
                override fun onResponse(call:Call,response:Response) {
                    try {
                        val value=response.use { r ->
                            if(ownOrigin && r.code==429) {
                                rememberSourceHold(r.header("Retry-After"))
                                checkSourceHold()
                            }
                            read(r)
                        }
                        if(c.isActive)c.resume(value,onCancellation={_,_,_->})
                    } catch(e:Exception) {
                        if(c.isActive)c.resumeWithException(if(e is ErrorLoadingException)e else ErrorLoadingException("DramaVakti yanıtı doğrulanamadı."))
                    }
                }
            })
        }
    private suspend fun publicUrl(raw:String):String = withContext(Dispatchers.IO) {
        val valid=DramaVaktiContract.https(raw)?.toHttpUrlOrNull() ?: throw ErrorLoadingException("DramaVakti medya adresi tanınmadı.")
        val addresses=runCatching { Dns.SYSTEM.lookup(valid.host) }.getOrElse { throw ErrorLoadingException("DramaVakti medya sunucusuna erişilemedi.") }
        if(addresses.isEmpty() || addresses.any(::privateAddress))throw ErrorLoadingException("DramaVakti medya sunucusu tanınmadı.")
        valid.toString()
    }
    data class Media(val url:String,val hls:Boolean) { override fun toString()="Media(url=[redacted],hls=$hls)" }
    private data class Probe(val complete:Boolean,val target:String,val hls:Boolean=false) {
        override fun toString()="Probe(target=[redacted])"
    }
    suspend fun verifyVideo(raw:String,agent:String,referer:String,declaredHls:Boolean?):Media {
        var url=publicUrl(raw)
        repeat(4) {
            val req=Request.Builder().url(url).header("User-Agent",agent).header("Range","bytes=0-511")
                .apply { if(DramaVaktiContract.own(url)!=null)header("Referer",referer) }.build()
            val result=call(media,req) { r ->
                if(r.code in setOf(301,302,303,307,308)) {
                    Probe(false,r.header("Location")?.let { req.url.resolve(it) }?.toString() ?: error("redirect"))
                } else {
                    if(!r.isSuccessful)throw ErrorLoadingException("DramaVakti medya erişimi tamamlanmadı.")
                    val input=r.body.byteStream();val bytes=ByteArray(512);var n=0
                    while(n<bytes.size) { val k=input.read(bytes,n,bytes.size-n);if(k<0)break;n+=k }
                    val prefix=String(bytes,0,n,Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n','\t')
                    val hls=prefix.startsWith("#EXTM3U")
                    val mp4=n>=8 && bytes.copyOfRange(4,8).contentEquals("ftyp".toByteArray())
                    val webm=n>=4 && bytes.copyOfRange(0,4).contentEquals(byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte()))
                    val ts=n>376 && bytes[0]==0x47.toByte() && bytes[188]==0x47.toByte() && bytes[376]==0x47.toByte()
                    if(!(hls || mp4 || webm || ts) || (declaredHls!=null && declaredHls!=hls))
                        throw ErrorLoadingException("DramaVakti kaynak videosu doğrulanamadı.")
                    Probe(true,url,hls)
                }
            }
            if(result.complete)return Media(result.target,result.hls)
            url=publicUrl(result.target)
        }
        throw ErrorLoadingException("DramaVakti medya yönlendirme sınırı.")
    }
    suspend fun validCaption(raw:String,agent:String,referer:String):Boolean {
        return try {
            val url=publicUrl(raw);val req=Request.Builder().url(url).header("User-Agent",agent)
                .apply { if(DramaVaktiContract.own(url)!=null)header("Referer",referer) }.build()
            call(media,req) { r ->
                if(!r.isSuccessful)return@call false
                val source=r.body.source();source.request(512_001)
                if(source.buffer.size>512_000)return@call false
                val text=source.readUtf8().removePrefix("\uFEFF")
                Regex("""(?:\d{2}:)?\d{2}:\d{2}[.,]\d{3}\s*-->\s*(?:\d{2}:)?\d{2}:\d{2}[.,]\d{3}""").containsMatchIn(text) &&
                    text.removePrefix("WEBVTT").any(Char::isLetter)
            }
        } catch(e:CancellationException) { throw e } catch(_:Exception) {
            checkSourceHold()
            false
        }
    }
    fun close() { base.dispatcher.cancelAll();base.connectionPool.evictAll() }
    companion object {
        private val HOLD_LOCK=Any()
        const val UA="ArcanaDrama-DramaVakti/1"
        private fun privateAddress(a:InetAddress)=a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress || a.isMulticastAddress || a.address.let {
                if(it.size==16)(it[0].toInt() and 0xfe)==0xfc
                else {
                    val first=it[0].toInt() and 255;val second=it[1].toInt() and 255
                    first==0 || first>=224 || (first==100 && second in 64..127) || (first==198 && second in 18..19)
                }
            }
    }
}
