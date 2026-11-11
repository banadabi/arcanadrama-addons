package dev.arcana.liderdrama

import org.junit.Assert.*
import org.junit.Test

class LiderFailureContextTest {
    @Test fun identifiesTheThreePossiblePlaybackRejectionStages() {
        assertEquals(LiderFailureContext.Phase.DETAIL,LiderFailureContext.phase("/api/drama/chosen-luna"))
        assertEquals(LiderFailureContext.Phase.VIDEO_API,LiderFailureContext.phase("/api/video"))
        assertEquals(LiderFailureContext.Phase.SUBTITLE,LiderFailureContext.phase("/subs/chosen-luna/2.vtt"))
    }
    @Test fun classifiesBoundedDenialsWithoutReturningBodyOrRuntimeValues() {
        val sensitive="{\"url\":\"https://example.invalid/?token=FIXTURE_NOT_RUNTIME\"}"
        assertEquals(LiderFailureContext.Kind.JSON,LiderFailureContext.kind("application/json",false,sensitive))
        assertEquals(LiderFailureContext.Kind.HTML,LiderFailureContext.kind("text/html",false,"<html>Forbidden</html>"))
        assertEquals(LiderFailureContext.Kind.BROWSER_CHALLENGE,LiderFailureContext.kind("text/html",true,null))
        assertEquals(LiderFailureContext.Kind.UNREADABLE,LiderFailureContext.kind(null,false,null))
    }
}
