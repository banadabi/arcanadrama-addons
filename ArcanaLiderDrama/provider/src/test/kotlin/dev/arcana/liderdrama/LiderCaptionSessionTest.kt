package dev.arcana.liderdrama

import org.junit.Assert.*
import org.junit.Test

class LiderCaptionSessionTest {
    @Test fun earlyFinishWaitsForMatchingCommitWithoutSpendingBodyBudget() {
        val gate=LiderCaptionGate();gate.deny("caption"){true};val ticket=gate.begin()!!
        val nav=LiderCaptionNavigation();nav.navigationStarted()
        assertNull(nav.completed("source",false))
        assertNull(nav.queue())
        val ready=nav.committed("source")!!
        assertEquals("source",ready.document);assertFalse(ready.download)
        assertNull(nav.committed("source")) // Commit cannot replay the consumed signal.
        val attempt=nav.queue()!!
        assertTrue(gate.admitBody(ticket));assertTrue(nav.admit(attempt))
        assertFalse(gate.admitBody(ticket))
    }
    @Test fun earlyDownloadAndFinishMergeAndLateFinishUsesCurrentCommit() {
        val nav=LiderCaptionNavigation();nav.navigationStarted()
        assertNull(nav.completed("source",true))
        assertNull(nav.completed("source",false))
        assertTrue(nav.committed("source")!!.download)
        assertNull(nav.committed("source"))
        val after=nav.completed("source",false)!!
        assertFalse(after.download);assertEquals("source",after.document)
    }
    @Test fun pendingCompletionCannotSurviveNavigationCloseOrDifferentCommit() {
        val nav=LiderCaptionNavigation();nav.navigationStarted()
        nav.completed("source",true)
        nav.navigationStarted() // Same URL in a new navigation is still a new document.
        assertNull(nav.committed("source"))
        nav.navigationStarted();nav.completed("source",true)
        assertNull(nav.committed("other"));assertNull(nav.committed("source"))
        nav.navigationStarted();nav.completed("source",true);nav.close()
        assertNull(nav.committed("source"));assertNull(nav.completed("source",true));assertNull(nav.queue())
    }
    @Test fun optionalDenialAndGlobalRateLimitStaySeparate() {
        assertEquals(LiderCaptionPolicy.Scope.CAPTION,LiderCaptionPolicy.scope(LiderFailureContext.Phase.SUBTITLE,403))
        assertEquals(LiderCaptionPolicy.Scope.GLOBAL,LiderCaptionPolicy.scope(LiderFailureContext.Phase.VIDEO_API,403))
        assertEquals(LiderCaptionPolicy.Scope.GLOBAL,LiderCaptionPolicy.scope(LiderFailureContext.Phase.SUBTITLE,429))
        assertEquals(LiderCaptionPolicy.Scope.GLOBAL,LiderCaptionPolicy.scope(LiderFailureContext.Phase.DETAIL,401))
    }
    @Test fun acceptsRealTimedVttAndSrtButNotChallengeOrErrorDocuments() {
        assertTrue(LiderCaptionPolicy.timed("WEBVTT\n\n00:01.000 --> 00:03.000\nTürkçe bir cümle."))
        assertTrue(LiderCaptionPolicy.timed("1\n00:00:01,000 --> 00:00:03,000\nTürkçe bir cümle."))
        assertFalse(LiderCaptionPolicy.timed("<html><!-- --> Doğrulama gerekiyor</html>"))
        assertFalse(LiderCaptionPolicy.timed("{\"message\":\"-->\"}"))
    }

    @Test fun onlyActualDenialSetsTargetAndDismissedTicketCannotCommit() {
        val gate=LiderCaptionGate()
        assertNull(gate.begin())
        assertTrue(gate.deny("TR"){true})
        val old=gate.begin()!!
        assertEquals("TR",old.url)
        gate.end(old)
        var called=false
        assertFalse(gate.complete(old){called=true;true})
        assertFalse(called)
        val current=gate.begin()!!
        assertEquals("TR",current.url)
        assertFalse(gate.complete(old){called=true;true})
        assertTrue(gate.live(current))
        assertFalse(called)
        assertTrue(gate.complete(current){true})
        assertNull(gate.begin())
    }
    @Test fun newDenialFencesOldCallbackAndFailedCommitKeepsTarget() {
        val gate=LiderCaptionGate()
        gate.deny("TR"){true}
        val old=gate.begin()!!
        gate.deny("new TR"){true}
        var called=false
        assertFalse(gate.complete(old){called=true;true})
        assertFalse(called)
        val current=gate.begin()!!
        assertEquals("new TR",current.url)
        assertFalse(gate.complete(current){false})
        assertTrue(gate.live(current))
        assertTrue(gate.hasTarget())
        gate.cancel()
        assertFalse(gate.complete(current){called=true;true})
        assertFalse(called)
    }

