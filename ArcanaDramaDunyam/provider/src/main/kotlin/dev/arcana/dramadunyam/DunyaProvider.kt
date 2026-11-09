package dev.arcana.dramadunyam

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

class DunyaProvider(context: Context) : MainAPI() {
    override var name=DunyaContract.NAME
    override var mainUrl=DunyaContract.BASE
    override var lang="tr"
    override val hasMainPage=true
    override val hasQuickSearch=true
    override val hasChromecastSupport=false
    override val hasDownloadSupport=false
    override val supportedTypes=setOf(TvType.TvSeries)
    private val settings=DunyaSettings(context)
    override val mainPage get()=settings.request().let { mainPageOf(it to if(it=="home")"Ana Sayfa"else"Arama Sonuçları") }
    private val session=DunyaSession(context)
    private val http=DunyaHttp(context,session)
    private val captions=CaptionBuffer()
    private val media=DunyaMedia(http,captions)
    private val cacheMutex=Mutex()
    private var cacheAt=0L
    private var cache=emptyList<DunyaContract.Row>()
    @Volatile private var closed=false
    override fun getVideoInterceptor(extractorLink:ExtractorLink):Interceptor?=media.interceptor(extractorLink)
    fun showSettings(owner:Context)=settings.show(owner,{!closed},{cacheAt=0L;cache=emptyList()})
    private fun DunyaContract.Card.response()=newTvSeriesSearchResponse(title,DunyaContract.seriesUrl(slug),TvType.TvSeries) {
        posterUrl=poster;episodes=count
    }
    private inline fun <T> parse(stage:String="other",block:()->T):T=try { block() }
        catch(e:CancellationException){throw e}
        catch(e:ErrorLoadingException){throw e}
        catch(e:Exception){android.util.Log.i("ArcanaDramaDunyam","parse stage=$stage exception="+e.javaClass.simpleName);throw ErrorLoadingException("DramaDünyam istenen dizi veya bölümü doğrulamadı.")}
    private fun searchUrl(query:String,page:Int):String=(mainUrl+"/api/search").toHttpUrl().newBuilder()
        .addQueryParameter("q",query.take(300)).addQueryParameter("page",page.toString()).addQueryParameter("limit","48").build().toString()
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        if(page<1)throw ErrorLoadingException("DramaDünyam katalog isteği tanınmadı.")
        if(request.data!="home") {
            val query=parse { DunyaSettings.query(request.data) }
            val result=parse { DunyaContract.page(http.api(searchUrl(query,page)),page) }
            return newHomePageResponse(request.name,result.cards.map { it.response() },result.hasNext)
        }
        if(page>1)return newHomePageResponse(request.name,emptyList(),false)
        val rows=cacheMutex.withLock {
            if(closed)throw ErrorLoadingException("DramaDünyam eklentisi kapalı.")
            val now=android.os.SystemClock.elapsedRealtime()
            if(cache.isNotEmpty() && now>=cacheAt && now-cacheAt<=120_000)cache else {
                parse { DunyaContract.home(http.api(mainUrl+"/api/home")) }.also {
                    if(!closed){cache=it;cacheAt=android.os.SystemClock.elapsedRealtime()}
                }
            }
        }
        return newHomePageResponse(rows.map { row -> HomePageList(row.name,row.cards.map { it.response() }) },false)
    }
    override suspend fun search(query:String):List<SearchResponse> {
        val q=query.trim();if(q.isEmpty())return emptyList()
        return parse { DunyaContract.page(http.api(searchUrl(q,1)),1).cards.map { it.response() } }
    }
    override suspend fun quickSearch(query:String)=search(query)
    override suspend fun load(url:String):LoadResponse? {
        // Opaque episode JSON is deliberately not a detail URL; the host may try this first.
        val slug=DunyaContract.detailSlug(url) ?: return null
        val canonical=DunyaContract.seriesUrl(slug)
        val api=(mainUrl+"/api/series").toHttpUrl().newBuilder().addPathSegment(slug).addQueryParameter("core","1").build().toString()
        val raw=http.api(api)
        // Keep the normal source page/verification context. Its responsive markup is not
        // the authoritative number set; the documented API membership predicate is.
        val html=http.html(canonical)
        android.util.Log.i("ArcanaDramaDunyam","detail rendered "+DunyaContract.structure(raw,html,slug))
        val series=parse("detail") { DunyaContract.series(raw,null,slug) }
        android.util.Log.i("ArcanaDramaDunyam","detail membership mode="+series.chapterEvidence+" chapters="+series.chapters.size)
        val episodes=series.chapters.map { chapter -> newEpisode(DunyaContract.encode(chapter)) {
            season=1;episode=chapter.number;name="${chapter.number}. Bölüm";posterUrl=series.poster
            // Publisher tooltip durations are not player end times. Media3 owns the actual duration.
        } }
        return newTvSeriesLoadResponse(series.title,canonical,TvType.TvSeries,episodes) {
            posterUrl=series.poster;plot=series.plot;tags=series.tags
        }
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val chapter=DunyaContract.decode(data) ?: return false
        val play=parse { DunyaContract.playback(http.play(chapter)) }
        val video=http.publicMedia(play.video)
        val agent=session.userAgent.takeIf { it.isNotBlank() } ?: throw ErrorLoadingException("DramaDünyam normal oturumu hazır değil.")
        val ready=mutableListOf<Pair<String,String>>()
        withTimeoutOrNull(7000) {
            for(caption in play.captions) {
                http.captionBytes(caption.url,chapter.url)?.let { ready.add(caption.language to captions.register(it)) }
                if(ready.size>=12)break
            }
        }
        if(closed)throw ErrorLoadingException("DramaDünyam eklentisi kapalı.")
        http.permitted()
        val cookie=session.cookies(mainUrl+"/")
        val routed=media.register(video,chapter.url,agent,cookie,play.expiresAt)
        ready.forEach { (language,link) -> subtitleCallback(newSubtitleFile(language,link) {
            headers=mapOf("User-Agent" to agent,"Referer" to chapter.url)
        }) }
        callback(newExtractorLink(name,name,routed,if(play.hls)ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            referer=chapter.url;headers=mapOf("User-Agent" to agent);quality=Qualities.Unknown.value
        })
        return true
    }
    fun close(){closed=true;cache=emptyList();media.close();http.close();session.close();captions.close()}
}
