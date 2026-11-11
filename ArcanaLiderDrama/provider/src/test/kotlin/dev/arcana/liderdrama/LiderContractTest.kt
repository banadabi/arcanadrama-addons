package dev.arcana.liderdrama

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class LiderContractTest {
    private fun fixture(name:String)=JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())
    private val slug="balikci-baba-hesap-soruyor"
    @Test fun realChosenLunaChaptersWithoutRowIdsRemainAvailable() {
        val d=LiderContract.detail(fixture("chosen-luna-metadata.json"),"chosen-luna",1906626157L)
        assertEquals(790,d.chapters.size)
        assertEquals((1..790).toList(),d.chapters.map{it.number})
        assertNull(d.chapters.first().id)
        for(c in d.chapters.take(2)) {
            val p=LiderContract.Payload(d.card.id,d.card.slug,c.id,c.number)
            val encoded=LiderContract.encode(p)
            assertEquals(p,LiderContract.decode(encoded))
            assertEquals(2,JSONObject(encoded).getInt("schema"))
            assertTrue(JSONObject(encoded).isNull("chapter"))
            assertNull(LiderContract.decode(JSONObject(encoded).put("schema",1).toString()))
        }
        val duplicate=fixture("chosen-luna-metadata.json");duplicate.getJSONObject("drama").getJSONArray("bolumler").getJSONObject(1).put("bolum",1).put("ep",1)
        assertThrows(IllegalArgumentException::class.java){LiderContract.detail(duplicate,"chosen-luna",1906626157L)}
    }
    @Test fun realLargeSeriesIdAndAllPublishedChaptersSurviveMapping() {
        val d=LiderContract.detail(fixture("detail.json"),slug,2147485212L)
        assertEquals(2147485212L,d.card.id);assertEquals("Balıkçı Baba Hesap Soruyor",d.card.title)
        assertEquals((1..31).toList(),d.chapters.map{it.number})
        val p=LiderContract.Payload(d.card.id,d.card.slug,d.chapters[0].id,d.chapters[0].number)
        assertEquals(p,LiderContract.decode(LiderContract.encode(p)))
        assertEquals(p,LiderContract.decode(JSONObject(LiderContract.encode(p)).put("schema",1).toString()))
        assertEquals(slug to 2147485212L,LiderContract.identity(LiderContract.route(d.card)))
        assertThrows(IllegalArgumentException::class.java){LiderContract.detail(fixture("detail.json"),"another-series",d.card.id)}
        assertThrows(IllegalArgumentException::class.java){LiderContract.detail(fixture("detail.json"),slug,123L)}
        val gap=fixture("detail.json");gap.getJSONObject("drama").getJSONArray("bolumler").remove(1)
        assertEquals(3,LiderContract.detail(gap,slug,d.card.id).chapters[1].number)
    }
    @Test fun currentHomeAndTrueCataloguePaginationArePreserved() {
        val rows=LiderContract.home(fixture("home.json"));assertEquals(5,rows.size)
        val page=LiderContract.page(fixture("catalogue.json"),1)
        assertEquals(24,page.cards.size);assertEquals(10965,page.total);assertEquals(457,page.pages)
        assertThrows(IllegalArgumentException::class.java){LiderContract.page(fixture("catalogue.json"),2)}
        assertTrue(rows.flatMap{it.second}.all{LiderContract.identity(LiderContract.route(it))!=null})
    }
    @Test fun normalVideoApiDeclaresMp4DespiteInitialM3u8FieldName() {
        val video=LiderContract.playback(fixture("video.json"))
        assertEquals("mp4",video.type);assertEquals("tr",video.captions.single().language)
        assertEquals("https://liderdrama.com/subs/$slug/1.vtt",video.captions.single().url)
        assertTrue(java.net.URI(video.url).rawQuery.split('&').size>10)
        assertNull(LiderContract.decode(JSONObject().put("schema",1).put("series",1).put("slug",slug).put("chapter",1).put("number",1).put("url",video.url).toString()))
        assertNull(LiderContract.identity(LiderContract.encode(LiderContract.Payload(1,slug,1,1))))
    }
    @Test fun apiControlTranslationAndNonPublicBoundariesStayClosed() {
        LiderPolicy.request("https://liderdrama.com/api/dramas?page=1&limit=24&dil=tr&lang=tr&sort=popular&genre=fantastik&dubbed=1&episodes=az&search=Baba",false)
        LiderPolicy.request("https://liderdrama.com/api/video?slug=$slug&ep=1",false)
        for(url in listOf("https://liderdrama.com/api/admin/settings","https://liderdrama.com/x/../api/video?slug=$slug&ep=1"))assertThrows(IllegalArgumentException::class.java){LiderPolicy.request(url,false)}
        for(url in listOf("https://liderdrama.com/api/video?slug=$slug&ep=1","https://liderdrama.com/x/../api/admin/settings","https://liderdrama.com/api/%74ranslate?id=1"))assertThrows(IllegalArgumentException::class.java){LiderContract.media(url)}
        assertFalse(LiderPolicy.publicAddress(InetAddress.getByAddress(byteArrayOf(100,64,0,1))))
        assertFalse(LiderPolicy.publicAddress(InetAddress.getByAddress(byteArrayOf(198.toByte(),18,0,1))))
        val now=java.time.Instant.parse("2026-11-10T00:00:00Z").toEpochMilli()
        assertEquals(now+172800000,LiderPolicy.retryDeadline("Thu, 12 Nov 2026 00:00:00 GMT",now))
    }
    @Test fun retainedBinaryRangeAndExplicitDenialRulesDoNotConfuseMedia() {
        assertFalse(LiderPolicy.inspectMediaDocument(206,"mp4","application/octet-stream"))
        assertFalse(LiderPolicy.inspectMediaDocument(200,"key","text/plain"))
        assertFalse(LiderPolicy.errorDocument("{\u0000\u0001\u0002"))
        assertEquals(403,LiderPolicy.bodyDenial("{\"error\":\"forbidden\"}"))
        assertNull(LiderPolicy.bodyDenial("WEBVTT\n\n00:00.000 --> 00:01.000\nAccess denied\n"))
    }
    @Test fun sourceAliasesAndWrappedControlUrlsCannotBecomeMedia() {
        val denied=listOf("https://liderdrama.com./api/video?slug=$slug&ep=1","https://www.liderdrama.com/api/home?lang=tr")
        for(url in denied) {
            assertThrows(IllegalArgumentException::class.java){LiderContract.media(url)}
            val encoded=java.net.URLEncoder.encode(url,"UTF-8")
            assertThrows(IllegalArgumentException::class.java){LiderContract.media("https://media.example.org/proxy?url=$encoded")}
            val base64=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray())
            assertThrows(IllegalArgumentException::class.java){LiderContract.media("https://media.example.org/proxy?url=$base64")}
        }
        val target="https://ns-aws-cdn.netshort.com/FIXTURE?mime_type=video_mp4"
        val original="https://media.example.org/proxy?url="+java.net.URLEncoder.encode(target,"UTF-8")
        assertEquals(original,LiderContract.media(original))
    }
}
