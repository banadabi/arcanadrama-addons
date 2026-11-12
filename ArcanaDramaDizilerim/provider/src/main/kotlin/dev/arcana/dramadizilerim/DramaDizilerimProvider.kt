package dev.arcana.dramadizilerim

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.ConcurrentHashMap

class DramaDizilerimProvider(context:Context):MainAPI() {
    override var name="(Arcana Drama) DramaDizilerim"
    override var mainUrl=DramaDizilerimContract.BASE
    override var lang="tr"
    override val supportedTypes=setOf(TvType.TvSeries)
    override val hasMainPage=true
    override val hasQuickSearch=true
    override val mainPage=mainPageOf("/" to "Öne Çıkanlar","/diziler" to "Diziler")
    private val http=DramaDizilerimHttp(context.applicationContext)
    private fun DramaDizilerimContract.Card.response()=newTvSeriesSearchResponse(title,url,TvType.TvSeries) {
        posterUrl=poster;episodes=count;year=this@response.year
    }
    private inline fun <T> parse(block:()->T):T=try { block() }
        catch(e:CancellationException) { throw e }
        catch(e:ErrorLoadingException) { throw e }
        catch(_:Exception) { throw ErrorLoadingException("DramaDizilerim dizi veya bölüm yanıtı doğrulanamadı.") }
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        require(page>0 && request.data in setOf("/","/diziler"))
        if(request.data=="/") {
            if(page>1)return newHomePageResponse(request.name,emptyList(),false)
            val cards=parse { DramaDizilerimContract.home(http.text(mainUrl+"/").body) }
            if(cards.isEmpty())throw ErrorLoadingException("DramaDizilerim katalog kartları bulunamadı.")
            return newHomePageResponse(request.name,cards.map { it.response() },false)
        }
        val url=(mainUrl+"/diziler").toHttpUrl().newBuilder().apply { if(page>1)addQueryParameter("page",page.toString()) }.build().toString()
        val list=parse { DramaDizilerimContract.listing(http.text(url).body,page) }
        return newHomePageResponse(request.name,list.cards.map { it.response() },list.more)
    }
    override suspend fun quickSearch(query:String):List<SearchResponse> {
        val q=query.trim();if(q.length<2)return emptyList()
        val url=(mainUrl+"/api/catalog").toHttpUrl().newBuilder().addQueryParameter("q",q).addQueryParameter("language","all").addQueryParameter("pageSize","6").build().toString()
        return parse { DramaDizilerimContract.quickSearch(http.text(url).body).map { it.response() } }
    }
    override suspend fun search(query:String):List<SearchResponse> {
        val q=query.trim();if(q.length<2)return emptyList()
        val url=(mainUrl+"/diziler").toHttpUrl().newBuilder().addQueryParameter("q",q).build().toString()
        return parse { DramaDizilerimContract.search(http.text(url).body,q).map { it.response() } }
    }
    override suspend fun load(url:String):LoadResponse? {
        val slug=DramaDizilerimContract.detailSlug(url) ?: return null
        val s=parse { DramaDizilerimContract.series(http.text("$mainUrl/dizi/$slug").body,slug) }
        val chapters=s.chapters.map { c->newEpisode(DramaDizilerimContract.encode(c)) { season=c.season;episode=c.number;posterUrl=s.poster } }
        return newTvSeriesLoadResponse(s.title,s.url,TvType.TvSeries,chapters) {
            posterUrl=s.poster;plot=s.plot;tags=s.tags;year=s.year;score=Score.from10(s.score)
        }
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val c=DramaDizilerimContract.decode(data) ?: return false
        val page=http.text(c.url)
        val watch=parse { DramaDizilerimContract.watch(page.body,c) }
        val api=(mainUrl+"/api/playback").toHttpUrl().newBuilder().addQueryParameter("episodeId",watch.episodeId).build()
        var playback:DramaDizilerimContract.Playback?=null
        var resolvedAgent=page.agent
        val readyMedia=mutableListOf<Pair<DramaDizilerimContract.Link,DramaDizilerimHttp.Media>>()
        // The publisher offers refresh=1 for a failed/stale resolved stream. Metadata denial/429 propagates.
        for(attempt in 0..1) {
            val request=api.newBuilder().apply { if(attempt==1)addQueryParameter("refresh","1") }.build()
            val doc=http.text(request.toString(),c.url)
            val current=parse { DramaDizilerimContract.playback(doc.body,c) }
            for(link in current.links) {
                try {
                    val media=http.verifyVideo(link.url,doc.agent,c.url,link.hls)
                    readyMedia.add(link to media)
                } catch(e:CancellationException) { throw e }
                catch(e:DramaDizilerimHttp.MediaDenied) {
                    throw ErrorLoadingException(if(e.status==429)"DramaDizilerim medya kaynağı istek sınırı bildirdi; daha sonra deneyin."else"DramaDizilerim medya kaynağı erişime izin vermedi.")
                }
                catch(_:ErrorLoadingException) { http.throwIfHeld() }
            }
            if(readyMedia.isNotEmpty()) { playback=current;resolvedAgent=doc.agent;break }
        }
        val selected=playback ?: throw ErrorLoadingException("DramaDizilerim bu bölümün videosunu şu an sağlayamıyor.")
        val ready=ConcurrentHashMap<Int,DramaDizilerimContract.Caption>();val slots=Semaphore(3)
        val captions=selected.captions.sortedBy { if(it.language=="tr")0 else if(it.language=="en")1 else 2 }
        withTimeoutOrNull(4000) {
            coroutineScope { captions.mapIndexed { i,s->async { slots.withPermit { if(http.validCaption(s.url,resolvedAgent,c.url))ready[i]=s } } }.awaitAll() }
        }
        ready.toSortedMap().values.forEach { s->
            subtitleCallback(newSubtitleFile(s.language,s.url) { headers=mapOf("User-Agent" to resolvedAgent,"Referer" to if(DramaDizilerimContract.own(s.url)!=null)c.url else mainUrl+"/") })
        }
        readyMedia.distinctBy { it.second.url }.forEach { (link,media)->
            callback(newExtractorLink(name,if(link.compatible)"DramaDizilerim • Uyumlu"else"DramaDizilerim",media.url,if(media.hls)ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                referer=if(DramaDizilerimContract.own(media.url)!=null)c.url else mainUrl+"/"
                headers=mapOf("User-Agent" to resolvedAgent);quality=Qualities.Unknown.value
            })
        }
        return true
    }
    fun close()=http.close()
}
