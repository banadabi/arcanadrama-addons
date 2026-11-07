package dev.arcana.dramatr

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

/** Only catalogue/chapter identities persist. Play API URLs remain transient. */
internal object DramaTRContract {
    const val BASE = "https://dramatr.com"
    private val slugPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    data class Chapter(val slug: String, val number: Int) {
        val url: String get() = "$BASE/dramaizle/$slug-bolum-$number"
    }
    data class Card(val title: String, val url: String, val poster: String?, val count: Int?)
    data class Listing(val cards: List<Card>, val more: Boolean)
    data class Series(val title: String, val url: String, val poster: String?, val plot: String,
                      val tags: List<String>, val chapters: List<Chapter>)
    data class Video(val url: String, val type: String, val label: String)
    data class Caption(val url: String, val language: String)
    data class Playback(val videos: List<Video>, val captions: List<Caption>)

    fun https(raw: String): String? = runCatching {
        val u = URI(raw)
        require(u.scheme == "https" && u.port in setOf(-1,443) && u.rawUserInfo == null && u.rawFragment == null)
        val host = u.host?.lowercase() ?: error("host")
        require(host.contains('.') && !host.contains(':') && host.none { it.isWhitespace() } &&
            !Regex("[0-9.]+").matches(host) && !host.endsWith(".local") && !host.endsWith(".localhost"))
        u.toASCIIString()
    }.getOrNull()
    fun own(raw: String): String? = https(raw)?.takeIf { URI(it).host == "dramatr.com" }
    fun absolute(raw: String): String? = raw.takeIf(String::isNotBlank)?.let { runCatching { URI("$BASE/").resolve(it).toASCIIString() }.getOrNull() }?.let(::https)
    private fun validSlug(s: String) = s.length in 1..240 && slugPattern.matches(s) && !Regex(".*-bolum-[0-9]+$").matches(s)
    fun detailSlug(raw: String): String? = own(raw)?.let { text ->
        val u = URI(text)
        if (u.rawQuery != null) return@let null
        Regex("/dramaizle/([a-z0-9-]+)/?").matchEntire(u.rawPath)?.groupValues?.get(1)?.takeIf(::validSlug)
    }
    fun chapter(raw: String): Chapter? = own(raw)?.let { text ->
        val u = URI(text)
        if (u.rawQuery != null) return@let null
        val m = Regex("/dramaizle/([a-z0-9-]+)-bolum-([1-9][0-9]{0,5})/?").matchEntire(u.rawPath) ?: return@let null
        val s = m.groupValues[1].takeIf(::validSlug) ?: return@let null
        Chapter(s,m.groupValues[2].toInt())
    }
    fun encode(c: Chapter): String = JSONObject().put("v",1).put("kind","dramatr_episode").put("url",c.url).toString()
    fun decode(raw: String): Chapter? = runCatching {
        val j = JSONObject(raw)
        if (j.optInt("v") != 1 || j.optString("kind") != "dramatr_episode") null else chapter(j.optString("url"))
    }.getOrNull()
    private fun array(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
    private fun card(j: JSONObject): Card? {
        val url = absolute(j.optString("url")) ?: return null
        if (detailSlug(url) == null) return null
        val title = j.optString("title").trim().takeIf(String::isNotEmpty) ?: return null
        return Card(title,url,j.optString("poster").takeIf(String::isNotBlank)?.let(::absolute),j.optInt("episode_count").takeIf { it > 0 })
    }
    fun listing(raw: String): Listing {
        val j = JSONObject(raw)
        require(j.optBoolean("ok") && j.optJSONArray("items") != null) { "catalogue" }
        return Listing(array(j.optJSONArray("items")).mapNotNull(::card).distinctBy { it.url },j.optBoolean("has_more",false))
    }
    private fun nodes(value: Any?): List<JSONObject> = when (value) {
        is JSONArray -> (0 until value.length()).flatMap { nodes(value.opt(it)) }
        is JSONObject -> listOf(value) + nodes(value.opt("@graph"))
        else -> emptyList()
    }
    fun series(raw: String, slug: String): Series {
        require(validSlug(slug))
        val url = "$BASE/dramaizle/$slug"
        val doc = Jsoup.parse(raw,url)
        val data = doc.select("script[type=application/ld+json]").flatMap {
            runCatching { nodes(org.json.JSONTokener(it.data()).nextValue()) }.getOrDefault(emptyList())
        }.firstOrNull { it.optString("@type") == "TVSeries" && detailSlug(it.optString("url")) == slug }
            ?: error("series identity")
        val title = data.optString("name").trim().takeIf(String::isNotEmpty) ?: error("title")
        val image = doc.selectFirst(".sd-poster img[src]")?.attr("src")?.let(::absolute)
            ?: (data.optJSONObject("image")?.optString("url") ?: data.optString("image")).let(::absolute)
        val chapters = doc.select("a.sd-ecard[href]").mapNotNull { absolute(it.attr("href"))?.let(::chapter) }
            .filter { it.slug == slug }.distinctBy { it.number }.sortedBy { it.number }
        require(chapters.isNotEmpty()) { "chapters" }
        // Preserve gaps/unreleased chapters rather than fabricating 1..numberOfEpisodes.
        val genre = data.opt("genre")
        val genres = when (genre) {
            is JSONArray -> (0 until genre.length()).map { genre.optString(it) }
            is String -> listOf(genre)
            else -> emptyList()
        }
        val platform = data.optJSONObject("productionCompany")?.optString("name").orEmpty()
        return Series(title,url,image,data.optString("description"),(genres + platform).filter(String::isNotBlank).distinct(),chapters)
    }
    fun playback(raw: String, chapter: Chapter): Playback {
        val j = JSONObject(raw)
        require(j.optBoolean("ok") && (j.isNull("error") || j.optString("error").isBlank())) { "access" }
        require(j.optString("series_slug") == chapter.slug && j.optInt("number") == chapter.number && j.optLong("episode_id") > 0) { "episode identity" }
        val videos = array(j.optJSONArray("sources")).mapNotNull { s ->
            val url = s.optString("url").takeIf(String::isNotBlank) ?: s.optString("src")
            val valid = absolute(url)?.takeIf { url.isNotBlank() } ?: return@mapNotNull null
            val type = when (s.optString("type").lowercase()) {
                "hls","m3u8" -> "hls"
                "mp4","video/mp4" -> "mp4"
                else -> return@mapNotNull null
            }
            Video(valid,type,s.optString("label").takeIf(String::isNotBlank) ?: s.optString("quality"))
        }.distinctBy { it.url }
        require(videos.isNotEmpty()) { "no native source" }
        val captions = array(j.optJSONArray("subtitles")).mapNotNull { s ->
            val url = s.optString("url").takeIf(String::isNotBlank) ?: s.optString("src")
            val valid = absolute(url)?.takeIf { url.isNotBlank() } ?: return@mapNotNull null
            val language = listOf("lang","language","label").map { s.optString(it).trim() }.firstOrNull(String::isNotEmpty) ?: return@mapNotNull null
            val iso = language.replace('_','-').lowercase()
            Caption(valid,if (iso == "tr" || Regex("tr-[a-z0-9]{2,8}(?:-[a-z0-9]{2,8})*").matches(iso)) "tr" else language)
        }.distinctBy { it.url }
        return Playback(videos,captions)
    }
}
