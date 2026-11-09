package dev.arcana.directdrama

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectContractTest {
    private fun flight(vararg values:JSONObject):String {
        val raw=values.mapIndexed { i,v->"${i.toString(16)}:$v\n".replace("\\n","\n") }.joinToString("")
        return "<script>self.__next_f.push([1,"+JSONObject.quote(raw)+"])</script>"
    }
    private fun episode(id:Int,n:Int)=JSONObject().put("id",id).put("number",n)
    private fun binding()=JSONObject().put("series",JSONObject().put("id",120).put("slug","example-show").put("title","Örnek Dizi").put("coverKey","cover_1"))
        .put("episodes",JSONArray().put(episode(81,1)).put(episode(98,3))).put("children",JSONArray())
    private fun detail()="""<html><head><script type="application/ld+json">{"@type":"TVSeries","url":"https://directdrama.com/tr/dizi/example-show","name":"Örnek Dizi","image":{"url":"https://directdrama.com/img/covers/cover_1/720.webp"}}</script></head><body><section aria-labelledby="series-title"><h1>Örnek Dizi – Tüm Bölümler</h1><a href="/tr/platform/sample">Platform</a></section><section id="facts"><p>Kaynağın tam açıklaması.</p><a href="/tr/tur/gerilim">Gerilim</a></section><a href="/tr/dizi/example-show/bolum-3">3</a><a href="/tr/dizi/example-show/bolum-1">1</a></body></html>"""
    @Test fun realEpisodeIdsAndGapsSurviveDetailAndPayload() {
        val s=DirectContract.series(detail(),flight(binding()),"example-show")
        assertEquals(listOf(1,3),s.chapters.map { it.number });assertEquals(listOf("81","98"),s.chapters.map { it.id })
        assertEquals("Kaynağın tam açıklaması.",s.plot);assertEquals(listOf("Platform","Gerilim"),s.tags)
        val c=s.chapters.last();assertEquals(c,DirectContract.decode(DirectContract.encode(c)))
        assertEquals("https://directdrama.com/tr/dizi/example-show/bolum-1",DirectContract.firstWatch(detail(),"example-show"))
    }
    @Test fun wrongSeriesAndWrongEpisodeCannotBind() {
        val c=DirectContract.Chapter("120","81","example-show",1)
        DirectContract.verifyWatch(flight(binding(),JSONObject().put("seriesId",120).put("episodeId",81)),c)
        assertTrue(runCatching { DirectContract.verifyWatch(flight(binding(),JSONObject().put("seriesId",120).put("episodeId",98)),c) }.isFailure)
        assertTrue(runCatching { DirectContract.series(detail(),flight(binding()),"other-show") }.isFailure)
        assertNull(DirectContract.detailSlug(DirectContract.encode(c)))
    }
    @Test fun duplicateEpisodeIdsOrOrdinalsFailClosed() {
        val b=binding().put("episodes",JSONArray().put(episode(81,1)).put(episode(81,3)))
        assertTrue(runCatching { DirectContract.series(detail(),flight(b),"example-show") }.isFailure)
    }
    @Test fun homeUsesPublishedRowsAndCards() {
        val card=JSONObject().put("id",9).put("slug","example-show").put("title","Örnek Dizi").put("coverKey","cover_1").put("episodes",2)
        val row=JSONObject().put("id","sec-new").put("title","Yeni eklenenler").put("cards",JSONArray().put(card))
        val rows=DirectContract.home(flight(row));assertEquals(1,rows.size);assertEquals("Yeni eklenenler",rows.single().name)
        assertEquals("https://directdrama.com/img/covers/cover_1/720.webp",rows.single().cards.single().poster)
    }
    @Test fun searchUsesRealHasMoreAndRequestedPage() {
        val j=JSONObject().put("items",JSONArray()).put("total",0).put("page",2).put("hasMore",false)
        assertFalse(DirectContract.searchPage(j.toString(),2).hasNext)
        assertTrue(runCatching { DirectContract.searchPage(j.toString(),1) }.isFailure)
    }
    @Test fun playbackPreservesSignedBytesAndCaptionIdentity() {
        val c=DirectContract.Chapter("120","81","example-show",1)
        val url="https://cdn.example.test/file.mp4?sign=a%2Bb%3D&t=123"
        val j=JSONObject().put("episodeId",81).put("type","mp4").put("url",url).put("expiresAt","2030-01-01T00:00:00Z")
            .put("subtitles",JSONArray().put(JSONObject().put("lang","tr").put("url","/subs/track/tr.vtt")))
        val p=DirectContract.playback(j,c);assertEquals(url,p.video);assertFalse(p.hls);assertEquals("tr",p.captions.single().language)
        assertEquals("https://directdrama.com/subs/track/tr.vtt",p.captions.single().url)
        assertTrue(runCatching { DirectContract.playback(j.put("episodeId",98),c) }.isFailure)
    }
    @Test fun mutationAliasesAndTranslationWrappersAreRefused() {
        assertNull(DirectContract.https("/api/stream/81?locale=tr&%72efresh=1"))
        assertNull(DirectContract.https("https://directdrama.com./api/x/../stream/81?failed=x"))
        assertNull(DirectContract.captionURL("https://other.example/proxy?url=https%3A%2F%2Fx.example%2Fapi%2Faltyazi.php"))
        assertEquals("https://directdrama.com/api/stream/81?locale=tr",DirectContract.https("/api/stream/81?locale=tr"))
    }
    @Test fun captionsNeedActualTimedTextAndChallengePagesAreNotData() {
        assertTrue(DirectContract.timedText("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nMerhaba dünya.\n"))
        assertFalse(DirectContract.timedText("WEBVTT\n\n"))
        assertTrue(DirectContract.deniedHtml("<title>Just a moment...</title>"))
    }
    @Test fun preparingRetryHonorsHttpDateAndNeverRetriesUnknownHeaderEarly() {
        val now=java.time.Instant.parse("2026-11-09T10:00:00Z").toEpochMilli()
        assertEquals(12000L,DirectContract.preparingDelay("Mon, 9 Nov 2026 10:00:12 GMT",now))
        assertEquals(5000L,DirectContract.preparingDelay("5",now))
        assertEquals(3000L,DirectContract.preparingDelay(null,now))
        assertEquals(Long.MAX_VALUE,DirectContract.preparingDelay("not-a-date",now))
    }

}
