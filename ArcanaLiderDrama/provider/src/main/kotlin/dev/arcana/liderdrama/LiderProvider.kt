package dev.arcana.liderdrama

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Normal publisher API, no worker/database and no signed URL in episode data or disk caches. */
class LiderProvider(context:Context):MainAPI() {
    override var name=LiderContract.NAME
    override var mainUrl=LiderContract.BASE
    override var lang="tr"
    override val supportedTypes=setOf(TvType.TvSeries)
    override val hasMainPage=true
    override val hasQuickSearch=true
    private val settings=LiderSettings(context)
    private val http=LiderHttp(context)
    private val epoch=AtomicLong()
    @Volatile private var closed=false
    @Volatile private var transport:LiderMedia?=null
    private val cacheMutex=Mutex()
    private data class Entry(val at:Long,val text:String)
    private val cache=linkedMapOf<String,Entry>()
    private var cacheBytes=0L
    @Synchronized private fun media():LiderMedia {
        http.permitted();if(closed)throw ErrorLoadingException("LiderDrama oturumu kapandı. Kaynağı yeniden açın.")
        return transport?.takeUnless{it.isClosed}?:LiderMedia({LiderContract.media(it).toHttpUrl()},http::permitted,http::denied).also{transport=it}
    }
    override fun getVideoInterceptor(extractorLink:ExtractorLink):Interceptor=media()
    override val mainPage get():List<MainPageData> {
        val f=settings.read()
        return LiderCataloguePlan.entries(f.encode(),f.q.isEmpty()).flatMap{mainPageOf(it.data to it.label)}
    }
    fun showSettings(owner:Context)=settings.show(owner,{!closed},{epoch.incrementAndGet()})
    private fun active(version:Long,snapshot:String) {
        http.permitted();if(closed||epoch.get()!=version||settings.read().encode()!=snapshot)throw ErrorLoadingException("LiderDrama filtreleri değişti. Kaynak ana sayfasını yenileyin.")
    }
    private suspend fun metadata(url:String):JSONObject=cacheMutex.withLock {
        val now=System.currentTimeMillis()
        cache[url]?.takeIf{now-it.at<120000}?.let{return@withLock JSONObject(it.text)}
        cache.remove(url)?.let{cacheBytes-=it.text.toByteArray(Charsets.UTF_8).size}
        val result=http.json(url,mainUrl+"/");val text=result.toString();val size=text.toByteArray(Charsets.UTF_8).size
        if(size<=2000000) {
            while(cache.isNotEmpty()&&(cache.size>=32||cacheBytes+size>4000000))cache.remove(cache.keys.first())?.let{cacheBytes-=it.text.toByteArray(Charsets.UTF_8).size}
            cache[url]=Entry(now,text);cacheBytes+=size
        }
        result
    }
    private fun api(path:String)=(mainUrl+path).toHttpUrl().newBuilder()
    private fun browse(page:Int,f:LiderSettings.Filters)=api("/api/dramas").apply {
        LiderCataloguePlan.query(page,f.q,f.genre,f.sort,f.dubbed,f.episodes).forEach{(key,value)->addQueryParameter(key,value)}
    }.build().toString()
    private fun item(c:LiderContract.Card)=newTvSeriesSearchResponse(c.title,LiderContract.route(c),TvType.TvSeries){posterUrl=c.poster;episodes=c.count;score=Score.from10(c.score)}
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse=guarded {
        val version=epoch.get();val f=settings.read();val snapshot=f.encode();active(version,snapshot)
        if(page<1)return@guarded newHomePageResponse(request.name,emptyList(),false)
        val shelf=LiderCataloguePlan.resolve(request.data,snapshot,f.default,f.q.isEmpty())
        if(f.q.isNotEmpty()&&f.q.length<2)return@guarded newHomePageResponse(request.name,emptyList(),false)
        val effective=shelf?.let{f.copy(sort=it.sort,dubbed=it.dubbed)}?:f
        val result=LiderContract.page(metadata(browse(page,effective)),page);active(version,snapshot)
        android.util.Log.i("ArcanaLiderCatalogue","row=${shelf?.id?:"search"} page=${result.page} cards=${result.cards.size} more=${result.page<result.pages}")
        newHomePageResponse(request.name,result.cards.map(::item),result.page<result.pages)
    }
    override suspend fun search(query:String):List<SearchResponse> = guarded {
        val q=query.trim().take(100);if(q.length<2)return@guarded emptyList()
        val version=epoch.get();val original=settings.read();val snapshot=original.encode()
        val result=LiderContract.page(metadata(browse(1,original.copy(q=q))),1);active(version,snapshot);result.cards.map(::item)
    }
    override suspend fun quickSearch(query:String)=search(query)
    private suspend fun detail(slug:String,expected:Long?):LiderContract.Detail {
        val url=api("/api/drama/"+LiderContract.slug(slug)).addQueryParameter("lang","tr").build().toString()
        return LiderContract.detail(metadata(url),slug,expected)
    }
    override suspend fun load(url:String):LoadResponse? {
        val identity=LiderContract.identity(url)?:return null
        return guarded {
            val d=detail(identity.first,identity.second)
            val episodes=d.chapters.map{c->newEpisode(LiderContract.encode(LiderContract.Payload(d.card.id,d.card.slug,c.id,c.number))){season=1;episode=c.number;name=c.title?.takeUnless{it.matches(Regex("[0-9]+[.]?\\s*Bölüm",RegexOption.IGNORE_CASE))};posterUrl=d.card.poster}}
            newTvSeriesLoadResponse(d.card.title,LiderContract.route(d.card),TvType.TvSeries,episodes){posterUrl=d.card.poster;plot=d.plot;tags=d.tags;score=Score.from10(d.card.score)}
        }
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val p=LiderContract.decode(data)?:return false
        if(isCasting)throw ErrorLoadingException("LiderDrama bu oynatımı cihaz üzerinde destekler.")
        return guarded {
            val captionDeadline=android.os.SystemClock.elapsedRealtime()+28_000L
            val d=detail(p.slug,p.series);require(d.chapters.any{it.id==p.chapter&&it.number==p.number})
            // Always resolve through the normal player endpoint; initialDrama's signed URLs may age.
            val url=api("/api/video").addQueryParameter("slug",p.slug).addQueryParameter("ep",p.number.toString()).build().toString()
            val raw=http.json(url,mainUrl+"/dizi/"+p.slug)
            if(raw.opt("premium_required")==true)throw ErrorLoadingException("LiderDrama bu bölüm için kaynak hesabı yetkisi istiyor.")
            val playback=LiderContract.playback(raw,p)
            val prepared=mutableListOf<SubtitleFile>()
            for(c in playback.captions.sortedBy{if(LiderAutomaticCaptionPolicy.required(it.language))0 else 1}) {
                val u=c.url.toHttpUrl()
                // Source static caption paths have no translation/generation API.
                val required=LiderAutomaticCaptionPolicy.required(c.language)
                val bytes=if(u.host=="liderdrama.com"||required) {
                    val text=try { http.captionText(c,p.slug,p.number,captionDeadline-android.os.SystemClock.elapsedRealtime()) }
                    catch(_:LiderCaptionUnavailable) {
                        if(required)throw ErrorLoadingException("Bu bölümün Türkçe altyazısı hazırlanamadı. Daha sonra yeniden deneyin.")
                        continue
                    }
                    if(!LiderCaptionSession.timed(text)) {
                        if(required)throw ErrorLoadingException("Bu bölümün Türkçe altyazısı hazırlanamadı.")
                        continue
                    }
                    text.toByteArray(Charsets.UTF_8)
                }else null
                val caption=media().register(c.url,c.referer.orEmpty(),http.mediaClient,c.format,http.ua,null,bytes)
                val label=when(c.language.lowercase(java.util.Locale.ROOT)){"tr","tur","tr-tr"->"Turkish";"en","eng"->"English";else->c.language}
                http.permitted();currentCoroutineContext().ensureActive();prepared.add(newSubtitleFile(label,caption))
            }
            http.permitted();currentCoroutineContext().ensureActive();prepared.forEach(subtitleCallback)
            val stream=media().register(playback.url,playback.referer.orEmpty(),http.mediaClient,playback.type,http.ua)
            http.permitted();currentCoroutineContext().ensureActive()
            callback(newExtractorLink(name,"LiderDrama",stream,if(playback.type=="hls")ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO){referer="";quality=Qualities.Unknown.value})
            true
        }
    }
    private suspend fun<T> guarded(block:suspend()->T):T=try{http.permitted();block()}catch(e:CancellationException){throw e}catch(e:ErrorLoadingException){throw e}catch(_:Exception){throw ErrorLoadingException("LiderDrama kaynak yanıtı değişmiş veya eksik.")}
    @Synchronized fun close(){closed=true;epoch.incrementAndGet();transport?.close();transport=null;http.close()}
}
