package dev.arcana.verticalshorts

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

internal object VerticalContract {
    const val BASE = "https://verticalshorts.ai"
    const val NAME = "(Arcana Drama) VerticalShorts"
    private val aliases = mapOf(
        "dramaboxv2" to "c7", "dramabox" to "w8", "melolo" to "g6", "pinedrama" to "u4",
        "netshort" to "r7", "shortmax" to "z6", "flickreels" to "x5", "freereels" to "m4",
        "reelshort" to "y8", "goodshort" to "w6", "dramabite" to "j7", "idrama" to "v6",
        "starshort" to "e4", "flareflow" to "h4", "moboreels" to "d6", "dramanova" to "j9",
        "dramawave" to "u2", "playlet" to "f9", "iqiyi" to "a9", "stardustv" to "y3",
        "wetv" to "s9", "mydrama" to "d2", "microdrama" to "u5", "cubetv" to "t6",
        "rapidtv" to "c5", "topdrama" to "n5", "dotdrama" to "c3", "fundrama" to "x3",
        "reelife" to "t3", "serialplus" to "j8", "vigloo" to "p2", "raptdrama" to "m9",
        "flextv" to "u8", "shortswave" to "u9"
    )
    data class Card(val platform: String, val id: String, val coded: Boolean, val title: String, val url: String, val poster: String?, val count: Int?)
    data class Row(val name: String, val cards: List<Card>)
    data class Chapter(val platform: String, val id: String, val coded: Boolean, val number: Int, val episodeId: String?, val lang: String, val locked: Boolean) {
        val url get() = watchUrl(platform, id, coded, number)
    }
    data class Series(val platform: String, val id: String, val coded: Boolean, val title: String, val plot: String, val poster: String?, val tags: List<String>, val chapters: List<Chapter>)
    data class Caption(val language: String, val url: String) { override fun toString() = "Caption([redacted])" }
    data class Playback(val video: String, val hls: Boolean, val captions: List<Caption>, val expiring: Boolean) { override fun toString() = "Playback([redacted])" }

