package dev.arcana.liderdrama

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class LiderCataloguePlanTest {
    @Test fun defaultOpeningHasOneEncodedCatalogueDependency() {
        val snapshot="""{"q":"","genre":"all","sort":"popular","dubbed":false,"episodes":"all"}"""
        val entries=LiderCataloguePlan.entries(snapshot,true,true)
        assertEquals(1,entries.size)
        assertEquals(snapshot,entries.single().data)
        assertEquals("Popüler Diziler",entries.single().label)
        assertNotEquals("home",entries.single().data)
        LiderCataloguePlan.requireCurrent(entries.single().data,snapshot,true)
    }
    @Test fun legacyHomeAliasesOnlyCurrentDefaultCatalogue() {
        val snapshot="""{"q":""}"""
        LiderCataloguePlan.requireCurrent("home",snapshot,true)
        assertThrows(IllegalArgumentException::class.java){LiderCataloguePlan.requireCurrent("home",snapshot,false)}
        assertThrows(IllegalArgumentException::class.java){LiderCataloguePlan.requireCurrent("""{"q":"old"}""",snapshot,true)}
    }
    @Test fun filteredSearchRetainsExactCurrentDataAndSinglePagedEntry() {
        val snapshot="""{"q":"Baba","genre":"fantastik","sort":"newest","dubbed":true,"episodes":"az"}"""
        val entries=LiderCataloguePlan.entries(snapshot,false,false)
        assertEquals(1,entries.size)
        assertEquals("Arama Sonuçları",entries.single().label)
        assertEquals(snapshot,entries.single().data)
        LiderCataloguePlan.requireCurrent(entries.single().data,snapshot,false)
        // Existing source page parsing remains usable for subsequent pages on this sole entry.
        val payload=JSONObject(javaClass.classLoader!!.getResource("catalogue.json")!!.readText())
        payload.put("currentPage",2)
        val second=LiderContract.page(payload,2)
        assertEquals(2,second.page)
        assertEquals(24,second.cards.size)
        assertTrue(second.page<second.pages)
    }
}
