package dev.arcana.verticalshorts

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import org.json.JSONObject

class VerticalProvider(context:Context):MainAPI() {
    override var name=VerticalContract.NAME
    override var mainUrl=VerticalContract.BASE
    override var lang="tr"
    override val hasMainPage=true
    override val hasQuickSearch=true
    override val hasChromecastSupport=false
    override val hasDownloadSupport=false
    override val supportedTypes=setOf(TvType.TvSeries)
    private val settings=VerticalSettings(context)
    override val mainPage get()=settings.request().let { mainPageOf(it to if(it=="home")"Ana Sayfa"else"Arama Sonuçları") }
    private val session=VerticalSession(context)
    private val http=VerticalHttp(context,session)
    private val captions=CaptionBuffer()
    private val media=VerticalMedia(http,captions)
    private val cacheMutex=Mutex()
    private var cacheAt=0L
    private var cache=emptyList<VerticalContract.Row>()
    @Volatile private var closed=false
    override fun getVideoInterceptor(extractorLink:ExtractorLink):Interceptor?=media.interceptor(extractorLink)
    fun showSettings(owner:Context)=settings.show(owner,{!closed},{cacheAt=0L;cache=emptyList()})
    private inline fun <T> parse(stage:String="metadata",block:()->T):T=try { block() }
        catch(e:CancellationException){throw e}
        catch(e:ErrorLoadingException){throw e}
        catch(e:Exception){android.util.Log.i("ArcanaVerticalShorts","parse stage="+stage+" exception="+e.javaClass.simpleName);throw ErrorLoadingException("VerticalShorts istenen dizi veya bölümü doğrulamadı.")}
    private fun VerticalContract.Card.response()=newTvSeriesSearchResponse(title,url,TvType.TvSeries) {
        posterUrl=poster;episodes=count
    }
    private fun searchUrl(q: String) = (mainUrl + "/tr/search").toHttpUrl().newBuilder()
        .addQueryParameter("q", q.take(200)).build().toString()
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        if(page<1)throw ErrorLoadingException("VerticalShorts katalog isteği tanınmadı.")
        if(request.data!="home") {
            val q=parse { VerticalSettings.query(request.data) }
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val result = parse { VerticalContract.searchHtml(http.html(searchUrl(q))) }
            return newHomePageResponse(request.name,result.map { it.response() },false)
        }
        if(page>1)return newHomePageResponse(request.name,emptyList(),false)
        val rows=cacheMutex.withLock {
            if(closed)throw ErrorLoadingException("VerticalShorts eklentisi kapalı.")
            val now=android.os.SystemClock.elapsedRealtime()
            if(cache.isNotEmpty() && now>=cacheAt && now-cacheAt<=120_000)cache else {
                parse { VerticalContract.home(http.html(mainUrl+"/tr")) }.also {
                    if(!closed){cache=it;cacheAt=android.os.SystemClock.elapsedRealtime()}
                }
            }
        }
        return newHomePageResponse(rows.map { row->HomePageList(row.name,row.cards.map { it.response() }) },false)
    }
    override suspend fun search(query:String):List<SearchResponse> {
        val q=query.trim();if(q.isEmpty())return emptyList()
        return parse { VerticalContract.searchHtml(http.html(searchUrl(q))).map { it.response() } }
    }
    override suspend fun quickSearch(query:String)=search(query)
    override suspend fun load(url:String):LoadResponse? {
        if (!VerticalContract.detailUrl(url)) return null
        val canonical = url
        val detail=http.html(canonical)
        val first=parse { VerticalContract.firstWatch(detail,canonical) }
        val watch=http.html(first)
        val series=parse { VerticalContract.series(watch,canonical) }
        val episodes=series.chapters.map { c->newEpisode(VerticalContract.encode(c)) {
            season=1;episode=c.number;name="${c.number}. Bölüm";posterUrl=series.poster
            // No guessed runtime: the player's actual media duration decides episode end.
        } }
        return newTvSeriesLoadResponse(series.title,canonical,TvType.TvSeries,episodes) {
            posterUrl=series.poster;plot=series.plot;tags=series.tags
        }
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val c=VerticalContract.decode(data) ?: return false
        if (c.locked) throw ErrorLoadingException("Bu bölüm kaynakta kilitli.")
        val play=parse("links") { VerticalContract.playback(http.play(c),c) }
        val video=http.publicMedia(play.video)
        val ua=session.userAgent.takeIf { it.isNotBlank() } ?: throw ErrorLoadingException("VerticalShorts normal oturumu hazır değil.")
        val ready=mutableListOf<Pair<String,String>>()
        withTimeoutOrNull(7000) {
            for(caption in play.captions) {
                http.captionBytes(caption.url,c.url)?.let { ready.add(caption.language to captions.register(it)) }
                if(ready.size>=12)break
            }
        }
        if(closed)throw ErrorLoadingException("VerticalShorts eklentisi kapalı.")
        http.permitted()
        val cookie = session.cookies(mainUrl + "/")
        http.permitted() // Recheck after the final suspension and before any publication.
        val routed = media.register(video,c.url,ua,cookie,null)
        ready.forEach { (language,link) ->
            http.permitted()
            subtitleCallback(newSubtitleFile(language,link) { headers=mapOf("User-Agent" to ua,"Referer" to c.url) })
        }
        http.permitted()
        callback(newExtractorLink(name,name,routed,if(play.hls)ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            referer=c.url;headers=mapOf("User-Agent" to ua);quality=Qualities.Unknown.value
        })
        return true
    }
    fun close(){closed=true;cache=emptyList();media.close();http.close();session.close();captions.close()}
}
