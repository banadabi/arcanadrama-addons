package dev.arcana.lidershort

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

internal object LiderShortContract {
    const val BASE = "https://www.lidershort.com"
    const val NAME = "(Arcana Drama) LiderShort"
    private val slugPattern = Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")
    data class Chapter(val slug: String, val number: Int) {
        val url get() = "$BASE/tr/videos/$slug/bolum-$number"
    }
    data class Card(val title: String, val url: String, val poster: String?, val count: Int?)
    data class Row(val name: String, val cards: List<Card>)
    data class Series(val title: String, val plot: String, val poster: String?, val genres: List<String>, val chapters: List<Chapter>)
    class Caption(val language: String, val url: String) { override fun toString() = "Caption([redacted])" }
    class Playback(val video: String, val hls: Boolean, val captions: List<Caption>) { override fun toString() = "Playback([redacted])" }

    fun https(raw: String): String? = runCatching {
        require(raw.length in 1..16_384 && raw !in setOf("null", "undefined") && !raw.startsWith("$"))
        val u = URI(BASE + "/").resolve(raw)
        require(u.scheme == "https" && !u.host.isNullOrBlank() && u.port in setOf(-1, 443) && u.rawUserInfo == null && u.rawFragment == null)
        require(u.rawPath == u.normalize().rawPath)
        u.toString()
    }.getOrNull()
    fun own(raw: String): String? = https(raw)?.takeIf { URI(it).host in setOf("www.lidershort.com", "lidershort.com") }
    private fun slug(s: String) = s.length in 1..240 && slugPattern.matches(s)
    fun detailSlug(raw: String): String? = own(raw)?.let { URI(it) }?.takeIf { it.rawQuery == null }?.let {
        Regex("/tr/videos/([A-Za-z0-9-]+)/?").matchEntire(it.rawPath)?.groupValues?.get(1)?.takeIf(::slug)
    }
    fun chapter(raw: String): Chapter? = own(raw)?.let { URI(it) }?.takeIf { it.rawQuery == null }?.let {
        val match = Regex("/tr/videos/([A-Za-z0-9-]+)/bolum-([1-9][0-9]{0,3})/?").matchEntire(it.rawPath) ?: return@let null
        match.groupValues[1].takeIf(::slug)?.let { s -> Chapter(s, match.groupValues[2].toInt()) }
    }
    fun encode(c: Chapter): String = JSONObject().put("v", 1).put("slug", c.slug).put("episode", c.number).toString()
    fun decode(raw: String): Chapter? = runCatching {
        require(raw.length <= 1024)
        val j = JSONObject(raw)
        require(j.optInt("v") == 1 && j.opt("episode") is Int)
        val c = Chapter(j.getString("slug"), j.getInt("episode"))
        require(slug(c.slug) && c.number in 1..9999)
        c
    }.getOrNull()

    private fun cards(root: Element): List<Card> = root.select("article").mapNotNull { article ->
        val anchor = article.select("a[href]").firstOrNull { runCatching { chapter(URI(BASE).resolve(it.attr("href")).toString()) }.getOrNull() != null } ?: return@mapNotNull null
        val c = runCatching { chapter(URI(BASE).resolve(anchor.attr("href")).toString()) }.getOrNull() ?: return@mapNotNull null
        val title = article.selectFirst("h3")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return@mapNotNull null
        val image = article.selectFirst("img")?.let { https(it.attr("src").ifBlank { it.attr("data-src") }) }
        val count = Regex("([0-9]{1,4})\\s*bölüm", RegexOption.IGNORE_CASE).find(article.text())?.groupValues?.get(1)?.toIntOrNull()
        Card(title, "$BASE/tr/videos/${c.slug}", image, count)
    }.distinctBy { it.url }.take(5000)

    fun home(raw: String): List<Row> {
        val doc = Jsoup.parse(raw, BASE)
        return doc.select("section[aria-labelledby]").mapNotNull { section ->
            val id = section.attr("aria-labelledby")
            if (!id.startsWith("row-")) return@mapNotNull null
            val title = section.selectFirst("h2")?.text()?.trim().orEmpty()
            val values = cards(section)
            if (title.isBlank() || values.isEmpty()) null else Row(title, values)
        }.also { require(it.isNotEmpty()) }
    }
    fun search(raw: String): List<Card> = cards(Jsoup.parse(raw, BASE).selectFirst("main") ?: Jsoup.parse(raw, BASE))
    fun deniedHtml(raw: String): Boolean {
        val doc = Jsoup.parse(raw)
        return Regex("Access Denied|Attention Required|you have been blocked|verify you are human|captcha", RegexOption.IGNORE_CASE)
            .containsMatchIn(doc.title() + " " + doc.body().text().take(500))
    }