    fun https(raw: String): String? = runCatching {
        require(raw.isNotBlank() && raw.length <= 16000 && raw.none { it.code < 0x20 || it.code == 0x7f })
        val u = URI(BASE + "/").resolve(raw)
        require(u.scheme.equals("https", true) && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.port in setOf(-1, 443) && u.rawFragment == null)
        require(VerticalUrlPolicy.readOnly(u))
        u.toASCIIString()
    }.getOrNull()
    /** Candidate for an original WebView subresource, not a new HTTP request or API grant. */
    internal fun browserResourceHost(raw: String, method: String, mainFrame: Boolean): String? = runCatching {
        require(!mainFrame && method in setOf("GET", "HEAD"))
        require(URI(raw).isAbsolute) // No guessed relative destination.
        val u = URI(https(raw) ?: return@runCatching null)
        u.host.lowercase(Locale.ROOT)
    }.getOrNull()
    fun own(raw: String): URI? = https(raw)?.let(::URI)?.takeIf { it.host.equals("verticalshorts.ai", true) }
    private fun text(j: JSONObject, key: String) = (j.opt(key) as? String).orEmpty().trim()
    private fun validPlatform(value: String): String = value.also { require(it.matches(Regex("[a-z][a-z0-9_-]{0,39}"))) }
    private fun opaqueId(value: Any?): String = (value as? String)?.also {
        require(it.isNotBlank() && it == it.trim() && it.length <= 160 && it !in setOf(".", "..") && it.none { c -> c.code < 0x20 || c.code == 0x7f || c in "/\\%" })
    } ?: error("Publisher opaque string ID required")
    private fun ordinal(value: Any?): Int? = when (value) {
        is Int -> value.takeIf { it in 1..10000 }
        is Long -> value.takeIf { it in 1L..10000L }?.toInt()
        else -> null
    }
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")")
    private fun route(platform: String, id: String, coded: Boolean): String {
        validPlatform(platform); opaqueId(id)
        val code = aliases[platform] ?: return "$platform/${encoded(id)}"
        val prefixed = Regex("^[a-z][0-9]-").containsMatchIn(id) || coded
        return "dramaseries/" + if (prefixed) "$code-${encoded(id)}" else encoded(id)
    }
    internal fun titleSlug(title: String): String {
        val decomposed = Normalizer.normalize(title, Normalizer.Form.NFKD)
        val out = StringBuilder(); var latin = false
        for (cp in decomposed.codePoints().toArray()) {
            val mark = Character.getType(cp) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
            if (!(mark && latin)) out.appendCodePoint(cp)
            if (!mark) latin = Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
        }
        var slug = Normalizer.normalize(out, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .replace(Regex("['’`]"), "").replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), "-").trim('-')
        if (slug.length > 70) {
            val prefix = slug.take(71); val split = prefix.lastIndexOf('-')
            slug = (if (split >= 35) prefix.take(split) else slug.take(70)).trimEnd('-')
        }
        return slug.ifEmpty { "drama" }
    }
    fun seriesUrl(platform: String, id: String, coded: Boolean, title: String) = "$BASE/tr/drama/${route(platform, id, coded)}/${encoded(titleSlug(title))}"
    fun watchUrl(platform: String, id: String, coded: Boolean, episode: Int): String {
        require(episode in 1..10000)
        return "$BASE/tr/watch/${route(platform, id, coded)}/$episode"
    }
    private fun publicPath(raw: String): List<String>? = own(raw)?.takeIf { it.rawQuery == null }?.rawPath?.trim('/')?.split('/')?.let { parts ->
        if (parts.firstOrNull() == "tr") parts.drop(1) else parts
    }
    fun detailUrl(raw: String): Boolean = publicPath(raw)?.let { it.size in 3..4 && it[0] == "drama" && it[1].matches(Regex("[a-z][a-z0-9_-]*")) && it[2].isNotBlank() } == true
    fun sameSeriesPage(raw: String, platform: String, id: String, coded: Boolean, number: Int? = null): Boolean {
        val parts = publicPath(raw) ?: return false
        val base = route(platform, id, coded).split('/')
        if (parts.size < 3 || parts[1] != base[0] || parts[2] != base[1]) return false
        return if (number == null) parts[0] == "drama" && parts.size in 3..4 else parts[0] == "watch" && parts.size == 4 && parts[3] == number.toString()
    }
    /** Current top-level document identity; query/fragment cannot replace the full chapter path. */
    internal fun sameWatchDocument(origin: String, rawPath: String, chapter: Chapter): Boolean =
        origin == BASE && rawPath == URI(chapter.url).rawPath
    private fun card(j: JSONObject): Card? = runCatching {
        val p = validPlatform(text(j, "platform")); val i = opaqueId(j.opt("id")); require(j.opt("coded") is Boolean)
        val title = text(j, "title"); require(title.isNotEmpty())
        Card(p, i, j.getBoolean("coded"), title, seriesUrl(p, i, j.getBoolean("coded"), title), https(text(j, "cover")), ordinal(j.opt("episodes")))
    }.getOrNull()
    private fun cards(a: JSONArray?): List<Card> = (0 until minOf(a?.length() ?: 0, 1000)).mapNotNull { a?.optJSONObject(it)?.let(::card) }.distinctBy { it.platform to it.id }
    fun home(raw: String): List<Row> {
        val objects = FlightData.objects(raw)
        val hero = objects.firstOrNull { it.length() == 1 && it.optJSONArray("items") != null }
            ?.let { cards(it.optJSONArray("items")) }.orEmpty()
        val headings = Jsoup.parse(raw).select("h2").map { it.text().trim() }
        val named = objects.filter { it.optJSONArray("items") != null && text(it, "label").isNotBlank() }
            .mapNotNull { value -> cards(value.optJSONArray("items")).takeIf { it.isNotEmpty() }?.let { Row(text(value, "label"), it) } }
            .distinctBy { it.name }.sortedBy { row -> headings.indexOf(row.name).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        val tail = objects.filter { it.optJSONArray("items") != null && it.has("badge") && !it.has("label") }
            .flatMap { cards(it.optJSONArray("items")) }.distinctBy { it.platform to it.id }
        return buildList {
            if (hero.isNotEmpty()) add(Row("Öne Çıkanlar", hero))
            addAll(named)
            if (tail.isNotEmpty()) add(Row("Diğer Diziler", tail))
        }.also { require(it.isNotEmpty()) }
    }
    fun searchHtml(raw: String): List<Card> {
        val collections = FlightData.objects(raw).filter { it.length() == 1 && it.optJSONArray("items") != null }
        require(collections.size == 1)
        return cards(collections.single().getJSONArray("items"))
    }
    fun firstWatch(detail: String, expected: String): String {
        require(detailUrl(expected))
        val base = publicPath(expected)!!.subList(1, 3)
        val links = Jsoup.parse(detail, expected).select("a[href]").mapNotNull { a -> own(a.absUrl("href")) }
            .filter { u -> publicPath(u.toString())?.let { it.size == 4 && it[0] == "watch" && it.subList(1, 3) == base && it[3].toIntOrNull() in 1..10000 } == true }
        require(links.isNotEmpty())
        return links.minBy { publicPath(it.toString())!!.last().toInt() }.toASCIIString()
    }
    fun publicSearch(raw: String): List<Card> {
        val a = JSONObject(raw).getJSONArray("results")
        return (0 until minOf(a.length(), 20)).mapNotNull { index -> runCatching {
            val v = a.getJSONObject(index); val p = validPlatform(text(v, "platform")); val title = text(v, "title"); require(title.isNotEmpty())
            val original = own(text(v, "url")) ?: error("Source result URL required")
            val path = publicPath(original.toString()) ?: error("Source detail path required")
            require(path.size in 3..4 && path[0] == "drama")
            val rawId = URLDecoder.decode(path[2].replace("+", "%2B"), "UTF-8")
            val code = aliases[p]; val coded = code != null && rawId.startsWith("$code-")
            val i = opaqueId(if (coded) rawId.removePrefix("$code-") else rawId)
            require(route(p, i, coded).split('/') == path.subList(1, 3))
            val url = BASE + "/tr/" + path.joinToString("/")
            Card(p, i, coded, title, url, null, null)
        }.getOrNull() }.distinctBy { it.platform to it.id }
    }
    private fun binding(raw: String): JSONObject {
        val candidates = FlightData.objects(raw).filter {
            it.opt("platform") is String && it.opt("dramaId") is String && it.optJSONArray("episodes") != null && it.opt("description") is String && it.opt("lang") is String
        }
        require(candidates.size == 1)
        return candidates.single()
    }
    private fun episodeId(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> opaqueId(value)
        is Int, is Long -> value.toString().also { require(it.matches(Regex("[1-9][0-9]{0,14}"))) }
        else -> error("Actual episode ID required")
    }
    fun series(raw: String, expectedUrl: String): Series {
        val j = binding(raw); val p = validPlatform(text(j, "platform")); val i = opaqueId(j.opt("dramaId")); require(j.opt("coded") is Boolean)
        val coded = j.getBoolean("coded"); val n = publicPath(expectedUrl)
        val expectedBase = route(p, i, coded).split('/')
        require(n != null && n.size >= 3 && n.subList(1, 3) == expectedBase)
        val title = text(j, "title"); require(title.isNotEmpty())
        val lang = text(j, "lang"); require(lang.matches(Regex("[a-z]{2,3}(?:-[A-Za-z0-9]+)?")))
        val a = j.getJSONArray("episodes"); require(a.length() in 1..10000)
        val episodes = (0 until a.length()).map { index ->
            val e = a.getJSONObject(index); require(e.opt("locked") is Boolean)
            Chapter(p, i, coded, ordinal(e.opt("number")) ?: error("Actual ordinal required"), episodeId(e.opt("id")), lang, e.getBoolean("locked"))
        }
        require(episodes.map { it.number }.distinct().size == episodes.size)
        val tags = j.optJSONArray("tags")?.let { values -> (0 until values.length()).mapNotNull { index ->
            when (val v = values.opt(index)) { is String -> v.trim().takeIf { it.isNotEmpty() }; is JSONObject -> text(v, "label").takeIf { it.isNotEmpty() }; else -> null }
        } }.orEmpty().toMutableList()
        text(j, "platformName").takeIf { it.isNotEmpty() }?.let(tags::add)
        return Series(p, i, coded, title, text(j, "description"), https(text(j, "cover")), tags.distinct(), episodes.sortedBy { it.number })
    }
    fun encode(c: Chapter): String = JSONObject().put("v", 1).put("platform", c.platform).put("id", c.id).put("coded", c.coded).put("number", c.number).put("episodeId", c.episodeId ?: JSONObject.NULL).put("lang", c.lang).put("locked", c.locked).toString()
    fun decode(raw: String): Chapter? = runCatching {
        require(raw.length <= 2048); val j = JSONObject(raw)
        require(j.opt("v") == 1 && j.opt("coded") is Boolean && j.opt("locked") is Boolean)
        val lang = text(j, "lang"); require(lang.matches(Regex("[a-z]{2,3}(?:-[A-Za-z0-9]+)?")))
        Chapter(validPlatform(text(j, "platform")), opaqueId(j.opt("id")), j.getBoolean("coded"), ordinal(j.opt("number")) ?: error("Ordinal required"), episodeId(j.opt("episodeId")), lang, j.getBoolean("locked"))
    }.getOrNull()
    fun verifyWatch(raw: String, c: Chapter): Series = series(raw, c.url).also { require(it.chapters.any { e -> e == c }) }
    fun playback(raw: String, c: Chapter): Playback {
        val j = JSONObject(raw)
        require(j.optString("platform") == c.platform && j.opt("dramaId") is String && j.getString("dramaId") == c.id && ordinal(j.opt("episode")) == c.number)
        require(!j.optBoolean("locked", false))
        val type = text(j, "type"); require(type in setOf("hls", "mp4"))
        val video = https(text(j, "url")) ?: error("Source URL required")
        val a = j.optJSONArray("subtitles") ?: JSONArray()
        val captions = (0 until minOf(a.length(), 32)).mapNotNull { index ->
            val t = a.optJSONObject(index) ?: return@mapNotNull null
            val url = captionURL(text(t, "url")) ?: return@mapNotNull null
            val lang = text(t, "lang").lowercase(Locale.ROOT).replace('_', '-').let { if (it == "tur" || it == "tr" || it.startsWith("tr-")) "tr" else it }
            if (!lang.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})?"))) return@mapNotNull null
            Caption(lang, url)
        }.distinctBy { it.url }.sortedBy { if (it.language == "tr") 0 else 1 }
        return Playback(video, type == "hls", captions, j.optBoolean("expiringLinks", false))
    }
    /** Fixed diagnostic schema: no source URL, response text, identity or arbitrary string escapes. */
    internal fun diagnosticSummary(state: JSONObject, expectedPath: String): String {
        val observer = state.optJSONObject("observer")
        val result = observer?.optJSONObject("result")
        val body = (result?.opt("body") as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val selection = observer?.optString("selection").orEmpty().takeIf { it in setOf("matched", "params", "platform", "series", "episode", "language", "episode_id", "invalid") } ?: "none"
        return JSONObject()
            .put("origin_ok", state.optString("origin") == BASE).put("path_ok", state.optString("path") == expectedPath)
            .put("document", state.optString("doc").takeIf { it in setOf("loading", "interactive", "complete") } ?: "unknown")
            .put("guard_present", state.optBoolean("guardPresent")).put("guard_open", state.optBoolean("guardOpen"))
            .put("guard_bait", state.optBoolean("guardBait")).put("guard_library_signal", state.optBoolean("guardLib"))
            .put("observer_present", observer != null).put("observer_closed", observer?.optBoolean("closed") ?: false)
            .put("observer_terminal", observer?.optBoolean("terminal") ?: false).put("security_delivered", observer?.optBoolean("securityScriptDelivered") ?: false)
            .put("seen_play", (observer?.optInt("seenPlay", 0) ?: 0).coerceIn(0, 100))
            .put("matched_play", (observer?.optInt("latest", 0) ?: 0).coerceIn(0, 100)).put("selection", selection)
            .put("status", (result?.optInt("status", 0) ?: 0).takeIf { it in 100..599 } ?: 0)
            .put("body_present", result?.opt("body") is String).put("body_url_present", body?.opt("url") is String)
            .put("body_type", body?.optString("type").orEmpty().takeIf { it in setOf("hls", "mp4") } ?: "other")
            .put("body_platform_present", body?.opt("platform") is String).put("body_id_present", body?.opt("dramaId") is String)
            .put("body_episode_present", body?.has("episode") ?: false)
            .put("subtitle_count", (body?.optJSONArray("subtitles")?.length() ?: 0).coerceIn(0, 32))
            .put("video_count", state.optInt("videoCount", 0).coerceIn(0, 8))
            .put("video_source", state.optBoolean("videoSource")).put("video_error", state.optBoolean("videoError")).toString()
    }
    fun captionURL(raw: String) = https(raw)
    enum class Denial { ACCESS, RATE, HUMAN }
    fun jsonDenial(raw: String): Denial? = runCatching {
        val j = JSONObject(raw)
        val code = (j.opt("error") as? String ?: j.optJSONObject("error")?.optString("code") ?: j.opt("code") as? String).orEmpty().lowercase(Locale.ROOT)
        when (code) {
            "forbidden", "unauthorized", "access_denied", "bad_origin", "bad_referrer" -> Denial.ACCESS
            "rate_limited", "too_many_requests" -> Denial.RATE
            "challenge_required", "verification_required", "captcha_required" -> Denial.HUMAN
            else -> null
        }
    }.getOrNull()
    fun deniedHtml(raw: String): Boolean {
        val doc = Jsoup.parse(raw)
        return Regex("Access Denied|Attention Required|Just a moment|verify you are human|captcha|checking your browser", RegexOption.IGNORE_CASE).containsMatchIn(doc.title()) ||
            doc.select("a[href*=/drama/]").isEmpty() && Regex("you have been blocked|verify you are human|complete the captcha", RegexOption.IGNORE_CASE).containsMatchIn(doc.body().text().take(500))
    }
    fun timedText(raw: String): Boolean {
        val t = raw.removePrefix("\uFEFF").replace("\r\n", "\n").trimStart(); if (t.startsWith("<")) return false
        return Regex("""(?m)^(?:\d{2,}:)?\d{2}:\d{2}[.,]\d{3}\s*-->\s*(?:\d{2,}:)?\d{2}:\d{2}[.,]\d{3}[^\n]*\n([^\n]+)""").findAll(t).any { it.groupValues[1].replace(Regex("<[^>]*>"), "").any(Char::isLetter) }
    }
    internal fun retryUntil(header: String?, now: Long): Long {
        val seconds = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        if (seconds != null) return if (seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
        return runCatching { java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()?.takeIf { it > now } ?: now + 1_800_000
    }
}
