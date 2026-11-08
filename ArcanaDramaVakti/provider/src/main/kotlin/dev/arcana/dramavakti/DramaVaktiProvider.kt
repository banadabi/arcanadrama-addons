package dev.arcana.dramavakti

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.ConcurrentHashMap

class DramaVaktiProvider(context:Context):MainAPI() {
    override var name="(Arcana Drama) DramaVakti"
    override var mainUrl=DramaVaktiContract.BASE
    override var lang="tr"
    override val supportedTypes=setOf(TvType.TvSeries)
    override val hasMainPage=true
    override val hasQuickSearch=true
    override val mainPage=mainPageOf("/" to "Öne Çıkanlar","catalog" to "Diziler","dub" to "Türkçe Dublaj","sub" to "Türkçe Altyazı")
    private val http=DramaVaktiHttp(context.applicationContext)
    private fun DramaVaktiContract.Card.response()=newTvSeriesSearchResponse(title,url,TvType.TvSeries) {
        posterUrl=poster;episodes=count;year=this@response.year
    }
    private inline fun <T> parse(block:()->T):T=try { block() }
        catch(e:CancellationException) { throw e }
        catch(e:ErrorLoadingException) { throw e }
        catch(_:Exception) { throw ErrorLoadingException("DramaVakti dizi veya bölüm yanıtı doğrulanamadı.") }
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        require(page>0 && request.data in setOf("/","catalog","dub","sub"))
        if(request.data=="/") {
            if(page>1)return newHomePageResponse(request.name,emptyList(),false)
            val cards=parse { DramaVaktiContract.home(http.text(mainUrl+"/").body) }
            if(cards.isEmpty())throw ErrorLoadingException("DramaVakti katalog kartları bulunamadı.")
            return newHomePageResponse(request.name,cards.map { it.response() },false)
        }
        val url="$mainUrl/diziler".toHttpUrl().newBuilder()
            .apply { if(page>1)addQueryParameter("page",page.toString());if(request.data=="dub")addQueryParameter("audio","dubbed");if(request.data=="sub")addQueryParameter("audio","subtitled") }.build().toString()
        val listing=parse { DramaVaktiContract.listing(http.text(url).body,page) }
        return newHomePageResponse(request.name,listing.cards.map { it.response() },listing.more)
    }
    override suspend fun search(query:String):List<SearchResponse> {
        val q=query.trim();if(q.length<2)return emptyList()
        val url="$mainUrl/diziler".toHttpUrl().newBuilder().addQueryParameter("q",q).build().toString()
        return parse { DramaVaktiContract.listing(http.text(url).body,1).cards.map { it.response() } }
    }
    override suspend fun quickSearch(query:String):List<SearchResponse> {
        val q=query.trim();if(q.length<2)return emptyList()
        val url="$mainUrl/api/search".toHttpUrl().newBuilder().addQueryParameter("q",q).build().toString()
        return parse { DramaVaktiContract.quickSearch(http.text(url).body,q).map { it.response() } }
    }
    override suspend fun load(url:String):LoadResponse? {
        val slug=DramaVaktiContract.detailSlug(url) ?: return null
        val s=parse { DramaVaktiContract.series(http.text(url).body,slug) }
        val episodes=s.chapters.map { c->newEpisode(DramaVaktiContract.encode(c)) { season=1;episode=c.number;name="Bölüm ${c.number}";posterUrl=s.poster } }
        return newTvSeriesLoadResponse(s.title,s.url,TvType.TvSeries,episodes) {
            posterUrl=s.poster;plot=s.plot;tags=s.tags;year=s.year;score=Score.from10(s.score)
        }
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val c=DramaVaktiContract.decode(data) ?: return false
        val page=http.text(c.url)
        val playback=parse { DramaVaktiContract.playback(page.body,c) }
        val media=http.verifyVideo(playback.url,page.agent,c.url,playback.hls)
        val ready=ConcurrentHashMap<Int,DramaVaktiContract.Caption>();val slots=Semaphore(4)
        val subs=playback.captions.sortedBy { if(it.language=="tr")0 else if(it.language=="en")1 else 2 }
        withTimeoutOrNull(4000) {
            coroutineScope { subs.mapIndexed { i,s->async { slots.withPermit { if(http.validCaption(s.url,page.agent,c.url))ready[i]=s } } }.awaitAll() }
        }
        ready.toSortedMap().values.forEach { s->
            subtitleCallback(newSubtitleFile(s.language,s.url) {
                headers=buildMap { put("User-Agent",page.agent);if(DramaVaktiContract.own(s.url)!=null)put("Referer",c.url) }
            })
        }
        callback(newExtractorLink(name,"DramaVakti",media.url,if(media.hls)ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            referer=if(DramaVaktiContract.own(media.url)!=null)c.url else ""
            headers=mapOf("User-Agent" to page.agent);quality=Qualities.Unknown.value
        })
        return true
    }
    fun close()=http.close()
}
