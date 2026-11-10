package dev.arcana.anyshort

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.OffsetDateTime

/** Source integer identities and ordinal gaps are preserved; signed URLs never enter Episode.data. */
internal object AnyContract {
    const val BASE="https://anyshort.net"
    const val NAME="(Arcana Drama) AnyShort"
    data class Title(val id:String,val slug:String) { val url get()="$BASE/tr/film/$slug-$id" }
    data class Card(val identity:Title,val name:String,val poster:String?,val count:Int?)
    data class Row(val name:String,val cards:List<Card>)
    data class Page(val cards:List<Card>,val next:String?)
    data class Chapter(val title:Title,val id:String,val number:Int) { val url get()=title.url+"/watch?ep=$number" }
    data class Detail(val card:Card,val plot:String,val tags:List<String>)
    data class EpisodePage(val chapters:List<Chapter>,val hasMore:Boolean,val received:Int)
    data class Stream(val url:String,val hls:Boolean,val quality:Int,val expiresAt:Long) { override fun toString()="Stream([redacted])" }
    data class Caption(val language:String,val url:String,val expiresAt:Long) { override fun toString()="Caption([redacted])" }
    fun text(j:JSONObject,k:String)=(j.opt(k) as? String).orEmpty().trim()
    fun id(v:Any?):String {
        require(v is Int || v is Long)
        return v.toString().also { require(it.matches(Regex("[1-9][0-9]{0,15}")) && it.toLong()<=9_007_199_254_740_991L) }
    }
    private fun savedId(v:Any?):String=(v as? String)?.also { require(it.matches(Regex("[1-9][0-9]{0,15}")) && it.toLong()<=9_007_199_254_740_991L) } ?: error("Missing episode identity")
    private fun slug(v:String)=v.also { require(it.matches(Regex("[a-z0-9][a-z0-9-]{0,239}"))) }
    private fun number(v:Any?):Int?=when(v){is Int->v;is Long->v.takeIf { it in 0..10000L }?.toInt();else->null}?.takeIf { it in 0..10000 }
    fun https(raw:String):String?=runCatching {
        require(raw.isNotEmpty() && raw.length<=16000 && raw.none { it.code<32 || it.code==127 || it=='\\' })
        val u=URI(BASE+"/").resolve(raw)
        require(u.scheme=="https" && !u.host.isNullOrBlank() && u.port in setOf(-1,443) && u.rawUserInfo==null && u.rawFragment==null)
        require(AnyUrlPolicy.readOnly(u));u.toASCIIString()
    }.getOrNull()
    fun mediaUrl(raw:String):String?=https(raw)?.takeIf { value->val u=URI(value)
        !(u.host.equals("anyshort.net",true) && (u.path=="/v1" || u.path.startsWith("/v1/") || u.path.startsWith("/.arcana-anyshort/")))
    }
    fun identity(raw:String):Title?=runCatching {
        val u=URI(raw);require(u.scheme=="https" && u.host=="anyshort.net" && u.port in setOf(-1,443) && u.rawUserInfo==null && u.rawQuery==null && u.rawFragment==null)
        val m=Regex("/tr/film/([a-z0-9-]+)-([1-9][0-9]{0,15})/?").matchEntire(u.rawPath) ?: error("Not a detail route")
        Title(savedId(m.groupValues[2]),slug(m.groupValues[1]))
    }.getOrNull()
    fun card(j:JSONObject):Card {
        val ident=Title(id(j.opt("title_id")),slug(text(j,"slug")))
        val name=text(j,"name");require(name.isNotEmpty() && name.length<=2048)
        return Card(ident,name,https(text(j,"cover_url")),number(j.opt("episode_count")) ?: number(j.opt("total_episodes")))
    }
    fun cards(a:JSONArray):List<Card> {
        require(a.length()<=1000)
        return (0 until a.length()).map { card(a.getJSONObject(it)) }.distinctBy { it.identity.id }
    }
    fun cursor(v:Any?):String? {
        if(v==null || v==JSONObject.NULL || v=="")return null
        require(v is String && v.length<=4096 && v.none { it.code<32 || it.code==127 });return v
    }
    fun page(j:JSONObject):Page=Page(cards(j.getJSONArray("data")),cursor(j.optJSONObject("page")?.opt("next")))
    fun home(j:JSONObject):List<Row> {
        val d=j.getJSONObject("data");val result=mutableListOf<Row>()
        d.optJSONObject("spotlight")?.optJSONArray("items")?.let { result.add(Row("Sizin için önerilenler",cards(it))) }
        val a=d.getJSONArray("rails");require(a.length()<=50)
        val labels=mapOf("rail_newest" to "Son Güncellenenler","rail_trending" to "Trend Olanlar","rail_most_viewed" to "En Çok İzlenenler","rail_completed" to "Tamamlananlar")
        for(i in 0 until a.length()) {
            val r=a.getJSONObject(i)
            val label=labels[text(r,"title_key")] ?: if(text(r,"kind")=="auto")when(text(r,"ref")){"newest"->"Son Güncellenenler";"popular"->"Popüler";else->text(r,"title")}else text(r,"title")
            if(label.isNotBlank())result.add(Row(label,cards(r.getJSONArray("items"))))
        }
        return result.filter { it.cards.isNotEmpty() }.also { require(it.isNotEmpty()) }
    }
    fun detail(j:JSONObject,expected:Title):Detail {
        val d=j.getJSONObject("data");val card=card(d);require(card.identity==expected)
        val a=d.optJSONArray("tags") ?: JSONArray()
        val tags=(0 until minOf(a.length(),100)).mapNotNull { a.optJSONObject(it)?.let { v->text(v,"name").takeIf(String::isNotBlank) } }.distinct()
        return Detail(card,text(d,"description"),tags)
    }
    fun episodes(j:JSONObject,title:Title,offset:Int):EpisodePage {
        val a=j.getJSONArray("data");require(a.length()<=60)
        val p=j.getJSONObject("page");require(number(p.opt("offset"))==offset && p.opt("has_more") is Boolean)
        val records=(0 until a.length()).map { i->val e=a.getJSONObject(i)
            val eid=id(e.opt("episode_id"));val n=number(e.opt("episode_no")) ?: error("Missing ordinal")
            require(e.opt("locked") is Boolean && e.opt("has_streams") is Boolean)
            Triple(Chapter(title,eid,n),e.getBoolean("locked"),e.getBoolean("has_streams"))
        }
        require(records.map { it.first.id }.distinct().size==records.size && records.map { it.first.number }.distinct().size==records.size)
        return EpisodePage(records.filter { it.first.number>0 && !it.second && it.third }.map { it.first },p.getBoolean("has_more"),a.length())
    }
    fun sourceQuery(data:String):String {
        require(data.length<=2000)
        val j=JSONObject(data);val q=j.opt("q") as? String ?: error("Source query required")
        require(q.isNotBlank() && q.length<=300);return q
    }
    fun encode(c:Chapter)=JSONObject().put("v",1).put("title",c.title.id).put("slug",c.title.slug).put("episode",c.id).put("number",c.number).toString()
    fun decode(raw:String):Chapter?=runCatching {
        require(raw.length<=2048);val j=JSONObject(raw);require(j.opt("v")==1)
        Chapter(Title(savedId(j.opt("title")),slug(j.getString("slug"))),savedId(j.opt("episode")),number(j.opt("number"))?.takeIf { it>0 } ?: error("Missing ordinal"))
    }.getOrNull()
    private fun expiry(j:JSONObject,now:Long):Long {
        require(j.opt("stale")==false)
        return OffsetDateTime.parse(text(j,"expire_at")).toInstant().toEpochMilli().also { require(it>now+60_000) }
    }
    fun playback(j:JSONObject,c:Chapter,now:Long):List<Stream> {
        val d=j.getJSONObject("data")
        require(id(d.opt("title_id"))==c.title.id && id(d.opt("episode_id"))==c.id && number(d.opt("episode_no"))==c.number)
        require(text(d,"access")=="free")
        val a=d.getJSONArray("streams");require(a.length() in 1..32)
        return (0 until a.length()).mapNotNull { i->runCatching {
            val s=a.getJSONObject(i);val protocol=text(s,"protocol").lowercase();require(protocol in setOf("mp4","hls"))
            require(text(s,"codec").lowercase() in setOf("h264","avc","avc1"))
            val quality=Regex("([0-9]{3,4})p").matchEntire(text(s,"quality"))?.groupValues?.get(1)?.toInt() ?: 0
            Stream(mediaUrl(text(s,"url")) ?: error("Missing publisher media"),protocol=="hls",quality,expiry(s,now))
        }.getOrNull() }.distinctBy { it.url }.sortedByDescending { it.quality }.also { require(it.isNotEmpty()) }
    }
    fun subtitles(j:JSONObject,now:Long):List<Caption> {
        val a=j.getJSONArray("data");require(a.length()<=32)
        return (0 until a.length()).mapNotNull { i->runCatching {
            val s=a.getJSONObject(i);require(text(s,"format").lowercase() in setOf("webvtt","vtt","srt"))
            val code=text(s,"language_code").lowercase().replace('_','-').let { if(it=="tur" || it=="tr" || it.startsWith("tr-"))"tr"else it }
            require(code.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})?")))
            Caption(code,mediaUrl(text(s,"url")) ?: error("Missing publisher subtitle"),expiry(s,now))
        }.getOrNull() }.distinctBy { it.url }.sortedBy { if(it.language=="tr")0 else 1 }
    }
    fun timedText(text:String):Boolean {
        val t=text.removePrefix("\uFEFF").replace("\r\n","\n").trimStart();if(t.startsWith("<"))return false
        val cue=Regex("""(?m)^(?:\d{2,}:)?\d{2}:\d{2}[.,]\d{3}\s*-->\s*(?:\d{2,}:)?\d{2}:\d{2}[.,]\d{3}[^\n]*\n([^\n]+)""")
        return cue.findAll(t).any { it.groupValues[1].replace(Regex("<[^>]*>"),"").any(Char::isLetter) }
    }
    enum class Denial { NONE, AUTH, ACCESS, LIMIT, CHALLENGE }
    fun denial(raw:String):Denial=runCatching {
        val body=raw.trimStart('\uFEFF',' ','\r','\n','\t');if(!body.startsWith("{"))return@runCatching Denial.NONE
        val j=JSONObject(body)
        val error=j.opt("error")
        val code=when(error){is JSONObject->text(error,"code");is String->error;else->if(j.opt("ok")==false || j.opt("success")==false)text(j,"code")else ""}.lowercase().replace('-','_')
        when(code) {
            "unauthorized","token_expired","session_expired","authentication_required"->Denial.AUTH
            "forbidden","access_denied","permission_denied","auth_denied","blocked"->Denial.ACCESS
            "rate_limited","too_many_requests","rate_limit_exceeded"->Denial.LIMIT
            "challenge_required","verification_required","bot_challenge","captcha_required"->Denial.CHALLENGE
            else->Denial.NONE
        }
    }.getOrDefault(Denial.NONE)
    fun deniedHtml(raw:String)=Regex("<title[^>]*>[^<]*(?:Access Denied|Attention Required|Just a moment|captcha)|verify you are human|you have been blocked",RegexOption.IGNORE_CASE).containsMatchIn(raw)
        fun apiAllowed(path:String,keys:Set<String>):Boolean {
            val allowed=when {
                path=="/home"->setOf("lang")
                path=="/browse"->setOf("lang","sort","limit","cursor")
                path=="/search"->setOf("lang","q","limit","cursor")
                Regex("/titles/[1-9][0-9]{0,15}").matches(path)->setOf("lang")
                Regex("/titles/[1-9][0-9]{0,15}/episodes").matches(path)->setOf("lang","offset","limit")
                Regex("/episodes/[1-9][0-9]{0,15}/play").matches(path)->setOf("lang","sub_lang")
                Regex("/episodes/[1-9][0-9]{0,15}/subtitles").matches(path)->setOf("lang")
                else->return false
            }
            return keys.all { it in allowed }
        }
    fun retryUntil(header:String?,now:Long):Long {
        val seconds=header?.trim()?.toLongOrNull()?.takeIf { it>=0 }
        if(seconds!=null)return if(seconds>(Long.MAX_VALUE-now)/1000)Long.MAX_VALUE else now+seconds*1000
        return runCatching { java.time.ZonedDateTime.parse(header,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()?.takeIf { it>now } ?: (now+1_800_000)
    }
}
