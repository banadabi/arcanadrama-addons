package dev.arcana.dramadunyam

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.Request
import java.io.IOException
import java.util.Base64

class DunyaContractTest {
    private fun metadata(count:Int=3)=JSONObject().put("id","854352711757").put("slug","ornek-dizi").put("title","Örnek Dizi")
        .put("available_episodes",count).put("total_episodes",7).put("platform","Örnek Platform").put("genre","Fantastik")
        .put("description","<p>Tam açıklama.</p><p>İkinci paragraf.</p>").put("cover","https://images.example/cover.webp")
        .put("tags",JSONArray().put(JSONObject().put("slug","romantik").put("name","Romantik")))
    private val links="""<html><head><title>Örnek</title></head><body><h1>Örnek Dizi</h1>
        <a href="/dizi/ornek-dizi/bolum-7">7</a><a href="/dizi/ornek-dizi/bolum-1">1</a>
        <a href="/dizi/ornek-dizi/bolum-3">3</a><a href="/dizi/baska-dizi/bolum-2">başka</a>
        <a href="/dizi/ornek-dizi/bolum-1">tekrar</a></body></html>"""
    private fun resource(name:String)=javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
    @Test fun opaqueLongIdAndActualChapterGapsSurviveRoundTrip() {
        val series=DunyaContract.series(metadata().put("bolum_no",JSONArray().put(1).put(3).put(7)).toString(),links,"ornek-dizi")
        assertEquals("854352711757",series.id)
        assertEquals(listOf(1,3,7),series.chapters.map { it.number })
        assertEquals(series.chapters[1],DunyaContract.decode(DunyaContract.encode(series.chapters[1])))
        assertTrue(series.plot.contains("Tam açıklama."));assertTrue(series.plot.contains("İkinci paragraf."))
        assertTrue(series.tags.containsAll(listOf("Fantastik","Romantik","Örnek Platform")))
        assertNull(DunyaContract.detailSlug(DunyaContract.encode(series.chapters[0])))
    }
    @Test fun declaredNumberArrayNeverFillsMissingOrdinals() {
        val m=metadata().put("bolum_no",JSONArray().put(7).put(1).put(3))
        assertEquals(listOf(1,3,7),DunyaContract.series(m.toString(),null,"ornek-dizi").chapters.map { it.number })
        assertTrue(runCatching { DunyaContract.series(m.put("bolum_no",JSONArray().put(1).put(1)).toString(),null,"ornek-dizi") }.isFailure)
    }
    @Test fun declaredAvailabilityNeverExpandsToPlannedTotalAndIdentityStillBinds() {
        val series=DunyaContract.series(metadata(4).toString(),null,"ornek-dizi")
        assertEquals(listOf(1,2,3,4),series.chapters.map { it.number })
        assertEquals("PUBLISHER_DECLARED_AVAILABLE_RANGE",series.chapterEvidence)
        assertTrue(runCatching { DunyaContract.series(metadata().toString(),links,"baska-dizi") }.isFailure)
        val chapter=series.chapters.first()
        assertNull(DunyaContract.decode(JSONObject(DunyaContract.encode(chapter)).put("number",3).toString()))
    }
    @Test fun oneHomeSnapshotPreservesActualRowsAndOpaqueIds() {
        val card=metadata().put("available_episodes",3)
        val h=JSONObject().put("hero",JSONArray().put(card)).put("rows",JSONArray()
            .put(JSONObject().put("title","Popüler Diziler").put("items",JSONArray().put(card))))
        val rows=DunyaContract.home(h.toString())
        assertEquals(listOf("Öne Çıkanlar","Popüler Diziler"),rows.map { it.name })
        assertEquals("854352711757",rows[1].cards.single().id)
        val page=DunyaContract.page(JSONObject().put("data",JSONArray().put(card)).put("pages",4).toString(),3)
        assertTrue(page.hasNext);assertEquals("ornek-dizi",page.cards.single().slug)
    }
    @Test fun playbackBindsSourceFormatsAndSourceCaptionLanguages() {
        val j=JSONObject().put("url","/published/master.m3u8").put("type","hls").put("expires_at","2026-11-10T00:00:00Z")
            .put("altyazilar",JSONArray().put(JSONObject().put("dil","EN").put("src","https://captions.example/en.vtt"))
                .put(JSONObject().put("dil","TR").put("src","https://captions.example/tr.vtt")))
        val p=DunyaContract.playback(j)
        assertEquals("https://dramadunyam.com/published/master.m3u8",p.video);assertTrue(p.hls)
        assertEquals(listOf("tr","en"),p.captions.map { it.language });assertNotNull(p.expiresAt)
        assertTrue(runCatching { DunyaContract.playback(j.put("type","unknown")) }.isFailure)
    }
    @Test fun translationTargetsAreBlockedBeforeAnyFetch() {
        val translated="https://dramacix.com/api/altyazi.php?from=en&dil=tr"
        val encoded=Base64.getEncoder().encodeToString(translated.toByteArray())
        assertNull(DunyaContract.captionURL(translated))
        assertNull(DunyaContract.captionURL("https://dramadunyam.com/live.php?url=$encoded"))
        assertNull(DunyaContract.captionURL("https://dramadunyam.com/live.php?url=https%3A%2F%2Fcaptions.example%2Fa.vtt&url=x"))
        assertNotNull(DunyaContract.captionURL("https://captions.example/tr.vtt"))
    }
    @Test fun captionAliasesHaveNoNetworkFallbackAndNormalizeSrt() {
        val cache=CaptionBuffer();val text="1\n00:00:01,000 --> 00:00:02,000\nTürkçe örnek.\n"
        val bytes=CaptionBuffer.vtt(text)!!;assertTrue(bytes.toString(Charsets.UTF_8).startsWith("WEBVTT"))
        val first=cache.register(bytes)
        assertEquals(200,cache.response(Request.Builder().url(first).build())!!.use { it.code })
        repeat(12){cache.register(bytes)}
        assertEquals(410,cache.response(Request.Builder().url(first).build())!!.use { it.code })
        assertEquals(410,cache.response(Request.Builder().url(DunyaContract.BASE+"/.arcana-dramadunyam/subtitles/unknown.vtt").build())!!.use { it.code })
        cache.close()
    }
    @Test fun independentOpenSslWireFixtureDecodesPrefixAndPreservesRemainder() {
        val decoded=DunyaCipher.decode(resource("dummy-shortmax-fragment.bin"))
        assertArrayEquals(resource("dummy-shortmax-plain.bin"),decoded)
        assertNull(DunyaCipher.decode(resource("dummy-shortmax-plain.bin")))
    }
    @Test fun malformedPublishedCipherHeadersFailClosed() {
        val wrongOffset=resource("dummy-shortmax-fragment.bin").apply { "0001".toByteArray().copyInto(this,16) }
        val wrongLength=resource("dummy-shortmax-fragment.bin").apply { "0017".toByteArray().copyInto(this,20) }
        for(bytes in listOf(wrongOffset,wrongLength)) {
            val error=runCatching { DunyaCipher.decode(bytes) }.exceptionOrNull()
            assertTrue(error is IOException)
        }
    }
    @Test fun invalidatingSourceRoutesAreRejectedAcrossSemanticAliases() {
        val blocked=listOf(
            "https://dramadunyam.com/play/1/1?failed=1",
            "https://dramadunyam.com./pl%61y/1/1?ReChEcK=1",
            "https://www.dramadunyam.com/x/../%2570lay/1/1?%2566ailed=1",
            "https://dramadunyam.com/../play/1/1?failed[]=1",
            "https://dramadunyam.com//play/1/1?recheck=1",
            "https://dramadunyam.com/play/1/1?failed.code=1"
        )
        blocked.forEach { assertNull(it,DunyaContract.https(it)) }
        val ordinary="https://dramadunyam.com/play/854352711757/1"
        assertEquals(ordinary,DunyaContract.https(ordinary))
        val signed="https://media.example/segment.ts?sig=a%2Bb%2F%3D&expires=1234567890"
        assertEquals(signed,DunyaContract.https(signed))
    }
    @Test fun nestedInvalidationAndTranslationWrappersRemainReadOnly() {
        val target="https://dramadunyam.com/play/1/1?failed=1"
        val encoded=Base64.getEncoder().encodeToString(target.toByteArray())
        assertNull(DunyaContract.https("https://dramadunyam.com/relay?url=$encoded"))
        assertNull(DunyaContract.https("https://dramadunyam.com/%2561pi/%2561ltyazi.php?from=en&dil=tr"))
        assertNull(DunyaContract.https("https://dramadunyam.com/play/1/1?x%26failed%3D1=y"))
    }
    @Test fun knownHumanHtmlIsNotAConfigOrMediaSuccess() {
        assertTrue(DunyaContract.deniedHtml("<html><title>Just a moment</title><body>verify you are human</body></html>"))
        assertTrue(DunyaContract.deniedHtml("<html><title>CAPTCHA</title><body>Complete the captcha</body></html>"))
        assertFalse(DunyaContract.deniedHtml(links.replace("Örnek Dizi","Örnek Dizi complete the captcha")))
    }

    @Test fun publisherEmptySetBranchAndStructuralTelemetryAreExplicit() {
        val raw=metadata().put("bolum_no",JSONArray()).toString()
        val series=DunyaContract.series(raw,null,"ornek-dizi")
        assertEquals(listOf(1,2,3),series.chapters.map { it.number })
        assertEquals("PUBLISHER_DECLARED_AVAILABLE_RANGE",series.chapterEvidence)
        val explicit=DunyaContract.series(metadata().put("bolum_no",JSONArray().put(1).put(3).put(7)).toString(),null,"ornek-dizi")
        assertEquals(listOf(1,3,7),explicit.chapters.map { it.number })
        assertEquals("API_EXPLICIT_SET",explicit.chapterEvidence)
        val shape=DunyaContract.structure(raw,links,"ornek-dizi")
        assertEquals(3,JSONObject(shape).getInt("chapters"))
        assertFalse(shape.contains("images.example"));assertFalse(shape.contains("Örnek Dizi"))
    }

}
