package dev.arcana.liderdrama

/** Caption errors are optional resources; API authorisation and issuer rate limits remain global. */
internal object LiderCaptionPolicy {
    enum class Scope { NONE, CAPTION, GLOBAL }
    fun scope(phase:LiderFailureContext.Phase,status:Int):Scope=when {
        status==429->Scope.GLOBAL
        status in setOf(401,403)->if(phase==LiderFailureContext.Phase.SUBTITLE)Scope.CAPTION else Scope.GLOBAL
        else->Scope.NONE
    }
    fun timed(text:String):Boolean {
        if(text.toByteArray(Charsets.UTF_8).size>2000000)return false
        val t=text.trimStart('\uFEFF',' ','\n','\r','\t')
        if(t.startsWith("<")||t.startsWith("{")||LiderPolicy.challenge(t))return false
        return Regex("(?m)^(?:[0-9]{2,}:)?[0-9]{2}:[0-9]{2}[.,][0-9]{3}\\s+-->\\s+(?:[0-9]{2,}:)?[0-9]{2}:[0-9]{2}[.,][0-9]{3}").containsMatchIn(t)
    }
}


/** An unlock must belong to the exact active denial and dialog lifetime. */
internal class LiderCaptionGate {
    class Ticket internal constructor(val url:String,val generation:Long)
    private var target:String?=null
    private var generation=0L
    private var active:Ticket?=null
    private var confirmationSpent=false
    @Synchronized fun hasTarget()=target!=null
    @Synchronized fun deny(url:String,commit:()->Boolean):Boolean {
        generation++;target=url;active=null;confirmationSpent=false
        return commit()
    }
    @Synchronized fun begin():Ticket? {
        val url=target?:return null
        generation++
        return Ticket(url,generation).also{active=it}
    }
    @Synchronized fun live(ticket:Ticket)=active===ticket&&target==ticket.url&&generation==ticket.generation
    @Synchronized fun admitBody(ticket:Ticket):Boolean {
        if(!live(ticket)||confirmationSpent)return false
        confirmationSpent=true
        return true
    }
    @Synchronized fun end(ticket:Ticket) {
        if(active===ticket){active=null;generation++}
    }
    @Synchronized fun complete(ticket:Ticket,commit:()->Boolean):Boolean {
        if(!live(ticket)||!commit())return false
        active=null;target=null;generation++
        return true
    }
    @Synchronized fun cancel(){active=null;target=null;generation++}
}

/** Per-view document identity; an equal URL after navigation is still a different document. */
internal class LiderCaptionNavigation {
    class Attempt internal constructor(val generation:Long,val document:String)
    class Completion internal constructor(val generation:Long,val document:String,val download:Boolean)
    private var generation=0L
    private var document:String?=null
    private var queued:Attempt?=null
    private var admitted:Attempt?=null
    private var completion:Completion?=null
    private var closed=false
    private var probeCount=0
    @Synchronized fun reserveProbe(attempt:Attempt):Boolean {
        if(!pending(attempt)||probeCount>=4)return false
        probeCount++
        return true
    }
    @Synchronized fun navigationStarted():Boolean {
        generation++;document=null;queued=null;completion=null
        return admitted!=null
    }
    @Synchronized fun committed(value:String):Completion? {
        if(closed||admitted!=null)return null
        document=value
        if(completion?.document!=value)completion=null
        return takeCompletion()
    }
    // WebView can report finish/download before a visible document commit. Remember
    // only the current navigation's signal; a signal alone never queues a body GET.
    @Synchronized fun completed(value:String,download:Boolean):Completion? {
        if(closed||admitted!=null)return null
        val old=completion?.takeIf{it.generation==generation&&it.document==value}
        completion=Completion(generation,value,download||old?.download==true)
        return takeCompletion()
    }
    private fun takeCompletion():Completion? {
        val ready=completion?:return null
        if(ready.generation!=generation||ready.document!=document)return null
        completion=null
        return ready
    }
    @Synchronized fun committedDocument():String?=if(closed)null else document
    @Synchronized fun queue():Attempt? {
        val value=document?:return null
        if(closed||queued!=null||admitted!=null)return null
        return Attempt(generation,value).also{queued=it}
    }
    @Synchronized fun pending(attempt:Attempt)=!closed&&queued===attempt&&generation==attempt.generation&&document==attempt.document
    @Synchronized fun admit(attempt:Attempt):Boolean {
        if(!pending(attempt))return false
        queued=null;admitted=attempt
        return true
    }
    @Synchronized fun current(attempt:Attempt)=!closed&&admitted===attempt&&generation==attempt.generation&&document==attempt.document
    @Synchronized fun hasAdmitted()=admitted!=null
    @Synchronized fun abandon(attempt:Attempt) {if(queued===attempt)queued=null}
    @Synchronized fun close(){closed=true;generation++;document=null;queued=null;admitted=null;completion=null}
}

/** Fixed native-only observations; never include URLs, document text, cookies or challenge tokens. */
internal enum class LiderVerificationEvent {
    OPENED, DOCUMENT_COMMITTED, QUEUED, QUEUED_NAV_CANCELLED, STALE_QUEUE_IGNORED,
    ADMITTED_INJECTION_ATTEMPT, AFTER_ADMISSION_NAV_STOP, DOWNLOAD_OBSERVED,
    BODY_VERIFIED, BODY_REJECTED, BODY_HTTP_401, BODY_HTTP_403, BODY_HTTP_OTHER,
    BUDGET_ALREADY_SPENT, NO_SOURCE_DOCUMENT, PERMISSION_STOP, PERSIST_FAILED,
    RATE_LIMITED, CLOSED, DOCUMENT_CHANGED_AFTER_INJECTION, MAIN_NETWORK_ERROR,
    INITIAL_HTTP_401, INITIAL_HTTP_403, TIMED_DOCUMENT_OBSERVED, DOCUMENT_NO_TRIGGER,
    DOCUMENT_PROBE_TIMED_OUT, DOCUMENT_PROBE_LIMIT
}

/** Only browser-internal and already-approved challenge frames. Never main-frame authority. */
internal object LiderVerificationFramePolicy {
    fun allowRequest(raw:String,mainFrame:Boolean,method:String,normalMainNavigation:Boolean,normalResource:Boolean):Boolean {
        // Resource fallback must never broaden top-level navigation authority.
        if(mainFrame&&!normalMainNavigation)return false
        return allowSubframe(raw,mainFrame,method)||normalResource
    }
    fun allowSubframe(raw:String,mainFrame:Boolean,method:String):Boolean {
        if(mainFrame||method !in setOf("GET","POST"))return false
        if(raw in setOf("about:blank","about:srcdoc"))return method=="GET"
        return runCatching {
            val u=java.net.URI(raw)
            u.scheme=="https"&&u.host=="challenges.cloudflare.com"&&u.rawUserInfo==null&&u.port in setOf(-1,443)
        }.getOrDefault(false)
    }
}
