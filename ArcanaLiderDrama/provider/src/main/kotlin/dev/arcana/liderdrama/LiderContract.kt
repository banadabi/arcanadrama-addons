package dev.arcana.liderdrama

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import org.jsoup.Jsoup

/** Real publisher series IDs (including >Int.MAX_VALUE), actual scoped episode IDs and ordinals. */
internal object LiderContract {
    const val BASE="https://liderdrama.com"
    const val NAME="(Arcana Drama) LiderDrama"
    val genres=linkedMapOf("all" to "Tüm Türler","dirilis" to "Diriliş ve Zafer","antik" to "Antik","intikam" to "İntikam","romantik" to "Romantik","patron" to "Patron & CEO","sozlesme" to "Sözleşmeli Aşk","modern" to "Modern Aşk","fantastik" to "Fantastik","yeniden" to "Yeniden Doğuş","mafya" to "Dövüş & Mafya")
    val sorts=linkedMapOf("popular" to "Popüler","newest" to "Yeni Eklenenler","rating" to "Yüksek Puanlı")
    val counts=linkedMapOf("all" to "Tüm Bölüm Sayıları","az" to "Az Bölümlü","orta" to "Orta Bölümlü","cok" to "Çok Bölümlü")
    data class Card(val id:Long,val slug:String,val title:String,val poster:String?,val count:Int?,val score:Double?,val dubbed:Boolean)
    data class Chapter(val id:Long?,val number:Int,val title:String?)
    data class Detail(val card:Card,val plot:String,val tags:List<String>,val chapters:List<Chapter>)
    data class Page(val cards:List<Card>,val page:Int,val pages:Int,val total:Int)
    data class Payload(val series:Long,val slug:String,val chapter:Long?,val number:Int)
    data class Caption(val language:String,val url:String,val format:String)
    data class Playback(val url:String,val type:String,val captions:List<Caption>) { override fun toString()="LiderPlayback([redacted])" }
    fun id(v:Any?):Long {
        val n=when(v){is Int->v.toLong();is Long->v;else->error("Source integer identity missing")}
        require(n in 1..9_007_199_254_740_991L);return n
    }
    fun slug(v:String)=v.also { require(it.matches(Regex("[a-z0-9][a-z0-9-]{0,239}"))) }
    private fun number(v:Any?):Int?=when(v){is Int->v;is Long->v.takeIf{it in 0..100000}?.toInt();else->null}
    private fun text(j:JSONObject,k:String)=(j.opt(k)as?String).orEmpty().trim()
    private fun plain(v:String)=Jsoup.parse(v).text().trim()
    fun https(raw:String):String {
        require(raw.length in 1..16000 && raw.none{it.code<32||it.code==127||it=='\\'})
        val u=URI(BASE+"/").resolve(raw)
        require(u.scheme=="https"&&!u.host.isNullOrBlank()&&u.port in setOf(-1,443)&&u.rawUserInfo==null&&u.rawFragment==null)
        return u.toASCIIString()
    }
    fun media(raw:String):String= https(raw).also { LiderPolicy.readOnlyMedia(URI(it)) }
    fun route(card:Card)="$BASE/dizi/${card.slug}#lid=${card.id}"
    fun identity(raw:String):Pair<String,Long?>?=runCatching {
        require(raw.length<=4096);val u=URI(raw)
        require(u.scheme=="https"&&u.host=="liderdrama.com"&&u.port in setOf(-1,443)&&u.rawUserInfo==null&&u.rawQuery==null)
        val m=Regex("/dizi/([a-z0-9][a-z0-9-]{0,239})/?").matchEntire(u.rawPath)?:error("Not a source detail")
        val expected=u.rawFragment?.let { f->val v=Regex("lid=([1-9][0-9]{0,15})").matchEntire(f)?:error("Bad source identity");v.groupValues[1].toLong().also{require(it<=9_007_199_254_740_991L)} }
        m.groupValues[1] to expected
    }.getOrNull()
    fun card(j:JSONObject):Card {
        val sourceId=id(j.opt("id"));val sourceSlug=slug(j.getString("slug"));val name=plain(text(j,"baslik").ifBlank{text(j,"title")});require(name.isNotBlank()&&name.length<=2048)
        val poster=text(j,"poster").takeIf{it.isNotBlank()}?.let { runCatching{https(it)}.getOrNull() }
        val count=number(j.opt("toplam_bolum"))?:number(j.opt("totalEpisodes"))
        val score=text(j,"rating").toDoubleOrNull()?.takeIf{it in 0.0..10.0}
        return Card(sourceId,sourceSlug,name,poster,count,score,j.opt("dublaj")==true)
    }
    private fun cards(a:JSONArray):List<Card> {
        require(a.length()<=1000)
        return (0 until a.length()).map{card(a.getJSONObject(it))}.distinctBy{it.id}
    }
    fun home(j:JSONObject):List<Pair<String,List<Card>>> {
        require(j.opt("success")==true)
        val keys=linkedMapOf("hero" to "Öne Çıkanlar","newReleases" to "Yeni Eklenenler","topTrends" to "En Çok İzlenenler","popular" to "Popüler Diziler","dubbed" to "Türkçe Dublajlı")
        return keys.mapNotNull{(key,label)->j.optJSONArray(key)?.let{label to cards(it)}}.filter{it.second.isNotEmpty()}.also{require(it.isNotEmpty())}
    }
    fun page(j:JSONObject,expected:Int):Page {
        require(j.opt("success")==true&&expected in 1..100000)
        val page=number(j.opt("currentPage"))?:error("Source page missing");val pages=number(j.opt("pages"))?:error("Source pages missing");val total=number(j.opt("total"))?:error("Source total missing")
        require(page==expected&&pages>=1&&page<=pages&&total>=0)
        return Page(cards(j.getJSONArray("items")),page,pages,total)
    }
    fun detail(j:JSONObject,expectedSlug:String,expectedId:Long?):Detail {
        require(j.opt("success")==true);val d=j.getJSONObject("drama");val c=card(d)
        require(c.slug==slug(expectedSlug)&&(expectedId==null||expectedId==c.id))
        val a=d.getJSONArray("bolumler");require(a.length() in 1..10000)
        val chapters=(0 until a.length()).map{i->val e=a.getJSONObject(i);val n=number(e.opt("bolum"))?:number(e.opt("ep"))?:error("Source ordinal missing");require(n in 1..10000)
            number(e.opt("ep"))?.let{require(it==n)}
            // Some real catalogues publish only bolum/ep. Their ordinal is the API's chapter key.
            val rawId=e.opt("id");val chapterId=if(rawId==null||rawId===JSONObject.NULL)null else id(rawId)
            Chapter(chapterId,n,text(e,"baslik").takeIf{it.isNotBlank()})
        }.sortedBy{it.number}
        val suppliedIds=chapters.mapNotNull{it.id}
        require(chapters.map{it.number}.toSet().size==chapters.size&&suppliedIds.toSet().size==suppliedIds.size)
        fun strings(v:Any?):List<String> = when(v){is JSONArray->(0 until v.length()).mapNotNull{(v.opt(it)as?String)?.trim()?.takeIf(String::isNotBlank)};is String->listOf(v.trim()).filter(String::isNotBlank);else->emptyList()}
        val tags=(strings(d.opt("tags"))+strings(d.opt("turler"))+strings(d.opt("genre"))+strings(d.opt("platform"))).distinct()
        return Detail(c,plain(text(d,"ozet").ifBlank{text(d,"summary")}),tags,chapters)
    }
    fun encode(p:Payload)=JSONObject().put("schema",2).put("series",p.series).put("slug",p.slug).put("chapter",p.chapter?:JSONObject.NULL).put("number",p.number).toString()
    fun decode(raw:String):Payload?=runCatching {
        require(raw.length<=4096);val j=JSONObject(raw);val schema=j.opt("schema")
        require(schema in setOf(1,2)&&j.keys().asSequence().toSet()==setOf("schema","series","slug","chapter","number"))
        val rawId=j.get("chapter");val chapter=if(schema==2&&rawId===JSONObject.NULL)null else id(rawId)
        val n=id(j.opt("number"));require(n<=10000);Payload(id(j.opt("series")),slug(j.getString("slug")),chapter,n.toInt())
    }.getOrNull()
    fun playback(j:JSONObject):Playback {
        require(j.opt("premium_required")!=true&&j.opt("available")!=false&&!LiderPolicy.error(j)&&LiderPolicy.denial(j)==null)
        val url=media(j.getString("url"));val type=text(j,"t").lowercase(java.util.Locale.ROOT)
        require(type in setOf("mp4","hls","m3u8"))
        val a=j.optJSONArray("subs")?:JSONArray();require(a.length()<=100)
        val captions=(0 until a.length()).map{i->val c=a.getJSONObject(i);val lang=text(c,"lang");require(lang.length in 1..100)
            val format=text(c,"format").lowercase(java.util.Locale.ROOT);require(format in setOf("vtt","srt"))
            Caption(lang,media(c.getString("url")),format)
        }
        return Playback(url,if(type=="mp4")"mp4" else "hls",captions)
    }
}
