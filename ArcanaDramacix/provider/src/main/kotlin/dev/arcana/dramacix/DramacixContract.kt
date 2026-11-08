package dev.arcana.dramacix

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Protocol draft from existing publisher-reader contracts. No transport or challenge handling. */
internal object DramacixContract {
    const val BASE = "https://dramacix.com"
    private val slugPattern = Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")
    data class Chapter(val slug: String, val number: Int) {
        val url: String get() = "$BASE/dizi/$slug/bolum-$number"
    }
    class Ticket(val chapter: Chapter, val token: String) {
        override fun toString() = "Dramacix chapter ticket (transient)"
    }
    data class Series(val title: String, val description: String, val poster: String?,
                      val genres: List<String>, val chapters: List<Chapter>)
    data class Card(val title: String, val url: String, val poster: String?, val count: Int?)
    class Resource(val url: String, val referer: String, val contentPath:String=URI(url).path, val upstream:String?=null) {
        override fun toString()="DramaCix transient publisher resource"
    }
    class Caption(val resource: Resource, val language: String)
    class Playback(val video: Resource, val captions: List<Caption>)
    data class Listing(val cards: List<Card>,val more: Boolean)
    private fun validSlug(s: String) = s.length in 1..240 && slugPattern.matches(s)
    fun own(raw: String): URI? = runCatching {
        val u=URI(raw)
        require(u.scheme=="https" && u.host=="dramacix.com" && u.port in setOf(-1,443) && u.rawUserInfo==null && u.rawFragment==null)
        require(u.rawPath==u.normalize().rawPath)
        u
    }.getOrNull()
    fun detailSlug(raw: String): String? = own(raw)?.takeIf { it.rawQuery==null }?.let {
        Regex("/dizi/([A-Za-z0-9-]+)/?").matchEntire(it.rawPath)?.groupValues?.get(1)?.takeIf(::validSlug)
    }
    fun chapter(raw: String): Chapter? = own(raw)?.takeIf { it.rawQuery==null }?.let {
        val m=Regex("/dizi/([A-Za-z0-9-]+)/bolum-([1-9][0-9]{0,3})/?").matchEntire(it.rawPath) ?: return@let null
        val slug=m.groupValues[1].takeIf(::validSlug) ?: return@let null
        Chapter(slug,m.groupValues[2].toInt())
    }
    fun encode(c: Chapter) = JSONObject().put("v",1).put("kind","dramacix_episode").put("url",c.url).toString()
    fun decode(raw: String): Chapter? = runCatching {
        val j=JSONObject(raw)
        if(j.optInt("v")!=1 || j.optString("kind")!="dramacix_episode") null else chapter(j.optString("url"))
    }.getOrNull()
    private fun integer(value: Any?): Int? = (value as? Number)?.takeIf { it is Int || it is Long }
        ?.toLong()?.takeIf { it in 1..9999 }?.toInt()
    private fun records(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
    private fun config(html: String): JSONObject {
        val pattern=Regex("(?m)(?:^|[;\\r\\n])\\s*(?:(?:const|let|var)\\s+|window\\.)?DH_CONFIG\\s*=\\s*")
        val values=Jsoup.parse(html).select("script:not([src])").flatMap { script ->
            val text=script.data()
            pattern.findAll(text).map { JSONTokener(text.substring(it.range.last+1)).nextValue() as? JSONObject ?: error("config") }.toList()
        }
        require(values.size==1) { "config identity" }
        return values.single()
    }
    /** Bind the requested episode; never use the stale first item's src as a fallback. */
    fun ticket(html: String, c: Chapter): Ticket {
        require(validSlug(c.slug) && c.number in 1..9999)
        val doc=Jsoup.parse(html,c.url);val j=config(html)
        require(j.optString("slug")==c.slug && integer(j.opt("urlEp"))==c.number) { "selected episode identity" }
        val total=integer(j.opt("toplamBolum")) ?: error("count")
        require(c.number<=total)
        val metas=doc.select("meta[property=og:video]")
        require(metas.size==1)
        val embed=own(URI(c.url).resolve(metas.single().attr("content")).toASCIIString()) ?: error("embed origin")
        require(embed.rawPath=="/embed/${c.slug}" && embed.rawQuery=="ep=${c.number}") { "embed identity" }
        val selected=records(j.optJSONArray("items")).filter { it.optString("slug")==c.slug && integer(it.opt("ep"))==c.number }
        require(selected.size==1) { "unique chapter" }
        val token=selected.single().opt("t") as? String ?: error("ticket")
        require(token.length in 1..4096) { "ticket" }
        return Ticket(c,token)
    }
    fun series(html: String, slug: String): Series {
        require(validSlug(slug));val url="$BASE/dizi/$slug";val doc=Jsoup.parse(html,url)
        val structured=doc.select("script[type=application/ld+json]").flatMap {
            val j=runCatching { JSONTokener(it.data()).nextValue() }.getOrNull()
            when(j) { is JSONObject -> listOf(j);is JSONArray -> records(j);else -> emptyList() }
        }
        val data=structured.filter { it.optString("@type")=="TVSeries" && detailSlug(it.optString("url"))==slug }
        require(data.size==1) { "series identity" }
        val j=config(html);require(j.optString("slug")==slug)
        val chapters=records(j.optJSONArray("items")).mapNotNull { item ->
            val n=integer(item.opt("ep")) ?: return@mapNotNull null
            if(item.optString("slug")!=slug) return@mapNotNull null
            Chapter(slug,n)
        }.distinctBy { it.number }.sortedBy { it.number }
        require(chapters.isNotEmpty()) { "real chapter records" }
        val name=data.single().optString("name").trim();require(name.isNotEmpty())
        val rawImage=data.single().opt("image")
        val poster=(if(rawImage is JSONObject)rawImage.optString("url") else rawImage as? String)?.takeIf(String::isNotBlank)
            ?.let { runCatching { URI(url).resolve(it).toASCIIString() }.getOrNull() }
            ?.takeIf { runCatching { URI(it).scheme=="https" && URI(it).rawUserInfo==null }.getOrDefault(false) }
        val genres=doc.select(".dizi-turler a.dizi-tur").map { it.text().trim() }.filter(String::isNotBlank).distinct()
        return Series(name,data.single().optString("description"),poster,genres,chapters)
    }
    /** Preserve the rendered-page access gate on an inert HTTP200 HTML response too. */
    fun deniedHtml(html:String):Boolean {
        val doc=Jsoup.parse(html)
        val probe=doc.title()+" "+doc.body().text().take(500)
        return Regex("Access Denied|Attention Required|you have been blocked|verify you are human",RegexOption.IGNORE_CASE).containsMatchIn(probe)
    }
    /** Only metadata from the existing card markup; no synthetic chapters or declared audio inference. */
    fun cards(html: String): List<Card> = Jsoup.parse(html,BASE).select("a.card[href]").mapNotNull { a ->
        val url=runCatching { URI(BASE).resolve(a.attr("href")).toASCIIString() }.getOrNull() ?: return@mapNotNull null
        if(detailSlug(url)==null)return@mapNotNull null
        val title=a.selectFirst(".card-title, .card-name")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: a.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val image=a.selectFirst("img[src]")?.attr("src")?.takeIf(String::isNotBlank)?.let { URI(BASE).resolve(it).toASCIIString() }
        val count=Regex("^([1-9][0-9]{0,3}) Bölüm$").matchEntire(a.selectFirst(".kr-bol")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        Card(title,url,image,count)
    }.distinctBy { it.url }

    fun listing(raw: String,page: Int): Listing {
        require(page>0)
        if(raw.trimStart().startsWith("{")) {
            val j=JSONObject(raw)
            val total=j.optInt("toplamSayfa")
            val items=cards(j.getString("html"))
            require(j.optBoolean("ok") && j.optInt("sayfa")==page && (total>=page || page==1 && total==0 && items.isEmpty()))
            val more=page<total
            if(!j.isNull("hasMore"))require(j.opt("hasMore") is Boolean && j.getBoolean("hasMore")==more)
            return Listing(items,more)
        }
        val doc=Jsoup.parse(raw,BASE)
        val scripts=doc.select("script:not([src])").joinToString("\n") { it.data() }
        val current=Regex("\\b(?:var|let|const)\\s+suankiSayfa\\s*=\\s*([0-9]+)").find(scripts)?.groupValues?.get(1)?.toIntOrNull()
        val total=Regex("\\b(?:var|let|const)\\s+toplamSayfa\\s*=\\s*([0-9]+)").find(scripts)?.groupValues?.get(1)?.toIntOrNull()
        val items=cards(doc.selectFirst("#diziGrid")?.outerHtml() ?: raw)
        require(current==page && total!=null && (total>=page || page==1 && total==0 && items.isEmpty())) { "catalogue page identity" }
        return Listing(items,current<total)
    }
    fun publicHttps(raw: String): URI? = runCatching {
        val u=URI(raw);val host=u.host?.lowercase() ?: error("host")
        val canonical=host.trimEnd('.')
        if(canonical=="dramacix.com" || canonical.endsWith(".dramacix.com"))require(u.host==canonical) { "canonical source host" }
        require(u.scheme=="https" && u.port in setOf(-1,443) && u.rawUserInfo==null && u.rawFragment==null)
        require(host.contains('.') && !host.contains(':') && !Regex("[0-9.]+").matches(host) && !host.endsWith(".local") && !host.endsWith(".localhost"))
        require(u.rawPath==u.normalize().rawPath);u
    }.getOrNull()
    private fun parameters(u: URI): Map<String,String> {
        val result=mutableMapOf<String,String>()
        for(pair in u.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)) {
            val p=pair.split('=',limit=2);val key=URLDecoder.decode(p[0],"UTF-8")
            require(key !in result);result[key]=URLDecoder.decode(p.getOrElse(1){""},"UTF-8")
        }
        return result
    }
    private fun decoded(raw: String): String {
        require(raw.length in 1..16384)
        return String(Base64.getDecoder().decode(raw.replace('-','+').replace('_','/')),Charsets.UTF_8)
    }
    /** Validate the delivered target while preserving the publisher's signed processing route. */
    fun resource(raw: String,watch: String): Resource? = runCatching {
        require(raw.isNotBlank())
        val outer=publicHttps(URI(BASE).resolve(raw).toASCIIString()) ?: error("https")
        if(outer.host=="dramacix.com") {
            require(outer.path in setOf("/live.php","/live.m3u8")) { "not a publisher media route" }
            val q=parameters(outer)
            val target=publicHttps(decoded(q.getValue("url"))) ?: error("publisher")
            val targetHost=target.host.lowercase().trimEnd('.')
            require(targetHost!="dramacix.com" && !targetHost.endsWith(".dramacix.com"))
            q["ref"]?.let { require(publicHttps(decoded(it))!=null) { "referer" } }
            // The publisher's signed route may process the playlist/segments; do not bypass it.
            Resource(outer.toASCIIString(),watch,target.path,target.toASCIIString())
        } else {
            require(!outer.host.endsWith(".dramacix.com"))
            Resource(outer.toASCIIString(),watch)
        }
    }.getOrNull()
    fun playback(raw: String,c: Chapter): Playback {
        val j=JSONObject(raw)
        require(listOf("dogrulama","alternatif").all { j.isNull(it) || j.opt(it)==false }) { "access or edition" }
        val video=resource(j.getString("url"),c.url) ?: error("media")
        val subs=records(j.optJSONArray("subs")).mapNotNull { s ->
            val language=s.optString("lang").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val iso=language.lowercase().replace('_','-')
            val normalized=if(iso=="tr" || iso.startsWith("tr-"))"tr"else language
            val r=resource(s.optString("url"),c.url) ?: return@mapNotNull null
            val declared=s.optString("format").trim().lowercase()
            val named=r.contentPath.lowercase().let { it.endsWith(".srt") || it.endsWith(".vtt") }
            if(!named && declared !in setOf("srt","vtt","webvtt"))return@mapNotNull null
            Caption(r,normalized)
        }.distinctBy { it.resource.url }.take(64)
        return Playback(video,subs)
    }
}
