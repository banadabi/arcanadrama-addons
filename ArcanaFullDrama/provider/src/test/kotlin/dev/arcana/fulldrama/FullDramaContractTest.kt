package dev.arcana.fulldrama

import org.junit.Assert.*
import org.junit.Test

class FullDramaContractTest {
    @Test fun cataloguePreservesDistinctEditionsAndMixedRatingRepresentations() {
        val rows = FullDramaContract.catalogue("""[
          {"id":2,"title":"\u0130lk Dizi","slug":"ilk-dizi-2","poster":"https://fulldrama.xyz/assets/cache_img/new.webp","imdb_score":"7.5","is_premium":0,"type":"series","source_type":"dramawave_v2"},
          {"id":1,"title":"İlk Dizi(Dublajlı)","slug":"ilk-dizi-dublajli-1","poster":"https://fulldrama.xyz/assets/cache_img/new.webp","imdb_score":7.3,"is_premium":1,"type":"series","source_type":"dramawave_v2"}
        ]""")
        assertEquals(2, rows.size); assertEquals("İlk Dizi",rows[0].title)
        assertEquals(7.5,rows[0].score!!,0.0); assertEquals(7.3,rows[1].score!!,0.0)
        assertNotEquals(rows[0].slug,rows[1].slug); assertFalse(rows[0].premium); assertTrue(rows[1].premium)
        assertEquals(rows[0].source,rows[1].source); assertTrue(rows[0].poster!!.endsWith("new.webp"))
    }
    @Test fun gateOrUnexpectedEnvelopeIsNotAnEmptyLastPage() {
        assertThrows(Exception::class.java) { FullDramaContract.catalogue("""{"action":"captcha"}""") }
        assertTrue(FullDramaContract.catalogue("[]").isEmpty())
    }
    @Test fun detailUsesOnlyRealEpisodeAnchorsAndOrdersSeasonsWithoutFillingGaps() {
        val html = """<div class="dp-info"><h1>Örnek Dizi</h1></div><img class="dp-poster" src="https://cdn.example.invalid/poster.webp">
          <div class="dp-desc">Tam açıklama.</div><div class="dp-meta"><span class="dp-imdb">7.0</span><span>3 Bölüm</span><span>2026</span><a class="dp-cat">NetShort</a></div>
          <div class="dp-egrid">
           <a class="dp-ecard" href="/izle/ornek-1?s=2&amp;e=1"><div class="dp-etitle">Bölüm 1</div></a>
           <a class="dp-ecard" href="/izle/ornek-1?s=1&amp;e=3"><div class="dp-etitle">Bölüm 3</div></a>
           <a class="dp-ecard" href="/izle/ornek-1?s=1&amp;e=1"><div class="dp-etitle">Bölüm 1</div></a>
          </div><a data-honeypot="1" href="/api/episode?bulk=all">.</a>"""
        val s = FullDramaContract.series(html,"ornek-1")
        assertEquals(listOf(1 to 1,1 to 3,2 to 1),s.chapters.map { it.season to it.episode })
        assertEquals(2026,s.year); assertEquals("NetShort",s.platform); assertEquals("Tam açıklama.",s.plot)
        assertEquals(3,s.chapters.size); assertEquals(7.0,s.score!!,0.0)
    }
    @Test fun missingEpisodeRowsCannotBecomeASyntheticCompleteList() {
        val html = """<div class="dp-info"><h1>Dizi</h1></div><div class="dp-meta"><span>45 Bölüm</span></div><div class="dp-egrid"><a class="dp-ecard" href="/izle/ornek-1?s=1&amp;e=1">Bölüm 1</a></div>"""
        assertThrows(IllegalArgumentException::class.java) { FullDramaContract.series(html,"ornek-1") }
    }
    @Test fun episodePayloadIsStableAndBoundToTheExactSourceRoute() {
        val c = FullDramaContract.chapter("https://fulldrama.xyz/izle/ornek-1?s=1&e=2")!!
        val data = FullDramaContract.encode(c)
        assertEquals(data,FullDramaContract.encode(c)); assertEquals(c,FullDramaContract.decode(data))
        assertNull(FullDramaContract.detailSlug(data))
        assertNull(FullDramaContract.chapter("https://other.example.invalid/izle/ornek-1?s=1&e=2"))
        assertNull(FullDramaContract.chapter("https://fulldrama.xyz/izle/ornek-1?s=1&e=2&e=3"))
        assertNull(FullDramaContract.chapter("https://fulldrama.xyz/api/episode?bulk=all"))
    }
    @Test fun actualCaptionDeclarationNormalizesTurkishWithoutUsingPageLocale() {
        val html = """<html lang="tr"><script>const subtitleUrl = "https://cdn.example.invalid/sub.srt";track.srclang = 'tr-TR';</script></html>"""
        val caption = FullDramaContract.captions(html).single()
        assertEquals("tr",caption.language); assertEquals("https://cdn.example.invalid/sub.srt",caption.url)
        assertTrue(FullDramaContract.captions("""<html lang="tr"><script>const subtitleUrl = "";</script></html>""").isEmpty())
    }
    @Test fun categoryAndCoverMetadataDoNotAdmitForeignOrLocalRoutes() {
        val html = """<a href="/series?category=netshort-dublajli">NetShort (Dublajlı)</a><a href="https://other.example.invalid/series?category=wrong">Other</a>"""
        assertEquals(listOf("netshort-dublajli" to "NetShort (Dublajlı)"),FullDramaContract.categories(html))
        assertNull(FullDramaContract.publicHttps("http://fulldrama.xyz/image.webp"))
        assertNull(FullDramaContract.publicHttps("https://127.0.0.1/image.webp"))
    }
    @Test fun binaryMp4AndRealPlaylistsAreDistinguishedFromPublisherHlsLabel() {
        // Observed B2: source says hls, CDN Range206 says video/mp4 and has ISO-BMFF ftyp bytes.
        val mp4 = byteArrayOf(0,0,0,32,102,116,121,112,105,115,111,109)
        assertEquals("mp4",FullDramaContract.mediaFormat(mp4))
        assertEquals("hls",FullDramaContract.mediaFormat("#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertEquals("dash",FullDramaContract.mediaFormat("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">".toByteArray()))
        assertNull(FullDramaContract.mediaFormat("<html>Source login required</html>".toByteArray()))
    }
    @Test fun sourceRefreshRequiresItsActualEpisodeIdAndIssuedSignatureTogether() {
        val html = """<script>window.__NS2_EP_ID = 781114; window.__RF_SIG = "test-issued-signature-not-a-real-ticket";</script>"""
        val ticket = FullDramaContract.refreshTicket(html)!!
        assertEquals(781114L,ticket.episodeId)
        assertEquals("test-issued-signature-not-a-real-ticket",ticket.signature)
        assertFalse(ticket.toString().contains(ticket.signature))
        assertNull(FullDramaContract.refreshTicket("""<script>window.__NS2_EP_ID=781114;</script>"""))
        assertNull(FullDramaContract.refreshTicket("""<script>window.__NS2_EP_ID=0;window.__RF_SIG="test-issued-signature-not-a-real-ticket";</script>"""))
    }
}
