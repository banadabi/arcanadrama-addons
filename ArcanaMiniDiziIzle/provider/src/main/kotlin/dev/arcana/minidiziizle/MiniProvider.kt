package dev.arcana.minidiziizle

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Source HTML/API provider, independent of the legacy scanner and Asel database. */
class MiniProvider(context: Context) : MainAPI() {
    override var name = MiniContract.NAME
    override var mainUrl = MiniContract.BASE
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true
    private val config = MiniSettings(context)
    private val epoch = AtomicLong(0)
    @Volatile private var closed = false
    private val http = MiniHttp(context)
    @Volatile private var media: MiniMedia? = null
    @Synchronized private fun media(): MiniMedia {
        http.permitted();if(closed)throw ErrorLoadingException("MiniDiziİzle oturumu kapandı. Kaynağı yeniden açın.")
        return media?.takeUnless { it.isClosed } ?: MiniMedia({ MiniContract.media(it).toHttpUrl() },http::permitted,http::denied).also { media=it }
    }
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = media()
    override val mainPage get(): List<MainPageData> {
        val request = config.request()
        return if(request == "home")mainPageOf(*MiniContract.lists.map { it.key to it.value }.toTypedArray())
            else mainPageOf(request to "Arama Sonuçları")
    }
    private val homeMutex = Mutex()
    private var cachedHome: Pair<Long,List<MiniContract.Row>>? = null
    private val pagesMutex = Mutex()
    private data class Cursor(val url: String, val seen: Set<String>)
    private data class CachedPage(val cards: List<MiniContract.Card>, val more: Boolean)
    private data class Section(val created: Long = System.currentTimeMillis(), val cursors: MutableMap<Int,Cursor> = linkedMapOf(), val pages: MutableMap<Int,CachedPage> = linkedMapOf())
    private val sections = linkedMapOf<String,Section>()
    private suspend fun home(): List<MiniContract.Row> = homeMutex.withLock {
        cachedHome?.takeIf { System.currentTimeMillis()-it.first < 120000 }?.second?.let { return@withLock it }
        MiniContract.home(http.text(mainUrl+"/")).also { cachedHome = System.currentTimeMillis() to it }
    }
    fun settings(owner: Context) = config.show(owner,{ !closed }) { epoch.incrementAndGet() }
    private fun active(snapshot: Long, configured: String) {
        http.permitted()
        if(closed || epoch.get() != snapshot || config.request() != configured)throw ErrorLoadingException("MiniDiziİzle araması değişti. Kaynak ana sayfasını yenileyin.")
    }
    private fun item(card: MiniContract.Card): SearchResponse {
        val route = card.url + (card.edition?.let { "#mini=$it" } ?: "")
        return newTvSeriesSearchResponse(card.title,route,TvType.TvSeries) { posterUrl=card.poster }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = guarded {
        val snapshot = epoch.get();val configured = config.request();active(snapshot,configured)
        if(page < 1)return@guarded newHomePageResponse(request.name,emptyList(),false)
        if(configured != "home") {
            require(request.data == configured)
            if(page > 1)return@guarded newHomePageResponse(request.name,emptyList(),false)
            val term = JSONObject(configured).getString("q")
            val rows = searchCards(term);active(snapshot,configured)
            return@guarded newHomePageResponse(request.name,rows.map(::item),false)
        }
        require(request.data in MiniContract.lists)
        val path = request.data
        val forced = when(path) { "/dublajli-mini-diziler/" -> "dublaj"; "/altyazili-mini-diziler/" -> "altyazi"; else -> null }
        // All six home previews share one normal HTML request. Real archive URLs, not guessed page counts, continue each row.
        val preview = home().firstOrNull { it.path == path };active(snapshot,configured)
        val result = pagesMutex.withLock {
            active(snapshot,configured)
            if(page == 1 && sections[path]?.let { System.currentTimeMillis()-it.created >= 120000 } == true)sections.remove(path)
            val section = sections.getOrPut(path) { Section() }
            section.pages[page]?.let { return@withLock it }
            if(page == 1 && preview != null) {
                val seen = preview.cards.map { it.url }.toSet()
                section.cursors[2] = Cursor(MiniContract.archive(mainUrl+path),seen)
                CachedPage(preview.cards,true).also { section.pages[1] = it }
            } else {
                val cursor = if(page == 1)Cursor(MiniContract.archive(mainUrl+path),emptySet())
                    else section.cursors[page] ?: throw ErrorLoadingException("MiniDiziİzle liste oturumu yenilendi. Kaynağı yeniden açın.")
                val listing = MiniContract.listing(http.text(cursor.url),cursor.url,forced)
                active(snapshot,configured)
                val cards = listing.cards.filter { it.url !in cursor.seen }
                val seen = cursor.seen // only the fixed home preview, not an accumulating full catalogue
                // Preserve sparse/update pages: an empty deduplicated page with a real next link is still pageable.
                listing.next?.let { section.cursors[page+1] = Cursor(it,seen) }
                CachedPage(cards,listing.next != null).also {
                    section.pages[page] = it
                    while(section.pages.size > 64)section.pages.remove(section.pages.keys.first())
                    while(section.cursors.size > 65)section.cursors.remove(section.cursors.keys.first())
                }
            }
        }
        active(snapshot,configured)
        newHomePageResponse(request.name,result.cards.map(::item),result.more)
    }
    private suspend fun searchCards(query: String): List<MiniContract.Card> {
        val q = query.trim().take(100)
        if(q.length < 2)return emptyList()
        val url = (mainUrl+"/wp-json/minidizizle/v1/arama").toHttpUrl().newBuilder().addQueryParameter("q",q).build()
        return MiniContract.search(http.text(url.toString()))
    }
    override suspend fun search(query: String): List<SearchResponse> = guarded { searchCards(query).map(::item) }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    override suspend fun load(url: String): LoadResponse? {
        val route = MiniContract.detailUrl(url) ?: return null // opaque chapter JSON is rejected before network access
        return guarded {
            val detail = MiniContract.detail(http.text(MiniContract.canonical(route)),route)
            val chapters = detail.chapters.map { ch -> newEpisode(MiniContract.encode(ch)) {
                season=1;episode=ch.number;posterUrl=detail.poster
            } }
            newTvSeriesLoadResponse(detail.title,route,TvType.TvSeries,chapters) {
                posterUrl=detail.poster;plot=detail.plot;tags=detail.tags;recommendations=detail.related.map(::item)
            }
        }
    }
    private fun playUrl(player: MiniContract.Player, sid: String? = null): String {
        val path = if(player.pool)"pool-playback/" else "playback/"
        return (mainUrl+"/wp-json/minidizizle/v1/"+path+player.episodeId).toHttpUrl().newBuilder()
            .addQueryParameter("token",player.token).addQueryParameter("dil",player.chapter.edition)
            .addQueryParameter("say","1").addQueryParameter("r2_capable","1")
            .apply { sid?.let { addQueryParameter("sid",it) } }.build().toString()
    }
    private suspend fun resolve(player: MiniContract.Player): JSONObject {
        var data = MiniContract.playback(http.json(playUrl(player),player.chapter.page),player)
        if(data.optString("tur") != "depo")return data
        val origin = data.getJSONObject("depo").getString("taban")
        require(origin in setOf("https://storage-pilot.shortizle.com","https://media.minidiziizle.com"))
        // Publisher's normal guest session -> same episode/ticket -> authorization. No copied browser/account identity.
        val session = http.json(origin+"/session",player.chapter.page,JSONObject())
        val sid = session.getString("sid");require(sid.matches(Regex("[a-f0-9]{64}")))
        data = MiniContract.playback(http.json(playUrl(player,sid),player.chapter.page),player)
        if(data.optString("tur") != "depo")return data
        val ticket = data.getJSONObject("depo");require(ticket.getString("taban") == origin)
        val value = ticket.getString("ticket");require(value.isNotBlank() && value.length <= 16000)
        val authorization = http.json(origin+"/authorize",player.chapter.page,JSONObject().put("ticket",value))
        val mediaUrl = MiniContract.media(authorization.getString("url")).toHttpUrl()
        require(mediaUrl.scheme+"://"+mediaUrl.host == origin && mediaUrl.query == null)
        if(origin == "https://media.minidiziizle.com")require(mediaUrl.encodedPath.matches(Regex("/s/[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}/protected/v1/[a-f0-9]{32}/master\\.m3u8")))
        data.put("video_url",mediaUrl.toString()).put("tur","hls").put("cerezli",true)
        return data
    }
    override suspend fun loadLinks(data: String,isCasting: Boolean,subtitleCallback: (SubtitleFile)->Unit,callback: (ExtractorLink)->Unit): Boolean {
        val chapter = MiniContract.decode(data) ?: return false
        return guarded {
            val player = MiniContract.player(http.text(chapter.page),chapter)
            val result = resolve(player)
            val type = when(result.getString("tur")) {
                "hls" -> ExtractorLinkType.M3U8
                "mp4","video" -> ExtractorLinkType.VIDEO
                else -> throw ErrorLoadingException("MiniDiziİzle bu bölümde desteklenmeyen gömülü oynatıcı döndürdü.")
            }
            val upstream = MiniContract.media(result.getString("video_url"))
            val referer = when(result.optString("referer_policy")) {
                "no-referrer" -> ""
                "unsafe-url" -> chapter.page
                else -> mainUrl+"/"
            }
            val captions = result.optJSONArray("subtitles")
            for(i in 0 until (captions?.length() ?: 0)) {
                val sub = captions!!.getJSONObject(i)
                val raw = MiniContract.media(sub.getString("url"))
                val language = sub.optString("srclang").ifBlank { sub.optString("lang") }
                val label = when(language.lowercase(java.util.Locale.ROOT)) { "tr","tur","tr-tr" -> "Turkish";else -> sub.optString("label").ifBlank { language } }
                if(label.isBlank())continue
                val url = media().register(raw,referer,http.mediaClient,"vtt",http.ua,mainUrl)
                http.permitted();currentCoroutineContext().ensureActive()
                subtitleCallback(newSubtitleFile(label,url))
            }
            val url = media().register(upstream,referer,http.mediaClient,if(type == ExtractorLinkType.M3U8)"hls" else "mp4",http.ua,mainUrl)
            http.permitted();currentCoroutineContext().ensureActive()
            callback(newExtractorLink(name,if(chapter.edition == "dublaj")"MiniDiziİzle · Türkçe dublaj" else "MiniDiziİzle · Türkçe altyazılı",url,type) {
                quality=Qualities.Unknown.value;this.referer=referer
            })
            true
        }
    }
    private suspend fun <T> guarded(block: suspend ()->T): T = try { http.permitted();block() }
        catch(e: CancellationException) { throw e }
        catch(e: ErrorLoadingException) { throw e }
        catch(_: Exception) { throw ErrorLoadingException("MiniDiziİzle kaynak yanıtı değişmiş veya eksik.") }
    @Synchronized fun close() { closed=true;epoch.incrementAndGet();media?.close();media=null;http.close();cachedHome=null }
}
