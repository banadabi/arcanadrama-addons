package dev.arcana.lidershort

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

class LiderShortProvider(context: Context) : MainAPI() {
    override var name = LiderShortContract.NAME
    override var mainUrl = LiderShortContract.BASE
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)
    private val settings = LiderShortSettings(context)
    override val mainPage get() = mainPageOf(settings.request() to "LiderShort")
    private val session = LiderShortSession(context)
    private val http = LiderShortHttp(context, session)
    private val captions = CaptionBuffer()
    private val cacheMutex = Mutex()
    private var cacheAt = 0L
    private var cache = emptyList<LiderShortContract.Row>()
    @Volatile private var closed = false
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? = if (captions.owns(extractorLink.url)) captions.interceptor else null
    fun showSettings(owner: Context) = settings.show(owner, { !closed }, { cacheAt = 0L; cache = emptyList() })
    private fun LiderShortContract.Card.response() = newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
        posterUrl = poster; episodes = count
    }
    private inline fun <T> parse(block: () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: ErrorLoadingException) { throw e }
        catch (_: Exception) { throw ErrorLoadingException("LiderShort istenen dizi veya bölümü doğrulamadı.") }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1) throw ErrorLoadingException("LiderShort katalog isteği tanınmadı.")
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        if (request.data != "home") {
            val q = parse { LiderShortSettings.query(request.data) }
            return newHomePageResponse("Arama Sonuçları", search(q), false)
        }
        val rows = cacheMutex.withLock {
            if (closed) throw ErrorLoadingException("LiderShort eklentisi kapalı.")
            val now = android.os.SystemClock.elapsedRealtime()
            if (cache.isNotEmpty() && now >= cacheAt && now - cacheAt <= 120_000) cache else {
                parse { LiderShortContract.home(http.text("$mainUrl/tr")) }.also {
                    if (!closed) { cache = it; cacheAt = android.os.SystemClock.elapsedRealtime() }
                }
            }
        }
        return newHomePageResponse(rows.map { HomePageList(it.name, it.cards.map { c -> c.response() }) }, false)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim(); if (q.isEmpty()) return emptyList()
        val url = "$mainUrl/tr/search".toHttpUrl().newBuilder().addQueryParameter("q", q.take(300)).build().toString()
        return parse { LiderShortContract.search(http.text(url)).map { it.response() } }
    }
    override suspend fun quickSearch(query: String) = search(query)
    override suspend fun load(url: String): LoadResponse? {
        val slug = LiderShortContract.detailSlug(url) ?: return null
        val canonical = "$mainUrl/tr/videos/$slug"
        val s = parse { LiderShortContract.series(http.text(canonical), slug) }
        val episodes = s.chapters.map { c -> newEpisode(LiderShortContract.encode(c)) {
            season = 1; episode = c.number; name = "${c.number}. Bölüm"; posterUrl = s.poster
        } }
        return newTvSeriesLoadResponse(s.title, canonical, TvType.TvSeries, episodes) {
            posterUrl = s.poster; plot = s.plot; tags = s.genres
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val c = LiderShortContract.decode(data) ?: return false
        val source = parse { LiderShortContract.watchSource(http.text(c.url), c) }
            ?: run {
                val api = "$mainUrl/api/stream/${c.slug}/${c.number}".toHttpUrl().newBuilder().addQueryParameter("dil", "tr").build()
                parse { JSONObject(http.text(api.toString(), c.url)) }
            }
        val play = parse { LiderShortContract.playback(source) }
        val video = http.publicMedia(play.video)
        val agent = session.userAgent.ifBlank { LiderShortHttp.UA }
        val ready = mutableListOf<Pair<String, String>>()
        withTimeoutOrNull(4500) {
            for (caption in play.captions) {
                http.captionBytes(caption.url, c.url)?.let { ready.add(caption.language to captions.register(it)) }
                if (ready.size >= 12) break
            }
        }
        if (closed) throw ErrorLoadingException("LiderShort eklentisi kapalı.")
        // Callback lists are complete before the host snapshots loadLinks results.
        ready.forEach { (language, link) -> subtitleCallback(newSubtitleFile(language, link) {
            headers = mapOf("User-Agent" to agent, "Referer" to c.url)
        }) }
        callback(newExtractorLink(name, name, captions.tagged(video), if (play.hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            referer = c.url; headers = mapOf("User-Agent" to agent); quality = Qualities.Unknown.value
        })
        return true
    }
    fun close() { closed = true; cache = emptyList(); http.close(); session.close(); captions.close() }
}
