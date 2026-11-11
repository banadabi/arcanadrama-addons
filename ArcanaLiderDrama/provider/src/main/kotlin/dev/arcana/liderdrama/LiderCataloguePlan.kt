package dev.arcana.liderdrama

/** Initial loading has one catalogue dependency; aggregate Home is never a prerequisite. */
internal object LiderCataloguePlan {
    class Entry(val data:String,val label:String)
    fun entries(snapshot:String,defaultFilters:Boolean,emptyQuery:Boolean):List<Entry> =
        listOf(Entry(snapshot,if(defaultFilters)"Popüler Diziler" else if(emptyQuery)"Filtrelenmiş Diziler" else "Arama Sonuçları"))
    fun requireCurrent(data:String,snapshot:String,defaultFilters:Boolean) {
        require(data==snapshot||(data=="home"&&defaultFilters)) { "Stale catalogue filters" }
    }
}