    @Test fun reopeningDoesNotCreateAnotherConfirmationRequest() {
        val gate=LiderCaptionGate()
        gate.deny("TR"){true}
        val first=gate.begin()!!
        assertTrue(gate.admitBody(first))
        assertFalse(gate.admitBody(first))
        gate.end(first)
        val reopened=gate.begin()!!
        assertFalse(gate.admitBody(reopened))
        gate.deny("TR"){true}
        val newDenial=gate.begin()!!
        assertFalse(gate.admitBody(reopened))
        assertTrue(gate.admitBody(newDenial))
    }

    @Test fun queuedNavigationRetainsBudgetAndSameUrlDoesNotReviveStaleJob() {
        val gate=LiderCaptionGate();gate.deny("caption"){true};val ticket=gate.begin()!!
        val nav=LiderCaptionNavigation()
        assertFalse(nav.navigationStarted());nav.committed("documentA")
        val stale=nav.queue()!!
        assertFalse(nav.navigationStarted());nav.committed("documentA")
        assertFalse(nav.pending(stale))
        val current=nav.queue()!!
        nav.abandon(stale)
        assertTrue(nav.pending(current))
        assertTrue(gate.admitBody(ticket))
        assertTrue(nav.admit(current))
        assertTrue(nav.current(current))
        assertFalse(gate.admitBody(ticket))
    }
    @Test fun navigationAfterAdmissionIsTerminalAndCannotGrantAnotherRequest() {
        val nav=LiderCaptionNavigation();nav.navigationStarted();nav.committed("source")
        val first=nav.queue()!!
        assertTrue(nav.admit(first))
        assertTrue(nav.navigationStarted())
        assertFalse(nav.current(first))
        nav.committed("source")
        assertNull(nav.queue())
        nav.close();assertFalse(nav.pending(first));assertNull(nav.queue())
    }

    @Test fun finiteDomProbesNeverSpendOrResetTheSingleBodyBudget() {
        val gate=LiderCaptionGate();gate.deny("caption"){true};val ticket=gate.begin()!!
        val nav=LiderCaptionNavigation();nav.navigationStarted();nav.committed("source")
        repeat(4) {
            val probe=nav.queue()!!
            assertTrue(nav.reserveProbe(probe))
            nav.abandon(probe)
        }
        val actualDownload=nav.queue()!!
        assertFalse(nav.reserveProbe(actualDownload))
        assertTrue(gate.admitBody(ticket)) // A real download can still request the sole confirmation.
        assertTrue(nav.admit(actualDownload))
        assertFalse(gate.admitBody(ticket))
    }

    @Test fun internalChallengeFramesNeverBecomeMainFrameAuthority() {
        assertTrue(LiderVerificationFramePolicy.allowSubframe("about:blank",false,"GET"))
        assertTrue(LiderVerificationFramePolicy.allowSubframe("about:srcdoc",false,"GET"))
        assertFalse(LiderVerificationFramePolicy.allowSubframe("about:blank",true,"GET"))
        assertFalse(LiderVerificationFramePolicy.allowSubframe("about:srcdoc",false,"POST"))
        assertFalse(LiderVerificationFramePolicy.allowSubframe("file:///data/local/private",false,"GET"))
    }
    @Test fun onlyTheExistingHttpsChallengeHostIsAllowedAsSubframe() {
        assertTrue(LiderVerificationFramePolicy.allowSubframe("https://challenges.cloudflare.com/turnstile/frame",false,"GET"))
        assertFalse(LiderVerificationFramePolicy.allowSubframe("https://challenges.cloudflare.com/turnstile/frame",true,"GET"))
        assertFalse(LiderVerificationFramePolicy.allowSubframe("http://challenges.cloudflare.com/frame",false,"GET"))
        assertFalse(LiderVerificationFramePolicy.allowSubframe("https://challenges.cloudflare.com.evil.invalid/frame",false,"GET"))
        assertFalse(LiderVerificationFramePolicy.allowSubframe("https://user@challenges.cloudflare.com/frame",false,"GET"))
    }

    @Test fun resourceFallbackCannotAuthoriseMainFrameChallengeRequests() {
        for(method in listOf("GET","POST")) {
            assertFalse(LiderVerificationFramePolicy.allowRequest(
                "https://challenges.cloudflare.com/frame",true,method,false,true))
        }
        assertTrue(LiderVerificationFramePolicy.allowRequest(
            "https://challenges.cloudflare.com/frame",false,"GET",false,true))
        assertTrue(LiderVerificationFramePolicy.allowRequest(
            "https://liderdrama.com/subs/source/1.vtt",true,"GET",true,true))
        assertFalse(LiderVerificationFramePolicy.allowRequest(
            "https://liderdrama.com/subs/source/1.vtt",true,"POST",true,false))
    }
}
