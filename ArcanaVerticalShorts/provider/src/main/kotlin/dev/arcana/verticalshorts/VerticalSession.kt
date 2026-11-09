package dev.arcana.verticalshorts

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException

/** Real website guest context. No desktop identity/cookie import or denied-API workaround. */
internal class VerticalSession(context: Context) {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private val limits = this.context.getSharedPreferences("arcana_verticalshorts_network", Context.MODE_PRIVATE)
    private var browser: WebView? = null
    private var script: DocumentScripts.Handle? = null
    private var observerKey: String? = null
    @Volatile private var closed = false
    @Volatile private var stopped = false
    @Volatile private var sourceStarted = false
    @Volatile private var mainError = 0
    @Volatile private var networkError = 0
    @Volatile private var sslError = false
    @Volatile private var starts = 0
    // A current approved main-document navigation, not the lifetime sourceStarted flag.
    // Original page resources may be required before document completion.
    private data class Navigation(val generation: Long, val url: String)
    @Volatile private var navigation: Navigation? = null
    private var nextGeneration = 0L // Main thread only.
    private val resourceDiagnostics = ConcurrentHashMap.newKeySet<String>()
    private var ready = false
    private var busy = false
    @Volatile private var lastWatchDiagnostic: String? = null
    var userAgent: String = ""
        private set
    private val idle = Runnable { if (!busy) destroy("idle") }

