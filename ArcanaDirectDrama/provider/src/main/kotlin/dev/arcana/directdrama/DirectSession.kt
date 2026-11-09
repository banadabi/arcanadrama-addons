package dev.arcana.directdrama

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
import java.util.UUID
import kotlin.coroutines.resumeWithException

/** Normal source WebView and first-party fetch. No profile import or fingerprint overrides. */
internal class DirectSession(context: Context) {
    private val context=context.applicationContext
    private val lock=Mutex()
    private val handler=Handler(Looper.getMainLooper())
    private var browser: WebView?=null
    @Volatile private var ready=false
    @Volatile private var closed=false
    @Volatile private var accessStopped=false
    private val limits=this.context.getSharedPreferences("arcana_directdrama_network",Context.MODE_PRIVATE)
    @Volatile private var mainFrameError=0
    @Volatile private var mainNetworkError=0
    @Volatile private var mainSslError=-1
    @Volatile private var sslKind="none"
    @Volatile private var starts=0
    @Volatile private var finishes=0
    @Volatile private var blockedNavigations=0
    @Volatile private var blockedKind="none"
    private fun urlKind(raw:String?):String = runCatching {
        if(raw.isNullOrBlank() || raw=="about:blank")return@runCatching "blank"
        if(raw.startsWith("chrome-error:"))return@runCatching "browser-error"
        val u=java.net.URI(raw)
        when {
            u.scheme=="https" && u.host=="directdrama.com" && u.path in setOf("/tr","/tr/") -> "source-root"
            u.scheme=="https" && u.host=="directdrama.com" -> "source-other"
            u.scheme=="https" && u.host=="directdrama.com" -> "source-www"
            u.scheme=="http" && u.host in setOf("directdrama.com","directdrama.com") -> "source-http"
            u.scheme=="https" -> "foreign-https"
            else -> "other"
        }
    }.getOrDefault("invalid")
    // This is the publisher-declared normal verification resource. No token injection,
    // widget interaction, fingerprint override or personal Chrome cookie import occurs.
    private fun authResource(raw: String): Boolean = runCatching {
        val u = java.net.URI(raw)
        DirectContract.https(raw) != null && u.scheme == "https" && u.host == "challenges.cloudflare.com" && u.port in setOf(-1,443) && u.rawUserInfo == null
    }.getOrDefault(false)
    var userAgent=""
        private set
    private var busy=false
    private val idleClose=Runnable { if(!busy)destroyOwnedBrowser() }
    private fun destroyOwnedBrowser() {
        ready=false;browser?.let { it.onPause();it.stopLoading();it.destroy() };browser=null
    }
    private suspend fun startCall() = withContext(Dispatchers.Main.immediate) {
        checkHold()
        handler.removeCallbacks(idleClose);busy=true
    }
    private suspend fun endCall() = withContext(NonCancellable+Dispatchers.Main.immediate) {
        handler.removeCallbacks(idleClose)
        busy=false
        if(!ready || closed || accessStopped || System.currentTimeMillis()<limits.getLong("retry_until_ms",0L))destroyOwnedBrowser()else handler.postDelayed(idleClose,90_000)
    }