    fun series(raw: String, expected: String): Series {
        require(slug(expected))
        val bindings = FlightData.objects(raw).filter {
            it.has("initialSource") && it.optString("locale") == "tr" && it.optJSONObject("series")?.optString("slug") == expected
        }
        if (bindings.isNotEmpty()) {
            require(bindings.size == 1)
            val value = bindings.single().getJSONObject("series")
            val episodes = value.optJSONArray("episodes") ?: error("missing chapters")
            val chapters = (0 until episodes.length()).map { i ->
                val episode = episodes.getJSONObject(i)
                require(episode.opt("number") is Int)
                Chapter(expected, episode.getInt("number").also { require(it in 1..9999) })
            }.distinctBy { it.number }.sortedBy { it.number }
            require(chapters.isNotEmpty())
            fun tr(data: Any?): String = when (data) { is String -> data; is JSONObject -> data.optString("tr"); else -> "" }
            val title = tr(value.opt("title")).trim(); require(title.isNotEmpty())
            val genres = value.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
            return Series(title, tr(value.opt("synopsis")), https(value.optString("poster")), genres, chapters)
        }
        val doc = Jsoup.parse(raw, BASE)
        val nodes = mutableListOf<JSONObject>()
        fun collect(value: Any?, depth: Int = 0) {
            require(depth <= 20)
            when(value) {
                is JSONObject -> { nodes.add(value); value.opt("@graph")?.let { collect(it, depth + 1) } }
                is JSONArray -> for (i in 0 until minOf(value.length(), 1000)) collect(value.opt(i), depth + 1)
            }
        }
        doc.select("script[type=application/ld+json]").forEach { script ->
            runCatching { org.json.JSONTokener(script.data()).nextValue() }.getOrNull()?.let { collect(it) }
        }
        val ld = nodes.firstOrNull { it.optString("@type") == "TVSeries" } ?: error("missing series")
        require(detailSlug(ld.optString("url")) == expected)
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        require(title.isNotEmpty())
        val episodes = doc.select("a[href]").mapNotNull { runCatching { chapter(URI(BASE).resolve(it.attr("href")).toString()) }.getOrNull() }
            .filter { it.slug == expected }.distinctBy { it.number }.sortedBy { it.number }
        require(episodes.isNotEmpty())
        val images = ld.opt("image")
        val poster = when(images) { is String -> https(images); is JSONArray -> https(images.optString(0)); else -> null }
        val genres = ld.optJSONArray("genre")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
        return Series(title, ld.optString("description"), poster, genres, episodes)
    }
    fun sameSeriesPage(raw: String, expected: String) = detailSlug(raw) == expected || chapter(raw)?.slug == expected

    fun watchSource(raw: String, c: Chapter): JSONObject? {
        val matching = FlightData.objects(raw).filter {
            it.has("initialSource") && it.optJSONObject("series")?.optString("slug") == c.slug &&
                it.optJSONObject("episode")?.opt("number") == c.number && it.optString("locale") == "tr"
        }
        require(matching.size == 1)
        return matching.single().optJSONObject("initialSource")?.takeIf { it.optString("url").isNotBlank() }
    }
    /** Unclear proxy targets fail closed. This inspects data, never requests the inner URL. */
    private fun translatedOrAmbiguous(raw: String, depth: Int = 0): Boolean {
        if (depth > 3) return true
        val u = runCatching { URI(raw) }.getOrNull() ?: return true
        if (u.scheme?.lowercase() !in setOf("http", "https") || u.host.isNullOrBlank() || u.rawUserInfo != null) return true
        if (u.path.orEmpty().lowercase().endsWith("/api/altyazi.php")) return true
        val inner = u.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val p = pair.split('=', limit = 2)
            val key = runCatching { URLDecoder.decode(p[0], "UTF-8").lowercase() }.getOrDefault("")
            if (key != "url") null else if (p.size != 2) "" else runCatching { URLDecoder.decode(p[1], "UTF-8") }.getOrDefault("")
        }
        if (inner.isEmpty()) return false
        if (inner.size != 1 || inner.single().isBlank()) return true
        val value = inner.single()
        val direct = runCatching { URI(value) }.getOrNull()
        if (direct?.scheme?.lowercase() in setOf("http", "https")) return translatedOrAmbiguous(value, depth + 1)
        val decoded = runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
            ?: runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull() ?: return true
        return translatedOrAmbiguous(decoded, depth + 1)
    }
    fun captionURL(raw: String): String? = https(raw)?.takeUnless { translatedOrAmbiguous(it) }
    fun playback(j: JSONObject): Playback {
        require(j.opt("isHls") is Boolean)
        val video = https(j.optString("url")) ?: error("missing video")
        val tracks = j.optJSONArray("subtitles") ?: JSONArray()
        val captions = (0 until minOf(tracks.length(), 32)).mapNotNull { i ->
            val track = tracks.optJSONObject(i) ?: return@mapNotNull null
            val url = captionURL(track.optString("url")) ?: return@mapNotNull null
            val language = track.optString("lang").lowercase().replace('_','-').let { if (it == "tur" || it == "tr" || it.startsWith("tr-")) "tr" else it }
            if (!language.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})?"))) return@mapNotNull null
            Caption(language, url)
        }.distinctBy { it.url }.sortedBy { if (it.language == "tr") 0 else 1 }
        return Playback(video, j.getBoolean("isHls"), captions)
    }
        internal fun retryUntil(header: String?, now: Long): Long {
            val seconds = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            if (seconds != null) return if (seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
            return runCatching { java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                .getOrNull()?.takeIf { it > now } ?: (now + 1_800_000)
        }
        internal fun timedText(text: String): Boolean {
            val t = text.removePrefix("\uFEFF").replace("\r\n", "\n").trimStart()
            if (t.startsWith("<")) return false
            val cue = Regex("""(?m)^(?:\d{2,}:)?\d{2}:\d{2}[.,]\d{3}\s*-->\s*(?:\d{2,}:)?\d{2}:\d{2}[.,]\d{3}[^\n]*\n([^\n]+)""")
            return cue.findAll(t).any { it.groupValues[1].replace(Regex("<[^>]*>"), "").any(Char::isLetter) }
        }

}
