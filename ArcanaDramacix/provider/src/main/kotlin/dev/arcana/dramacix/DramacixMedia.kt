package dev.arcana.dramacix

import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException

/** Source-issued media routes and public CDNs. First-party cookies stay on two exact source media paths; no redirects. */
internal class DramacixMedia {
    private val mediaPaths=setOf("/live.php","/live.m3u8")
    private val sourceCookies=ConcurrentHashMap<String,List<Cookie>>()
    private val cookieJar=object:CookieJar {
        override fun saveFromResponse(url:HttpUrl,cookies:List<Cookie>) = Unit
        override fun loadForRequest(url:HttpUrl):List<Cookie> =
            if(url.isHttps && url.host=="dramacix.com" && url.encodedPath in mediaPaths)
                sourceCookies[url.encodedPath].orEmpty().filter { it.matches(url) }
            else emptyList()
    }
    private suspend fun refreshSourceCookies() {
        for(path in mediaPaths) {
            val url=(DramacixContract.BASE+path).toHttpUrl()
            val raw=DramacixLogin.cookies(url.toString())
            sourceCookies[path]=raw.split(';').mapNotNull { value -> Cookie.parse(url,value.trim()+"; Path="+path+"; Secure") }
        }
    }
    private fun validateRequest(url:HttpUrl) {
        if(DramacixContract.publicHttps(url.toString())==null)throw IOException("Public publisher required")
        val host=url.host.lowercase().trimEnd('.')
        if(host=="dramacix.com" || host.endsWith(".dramacix.com")) {
            if(url.host!="dramacix.com")throw IOException("Canonical publisher required")
            val resource=DramacixContract.resource(url.toString(),DramacixContract.BASE+"/") ?: throw IOException("Publisher media route required")
            val inner=resource.upstream ?: throw IOException("Publisher target required")
            val host=java.net.URI(inner).host
            Dns.SYSTEM.lookup(host).also { if(it.isEmpty() || it.any(::privateAddress))throw IOException("Public publisher required") }
        }
    }
    private val client=OkHttpClient.Builder().cookieJar(cookieJar)
        .addInterceptor { chain -> validateRequest(chain.request().url);chain.proceed(chain.request()) }
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(7,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS)
        .dns { host -> Dns.SYSTEM.lookup(host).also { if(it.isEmpty() || it.any(::privateAddress))throw IOException("Public publisher required") } }.build()
    private val streaming=client.newBuilder().callTimeout(0,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    private var routed:DramacixHttpsTransport?=null
    @Synchronized private fun transport():DramacixHttpsTransport = routed?.takeUnless { it.isClosed }
        ?: DramacixHttpsTransport { raw -> DramacixContract.publicHttps(raw)?.toASCIIString()?.toHttpUrl() ?: throw IOException("Public publisher required") }.also { routed=it }
    fun playbackUrl(r:DramacixContract.Resource,ua:String,format:String):String = transport().register(r.url,r.referer,streaming,format,ua)
    fun interceptor():Interceptor = transport()
    fun subtitleUrl(r:DramacixContract.Resource,ua:String,prepared:ByteArray?=null):String {
        val vtt=prepared?.let(DramacixCaption::vtt)
        return transport().register(r.url,r.referer,streaming,
            if(vtt!=null)"vtt"else if(r.contentPath.lowercase().endsWith(".srt"))"srt"else"vtt",ua,bytes=vtt)
    }
    fun invalidateCaptions() { routed?.clearPreparedCaptions();sourceCookies.clear() }
    private suspend fun read(r:DramacixContract.Resource,ua:String,limit:Int,range:Boolean):ByteArray {
        if(DramacixContract.publicHttps(r.url)==null)throw ErrorLoadingException("DramaCix yayıncı adresi tanınmadı.")
        if(r.upstream!=null)refreshSourceCookies()
        val request=Request.Builder().url(r.url).header("User-Agent",ua).header("Referer",r.referer)
            .apply { if(range)header("Range","bytes=0-${limit-1}") }.build()
        val call=client.newCall(request)
        return suspendCancellableCoroutine { c ->
            c.invokeOnCancellation { call.cancel() }
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException) { if(c.isActive)c.resumeWithException(ErrorLoadingException("DramaCix yayıncı bağlantısı tamamlanamadı.")) }
                override fun onResponse(call:Call,response:Response) {
                    try {
                        val bytes=response.use { value ->
                            if(value.code !in setOf(200,206))throw ErrorLoadingException("DramaCix yayıncısı HTTP ${value.code} yanıtı verdi.")
                            val source=value.body.source();source.request(limit.toLong()+if(range)0 else 1)
                            if(!range && source.buffer.size>limit)throw ErrorLoadingException("DramaCix altyazı boyutu sınırını aştı.")
                            source.readByteArray(minOf(source.buffer.size,limit.toLong()))
                        }
                        if(c.isActive)c.resume(bytes,onCancellation={_,_,_->})
                    } catch(e:Exception) {
                        if(c.isActive)c.resumeWithException(if(e is ErrorLoadingException)e else ErrorLoadingException("DramaCix yayıncı yanıtı tanınmadı."))
                    }
                }
            })
        }
    }
    suspend fun format(r:DramacixContract.Resource,ua:String):String {
        val b=read(r,ua,512,true);val text=String(b,Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n','\t')
        if(text.startsWith("#EXTM3U"))return "hls"
        if(b.size>=12 && String(b,4,4,Charsets.US_ASCII)=="ftyp")return "mp4"
        if(text.startsWith("<MPD") || text.startsWith("<?xml") && text.contains("<MPD"))return "dash"
        throw ErrorLoadingException("DramaCix yayıncısı geçerli video içeriği döndürmedi.")
    }
    suspend fun validatedCaption(r:DramacixContract.Resource,ua:String):ByteArray? = try {
        val b=read(r,ua,512000,false);val text=String(b,Charsets.UTF_8)
        b.takeIf(DramacixCaption::hasTiming)
    } catch(e:CancellationException) { throw e } catch(_:Exception) { null }
    fun close() { routed?.close();sourceCookies.clear();client.dispatcher.cancelAll();streaming.dispatcher.cancelAll();client.connectionPool.evictAll() }
    companion object {
        private fun privateAddress(a:InetAddress)=a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress || a.address.let { it.size==16&&(it[0].toInt()and 0xfe)==0xfc }
    }
}
