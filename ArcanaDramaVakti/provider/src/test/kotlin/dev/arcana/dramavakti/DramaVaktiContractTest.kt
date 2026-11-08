package dev.arcana.dramavakti

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DramaVaktiContractTest {
    private val c=DramaVaktiContract
    private val url="https://dramavakti.com/dizi/sample/bolum-3"
    private fun ep()=c.chapter(url)!!
    @Test fun catalogueAndActualNextPageKeepRealCardFields() {
        val html="""<a class="browse-card" href="/dizi/sample"><span class="browse-art"><img src="/assets/posters/sample/poster.webp"></span><span class="browse-copy"><strong>Tam Başlık</strong><small>2026 · 59 bölüm</small></span></a>
        <a class="browse-card" href="https://foreign.invalid/dizi/a"><strong>Yabancı</strong></a>
        <a class="pagination-arrow disabled" href="/diziler?page=2">x</a>
        <a class="pagination-arrow" href="/diziler?audio=dubbed&amp;page=2">ileri</a>"""
        val page=c.listing(html,1);assertTrue(page.more);assertEquals(1,page.cards.size)
        val card=page.cards.single();assertEquals(59,card.count);assertEquals(2026,card.year);assertEquals("Tam Başlık",card.title)
        assertEquals("https://dramavakti.com/assets/posters/sample/poster.webp",card.poster)
        assertFalse(c.listing(html,2).more)
    }
    @Test fun quickApiUsesObservedSchemaAndQueryBinding() {
        val text="""{"ok":true,"term":"dahi","items":[{"title":"Dahi","url":"/dizi/sample","poster":"/assets/posters/sample/p.jpg","meta":"2026 · 59 bölüm","audio":"dubbed"}],"more_url":"/diziler?q=dahi"}"""
        val card=c.quickSearch(text,"dahi").single();assertEquals(59,card.count);assertEquals("Dahi",card.title)
        assertThrows(IllegalArgumentException::class.java) { c.quickSearch(text,"başka") }
    }
    @Test fun completeDomPlotRatingScaleAndActualChapterGapsArePreserved() {
        val html="""<meta property="og:url" content="https://dramavakti.com/dizi/sample"><meta property="og:image" content="/assets/posters/sample/p.jpg">
        <script type="application/ld+json">{"@graph":[{"@type":"TVSeries","name":"Başlık","description":"Kısa.","numberOfSeasons":1,"numberOfEpisodes":59,"genre":["Aksiyon"],"productionCompany":{"name":"StarDust"},"aggregateRating":{"ratingValue":84,"bestRating":100}}]}</script>
        <h1>Tam Başlık (Türkçe Dublaj)</h1><dl><dt>Yıl</dt><dd>2026</dd></dl><p class="series-synopsis">Kaynağın gerçekten tam açıklaması.</p>
        <a href="/dizi/sample/bolum-8">8</a><a href="/dizi/sample/bolum-3">3</a><a href="/dizi/other/bolum-1">related</a>"""
        val s=c.series(html,"sample");assertEquals(listOf(3,8),s.chapters.map { it.number })
        assertEquals("Kaynağın gerçekten tam açıklaması.",s.plot);assertEquals(2026,s.year)
        assertEquals(8.4,s.score!!,0.001);assertEquals(listOf("Aksiyon","StarDust"),s.tags)
        assertThrows(IllegalArgumentException::class.java) { c.series(html,"other") }
    }
    @Test fun opaquePayloadContainsOnlyStablePublisherIdentity() {
        val raw=c.encode(ep());assertEquals(ep(),c.decode(raw));assertNull(c.detailSlug(raw))
        assertFalse(raw.contains("media"));assertFalse(raw.contains("csrf"))
        assertNull(c.chapter("https://foreign.invalid/dizi/sample/bolum-3"))
        assertNull(c.chapter("https://dramavakti.com/dizi/sample/bolum-3?token=x"))
        assertNull(c.chapter("https://dramavakti.com/dizi/sample/bolum-0"))
    }
    @Test fun selectedWatchSurfaceAndSourceCaptionCannotFallBackToAnotherEpisode() {
        val html="""<main data-slug="sample"><section data-episode="1"><video data-hls-src="https://shows1.dramavakti.com/media/SYNTHETIC_OTHER" data-playback-kind="file"></video></section>
        <section data-episode="3"><video data-hls-src="https://shows1.dramavakti.com/media/SYNTHETIC_SELECTED" data-playback-kind="auto"><track kind="subtitles" srclang="tr_TR" src="/assets/subtitles/sample.vtt"><track kind="subtitles" srclang="en" src="/api/altyazi.php?episode=3"></video></section></main>"""
        val play=c.playback(html,ep());assertEquals("https://shows1.dramavakti.com/media/SYNTHETIC_SELECTED",play.url)
        assertNull(play.hls);assertEquals(listOf("tr"),play.captions.map { it.language })
        assertFalse(play.toString().contains("SYNTHETIC_SELECTED"))
        assertThrows(IllegalArgumentException::class.java) { c.playback(html.replace("data-slug=\"sample\"","data-slug=\"other\""),ep()) }
        assertThrows(IllegalArgumentException::class.java) { c.playback(html.replace("data-episode=\"3\"","data-episode=\"4\""),ep()) }
    }
    @Test fun publishedRetryAfterIntegerAndHttpDateAreNotLostOnReload() {
        val now=Instant.parse("2026-11-08T04:00:00Z").toEpochMilli()
        assertEquals(now+120000,c.retryUntil("120",now))
        assertEquals(now+600000,c.retryUntil("Sun, 8 Nov 2026 04:10:00 GMT",now))
        assertEquals(now+60000,c.retryUntil(null,now))
    }
}
