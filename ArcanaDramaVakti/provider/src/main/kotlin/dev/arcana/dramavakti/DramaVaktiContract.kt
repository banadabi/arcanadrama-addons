package dev.arcana.dramavakti

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Only publisher-delivered identities and media; no protected URL creation. */
internal object DramaVaktiContract {
    const val BASE="https://dramavakti.com"
    private val slugPattern=Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,199}")
    data class Card(val title:String,val url:String,val poster:String?,val count:Int?,val year:Int?)
    data class Listing(val cards:List<Card>,val more:Boolean)
    data class Chapter(val slug:String,val number:Int,val url:String)
    data class Series(val title:String,val url:String,val poster:String?,val plot:String?,val year:Int?,val score:Double?,val tags:List<String>,val chapters:List<Chapter>)
    data class Caption(val language:String,val url:String) { override fun toString()="Caption(language=$language,url=[redacted])" }
    data class Playback(val url:String,val hls:Boolean?,val captions:List<Caption>) { override fun toString()="Playback(url=[redacted])" }
    fun https(raw:String):String?=runCatching {
        val u=URI(raw);require(u.scheme.equals("https",true) && !u.host.isNullOrBlank() && u.rawUserInfo==null && u.port in setOf(-1,443))
        require(!u.host.contains(':') && u.host.lowercase().trimEnd('.')!="localhost")
        raw
    }.getOrNull()
    fun own(raw:String):String?=https(raw)?.takeIf { URI(it).host.equals("dramavakti.com",true) }
    private fun absolute(raw:String,base:String=BASE+"/")=runCatching { URI(base).resolve(raw).toString() }.getOrNull()?.let(::https)
    fun detailSlug(raw:String):String?=own(raw)?.let { value ->
        val u=URI(value);if(u.rawQuery!=null || u.rawFragment!=null)return@let null
        val slug=u.path.removePrefix("/dizi/")
        slug.takeIf { u.path=="/dizi/$it" && slugPattern.matches(it) }
    }
    fun chapter(raw:String,expected:String?=null):Chapter?=runCatching {
        val url=own(raw) ?: error("origin");val u=URI(url);require(u.rawQuery==null && u.rawFragment==null)
        val match=Regex("""/dizi/([A-Za-z0-9][A-Za-z0-9_-]{0,199})/bolum-(\d+)""").matchEntire(u.path) ?: error("chapter")
        val slug=match.groupValues[1];val number=match.groupValues[2].toInt()
        require(number>0 && (expected==null || slug==expected))
        Chapter(slug,number,url)
    }.getOrNull()
    fun encode(c:Chapter)=JSONObject().put("v",1).put("kind","dramavakti_episode").put("url",c.url).toString()
    fun decode(raw:String):Chapter?=runCatching {
        val j=JSONObject(raw);require(j.optInt("v")==1 && j.optString("kind")=="dramavakti_episode")
        chapter(j.getString("url")) ?: error("identity")
    }.getOrNull()
    private fun poster(raw:String?)=raw?.trim()?.takeIf(String::isNotBlank)?.let(::absolute)
    private fun count(text:String)=Regex("""(\d+)\s*bölüm""",RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
    private fun year(text:String)=Regex("""\b((?:19|20)\d{2})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    private fun card(a:Element):Card? {
        val url=absolute(a.attr("href")) ?: return null;if(detailSlug(url)==null)return null
        val name=a.selectFirst(".browse-copy strong, .home-poster-copy strong, h3, h2")?.text()?.trim()?.takeIf(String::isNotBlank) ?: return null
        val meta=a.selectFirst(".browse-copy small, .home-poster-copy small")?.text().orEmpty()
        val img=a.selectFirst("img")
        return Card(name,url,poster(img?.attr("data-src")?.takeIf(String::isNotBlank) ?: img?.attr("src")),count(meta),year(meta))
    }
    fun home(html:String)=Jsoup.parse(html,BASE).select("a.home-poster-card[href]").mapNotNull(::card).distinctBy { it.url }
    fun listing(html:String,currentPage:Int):Listing {
        require(currentPage>0)
        val doc=Jsoup.parse(html,BASE)
        val items=doc.select("a.browse-card[href]").mapNotNull(::card).distinctBy { it.url }
        val more=doc.select("a.pagination-arrow[href]:not(.disabled)").any {
            val u=runCatching { URI(BASE+"/").resolve(it.attr("href")) }.getOrNull() ?: return@any false
            if(own(u.toString())==null || !u.path.startsWith("/diziler"))return@any false
            val values=u.rawQuery.orEmpty().split('&').map { s->s.split('=',limit=2) }.filter { p->p[0]=="page" }
            values.size==1 && values[0].getOrNull(1)?.toIntOrNull()==currentPage+1
        }
        return Listing(items,more)
    }
    fun quickSearch(raw:String,query:String):List<Card> {
        val j=JSONObject(raw);require(j.optBoolean("ok",false) && j.getString("term")==query)
        val a=j.getJSONArray("items")
        return (0 until a.length()).mapNotNull { i ->
            val item=a.optJSONObject(i) ?: return@mapNotNull null
            val url=absolute(item.optString("url")) ?: return@mapNotNull null;if(detailSlug(url)==null)return@mapNotNull null
            val title=item.optString("title").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val meta=item.optString("meta")
            Card(title,url,poster(item.optString("poster")),count(meta),year(meta))
        }.distinctBy { it.url }
    }
    private fun objects(raw:Any?):List<JSONObject> = when(raw) {
        is JSONObject -> listOf(raw)+objects(raw.opt("@graph"))
        is JSONArray -> (0 until raw.length()).flatMap { objects(raw.opt(it)) }
        else -> emptyList()
    }
    fun series(html:String,slug:String):Series {
        require(slugPattern.matches(slug));val canonical="$BASE/dizi/$slug";val doc=Jsoup.parse(html,BASE)
        val advertised=doc.selectFirst("meta[property=og:url]")?.attr("content")?.trimEnd('/')
            ?: doc.selectFirst("link[rel=canonical]")?.attr("href")?.trimEnd('/')
        require(advertised==canonical)
        val all=doc.select("script[type=application/ld+json]").flatMap { runCatching { objects(JSONTokener(it.data()).nextValue()) }.getOrDefault(emptyList()) }
        val candidates=all.filter { it.optString("@type")=="TVSeries" };require(candidates.size==1)
        val j=candidates.single();require(j.optInt("numberOfSeasons",1)==1)
        val title=doc.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank) ?: j.getString("name").trim()
        require(title.isNotBlank())
        val facts=doc.select("dt").associate { it.text().trim() to it.nextElementSibling()?.takeIf { n->n.tagName()=="dd" }?.text().orEmpty() }
        val year=facts["Yıl"]?.toIntOrNull()?.takeIf { it in 1000..9999 }
        val plot=doc.selectFirst(".series-synopsis")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: j.optString("description").trim().takeIf(String::isNotBlank)
        val rating=j.optJSONObject("aggregateRating")
        val best=rating?.optDouble("bestRating",10.0)?.takeIf { it.isFinite() && it>0 }
        val value=rating?.optDouble("ratingValue",Double.NaN)
        val score=if(best!=null && value!=null && value.isFinite() && value in 0.0..best)value/best*10 else null
        val genres=j.opt("genre").let { v->when(v) {
            is JSONArray -> (0 until v.length()).map { v.optString(it).trim() }.filter(String::isNotBlank)
            is String -> listOf(v.trim()).filter(String::isNotBlank)
            else -> emptyList()
        }}
        val publisher=j.opt("productionCompany").let { v->if(v is JSONObject)v.optString("name")else v?.toString().orEmpty() }.trim()
        val tags=(genres+listOf(publisher).filter(String::isNotBlank)).distinct()
        val chapters=doc.select("a[href]").mapNotNull { absolute(it.attr("href"))?.let { u->chapter(u,slug) } }.distinctBy { it.number }.sortedBy { it.number }
        require(chapters.isNotEmpty())
        return Series(title,canonical,poster(doc.selectFirst("meta[property=og:image]")?.attr("content")),plot,year,score,tags,chapters)
    }
    fun playback(html:String,c:Chapter):Playback {
        val doc=Jsoup.parse(html,BASE)
        val main=doc.selectFirst("main[data-slug]") ?: error("watch identity")
        require(main.attr("data-slug")==c.slug)
        val sections=main.select("section[data-episode]").filter { it.attr("data-episode").toIntOrNull()==c.number }
        require(sections.size==1)
        val section=sections.single()
        val videos=section.select("video[data-hls-src], video[data-lazy-hls-src]")
        require(videos.size==1)
        val v=videos.single();val src=v.attr("data-hls-src").takeIf(String::isNotBlank) ?: v.attr("data-lazy-hls-src")
        require(src.isNotBlank())
        val url=absolute(src,c.url) ?: error("source unavailable")
        val kind=v.attr("data-playback-kind").takeIf(String::isNotBlank) ?: "hls"
        val hls=when(kind) { "hls"->true;"file"->false;"auto"->null;else->error("unknown transport") }
        val captions=v.select("track[kind=subtitles], track[kind=captions]").mapNotNull { t->
            val declared=t.attr("srclang").lowercase().replace('_','-')
            if(!Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*").matches(declared))return@mapNotNull null
            val lang=if(declared=="tr" || declared.startsWith("tr-"))"tr"else declared
            val raw=t.attr("src").takeIf(String::isNotBlank) ?: t.attr("data-src")
            val target=absolute(raw.takeIf(String::isNotBlank) ?: return@mapNotNull null,c.url) ?: return@mapNotNull null
            if(URI(target).path.lowercase().let { it.contains("altyazi.php") || it.contains("/translate") })return@mapNotNull null
            Caption(lang,target)
        }.distinctBy { it.language to it.url }
        return Playback(url,hls,captions)
    }
    fun retryUntil(value:String?,now:Long):Long {
        val raw=value?.trim();val seconds=raw?.toLongOrNull()
        val target=if(seconds!=null && seconds>=0) {
            if(seconds>(Long.MAX_VALUE-now)/1000)Long.MAX_VALUE else now+seconds*1000
        } else runCatching { ZonedDateTime.parse(raw,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0)
        return maxOf(now+60_000,target)
    }
}
