package dev.arcana.dramadunyam

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.time.Instant
import java.time.OffsetDateTime

internal object DunyaContract {
    const val NAME = "(Arcana Drama) DramaDünyam"
    const val BASE = "https://dramadunyam.com"
    private val origin = URI(BASE)
    data class Card(val id: String, val slug: String, val title: String, val poster: String?, val count: Int?)
    data class Row(val name: String, val cards: List<Card>)
    data class Chapter(val id: String, val slug: String, val number: Int, val url: String)
    data class Series(val id: String, val slug: String, val title: String, val poster: String?, val plot: String, val tags: List<String>, val chapters: List<Chapter>, val chapterEvidence: String)
    data class Caption(val language: String, val url: String) { override fun toString() = "Caption(language=$language,[redacted])" }
    data class Playback(val video: String, val hls: Boolean, val captions: List<Caption>, val expiresAt: Long?) { override fun toString() = "Playback([redacted])" }

    fun https(raw: String): String? = runCatching {
        require(raw.isNotBlank() && raw.length <= 16000 && raw.none { it.code < 0x20 || it.code == 0x7f })
        val u = origin.resolve(raw)
        require(u.scheme.equals("https", true) && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.port in setOf(-1, 443) && u.rawFragment == null)
        require(DunyaUrlPolicy.readOnly(u))
        u.toASCIIString()
    }.getOrNull()
    fun own(raw: String): URI? = https(raw)?.let { URI(it) }?.takeIf { it.host.equals("dramadunyam.com", true) }
    fun detailSlug(raw: String): String? = own(raw)?.takeIf { it.rawQuery == null }?.path?.removeSuffix("/")?.let { path ->
        val parts = path.removePrefix("/").split('/')
        parts.takeIf { it.size == 2 && it[0] == "dizi" }?.get(1)?.takeIf(::validSlug)
    }
    fun sameSeriesPage(raw: String, expected: String): Boolean {
        val u = own(raw) ?: return false
        if (u.rawQuery != null) return false
        val p = u.path.removeSuffix("/").removePrefix("/").split('/')
        return p.size in 2..3 && p[0] == "dizi" && p[1] == expected &&
            (p.size == 2 || p[2].matches(Regex("bolum-[1-9][0-9]*")))
    }
    fun deniedHtml(raw: String): Boolean {
        val d = Jsoup.parse(raw)
        if (Regex("Access Denied|Attention Required|Just a moment|verify you are human|captcha|checking your browser", RegexOption.IGNORE_CASE).containsMatchIn(d.title())) return true
        return d.select("a[href*=/dizi/]").isEmpty() && Regex("you have been blocked|verify you are human|checking your browser|complete the captcha", RegexOption.IGNORE_CASE).containsMatchIn(d.body().text().take(500))
    }
    private fun validSlug(value: String) = value.matches(Regex("[a-z0-9][a-z0-9-]{0,239}"))
    fun seriesUrl(slug: String): String {
        require(validSlug(slug))
        return URI("https", "dramadunyam.com", "/dizi/$slug", null).toASCIIString()
    }
    private fun opaqueId(value: Any?): String {
        val id = when (value) { is String -> value; is Int, is Long -> value.toString(); else -> error("Publisher id required") }
        require(id.isNotBlank() && id == id.trim() && id.length <= 160 && id !in setOf("null", "undefined") && id.none { it.code < 0x20 || it.code == 0x7f })
        return id
    }
    private fun integer(value: Any?, minimum: Int = 0): Int? = when (value) {
        is Int -> value.takeIf { it in minimum..10000 }
        is Long -> value.takeIf { it in minimum.toLong()..10000L }?.toInt()
        else -> null
    }
    private fun text(j: JSONObject, key: String): String = (j.opt(key) as? String).orEmpty().trim()
    fun card(j: JSONObject): Card? = runCatching {
        val id = opaqueId(j.opt("id")); val slug = text(j, "slug"); require(validSlug(slug))
        val title = text(j, "title"); require(title.isNotBlank() && title.length <= 2048)
        Card(id, slug, title, https(text(j, "cover")), integer(j.opt("available_episodes")))
    }.getOrNull()
    private fun cards(array: JSONArray?): List<Card> = (0 until minOf(array?.length() ?: 0, 1000)).mapNotNull { array?.optJSONObject(it)?.let(::card) }.distinctBy { it.slug }
    fun home(raw: String): List<Row> {
        val j = JSONObject(raw); val result = mutableListOf<Row>()
        cards(j.optJSONArray("hero")).takeIf { it.isNotEmpty() }?.let { result.add(Row("Öne Çıkanlar", it)) }
        val rows = j.getJSONArray("rows")
        for (index in 0 until minOf(rows.length(), 30)) {
            val row = rows.optJSONObject(index) ?: continue
            val name = text(row, "title"); val items = cards(row.optJSONArray("items"))
            if (name.isNotBlank() && items.isNotEmpty()) result.add(Row(name, items))
        }
        require(result.isNotEmpty())
        return result
    }
    data class Page(val cards: List<Card>, val hasNext: Boolean)
    fun page(raw: String, page: Int): Page {
        val j = JSONObject(raw); require(page >= 1)
        val data = j.optJSONArray("data") ?: j.optJSONArray("series") ?: error("Publisher catalogue required")
        val next = (integer(j.opt("pages"), 1) ?: 1) > page
        return Page(cards(data), next)
    }
    fun chapter(raw: String, id: String, expectedSlug: String): Chapter? = runCatching {
        val u = own(raw) ?: error("Publisher required"); require(u.rawQuery == null)
        val p = u.path.removeSuffix("/").removePrefix("/").split('/')
        require(p.size == 3 && p[0] == "dizi" && p[1] == expectedSlug && p[2].startsWith("bolum-"))
        val number = p[2].removePrefix("bolum-").toIntOrNull()?.takeIf { it in 1..10000 } ?: error("Ordinal required")
        Chapter(opaqueId(id), expectedSlug, number, u.toASCIIString())
    }.getOrNull()
    fun chapterLinks(html: String,id: String,slug: String): List<Chapter> = Jsoup.parse(html,seriesUrl(slug)).select("a[href]")
        .mapNotNull { a -> chapter(a.absUrl("href"),id,slug) }.distinctBy { it.number }.sortedBy { it.number }
    /** Bounded structural telemetry only: no page text, URL, title, cookie or runtime key. */
    fun structure(raw: String,html: String?,slug: String): String = runCatching {
        val j=JSONObject(raw)
        fun kind(v: Any?):String=when(v){is String->"string";is Int->"int";is Long->"long";is Double->"double";is JSONArray->"array";null,JSONObject.NULL->"missing";else->"other"}
        val out=JSONObject().put("idType",kind(j.opt("id"))).put("availableType",kind(j.opt("available_episodes")))
            .put("available",integer(j.opt("available_episodes")) ?: -1).put("slugMatches",j.optString("slug")==slug)
            .put("numberArray",j.optJSONArray("bolum_no")?.length() ?: -1).put("htmlChars",html?.length ?: 0)
        if(html!=null){
            val d=Jsoup.parse(html,seriesUrl(slug))
            out.put("allAnchors",d.select("a[href]").size).put("episodeLike",d.select("a[href*=bolum-]").size)
                .put("dataEp",d.select("[data-ep]").size).put("detailTiles",d.select(".dt-bolum").size)
                .put("chapters",chapterLinks(html,opaqueId(j.opt("id")),slug).size)
        }
        out.toString()
    }.getOrElse { JSONObject().put("structureError",it.javaClass.simpleName.take(80)).toString() }
    fun series(raw: String, html: String?, expectedSlug: String): Series {
        val j = JSONObject(raw); val id = opaqueId(j.opt("id")); val slug = text(j, "slug")
        require(slug == expectedSlug && validSlug(slug))
        val title = text(j, "title"); require(title.isNotBlank() && title.length <= 2048)
        val available = integer(j.opt("available_episodes")) ?: error("Published availability required")
        val declared = j.optJSONArray("bolum_no")?.takeIf { it.length()>0 }
        val chapters = if (declared != null) {
            (0 until declared.length()).map { index ->
                val number = integer(declared.opt(index), 1) ?: error("Published ordinal required")
                Chapter(id, slug, number, seriesUrl(slug) + "/bolum-$number")
            }.also { require(it.size <= 10000 && it.map { c -> c.number }.distinct().size == it.size) }
        } else {
            // Exact current publisher app.js bolumVar contract: no nonempty explicit set
            // means ordinals 1..available_episodes, never total/source_total/DB counts.
            // This is declared availability, not independent playback proof for all ordinals.
            (1..available).map { number -> Chapter(id,slug,number,seriesUrl(slug)+"/bolum-$number") }
        }.sortedBy { it.number }
        require(chapters.isNotEmpty())
        val tags = linkedSetOf<String>()
        text(j, "genre").split(Regex("[,|/]" )).map(String::trim).filter(String::isNotEmpty).forEach(tags::add)
        text(j, "platform").takeIf(String::isNotEmpty)?.let(tags::add)
        j.optJSONArray("tags")?.let { a -> for (i in 0 until minOf(a.length(), 200)) a.optJSONObject(i)?.let { text(it, "name").takeIf(String::isNotEmpty)?.let(tags::add) } }
        val poster = j.optJSONObject("cover_variants")?.let { https(text(it, "720")) } ?: https(text(j, "cover"))
        val plot = Jsoup.parse(text(j, "description")).wholeText().trim()
        return Series(id, slug, title, poster, plot, tags.toList(), chapters,if(declared!=null)"API_EXPLICIT_SET"else"PUBLISHER_DECLARED_AVAILABLE_RANGE")
    }
    fun encode(c: Chapter): String = JSONObject().put("v", 1).put("id", c.id).put("slug", c.slug).put("number", c.number).put("url", c.url).toString()
    fun decode(raw: String): Chapter? = runCatching {
        require(raw.length <= 20000)
        val j = JSONObject(raw); require(j.opt("v") == 1)
        val id = opaqueId(j.opt("id")); val slug = j.getString("slug"); require(validSlug(slug))
        val number = integer(j.opt("number"), 1) ?: error("Ordinal required")
        chapter(j.getString("url"), id, slug)?.also { require(it.number == number) }
    }.getOrNull()
    fun playback(j: JSONObject): Playback {
        val video = https(text(j, "url")) ?: error("Publisher video required")
        val type = text(j, "type").lowercase(); require(type in setOf("hls", "mp4"))
        val tracks = j.optJSONArray("altyazilar") ?: JSONArray()
        val captions = (0 until minOf(tracks.length(), 64)).mapNotNull { index ->
            val t = tracks.optJSONObject(index) ?: return@mapNotNull null
            val url = captionURL(text(t, "src")) ?: return@mapNotNull null
            val language = text(t, "dil").lowercase().replace('_', '-').let { if (it == "tr" || it == "tur" || it.startsWith("tr-")) "tr" else it }
            if (!language.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})?"))) return@mapNotNull null
            Caption(language, url)
        }.distinctBy { it.url }.sortedBy { if (it.language == "tr") 0 else 1 }
        val expiry = text(j, "expires_at").takeIf(String::isNotBlank)?.let { value ->
            value.toLongOrNull()?.let { if (it > 1_000_000_000_000L) it else if (it in 1..9_000_000_000L) it * 1000 else null }
                ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        }
        return Playback(video, type == "hls", captions, expiry)
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
