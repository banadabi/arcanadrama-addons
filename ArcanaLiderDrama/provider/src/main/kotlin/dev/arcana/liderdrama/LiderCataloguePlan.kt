package dev.arcana.liderdrama

/** Each shelf uses the publisher's paged catalogue and current settings snapshot. */
internal object LiderCataloguePlan {
    class Entry(val data:String,val label:String)
    data class Shelf(val id:String,val label:String,val sort:String,val dubbed:Boolean)
    // Distinct phrases keep substring-based host row matching unambiguous.
    private val shelves=listOf(
        Shelf("newest","Yeni Eklenenler","newest",false),
        Shelf("popular","Popüler","popular",false),
        Shelf("rating","En Yüksek Puanlı","rating",false),
        Shelf("dub-newest","Türkçe Dublaj","newest",true)
    )
    private fun wire(id:String,snapshot:String)="lider-catalogue-v2:$id:$snapshot"
    fun entries(snapshot:String,emptyQuery:Boolean):List<Entry> =
        if(emptyQuery)shelves.map{Entry(wire(it.id,snapshot),it.label)}
        else listOf(Entry(wire("search",snapshot),"Arama Sonuçları"))
    /** Null preserves the saved search filters, including the legacy raw snapshot. */
    fun resolve(data:String,snapshot:String,defaultFilters:Boolean,emptyQuery:Boolean):Shelf? {
        if(data==snapshot)return null
        if(data=="home") {
            require(defaultFilters&&emptyQuery){"Stale catalogue filters"}
            return shelves.first{it.id=="popular"}
        }
        if(!emptyQuery&&data==wire("search",snapshot))return null
        if(emptyQuery)shelves.firstOrNull{data==wire(it.id,snapshot)}?.let{return it}
        error("Stale or unknown catalogue shelf")
    }
    fun query(page:Int,q:String,genre:String,sort:String,dubbed:Boolean,episodes:String):Map<String,String> {
        require(page in 1..100000)
        return linkedMapOf("page" to page.toString(),"limit" to "24","dil" to "tr","lang" to "tr","sort" to sort).apply {
            if(dubbed)put("dubbed","1")
            if(genre!="all")put("genre",genre)
            if(episodes!="all")put("episodes",episodes)
            if(q.isNotEmpty())put("search",q)
        }
    }
}
