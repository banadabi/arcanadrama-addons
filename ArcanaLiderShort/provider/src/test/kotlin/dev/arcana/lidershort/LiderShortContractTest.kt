package dev.arcana.lidershort

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLEncoder
import java.util.Base64

class LiderShortContractTest {
    private val c = LiderShortContract.Chapter("ornek-dizi", 1)
    private fun flight(value: JSONObject, prefix: String = ""): String {
        val row = prefix + "a:" + value.toString() + "\n"
        return "<script>self.__next_f.push(" + JSONArray().put(1).put(row).toString() + ");</script>"
    }
    private fun source() = JSONObject().put("url", "/live.m3u8?exp=1&sig=fixture").put("isHls", true)
    private fun binding() = JSONObject().put("series", JSONObject().put("slug", c.slug))
        .put("episode", JSONObject().put("number", c.number)).put("locale", "tr").put("initialSource", source())
    @Test fun flightUtf8LengthRecordsDoNotSwallowEpisodeBinding() {
        val text = "Türkçe metin\nve yeni satır"
        val record = "1:T" + text.toByteArray().size.toString(16) + "," + text
        assertEquals("/live.m3u8?exp=1&sig=fixture", LiderShortContract.watchSource(flight(binding(), record), c)!!.getString("url"))
    }
    @Test fun episodeBindingRejectsOtherSeriesOrLanguageAndAmbiguity() {
        for (bad in listOf(binding().put("locale", "en"), binding().put("episode", JSONObject().put("number", 2)), binding().put("series", JSONObject().put("slug", "baska-dizi")))) {
            assertTrue(runCatching { LiderShortContract.watchSource(flight(bad), c) }.isFailure)
        }
        assertTrue(runCatching { LiderShortContract.watchSource(flight(binding()) + flight(binding()), c) }.isFailure)
        assertNull(LiderShortContract.watchSource(flight(binding().put("initialSource", JSONObject.NULL)), c))
    }
    @Test fun chapterPayloadRejectsForeignPathsAndPreservesStableIdentity() {
        assertEquals(c, LiderShortContract.decode(LiderShortContract.encode(c)))
        assertNull(LiderShortContract.detailSlug(LiderShortContract.encode(c)))
        assertNull(LiderShortContract.chapter("https://outside.example/tr/videos/ornek-dizi/bolum-1"))
        assertNull(LiderShortContract.decode("{\"v\":1,\"slug\":\"../bad\",\"episode\":1}"))
        assertNull(LiderShortContract.decode("{\"v\":1,\"slug\":\"ornek-dizi\",\"episode\":\"1\"}"))
    }
    @Test fun detailUsesOnlyActualSameSeriesChaptersWithoutFillingGaps() {
        val ld = JSONObject().put("@type", "TVSeries").put("url", "${LiderShortContract.BASE}/tr/videos/ornek-dizi")
            .put("description", "Tam açıklama").put("image", JSONArray().put("https://cdn.example/cover.jpg"))
        val html = "<h1>Örnek Dizi</h1><script type='application/ld+json'>$ld</script>" +
            "<a href='/tr/videos/ornek-dizi/bolum-3'>3</a><a href='/tr/videos/ornek-dizi/bolum-1'>1</a>" +
            "<a href='/tr/videos/baska/bolum-2'>2</a><a href='bad value'>ignore</a>"
        val series = LiderShortContract.series(html, "ornek-dizi")
        assertEquals(listOf(1, 3), series.chapters.map { it.number })
        assertEquals("Tam açıklama", series.plot)
    }
    @Test fun homepageScopesRealRowsAndDeduplicatesCards() {
        val card = "<article><a href='/tr/videos/ornek-dizi/bolum-1'><img src='/cover.jpg'></a><h3>Örnek Dizi</h3><span>35 bölüm</span></article>"
        val html = "<section aria-labelledby='row-yeni'><h2>Yeni Çıkanlar</h2>$card$card</section><section>$card</section>"
        val rows = LiderShortContract.home(html)
        assertEquals(1, rows.size); assertEquals(1, rows.single().cards.size)
        assertEquals(35, rows.single().cards.single().count)
    }
    @Test fun sourceTranslationEndpointsAreNotCaptionFiles() {
        val tracks = JSONArray().put(JSONObject().put("lang", "tr").put("url", "https://dramacix.com/api/altyazi.php?url=x&dil=tr&from=en"))
            .put(JSONObject().put("lang", "trv").put("url", "/track.vtt"))
            .put(JSONObject().put("lang", "tr-TR").put("url", "/live.php?url=https%3A%2F%2Fcdn.example%2Ftr.vtt&exp=1&sig=fixture&t=vtt"))
        val result = LiderShortContract.playback(source().put("subtitles", tracks))
        assertEquals(listOf("tr", "trv"), result.captions.map { it.language })
        assertFalse(result.captions.any { "altyazi.php" in it.url })
        assertTrue(runCatching { LiderShortContract.playback(source().put("isHls", "true")) }.isFailure)
    }
    @Test fun timedCaptionsNeedRealCueTextAndSourceRetryDatesAreRespected() {
        assertTrue(LiderShortContract.timedText("WEBVTT\n\n00:01.000 --> 00:02.000\nTürkçe metin\n"))
        assertFalse(LiderShortContract.timedText("<html>00:01.000 --> 00:02.000\nNot captions</html>"))
        assertFalse(LiderShortContract.timedText("WEBVTT\n\n00:01.000 --> 00:02.000\n1234\n"))
        assertEquals(1_060_000L, LiderShortContract.retryUntil("60", 1_000_000L))
        assertEquals(4_070_908_800_000L, LiderShortContract.retryUntil("Thu, 01 Jan 2099 00:00:00 GMT", 1_000_000L))
    }
    @Test fun nestedCaptionPolicyRejectsMixedSchemesAndAmbiguousTargets() {
        for (scheme in listOf("http", "HTTPS")) {
            val inner = "$scheme://dramacix.com/api/altyazi.php?url=x&dil=tr"
            val encoded = URLEncoder.encode(inner, "UTF-8")
            assertNull(LiderShortContract.captionURL("/live.php?url=$encoded&sig=fixture"))
            val base64 = URLEncoder.encode(Base64.getEncoder().encodeToString(inner.toByteArray()), "UTF-8")
            assertNull(LiderShortContract.captionURL("/live.php?url=$base64&sig=fixture"))
        }
        assertNull(LiderShortContract.captionURL("/live.php?url=https%3A%2F%2Fcdn.example%2Ftr.vtt&%75rl=https%3A%2F%2Fcdn.example%2Fother.vtt"))
        assertNull(LiderShortContract.captionURL("/live.php?url=unrecognised"))
        assertNull(LiderShortContract.captionURL("https://dramacix.com/api/altyazi.php?dil=tr"))
    }
    @Test fun readyCaptionsNeverUseNetworkAndEvictionFailsClosed() {
        val buffer = CaptionBuffer()
        val vtt = CaptionBuffer.vtt("1\n00:00:01,000 --> 00:00:02,000\nGerçek metin\n")!!
        val url = buffer.register(vtt)
        val client = OkHttpClient.Builder().dns { error("No caption DNS or network allowed") }.addInterceptor(buffer.interceptor).build()
        client.newCall(Request.Builder().url(url).build()).execute().use {
            assertEquals(200, it.code)
            assertTrue(it.body.string().startsWith("WEBVTT\n\n1\n00:00:01.000 -->"))
        }
        repeat(12) { buffer.register(vtt) }
        client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(410, it.code) }
        buffer.close()
    }
    @Test fun presentationTagDoesNotChangePublisherSignatureQuery() {
        val buffer = CaptionBuffer()
        val original = "https://cdn.example/video.mp4?auth_key=fixture%2Bvalue&exp=123"
        val tagged = buffer.tagged(original)
        assertTrue(buffer.owns(tagged))
        val client = OkHttpClient.Builder().addInterceptor(buffer.interceptor).addInterceptor { chain ->
            assertEquals(original, chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(byteArrayOf().toResponseBody(null)).build()
        }.build()
        client.newCall(Request.Builder().url(tagged).build()).execute().use { assertEquals(200, it.code) }
        buffer.close()
    }
    @Test fun sameSeriesWatchRedirectUsesActualFlightMetadataAndChapters() {
        val values = JSONObject().put("slug", c.slug).put("title", JSONObject().put("tr", "Gerçek Dizi"))
            .put("synopsis", JSONObject().put("tr", "Tam kaynak açıklaması"))
            .put("poster", "https://cdn.example/cover.jpg")
            .put("episodes", JSONArray().put(JSONObject().put("number", 3)).put(JSONObject().put("number", 1)))
        val html = flight(binding().put("series", values))
        val s = LiderShortContract.series(html, c.slug)
        assertEquals("Gerçek Dizi", s.title)
        assertEquals("Tam kaynak açıklaması", s.plot)
        assertEquals(listOf(1, 3), s.chapters.map { it.number })
        assertTrue(LiderShortContract.sameSeriesPage(c.url, c.slug))
        assertFalse(LiderShortContract.sameSeriesPage(c.url.replace(c.slug, "baska"), c.slug))
    }
}
