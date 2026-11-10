package dev.arcana.minidiziizle

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MiniContractTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val detail = "https://minidiziizle.com/icerik/dileklerin-efendisi-u8ke/"
    @Test fun currentPublisherDetailPreservesIdentityAndRealChapters() {
        val d = MiniContract.detail(fixture("mini-detail-source-r1.html"),detail)
        assertEquals(77187L,d.id);assertEquals("Dileklerin Efendisi",d.title)
        assertThrows(IllegalArgumentException::class.java) { MiniContract.detail(fixture("mini-detail-source-r1.html"),"https://minidiziizle.com/icerik/other-series/") }
        assertEquals((1..51).toList(),d.chapters.map { it.number })
        assertEquals("https://minidiziizle.com/bolum/dileklerin-efendisi-u8ke-1-bolum/",d.chapters.first().page)
        assertTrue(d.chapters.all { MiniContract.decode(MiniContract.encode(it)) == it })
        val dub = MiniContract.detail(fixture("mini-detail-source-r1.html"),detail+"#mini=dublaj")
        assertTrue(dub.chapters.all { it.edition == "dublaj" });assertTrue(d.chapters.all { it.edition == "altyazi" })
        val player = MiniContract.player(fixture("mini-episode-source-r1.html"),d.chapters.first())
        assertEquals(77431L,player.episodeId);assertTrue(player.pool)
        assertThrows(IllegalArgumentException::class.java) { MiniContract.player(fixture("mini-episode-source-r1.html"),d.chapters[1]) }
    }
    @Test fun currentHomeAndArchiveRetainPublisherPagingAndSearchRoutes() {
        val rows = MiniContract.home(fixture("mini-home-source-r1.html"))
        assertEquals(6,rows.size);assertEquals(rows.size,rows.map { it.path }.toSet().size)
        assertTrue(rows.first().cards.all { it.url.startsWith(MiniContract.BASE+"/icerik/") })
        val p = MiniContract.listing(fixture("mini-sub-list-source-r1.html"),MiniContract.BASE+"/altyazili-mini-diziler/","altyazi")
        assertEquals(MiniContract.BASE+"/altyazili-mini-diziler/page/2/",p.next)
        assertTrue(p.cards.all { it.edition == "altyazi" })
        val search = MiniContract.search(fixture("search.json"))
        assertTrue(search.any { it.title == "Dileklerin Efendisi" && it.url == detail })
    }
    @Test fun mixedUpPlaybackIdentityOrEditionIsRejected() {
        val d = MiniContract.detail(fixture("mini-detail-source-r1.html"),detail)
        val player = MiniContract.player(fixture("mini-episode-source-r1.html"),d.chapters.first())
        fun result(id: Int, lang: String) = JSONObject().put("id",id).put("dil",lang).put("tur","hls")
        assertEquals(77431,MiniContract.playback(result(77431,"altyazi"),player).getInt("id"))
        assertThrows(IllegalArgumentException::class.java) { MiniContract.playback(result(77432,"altyazi"),player) }
        assertThrows(IllegalArgumentException::class.java) { MiniContract.playback(result(77431,"dublaj"),player) }
        val encoded = MiniContract.encode(d.chapters.first())
        assertNull(MiniContract.decode(JSONObject(encoded).put("url","https://example.org").toString()))
    }
    @Test fun sourceAndMediaControlBoundariesStaySeparate() {
        assertNull(MiniContract.detailUrl("{\"schema\":1}"))
        assertNull(MiniContract.detailUrl("https://evil.example/icerik/a/"))
        assertThrows(IllegalArgumentException::class.java) { MiniContract.chapterUrl(MiniContract.BASE+"/wp-json/wp/v2/users") }
        for(raw in listOf("http://api.mybigdramapool.com/a.m3u8",MiniContract.BASE+"/wp-json/minidizizle/v1/playback/1", "https://media.minidiziizle.com/authorize")) {
            assertThrows(IllegalArgumentException::class.java) { MiniContract.media(raw) }
        }
        assertEquals("https://api.mybigdramapool.com/a.m3u8?cut=1",MiniContract.media("https://api.mybigdramapool.com/a.m3u8?cut=1"))
    }
    @Test fun reviewedRequestHoldsAddressAndDenialBoundaries() {
        val now = java.time.Instant.parse("2026-11-10T00:00:00Z").toEpochMilli()
        assertEquals(now+172800000,MiniPolicy.retryDeadline("172800",now))
        assertEquals(now+172800000,MiniPolicy.retryDeadline("Thu, 12 Nov 2026 00:00:00 GMT",now))
        assertEquals(Long.MAX_VALUE,MiniPolicy.retryDeadline("99999999999999999999999",now))
        for(ip in listOf(intArrayOf(100,64,0,1),intArrayOf(198,18,0,1),intArrayOf(0,2,3,4),intArrayOf(240,2,3,4))) {
            assertFalse(MiniPolicy.publicAddress(java.net.InetAddress.getByAddress(ip.map { it.toByte() }.toByteArray())))
        }
        for(path in listOf("/x/../authorize","/authorize/","/%61uthorize","/authorize;extra","/session/")) {
            assertThrows(IllegalArgumentException::class.java) { MiniContract.media("https://media.minidiziizle.com"+path) }
            assertThrows(IllegalArgumentException::class.java) { MiniPolicy.request("https://media.minidiziizle.com"+path,true) }
        }
        assertThrows(IllegalArgumentException::class.java) { MiniPolicy.request(MiniContract.BASE+"/wp-admin/options.php",false) }
        MiniPolicy.request("https://media.minidiziizle.com/session",true)
        MiniPolicy.request(MiniContract.BASE+"/wp-json/minidizizle/v1/arama?q=Dilek",false)
        assertEquals("https://api.mybigdramapool.com/api/v2/fixture/asset/master.m3u8?cut=1",MiniContract.media("https://api.mybigdramapool.com/api/v2/fixture/asset/master.m3u8?cut=1"))
        assertEquals(403,MiniPolicy.bodyDenial("{\"code\":\"source_denied\",\"data\":{\"status\":403}}"))
        assertEquals(403,MiniPolicy.bodyDenial("<html><title>Access denied</title></html>"))
        assertNull(MiniPolicy.bodyDenial("WEBVTT\n\n00:00.000 --> 00:01.000\nAccess denied\n"))
    }
    @Test fun binaryKeysAndRangesAreNotTextErrorDocuments() {
        val key = byteArrayOf(123,0,4,6,8,10,12,14,16,18,20,22,24,26,28,30).toString(Charsets.UTF_8)
        assertFalse(MiniPolicy.errorDocument(key))
        assertFalse(MiniPolicy.inspectMediaDocument(200,"key","application/octet-stream"))
        assertFalse(MiniPolicy.inspectMediaDocument(200,"key","text/plain"))
        assertFalse(MiniPolicy.inspectMediaDocument(200,"bin","application/octet-stream"))
        assertFalse(MiniPolicy.inspectMediaDocument(206,"mp4","video/mp4"))
        assertFalse(MiniPolicy.inspectMediaDocument(206,"bin","application/json"))
        assertTrue(MiniPolicy.inspectMediaDocument(200,"hls","application/vnd.apple.mpegurl"))
        assertTrue(MiniPolicy.inspectMediaDocument(200,"mp4","text/html"))
        assertTrue(MiniPolicy.errorDocument("{\"error\":\"forbidden\"}"))
        assertFalse(MiniPolicy.errorDocument("{\"legitimate\":true}"))
    }
}
