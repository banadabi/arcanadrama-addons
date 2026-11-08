package dev.arcana.dramadizilerim

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Normal publisher SSR/Flight and playback records, without executing JavaScript or minting tickets. */
internal object DramaDizilerimContract {
    const val BASE="https://dramadizilerim.com"
    private val slugPattern=Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,199}")
    data class Card(val title:String,val url:String,val poster:String?,val count:Int?,val year:Int?)
    data class Listing(val cards:List<Card>,val more:Boolean)
    data class Chapter(val slug:String,val season:Int,val number:Int,val url:String)
    data class Series(val title:String,val url:String,val poster:String?,val plot:String?,val year:Int?,val score:Double?,val tags:List<String>,val chapters:List<Chapter>)
    data class Watch(val episodeId:String,val titleId:String) { override fun toString()="Watch(identities=[publisher])" }
    data class Caption(val language:String,val url:String) { override fun toString()="Caption(language=$language,url=[redacted])" }
    data class Link(val url:String,val hls:Boolean?,val compatible:Boolean) { override fun toString()="Link(url=[redacted],compatible=$compatible)" }
    data class Playback(val links:List<Link>,val captions:List<Caption>) { override fun toString()="Playback(links=[redacted])" }

    fun https(raw:String):String?=runCatching {
        val u=URI(raw);require(u.scheme.equals("https",true) && !u.host.isNullOrBlank() && u.rawUserInfo==null && u.port in setOf(-1,443) && u.rawFragment==null)
        require(!u.host.contains(':') && u.host.lowercase().trimEnd('.')!="localhost")
        raw
    }.getOrNull()
    fun own(raw:String):String?=https(raw)?.takeIf { URI(it).host.lowercase() in setOf("dramadizilerim.com","www.dramadizilerim.com") }
    fun absolute(raw:String,base:String=BASE+"/")=runCatching { URI(base).resolve(raw.trim()).toASCIIString() }.getOrNull()?.let(::https)
    private fun query(u:URI):Map<String,String> {
        val parts=u.rawQuery?.split('&') ?: return emptyMap()
        val pairs=parts.map { it.split('=',limit=2) }
        require(pairs.all { it.size==2 })
        val decoded=pairs.map { URLDecoder.decode(it[0],"UTF-8") to URLDecoder.decode(it[1],"UTF-8") }
        require(decoded.map { it.first }.distinct().size==decoded.size)
        return decoded.toMap()
    }
    fun detailSlug(raw:String):String?=own(raw)?.let {
        val u=URI(it);if(u.rawQuery!=null)return@let null
        val slug=u.path.trimEnd('/').removePrefix("/dizi/")
        slug.takeIf { u.path.trimEnd('/')=="/dizi/$it" && slugPattern.matches(it) }
    }
    fun chapter(raw:String,expected:String?=null):Chapter?=runCatching {
        val url=own(raw) ?: error("origin");val u=URI(url)
        val slug=u.path.removePrefix("/izle/");require(u.path=="/izle/$slug" && slugPattern.matches(slug) && (expected==null || slug==expected))
        val q=query(u);require(q.isEmpty() || q.keys==setOf("s","e"))
        val s=if(q.isEmpty())1 else q.getValue("s").toInt()
        val n=if(q.isEmpty())1 else q.getValue("e").toInt()
        require(s>0 && n>0)
        Chapter(slug,s,n,url)
    }.getOrNull()
    fun encode(c:Chapter)=JSONObject().put("v",1).put("kind","dramadizilerim_episode").put("url",c.url).toString()
    fun decode(raw:String):Chapter?=runCatching {
        val j=JSONObject(raw);require(j.optInt("v")==1 && j.optString("kind")=="dramadizilerim_episode")
        chapter(j.getString("url")) ?: error("chapter")
    }.getOrNull()
    private fun poster(value:String?)=value?.takeIf(String::isNotBlank)?.let { absolute(it) }
    private fun count(value:String)=Regex("""(\d+)\s*bölüm""",RegexOption.IGNORE_CASE).findAll(value).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    private fun card(node:Element):Card? {
        val a=if(node.tagName()=="a" && detailSlug(absolute(node.attr("href")).orEmpty())!=null)node
            else node.select("a[href]").firstOrNull { detailSlug(absolute(it.attr("href")).orEmpty())!=null } ?: return null
        if(a.attr("data-honeypot")=="1" || a.attr("aria-hidden")=="true")return null
        val url=absolute(a.attr("href")) ?: return null
        val img=node.selectFirst("img")
        val title=node.selectFirst(".card-caption h3,.card-caption h2,h3,h2")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: img?.attr("alt")?.trim()?.takeIf(String::isNotBlank) ?: return null
        val meta=node.select(".card-caption p,.card-meta,.card-stats").text().ifBlank { node.select(".card-caption").text() }
        return Card(title,url,poster(img?.attr("data-src")?.takeIf(String::isNotBlank) ?: img?.attr("src")),count(meta),null)
    }
    private fun merge(items:List<Card>)=items.groupBy { it.url }.values.map { xs->
        xs.reduce { a,b -> a.copy(poster=a.poster ?: b.poster,count=a.count ?: b.count,year=a.year ?: b.year) }
    }
    fun home(html:String)=merge(Jsoup.parse(html,BASE).select("article.title-card,.showcase-card,.studio-rank").mapNotNull(::card))
    fun listing(html:String,page:Int):Listing {
        require(page>0);val doc=Jsoup.parse(html,BASE)
        val cards=merge(doc.select("article.title-card").mapNotNull(::card))
        val more=doc.select("a[href]").any { a->
            val raw=absolute(a.attr("href")) ?: return@any false
            if(own(raw)==null)return@any false
            val u=URI(raw);if(u.path!="/diziler")return@any false
            runCatching { query(u)["page"]?.toIntOrNull()==page+1 }.getOrDefault(false)
        }
        return Listing(cards,more)
    }
    fun search(html:String,query:String):List<Card> {
        val doc=Jsoup.parse(html,BASE)
        require(doc.select("input[name=q]").any { it.attr("value")==query })
        return listing(html,1).cards
    }
    fun quickSearch(raw:String):List<Card> {
        val j=JSONObject(raw);val items=j.getJSONArray("items")
        return merge((0 until items.length()).mapNotNull { i->
            val x=items.optJSONObject(i) ?: return@mapNotNull null
            val slug=x.optString("slug");if(!slugPattern.matches(slug))return@mapNotNull null
            val name=x.optString("title").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            Card(name,"$BASE/dizi/$slug",poster(x.optString("poster")),x.optInt("episodeCount").takeIf { it>0 },x.optInt("year").takeIf { it in 1900..2100 })
        })
    }
    private fun objects(value:Any?):List<JSONObject> = when(value) {
        is JSONObject -> listOf(value)+objects(value.opt("@graph"))
        is JSONArray -> (0 until value.length()).flatMap { objects(value.opt(it)) }
        else -> emptyList()
    }
    fun series(html:String,slug:String):Series {
        require(slugPattern.matches(slug));val doc=Jsoup.parse(html,BASE)
        val all=doc.select("script[type=application/ld+json]").flatMap { runCatching { objects(JSONTokener(it.data()).nextValue()) }.getOrDefault(emptyList()) }
        val candidates=all.filter { it.optString("@type")=="TVSeries" && detailSlug(it.optString("url"))==slug }
        require(candidates.size==1);val j=candidates.single()
        val canonical="$BASE/dizi/$slug";val advertised=doc.selectFirst("link[rel=canonical]")?.attr("href") ?: doc.selectFirst("meta[property=og:url]")?.attr("content")
        if(!advertised.isNullOrBlank())require(detailSlug(advertised)==slug)
        val title=doc.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank) ?: j.getString("name").trim()
        require(title.isNotBlank())
        val facts=doc.select(".series-infobox-row").associate { row->row.selectFirst(".series-infobox-label")?.text().orEmpty().trim() to row.selectFirst(".series-infobox-val")?.text().orEmpty().trim() }
        val plot=(doc.selectFirst(".series-synopsis p") ?: doc.selectFirst(".series-synopsis"))?.text()?.trim()?.takeIf(String::isNotBlank) ?: j.optString("description").trim().takeIf(String::isNotBlank)
        val year=Regex("""^(19|20)\d{2}""").find(j.optString("startDate"))?.value?.toIntOrNull()
        val rating=j.optJSONObject("aggregateRating");val best=rating?.optDouble("bestRating",10.0)?.takeIf { it.isFinite() && it>0 }
        val score=rating?.optDouble("ratingValue",Double.NaN)?.takeIf { best!=null && it.isFinite() && it in 0.0..best }?.let { it/best!!*10 }
        val tags=when(val g=j.opt("genre")) { is JSONArray->(0 until g.length()).map { g.optString(it).trim() };is String->listOf(g.trim());else->emptyList() }
        val platform=facts["Platform"]?.removeSuffix(" Dizileri")
        val chapters=doc.select("a.series-episode-link[href]").mapNotNull { a->
            val c=chapter(absolute(a.attr("href")).orEmpty(),slug) ?: return@mapNotNull null
            val label=Regex("""\d+""").findAll(a.attr("aria-label")).map { it.value.toInt() }.toList()
            require(label==listOf(c.season,c.number))
            c
        }.distinctBy { it.season to it.number }.sortedWith(compareBy({it.season},{it.number}))
        require(chapters.isNotEmpty())
        val image=j.opt("image").let { if(it is JSONObject)it.optString("url")else it as? String }
        return Series(title,canonical,poster(image ?: doc.selectFirst("meta[property=og:image]")?.attr("content")),plot,year,score,(tags+listOfNotNull(platform)).filter(String::isNotBlank).distinct(),chapters)
    }
    /** Read only JSON arguments and Flight records; no source eval/JavaScript interpreter. */
    private fun flight(html:String):Map<String,Any?> {
        val doc=Jsoup.parse(html,BASE);val text=StringBuilder()
        val marker=Regex("""self\.__next_f\.push\(""")
        for(script in doc.select("script:not([src])")) {
            val source=script.data()
            for(m in marker.findAll(source)) {
                val a=runCatching { JSONTokener(source.substring(m.range.last+1)).nextValue() as? JSONArray }.getOrNull() ?: continue
                if(a.length()>1 && a.opt(1) is String)text.append(a.getString(1))
            }
        }
        require(text.length<=3_000_000)
        val records=linkedMapOf<String,Any?>()
        for(line in text.lineSequence()) {
            val sep=line.indexOf(':');if(sep<=0)continue
            val id=line.substring(0,sep);if(!Regex("[0-9a-z]+").matches(id))continue
            val raw=line.substring(sep+1)
            // Flight control/debug frames share IDs with each other and are not JSON models.
            // JSONTokener also accepts bare unquoted strings (e.g. X/C), so gate its input.
            val jsonModel=raw.firstOrNull() in setOf('{','[','"') || raw in setOf("null","true","false") ||
                Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""").matches(raw)
            if(!jsonModel)continue
            val v=runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: continue
            require(id !in records || records[id].toString()==v.toString())
            records[id]=v
        }
        return records
    }
    fun watch(html:String,c:Chapter):Watch {
        val doc=Jsoup.parse(html,BASE)
        // Later episodes use the series detail URL for SEO canonical and the selected
        // watch URL for og:url. Validate both identities at their own level.
        for(node in doc.select("link[rel=canonical],meta[property=og:url]")) {
            val advertised=if(node.hasAttr("href"))node.attr("href")else node.attr("content")
            if(advertised.isBlank())continue
            val seriesSlug=detailSlug(advertised)
            if(seriesSlug!=null) {
                require(seriesSlug==c.slug)
            } else {
                val declared=chapter(advertised) ?: error("watch canonical")
                require(declared.slug==c.slug)
                if(URI(advertised).rawQuery!=null)require(declared.season==c.season && declared.number==c.number)
            }
        }
        val records=flight(html)
        fun resolve(raw:Any?):Any? {
            var v=raw;val seen=mutableSetOf<String>()
            repeat(8) {
                val key=(v as? String)?.let { Regex("""^\$([0-9a-z]+)$""").matchEntire(it)?.groupValues?.get(1) }
                if(key!=null && key in records && seen.add(key))v=records[key]else return v
            }
            error("Flight reference depth")
        }
        val found=mutableListOf<Watch>()
        fun visit(raw:Any?,depth:Int,seen:Set<String>) {
            require(depth<80)
            val reference=(raw as? String)?.takeIf { it.startsWith("$") }
            if(reference!=null && reference in seen)return
            val v=resolve(raw)
            when(v) {
                is JSONObject -> {
                    if(v.has("episodeId") && v.has("titleSlug") && v.has("episodeLabel") && resolve(v.opt("titleSlug"))==c.slug) {
                        val label=Regex("""\d+""").findAll(resolve(v.opt("episodeLabel")).toString()).map { it.value.toInt() }.toList()
                        if(label==listOf(c.season,c.number)) {
                            val id=resolve(v.opt("episodeId")) as? String ?: error("episode id")
                            val titleId=resolve(v.opt("titleId")) as? String ?: error("title id")
                            require(Regex("[A-Za-z0-9][A-Za-z0-9_:-]{0,159}").matches(id) && Regex("[A-Za-z0-9][A-Za-z0-9_:-]{0,159}").matches(titleId))
                            found.add(Watch(id,titleId))
                        }
                    }
                    v.keys().forEach { key->if(key !in setOf("user","session","auth","csrf","tokens"))visit(v.opt(key),depth+1,seen+listOfNotNull(reference)) }
                }
                is JSONArray -> (0 until v.length()).forEach { visit(v.opt(it),depth+1,seen+listOfNotNull(reference)) }
            }
        }
        records.values.forEach { visit(it,0,emptySet()) }
        val unique=found.distinct();require(unique.size==1)
        return unique.single()
    }
    private fun media(raw:String,page:String):String {
        val url=absolute(raw,page) ?: error("media URL")
        if(own(url)!=null && URI(url).path=="/api/media")require(query(URI(url))["ticket"]?.isNotBlank()==true)
        return url
    }
    fun playback(raw:String,c:Chapter):Playback {
        val j=JSONObject(raw);require(j.optBoolean("success",false))
        val kind=j.getString("kind");require(kind in setOf("hls","mp4"))
        val links=mutableListOf(Link(media(j.getString("url"),c.url),kind=="hls",false))
        j.optString("compatibilityUrl").takeIf(String::isNotBlank)?.let { links.add(Link(media(it,c.url),null,true)) }
        val captions=j.optJSONArray("captions")?.let { a->(0 until a.length()).mapNotNull { i->
            val x=a.optJSONObject(i) ?: return@mapNotNull null
            val language=x.optString("language").lowercase().replace('_','-')
            if(!Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*").matches(language))return@mapNotNull null
            val url=absolute(x.optString("url"),c.url) ?: return@mapNotNull null
            if(URI(url).path.lowercase().let { it.contains("/translate") || it.contains("/generate") })return@mapNotNull null
            Caption(if(language=="tr" || language.startsWith("tr-"))"tr"else language,url)
        } }.orEmpty().distinctBy { it.language to it.url }
        return Playback(links.distinctBy { it.url },captions)
    }
    fun terminalMediaStatus(code:Int)=code in setOf(401,403,429)
    fun retryUntil(value:String?,now:Long):Long {
        val raw=value?.trim();val seconds=raw?.toLongOrNull()
        val target=if(seconds!=null && seconds>=0) {
            if(seconds>(Long.MAX_VALUE-now)/1000)Long.MAX_VALUE else now+seconds*1000
        } else runCatching { ZonedDateTime.parse(raw,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0)
        return maxOf(now+60_000,target)
    }
}
