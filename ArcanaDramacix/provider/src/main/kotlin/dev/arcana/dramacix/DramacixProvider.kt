package dev.arcana.dramacix

import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class DramacixProvider(context:Context):MainAPI() {
    override var name="(Arcana Drama) Cix"
    override var mainUrl=DramacixContract.BASE
    override var lang="tr"
    override val supportedTypes=setOf(TvType.TvSeries)
    override val hasMainPage=true
    override val hasQuickSearch=true
    private val settings=DramacixSettings(context)
    override val mainPage get()=settings.requests().flatMap { mainPageOf(it.data to it.label) }
    private data class CatalogueCache(val at:Long,val value:DramacixContract.Listing)
    private val catalogueMutex=Mutex()
    private val catalogueCache=ConcurrentHashMap<String,CatalogueCache>()
    private val catalogueEpoch=AtomicLong()
    @Volatile private var closed=false
    private val session=DramacixSession(context)
    private val media=DramacixMedia()
    override fun getVideoInterceptor(extractorLink:ExtractorLink):Interceptor?=if(extractorLink.url.contains("#arcana.cix."))media.interceptor()else null
    private fun DramacixContract.Card.response()=newTvSeriesSearchResponse(title,url,TvType.TvSeries) { posterUrl=poster;episodes=count }
    private inline fun<T> parse(block:()->T):T=try { block() }
        catch(e:CancellationException) { throw e }
        catch(e:ErrorLoadingException) { throw e }
        catch(_:Exception) { throw ErrorLoadingException("DramaCix istenen dizi veya bölümü doğrulamadı.") }
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        if(page !in 1..9999)throw ErrorLoadingException("DramaCix katalog sayfası tanınmadı.")
        val spec=parse { DramacixCatalogue.decode(request.data) }
        val key="${catalogueEpoch.get()}|$page|${spec.data}"
        val started=android.os.SystemClock.elapsedRealtime();var cached=false
        val listing=catalogueMutex.withLock {
            if(closed)throw ErrorLoadingException("DramaCix eklentisi kapalı.")
            val now=android.os.SystemClock.elapsedRealtime()
            val previous=catalogueCache[key]
            if(previous!=null && now>=previous.at && now-previous.at<=120_000) {
                cached=true;previous.value
            } else {
                val ajax=page>1 && spec.kind!=DramacixCatalogue.Kind.PLATFORM
                val html=session.text(spec.url(page),json=ajax,ajax=ajax)
                val result=parse {
                    if(spec.kind==DramacixCatalogue.Kind.PLATFORM)DramacixCatalogue.platformListing(html,spec,page)
                    else DramacixContract.listing(html,page)
                }
                if(!closed) {
                    while(catalogueCache.size>=64) {
                        val oldest=catalogueCache.entries.minByOrNull { it.value.at } ?: break
                        catalogueCache.remove(oldest.key,oldest.value)
                    }
                    catalogueCache[key]=CatalogueCache(android.os.SystemClock.elapsedRealtime(),result)
                }
                result
            }
        }
        // No query, URL, title, cookie or account values in performance diagnostics.
        Log.i("ArcanaDramacix","catalog kind=${spec.kind.name} page=$page returned=${listing.cards.size} more=${listing.more} cached=$cached elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}")
        return newHomePageResponse(request.name,listing.cards.map { it.response() },listing.more)
    }
    override suspend fun search(query:String):List<SearchResponse> {
        if(query.isBlank())return emptyList()
        val url="$mainUrl/ara".toHttpUrl().newBuilder().addQueryParameter("q",query.trim()).build().toString()
        return parse { DramacixContract.cards(session.text(url)).map { it.response() } }
    }
    override suspend fun quickSearch(query:String)=search(query)
    override suspend fun load(url:String):LoadResponse? {
        val slug=DramacixContract.detailSlug(url) ?: return null
        val canonical="$mainUrl/dizi/$slug";val s=parse { DramacixContract.series(session.text(canonical),slug) }
        val episodes=s.chapters.map { c -> newEpisode(DramacixContract.encode(c)) { season=1;episode=c.number;name="${c.number}. Bölüm";posterUrl=s.poster } }
        return newTvSeriesLoadResponse(s.title,canonical,TvType.TvSeries,episodes) { posterUrl=s.poster;plot=s.description;tags=s.genres }
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val c=DramacixContract.decode(data) ?: return false
        val ticket=parse { DramacixContract.ticket(session.text(c.url),c) }
        val g=session.gpuFlag()
        val url="$mainUrl/api/video".toHttpUrl().newBuilder().addQueryParameter("slug",c.slug).addQueryParameter("ep",c.number.toString())
            .addQueryParameter("t",ticket.token).addQueryParameter("g",g.toString()).addQueryParameter("dil","tr").build().toString()
        val play=parse { DramacixContract.playback(session.text(url,c.url,json=true),c) }
        val format=media.format(play.video,session.userAgent)
        data class ReadyCaption(val source:DramacixContract.Caption,val bytes:ByteArray?)
        val ready=ConcurrentHashMap<Int,ReadyCaption>()
        val sorted=play.captions.sortedBy { if(it.language=="tr")0 else if(it.language=="en")1 else 2 }
        val slots=Semaphore(4)
        // Only source files with actual timing cues are offered; no translation endpoint is requested.
        withTimeoutOrNull(4500) {
            coroutineScope {
                sorted.mapIndexed { i,s -> async { slots.withPermit {
                    media.validatedCaption(s.resource,session.userAgent)?.let { bytes ->
                        // Retain only the first preferred track (TR first), at most 512k per resolution.
                        ready[i]=ReadyCaption(s,if(i==0)bytes else null)
                    }
                } } }.awaitAll()
            }
        }
        ready.toSortedMap().values.forEach { value ->
            val s=value.source
            subtitleCallback(newSubtitleFile(s.language,media.subtitleUrl(s.resource,session.userAgent,value.bytes)) {
                headers=mapOf("User-Agent" to session.userAgent,"Referer" to s.resource.referer)
            })
        }
        val playbackUrl=media.playbackUrl(play.video,session.userAgent,format)
        callback(newExtractorLink(name,"DramaCix",playbackUrl,when(format){"hls"->ExtractorLinkType.M3U8;"dash"->ExtractorLinkType.DASH;else->ExtractorLinkType.VIDEO}) {
            referer=play.video.referer;headers=mapOf("User-Agent" to session.userAgent);quality=Qualities.Unknown.value
        })
        return true
    }
    fun showSettings(context:Context)=settings.show(context) { invalidateSource() }
    fun invalidateSource() { catalogueEpoch.incrementAndGet();catalogueCache.clear();media.invalidateCaptions();session.invalidate() }
    fun close() { closed=true;catalogueCache.clear();session.close();media.close() }
}
