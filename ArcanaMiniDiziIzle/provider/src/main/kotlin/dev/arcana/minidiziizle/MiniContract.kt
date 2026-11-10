package dev.arcana.minidiziizle

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/** Publisher routes, real chapter ordinals and source editions remain separate identities. */
internal object MiniContract {
    const val BASE = "https://minidiziizle.com"
    const val NAME = "(Arcana Drama) MiniDiziİzle"
    val lists = linkedMapOf(
        "/yeni-eklenenler/" to "Yeni Eklenenler", "/en-cok-izlenenler/" to "Popüler Diziler",
        "/dublajli-mini-diziler/" to "Türkçe Dublajlı", "/altyazili-mini-diziler/" to "Türkçe Altyazılı",
        "/kategori/intikam/" to "İntikam", "/kategori/milyarder/" to "Milyarder",
        "/kategori/gizli-kimlik/" to "Gizli Kimlik", "/kategori/romantik/" to "Romantik Aşk"
    )
    data class Card(val url: String, val title: String, val poster: String?, val edition: String?)
    data class Row(val path: String, val name: String, val cards: List<Card>)
    data class Page(val cards: List<Card>, val next: String?)
    data class Chapter(val seriesId: Long, val page: String, val number: Int, val edition: String)
    data class Detail(val id: Long, val title: String, val poster: String?, val plot: String,
                      val tags: List<String>, val chapters: List<Chapter>, val related: List<Card>)
    data class Player(val chapter: Chapter, val episodeId: Long, val token: String, val pool: Boolean) {
        override fun toString() = "MiniPlayer([redacted])"
    }
    fun id(v: Any?): Long {
        require(v is Int || v is Long); return (v as Number).toLong().also { require(it in 1..9_007_199_254_740_991L) }
    }
    private fun url(raw: String): URI {
        require(raw.length in 1..16000 && raw.none { it.code < 32 || it.code == 127 || it == '\\' })
        return URI(BASE + "/").resolve(raw).also {
            require(it.scheme == "https" && !it.host.isNullOrBlank() && it.port in setOf(-1,443) && it.rawUserInfo == null)
        }
    }
    fun own(raw: String): String = url(raw).also { require(it.host == "minidiziizle.com" && it.rawFragment == null) }.toASCIIString()
    fun detailUrl(raw: String): String? = runCatching {
        val u = url(raw); require(u.host == "minidiziizle.com" && u.rawQuery == null)
        require(u.rawPath.matches(Regex("/icerik/[a-z0-9][a-z0-9-]{0,239}/?")))
        require(u.rawFragment == null || u.rawFragment in setOf("mini=altyazi","mini=dublaj"))
        u.toASCIIString()
    }.getOrNull()
    fun edition(raw: String): String? = URI(raw).rawFragment?.substringAfter("mini=")?.takeIf { it in setOf("altyazi","dublaj") }
    fun canonical(raw: String) = URI(raw).let { URI(it.scheme,it.authority,it.path,null,null).toASCIIString() }
    fun chapterUrl(raw: String): String = own(raw).also {
        val u = URI(it); require(u.rawQuery == null && u.rawPath.matches(Regex("/bolum/[a-z0-9][a-z0-9-]{0,279}/")))
    }
    fun archive(raw: String): String = own(raw).also {
        val u = URI(it); require(u.rawQuery == null && (u.path in lists || u.path == "/kategori/" ||
            u.path.matches(Regex("/(?:yeni-eklenenler|en-cok-izlenenler|dublajli-mini-diziler|altyazili-mini-diziler|kategori/[a-z0-9-]+)/page/[1-9][0-9]{0,4}/"))))
    }
    fun media(raw: String): String {
        val u = url(raw); require(u.rawFragment == null)
        val host = u.host.lowercase()
        require(host in setOf("minidiziizle.com","media.minidiziizle.com","storage-pilot.shortizle.com","mybigdramapool.com") ||
            host.endsWith(".mybigdramapool.com") || host.endsWith(".b-cdn.net"))
        MiniPolicy.readOnlyMedia(u)
        val path = u.path.lowercase()
        if(host == "minidiziizle.com")require(u.rawPath == u.path && path.startsWith("/wp-content/uploads/") && u.path.split('/').none { it in setOf(".","..") })
        require(path !in setOf("/session","/authorize") && !path.startsWith("/wp-admin"))
        return u.toASCIIString()
    }
    private fun image(raw: String): String? = runCatching { own(raw) }.getOrNull()?.takeIf {
        URI(it).path.startsWith("/wp-content/uploads/")
    }
    fun card(el: Element, forced: String? = null): Card? {
        val a = el.selectFirst("a.mdz-kart__baslik-link[href]") ?: return null
        val link = detailUrl(a.attr("href")) ?: return null
        val title = a.text().trim(); if (title.isEmpty()) return null
        val badge = el.select(".mdz-kart__rozet").text().lowercase(java.util.Locale.ROOT)
        val language = forced ?: when { "dublaj" in badge && "altyaz" !in badge -> "dublaj"; "altyaz" in badge -> "altyazi"; else -> null }
        return Card(canonical(link),title,el.selectFirst(".mdz-kart__afis img[src]")?.attr("src")?.let(::image),language)
    }
    fun home(html: String): List<Row> {
        val d = Jsoup.parse(html)
        return d.select("section.mdz-slider").mapNotNull { section ->
            val title = section.selectFirst("h2.mdz-slider__baslik")?.text().orEmpty()
            val path = section.select("a[href]").mapNotNull { a -> runCatching { URI(archive(a.attr("href"))).path }.getOrNull() }.firstOrNull { it in lists }
                ?: return@mapNotNull null
            val cards = section.select("article.mdz-kart").mapNotNull { card(it) }.distinctBy { it.url }
            if (title.isBlank() || cards.isEmpty()) null else Row(path,title,cards)
        }.distinctBy { it.path }.also { require(it.isNotEmpty()) }
    }
    fun listing(html: String, current: String, forced: String?): Page {
        val url = URI(archive(current)); val root = Jsoup.parse(html).selectFirst(".mdz-arsiv") ?: error("Missing source archive")
        require(root.selectFirst(".mdz-katalog") != null)
        val cards = root.select(".mdz-katalog article.mdz-kart").mapNotNull { card(it,forced) }.distinctBy { it.url }
        val next = root.selectFirst("a.next.page-numbers[href]")?.attr("href")?.let(::archive)?.also {
            val target = URI(it); require(target.path != url.path)
            val base = url.path.replace(Regex("page/[0-9]+/$"),"")
            val now = Regex("/page/([0-9]+)/$").find(url.path)?.groupValues?.get(1)?.toInt() ?: 1
            require(target.path == base + "page/${now+1}/")
        }
        return Page(cards,next)
    }
    fun search(body: String): List<Card> {
        val a = JSONObject(body).getJSONArray("sonuclar"); require(a.length() <= 200)
        return (0 until a.length()).mapNotNull { i ->
            val j = a.getJSONObject(i); val route = detailUrl(j.optString("link")) ?: return@mapNotNull null
            val title = j.optString("baslik").trim(); if (title.isBlank()) return@mapNotNull null
            Card(canonical(route),title,image(j.optString("afis")),null)
        }.distinctBy { it.url }
    }
    fun detail(html: String, route: String): Detail {
        require(detailUrl(route) != null); val d = Jsoup.parse(html)
        require(d.select("link[rel=canonical][href]").map { canonical(it.attr("href")) }.single() == canonical(route))
        val root = d.selectFirst("article.mdz-tekil.dizi") ?: error("Missing source title")
        val sourceId = root.classNames().mapNotNull { Regex("post-([1-9][0-9]{0,15})").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }.single()
        val title = root.selectFirst("h1.mdz-tekil__baslik")?.text()?.takeIf { it.isNotBlank() } ?: error("Missing title")
        val meta = root.select(".mdz-tekil__ozel-bilgiler").text().lowercase(java.util.Locale.ROOT)
        val language = edition(route) ?: when { "altyaz" in meta -> "altyazi"; "dublaj" in meta -> "dublaj"; else -> error("Source edition missing") }
        if(language == "dublaj")require("dublaj" in meta)
        if(language == "altyazi")require("altyaz" in meta)
        val chapters = chapters(root,sourceId,language)
        require(chapters.isNotEmpty())
        return Detail(sourceId,title,root.selectFirst(".mdz-tekil__afis img[src]")?.attr("src")?.let(::image),
            root.select(".mdz-tekil__icerik").text(),root.select(".mdz-tekil__meta a").map { it.text() }.distinct(),chapters,
            d.select(".mdz-benzer article.mdz-kart").mapNotNull { card(it) }.distinctBy { it.url })
    }
    private fun chapters(root: Element, sourceId: Long, language: String): List<Chapter> {
        val a = root.select("ul.mdz-bolumler__liste a.mdz-bolum__link[href]"); require(a.size in 1..10000)
        val rows = a.map { link ->
            val n = link.selectFirst(".mdz-bolum__no")?.text()?.toIntOrNull() ?: error("Missing chapter number")
            require(n in 1..10000); Chapter(sourceId,chapterUrl(link.attr("href")),n,language)
        }
        require(rows.map { it.number }.toSet().size == rows.size && rows.map { it.page }.toSet().size == rows.size)
        return rows.sortedBy { it.number }
    }
    fun encode(ch: Chapter): String = JSONObject().put("schema",1).put("series",ch.seriesId).put("page",ch.page).put("number",ch.number).put("edition",ch.edition).toString()
    fun decode(raw: String): Chapter? = runCatching {
        require(raw.length <= 4096); val j = JSONObject(raw)
        require(j.keys().asSequence().toSet() == setOf("schema","series","page","number","edition") && j.opt("schema") == 1)
        val n = id(j.opt("number")); require(n <= 10000)
        val lang = j.getString("edition"); require(lang in setOf("altyazi","dublaj"))
        Chapter(id(j.opt("series")),chapterUrl(j.getString("page")),n.toInt(),lang)
    }.getOrNull()
    fun player(html: String, expected: Chapter): Player {
        val d = Jsoup.parse(html)
        require(d.select("link[rel=canonical][href]").map { chapterUrl(it.attr("href")) }.single() == expected.page)
        val p = d.select(".mdz-oynatici[data-yapim][data-bolum]").single()
        require(p.attr("data-yapim").toLongOrNull() == expected.seriesId)
        val episodeId = p.attr("data-bolum").toLong(); require(episodeId in 1..9_007_199_254_740_991L)
        val current = d.select("ul.mdz-bolumler__liste a.mdz-bolum__link[aria-current=page]").single()
        require(current.selectFirst(".mdz-bolum__no")?.text()?.toIntOrNull() == expected.number && chapterUrl(current.attr("href")) == expected.page)
        require(chapters(d,expected.seriesId,expected.edition).any { it.number == expected.number && it.page == expected.page })
        val token = p.attr("data-jeton"); require(token.length in 1..2048 && token.none { it.code < 32 })
        return Player(expected,episodeId,token,p.attr("data-havuz") == "1")
    }
    fun playback(j: JSONObject, expected: Player): JSONObject {
        require(id(j.opt("id")) == expected.episodeId && j.optString("dil") == expected.chapter.edition)
        require(j.optString("tur") in setOf("hls","mp4","video","depo","iframe"))
        return j
    }
}
