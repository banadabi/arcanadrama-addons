package dev.arcana.dramatr

import org.junit.Assert.*
import org.junit.Test

class DramaTRContractTest {
    @Test fun actualChapterLinksKeepGapsEditionsAndSeriesIdentity() {
        val html="""<script type="application/ld+json">{"@type":"TVSeries","url":"https://dramatr.com/dramaizle/ornek-dublajli","name":"Örnek (Dublajlı)","description":"Tam açıklama","numberOfEpisodes":50,"datePublished":"2026-10-27","genre":["Romantik"],"productionCompany":{"name":"ReelShort"}}</script>
        <a class="sd-ecard" href="/dramaizle/ornek-dublajli-bolum-3">3</a><a class="sd-ecard" href="/dramaizle/ornek-dublajli-bolum-1">1</a>
        <a class="sd-ecard" href="/dramaizle/other-bolum-2">Related</a><a data-honeypot="1" href="/api/episode?bulk=all">.</a>"""
        val s=DramaTRContract.series(html,"ornek-dublajli")
        assertEquals(listOf(1,3),s.chapters.map { it.number })
        assertEquals("Örnek (Dublajlı)",s.title);assertEquals(listOf("Romantik","ReelShort"),s.tags);assertNull(s.poster)
        assertThrows(Exception::class.java) { DramaTRContract.series(html,"other") }
    }
    @Test fun stablePayloadRejectsForeignAndNonChapterRoutes() {
        val c=DramaTRContract.chapter("https://dramatr.com/dramaizle/ornek-bolum-2")!!
        assertEquals(c,DramaTRContract.decode(DramaTRContract.encode(c)))
        assertNull(DramaTRContract.detailSlug(DramaTRContract.encode(c)))
        assertNull(DramaTRContract.detailSlug(c.url))
        assertNull(DramaTRContract.chapter("https://other.example.invalid/dramaizle/ornek-bolum-2"))
        assertNull(DramaTRContract.chapter(c.url+"?ep=3"))
    }
    @Test fun playbackUsesChosenSourceNeverRawOriginAndChecksEpisode() {
        val c=DramaTRContract.Chapter("ornek",2)
        val raw="""{"ok":true,"series_slug":"ornek","number":2,"episode_id":108825,"error":null,"sources":[{"url":"https://cdn.dramatr.com/test.m3u8","type":"hls","raw_url":"https://unselected.example.invalid/raw.mp4"}],"subtitles":[{"url":"https://cdn.dramatr.com/test.vtt","lang":"tr"}]}"""
        val p=DramaTRContract.playback(raw,c)
        assertEquals("https://cdn.dramatr.com/test.m3u8",p.videos.single().url)
        assertEquals("hls",p.videos.single().type);assertEquals("tr",p.captions.single().language)
        assertEquals("tr",DramaTRContract.playback(raw.replace("\"lang\":\"tr\"","\"lang\":\"tr_TR\""),c).captions.single().language)
        assertEquals("pt-BR",DramaTRContract.playback(raw.replace("\"lang\":\"tr\"","\"lang\":\"pt-BR\""),c).captions.single().language)
        assertThrows(Exception::class.java) { DramaTRContract.playback(raw,c.copy(number=3)) }
        assertThrows(Exception::class.java) { DramaTRContract.playback(raw.replace("\"ok\":true","\"ok\":false"),c) }
    }
    @Test fun catalogueHasMoreAndFailureRemainDistinct() {
        val raw="""{"ok":true,"has_more":true,"items":[{"title":"Bir","url":"https://dramatr.com/dramaizle/bir","poster":"/uploads/one.webp","episode_count":50},{"title":"Bir (Dublajlı)","url":"https://dramatr.com/dramaizle/bir-dublajli","episode_count":48}]}"""
        val l=DramaTRContract.listing(raw);assertTrue(l.more);assertEquals(2,l.cards.size)
        assertThrows(Exception::class.java) { DramaTRContract.listing("""{"ok":false,"items":[]}""") }
        assertFalse(DramaTRContract.listing("""{"ok":true,"has_more":false,"items":[]}""").more)
    }
}
