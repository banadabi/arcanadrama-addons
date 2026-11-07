package dev.arcana.fulldrama

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.InetAddress

/** Standard source provider. Only stable source episode routes enter Episode.data. */
class FullDramaProvider(private val context: Context) : MainAPI() {
    override var name = "(Arcana Drama) FullDrama"
    override var mainUrl = FullDramaContract.BASE
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true
    private val session = FullDramaSession(context)
    private val http = FullDramaHttp(session)
    override val mainPage get() = mainPageOf(*((listOf("all" to "Tüm Diziler") + FullDramaSession.categories(context)).distinctBy { it.first }).toTypedArray())
    private fun endpoint(path: String) = FullDramaContract.absolute(path).toHttpUrlOrNull()
        ?: throw ErrorLoadingException("FullDrama istek adresi tanınmadı.")
    private fun searchItem(item: FullDramaContract.Item): SearchResponse = newTvSeriesSearchResponse(item.title,mainUrl + "/dizi/" + item.slug,TvType.TvSeries) { posterUrl = item.poster; score = Score.from10(item.score) }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1) return newHomePageResponse(request.name,emptyList(),false)
        val key = request.data
        if (key != "all" && !key.matches(Regex("[a-z0-9][a-z0-9_-]{0,79}"))) throw ErrorLoadingException("FullDrama kategori kimliği tanınmadı.")
        val url = endpoint("/api/series").newBuilder().addQueryParameter("page",page.toString())
            .apply { if (key != "all") addQueryParameter("category",key) }.build()
        val rows = parse { FullDramaContract.catalogue(http.api(url.toString())) }
        // The observed endpoint returns a bare array, without a total/hasMore envelope. End on a real empty array.
        return newHomePageResponse(request.name,rows.map(::searchItem),rows.isNotEmpty())
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim(); if (term.length < 2) return emptyList()
        val url = endpoint("/search").newBuilder().addQueryParameter("q",term).build()
        return parse { FullDramaContract.htmlItems(http.text(url.toString())).map(::searchItem) }
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val term = query.trim(); if (term.length < 2) return emptyList()
        val url = endpoint("/api/search").newBuilder().addQueryParameter("q",term).build()
        return parse { FullDramaContract.catalogue(http.api(url.toString()),skipMovies = true).map(::searchItem) }
    }
    override suspend fun load(url: String): LoadResponse? {
        // Arcana probes load(opaque Episode.data) before loadLinks; fail immediately for non-detail input.
        val slug = FullDramaContract.detailSlug(url) ?: return null
        val canonical = mainUrl + "/dizi/" + slug
        val series = parse { FullDramaContract.series(http.text(canonical),slug) }
        val episodes = series.chapters.map { c ->
            newEpisode(FullDramaContract.encode(c)) {
                season = c.season; episode = c.episode; posterUrl = series.poster
                name = c.title?.takeUnless { it.matches(Regex("Bölüm\\s*\\d+",RegexOption.IGNORE_CASE)) }
            }
        }
        return newTvSeriesLoadResponse(series.title,canonical,TvType.TvSeries,episodes) {
            posterUrl = series.poster; plot = series.plot; year = series.year
            tags = listOfNotNull(series.platform).filter(String::isNotBlank)
            recommendations = series.recommendations.map(::searchItem)
            score = Score.from10(series.score)
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val c = FullDramaContract.decode(data) ?: return false
        val request = endpoint("/api/episode").newBuilder().addQueryParameter("slug",c.slug)
            .addQueryParameter("s",c.season.toString()).addQueryParameter("e",c.episode.toString()).build()
        val episode = http.json(request.toString())
        if (episode.optString("error") == "login_required") throw ErrorLoadingException("FullDrama bu bölüm için üyelik istiyor. Eklenti ayarından kendi hesabınızla giriş yapın.")
        if (episode.optString("status") == "processing") throw ErrorLoadingException("FullDrama bölümü hazırlıyor. Biraz sonra yeniden deneyin.")
        if (!episode.optBoolean("success")) throw ErrorLoadingException("FullDrama bölüm oynatıcısı hazır değil.")
        var playerUrl = parse { checkedPlayer(episode.getString("playerUrl")) }
        repeat(2) { attempt ->
            val embedToken = playerUrl.queryParameter("token").orEmpty()
            if (embedToken.isBlank()) throw ErrorLoadingException("FullDrama oynatıcı bileti eksik.")
            val embed = http.text(playerUrl.toString())
            val ajax = playerUrl.resolve("ajax_token.php")?.newBuilder()?.addQueryParameter("token",embedToken)?.build()
                ?: throw ErrorLoadingException("FullDrama oynatıcı API adresi eksik.")
            val initial = http.json(ajax.toString())
            if (!initial.optBoolean("success")) throw ErrorLoadingException("FullDrama oynatma bileti doğrulanamadı.")
            val stream = parse { FullDramaContract.own(FullDramaContract.absolute(initial.getString("streamUrl"))) }
            val marker = "/secure-view/"; val at = stream.path.indexOf(marker)
            val streamToken = if (at >= 0) stream.path.substring(at + marker.length) else ""
            if (streamToken.isBlank() || streamToken.contains('/') || stream.rawQuery != null) throw ErrorLoadingException("FullDrama stream bileti biçimi tanınmadı.")
            // Embed authorization token and stream authorization token are different roles. Follow the registered SW contract.
            val resolve = endpoint(stream.path.substring(0,at) + "/ajax/resolve_stream.php").newBuilder().addQueryParameter("token",streamToken).build()
            val media = http.json(resolve.toString())
            if (!media.optBoolean("success")) throw ErrorLoadingException("FullDrama medya adresini henüz çözemedi.")
            val link = parse { FullDramaContract.publicHttps(media.getString("url")) }
                ?: throw ErrorLoadingException("FullDrama geçerli bir HTTPS medya adresi döndürmedi.")
            withContext(Dispatchers.IO) {
                val host = java.net.URI(link).host
                val addresses = InetAddress.getAllByName(host)
                if (addresses.isEmpty() || addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress || (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc) })
                    throw ErrorLoadingException("FullDrama medya sunucusu genel ağ adresi değil.")
            }
            val info = http.mediaInfo(link)
            val observedNetshort = java.net.URI(link).host.equals("txvideo.netshort.com",true)
            if (info.format == "mp4" && observedNetshort && (info.totalBytes ?: 0) > 1081344 && !http.netshortTailReadable(link)) {
                if (attempt != 0) throw ErrorLoadingException("FullDrama yenilenen kaynak da okunamıyor. Kaynak sitesi düzelttikten sonra yeniden deneyin.")
                val ticket = parse { FullDramaContract.refreshTicket(embed) }
                    ?: throw ErrorLoadingException("FullDrama kaynak dosyası okunamıyor; bu bölümde kaynak yenileme bileti yok.")
                recordRefreshIntent(ticket.episodeId)
                val result = http.refresh(ticket)
                if (result.optString("action") in setOf("wait","captcha","block")) throw ErrorLoadingException("FullDrama yenileme için doğrulama istiyor. Eklenti ayarından kaynak sitesini açın.")
                if (result.optString("status") == "failed") throw ErrorLoadingException("FullDrama bu kaynağı yenileyemedi. Biraz sonra yeniden deneyin.")
                val complete = result.optString("status") == "completed" || (result.optBoolean("ok") && result.optBoolean("already_ok"))
                if (!complete) throw ErrorLoadingException("FullDrama kaynağı yeniliyor. Biraz sonra bölümü yeniden açın.")
                val fresh = result.optString("embed_token")
                playerUrl = if (fresh.isNotBlank()) playerUrl.newBuilder().setQueryParameter("token",fresh).build()
                    else {
                        val next = http.json(request.toString())
                        if (!next.optBoolean("success")) throw ErrorLoadingException("FullDrama yeni oynatıcıyı hazırlıyor. Biraz sonra yeniden açın.")
                        parse { checkedPlayer(next.getString("playerUrl")) }
                    }
                return@repeat
            }
            val type = when (info.format) {
                "hls" -> ExtractorLinkType.M3U8
                "mp4" -> ExtractorLinkType.VIDEO
                "dash" -> ExtractorLinkType.DASH
                else -> throw ErrorLoadingException("FullDrama medya biçimi tanınmadı.")
            }
            parse { FullDramaContract.captions(embed) }.forEach { caption ->
                subtitleCallback(newSubtitleFile(caption.language,caption.url) { headers = mapOf("User-Agent" to session.userAgent) })
            }
            callback(newExtractorLink(name,"FullDrama",link,type) {
                quality = Qualities.Unknown.value; referer = ""; headers = mapOf("User-Agent" to session.userAgent)
            })
            return true
        }
        return false
    }
    private fun checkedPlayer(raw: String): okhttp3.HttpUrl {
        val player = FullDramaContract.own(FullDramaContract.absolute(raw))
        if (!player.path.endsWith("/embed.php")) throw ErrorLoadingException("FullDrama farklı bir oynatıcı adresi döndürdü.")
        return player.toASCIIString().toHttpUrlOrNull() ?: throw ErrorLoadingException("FullDrama oynatıcı adresi tanınmadı.")
    }
    private fun recordRefreshIntent(episodeId: Long) {
        val prefs = context.applicationContext.getSharedPreferences("arcana.fulldrama.refresh_receipts",Context.MODE_PRIVATE)
        synchronized(prefs) {
            val key = "episode_" + episodeId
            val now = System.currentTimeMillis(); val before = prefs.getLong(key,0)
            if (before > 0 && now - before < 60000) throw ErrorLoadingException("FullDrama yenilemesi kısa süre önce istendi. Biraz sonra yeniden açın.")
            // Save before POST: an interrupted/unknown response must not immediately duplicate a source job.
            if (!prefs.edit().putLong(key,now).commit()) throw ErrorLoadingException("FullDrama yenileme isteği kaydedilemedi.")
        }
    }
    private suspend fun <T> parse(block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e } catch (e: ErrorLoadingException) { throw e }
        catch (_: Exception) { throw ErrorLoadingException("FullDrama kaynak yanıtı değişmiş veya eksik.") }
    fun close() = http.close()
}
