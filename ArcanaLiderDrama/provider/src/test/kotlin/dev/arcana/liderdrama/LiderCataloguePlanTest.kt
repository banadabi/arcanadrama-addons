package dev.arcana.liderdrama

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class LiderCataloguePlanTest {
    private val snapshot="""{"q":"","genre":"all","sort":"rating","dubbed":true,"episodes":"all"}"""
    @Test fun browsingHasThreeMixedShelvesAndOneLatestDubShelf() {
        val entries=LiderCataloguePlan.entries(snapshot,true)
        assertEquals(listOf("Yeni Eklenenler","Popüler","En Yüksek Puanlı","Türkçe Dublaj"),entries.map{it.label})
        assertEquals(4,entries.map{it.data}.toSet().size)
        assertTrue(entries.all{it.data!="home"&&it.data!=snapshot})
    }
    @Test fun everyShelfKeepsItsSortAndDubOnSecondPage() {
        val expected=listOf("newest" to false,"popular" to false,"rating" to false,"newest" to true)
        LiderCataloguePlan.entries(snapshot,true).forEachIndexed{i,entry->
            val shelf=LiderCataloguePlan.resolve(entry.data,snapshot,false,true)!!
            assertEquals(expected[i].first,shelf.sort);assertEquals(expected[i].second,shelf.dubbed)
            val query=LiderCataloguePlan.query(2,"","fantastik",shelf.sort,shelf.dubbed,"az")
            assertEquals("2",query["page"]);assertEquals("24",query["limit"])
            assertEquals(shelf.sort,query["sort"]);assertEquals(if(shelf.dubbed)"1" else null,query["dubbed"])
            assertEquals("fantastik",query["genre"]);assertEquals("az",query["episodes"])
        }
    }
    @Test fun fullSettingsSnapshotRejectsEveryStaleShelf() {
        val data=LiderCataloguePlan.entries(snapshot,true).first().data
        for((key,value)in listOf("q" to "Baba","genre" to "romantik","sort" to "newest","dubbed" to false,"episodes" to "cok")) {
            val changed=JSONObject(snapshot).put(key,value).toString()
            assertThrows(IllegalStateException::class.java){LiderCataloguePlan.resolve(data,changed,false,key!="q")}
        }
    }
    @Test fun searchIsOnePagedRowWithSavedFiltersAndUsableDetailRoutes() {
        val search=JSONObject(snapshot).put("q","Sürgünün Süpermarketi").toString()
        val entries=LiderCataloguePlan.entries(search,false)
        assertEquals(1,entries.size);assertEquals("Arama Sonuçları",entries.single().label)
        assertNull(LiderCataloguePlan.resolve(entries.single().data,search,false,false))
        val query=LiderCataloguePlan.query(2,"Sürgünün Süpermarketi","fantastik","rating",true,"az")
        assertEquals("Sürgünün Süpermarketi",query["search"]);assertEquals("rating",query["sort"]);assertEquals("1",query["dubbed"])
        val payload=JSONObject(javaClass.classLoader!!.getResource("catalogue.json")!!.readText()).put("currentPage",2)
        val second=LiderContract.page(payload,2)
        assertEquals(2,second.page);assertEquals(24,second.cards.size);assertTrue(second.page<second.pages)
        assertTrue(second.cards.all{LiderContract.identity(LiderContract.route(it))?.first==it.slug})
    }
    @Test fun legacyKeysRemainExplicitAndNeverAcceptUnknownOrStaleRows() {
        assertNull(LiderCataloguePlan.resolve(snapshot,snapshot,false,true))
        assertEquals("popular",LiderCataloguePlan.resolve("home",snapshot,true,true)!!.sort)
        assertThrows(IllegalArgumentException::class.java){LiderCataloguePlan.resolve("home",snapshot,false,true)}
        assertThrows(IllegalStateException::class.java){LiderCataloguePlan.resolve("lider-catalogue-v2:unknown:"+snapshot,snapshot,false,true)}
        val shelf=LiderCataloguePlan.entries(snapshot,true).first().data
        assertThrows(IllegalStateException::class.java){LiderCataloguePlan.resolve(shelf,snapshot,false,false)}
    }
    @Test fun defaultQueryUsesOnlyNormalCatalogueParameters() {
        assertEquals(mapOf("page" to "1","limit" to "24","dil" to "tr","lang" to "tr","sort" to "popular"),LiderCataloguePlan.query(1,"","all","popular",false,"all"))
    }
    @Test fun serializedCategoryTitleSelectsOnlyItsOwnShelf() {
        val entries=LiderCataloguePlan.entries(snapshot,true)
        entries.forEachIndexed { index, selected ->
            // Current hosts derive row IDs from title plus ordinal, then use first contains.
            val rowId="${selected.label}_$index"
            val matching=entries.first { rowId.contains(it.label)||rowId.startsWith(it.label) }
            assertEquals("Wrong category selected for $rowId",selected.data,matching.data)
            val target=LiderCataloguePlan.resolve(matching.data,snapshot,false,true)!!
            assertEquals(index>=3,target.dubbed)
            assertEquals(if(index>=3)"1" else null,LiderCataloguePlan.query(2,"","all",target.sort,target.dubbed,"all")["dubbed"])
        }
    }

}
