package dev.arcana.directdrama

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant

/** Published DirectDrama data only. IDs, ordinal gaps and source-issued URLs stay distinct. */
internal object DirectContract {
    const val NAME="(Arcana Drama) DirectDrama"
    const val BASE="https://directdrama.com"
    data class Card(val id:String,val slug:String,val title:String,val poster:String?,val count:Int?)
    data class Row(val name:String,val cards:List<Card>)
    data class Page(val cards:List<Card>,val hasNext:Boolean)
    data class Chapter(val series:String,val id:String,val slug:String,val number:Int) {
        val url get()=seriesUrl(slug)+"/bolum-$number"
    }
    data class Series(val title:String,val plot:String,val poster:String?,val tags:List<String>,val chapters:List<Chapter>)
    data class Caption(val language:String,val url:String) { override fun toString()="Caption([redacted])" }
    data class Playback(val video:String,val hls:Boolean,val captions:List<Caption>,val expiresAt:Long?) { override fun toString()="Playback([redacted])" }
    fun https(raw:String):String?=runCatching {
        require(raw.isNotBlank() && raw.length<=16000 && raw.none { it.code<0x20 || it.code==0x7f })
        val u=URI(BASE+"/").resolve(raw)
        require(u.scheme.equals("https",true) && !u.host.isNullOrBlank() && u.port in setOf(-1,443) && u.rawUserInfo==null && u.rawFragment==null)
        require(DirectUrlPolicy.readOnly(u));u.toASCIIString()
    }.getOrNull()
    fun own(raw:String):URI?=https(raw)?.let(::URI)?.takeIf { it.host.equals("directdrama.com",true) }
    private fun validSlug(s:String)=s.matches(Regex("[a-z0-9][a-z0-9-]{0,239}"))
    fun seriesUrl(slug:String):String { require(validSlug(slug));return "$BASE/tr/dizi/$slug" }
    fun detailSlug(raw:String):String?=own(raw)?.takeIf { it.rawQuery==null }?.path?.let {
        Regex("/tr/dizi/([a-z0-9-]+)/?").matchEntire(it)?.groupValues?.get(1)?.takeIf(::validSlug)
    }
    fun route(raw:String):Pair<String,Int>?=own(raw)?.takeIf { it.rawQuery==null }?.path?.let {
        val m=Regex("/tr/dizi/([a-z0-9-]+)/bolum-([1-9][0-9]{0,4})/?").matchEntire(it) ?: return@let null
        val n=m.groupValues[2].toIntOrNull()?.takeIf { it in 1..10000 } ?: return@let null
        m.groupValues[1].takeIf(::validSlug)?.let { s->s to n }
    }
    fun sameSeriesPage(raw:String,slug:String)=detailSlug(raw)==slug || route(raw)?.first==slug
    private fun id(v:Any?):String {
        val s=when(v){is Int,is Long->v.toString();else->error("Publisher integer ID required")}
        require(s.matches(Regex("[1-9][0-9]{0,15}")) && s.toLong()<=9_007_199_254_740_991L);return s
    }
    private fun savedId(v:Any?):String=(v as? String)?.also { require(it.matches(Regex("[1-9][0-9]{0,15}")) && it.toLong()<=9_007_199_254_740_991L) } ?: error("Episode ID required")
    private fun integer(v:Any?,minimum:Int=0):Int?=when(v){is Int->v.takeIf { it in minimum..10000 };is Long->v.takeIf { it in minimum.toLong()..10000L }?.toInt();else->null}
    private fun text(j:JSONObject,k:String)=(j.opt(k) as? String).orEmpty().trim()
    private fun poster(key:String):String?=key.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,200}")) }?.let { "$BASE/img/covers/$it/720.webp" }
    private fun card(j:JSONObject):Card?=runCatching {
        val slug=text(j,"slug");require(validSlug(slug));val title=text(j,"title");require(title.isNotEmpty() && title.length<=2048)
        Card(id(j.opt("id")),slug,title,poster(text(j,"coverKey")),integer(j.opt("episodes")))
    }.getOrNull()
    private fun cards(a:JSONArray?):List<Card> =(0 until minOf(a?.length() ?: 0,1000)).mapNotNull { a?.optJSONObject(it)?.let(::card) }.distinctBy { it.slug }
    fun home(html:String):List<Row> {
        val rows=FlightData.objects(html).filter { it.optJSONArray("cards")!=null && it.opt("title") is String && it.opt("id") is String }
            .mapNotNull { j->val items=cards(j.optJSONArray("cards"));val name=text(j,"title");if(items.isEmpty() || name.isBlank())null else Row(name,items) }
            .distinctBy { it.name }.take(30)
        require(rows.isNotEmpty());return rows
    }
    fun searchPage(raw:String,expectedPage:Int):Page {
        val j=JSONObject(raw);require(j.opt("page")==expectedPage && j.opt("hasMore") is Boolean)
        return Page(cards(j.getJSONArray("items")),j.getBoolean("hasMore"))
    }
    fun firstWatch(html:String,slug:String):String {
        val d=Jsoup.parse(html,seriesUrl(slug))
        val links=d.select("a[href]").mapNotNull { a->route(a.absUrl("href")) }.filter { it.first==slug }.map { it.second }.distinct().sorted()
        require(links.isNotEmpty());return seriesUrl(slug)+"/bolum-"+links.first()
    }
    private fun bound(html:String,slug:String):JSONObject {
        val values=FlightData.objects(html).filter { it.optJSONObject("series")?.optString("slug")==slug && it.optJSONArray("episodes")!=null }
        require(values.size==1);return values.single()
    }
    private fun chapters(j:JSONObject,slug:String):List<Chapter> {
        val s=j.getJSONObject("series");require(s.getString("slug")==slug);val sid=id(s.opt("id"));val a=j.getJSONArray("episodes")
        require(a.length() in 1..10000)
        val result=(0 until a.length()).map { i->val e=a.getJSONObject(i);Chapter(sid,id(e.opt("id")),slug,integer(e.opt("number"),1) ?: error("Ordinal required")) }
        require(result.map { it.id }.distinct().size==result.size && result.map { it.number }.distinct().size==result.size)
        // Never fill missing ordinals from a card count or the maximum episode number.
        return result.sortedBy { it.number }
    }
    fun series(detail:String,watch:String,slug:String):Series {
        val b=bound(watch,slug);val source=b.getJSONObject("series");val chapterList=chapters(b,slug)
        val d=Jsoup.parse(detail,seriesUrl(slug));val nodes=mutableListOf<JSONObject>()
        fun walk(v:Any?,depth:Int=0) { require(depth<=20);when(v){is JSONObject->{nodes.add(v);v.opt("@graph")?.let { walk(it,depth+1) }};is JSONArray->for(i in 0 until minOf(v.length(),1000))walk(v.opt(i),depth+1)} }
        d.select("script[type=application/ld+json]").forEach { runCatching { JSONTokener(it.data()).nextValue() }.getOrNull()?.let { v->walk(v) } }
        val ld=nodes.single { it.optString("@type")=="TVSeries" && detailSlug(it.optString("url"))==slug }
        val title=text(ld,"name").ifBlank { text(source,"title") };require(title.isNotBlank())
        val image=when(val v=ld.opt("image")){is String->https(v);is JSONObject->https(text(v,"url").ifBlank { text(v,"contentUrl") });else->null} ?: poster(text(source,"coverKey"))
        val facts=d.selectFirst("#facts")
        val plot=facts?.select("p")?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.joinToString("\n\n").orEmpty()
        val tags=d.select("section[aria-labelledby=series-title] a[href], #facts a[href]")
            .filter { a->own(a.absUrl("href"))?.path?.let { it.startsWith("/tr/tur/") || it.startsWith("/tr/platform/") }==true }
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        return Series(title,plot,image,tags,chapterList)
    }
    fun encode(c:Chapter)=JSONObject().put("v",1).put("series",c.series).put("id",c.id).put("slug",c.slug).put("episode",c.number).toString()
    fun decode(raw:String):Chapter?=runCatching {
        require(raw.length<=2048);val j=JSONObject(raw);require(j.opt("v")==1)
        val s=j.getString("slug");require(validSlug(s));Chapter(savedId(j.opt("series")),savedId(j.opt("id")),s,integer(j.opt("episode"),1) ?: error("Ordinal required"))
    }.getOrNull()
    fun verifyWatch(html:String,c:Chapter) {
        require(chapters(bound(html,c.slug),c.slug).any { it==c })
        require(FlightData.objects(html).any { runCatching { id(it.opt("seriesId"))==c.series && id(it.opt("episodeId"))==c.id }.getOrDefault(false) })
    }
    fun captionURL(raw:String)=https(raw)
    fun playback(j:JSONObject,c:Chapter):Playback {
        require(id(j.opt("episodeId"))==c.id);val type=text(j,"type");require(type in setOf("hls","mp4"))
        val video=https(text(j,"url")) ?: error("Publisher media required")
        val a=j.optJSONArray("subtitles") ?: JSONArray()
        val captions=(0 until minOf(a.length(),32)).mapNotNull { i->
            val v=a.optJSONObject(i) ?: return@mapNotNull null
            val url=captionURL(text(v,"url")) ?: return@mapNotNull null
            val lang=text(v,"lang").lowercase().replace('_','-').let { if(it=="tur" || it=="tr" || it.startsWith("tr-"))"tr"else it }
            if(!lang.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})?")))return@mapNotNull null
            Caption(lang,url)
        }.distinctBy { it.url }.sortedBy { if(it.language=="tr")0 else 1 }
        val expiry=text(j,"expiresAt").takeIf { it.isNotEmpty() }?.let { Instant.parse(it).toEpochMilli() }
        return Playback(video,type=="hls",captions,expiry)
    }
    fun deniedHtml(raw:String):Boolean {
        val d=Jsoup.parse(raw)
        return Regex("Access Denied|Attention Required|Just a moment|verify you are human|captcha|checking your browser",RegexOption.IGNORE_CASE).containsMatchIn(d.title()) ||
            d.select("a[href*=/tr/dizi/]").isEmpty() && Regex("you have been blocked|verify you are human|complete the captcha",RegexOption.IGNORE_CASE).containsMatchIn(d.body().text().take(500))
    }
    fun timedText(text:String):Boolean {
        val t=text.removePrefix("\uFEFF").replace("\r\n","\n").trimStart();if(t.startsWith("<"))return false
        val cue=Regex("""(?m)^(?:\d{2,}:)?\d{2}:\d{2}[.,]\d{3}\s*-->\s*(?:\d{2,}:)?\d{2}:\d{2}[.,]\d{3}[^\n]*\n([^\n]+)""")
        return cue.findAll(t).any { it.groupValues[1].replace(Regex("<[^>]*>"),"").any(Char::isLetter) }
    }
    internal fun preparingDelay(header:String?,now:Long):Long {
        if(header.isNullOrBlank())return 3000L
        val seconds=header.trim().toLongOrNull()?.takeIf { it>=0 }
        if(seconds!=null)return if(seconds>Long.MAX_VALUE/1000)Long.MAX_VALUE else maxOf(3000L,seconds*1000)
        return runCatching {
            val target=java.time.ZonedDateTime.parse(header,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            maxOf(3000L,Math.subtractExact(target,now))
        }.getOrDefault(Long.MAX_VALUE)
    }
    internal fun retryUntil(header:String?,now:Long):Long {
        val seconds=header?.trim()?.toLongOrNull()?.takeIf { it>=0 }
        if(seconds!=null)return if(seconds>(Long.MAX_VALUE-now)/1000)Long.MAX_VALUE else now+seconds*1000
        return runCatching { java.time.ZonedDateTime.parse(header,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()?.takeIf { it>now } ?: (now+1_800_000)
    }

}
