package dev.arcana.dramacix

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

/** Published catalogue filters. A request carries its own snapshot through pagination. */
internal object DramacixCatalogue {
    val genres=linkedMapOf(
        "" to "Tümü (Dublaj ve Altyazı)", "romantik" to "Romantik", "ceo" to "CEO & Patron",
        "intikam" to "İntikam", "fantastik" to "Fantastik", "aile" to "Aile & Dram",
        "kurt-adam" to "Kurt Adam", "tarihi" to "Tarihi & Saray", "aksiyon" to "Aksiyon"
    )
    enum class Kind { POPULAR, NEW, DUB, SEARCH, PLATFORM }
    data class Request(val kind:Kind,val genre:String="",val query:String="",val platform:String="") {
        init {
            require(genre in genres && query.length<=160 && query==query.trim() && query.none(Char::isISOControl))
            if(kind==Kind.PLATFORM)require(platform.length<=80 && platformSlug.matches(platform) && genre.isEmpty() && query.isEmpty())
            else require(platform.isEmpty())
            if(kind==Kind.SEARCH)require(query.isNotEmpty())
        }
        val data:String get()=JSONObject().put("v",1).put("kind",kind.name).put("genre",genre)
            .put("q",query).put("platform",platform).toString()
        val label:String get() {
            val title=when(kind) {
                Kind.POPULAR->"Popüler Diziler";Kind.NEW->"Yeni Eklenenler";Kind.DUB->"Türkçe Dublajlı"
                Kind.SEARCH->"Arama Sonuçları";Kind.PLATFORM->if(platform=="dramawave")"Platform · DramaWave"else"Platform · $platform"
            }
            return if(genre.isEmpty())title else "${genres.getValue(genre)} · $title"
        }
        fun url(page:Int,ajax:Boolean=page>1):String {
            require(page in 1..9999)
            if(kind==Kind.PLATFORM)return DramacixContract.BASE+"/platform/"+platform+if(page==1)""else"/sayfa/$page"
            return (DramacixContract.BASE+"/diziler").toHttpUrl().newBuilder().addQueryParameter("tur",genre)
                .apply {
                    if(kind==Kind.DUB)addQueryParameter("dublaj","1")
                    if(kind!=Kind.POPULAR)addQueryParameter("sira","yeni")
                    addQueryParameter("q",query)
                    if(page>1)addQueryParameter("sayfa",page.toString())
                    if(page>1 && ajax)addQueryParameter("ajax","1")
                }.build().toString()
        }
    }
    private val platformSlug=Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")
    fun platformInput(raw:String):String {
        val value=raw.trim()
        if(value.isEmpty())return ""
        if(value.length<=80 && platformSlug.matches(value))return value
        val absolute=if(value.startsWith("/"))DramacixContract.BASE+value else value
        val uri=DramacixContract.own(absolute) ?: error("platform origin")
        require(uri.rawQuery==null)
        val match=Regex("/platform/([A-Za-z0-9-]+)(?:/sayfa/[1-9][0-9]{0,3})?/?").matchEntire(uri.rawPath) ?: error("platform path")
        return match.groupValues[1].also { require(it.length<=80 && platformSlug.matches(it)) }
    }
    fun decode(raw:String):Request {
        // Existing host history may still refer to the former three catalogue IDs.
        when(raw) { "/"->return Request(Kind.POPULAR);"recent"->return Request(Kind.NEW);"dub"->return Request(Kind.DUB) }
        require(raw.length<=2048)
        val j=JSONObject(raw)
        require(j.keys().asSequence().toSet()==setOf("v","kind","genre","q","platform") && j.getInt("v")==1)
        return Request(Kind.valueOf(j.getString("kind")),j.getString("genre"),j.getString("q"),j.getString("platform"))
    }
    fun rows(genre:String,query:String,platform:String):List<Request> {
        val q=query.trim()
        if(q.isNotEmpty())return listOf(Request(Kind.SEARCH,genre,q))
        val base=listOf(Request(Kind.POPULAR,genre),Request(Kind.NEW,genre),Request(Kind.DUB,genre))
        return if(platform.isEmpty())base else base+Request(Kind.PLATFORM,platform=platformInput(platform))
    }
    fun platformListing(html:String,request:Request,page:Int):DramacixContract.Listing {
        require(request.kind==Kind.PLATFORM && page in 1..9999)
        val doc=Jsoup.parse(html,DramacixContract.BASE)
        val region=doc.selectFirst("#listeBasi") ?: error("platform catalogue region")
        val grid=region.selectFirst("div.grid") ?: error("platform catalogue grid")
        val pager=region.selectFirst("div.pages")
        if(pager==null)require(page==1)else {
            val current=pager.select("span.on").singleOrNull()?.text()?.trim()?.toIntOrNull()
            require(current==page) { "platform page identity" }
        }
        fun number(raw:String):Int?=runCatching {
            val u=DramacixContract.own(URI(DramacixContract.BASE).resolve(raw).toASCIIString()) ?: return@runCatching null
            if(u.rawQuery!=null)return@runCatching null
            if(u.path=="/platform/${request.platform}" || u.path=="/platform/${request.platform}/")return@runCatching 1
            Regex("/platform/${Regex.escape(request.platform)}/sayfa/([1-9][0-9]{0,3})/?").matchEntire(u.path)?.groupValues?.get(1)?.toIntOrNull()
        }.getOrNull()
        val more=pager?.select("a[href]")?.any { number(it.attr("href"))==page+1 }==true
        return DramacixContract.Listing(DramacixContract.cards(grid.outerHtml()),more)
    }
}
