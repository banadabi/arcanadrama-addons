package dev.arcana.fulldrama

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Public catalogue identifiers and source episode routes only. Never contains a cached media ticket. */
internal object FullDramaContract {
    const val BASE = "https://fulldrama.xyz"
    data class Item(val id: Long?, val title: String, val slug: String, val poster: String?, val score: Double?, val source: String?, val premium: Boolean)
    data class Chapter(val slug: String, val season: Int, val episode: Int, val url: String, val title: String?)
    data class Series(val title: String, val poster: String?, val plot: String, val year: Int?, val platform: String?, val score: Double?, val chapters: List<Chapter>, val recommendations: List<Item>)
    data class Caption(val language: String, val url: String)
    fun own(raw: String): URI {
        val u = URI(raw)
        require(u.scheme == "https" && u.host.equals("fulldrama.xyz", true) && u.port in setOf(-1, 443) && u.rawUserInfo == null) { "Source origin mismatch" }
        return u
    }
    fun absolute(raw: String): String = own(URI(BASE).resolve(raw).toString()).toASCIIString()
    fun publicHttps(raw: String): String? = runCatching {
        val u = URI(raw); val host = u.host.orEmpty().lowercase(Locale.ROOT)
        require(u.scheme == "https" && u.port in setOf(-1,443) && u.rawUserInfo == null && host.isNotBlank())
        require(host !in setOf("localhost","0.0.0.0","127.0.0.1","::1","[::1]") && !host.endsWith(".local") && !host.endsWith(".lan") && !host.endsWith(".localhost"))
        require(!Regex("^\\d+(?:\\.\\d+){3}$").matches(host) && !host.contains(':'))
        u.toASCIIString()
    }.getOrNull()
    fun detailSlug(raw: String): String? = runCatching {
        val u = own(raw); val p = u.path.split('/').filter(String::isNotEmpty)
        p.getOrNull(1)?.takeIf { p.size == 2 && p[0] == "dizi" && validSlug(it) }
    }.getOrNull()
    private fun validSlug(s: String) = s.length in 1..250 && s.matches(Regex("[a-z0-9]+(?:[-_][a-z0-9]+)*"))
    private fun query(u: URI): Map<String,String> {
        val rows = u.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map { part ->
            URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }
        require(rows.map { it.first }.distinct().size == rows.size) { "Duplicate source parameter" }
        return rows.toMap()
    }
    fun chapter(raw: String, expectedSlug: String? = null, title: String? = null): Chapter? = runCatching {
        val u = own(raw); val p = u.path.split('/').filter(String::isNotEmpty); require(p.size == 2 && p[0] == "izle" && validSlug(p[1]))
        require(expectedSlug == null || expectedSlug == p[1]); val q = query(u); require(q.keys == setOf("s","e"))
        val s = q.getValue("s").toInt(); val e = q.getValue("e").toInt(); require(s > 0 && e > 0)
        Chapter(p[1],s,e,u.toASCIIString(),title)
    }.getOrNull()
    fun encode(c: Chapter): String = "{\"v\":1,\"kind\":\"fulldrama_episode\",\"url\":" + JSONObject.quote(c.url) + "}"
    fun decode(raw: String): Chapter? = runCatching {
        val o = JSONObject(raw); require(o.optInt("v") == 1 && o.optString("kind") == "fulldrama_episode")
        require(o.keys().asSequence().toSet() == setOf("v","kind","url")); chapter(o.getString("url"))
    }.getOrNull()
    private fun score(s: String?) = s?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10.0 }
    fun catalogue(body: String, skipMovies: Boolean = false): List<Item> {
        val a = JSONArray(body)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i); if (skipMovies && o.optString("type") == "movie") return@mapNotNull null; val title = o.getString("title").trim(); val slug = o.getString("slug")
            require(title.isNotBlank() && validSlug(slug) && o.optString("type") == "series") { "Unexpected catalogue record" }
            Item(o.optLong("id").takeIf { it > 0 },title,slug,publicHttps(o.optString("poster")),score(o.optString("imdb_score")),o.optString("source_type").takeIf(String::isNotBlank),o.optInt("is_premium") == 1)
        }.distinctBy { it.slug }
    }
    fun htmlItems(html: String): List<Item> {
        val d = Jsoup.parse(html, BASE)
        return d.select("a[href]").mapNotNull { a ->
            val raw = runCatching { absolute(a.attr("href")) }.getOrNull() ?: return@mapNotNull null
            val slug = detailSlug(raw) ?: return@mapNotNull null
            val img = a.selectFirst("img")
            val title = a.selectFirst(".video-title,.srt-title,.video-info h3,h3,.video-info h2,h2")?.text()?.trim()
                ?.takeIf(String::isNotBlank) ?: img?.attr("alt")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val poster = img?.let { it.attr("data-src").takeIf(String::isNotBlank) ?: it.attr("src") }?.let(::publicHttps)
            Item(null,title,slug,poster,null,null,false)
        }.distinctBy { it.slug }
    }
    fun categories(html: String): List<Pair<String,String>> = Jsoup.parse(html, BASE).select("a[href]").mapNotNull { a ->
        val u = runCatching { own(absolute(a.attr("href"))) }.getOrNull() ?: return@mapNotNull null
        val key = query(u)["category"] ?: return@mapNotNull null
        val label = a.text().trim().replace(Regex("\\s+")," ")
        if (u.path != "/series" || !validSlug(key) || label.isBlank() || label.length > 80) null else key to label
    }.distinctBy { it.first }.take(80)
    fun series(html: String, expectedSlug: String): Series {
        require(validSlug(expectedSlug)); val d = Jsoup.parse(html, BASE)
        val title = d.selectFirst(".dp-info h1")?.text()?.trim().orEmpty(); require(title.isNotBlank()) { "Series title missing" }
        val chapters = d.select(".dp-egrid a.dp-ecard[href]").mapNotNull { a ->
            val raw = runCatching { absolute(a.attr("href")) }.getOrNull() ?: return@mapNotNull null
            chapter(raw,expectedSlug,a.selectFirst(".dp-etitle")?.text())
        }.distinctBy { it.url }.sortedWith(compareBy({it.season},{it.episode}))
        require(chapters.isNotEmpty()) { "Episode list missing" }
        val declared = d.select(".dp-meta span").mapNotNull { Regex("(\\d+)\\s*Bölüm",RegexOption.IGNORE_CASE).find(it.text())?.groupValues?.get(1)?.toIntOrNull() }.firstOrNull()
        require(declared == null || declared == chapters.size) { "Episode count mismatch" }
        val year = d.select(".dp-meta span").mapNotNull { it.text().trim().takeIf { s -> s.matches(Regex("(?:19|20)\\d{2}")) }?.toIntOrNull() }.firstOrNull()
        return Series(title,d.selectFirst(".dp-poster[src]")?.attr("src")?.let(::publicHttps),
            d.selectFirst(".dp-desc")?.wholeText()?.trim().orEmpty(),year,d.selectFirst(".dp-cat")?.text(),
            score(d.selectFirst(".dp-imdb")?.text()),chapters,htmlItems(d.selectFirst(".similar-detail-slider")?.outerHtml().orEmpty()))
    }
    /** An ephemeral publisher-issued ticket for its own failed-episode refresh API. */
    class RefreshTicket(val episodeId: Long, val signature: String)
    fun refreshTicket(embed: String): RefreshTicket? = runCatching {
        val script = Jsoup.parse(embed,BASE).select("script:not([src])").joinToString("\n") { it.data() }
        val id = Regex("""window\.__NS2_EP_ID\s*=\s*(\d+)\s*;""").find(script)?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it > 0 } ?: return null
        val literal = Regex("""window\.__RF_SIG\s*=\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')\s*;""").find(script)?.groupValues?.get(1) ?: return null
        val signature = (JSONTokener(literal).nextValue() as? String)?.takeIf { it.length in 16..2048 && it.matches(Regex("[A-Za-z0-9._~+/=-]+")) } ?: return null
        RefreshTicket(id,signature)
    }.getOrNull()
    /** Source type=hls can name an extensionless MP4. Actual bounded bytes decide the parser. */
    fun mediaFormat(prefix: ByteArray): String? {
        if (prefix.size >= 8 && prefix.copyOfRange(4,8).contentEquals(byteArrayOf(102,116,121,112))) return "mp4"
        val text = prefix.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        if (text.startsWith("#EXTM3U")) return "hls"
        if ((text.startsWith("<?xml") || text.startsWith("<MPD")) && Regex("<MPD(?:\\s|>)").containsMatchIn(text)) return "dash"
        return null
    }
    /** The embed's own explicit track declaration; page locale/FAQ is not a caption language. */
    fun captions(embed: String): List<Caption> {
        val d = Jsoup.parse(embed, BASE); val script = d.select("script:not([src])").joinToString("\n") { it.data() }
        fun literal(name: String): String? {
            val pattern = Regex("""(?:const|let|var)\s+${Regex.escape(name)}\s*=\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')\s*;""")
            val raw = pattern.find(script)?.groupValues?.get(1) ?: return null
            return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()?.takeIf(String::isNotBlank)
        }
        val lang = Regex("(?:track|el)\\.srclang\\s*=\\s*(['\"])([A-Za-z]{2,3}(?:[-_][A-Za-z]{2,4})?)\\1").find(script)?.groupValues?.get(2)?.let(::language) ?: "Altyazı"
        val direct = literal("subtitleUrl") ?: return emptyList()
        val url = publicHttps(direct) ?: runCatching { publicHttps(absolute(direct)) }.getOrNull() ?: return emptyList()
        return listOf(Caption(lang,url))
    }
    fun language(raw: String): String = raw.trim().replace('_','-').let { if (it.equals("tr",true) || it.startsWith("tr-",true)) "tr" else it.lowercase(Locale.ROOT) }
}
