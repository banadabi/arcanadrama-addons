package dev.arcana.anyshort

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnyContractTest {
    private val title=AnyContract.Title("174825866","balikci-baba-hesap-soruyor")
    private val chapter=AnyContract.Chapter(title,"36814193",1)
    @Test fun episodePayloadIsStableAndNotADetailRoute(){
        val encoded=AnyContract.encode(chapter)
        assertEquals(chapter,AnyContract.decode(encoded));assertNull(AnyContract.identity(encoded))
        assertEquals(title,AnyContract.identity(title.url));assertNull(AnyContract.identity(title.url+"/watch?ep=1"))
        assertNull(AnyContract.identity("https://anyshort.net.evil/tr/film/balikci-baba-hesap-soruyor-174825866"))
        assertNull(AnyContract.decode(encoded.replace("\"number\":1","\"number\":0")))
    }
    @Test fun trailerGapsAndLocksAreNotInventedIntoPlayableChapters(){
        val raw="""{"data":[{"episode_id":1,"episode_no":0,"locked":false,"has_streams":true},{"episode_id":2,"episode_no":1,"locked":false,"has_streams":true},{"episode_id":3,"episode_no":3,"locked":false,"has_streams":true},{"episode_id":4,"episode_no":4,"locked":true,"has_streams":true},{"episode_id":5,"episode_no":5,"locked":false,"has_streams":false}],"page":{"offset":0,"has_more":false}}"""
        val p=AnyContract.episodes(JSONObject(raw),title,0)
        assertEquals(listOf(1,3),p.chapters.map { it.number });assertEquals(5,p.received);assertFalse(p.hasMore)
        assertThrows(IllegalArgumentException::class.java){AnyContract.episodes(JSONObject(raw),title,60)}
    }
    @Test fun duplicateEpisodeIdentityFails(){
        val raw="""{"data":[{"episode_id":2,"episode_no":1,"locked":false,"has_streams":true},{"episode_id":2,"episode_no":2,"locked":false,"has_streams":true}],"page":{"offset":0,"has_more":false}}"""
        assertThrows(IllegalArgumentException::class.java){AnyContract.episodes(JSONObject(raw),title,0)}
    }
    private fun playback()=JSONObject("""{"data":{"title_id":174825866,"episode_id":36814193,"episode_no":1,"access":"free","streams":[{"protocol":"mp4","codec":"h264","quality":"1080p","url":"https://video.example.invalid/opaque?auth_key=fixture-only","stale":false,"expire_at":"2030-01-01T00:00:00Z"}]}}""")
    @Test fun playRequiresExactIdentityFreeAccessAndFreshActualH264Stream(){
        val j=playback();val now=1_000L
        val s=AnyContract.playback(j,chapter,now).single();assertFalse(s.hls);assertEquals(1080,s.quality);assertFalse(s.toString().contains("fixture-only"))
        j.getJSONObject("data").put("episode_no",2)
        assertThrows(IllegalArgumentException::class.java){AnyContract.playback(j,chapter,now)}
        j.getJSONObject("data").put("episode_no",1).put("access","locked")
        assertThrows(IllegalArgumentException::class.java){AnyContract.playback(j,chapter,now)}
        val stale=playback();stale.getJSONObject("data").getJSONArray("streams").getJSONObject(0).put("stale",true)
        assertThrows(IllegalArgumentException::class.java){AnyContract.playback(stale,chapter,now)}
    }
    @Test fun sourceUtcOffsetExpiryMatchesZAndPreservesNonUtcOffset(){
        val a=playback();val b=playback();val c=playback()
        a.getJSONObject("data").getJSONArray("streams").getJSONObject(0).put("expire_at","2030-01-01T00:00:00+00:00")
        b.getJSONObject("data").getJSONArray("streams").getJSONObject(0).put("expire_at","2030-01-01T03:00:00+03:00")
        val expected=AnyContract.playback(c,chapter,1000).single().expiresAt
        assertEquals(expected,AnyContract.playback(a,chapter,1000).single().expiresAt)
        assertEquals(expected,AnyContract.playback(b,chapter,1000).single().expiresAt)
    }
    @Test fun catalogueUsesOpaqueServerCursorAndLocalizedRailLabel(){
        val card="""{"title_id":174825866,"slug":"balikci-baba-hesap-soruyor","name":"Balıkçı Baba","total_episodes":31}"""
        val cursor="eyJvZmZzZXQiOjE4fQ=="
        val p=AnyContract.page(JSONObject("""{"data":[$card],"page":{"next":"$cursor"}}"""))
        assertEquals(cursor,p.next);assertEquals(title,p.cards.single().identity)
        val rows=AnyContract.home(JSONObject("""{"data":{"rails":[{"title":"Mới Cập Nhật","title_key":"rail_newest","kind":"auto","ref":"newest","items":[$card]}]}}"""))
        assertEquals("Son Güncellenenler",rows.single().name)
    }
    @Test fun subtitlesAreRealFreshTracksAndRequireActualTimedText(){
        val j=JSONObject("""{"data":[{"language_code":"tr-TR","format":"webvtt","url":"https://video.example.invalid/caption?auth_key=fixture-only","stale":false,"expire_at":"2030-01-01T00:00:00Z"}]}""")
        assertEquals("tr",AnyContract.subtitles(j,1_000).single().language)
        assertNull(CaptionBuffer.vtt("WEBVTT\n\n"));assertNull(CaptionBuffer.vtt("<html>captcha</html>"))
        assertNotNull(CaptionBuffer.vtt("WEBVTT\n\n00:00.000 --> 00:01.000\nMerhaba dünya.\n"))
    }
    @Test fun successfulHttpBodyDoesNotHideKnownSourceDenials(){
        assertEquals(AnyContract.Denial.ACCESS,AnyContract.denial("""{"error":{"code":"forbidden"}}"""))
        assertEquals(AnyContract.Denial.LIMIT,AnyContract.denial("""{"error":{"code":"rate_limited"}}"""))
        assertEquals(AnyContract.Denial.AUTH,AnyContract.denial("""{"error":{"code":"token_expired"}}"""))
        assertEquals(AnyContract.Denial.CHALLENGE,AnyContract.denial("""{"error":{"code":"challenge_required"}}"""))
        assertEquals(AnyContract.Denial.NONE,AnyContract.denial("""{"data":{"name":"Forbidden"}}"""))
    }
    @Test fun sourceSearchSettingsDoNotAcceptAnEpisodeAsAQuery(){
        assertEquals("Hesap",AnyContract.sourceQuery("""{"q":"Hesap"}"""))
        assertThrows(IllegalArgumentException::class.java){AnyContract.sourceQuery("""{"q":""}""")}
        assertThrows(IllegalStateException::class.java){AnyContract.sourceQuery(AnyContract.encode(chapter))}
    }
    @Test fun bearerEndpointsAreOnlyTheObservedReadOnlyGuestApi(){
        assertTrue(AnyContract.apiAllowed("/episodes/36814193/play",setOf("lang","sub_lang")))
        assertFalse(AnyContract.apiAllowed("/auth/session",emptySet()))
        assertFalse(AnyContract.apiAllowed("/episodes/36814193/play",setOf("refresh")))
        assertFalse(AnyContract.apiAllowed("/titles/../logout",emptySet()))
        assertFalse(AnyContract.apiAllowed("https://video.example.invalid/stream",emptySet()))
        assertNull(AnyContract.mediaUrl("https://anyshort.net/v1/episodes/36814193/play"))
        assertNull(AnyContract.mediaUrl("https://anyshort.net./v1/episodes/36814193/play"))
        assertNull(AnyContract.mediaUrl("https://anyshort.net/%2576%2531/episodes/36814193/play"))
        assertNull(AnyContract.mediaUrl("https://video.example.invalid/proxy?url=https%3A%2F%2Fanyshort.net%2Fv1%2Fepisodes%2F36814193%2Fplay"))
        assertNull(AnyContract.mediaUrl("https://anyshort.net/%61uth/logout"))
        assertNull(AnyContract.mediaUrl("https://anyshort.net/%252earcana-anyshort/subtitles/x.vtt"))
        assertNull(AnyContract.mediaUrl("https://video.example.invalid/proxy?url=https%3A%2F%2Fanyshort.net%2F%25252earcana-anyshort%2Fsubtitles%2Fx.vtt"))
        assertNull(AnyContract.https("http://video.example.invalid/stream"))
    }
}
