package dev.arcana.anyshort

import android.content.Context
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Own normal guest only. Only its server-issued device ID is persisted; access token stays in RAM. */
internal class AnyHttp(context:Context) {
    private val store=context.applicationContext.getSharedPreferences("arcana_anyshort_guest",Context.MODE_PRIVATE)
    private val sessionMutex=Mutex()
    @Volatile private var token:String?=null
    @Volatile private var closed=false
    @Volatile private var denied=false
    private val client=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .connectTimeout(7,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).callTimeout(18,TimeUnit.SECONDS)
        .dns { host->publicAddresses(host) }.build()
    private class Reply(val status:Int,val body:String,val retryAfter:String?,val location:HttpUrl?) { override fun toString()="SourceReply([redacted])" }
    fun permitted() {
        if(closed)throw ErrorLoadingException("AnyShort eklentisi kapalı.")
        if(denied || System.currentTimeMillis()<store.getLong("retry_until_ms",0))throw ErrorLoadingException("AnyShort erişimi sınırladı; daha sonra deneyin.")
    }
    fun rateLimited(header:String?) { AnyHold.remember(store,header);client.dispatcher.cancelAll() }
    private fun stopAccess() { denied=true;rateLimited(null) }
    /** Known body denials are terminal even on HTTP200; only documented API401 auth expiry may renew once. */
    fun checkChallenge(raw:String,retryAfter:String?=null,allowSession401:Boolean=false) {
        val body=raw.trimStart('\uFEFF',' ','\r','\n','\t')
        val denial=AnyContract.denial(body)
        when {
            denial==AnyContract.Denial.LIMIT->{rateLimited(retryAfter);throw ErrorLoadingException("AnyShort istek sınırı bildirdi.")}
            denial in setOf(AnyContract.Denial.ACCESS,AnyContract.Denial.CHALLENGE) || denial==AnyContract.Denial.AUTH && !allowSession401 || body.startsWith("<") && AnyContract.deniedHtml(body)->{
                stopAccess();throw ErrorLoadingException("AnyShort kaynak erişimini reddetti veya doğrulama istiyor.")
            }
        }
    }
    private suspend fun call(request:Request,limit:Long,allowSessionRenew:Boolean=false):Reply=suspendCancellableCoroutine { continuation->
        try{permitted()}catch(e:Exception){continuation.resumeWithException(e);return@suspendCancellableCoroutine}
        val call=client.newCall(request);continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive)continuation.resumeWithException(ErrorLoadingException("AnyShort bağlantısı tamamlanamadı.")) }
            override fun onResponse(call:Call,response:Response) {
                try {
                    val reply=response.use { r->
                        permitted()
                        if(r.code==429){rateLimited(r.header("Retry-After"));throw ErrorLoadingException("AnyShort istek sınırı bildirdi.")}
                        if(r.code==403 && r.request.url.host=="anyshort.net"){stopAccess();throw ErrorLoadingException("AnyShort erişimi reddetti.")}
                        val source=r.body.source();source.request(limit+1)
                        if(source.buffer.size>limit)throw ErrorLoadingException("AnyShort yanıtı çok büyük.")
                        val text=source.readUtf8();checkChallenge(text,r.header("Retry-After"),allowSessionRenew && r.code==401)
                        permitted();Reply(r.code,text,r.header("Retry-After"),r.header("Location")?.let { r.request.url.resolve(it) })
                    }
                    if(continuation.isActive)continuation.resume(reply,onCancellation={_,_,_->})
                }catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(if(e is ErrorLoadingException)e else ErrorLoadingException("AnyShort yanıtı okunamadı."))}
            }
        })
    }
    private fun json(reply:Reply):JSONObject {
        if(reply.status!=200)throw ErrorLoadingException("AnyShort HTTP ${reply.status} yanıtı verdi.")
        val j=try{JSONObject(reply.body)}catch(_:Exception){throw ErrorLoadingException("AnyShort yanıtı tanınmadı.")}
        if(j.has("error") && !j.isNull("error"))throw ErrorLoadingException("AnyShort istenen kaynağı sağlayamadı.")
        return j
    }
    private suspend fun guest():String {
        permitted()
        val body=JSONObject().put("platform","web")
        store.getString("device_id",null)?.let { body.put("device_id",it) }
        val request=Request.Builder().url(AnyContract.BASE+"/v1/auth/session").header("User-Agent",UA)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val d=json(call(request,256_000)).getJSONObject("data")
        if(!d.isNull("user"))throw ErrorLoadingException("AnyShort konuk oturumu bekleniyordu.")
        val access=AnyContract.text(d,"access_token");val device=AnyContract.text(d,"device_id")
        if(access.isBlank() || access.length>8192 || access.any { it.code<33 || it.code>126 } || device.isBlank() || device.length>512 || device.any { it.code<33 || it.code>126 })
            throw ErrorLoadingException("AnyShort konuk oturumu tanınmadı.")
        // Never replace an existing device identity to evade source limits.
        val previous=store.getString("device_id",null)
        if(previous!=null && previous!=device)throw ErrorLoadingException("AnyShort konuk kimliği değişti; işlem durduruldu.")
        val saved=withContext(Dispatchers.IO){store.edit().putString("device_id",device).commit()}
        if(!saved)throw ErrorLoadingException("AnyShort konuk kimliği kaydedilemedi.")
        permitted();token=access;return access
    }
    suspend fun api(path:String,query:Map<String,String> = emptyMap()):JSONObject {
        require(AnyContract.apiAllowed(path,query.keys))
        val url=(AnyContract.BASE+"/v1"+path).toHttpUrl().newBuilder().apply { query.forEach { (k,v)->addQueryParameter(k,v) } }.build()
        var access=sessionMutex.withLock { permitted();token ?: guest() }
        suspend fun get()=call(Request.Builder().url(url).header("User-Agent",UA).header("Authorization","Bearer $access").get().build(),2_000_000,allowSessionRenew=true)
        var reply=get()
        if(reply.status==401) {
            // Publisher documents exactly one normal session renewal, reusing our persisted identity.
            access=sessionMutex.withLock { permitted();if(token!=access && token!=null)token!! else guest() }
            reply=get()
        }
        if(reply.status==401){stopAccess();throw ErrorLoadingException("AnyShort konuk erişimini reddetti.")}
        return json(reply)
    }
    suspend fun captionBytes(raw:String,referer:String):ByteArray? {
        var target=AnyContract.mediaUrl(raw)?.toHttpUrl() ?: return null
        for(hop in 0..3) {
            permitted()
            val request=Request.Builder().url(target).header("User-Agent",UA).header("Referer",referer).get().build()
            val reply=call(request,512_000)
            if(reply.status in setOf(301,302,303,307,308)) {
                if(hop==3)return null
                target=AnyContract.mediaUrl(reply.location?.toString().orEmpty())?.toHttpUrl() ?: return null
                continue
            }
            return if(reply.status==200)CaptionBuffer.vtt(reply.body)else null
        }
        return null
    }
    fun close(){closed=true;token=null;client.dispatcher.cancelAll();client.connectionPool.evictAll()}
    companion object {
        const val UA="ArcanaDrama-AnyShort/1"
        fun publicAddresses(host:String):List<InetAddress> = Dns.SYSTEM.lookup(host).also { if(it.isEmpty() || it.any(::privateAddress))throw IOException("Public publisher required") }
        private fun privateAddress(a:InetAddress)=a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress || a.address.let {
            if(it.size==16)(it[0].toInt() and 0xfe)==0xfc else {
                val first=it[0].toInt() and 255;val second=it[1].toInt() and 255
                first==0 || first>=224 || first==100 && second in 64..127 || first==198 && second in 18..19
            }
        }
    }
}
