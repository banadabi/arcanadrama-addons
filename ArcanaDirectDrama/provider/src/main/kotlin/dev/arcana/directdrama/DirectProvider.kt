package dev.arcana.directdrama

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

class DirectProvider(context:Context):MainAPI() {
    override var name=DirectContract.NAME
    override var mainUrl=DirectContract.BASE
    override var lang="tr"
    override val hasMainPage=true
    override val hasQuickSearch=true
    override val hasChromecastSupport=false
    override val hasDownloadSupport=false
    override val supportedTypes=setOf(TvType.TvSeries)
    private val settings=DirectSettings(context)
    override val mainPage get()=settings.request().let { mainPageOf(it to if(it=="home")"Ana Sayfa"else"Arama Sonuçları") }
    private val session=DirectSession(context)
    private val http=DirectHttp(context,session)
    private val captions=CaptionBuffer()
    private val media=DirectMedia(http,captions)
    private val cacheMutex=Mutex()
    private var cacheAt=0L
    private var cache=emptyList<DirectContract.Row>()
    @Volatile private var closed=false
    override fun getVideoInterceptor(extractorLink:ExtractorLink):Interceptor?=media.interceptor(extractorLink)
    fun showSettings(owner:Context)=settings.show(owner,{!closed},{cacheAt=0L;cache=emptyList()})
    private inline fun <T> parse(block:()->T):T=try { block() }
        catch(e:CancellationException){throw e}
        catch(e:ErrorLoadingException){throw e}
        catch(_:Exception){throw ErrorLoadingException("DirectDrama istenen dizi veya bölümü doğrulamadı.")}
    private fun DirectContract.Card.response()=newTvSeriesSearchResponse(title,DirectContract.seriesUrl(slug),TvType.TvSeries) {
        posterUrl=poster;episodes=count
    }
    private fun searchUrl(q:String,page:Int)=(mainUrl+"/api/series/search").toHttpUrl().newBuilder()
        .addQueryParameter("locale","tr").addQueryParameter("q",q.take(300)).addQueryParameter("page",page.toString()).build().toString()
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        if(page<1)throw ErrorLoadingException("DirectDrama katalog isteği tanınmadı.")
        if(request.data!="home") {
            val q=parse { DirectSettings.query(request.data) }
            val result=parse { DirectContract.searchPage(http.api(searchUrl(q,page)),page) }
            return newHomePageResponse(request.name,result.cards.map { it.response() },result.hasNext)
        }
        if(page>1)return newHomePageResponse(request.name,emptyList(),false)
        val rows=cacheMutex.withLock {
            if(closed)throw ErrorLoadingException("DirectDrama eklentisi kapalı.")
            val now=android.os.SystemClock.elapsedRealtime()
            if(cache.isNotEmpty() && now>=cacheAt && now-cacheAt<=120_000)cache else {
                parse { DirectContract.home(http.html(mainUrl+"/tr")) }.also {
                    if(!closed){cache=it;cacheAt=android.os.SystemClock.elapsedRealtime()}
                }
            }
        }
        return newHomePageResponse(rows.map { row->HomePageList(row.name,row.cards.map { it.response() }) },false)
    }
    override suspend fun search(query:String):List<SearchResponse> {
        val q=query.trim();if(q.isEmpty())return emptyList()
        return parse { DirectContract.searchPage(http.api(searchUrl(q,1)),1).cards.map { it.response() } }
    }
    override suspend fun quickSearch(query:String)=search(query)
    override suspend fun load(url:String):LoadResponse? {
        val slug=DirectContract.detailSlug(url) ?: return null
        val canonical=DirectContract.seriesUrl(slug)
        val detail=http.html(canonical)
        val first=parse { DirectContract.firstWatch(detail,slug) }
        val watch=http.html(first)
        val series=parse { DirectContract.series(detail,watch,slug) }
        val episodes=series.chapters.map { c->newEpisode(DirectContract.encode(c)) {
            season=1;episode=c.number;name="${c.number}. Bölüm";posterUrl=series.poster
            // No guessed runtime: the player's actual media duration decides episode end.
        } }
        return newTvSeriesLoadResponse(series.title,canonical,TvType.TvSeries,episodes) {
            posterUrl=series.poster;plot=series.plot;tags=series.tags
        }
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val c=DirectContract.decode(data) ?: return false
        val play=parse { DirectContract.playback(http.play(c),c) }
        val video=http.publicMedia(play.video)
        val ua=session.userAgent.takeIf { it.isNotBlank() } ?: throw ErrorLoadingException("DirectDrama normal oturumu hazır değil.")
        val ready=mutableListOf<Pair<String,String>>()
        withTimeoutOrNull(7000) {
            for(caption in play.captions) {
                http.captionBytes(caption.url,c.url)?.let { ready.add(caption.language to captions.register(it)) }
                if(ready.size>=12)break
            }
        }
        if(closed)throw ErrorLoadingException("DirectDrama eklentisi kapalı.")
        http.permitted()
        val routed=media.register(video,c.url,ua,session.cookies(mainUrl+"/"),play.expiresAt)
        ready.forEach { (language,link)->subtitleCallback(newSubtitleFile(language,link) {
            headers=mapOf("User-Agent" to ua,"Referer" to c.url)
        }) }
        callback(newExtractorLink(name,name,routed,if(play.hls)ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            referer=c.url;headers=mapOf("User-Agent" to ua);quality=Qualities.Unknown.value
        })
        return true
    }
    fun close(){closed=true;cache=emptyList();media.close();http.close();session.close();captions.close()}
}
