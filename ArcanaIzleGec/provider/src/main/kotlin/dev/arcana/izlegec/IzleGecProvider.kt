package dev.arcana.izlegec

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Source pages use only the user's local source session; media never receives account cookies. */
class IzleGecProvider(
    private val sessionCookies: suspend (String) -> String = { "" },
    private val saveSessionCookies: suspend (String, List<String>) -> Unit = { _, _ -> }
) : MainAPI() {
    override var name = "(Arcana Drama) İzleGeç"
    override var mainUrl = BASE
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val mainPage = mainPageOf(
        "/yeni-eklenenler" to "Yeni Eklenenler", "/populer" to "Popüler",
        "/" to "İzleGeç Ana Sayfa", "/platform/netshort" to "NetShort",
        "/platform/freereels" to "FreeReels", "/platform/dramawave" to "DramaWave",
        "/platform/shortmax" to "ShortMax", "/platform/reelshort" to "ReelShort",
        "/platform/dramabox" to "DramaBox", "/platform/stardust" to "StarDust",
        "/platform/vibeshort" to "VibeShort", "/platform/flextv" to "FlexTV"
    )
    private val client = OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).dns { host ->
            Dns.SYSTEM.lookup(host).filterNot(::privateAddress).ifEmpty { throw IOException("Public source unavailable") }
        }.build()
    private data class Page(val url:HttpUrl,val body:String) { fun doc():Document=Jsoup.parse(body,url.toString()) }
    private fun own(raw:String):HttpUrl {
        val u=raw.toHttpUrlOrNull()?:throw ErrorLoadingException("İzleGeç adresi geçersiz.")
        if(u.scheme!="https" || u.host !in setOf("www.izlegec.com","izlegec.com") || u.port!=443 || u.username.isNotEmpty() || u.password.isNotEmpty())
            throw ErrorLoadingException("İzleGeç kaynak adresi tanınmadı.")
        return u
    }
    private fun media(raw:String):HttpUrl {
        val u=raw.toHttpUrlOrNull()?:throw ErrorLoadingException("İzleGeç medya adresi geçersiz.")
        if(u.scheme!="https" || u.host !in setOf("www.izlegec.com","izlegec.com","ha.vixolity.com") || u.port!=443 || u.username.isNotEmpty() || u.password.isNotEmpty())
            throw ErrorLoadingException("İzleGeç medya kaynağı tanınmadı.")
        return u
    }
    private fun absolute(raw:String,base:String=BASE):String=own(base).resolve(raw)?.toString()?:throw ErrorLoadingException("İzleGeç bağlantısı geçersiz.")
    private suspend fun page(raw:String,referer:String=BASE):Page=withContext(Dispatchers.IO) {
        var url=own(raw)
        repeat(4) {
            // Re-read cookies for each validated source redirect. Never attach them to CDN callbacks.
            val cookie=sessionCookies(url.toString())
            val req=Request.Builder().url(url).header("User-Agent",UA).header("Referer",referer)
                .header("Accept","text/html, application/json;q=0.9, */*;q=0.8")
                .apply { if(cookie.isNotBlank())header("Cookie",cookie) }.build()
            val call=client.newCall(req)
            val response:Response=suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:IOException) { if(continuation.isActive)continuation.resumeWithException(e) }
                    override fun onResponse(call:Call,response:Response) {
                        continuation.resume(response,onCancellation={_,value,_->value.close()})
                    }
                })
            }
            response.use { r ->
                // Browser store enforces cookie Domain/Path rules for this validated response origin.
                saveSessionCookies(url.toString(), r.headers.values("Set-Cookie"))
                if(r.code in listOf(301,302,303,307,308)) {
                    val target=r.header("Location")?.let { url.resolve(it) }?:throw ErrorLoadingException("İzleGeç yönlendirmesi geçersiz.")
                    url=own(target.toString());return@use
                }
                if(r.code in listOf(401,403))throw ErrorLoadingException("İzleGeç bu içerik için kaynak erişimi veya üyelik istiyor.")
                if(r.code==429)throw ErrorLoadingException("İzleGeç istek sınırı bildirdi; daha sonra deneyin.")
                if(!r.isSuccessful)throw ErrorLoadingException("İzleGeç HTTP ${r.code}")
                val b=r.body;val source=b.source();source.request(3_000_001)
                if(source.buffer.size>3_000_000)throw ErrorLoadingException("İzleGeç yanıtı çok büyük.")
                return@withContext Page(url,source.readUtf8())
            }
        }
        throw ErrorLoadingException("İzleGeç yönlendirme sınırı.")
    }
    private fun slug(raw:String):String? {
        val u=runCatching { own(raw) }.getOrNull()?:return null
        val parts=u.pathSegments.filter(String::isNotEmpty)
        return parts.takeIf { it.size==2 && it[0]=="drama-dizi" }?.get(1)
            ?.takeIf { it.length<=240 && it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) }
    }
    private fun number(raw:String,expectedSlug:String):Int? {
        val u=runCatching { own(raw) }.getOrNull()?:return null
        val p=u.pathSegments.filter(String::isNotEmpty)
        if(p.size!=3 || p[0]!="drama-dizi" || p[1]!=expectedSlug)return null
        return Regex("bolum-([1-9][0-9]{0,5})").matchEntire(p[2])?.groupValues?.get(1)?.toIntOrNull()
    }
    private fun card(el:Element):SearchResponse? {
        val url=runCatching{absolute(el.attr("href"))}.getOrNull()?:return null
        if(slug(url)==null)return null
        val title=el.selectFirst(".meta .title")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?:el.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf(String::isNotBlank)?:return null
        val image=el.selectFirst("img[src]")?.attr("src")?.let{runCatching{absolute(it)}.getOrNull()}
        val total=Regex("[0-9]+").find(el.selectFirst(".ep-badge")?.text().orEmpty())?.value?.toIntOrNull()
        return newTvSeriesSearchResponse(title,url,TvType.TvSeries) { posterUrl=image;episodes=total }
    }
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        val path=request.data
        if(path!="/" && path!="/populer" && path!="/yeni-eklenenler" && !path.matches(Regex("/platform/[a-z0-9-]+")))
            throw ErrorLoadingException("İzleGeç kategori isteği tanınmadı.")
        if(path=="/" && page>1)return newHomePageResponse(request.name,emptyList(),false)
        val url=own(absolute(path)).newBuilder().apply { if(page>1)addQueryParameter("sayfa",page.toString()) }.build()
        val doc=page(url.toString()).doc()
        if(path=="/") {
            val lists=doc.select("section").mapNotNull { section ->
                val heading=section.selectFirst("h2.section-title")?.text()?:return@mapNotNull null
                val items=section.select("a.card[href]").mapNotNull(::card).distinctBy{it.url}
                items.takeIf{it.isNotEmpty()}?.let{HomePageList(heading,it)}
            }
            return newHomePageResponse(lists,false)
        }
        val items=doc.select("a.card[href]").mapNotNull(::card).distinctBy{it.url}
        val next=doc.select(".pagination a[href]").any { link ->
            runCatching { own(absolute(link.attr("href"))).queryParameter("sayfa")?.toIntOrNull()?.let{it>page}==true }.getOrDefault(false)
        }
        return newHomePageResponse(request.name,items,next)
    }
    override suspend fun search(query:String):List<SearchResponse> {
        if(query.isBlank())return emptyList()
        val url=own(BASE).newBuilder().addPathSegments("api/arama").addQueryParameter("q",query.trim()).build()
        val data=JSONObject(page(url.toString()).body).optJSONArray("results")?:return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val item=data.optJSONObject(i)?:return@mapNotNull null
            val link=runCatching{absolute(item.getString("url"))}.getOrNull()?:return@mapNotNull null
            if(slug(link)==null)return@mapNotNull null
            val title=item.optString("title").takeIf{it.isNotBlank()}?:return@mapNotNull null
            newTvSeriesSearchResponse(title,link,TvType.TvSeries) {
                posterUrl=item.optString("cover_image").takeIf{it.isNotBlank()}?.let{absolute(it)}
                episodes=item.optInt("episode_count").takeIf{it>0}
            }
        }
    }
    override suspend fun quickSearch(query:String)=search(query)
    override suspend fun load(url:String):LoadResponse? {
        val s=slug(url)?:return null
        val canonical=absolute("/drama-dizi/$s")
        val doc=page(canonical).doc()
        val title=doc.selectFirst("h1")?.text()?.takeIf(String::isNotBlank)?:throw ErrorLoadingException("İzleGeç dizi başlığı yok.")
        val id=doc.selectFirst("[data-series-id]")?.attr("data-series-id")?.toLongOrNull()?.takeIf{it>0}
            ?:throw ErrorLoadingException("İzleGeç dizi kimliği yok.")
        val image=doc.selectFirst("meta[property=og:image]")?.attr("content")?.let{absolute(it)}
            ?:doc.selectFirst(".series-poster img[src]")?.attr("src")?.let{absolute(it)}
        val declaredDub=doc.select(".series-tags .badge").any { it.text().equals("Dublaj",true) }
        val items=doc.select("a.ep-pill[href]").mapNotNull { node ->
            val target=absolute(node.attr("href"));val n=number(target,s)?:return@mapNotNull null
            val locked=node.hasClass("locked")
            newEpisode(JSONObject().put("v",1).put("kind","izlegec_episode").put("slug",s).put("series",id).put("n",n).put("dubbed",declaredDub).toString()) {
                season=1;episode=n;name="$n. Bölüm"+(if(locked)" · Üyelik gerekli" else "");posterUrl=image
                if(locked)description="Kaynak bu bölümü misafir oturumuna kapatıyor."
            } to locked
        }.distinctBy{it.first.episode}.sortedBy{it.first.episode}
        if(items.isEmpty())throw ErrorLoadingException("İzleGeç gerçek bölüm listesi bulunamadı.")
        val lockedCount=items.count{it.second}
        return newTvSeriesLoadResponse(title,canonical,TvType.TvSeries,items.map{it.first}) {
            posterUrl=image
            plot=doc.selectFirst("#descBox")?.wholeText()?.trim().orEmpty()+
                if(lockedCount>0)"\n\nKaynakta ${items.size} bölüm var; $lockedCount bölüm üyelik gerektiriyor." else ""
            tags=doc.select(".series-tags a").map{it.text()}.filter(String::isNotBlank).distinct()
        }
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val payload=runCatching{JSONObject(data)}.getOrNull()?:return false
        if(payload.optInt("v")!=1 || payload.optString("kind")!="izlegec_episode")return false
        val s=payload.optString("slug");val n=payload.optInt("n");val id=payload.optLong("series")
        if(id<=0 || n<=0 || slug(absolute("/drama-dizi/$s"))!=s)return false
        val watch=absolute("/drama-dizi/$s/bolum-$n");val response=page(watch);val doc=response.doc()
        if(number(response.url.toString(),s)!=n)throw ErrorLoadingException("İzleGeç bölüm yönlendirme kimliği uyuşmadı.")
        val shell=doc.selectFirst("#playerShell[data-video-url]")?:throw ErrorLoadingException("İzleGeç bu bölüm için üyelik istiyor. Eklentinin ayarlarından kendi hesabınızla giriş yapın.")
        if(shell.attr("data-series-id").toLongOrNull()!=id || shell.attr("data-episode-id").toLongOrNull()?.let{it>0}!=true)
            throw ErrorLoadingException("İzleGeç bölüm/dizi kimliği uyuşmadı.")
        val active=doc.select("a.ep-pill.current[href]").firstOrNull()
            ?:throw ErrorLoadingException("İzleGeç etkin bölüm kimliği yok.")
        if(number(absolute(active.attr("href")),s)!=n)throw ErrorLoadingException("İzleGeç etkin bölüm uyuşmadı.")
        val media=media(absolute(shell.attr("data-video-url")))
        if(!media.encodedPath.startsWith("/vs/"))throw ErrorLoadingException("İzleGeç medya rotası tanınmadı.")
        val track=doc.selectFirst("#video track[src]")
        val api=shell.attr("data-subs-api").takeIf{it.isNotBlank()}
        if(track!=null && api!=null) {
            val endpoint=own(absolute(api));val episodeId=shell.attr("data-episode-id")
            if(endpoint.encodedPath!="/api/altyazilar/$episodeId")throw ErrorLoadingException("İzleGeç altyazı kimliği uyuşmadı.")
            val rows=JSONObject(page(endpoint.toString(),watch).body).optJSONArray("tracks")
                ?:throw ErrorLoadingException("İzleGeç altyazı listesi doğrulanamadı.")
            val base=own(absolute(track.attr("src")))
            if(base.encodedPath!="/altyazi/$episodeId")throw ErrorLoadingException("İzleGeç altyazı rotası uyuşmadı.")
            for(i in 0 until (rows?.length()?:0)) {
                val sub=rows?.optJSONObject(i)?:continue;val language=sub.optString("lang")
                if(!language.matches(Regex("[a-z]{2,3}(?:-[A-Za-z]{2,4})?")))continue
                val subUrl=base.newBuilder().setQueryParameter("dil",language).fragment("subtitle.vtt").build().toString()
                subtitleCallback(newSubtitleFile(language,subUrl){headers=mapOf("User-Agent" to UA,"Referer" to watch)})
            }
        }
        callback(newExtractorLink(name,if(payload.optBoolean("dubbed",false))"İzleGeç Türkçe Dublaj" else "İzleGeç",media.toString(),ExtractorLinkType.M3U8) { referer=watch;headers=mapOf("User-Agent" to UA);quality=Qualities.Unknown.value })
        return true
    }
    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    companion object {
        private const val BASE="https://www.izlegec.com"
        internal const val UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private fun privateAddress(a:InetAddress)=a.isAnyLocalAddress||a.isLoopbackAddress||a.isLinkLocalAddress||a.isSiteLocalAddress||a.isMulticastAddress||a.address.let{it.size==16&&(it[0].toInt() and 0xfe)==0xfc}
    }
}
