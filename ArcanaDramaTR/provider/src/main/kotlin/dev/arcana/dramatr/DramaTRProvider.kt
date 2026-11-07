package dev.arcana.dramatr

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl

class DramaTRProvider : MainAPI() {
    override var name = "(Arcana Drama) DramaTR"
    override var mainUrl = DramaTRContract.BASE
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val mainPage = mainPageOf("recent" to "Son Eklenenler","ranked" to "Popüler","views" to "Arşiv")
    private val http = DramaTRHttp()
    private fun DramaTRContract.Card.response() = newTvSeriesSearchResponse(title,url,TvType.TvSeries) { posterUrl=poster;episodes=count }
    private inline fun <T> parse(block: () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: ErrorLoadingException) { throw e }
        catch (_: Exception) { throw ErrorLoadingException("DramaTR yanıtı beklenen içerikle eşleşmedi.") }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1 || request.data !in setOf("recent","ranked","views")) throw ErrorLoadingException("DramaTR kategori isteği tanınmadı.")
        val url = "$mainUrl/api/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("sort",if(request.data=="recent") "recent" else "views")
            .addQueryParameter("page",page.toString()).addQueryParameter("per_page","24")
            .apply { if(request.data=="ranked") addQueryParameter("ranked","1") }.build()
        val listing = parse { DramaTRContract.listing(http.text(url.toString())) }
        return newHomePageResponse(request.name,listing.cards.map { it.response() },listing.more)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val q=query.trim(); if(q.length < 2) return emptyList()
        val url="$mainUrl/api/search".toHttpUrl().newBuilder().addQueryParameter("q",q).addQueryParameter("limit","36").build()
        return parse { DramaTRContract.listing(http.text(url.toString())).cards.map { it.response() } }
    }
    override suspend fun quickSearch(query: String) = search(query)
    override suspend fun load(url: String): LoadResponse? {
        val slug=DramaTRContract.detailSlug(url) ?: return null
        val series=parse { DramaTRContract.series(http.text("$mainUrl/dramaizle/$slug"),slug) }
        val eps=series.chapters.map { c -> newEpisode(DramaTRContract.encode(c)) { season=1;episode=c.number;name="${c.number}. Bölüm";posterUrl=series.poster } }
        return newTvSeriesLoadResponse(series.title,series.url,TvType.TvSeries,eps) { posterUrl=series.poster;plot=series.plot;tags=series.tags }
    }
    override suspend fun loadLinks(data: String,isCasting: Boolean,subtitleCallback: (SubtitleFile)->Unit,callback: (ExtractorLink)->Unit): Boolean {
        val c=DramaTRContract.decode(data) ?: return false
        val url="$mainUrl/api/play".toHttpUrl().newBuilder().addQueryParameter("series",c.slug).addQueryParameter("ep",c.number.toString()).build()
        val play=parse { DramaTRContract.playback(http.text(url.toString(),c.url),c) }
        val headers=mapOf("User-Agent" to UA,"Referer" to c.url)
        // Both sets resolve before the host takes its callback snapshot. No account headers reach media.
        val videos=play.videos.map { it to http.publicMedia(it.url) }
        val captions=play.captions.map { it to http.publicMedia(it.url) }
        captions.forEach { (s,u) -> subtitleCallback(newSubtitleFile(s.language,u) { this.headers=headers }) }
        videos.forEach { (s,u) -> callback(newExtractorLink(name,"DramaTR" + if(s.label.isBlank()) "" else " · ${s.label}",u,
            if(s.type=="hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) { referer=c.url;this.headers=mapOf("User-Agent" to UA);quality=Qualities.Unknown.value }) }
        return videos.isNotEmpty()
    }
    fun close() = http.close()
    companion object { internal const val UA=DramaTRHttp.UA }
}