    private suspend fun eval(script: String): String = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { c ->
            val view=browser
            if(view==null || closed) { c.resumeWithException(ErrorLoadingException("DirectDrama tarayıcı oturumu kapalı."));return@suspendCancellableCoroutine }
            view.evaluateJavascript(script) { value -> if(c.isActive)c.resume(value,onCancellation={_,_,_->}) }
        }
    }
    private suspend fun objectValue(script: String): JSONObject {
        val raw=eval(script)
        return try { JSONObject(JSONTokener(raw).nextValue() as String) }
            catch (_: Exception) { throw ErrorLoadingException("DirectDrama tarayıcı yanıtı tanınmadı.") }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun bootstrap() {
        checkHold()
        if(closed)throw ErrorLoadingException("DirectDrama oturumu kapalı.")
        if(accessStopped)throw ErrorLoadingException("DirectDrama kaynak erişimi durdu.")
        if(ready)return
        withContext(Dispatchers.Main.immediate) {
            if(browser==null) {
                browser=WebView(context).apply {
                    userAgent=settings.userAgentString
                    settings.javaScriptEnabled=true;settings.domStorageEnabled=true
                    settings.allowFileAccess=false;settings.allowContentAccess=false
                    settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.mediaPlaybackRequiresUserGesture=true
                    settings.javaScriptCanOpenWindowsAutomatically=false;settings.setSupportMultipleWindows(false)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
                    webChromeClient=object:WebChromeClient() { override fun onConsoleMessage(message:ConsoleMessage)=true }
                    webViewClient=object:WebViewClient() {
                        override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                            if(request.method in setOf("GET","HEAD") && DirectContract.own(request.url.toString())!=null || authResource(request.url.toString()))return null
                            return WebResourceResponse("text/plain","UTF-8",403,"Source resource only",emptyMap(),java.io.ByteArrayInputStream(byteArrayOf()))
                        }
                        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                            val reject=DirectContract.own(request.url.toString())==null && !( !request.isForMainFrame && authResource(request.url.toString()) )
                            if(reject && request.isForMainFrame) { blockedNavigations++;blockedKind=urlKind(request.url.toString()) }
                            return reject
                        }
                        override fun onPageStarted(view:WebView,url:String?,favicon:android.graphics.Bitmap?) { starts++ }
                        override fun onPageFinished(view:WebView,url:String?) { finishes++ }
                        override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError) {
                            if(request.isForMainFrame)mainNetworkError=error.errorCode
                        }
                        override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:android.net.http.SslError) {
                            mainSslError=error.primaryError;sslKind=urlKind(error.url)
                            accessStopped=true;ready=false
                            handler.cancel()
                        }
                        override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse) {
                            if(DirectContract.own(request.url.toString())!=null && response.statusCode==429) {
                                rememberHold(response.responseHeaders?.entries?.firstOrNull { it.key.equals("Retry-After",true) }?.value)
                                ready=false
                                if(browser===view && !closed)view.stopLoading()
                            }
                            if(request.isForMainFrame && response.statusCode>=400) {
                                mainFrameError=response.statusCode;ready=false
                                if(response.statusCode in setOf(401,403,429)) {
                                    if(response.statusCode!=429)accessStopped=true
                                    if(browser===view && !closed)view.stopLoading()
                                }
                            }
                        }
                    }
                    val d=context.resources.displayMetrics
                    // Initialize this owned view with its real display size; do not resume process-global timers.
                    measure(View.MeasureSpec.makeMeasureSpec(d.widthPixels,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(d.heightPixels,View.MeasureSpec.EXACTLY))
                    layout(0,0,d.widthPixels,d.heightPixels)
                    onResume()
                }
            }
            mainFrameError=0;mainNetworkError=0;mainSslError=-1;sslKind="none";starts=0;finishes=0;blockedNavigations=0;blockedKind="none"
            browser!!.loadUrl(DirectContract.BASE+"/tr")
        }
        val started=android.os.SystemClock.elapsedRealtime()
        var last=JSONObject()
        try {
            withTimeout(20_000) {
                while(true) {
                    checkHold()
                    if(mainFrameError>=400) { if(mainFrameError in setOf(401,403))accessStopped=true;throw ErrorLoadingException("DirectDrama kaynak erişimi istiyor (HTTP $mainFrameError).") }
                    // Do not evaluate the initial empty JavaScript document while the first navigation is pending.
                    val navigationStarted=withContext(Dispatchers.Main.immediate) {
                        if(closed || browser==null)throw ErrorLoadingException("DirectDrama tarayıcı oturumu kapalı.")
                        starts>0 && urlKind(browser?.originalUrl)=="source-root"
                    }
                    if(!navigationStarted) { delay(100);continue }
                    val state=objectValue("""JSON.stringify({origin:location.origin,title:document.title,blocked:/Access Denied|Attention Required|you have been blocked|verify you are human|captcha/i.test(document.title+' '+(document.body?.innerText||'').slice(0,500)),cards:document.querySelectorAll('a[href*="/dizi/"]').length,doc:document.readyState,vw:innerWidth,vh:innerHeight,bodyChars:(document.body?.innerText||'').length,menu:!!document.getElementById('mobileMenu'),pathKind:location.pathname.replace(/\/$/,'')==='/tr'?'root':location.pathname==='/giris'?'login':'other'})""")
                    last=JSONObject().put("originMatches",state.optString("origin")==DirectContract.BASE)
                        .put("cards",state.optInt("cards")).put("viewportWidth",state.optInt("vw")).put("viewportHeight",state.optInt("vh"))
                        .put("bodyChars",state.optInt("bodyChars")).put("menu",state.optBoolean("menu"))
                        .put("doc",state.optString("doc").takeIf { it in setOf("loading","interactive","complete") } ?: "unknown")
                        .put("pathKind",state.optString("pathKind").takeIf { it in setOf("root","login","other") } ?: "unknown")
                    if(state.optBoolean("blocked")) { accessStopped=true;throw ErrorLoadingException("DirectDrama kaynak doğrulaması istiyor.") }
                    if(state.optString("origin")==DirectContract.BASE && state.optInt("cards")>0) { markReady(DirectContract.BASE+"/tr");android.util.Log.i("ArcanaDirectDrama","bootstrap cards=${state.optInt("cards")} elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}");break }
                    delay(100)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Only bounded structure/enum fields: never a URL, page title, HTML, cookie or account field.
            withContext(NonCancellable+Dispatchers.Main.immediate) {
                last.put("httpError",mainFrameError).put("networkError",mainNetworkError).put("sslError",mainSslError).put("sslUrlKind",sslKind)
                    .put("starts",starts).put("finishes",finishes).put("blockedNavigations",blockedNavigations).put("blockedKind",blockedKind)
                    .put("viewUrlKind",urlKind(browser?.url)).put("originalUrlKind",urlKind(browser?.originalUrl)).put("progress",browser?.progress ?: -1)
                android.util.Log.i("ArcanaDirectDrama","bootstrap timeout "+last.toString())
            }
            throw ErrorLoadingException("DirectDrama normal tarayıcı başlangıcı tamamlanmadı.")
        }
    }
    data class Reply(val status: Int, val body: String, val retryAfter: String) {
        override fun toString() = "SourceReply([redacted])"
    }
    suspend fun text(url: String, referer: String = DirectContract.BASE + "/", json: Boolean = false): String {
        if (!json && (DirectContract.detailSlug(url) != null || DirectContract.route(url) != null)) return lock.withLock {
            try { startCall(); bootstrap(); renderDetail(url) }
            finally { endCall() }
        }
        val reply = reply(url, referer)
        if (reply.status != 200) throw ErrorLoadingException("DirectDrama HTTP ${reply.status} yanıtı verdi.")
        return reply.body
    }
    suspend fun reply(url: String, referer: String = DirectContract.BASE + "/", headers: Map<String,String> = emptyMap()): Reply = lock.withLock {
        if (DirectContract.own(url) == null || DirectContract.own(referer) == null)
            throw ErrorLoadingException("DirectDrama kaynak adresi tanınmadı.")
        val id = UUID.randomUUID().toString().replace("-", "")
        val wireHeaders = JSONObject().put("Accept", "application/json")
        headers.forEach { (key,value) ->
            require(key == "Accept")
            wireHeaders.put(key,value)
        }
        val begin = """(function(){var k=${JSONObject.quote(id)};window.__arcanaDirectJobs=window.__arcanaDirectJobs||{};var c=new AbortController();window.__arcanaDirectJobs[k]={done:false,controller:c};fetch(${JSONObject.quote(url)},{credentials:'same-origin',cache:'no-store',redirect:'error',referrer:${JSONObject.quote(referer)},headers:$wireHeaders,signal:c.signal}).then(async function(r){var origin=new URL(r.url).origin;var retry=(r.headers.get('Retry-After')||'').slice(0,160);if(r.status===401||r.status===403||r.status===429){if(r.body)await r.body.cancel();if(window.__arcanaDirectJobs[k])window.__arcanaDirectJobs[k]={done:true,status:r.status,origin:origin,retryAfter:retry,body:''};return;}var reader=r.body&&r.body.getReader();if(!reader)throw Error('STREAM_REQUIRED');var decoder=new TextDecoder('utf-8'),size=0,b='';for(;;){var item=await reader.read();if(item.done)break;size+=item.value.byteLength;if(size>3000000){await reader.cancel();c.abort();throw Error('BODY_LIMIT');}b+=decoder.decode(item.value,{stream:true});}b+=decoder.decode();if(window.__arcanaDirectJobs[k])window.__arcanaDirectJobs[k]={done:true,status:r.status,origin:origin,retryAfter:retry,body:b};}).catch(function(){if(window.__arcanaDirectJobs[k])window.__arcanaDirectJobs[k]={done:true,network:true};});})()"""
        try {
            startCall(); bootstrap(); checkHold(); eval(begin)
            withTimeout(18_000) {
                while (true) {
                    checkHold()
                    val r = objectValue("""JSON.stringify((function(){var o=window.__arcanaDirectJobs&&window.__arcanaDirectJobs[${JSONObject.quote(id)}];return o&&o.done?o:{done:false};})())""")
                    if (r.optBoolean("done")) {
                        if (r.optBoolean("network")) throw ErrorLoadingException("DirectDrama bağlantısı tamamlanamadı.")
                        val status = r.optInt("status")
                        if (status == 429) rememberHold(r.optString("retryAfter"))
                        if (status in setOf(401,403,429)) {
                            if (status != 429) accessStopped = true
                            ready = false
                            throw ErrorLoadingException("DirectDrama erişim veya doğrulama istiyor (HTTP $status).")
                        }
                        if (r.optString("origin") != DirectContract.BASE || r.isNull("body"))
                            throw ErrorLoadingException("DirectDrama kaynak yanıtı doğrulanamadı.")
                        return@withTimeout Reply(status,r.getString("body"),r.optString("retryAfter"))
                    }
                    delay(80)
                }
                @Suppress("UNREACHABLE_CODE") Reply(0,"","")
            }
        } catch (_: TimeoutCancellationException) {
            throw ErrorLoadingException("DirectDrama isteği zamanında tamamlanmadı.")
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                browser?.evaluateJavascript("""(function(){var k=${JSONObject.quote(id)},j=window.__arcanaDirectJobs&&window.__arcanaDirectJobs[k];if(j&&j.controller)j.controller.abort();if(window.__arcanaDirectJobs)delete window.__arcanaDirectJobs[k];})()""",null)
            }
            endCall()
        }
    }
    private fun checkHold() {
        if(System.currentTimeMillis()<limits.getLong("retry_until_ms",0L))throw ErrorLoadingException("DirectDrama istek sınırı bildirdi; daha sonra deneyin.")
    }
    private suspend fun renderDetail(url:String):String {
        val slug=(DirectContract.detailSlug(url) ?: DirectContract.route(url)?.first) ?: throw ErrorLoadingException("DirectDrama dizi adresi tanınmadı.")
        val before=starts
        withContext(Dispatchers.Main.immediate) {
            ready=false
            mainFrameError=0;mainNetworkError=0;mainSslError=-1
            browser?.loadUrl(url) ?: throw ErrorLoadingException("DirectDrama oturumu kapalı.")
        }
        return withTimeout(20_000) {
            while(true) {
                checkHold()
                if(mainFrameError>=400 || mainSslError>=0)throw ErrorLoadingException("DirectDrama dizi erişimi tamamlanmadı.")
                val started=withContext(Dispatchers.Main.immediate) { starts>before && DirectContract.sameSeriesPage(browser?.url.orEmpty(),slug) }
                if(!started) { delay(100);continue }
                val state=objectValue("""JSON.stringify({origin:location.origin,requestedPathMatches:location.pathname.replace(/\/$/,'')===${JSONObject.quote(java.net.URI(url).path.removeSuffix("/"))},done:document.readyState==='complete',h1:!!document.querySelector('h1'),chapters:document.querySelectorAll('a[href*="/tr/dizi/$slug/bolum-"]').length,blocked:/Access Denied|Attention Required|you have been blocked|verify you are human|captcha/i.test(document.title+' '+(document.body?.innerText||'').slice(0,500))})""")
                if(state.optBoolean("blocked")) { accessStopped=true;ready=false;throw ErrorLoadingException("DirectDrama kaynak doğrulaması istiyor.") }
                if(state.optString("origin")==DirectContract.BASE && state.optBoolean("requestedPathMatches") && state.optBoolean("done") && state.optBoolean("h1") && state.optInt("chapters")>0) {
                    val result=objectValue("""JSON.stringify((function(){var html=document.documentElement.outerHTML;if(new TextEncoder().encode(html).byteLength>3000000)return {large:true};return {body:html};})())""")
                    if(result.optBoolean("large") || result.isNull("body"))throw ErrorLoadingException("DirectDrama dizi yanıtı sınırı aşıldı.")
                    markReady(url)
                    return@withTimeout result.getString("body")
                }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
    }
    private fun rememberHold(header:String?) = DirectHold.remember(limits,header)
    private suspend fun markReady(expected:String) = withContext(Dispatchers.Main.immediate) {
        checkHold()
        val actual=browser?.url.orEmpty()
        if(closed || accessStopped || mainFrameError>=400 || mainSslError>=0 || mainNetworkError!=0 ||
            DirectContract.own(actual)==null || !(java.net.URI(actual).path.trimEnd('/')==java.net.URI(expected).path.trimEnd('/') ||
                DirectContract.detailSlug(expected)?.let { DirectContract.sameSeriesPage(actual,it) }==true))
            throw ErrorLoadingException("DirectDrama kaynak erişimi doğrulanamadı.")
        ready=true
    }
    suspend fun cookies(url:String):String {
        if(DirectContract.own(url)==null || closed)return ""
        return withContext(Dispatchers.Main.immediate) { CookieManager.getInstance().getCookie(url).orEmpty() }
    }
    fun close() {
        closed=true;ready=false
        handler.post { handler.removeCallbacks(idleClose);destroyOwnedBrowser() }
    }
}
