package dev.arcana.liderdrama

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

/** Captured structure, synthetic media/grants, and the observed public issuer origin. No requests. */
class LiderRelayContractTest {
    private fun encoded(value: String) = URLEncoder.encode(
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)), "UTF-8"
    )

    private fun response(): JSONObject {
        val relay = "https://liderdrama.com/live.php?url=" +
            encoded("https://media.example.invalid/first.m3u8") +
            "&ref=" + encoded("https://m.mydramawave.com/") +
            "&exp=9999999999&sig=" + "0".repeat(64) + "&t=m3u8&ext=.m3u8"
        return JSONObject().put("url", relay).put(
            "subs", JSONArray().put(JSONObject().put("lang", "tr").put("format", "vtt")
                .put("url", "/subs/fixture-relay/1.vtt"))
        )
    }

    @Test fun absentTopTypeUsesTheIssuedRelayDescriptor() {
        val playback = LiderContract.playback(response())
        assertEquals("hls", playback.type)
        assertEquals("https://media.example.invalid/first.m3u8", playback.url)
        assertEquals("https://m.mydramawave.com/", playback.referer)
    }

    @Test fun incompleteOrConflictingRelayDescriptorsNeverGuessAType() {
        val source = response()
        for (url in listOf(
            source.getString("url").replace("&ext=.m3u8", "&ext=.mp4"),
            source.getString("url") + "&ext=.m3u8",
            source.getString("url").replace("liderdrama.com/live.php", "media.example.invalid/live.php"),
            source.getString("url").replace("&exp=9999999999", "&exp=1")
        )) assertThrows(Exception::class.java) { LiderContract.playback(JSONObject(source.toString()).put("url", url)) }
        assertThrows(Exception::class.java) { LiderContract.playback(source.put("t", "unsupported")) }
    }

    @Test fun directApiCaptionIsBoundToTheRequestedEpisodeBeforeNativePreparation() {
        val json = JSONObject().put("url", "https://media.example.invalid/video.m3u8").put("t", "hls")
            .put("subs", JSONArray().put(JSONObject().put("lang", "TR").put("format", "vtt")
                .put("url", "https://media.example.invalid/captions/first.vtt")))
        val request = LiderContract.Payload(42, "fixture-relay", 101, 1)
        val caption = LiderContract.playback(json, request).captions.single()
        assertEquals(LiderContract.CaptionTransport.ISSUED_RESOURCE, caption.transport)
        assertEquals(caption.url, LiderAutomaticCaptionPolicy.issuedTarget(caption, request.slug, 1))
        assertThrows(Exception::class.java) { LiderAutomaticCaptionPolicy.issuedTarget(caption, request.slug, 2) }
        val unbound = LiderContract.playback(json).captions.single()
        assertThrows(Exception::class.java) { LiderAutomaticCaptionPolicy.issuedTarget(unbound, request.slug, 1) }
    }

    @Test fun issuedSrtIsDecodedOnceAndTheExistingOwnVttPathIsPreserved() {
        val json = response()
        val captionRelay = "https://liderdrama.com/live.php?url=" +
            encoded("https://media.example.invalid/captions/first.srt") +
            "&ref=" + encoded("https://m.mydramawave.com/") +
            "&exp=9999999999&sig=" + "0".repeat(64)
        json.put("subs", JSONArray().put(JSONObject().put("lang", "TR").put("format", "srt")
            .put("url", captionRelay)))
        val request = LiderContract.Payload(42, "fixture-relay", 101, 1)
        val srt = LiderContract.playback(json, request).captions.single()
        assertEquals("https://media.example.invalid/captions/first.srt", srt.url)
        assertEquals("https://m.mydramawave.com/", srt.referer)
        assertEquals(srt.url, LiderAutomaticCaptionPolicy.issuedTarget(srt, request.slug, 1))
        val staticVtt = LiderContract.playback(response(), request).captions.single()
        assertEquals(LiderContract.CaptionTransport.SOURCE_DOCUMENT, staticVtt.transport)
        assertTrue(LiderAutomaticCaptionPolicy.target(staticVtt.url, request.slug, 1).endsWith("/1.vtt"))
    }

    @Test fun nestedTargetsAndUnobservedRefererOriginsStayClosed() {
        val original = response()
        val current = original.getString("url")
        val source = encoded("https://media.example.invalid/first.m3u8")
        for (nested in listOf(
            "https://media.example.invalid/proxy.m3u8?url=" + encoded("https://media.example.invalid/final.m3u8"),
            "https://media.example.invalid/proxy.m3u8?%2574arget=" + encoded("https://media.example.invalid/final.m3u8"),
            "https://media.example.invalid/%6cive.php/first.m3u8"
        )) assertThrows(Exception::class.java) {
            LiderContract.playback(JSONObject(original.toString()).put("url", current.replace(source, encoded(nested))))
        }
        for (ref in listOf("https://127.0.0.1/", "https://unobserved.example.invalid/",
            "https://m.mydramawave.com/private/path")) {
            assertThrows(Exception::class.java) {
                LiderContract.playback(JSONObject(original.toString()).put("url",
                    current.replace(encoded("https://m.mydramawave.com/"), encoded(ref))))
            }
        }
    }
}
