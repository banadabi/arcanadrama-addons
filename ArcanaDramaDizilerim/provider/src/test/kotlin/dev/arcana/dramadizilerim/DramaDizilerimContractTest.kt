package dev.arcana.dramadizilerim

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DramaDizilerimContractTest {
    private val c=DramaDizilerimContract
    private val base="https://dramadizilerim.com"
    private val chapter=base+"/izle/sample?s=1&e=3"
    private fun ep()=c.chapter(chapter)!!
    private fun reject(block:()->Unit) { try { block();fail("must reject") } catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {} }
    private fun push(text:String)="<script>self.__next_f.push("+JSONArray().put(1).put(text).toString()+")</script>"
    private fun props(id:String="legacy-episode-3",slug:String="sample",label:String="1. Sezon · 3. bölüm")=JSONObject()
        .put("episodeId",id).put("titleId","legacy-title-1").put("titleSlug",slug).put("episodeLabel",label)
    @Test fun realCardsAndPublishedNextPagePreserveMetadata() {
        val html="""<article class="title-card stream-card"><a class="card-link" href="/dizi/sample"><div class="card-art"><img src="/api/images?src=poster"></div><div class="card-caption"><h3>Tam Başlık</h3><p>ReelShort · 47 bölüm</p></div></a></article>
            <article class="title-card"><a href="https://foreign.invalid/dizi/a"><h3>Yabancı</h3></a></article>
            <a href="/diziler?page=2">Sonraki</a>"""
        val list=c.listing(html,1);assertEquals(1,list.cards.size);assertEquals("Tam Başlık",list.cards.single().title)
        assertEquals(47,list.cards.single().count);assertTrue(list.more);assertNull(list.cards.single().year)
        assertEquals(base+"/api/images?src=poster",list.cards.single().poster)
        assertEquals(1,c.home(html).size)
        assertEquals(1,c.search("""<input name="q" value="tam">"""+html,"tam").size)
        reject { c.search(html,"wrong") }
    }
    @Test fun quickSearchUsesObservedItemsWithoutForeignSlugOrInventedImage() {
        val raw="""{"items":[{"title":"Başlık","slug":"sample","poster":"/cover.webp","episodeCount":47,"year":2026},{"title":"Yabancı","slug":"../foreign"}],"total":287,"page":1,"pageSize":6}"""
        val list=c.quickSearch(raw);assertEquals(1,list.size);assertEquals(base+"/dizi/sample",list[0].url)
        assertEquals(2026,list[0].year);assertEquals(47,list[0].count);assertEquals(base+"/cover.webp",list[0].poster)
    }
    @Test fun structuredSeriesUsesRealChaptersAndSourceScore() {
        val html="""<link rel="canonical" href="https://dramadizilerim.com/dizi/sample"><h1>Eksiksiz Başlık</h1>
        <script type="application/ld+json">[{"@type":"WebPage"},{"@type":"TVSeries","url":"https://dramadizilerim.com/dizi/sample","name":"Başlık","description":"Kısa","image":"/cover.webp","startDate":"2026-01-01","genre":["Gizem","+18"],"aggregateRating":{"ratingValue":9.6,"bestRating":10},"numberOfEpisodes":47}]</script>
        <div class="series-synopsis"><h2>Özet</h2><p>Tam ve uzun kaynak açıklaması.</p></div>
        <div class="series-infobox-row"><span class="series-infobox-label">Platform</span><span class="series-infobox-val">ReelShort Dizileri</span></div>
        <a class="series-episode-link" aria-label="1. sezon 3. bölüm" href="/izle/sample?s=1&amp;e=3">3</a>
        <a class="series-episode-link" aria-label="2. sezon 8. bölüm" href="/izle/sample?s=2&amp;e=8">8</a>
        <a class="series-episode-link" aria-label="1. sezon 1. bölüm" href="https://foreign.invalid/izle/sample">bad</a>"""
        val series=c.series(html,"sample");assertEquals("Eksiksiz Başlık",series.title);assertEquals("Tam ve uzun kaynak açıklaması.",series.plot)
        assertEquals(2026,series.year);assertEquals(9.6,series.score!!,0.001);assertTrue(series.tags.contains("ReelShort"))
        assertEquals(listOf(1 to 3,2 to 8),series.chapters.map { it.season to it.number })
        reject { c.series(html,"different") }
    }
    @Test fun episodePayloadIsStableAndRejectsAmbiguousQueries() {
        val data=c.encode(ep());assertFalse(data.contains("ticket"));assertEquals(ep(),c.decode(data))
        assertEquals(1,c.chapter(base+"/izle/sample")!!.number)
        assertNull(c.chapter(base+"/izle/sample?s=1&e=0"));assertNull(c.chapter(base+"/izle/sample?s=1&e=3&%65=4"))
        assertNull(c.chapter("https://foreign.invalid/izle/sample?s=1&e=3"));assertNull(c.decode("""{"v":1,"kind":"wrong","url":"$chapter"}"""))
        assertNull(c.detailSlug(data));assertNull(c.detailSlug(chapter))
    }
    @Test fun flightReferencesAndSelectedEpisodeAreBoundWithoutScriptEvaluation() {
        val p=props("\$a");p.put("titleId","\$b")
        val flight="a:"+JSONObject.quote("legacy-episode-3")+"\nb:"+JSONObject.quote("legacy-title-1")+"\n0:"+p+"\n"
        val html="""<link rel="canonical" href="https://dramadizilerim.com/izle/sample">"""+push(flight.substring(0,20))+push(flight.substring(20))
        val w=c.watch(html,ep());assertEquals("legacy-episode-3",w.episodeId);assertEquals("legacy-title-1",w.titleId)
        reject { c.watch(push("0:"+props(slug="other")+"\n"),ep()) }
        reject { c.watch(push("0:"+props(label="1. Sezon · 2. bölüm")+"\n"),ep()) }
        reject { c.watch(push("0:"+props()+"\n1:"+props("different-episode")+"\n"),ep()) }
    }
    @Test fun flightControlFramesSharingAnIdAreNotConflictingJsonModels() {
        // Observed real capture shape: two non-JSON Flight frames reuse the same ID.
        // Keep selected episode binding and rejection of genuinely conflicting JSON records.
        val model="0:"+props()+"\n"
        val html=push("15:X\n15:C\n"+model)
        assertEquals("legacy-episode-3",c.watch(html,ep()).episodeId)
        reject { c.watch(push("0:"+props()+"\n0:"+props("different-episode")+"\n"),ep()) }
    }
    @Test fun seriesSeoCanonicalAndWatchOpenGraphKeepSeparateIdentityChecks() {
        val meta="""<link rel="canonical" href="https://dramadizilerim.com/dizi/sample"><meta property="og:url" content="https://dramadizilerim.com/izle/sample?s=1&amp;e=3">"""
        val model=push("0:"+props()+"\n")
        assertEquals("legacy-episode-3",c.watch(meta+model,ep()).episodeId)
        reject { c.watch(meta.replace("/dizi/sample","/dizi/other")+model,ep()) }
        reject { c.watch(meta.replace("e=3","e=2")+model,ep()) }
        reject { c.watch(meta.replace("https://dramadizilerim.com/dizi/","https://foreign.invalid/dizi/")+model,ep()) }
        reject { c.watch(meta.replace("e=3","e=3&amp;e=2")+model,ep()) }
    }
    @Test fun sourceTicketsCompatibilityCaptionsAndRateDeadlineRemainBounded() {
        val raw="""{"success":true,"kind":"hls","url":"/api/media?ticket=synthetic-primary","compatibilityUrl":"/api/media?ticket=synthetic-compatible","captions":[{"language":"tr_TR","url":"/captions/source.vtt"},{"language":"en","url":"/translate/new"}]}"""
        val p=c.playback(raw,ep());assertEquals(2,p.links.size);assertEquals(true,p.links[0].hls);assertNull(p.links[1].hls)
        assertTrue(p.links[1].compatible);assertEquals(base+"/api/media?ticket=synthetic-primary",p.links[0].url)
        assertEquals(listOf("tr"),p.captions.map { it.language })
        reject { c.playback(raw.replace("ticket=synthetic-primary","other=none"),ep()) }
        reject { c.playback(raw.replace("\"hls\"","\"embed\""),ep()) }
        assertTrue(listOf(401,403,429).all(c::terminalMediaStatus));assertFalse(c.terminalMediaStatus(404))
        val now=Instant.parse("2026-11-08T00:00:00Z").toEpochMilli()
        assertEquals(now+120000,c.retryUntil("120",now));assertEquals(now+600000,c.retryUntil("Sun, 08 Nov 2026 00:10:00 GMT",now))
    }
}
