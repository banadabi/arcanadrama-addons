package dev.arcana.anyshort

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import java.util.concurrent.atomic.AtomicLong

class AnyProvider(context:Context):MainAPI() {
    override var name=AnyContract.NAME
    override var mainUrl=AnyContract.BASE
    override var lang="tr"
    override val hasMainPage=true
    override val hasQuickSearch=false
    override val hasChromecastSupport=false
    override val hasDownloadSupport=false
    override val supportedTypes=setOf(TvType.TvSeries)
    private val settings=AnySettings(context)
    override val mainPage get()=settings.request().let { request->
        if(request=="home")mainPageOf("home" to "Ana Sayfa","popular" to "Popüler","newest" to "Son Güncellenenler")
        else mainPageOf(request to "Arama Sonuçları")
    }
    private val http=AnyHttp(context)
    private val captions=CaptionBuffer()
    private val media=AnyMedia(http,captions)
    private val catalogueMutex=Mutex()
    private val catalogueEpoch=AtomicLong()
    private var cachedEpoch=-1L
    private val cursors=mutableMapOf<Pair<String,Int>,String>()
    private var cachedHome=emptyList<AnyContract.Row>()
    private var homeAt=0L
    @Volatile private var closed=false
    override fun getVideoInterceptor(extractorLink:ExtractorLink):Interceptor?=media.interceptor(extractorLink)
    private inline fun <T> parse(block:()->T):T=try{block()}catch(e:CancellationException){throw e}catch(e:ErrorLoadingException){throw e}catch(_:Exception){throw ErrorLoadingException("AnyShort dizi veya bölüm bilgisi tanınmadı.")}
    private fun AnyContract.Card.response()=newTvSeriesSearchResponse(name,identity.url,TvType.TvSeries){posterUrl=poster;episodes=count}
    fun showSettings(owner:Context)=settings.show(owner,{!closed},{catalogueEpoch.incrementAndGet()})
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse=catalogueMutex.withLock {
        if(page<1)throw ErrorLoadingException("AnyShort katalog isteği tanınmadı.")
        val searchMode=request.data !in setOf("home","popular","newest")
        val query=if(searchMode)parse { AnyContract.sourceQuery(request.data) }else null
        val epoch=catalogueEpoch.get()
        val expected=if(searchMode)request.data else "home"
        val configured=settings.request()
        if(configured!=expected)
            throw ErrorLoadingException("AnyShort arama ayarı değişti; kaynak ana sayfasını yenileyin.")
        http.permitted()
        if(cachedEpoch!=epoch){cachedHome=emptyList();homeAt=0L;cursors.clear();cachedEpoch=epoch}
        fun current() {
            if(closed || catalogueEpoch.get()!=epoch || settings.request()!=expected)throw ErrorLoadingException("AnyShort arama ayarı değişti; kaynak ana sayfasını yenileyin.")
            http.permitted()
        }
        current()
        if(request.data=="home") {
            if(page>1)return@withLock newHomePageResponse(emptyList<HomePageList>(),false)
            val now=android.os.SystemClock.elapsedRealtime()
            if(cachedHome.isEmpty() || now<homeAt || now-homeAt>120_000) {
                val rows=parse { AnyContract.home(http.api("/home",mapOf("lang" to "tr"))) }
                current();cachedHome=rows;homeAt=android.os.SystemClock.elapsedRealtime()
            }
            current()
            return@withLock newHomePageResponse(cachedHome.map { HomePageList(it.name,it.cards.map { c->c.response() }) },false)
        }
        val cursor=if(page==1)null else cursors[request.data to page]
            ?: throw ErrorLoadingException("AnyShort önceki katalog sayfası henüz yüklenmedi.")
        val params=mutableMapOf("lang" to "tr","limit" to "18")
        if(searchMode)params["q"]=query!! else params["sort"]=request.data
        cursor?.let { params["cursor"]=it }
        val result=parse { AnyContract.page(http.api(if(searchMode)"/search"else"/browse",params)) }
        current()
        if(page==1)cursors.keys.removeAll { it.first==request.data }
        result.next?.let { if(it==cursor)throw ErrorLoadingException("AnyShort katalog sayfası ilerlemedi.");cursors[request.data to page+1]=it }
        while(cursors.size>400)cursors.remove(cursors.keys.first())
        newHomePageResponse(request.name,result.cards.map { it.response() },result.next!=null)
    }
    override suspend fun search(query:String):List<SearchResponse> {
        val q=query.trim().take(300);if(q.isEmpty())return emptyList()
        return parse { AnyContract.cards(http.api("/search",mapOf("lang" to "tr","q" to q,"limit" to "18")).getJSONArray("data")).map { it.response() } }
    }
    override suspend fun load(url:String):LoadResponse? {
        val title=AnyContract.identity(url) ?: return null // Opaque episode payload belongs to loadLinks.
        val detail=parse { AnyContract.detail(http.api("/titles/${title.id}",mapOf("lang" to "tr")),title) }
        val chapters=mutableListOf<AnyContract.Chapter>();var offset=0
        while(true) {
            val page=parse { AnyContract.episodes(http.api("/titles/${title.id}/episodes",mapOf("lang" to "tr","offset" to offset.toString(),"limit" to "60")),title,offset) }
            chapters.addAll(page.chapters)
            if(chapters.size>10000 || chapters.map { it.id }.distinct().size!=chapters.size || chapters.map { it.number }.distinct().size!=chapters.size)
                throw ErrorLoadingException("AnyShort bölüm kimlikleri tutarsız.")
            if(!page.hasMore)break
            if(page.received==0 || offset+page.received>10000)throw ErrorLoadingException("AnyShort bölüm sayfası ilerlemedi.")
            offset+=page.received
        }
        if(chapters.isEmpty())throw ErrorLoadingException("AnyShort izlenebilir bölüm sunmadı.")
        val episodes=chapters.sortedBy { it.number }.map { c->newEpisode(AnyContract.encode(c)){season=1;episode=c.number;name="${c.number}. Bölüm";posterUrl=detail.card.poster} }
        return newTvSeriesLoadResponse(detail.card.name,title.url,TvType.TvSeries,episodes){posterUrl=detail.card.poster;plot=detail.plot;tags=detail.tags}
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        suspend fun callbackAdmission() {
            currentCoroutineContext().ensureActive()
            if(closed)throw ErrorLoadingException("AnyShort eklentisi kapalı.")
            http.permitted()
        }
        callbackAdmission()
        val chapter=AnyContract.decode(data) ?: return false
        val streams=parse { AnyContract.playback(http.api("/episodes/${chapter.id}/play",mapOf("lang" to "tr","sub_lang" to "tr")),chapter,System.currentTimeMillis()) }
        val tracks=parse { AnyContract.subtitles(http.api("/episodes/${chapter.id}/subtitles",mapOf("lang" to "tr")),System.currentTimeMillis()) }
        val ready=mutableListOf<Pair<String,String>>()
        withTimeoutOrNull(7000) {
            for(track in tracks.take(12)) {
                val bytes=http.captionBytes(track.url,chapter.url)
                callbackAdmission()
                if(bytes!=null) {
                    val route=captions.register(bytes)
                    callbackAdmission()
                    ready.add(track.language to route)
                }
            }
        }
        callbackAdmission()
        // Only fetched, nonempty source-timed text becomes a callback; locale is no subtitle proof.
        ready.forEach { (language,url)->
            callbackAdmission()
            val subtitle=newSubtitleFile(language,url){headers=mapOf("User-Agent" to AnyHttp.UA,"Referer" to chapter.url)}
            callbackAdmission();subtitleCallback(subtitle);callbackAdmission()
        }
        streams.forEach { stream->
            callbackAdmission()
            if(stream.expiresAt<=System.currentTimeMillis()+1000)throw ErrorLoadingException("AnyShort video bağlantısının süresi doldu; bölümü yeniden açın.")
            val routed=media.register(stream.url,chapter.url,AnyHttp.UA,stream.expiresAt)
            callbackAdmission()
            val link=newExtractorLink(name,name,routed,if(stream.hls)ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO){referer=chapter.url;headers=mapOf("User-Agent" to AnyHttp.UA);quality=stream.quality}
            callbackAdmission();callback(link);callbackAdmission()
        }
        return true
    }
    fun close(){closed=true;cachedHome=emptyList();media.close();http.close();captions.close()}
}
