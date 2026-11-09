package dev.arcana.verticalshorts

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class VerticalContractTest {
    private fun flight(vararg values: JSONObject): String {
        val data = values.mapIndexed { i, v -> "${i.toString(16)}:$v\n".replace("\\n", "\n") }.joinToString("")
        return "<script>self.__next_f.push([1," + JSONObject.quote(data) + "])</script>"
    }
    private fun episode(number: Int, locked: Boolean = false) = JSONObject().put("number", number).put("id", JSONObject.NULL).put("locked", locked).put("title", "Episode $number")
    private fun series() = JSONObject().put("platform", "goodshort").put("dramaId", "31001799154").put("coded", false)
        .put("title", "Alpha King Claims His Stolen Omega").put("description", "Full source description.").put("cover", "https://images.example/cover.jpg")
        .put("platformName", "GoodShort").put("lang", "tr").put("tags", JSONArray().put(JSONObject().put("id", "alpha").put("label", "Alpha")))
        .put("episodes", JSONArray().put(episode(1)).put(episode(3, true)))
    @Test fun routingKeepsOpaqueLongIdsAndPublishedCodedRepresentation() {
        assertEquals("https://verticalshorts.ai/tr/drama/dramaseries/31001799154/alpha-king-claims-his-stolen-omega", VerticalContract.seriesUrl("goodshort", "31001799154", false, "Alpha King Claims His Stolen Omega"))
        assertEquals("https://verticalshorts.ai/tr/watch/dramaseries/c7-42000031534/2", VerticalContract.watchUrl("dramaboxv2", "42000031534", true, 2))
        assertTrue(VerticalContract.watchUrl("melolo", "1762500192313418667", false, 1).contains("1762500192313418667"))
        assertEquals("kilolu-tavsanın-donusu", VerticalContract.titleSlug("Kilolu Tavşanın Dönüşü"))
    }
    @Test fun explicitEpisodeGapsAndLocksAreNotFilledOrUnlocked() {
        val url = VerticalContract.seriesUrl("goodshort", "31001799154", false, "Alpha King Claims His Stolen Omega")
        val s = VerticalContract.series(flight(series()), url)
        assertEquals(listOf(1, 3), s.chapters.map { it.number }); assertTrue(s.chapters.last().locked)
        assertNull(s.chapters.first().episodeId); assertEquals(listOf("Alpha", "GoodShort"), s.tags)
        assertEquals("Full source description.", s.plot)
        assertEquals(s.chapters.first(), VerticalContract.decode(VerticalContract.encode(s.chapters.first())))
        assertFalse(VerticalContract.detailUrl(VerticalContract.encode(s.chapters.first())))
    }
    @Test fun firstWatchCannotChooseARecommendationFromAnotherSeries() {
        val detail = "<a href='/tr/watch/dramaseries/other/1'>other</a><a href='/tr/watch/dramaseries/31001799154/3'>3</a><a href='/tr/watch/dramaseries/31001799154/1'>1</a>"
        val expected = VerticalContract.seriesUrl("goodshort", "31001799154", false, "Alpha King Claims His Stolen Omega")
        assertEquals("https://verticalshorts.ai/tr/watch/dramaseries/31001799154/1", VerticalContract.firstWatch(detail, expected))
    }
    @Test fun richSearchAndHomeReuseOnlyPublishedCardData() {
        val card = JSONObject().put("platform", "goodshort").put("id", "31001799154").put("coded", false).put("title", "Example").put("cover", "https://images.example/cover.jpg").put("episodes", 50)
        val result = VerticalContract.searchHtml(flight(JSONObject().put("items", JSONArray().put(card))))
        assertEquals("https://images.example/cover.jpg", result.single().poster); assertEquals(50, result.single().count)
        val home = VerticalContract.home(flight(JSONObject().put("label", "Hot Picks").put("items", JSONArray().put(card))))
        assertEquals("Hot Picks", home.single().name)
    }
    @Test fun sourceResponseMustEchoCurrentIdentityAndKeepIssuedSignedBytes() {
        val c = VerticalContract.Chapter("goodshort", "31001799154", false, 1, null, "tr", false)
        val signed = "https://media.example/video.mp4?token=a%2Bb%3D&t=1"
        val j = JSONObject().put("platform", c.platform).put("dramaId", c.id).put("episode", 1).put("type", "mp4").put("url", signed)
            .put("subtitles", JSONArray().put(JSONObject().put("lang", "en").put("url", "https://captions.example/one.vtt")))
        val p = VerticalContract.playback(j.toString(), c); assertEquals(signed, p.video); assertEquals("en", p.captions.single().language)
        assertTrue(runCatching { VerticalContract.playback(j.put("episode", 3).toString(), c) }.isFailure)
        assertTrue(runCatching { VerticalContract.series(flight(series()), VerticalContract.seriesUrl("goodshort", "other", false, "Other")) }.isFailure)
    }
    @Test fun automaticMutationAliasesAndTranslationWrappersAreRefused() {
        assertNull(VerticalContract.https("/api/site/play?platform=goodshort&id=x&ep=1&lang=tr&%66resh=1"))
        assertNull(VerticalContract.https("https://verticalshorts.ai./api/x/../site/play?failed=1"))
        assertNull(VerticalContract.captionURL("https://other.example/proxy?url=https%3A%2F%2Fx.example%2Fapi%2Faltyazi.php"))
        assertTrue(VerticalContract.timedText("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello world.\n"))
    }
    @Test fun browserResourcesKeepIssuedHttpsReadScopeAndCannotGrantMainNavigationOrMutation() {
        assertEquals("media.example", VerticalContract.browserResourceHost("https://media.example/video.mp4?token=a%2Bb%3D", "GET", false))
        assertEquals("cdn.example", VerticalContract.browserResourceHost("https://cdn.example/stream/1.m3u8", "HEAD", false))
        assertNull(VerticalContract.browserResourceHost("https://cdn.example/video.mp4", "POST", false))
        assertNull(VerticalContract.browserResourceHost("https://cdn.example/video.mp4", "GET", true))
        assertNull(VerticalContract.browserResourceHost("http://cdn.example/video.mp4", "GET", false))
        assertNull(VerticalContract.browserResourceHost("https://user:secret@cdn.example/video.mp4", "GET", false))
        assertNull(VerticalContract.browserResourceHost("https://cdn.example:444/video.mp4", "GET", false))
        assertNull(VerticalContract.browserResourceHost("/relative/video.mp4", "GET", false))
        assertNull(VerticalContract.browserResourceHost("https://verticalshorts.ai/api/site/play?platform=x&id=x&ep=1&lang=tr&fresh=1", "GET", false))
        assertNull(VerticalContract.browserResourceHost("https://other.example/proxy?url=https%3A%2F%2Fx.example%2Fapi%2Faltyazi.php", "GET", false))
    }
    @Test fun explicitBodyDenialDoesNotBecomeAUsableOrRetryableVideo() {
        assertEquals(VerticalContract.Denial.ACCESS, VerticalContract.jsonDenial("""{"error":"forbidden","message":"This API is only for the website itself."}"""))
        assertEquals(VerticalContract.Denial.RATE, VerticalContract.jsonDenial("""{"error":"rate_limited"}"""))
        assertNull(VerticalContract.jsonDenial("""{"title":"Forbidden Love","url":"https://media.example/video.mp4"}"""))
    }
    @Test fun currentDocumentIdentityUsesExactOriginSeriesAndOrdinal() {
        val chapter = VerticalContract.Chapter("goodshort", "31001799154", false, 1, null, "tr", false)
        val clientLocation = java.net.URI(chapter.url + "?from=watch#position")
        assertTrue(VerticalContract.sameWatchDocument(VerticalContract.BASE, clientLocation.rawPath, chapter))
        assertFalse(VerticalContract.sameWatchDocument("https://other.example", clientLocation.rawPath, chapter))
        assertFalse(VerticalContract.sameWatchDocument("http://verticalshorts.ai", clientLocation.rawPath, chapter))
        assertFalse(VerticalContract.sameWatchDocument(VerticalContract.BASE, clientLocation.rawPath.removeSuffix("1") + "2", chapter))
        assertFalse(VerticalContract.sameWatchDocument(VerticalContract.BASE, "/tr/watch/dramaseries/other/1", chapter))
        assertFalse(VerticalContract.sameWatchDocument(VerticalContract.BASE, "/tr", chapter))
        // Metadata/address validation stays strict; the authoritative current document is separate.
        assertFalse(VerticalContract.sameSeriesPage(clientLocation.toString(), chapter.platform, chapter.id, chapter.coded, chapter.number))
    }
    @Test fun diagnosticStateCannotExportSourceValuesOrIdentity() {
        val marker = "NEVER_EXPORT_SOURCE_VALUE"
        val body = JSONObject().put("url", "https://media.example/$marker?token=$marker").put("platform", marker).put("dramaId", marker).put("episode", marker).put("type", marker)
        val observer = JSONObject().put("selection", marker).put("latest", 1).put("seenPlay", 2)
            .put("result", JSONObject().put("status", 200).put("body", body.toString()))
        val state = JSONObject().put("origin", VerticalContract.BASE).put("path", "/private-path-$marker").put("doc", marker)
            .put("guardPresent", true).put("observer", observer).put("videoCount", 1).put("videoSource", true)
        val summary = VerticalContract.diagnosticSummary(state, "/private-path-$marker")
        assertFalse(summary.contains(marker)); assertFalse(summary.contains("https://")); assertFalse(summary.contains("private-path"))
        val parsed = JSONObject(summary); assertTrue(parsed.getBoolean("path_ok")); assertTrue(parsed.getBoolean("body_url_present"))
        assertEquals(200, parsed.getInt("status")); assertEquals("other", parsed.getString("body_type")); assertEquals("none", parsed.getString("selection"))
    }
    private fun observerCase(name: String) {
        val dir = Files.createTempDirectory(Path.of("build"), "observer-")
        try {
            val script = dir.resolve("observe.js")
            Files.writeString(script, VerticalObserver.script("ownstate", "goodshort", "31001799154", 1, "tr", null))
            val fixture = javaClass.getResourceAsStream("/observer-fixture.cjs")!!.readBytes()
            val runner = dir.resolve("runner.cjs"); Files.write(runner, fixture)
            val p = ProcessBuilder("node", runner.toString(), script.toString(), name).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText(); assertEquals(output, 0, p.waitFor())
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun observerPreservesOriginalRequestPromiseAndResponse() = observerCase("passthrough")
    @Test fun observerRefusesRedirectOrDuplicateIdentity() = observerCase("identity")
    @Test fun observerDenialCannotBeOverwrittenByConcurrentSuccess() = observerCase("denial")
    @Test fun observerBodyLevelAccessDenialIsTerminal() = observerCase("body-denial")

}
