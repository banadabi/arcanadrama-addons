package dev.arcana.liderdrama

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiderMixedCaseSlugTest {
    private val slug="babami-imparator-yaptim-dublajli-TaGeHy8Pp8"
    private fun fixture(name:String)=JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())
    @Test fun publisherMixedCaseCardSurvivesCatalogueDetailPayloadApiAndCaptionBinding() {
        val page=fixture("catalogue.json")
        page.getJSONArray("items").getJSONObject(5).put("slug",slug)
        val parsed=LiderContract.page(page,1);assertEquals(24,parsed.cards.size)
        val card=parsed.cards.single{it.slug==slug}
        val route=LiderContract.route(card)
        assertTrue(route.contains(slug));assertEquals(slug to card.id,LiderContract.identity(route))
        val detail=fixture("detail.json");detail.getJSONObject("drama").put("id",card.id).put("slug",slug)
        val d=LiderContract.detail(detail,slug,card.id);assertEquals(slug,d.card.slug)
        assertThrows(IllegalArgumentException::class.java){LiderContract.detail(detail,slug.lowercase(),card.id)}
        val p=LiderContract.Payload(card.id,slug,d.chapters.first().id,1)
        assertEquals(p,LiderContract.decode(LiderContract.encode(p)))
        LiderPolicy.request("https://liderdrama.com/api/drama/$slug?lang=tr",false)
        LiderPolicy.request("https://liderdrama.com/api/video?slug=$slug&ep=1",false)
        val caption="https://liderdrama.com/subs/$slug/1.vtt"
        LiderPolicy.request(caption,false);assertEquals(caption,LiderContract.media(caption))
        assertEquals(caption,LiderAutomaticCaptionPolicy.target(caption,slug,1))
        assertThrows(IllegalArgumentException::class.java){LiderAutomaticCaptionPolicy.target(caption,slug.lowercase(),1)}
        assertThrows(IllegalArgumentException::class.java){LiderAutomaticCaptionPolicy.target(caption,slug,2)}
    }
    @Test fun acceptingAsciiCaseDoesNotPermitPathQueryEncodingOrForeignSourceChanges() {
        for(bad in listOf("../Other","a/b","a?b","a#b","a%2Fb","a_b","Türkçe")) {
            assertThrows(IllegalArgumentException::class.java){LiderContract.slug(bad)}
            assertNull(LiderContract.identity("https://liderdrama.com/dizi/$bad"))
        }
        for(bad in listOf("https://liderdrama.com/subs/$slug/1.vtt?token=x","https://other.example/subs/$slug/1.vtt","https://liderdrama.com/subs/${slug.replace("T","%54")}/1.vtt"))
            assertThrows(IllegalArgumentException::class.java){LiderAutomaticCaptionPolicy.target(bad,slug,1)}
        assertThrows(IllegalArgumentException::class.java){LiderPolicy.request("https://liderdrama.com/api/drama/../admin?lang=tr",false)}
    }
}
