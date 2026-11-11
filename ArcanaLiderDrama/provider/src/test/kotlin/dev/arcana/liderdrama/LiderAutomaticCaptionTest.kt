package dev.arcana.liderdrama

import org.junit.Assert.*
import org.junit.Test

class LiderAutomaticCaptionTest {
    private val url="https://liderdrama.com/subs/episode-source/1.vtt"
    private val body="WEBVTT\n\n00:01.000 --> 00:03.000\nTürkçe altyazı.".toByteArray()
    @Test fun onlyActualFirstPartyCaptionCanBeTarget() {
        assertEquals(url,LiderAutomaticCaptionPolicy.target(url,"episode-source",1))
        for(bad in listOf("http://liderdrama.com/subs/episode-source/1.vtt","https://liderdrama.com/api/video?slug=x&ep=1",url+"?token=x",url+"#x","https://example.org/subs/episode-source/1.vtt"))
            assertThrows(IllegalArgumentException::class.java){LiderAutomaticCaptionPolicy.target(bad,"episode-source",1)}
        assertThrows(IllegalArgumentException::class.java){LiderAutomaticCaptionPolicy.target(url,"another-series",1)}
        assertThrows(IllegalArgumentException::class.java){LiderAutomaticCaptionPolicy.target(url,"episode-source",2)}
        assertTrue(LiderAutomaticCaptionPolicy.same(url+"?__cf_chl_tk=opaque",url))
        assertFalse(LiderAutomaticCaptionPolicy.same(url.replace("1.vtt","2.vtt"),url))
    }
    @Test fun CueNeverMeansBodyPermissionMoreThanOnce() {
        val t=LiderAutomaticCaptionTicket();val old=t.navigation()
        t.navigation()
        assertFalse(t.admit(old))
        assertTrue(t.admit(t.generation()))
        t.navigation();assertFalse(t.admit(t.generation()))
        t.close();assertFalse(t.live());assertFalse(t.admit(t.generation()))
    }
    @Test fun finiteProbesDoNotConsumeOrResetBodyAdmission() {
        val t=LiderAutomaticCaptionTicket();val g=t.navigation()
        repeat(4){assertTrue(t.probe(g))};assertFalse(t.probe(g))
        assertTrue(t.admit(g));assertFalse(t.admit(g));assertFalse(t.probe(g))
    }
    @Test fun completeExact200TimedUtf8RequiredForSuccess() {
        assertTrue(LiderAutomaticCaptionPolicy.body(200,url,url,body).contains("Türkçe"))
        for(code in listOf(206,302,403,429))assertThrows(IllegalArgumentException::class.java){LiderAutomaticCaptionPolicy.body(code,url,url,body)}
        assertThrows(IllegalArgumentException::class.java){LiderAutomaticCaptionPolicy.body(200,url,url.replace("1.vtt","2.vtt"),body)}
        assertThrows(IllegalArgumentException::class.java){LiderAutomaticCaptionPolicy.body(200,url,url,"<html>00:01.000 --> 00:03.000</html>".toByteArray())}
        assertThrows(java.nio.charset.CharacterCodingException::class.java){LiderAutomaticCaptionPolicy.body(200,url,url,body+byteArrayOf(0xC3.toByte(),0x28))}
    }
    @Test fun cookieHeaderAndRealSubtitleLanguageStayScoped() {
        assertEquals("a=b",LiderAutomaticCaptionPolicy.cookie("a=b"))
        assertThrows(IllegalArgumentException::class.java){LiderAutomaticCaptionPolicy.cookie("a=b\r\nInjected: value")}
        for(lang in listOf("tr","Tur","tr-TR"))assertTrue(LiderAutomaticCaptionPolicy.required(lang))
        assertFalse(LiderAutomaticCaptionPolicy.required("English"))
    }
    @Test fun queueWaitCannotExtendHostLoadLinksDeadline() {
        assertEquals(25_000L,LiderAutomaticCaptionPolicy.budget(28_000,0))
        assertEquals(8_000L,LiderAutomaticCaptionPolicy.budget(10_000,2_000))
        assertEquals(0L,LiderAutomaticCaptionPolicy.budget(10_000,10_001))
    }
    @Test fun operationalErrorsBackOffButCancellationAndGlobalStopsRemainAuthoritative() {
        var failures=0
        assertThrows(java.io.IOException::class.java){LiderAutomaticCaptionAttempt.run({},{failures++}){throw java.io.IOException()}}
        assertEquals(1,failures)
        assertThrows(java.util.concurrent.CancellationException::class.java){LiderAutomaticCaptionAttempt.run({},{failures++}){throw java.util.concurrent.CancellationException()}}
        assertEquals(1,failures)
        assertThrows(SecurityException::class.java){LiderAutomaticCaptionAttempt.run({throw SecurityException("global stop")},{failures++}){throw java.io.IOException()}}
        assertEquals(1,failures)
    }
    @Test fun backoffCannotShrinkAndFailedCommitStillStopsThisSession() {
        var stored=0L;var now=1000L
        val ledger=LiderAutomaticCaptionBackoff({stored},{stored=it;true},{now})
        ledger.failure(1_800_000);val longer=stored
        ledger.failure(300_000);assertEquals(longer,stored);assertTrue(ledger.blocked())
        now=stored+1;assertFalse(ledger.blocked())
        val failing=LiderAutomaticCaptionBackoff({0},{false},{1000})
        assertThrows(java.io.IOException::class.java){failing.failure(300_000)}
        assertTrue(failing.blocked())
    }
}