    class WatchResult(val html: String, val body: String) { override fun toString() = "WebsiteResult([redacted])" }
    fun permitted() {
        if (closed) throw ErrorLoadingException("VerticalShorts eklentisi kapalı.")
        if (stopped) throw ErrorLoadingException("VerticalShorts normal kaynak erişimini durdurdu.")
        if (System.currentTimeMillis() < limits.getLong("retry_until_ms", 0L)) throw ErrorLoadingException("VerticalShorts istek sınırı bildirdi; daha sonra deneyin.")
    }
    private fun stop(reason: String = "access") {
        if (!stopped) {
            android.util.Log.i("ArcanaVerticalShorts", "source_stop reason=" + reason)
            lastWatchDiagnostic?.let { android.util.Log.i("ArcanaVerticalShorts", "watch_state stage=last_observed_before_stop " + it) }
        }
        stopped = true; ready = false; navigation = null
    }
    private fun policyKind(uri: android.net.Uri): String = when {
        uri.path == "/api/site/play" -> "play_request_policy"
        uri.path?.startsWith("/api/img") == true -> "image_request_policy"
        uri.path?.startsWith("/api/stream") == true -> "media_request_policy"
        uri.path?.startsWith("/_next/") == true -> "script_request_policy"
        else -> "source_resource_policy"
    }
    fun deny() {
        stop()
        handler.post { browser?.stopLoading() }
    }
    fun rememberRate(header: String?) {
        hold(header)
        handler.post { browser?.stopLoading() }
    }
    private fun hold(header: String?) { VerticalHold.remember(limits, header); ready = false; navigation = null }
    private suspend fun begin() = withContext(Dispatchers.Main.immediate) {
        permitted(); handler.removeCallbacks(idle); busy = true
    }
    private suspend fun finish() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        observerKey?.let { key -> browser?.evaluateJavascript("""(function(){var s=window[${JSONObject.quote(key)}];if(s){s.closed=true;if(s.readers)s.readers.forEach(function(r){try{Promise.resolve(r.cancel()).catch(function(){});}catch(_){}});}})()""", null) }
        browser?.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(v){v.pause();});", null)
        busy = false; handler.removeCallbacks(idle)
        if (closed || stopped || !ready || System.currentTimeMillis() < limits.getLong("retry_until_ms", 0L))
            destroy(when { closed -> "closed"; stopped -> "stopped"; System.currentTimeMillis() < limits.getLong("retry_until_ms", 0L) -> "held"; else -> "unfinished" })
        else handler.postDelayed(idle, 90_000)
    }
    private fun destroy(reason: String) {
        navigation = null
        val previous = browser
        browser = null // Fence old callbacks BEFORE any physical stop/pause/destruction.
        resourceDiagnostics.clear(); lastWatchDiagnostic = null
        script?.remove(); script = null; observerKey = null; ready = false
        if (previous != null) {
            android.util.Log.i("ArcanaVerticalShorts", "view_lifecycle event=destroy reason=" + reason)
            previous.stopLoading(); previous.onPause(); previous.destroy()
        }
    }
    private fun securityAsset(raw: String): Boolean = VerticalContract.own(raw)?.path?.endsWith("/" + VerticalObserver.SECURITY_ASSET) == true
    private fun verificationResource(raw: String): Boolean = runCatching {
        val u = URI(raw)
        VerticalContract.https(raw) != null && u.host == "challenges.cloudflare.com" && u.port in setOf(-1,443)
    }.getOrDefault(false)
    private fun mainAllowed(raw: String): Boolean {
        val u = VerticalContract.own(raw) ?: return false
        return u.path in setOf("/tr", "/tr/") || u.path.startsWith("/tr/drama/") || u.path.startsWith("/tr/watch/")
    }
    private fun loadDocument(url: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(mainAllowed(url))
        navigation = null; lastWatchDiagnostic = null // Invalidate old document and diagnostic state.
        browser!!.loadUrl(url)
    }
    private fun resourceKind(uri: android.net.Uri): String = when {
        uri.path.orEmpty().lowercase().let { p -> listOf(".m3u8", ".mp4", ".mpd", ".ts", ".m4s", ".aac", ".webm").any { suffix -> p.endsWith(suffix) } } -> "media"
        uri.path.orEmpty().lowercase().let { p -> listOf(".jpg", ".jpeg", ".png", ".webp", ".avif", ".svg").any { suffix -> p.endsWith(suffix) } } -> "image"
        uri.path.orEmpty().lowercase().endsWith(".js") -> "script"
        else -> "other"
    }
    private fun requestCurrent(view: WebView): Boolean = browser === view && !closed && !stopped &&
        System.currentTimeMillis() >= limits.getLong("retry_until_ms", 0L)
    private fun publicResource(view: WebView, request: WebResourceRequest): Boolean {
        val context = navigation ?: return false
        if (!requestCurrent(view) || !mainAllowed(context.url)) return false
        val host = VerticalContract.browserResourceHost(request.url.toString(), request.method, request.isForMainFrame) ?: return false
        val kind = resourceKind(request.url)
        return try {
            // This preflight rejects currently non-public DNS answers. Chromium performs its
            // own resolution/redirects; this is NOT a pinned-DNS or complete SSRF sandbox.
            VerticalHttp.publicAddresses(host)
            val stillCurrent = requestCurrent(view) && navigation?.generation == context.generation
            if (stillCurrent && resourceDiagnostics.add("allowed:" + kind))
                android.util.Log.i("ArcanaVerticalShorts", "public_resource result=allowed kind=" + kind)
            stillCurrent // The original browser request/headers/cookies remain untouched.
        } catch (_: Exception) {
            if (resourceDiagnostics.add("rejected:" + kind))
                android.util.Log.i("ArcanaVerticalShorts", "public_resource result=rejected kind=" + kind)
            false
        }
    }
    private fun watchCurrent(view: WebView, generation: Long, chapter: VerticalContract.Chapter): Boolean {
        val current = navigation ?: return false
        return requestCurrent(view) && current.generation == generation && mainAllowed(current.url) &&
            runCatching { VerticalContract.sameWatchDocument(VerticalContract.BASE, URI(current.url).rawPath, chapter) }.getOrDefault(false)
    }
    private fun mutationDiagnostic(uri: android.net.Uri) {
        fun present(key: String) = runCatching { uri.getQueryParameter(key) != null }.getOrDefault(false)
        android.util.Log.i("ArcanaVerticalShorts", "play_policy fresh=" + present("fresh") + " failed=" + present("failed") + " refresh=" + present("refresh") + " recheck=" + present("recheck"))
    }
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureView() = withContext(Dispatchers.Main.immediate) {
        permitted()
        if (browser != null) return@withContext
        sourceStarted = false
        browser = WebView(context).apply {
            userAgent = settings.userAgentString
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage) = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!requestCurrent(view)) return rejected()
                    val target = request.url ?: return rejected()
                    val raw = target.toString()
                    if (!VerticalUrlPolicy.readOnly(runCatching { URI(raw) }.getOrElse { return rejected() })) {
                        if (target.path == "/api/site/play") mutationDiagnostic(target)
                        if (target.host?.lowercase()?.trimEnd('.')?.let { it == "verticalshorts.ai" || it.endsWith(".verticalshorts.ai") } == true) { stop(policyKind(target)); this@VerticalSession.handler.post { if (browser === view) view.stopLoading() } }
                        return rejected()
                    }
                    // Original first-party guard/security modules are never replaced or suppressed.
                    if (request.method in setOf("GET", "HEAD") && VerticalContract.own(raw) != null || verificationResource(raw)) return if (requestCurrent(view)) null else rejected()
                    if (publicResource(view, request)) return null
                    return rejected()
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (browser !== view || closed) return true // Ignore late callbacks from an owned, detached view.
                    val raw = request.url.toString()
                    if (request.isForMainFrame && raw == "about:blank" && sourceStarted) { stop("source_blank"); return false }
                    if (request.isForMainFrame && !mainAllowed(raw)) { stop("main_frame_policy"); return true }
                    return !request.isForMainFrame && VerticalContract.own(raw) == null && !verificationResource(raw)
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    if (browser !== view || closed) return
                    starts++
                    if (url != "about:blank") lastWatchDiagnostic = null
                    navigation = url?.takeIf(::mainAllowed)?.let { Navigation(++nextGeneration, it) }
                    if (url == "about:blank" && sourceStarted) stop("source_blank")
                    if (url != null && VerticalContract.own(url) != null) sourceStarted = true
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (browser !== view || closed) return
                    val raw = request.url.toString(); val own = VerticalContract.own(raw) != null
                    if (own && response.statusCode == 429) hold(response.responseHeaders?.entries?.firstOrNull { it.key.equals("Retry-After", true) }?.value)
                    if (request.isForMainFrame) { mainError = response.statusCode; if (response.statusCode >= 400) navigation = null }
                    if (own && request.url.path == "/api/site/play") android.util.Log.i("ArcanaVerticalShorts", "play_http status=" + response.statusCode)
                    if (securityAsset(raw) || own && (request.isForMainFrame || request.url.path == "/api/site/play") && response.statusCode in setOf(401,403)) stop(if (securityAsset(raw)) "security_http" else "source_http_" + response.statusCode)
                    if (securityAsset(raw) && response.statusCode >= 400) stop()
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (browser !== view || closed) return
                    if (request.isForMainFrame) { networkError = error.errorCode; navigation = null }
                    if (securityAsset(request.url.toString())) stop()
                }
                override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: android.net.http.SslError) {
                    if (browser === view && !closed) { sslError = true; stop("source_tls") }; sslHandler.cancel()
                }
            }
            val display = context.resources.displayMetrics
            measure(View.MeasureSpec.makeMeasureSpec(display.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(display.heightPixels, View.MeasureSpec.EXACTLY))
            layout(0, 0, display.widthPixels, display.heightPixels); onResume()
        }
    }
    private fun rejected() = WebResourceResponse("text/plain", "UTF-8", 403, "Read-only source resource", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
    private suspend fun eval(script: String): String = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { c ->
            val view = browser
            if (view == null || closed) { c.resumeWithException(ErrorLoadingException("VerticalShorts kaynak tarayıcısı kapalı.")); return@suspendCancellableCoroutine }
            view.evaluateJavascript(script) { value -> if (c.isActive) c.resume(value, onCancellation = { _, _, _ -> }) }
        }
    }
    private suspend fun json(script: String): JSONObject = try { JSONObject(JSONTokener(eval(script)).nextValue() as String) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw ErrorLoadingException("VerticalShorts kaynak yanıtı tanınmadı.") }
    private suspend fun state(key: String? = null): JSONObject = json("""JSON.stringify((function(){var g=window.__dsGuard,o=${key?.let { "window[" + JSONObject.quote(it) + "]" } ?: "null"};return {origin:location.origin,path:location.pathname,doc:document.readyState,guardPresent:!!g&&typeof g.open==='boolean'&&typeof g.bait==='boolean'&&typeof g.lib==='boolean',guardOpen:!!g&&(g.open||g.bait||g.lib),guardBait:!!g&&g.bait===true,guardLib:!!g&&g.lib===true,guardConsistent:!!g&&g.open===(g.bait||g.lib),cards:document.querySelectorAll('a[href*="/drama/"]').length,videoCount:document.querySelectorAll('video').length,videoSource:!!document.querySelector('video')&&(!!document.querySelector('video').currentSrc||!!document.querySelector('video').getAttribute('src')),videoError:!!(document.querySelector('video')&&document.querySelector('video').error),observer:o?{terminal:o.terminal,closed:o.closed,error:o.error,seenPlay:o.seenPlay,selection:o.selection,latest:o.latest,securityScriptDelivered:o.securityScriptDelivered,result:o.result}:null};})())""")
    private fun checkState(s: JSONObject) {
        permitted()
        if (mainError >= 400 || networkError != 0 || sslError) throw ErrorLoadingException("VerticalShorts kaynak sayfası açılamadı.")
        if (s.optString("origin") != VerticalContract.BASE || !s.optBoolean("guardPresent") || !s.optBoolean("guardConsistent") || s.optBoolean("guardOpen")) {
            if (s.optBoolean("guardOpen") || s.optString("origin") == "null") stop()
            throw ErrorLoadingException("VerticalShorts normal kaynak doğrulaması tamamlanmadı.")
        }
    }
    private suspend fun root() {
        ensureView(); permitted()
        if (ready) { checkState(state()); return }
        val before = starts
        withContext(Dispatchers.Main.immediate) {
            mainError = 0; networkError = 0; sslError = false
            loadDocument(VerticalContract.BASE + "/tr")
        }
        withTimeout(20_000) {
            while (true) {
                permitted()
                if (mainError >= 400 || sslError || networkError != 0) throw ErrorLoadingException("VerticalShorts kaynak başlangıcı tamamlanmadı.")
                if (starts <= before) { delay(100); continue }
                val s = state()
                if (s.optBoolean("guardOpen")) { stop("source_guard"); permitted() }
                if (s.optString("origin") == VerticalContract.BASE && s.optString("doc") == "complete" && s.optInt("cards") > 0 && s.optBoolean("guardPresent") && s.optBoolean("guardConsistent")) {
                    checkState(s); ready = true; return@withTimeout
                }
                delay(100)
            }
        }
    }
    /** Public metadata only. This method cannot call the site-only media API. */
    suspend fun text(url: String): String = mutex.withLock {
        val u = VerticalContract.own(url) ?: throw ErrorLoadingException("VerticalShorts kaynak adresi tanınmadı.")
        val parts = u.path.trim('/').split('/')
        require(!u.path.contains('%') && !u.path.contains('\\') && parts.none { it == "." || it == ".." })
        val publicDocument = parts == listOf("tr") || parts == listOf("tr", "search") ||
            parts.size in 4..5 && parts[0] == "tr" && parts[1] == "drama" && VerticalContract.detailUrl(url) ||
            parts.size == 5 && parts[0] == "tr" && parts[1] == "watch" && parts[4].toIntOrNull() in 1..10000
        require(publicDocument) // Media/API paths are never eligible for the metadata fetcher.
        val key = "avmeta" + UUID.randomUUID().toString().replace("-", "")
        try {
            begin(); root(); checkState(state())
            eval("""(function(){var k=${JSONObject.quote(key)},c=new AbortController();window[k]={done:false,c:c};fetch(${JSONObject.quote(url)},{credentials:'same-origin',cache:'no-store',redirect:'error',signal:c.signal}).then(async function(r){var reader=r.body&&r.body.getReader(),n=0,t='',d=new TextDecoder();if([401,403,429].includes(r.status)){if(r.body)await r.body.cancel();if(window[k])window[k]={done:true,status:r.status,retry:(r.headers.get('Retry-After')||'').slice(0,160)};return;}if(!reader)throw Error('body');for(;;){var p=await reader.read();if(p.done)break;n+=p.value.byteLength;if(n>3000000){await reader.cancel();throw Error('limit');}t+=d.decode(p.value,{stream:true});}t+=d.decode();if(window[k])window[k]={done:true,status:r.status,origin:new URL(r.url).origin,retry:(r.headers.get('Retry-After')||'').slice(0,160),body:t};}).catch(function(){if(window[k])window[k]={done:true,error:true};});})()""")
            withTimeout(18_000) {
                while (true) {
                    checkState(state())
                    val r = json("JSON.stringify(window[" + JSONObject.quote(key) + "]||{done:false})")
                    if (r.optBoolean("done")) {
                        if (r.optInt("status") == 429) hold(r.optString("retry"))
                        if (r.optInt("status") in setOf(401,403)) stop()
                        permitted()
                        if (r.optBoolean("error") || r.optInt("status") != 200 || r.optString("origin") != VerticalContract.BASE || r.isNull("body")) throw ErrorLoadingException("VerticalShorts metadata yanıtı alınamadı.")
                        val body = r.getString("body")
                        val denial = VerticalContract.jsonDenial(body)
                        if (denial == VerticalContract.Denial.RATE) hold(r.optString("retry"))
                        if (denial != null || body.trimStart().startsWith("<") && VerticalContract.deniedHtml(body)) { stop(); permitted() }
                        return@withTimeout body
                    }
                    delay(80)
                }
                @Suppress("UNREACHABLE_CODE") ""
            }
        } catch (_: TimeoutCancellationException) { throw ErrorLoadingException("VerticalShorts kaynak yanıtı zamanında tamamlanmadı.") }
        finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { browser?.evaluateJavascript("(function(){var k=" + JSONObject.quote(key) + ",o=window[k];if(o&&o.c)o.c.abort();delete window[k];})()", null) }
            finish()
        }
    }
    /** Let the unmodified website make its own normal play request; observe its actual result. */
    suspend fun watch(c: VerticalContract.Chapter): WatchResult = mutex.withLock {
        if (c.locked) throw ErrorLoadingException("Bu bölüm kaynakta kilitli.")
        val key = "avwatch" + UUID.randomUUID().toString().replace("-", "")
        var lastWait: String? = null
        var waitChanges = 0
        fun waitPhase(reason: String) {
            if (reason != lastWait && waitChanges < 12) {
                lastWait = reason; waitChanges++
                android.util.Log.i("ArcanaVerticalShorts", "watch_wait reason=" + reason)
            }
        }
        var identityLogged = false
        try {
            begin(); root()
            val started = withContext(Dispatchers.Main.immediate) {
                permitted()
                val view = browser ?: throw ErrorLoadingException("VerticalShorts kaynak tarayıcısı kapalı.")
                val before = starts
                script?.remove()
                script = DocumentScripts.install(view, VerticalObserver.script(key, c.platform, c.id, c.number, c.lang, c.episodeId), VerticalContract.BASE)
                observerKey = key; ready = false; mainError = 0; networkError = 0; sslError = false
                loadDocument(c.url)
                view to before
            }
            var watchGeneration: Long? = null
            withTimeout(25_000) {
                while (true) {
                    permitted()
                    if (mainError >= 400 || networkError != 0 || sslError) throw ErrorLoadingException("VerticalShorts normal oynatıcı erişimi tamamlanmadı.")
                    // Before touching JS, require a new approved own main navigation in this view.
                    val current = navigation
                    if (watchGeneration == null) {
                        if (starts <= started.second || browser !== started.first || current == null || !watchCurrent(started.first, current.generation, c)) {
                            waitPhase("navigation_start"); delay(100); continue
                        }
                        watchGeneration = current.generation
                    }
                    val generation = watchGeneration ?: throw ErrorLoadingException("VerticalShorts kaynak gezintisi hazır değil.")
                    if (!watchCurrent(started.first, generation, c)) throw ErrorLoadingException("VerticalShorts kaynak sayfası değişti.")
                    val s = state(key)
                    if (!watchCurrent(started.first, generation, c)) throw ErrorLoadingException("VerticalShorts kaynak sayfası değişti.")
                    lastWatchDiagnostic = VerticalContract.diagnosticSummary(s, URI(c.url).rawPath)
                    if (s.optBoolean("guardOpen")) { stop("source_guard"); permitted() }
                    val o = s.optJSONObject("observer")
                    val r = o?.optJSONObject("result")
                    // Known denial/hold is terminal independently of document readiness.
                    if (r?.optInt("status") == 429) { hold(r.optString("retryAfter")); stop("denied"); permitted() }
                    if (r?.optInt("status") in setOf(401, 403)) { stop("denied"); permitted() }
                    if (o?.optBoolean("terminal") == true || !(o?.opt("error") as? String).isNullOrBlank()) {
                        if (r?.optInt("status") == 429 || (r?.opt("body") as? String)?.let(VerticalContract::jsonDenial) == VerticalContract.Denial.RATE) hold(r?.optString("retryAfter"))
                        val reason = o?.optString("error").orEmpty().takeIf { it in setOf("denied", "body_denied", "guard_not_permitted", "security_resource", "response_route", "response_body", "response_limit", "response_unavailable", "network") } ?: "observer"
                        stop(reason); permitted()
                    }
                    val observedBody = r?.opt("body") as? String
                    if (observedBody != null) {
                        val denial = VerticalContract.jsonDenial(observedBody)
                        if (denial == VerticalContract.Denial.RATE) hold(r.optString("retryAfter"))
                        if (denial != null || observedBody.trimStart().startsWith("<") && VerticalContract.deniedHtml(observedBody)) { stop(); permitted() }
                    }
                    if (!VerticalContract.sameWatchDocument(s.optString("origin"), s.optString("path"), c)) {
                        waitPhase("document_identity"); delay(100); continue
                    }
                    if (s.optString("doc") != "complete") { waitPhase("document_loading"); delay(100); continue }
                    if (!s.optBoolean("guardPresent") || !s.optBoolean("guardConsistent")) { stop("source_guard_missing"); permitted() }
                    checkState(s)
                    waitPhase(when {
                        o?.optBoolean("securityScriptDelivered") != true -> "security_delivery"
                        r == null || r.isNull("body") -> "original_response"
                        s.optInt("videoCount") != 1 || !s.optBoolean("videoSource") -> "video_pending"
                        s.optBoolean("videoError") -> "video_error"
                        else -> "ready"
                    })
                    if (o?.optBoolean("securityScriptDelivered") == true && r != null && s.optInt("videoCount") == 1 && s.optBoolean("videoSource") && !s.optBoolean("videoError")) {
                        if (!identityLogged) {
                            identityLogged = true
                            withContext(Dispatchers.Main.immediate) {
                                val raw = browser?.url.orEmpty()
                                val javaUrl = runCatching { URI(raw) }.getOrNull()
                                val legacy = VerticalContract.sameSeriesPage(raw, c.platform, c.id, c.coded, c.number)
                                android.util.Log.i("ArcanaVerticalShorts", "document_identity legacy_url_match=" + legacy +
                                    " observed_dom_match=true java_url_present=" + raw.isNotEmpty() +
                                    " java_path_match=" + (javaUrl?.rawPath == URI(c.url).rawPath) +
                                    " java_query_present=" + (javaUrl?.rawQuery != null) +
                                    " java_fragment_present=" + (javaUrl?.rawFragment != null))
                            }
                            if (!watchCurrent(started.first, generation, c)) throw ErrorLoadingException("VerticalShorts kaynak sayfası değişti.")
                        }
                        val status = r.optInt("status")
                        permitted()
                        if (status != 200 || r.isNull("body")) throw ErrorLoadingException("VerticalShorts kullanılabilir video sağlamadı.")
                        val html = json("JSON.stringify((function(){var h=document.documentElement.outerHTML;if(new TextEncoder().encode(h).byteLength>3000000)return {large:true};return {html:h};})())").getString("html")
                        if (!watchCurrent(started.first, generation, c)) throw ErrorLoadingException("VerticalShorts kaynak sayfası değişti.")
                        require(html.toByteArray(Charsets.UTF_8).size <= 3_000_000)
                        VerticalContract.verifyWatch(html, c)
                        val finalState = state(key)
                        if (!watchCurrent(started.first, generation, c)) throw ErrorLoadingException("VerticalShorts kaynak sayfası değişti.")
                        checkState(finalState)
                        if (!VerticalContract.sameWatchDocument(finalState.optString("origin"), finalState.optString("path"), c)) throw ErrorLoadingException("VerticalShorts bölüm sayfası değişti.")
                        ready = true
                        return@withTimeout WatchResult(html, r.getString("body"))
                    }
                    delay(80)
                }
                @Suppress("UNREACHABLE_CODE") throw ErrorLoadingException("VerticalShorts video sağlamadı.")
            }
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw timeout
            android.util.Log.i("ArcanaVerticalShorts", "watch_end result=timeout")
            try {
                val snapshot = withTimeoutOrNull(1200) { state(key) }
                if (snapshot != null) android.util.Log.i("ArcanaVerticalShorts", "watch_state stage=timeout_before_cleanup " + VerticalContract.diagnosticSummary(snapshot, URI(c.url).rawPath))
                else android.util.Log.i("ArcanaVerticalShorts", "watch_state stage=timeout_before_cleanup unavailable=deadline")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { android.util.Log.i("ArcanaVerticalShorts", "watch_state stage=timeout_before_cleanup unavailable=" + failure.javaClass.simpleName) }
            throw ErrorLoadingException("VerticalShorts normal oynatıcı yanıtı zamanında tamamlanmadı.")
        } finally { finish() }
    }
    suspend fun cookies(url: String): String {
        permitted()
        if (VerticalContract.own(url) == null) return ""
        return withContext(Dispatchers.Main.immediate) { permitted(); CookieManager.getInstance().getCookie(url).orEmpty() }
    }
    fun close() {
        closed = true; ready = false
        handler.post { handler.removeCallbacks(idle); destroy("closed") }
    }
}
