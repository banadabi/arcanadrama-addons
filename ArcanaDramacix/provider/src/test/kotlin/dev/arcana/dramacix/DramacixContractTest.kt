package dev.arcana.dramacix

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class DramacixContractTest {
    private fun html(items: String, number: String="2") = """<meta property="og:video" content="https://dramacix.com/embed/ornek?ep=2">
        <script>window.DH_CONFIG={"slug":"ornek","urlEp":$number,"toplamBolum":50,"items":$items};</script>"""
    private val rows="""[{"slug":"ornek","ep":1,"t":"synthetic-one","src":"/stale-first.mp4"},{"slug":"ornek","ep":2,"t":"synthetic-two","src":""}]"""
    @Test fun correctSelectedTicketNeverUsesEpisodeOneCachedVideo() {
        val ticket=DramacixContract.ticket(html(rows),DramacixContract.Chapter("ornek",2))
        assertEquals("synthetic-two",ticket.token);assertFalse(ticket.toString().contains(ticket.token))
        assertThrows(Exception::class.java) { DramacixContract.ticket(html(rows,"1"),DramacixContract.Chapter("ornek",2)) }
        assertThrows(Exception::class.java) { DramacixContract.ticket(html(rows,"\"2\""),DramacixContract.Chapter("ornek",2)) }
    }
    @Test fun duplicateOrForeignEmbedCannotResolveAnotherChapter() {
        val duplicate=rows.dropLast(1)+",{\"slug\":\"ornek\",\"ep\":2,\"t\":\"synthetic-duplicate\"}]"
        assertThrows(Exception::class.java) { DramacixContract.ticket(html(duplicate),DramacixContract.Chapter("ornek",2)) }
        assertThrows(Exception::class.java) { DramacixContract.ticket(html(rows).replace("/embed/ornek?ep=2","/embed/other?ep=2"),DramacixContract.Chapter("ornek",2)) }
    }
    @Test fun stableEpisodePayloadRejectsForeignOrAmbiguousRoutes() {
        val c=DramacixContract.Chapter("ornek",2);val encoded=DramacixContract.encode(c)
        assertEquals(c,DramacixContract.decode(encoded));assertNull(DramacixContract.detailSlug(encoded))
        assertFalse(encoded.contains("synthetic-two"));assertNull(DramacixContract.chapter(c.url+"?ep=3"))
        assertNull(DramacixContract.chapter(c.url.replace("dramacix.com","other.example.invalid")))
    }
    @Test fun realRecordsPreserveGapsWithoutInventingMissingFiftyChapters() {
        val data=html(rows.replace("\"ep\":2","\"ep\":3"))+"""<script type="application/ld+json">{"@type":"TVSeries","url":"https://dramacix.com/dizi/ornek","name":"Örnek","description":"Tam açıklama","numberOfEpisodes":50,"datePublished":"2026-11-01"}</script>"""
        val s=DramacixContract.series(data,"ornek");assertEquals(listOf(1,3),s.chapters.map { it.number });assertEquals("Tam açıklama",s.description)
        assertThrows(Exception::class.java) { DramacixContract.series(data,"other") }
    }
    @Test fun catalogueAjaxKeepsPageIdentityAndRealEndState() {
        val page="""{"ok":true,"sayfa":2,"toplamSayfa":2,"html":"<a class='card' href='/dizi/ornek'><div class='card-title'>Örnek</div><span class='kr-bol'>32 Bölüm</span></a>"}"""
        val parsed=DramacixContract.listing(page,2);assertFalse(parsed.more);assertEquals(32,parsed.cards.single().count)
        assertThrows(Exception::class.java) { DramacixContract.listing(page,3) }
        assertThrows(Exception::class.java) { DramacixContract.listing(page.replace("\"sayfa\":2","\"sayfa\":2,\"hasMore\":true"),2) }
        assertTrue(DramacixContract.listing("""<script>var suankiSayfa = 1;var toplamSayfa = 605;</script>""",1).more)
    }
    @Test fun imageAltProvidesActualCardTitleWhenResponsiveCardHasNoTitleElement() {
        val html="""<a class='card' href='/dizi/ornek'><img src='/poster.jpg' alt='Gerçek kart başlığı'></a>
            <a class='card' href='/dizi/ikinci'><div class='card-title'>Görünen başlık</div><img alt='Diğer resim açıklaması'></a>
            <a class='card' href='https://other.example.invalid/dizi/yabanci'><img alt='Yabancı'></a>"""
        val cards=DramacixContract.cards(html)
        assertEquals(listOf("Gerçek kart başlığı","Görünen başlık"),cards.map { it.title })
        assertEquals("https://dramacix.com/dizi/ornek",cards.first().url)
    }
    @Test fun publisherMixedCaseIdentitySurvivesEveryRouteWithoutLowercasing() {
        val slug="OrnEK-41";val c=DramacixContract.Chapter(slug,2)
        assertEquals(slug,DramacixContract.detailSlug("https://dramacix.com/dizi/$slug"))
        assertEquals(c,DramacixContract.decode(DramacixContract.encode(c)))
        val page=html(rows).replace("ornek",slug)
        assertEquals(c,DramacixContract.ticket(page,c).chapter)
        val series=page+"""<script type="application/ld+json">{"@type":"TVSeries","url":"https://dramacix.com/dizi/$slug","name":"Örnek"}</script>"""
        assertEquals(slug,DramacixContract.series(series,slug).chapters.first().slug)
        assertThrows(Exception::class.java) { DramacixContract.series(series,slug.lowercase()) }
        val card="""<a class='card' href='/dizi/$slug'><img alt='Örnek'></a>"""
        assertEquals("https://dramacix.com/dizi/$slug",DramacixContract.cards(card).single().url)
    }
    private fun ts(packets:Int)=ByteArray(188*packets).also { b -> repeat(packets){p->b[p*188]=0x47;b[p*188+1]=0;b[p*188+2]=0x10;b[p*188+3]=0x10} }
    @Test fun disguisedSourceSegmentDropsOnlyVerifiedTransportPrefix() {
        val png=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)+ByteArray(100)
        val jpg=byteArrayOf(255.toByte(),216.toByte(),255.toByte())+ByteArray(20)
        assertEquals(png.size,DramacixTsPrefix.skip(png+ts(5)))
        assertEquals(jpg.size,DramacixTsPrefix.skip(jpg+ts(3)))
        assertEquals(17,DramacixTsPrefix.skip(ByteArray(17)+ts(5)))
        assertEquals(0,DramacixTsPrefix.skip(ts(5)))
    }
    @Test fun textMp4KeysAndUnprovenSynchronizationAreNeverRewritten() {
        for(text in listOf("#EXTM3U\n","WEBVTT\n","<html>Access denied</html>","{\"available\":false}"))assertEquals(0,DramacixTsPrefix.skip(text.toByteArray()+ts(6)))
        assertEquals(0,DramacixTsPrefix.skip(byteArrayOf(0,0,0,8,102,116,121,112)+ts(6)))
        assertEquals(0,DramacixTsPrefix.skip(ByteArray(16)))
        assertEquals(0,DramacixTsPrefix.skip(ByteArray(18)+ts(2)))
        assertEquals(0,DramacixTsPrefix.skip(ByteArray(8193)+ts(6)))
    }
    @Test fun successfulHttpStatusDoesNotTurnAChallengeIntoCatalogueData() {
        for(html in listOf("<title>Access Denied</title><body>blocked</body>","<title>Page</title><p>Please verify you are human</p>","<title>Attention Required</title>"))assertTrue(DramacixContract.deniedHtml(html))
        assertFalse(DramacixContract.deniedHtml("<title>DramaCix</title><a class='card' href='/dizi/ornek'><img alt='Örnek'></a>"))
    }
    @Test fun earliestFramedPacketsArePreservedEvenWhenHeaderFlagsAreUnusual() {
        val prefix=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)+ByteArray(92)
        val payload=ts(20).also { b -> repeat(11) { p -> b[p*188+1]=0x80.toByte();b[p*188+3]=0 } }
        assertEquals(prefix.size,DramacixTsPrefix.skip(prefix+payload))
        assertEquals(0,DramacixTsPrefix.skip(payload))
    }
    @Test fun signedPublisherRouteAndOpaqueParametersArePreservedWithoutRecursiveOrPrivateTargets() {
        val encoded=Base64.getEncoder().encodeToString("https://video-v6.mydramawave.com/episode/video.m3u8".toByteArray())
        val raw="/live.m3u8?url=$encoded&exp=123456&sig=synthetic-signature"
        val r=DramacixContract.resource(raw,"https://dramacix.com/dizi/ornek/bolum-1")!!
        assertEquals("https://dramacix.com"+raw,r.url)
        for(host in listOf("DRAMACIX.COM","dramacix.com.","MEDIA.DRAMACIX.COM","media.dramacix.com."))assertNull(DramacixContract.resource("https://"+host+raw,DramacixContract.BASE+"/"))
        for(target in listOf("https://127.0.0.1/internal.m3u8","https://dramacix.com/live.php","https://DRAMACIX.COM/live.php","https://dramacix.com./live.php","https://media.DRAMACIX.COM/live.php","https://media.dramacix.com./live.php")) {
            val e=Base64.getEncoder().encodeToString(target.toByteArray())
            assertNull(DramacixContract.resource("/live.m3u8?url=$e",DramacixContract.BASE+"/"))
        }
    }
    @Test fun deliveredWrappersDoNotExposeTicketsOrInvokeTranslationFallback() {
        val encoded=Base64.getEncoder().encodeToString("https://video-v6.mydramawave.com/example/video.m3u8".toByteArray())
        val caption=Base64.getEncoder().encodeToString("https://video-v6.mydramawave.com/example/tr.srt".toByteArray())
        val raw="""{"url":"/live.m3u8?url=$encoded","subs":[{"lang":"tr-TR","url":"/live.php?url=$caption"},{"lang":"en","url":"/api/altyazi.php?url=example&dil=tr"}]}"""
        val play=DramacixContract.playback(raw,DramacixContract.Chapter("ornek",2))
        assertEquals("dramacix.com",java.net.URI(play.video.url).host)
        assertEquals("/live.m3u8",java.net.URI(play.video.url).path)
        assertEquals("video-v6.mydramawave.com",java.net.URI(play.video.upstream).host)
        assertEquals("https://dramacix.com/dizi/ornek/bolum-2",play.video.referer)
        assertEquals("/live.php",java.net.URI(play.captions.single().resource.url).path)
        assertTrue(play.captions.single().resource.contentPath.endsWith(".srt"))
        assertEquals("tr",play.captions.single().language);assertFalse(play.video.toString().contains(play.video.url))
        assertNull(DramacixContract.resource("/api/altyazi.php?url=example&dil=tr","https://dramacix.com/dizi/ornek/bolum-2"))
        assertNull(DramacixContract.resource("https://127.0.0.1/file.mp4","https://dramacix.com/"))
    }
    @Test fun declaredWebVttDoesNotRequireAFilenameExtension() {
        val target=Base64.getEncoder().encodeToString("https://captions.example.org/resource?id=example".toByteArray())
        val raw="""{"url":"https://cdn.example.org/video.mp4","subs":[{"lang":"tr","format":"webvtt","url":"/live.php?url=$target"}]}"""
        val c=DramacixContract.Chapter("ornek",1)
        val captions=DramacixContract.playback(raw,c).captions
        assertEquals(1,captions.size);assertEquals("tr",captions.single().language)
        assertEquals("/resource",captions.single().resource.contentPath)
        assertTrue(DramacixContract.playback(raw.replace("webvtt","html"),c).captions.isEmpty())
        val translation="""{"url":"https://cdn.example.org/video.mp4","subs":[{"lang":"tr","format":"webvtt","url":"/api/altyazi.php?url=example&dil=tr"}]}"""
        assertTrue(DramacixContract.playback(translation,c).captions.isEmpty())
    }

    @Test fun catalogueFiltersSurviveNextPagesAndRequestSnapshots() {
        val r=DramacixCatalogue.Request(DramacixCatalogue.Kind.DUB,"fantastik")
        assertEquals("https://dramacix.com/diziler?tur=fantastik&dublaj=1&sira=yeni&q=&sayfa=3&ajax=1",r.url(3))
        assertEquals(r,DramacixCatalogue.decode(r.data))
        assertEquals("https://dramacix.com/diziler?tur=fantastik&dublaj=1&sira=yeni&q=&sayfa=4&ajax=1",DramacixCatalogue.decode(r.data).url(4))
        assertEquals("https://dramacix.com/diziler?tur=&q=",DramacixCatalogue.Request(DramacixCatalogue.Kind.POPULAR).url(1))
        assertEquals("https://dramacix.com/diziler?tur=&sira=yeni&q=",DramacixCatalogue.Request(DramacixCatalogue.Kind.NEW).url(1))
    }
    @Test fun platformPagerUsesItsOwnPathAndExcludesCommunityRows() {
        val r=DramacixCatalogue.Request(DramacixCatalogue.Kind.PLATFORM,platform="dramawave")
        assertEquals("https://dramacix.com/platform/dramawave/sayfa/2",r.url(2))
        assertEquals("dramawave",DramacixCatalogue.platformInput(r.url(2)))
        val html="""<div id="listeBasi"><div class="grid"><a class="card" href="/dizi/ornek"><div class="card-name">Gerçek Başlık</div><img alt="Kapak"></a></div><div class="pages"><a href="/platform/dramawave">1</a><span class="on">2</span><a href="/platform/dramawave/sayfa/3">›</a></div></div><div class="hrow"><a class="card" href="/dizi/baska"><img alt="Topluluk"></a></div>"""
        val page=DramacixCatalogue.platformListing(html,r,2)
        assertEquals(listOf("Gerçek Başlık"),page.cards.map { it.title });assertTrue(page.more)
        assertFalse(DramacixCatalogue.platformListing(html.replace("dramawave/sayfa/3","baska/sayfa/3"),r,2).more)
        try { DramacixCatalogue.platformListing(html,r,3);fail("wrong page accepted") }catch(_:IllegalArgumentException){}
    }
    @Test fun selectedFiltersKeepStartupBoundedAndSearchTextEncoded() {
        assertEquals(3,DramacixCatalogue.rows("","","").size)
        assertEquals(4,DramacixCatalogue.rows("fantastik","","dramawave").size)
        val rows=DramacixCatalogue.rows("romantik","A & B","")
        assertEquals(1,rows.size)
        val u=java.net.URI(rows.single().url(4))
        assertTrue(u.rawQuery.contains("q=A%20%26%20B"));assertTrue(u.rawQuery.contains("tur=romantik"));assertTrue(u.rawQuery.contains("sayfa=4"))
        assertEquals(rows.single(),DramacixCatalogue.decode(rows.single().data))
        assertTrue(DramacixContract.listing("""{"ok":true,"sayfa":1,"toplamSayfa":0,"html":""}""",1).cards.isEmpty())
    }
    @Test fun catalogueSettingsRejectForeignPlatformAndUnknownGenre() {
        for(url in listOf("https://example.org/platform/dramawave","https://dramacix.com/api/video","https://dramacix.com/platform/dramawave?token=example")) {
            assertTrue(runCatching { DramacixCatalogue.platformInput(url) }.isFailure)
        }
        assertTrue(runCatching { DramacixCatalogue.Request(DramacixCatalogue.Kind.NEW,"unknown-genre") }.isFailure)
    }
    @Test fun preparedCaptionsUseTheSameTimedTextConversion() {
        val srt="1\n00:00:01,000 --> 00:00:02,000\nMerhaba\n"
        assertTrue(DramacixCaption.hasTiming(srt.toByteArray()))
        assertEquals("WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.000\nMerhaba\n",DramacixCaption.vtt(srt.toByteArray())!!.toString(Charsets.UTF_8))
        val vtt="WEBVTT\n\n00:01.000 --> 00:02.000\nMerhaba\n"
        assertTrue(DramacixCaption.hasTiming(vtt.toByteArray()))
        assertFalse(DramacixCaption.hasTiming("<html>unavailable</html>".toByteArray()))
        assertEquals(vtt,DramacixCaption.vtt(vtt.toByteArray())!!.toString(Charsets.UTF_8))
        assertNull(DramacixCaption.vtt("<html>unavailable</html>".toByteArray()))
    }
    @Test fun preparedCaptionRouteAvoidsAnotherFetchAndRetainsIdentityAndHeadRules() {
        val network=java.util.concurrent.atomic.AtomicInteger()
        val upstream=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            network.incrementAndGet()
            throw java.io.IOException("fixture network must not be used")
        }.build()
        DramacixHttpsTransport { raw -> okhttp3.HttpUrl.Builder().scheme("https").host("captions.example.org").encodedPath(java.net.URI(raw).rawPath).build() }.use { transport ->
            val body="WEBVTT\n\n00:01.000 --> 00:02.000\nMerhaba\n".toByteArray()
            val url=transport.register("https://captions.example.org/file","https://dramacix.com/",upstream,"vtt","fixture",bytes=body)
            body.fill(0)
            val client=okhttp3.OkHttpClient.Builder().addInterceptor(transport).build()
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { assertTrue(it.body.string().contains("Merhaba"));assertEquals("text/vtt",it.header("Content-Type")) }
            client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { assertEquals("",it.body.string());assertEquals(body.size.toString(),it.header("Content-Length")) }
            assertTrue(runCatching { client.newCall(okhttp3.Request.Builder().url(url.replace("captions.example.org","other.example.org")).build()).execute().close() }.isFailure)
            assertEquals(0,network.get())
        }
    }
    @Test fun preparedCaptionEvictionKeepsTheRegisteredNetworkFallback() {
        val network=java.util.concurrent.atomic.AtomicInteger()
        val bytes="WEBVTT\n\n00:01.000 --> 00:02.000\nMerhaba\n".toByteArray()
        val upstream=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            network.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null,bytes)).build()
        }.build()
        DramacixHttpsTransport { raw -> okhttp3.HttpUrl.Builder().scheme("https").host("captions.example.org").encodedPath(java.net.URI(raw).rawPath).build() }.use { transport ->
            val urls=(1..13).map { n -> transport.register("https://captions.example.org/$n","",upstream,"vtt","fixture",bytes=bytes) }
            val client=okhttp3.OkHttpClient.Builder().addInterceptor(transport).build()
            client.newCall(okhttp3.Request.Builder().url(urls.first()).build()).execute().use { assertTrue(it.body.string().contains("Merhaba")) }
            assertEquals(1,network.get())
            client.newCall(okhttp3.Request.Builder().url(urls.last()).build()).execute().use { assertTrue(it.body.string().contains("Merhaba")) }
            assertEquals(1,network.get())
        }
    }

}
