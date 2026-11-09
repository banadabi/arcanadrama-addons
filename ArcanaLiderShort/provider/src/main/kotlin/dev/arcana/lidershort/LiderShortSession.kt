package dev.arcana.lidershort

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
internal class LiderShortSession(context: Context) {
    private val context=context.applicationContext
    private val lock=Mutex()
    private val handler=Handler(Looper.getMainLooper())
    private var browser: WebView?=null
    @Volatile private var ready=false
    @Volatile private var closed=false
    @Volatile private var accessStopped=false
    private val limits=this.context.getSharedPreferences("arcana_lidershort_network",Context.MODE_PRIVATE)
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
            u.scheme=="https" && u.host=="www.lidershort.com" && u.path in setOf("/tr","/tr/") -> "source-root"
            u.scheme=="https" && u.host=="www.lidershort.com" -> "source-other"
            u.scheme=="https" && u.host=="lidershort.com" -> "source-www"
            u.scheme=="http" && u.host in setOf("lidershort.com","www.lidershort.com") -> "source-http"
            u.scheme=="https" -> "foreign-https"
            else -> "other"
        }
    }.getOrDefault("invalid")
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
            if(view==null || closed) { c.resumeWithException(ErrorLoadingException("LiderShort tarayıcı oturumu kapalı."));return@suspendCancellableCoroutine }
            view.evaluateJavascript(script) { value -> if(c.isActive)c.resume(value,onCancellation={_,_,_->}) }
        }
    }
    private suspend fun objectValue(script: String): JSONObject {
        val raw=eval(script)
        return try { JSONObject(JSONTokener(raw).nextValue() as String) }
            catch (_: Exception) { throw ErrorLoadingException("LiderShort tarayıcı yanıtı tanınmadı.") }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun bootstrap() {
        checkHold()
        if(closed)throw ErrorLoadingException("LiderShort oturumu kapalı.")
        if(accessStopped)throw ErrorLoadingException("LiderShort kaynak erişimi durdu.")
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
                            if(LiderShortContract.own(request.url.toString())!=null && LiderShortContract.captionURL(request.url.toString())!=null)return null
                            return WebResourceResponse("text/plain","UTF-8",403,"Source resource only",emptyMap(),java.io.ByteArrayInputStream(byteArrayOf()))
                        }
                        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                            val reject=LiderShortContract.own(request.url.toString())==null
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
                            if(LiderShortContract.own(request.url.toString())!=null && response.statusCode==429) {
                                rememberHold(response.responseHeaders?.entries?.firstOrNull { it.key.equals("Retry-After",true) }?.value)
                                accessStopped=true;ready=false
                                if(browser===view && !closed)view.stopLoading()
                            }
                            if(request.isForMainFrame && response.statusCode>=400) {
                                mainFrameError=response.statusCode;ready=false
                                if(response.statusCode in setOf(401,403,429)) {
                                    accessStopped=true
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
            browser!!.loadUrl(LiderShortContract.BASE+"/tr")
        }
        val started=android.os.SystemClock.elapsedRealtime()
        var last=JSONObject()
        try {
            withTimeout(20_000) {
                while(true) {
                    checkHold()
                    if(mainFrameError>=400) { if(mainFrameError in setOf(401,403,429))accessStopped=true;throw ErrorLoadingException("LiderShort kaynak erişimi istiyor (HTTP $mainFrameError).") }
                    // Do not evaluate the initial empty JavaScript document while the first navigation is pending.
                    val navigationStarted=withContext(Dispatchers.Main.immediate) {
                        if(closed || browser==null)throw ErrorLoadingException("LiderShort tarayıcı oturumu kapalı.")
                        starts>0 && urlKind(browser?.originalUrl)=="source-root"
                    }
                    if(!navigationStarted) { delay(100);continue }
                    val state=objectValue("""JSON.stringify({origin:location.origin,title:document.title,blocked:/Access Denied|Attention Required|you have been blocked|verify you are human|captcha/i.test(document.title+' '+(document.body?.innerText||'').slice(0,500)),cards:document.querySelectorAll('article a[href*="bolum-"]').length,doc:document.readyState,vw:innerWidth,vh:innerHeight,bodyChars:(document.body?.innerText||'').length,menu:!!document.getElementById('mobileMenu'),pathKind:location.pathname==='/tr'?'root':location.pathname==='/giris'?'login':'other'})""")
                    last=JSONObject().put("originMatches",state.optString("origin")==LiderShortContract.BASE)
                        .put("cards",state.optInt("cards")).put("viewportWidth",state.optInt("vw")).put("viewportHeight",state.optInt("vh"))
                        .put("bodyChars",state.optInt("bodyChars")).put("menu",state.optBoolean("menu"))
                        .put("doc",state.optString("doc").takeIf { it in setOf("loading","interactive","complete") } ?: "unknown")
                        .put("pathKind",state.optString("pathKind").takeIf { it in setOf("root","login","other") } ?: "unknown")
                    if(state.optBoolean("blocked")) { accessStopped=true;throw ErrorLoadingException("LiderShort kaynak doğrulaması istiyor.") }
                    if(state.optString("origin")==LiderShortContract.BASE && state.optInt("cards")>0) { markReady(LiderShortContract.BASE+"/tr");android.util.Log.i("ArcanaLiderShort","bootstrap cards=${state.optInt("cards")} elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}");break }
                    delay(100)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Only bounded structure/enum fields: never a URL, page title, HTML, cookie or account field.
            withContext(NonCancellable+Dispatchers.Main.immediate) {
                last.put("httpError",mainFrameError).put("networkError",mainNetworkError).put("sslError",mainSslError).put("sslUrlKind",sslKind)
                    .put("starts",starts).put("finishes",finishes).put("blockedNavigations",blockedNavigations).put("blockedKind",blockedKind)
                    .put("viewUrlKind",urlKind(browser?.url)).put("originalUrlKind",urlKind(browser?.originalUrl)).put("progress",browser?.progress ?: -1)
                android.util.Log.i("ArcanaLiderShort","bootstrap timeout "+last.toString())
            }
            throw ErrorLoadingException("LiderShort normal tarayıcı başlangıcı tamamlanmadı.")
        }
    }
    suspend fun text(url: String,referer: String=LiderShortContract.BASE+"/tr",json: Boolean=false,ajax: Boolean=false): String = lock.withLock {
        if(LiderShortContract.own(url)==null || LiderShortContract.own(referer)==null)throw ErrorLoadingException("LiderShort kaynak adresi tanınmadı.")
        val id=UUID.randomUUID().toString().replace("-","")
        val headers=JSONObject().put("Accept",if(json)"application/json"else"text/html, */*;q=0.8")
        if(ajax)headers.put("X-Requested-With","XMLHttpRequest")
        val begin="""(function(){var k=${JSONObject.quote(id)};window.__arcanaLiderJobs=window.__arcanaLiderJobs||{};var c=new AbortController();window.__arcanaLiderJobs[k]={done:false,controller:c};fetch(${JSONObject.quote(url)},{credentials:'same-origin',redirect:'error',referrer:${JSONObject.quote(referer)},headers:$headers,signal:c.signal}).then(async function(r){var origin=new URL(r.url).origin;if(r.status!==200){if(r.body)await r.body.cancel();if(window.__arcanaLiderJobs[k])window.__arcanaLiderJobs[k]={done:true,status:r.status,origin:origin,retryAfter:(r.headers.get('Retry-After')||'').slice(0,160),body:''};return;}var reader=r.body&&r.body.getReader();if(!reader)throw Error('STREAM_REQUIRED');var decoder=new TextDecoder('utf-8'),size=0,b='';for(;;){var item=await reader.read();if(item.done)break;size+=item.value.byteLength;if(size>3000000){await reader.cancel();c.abort();throw Error('BODY_LIMIT');}b+=decoder.decode(item.value,{stream:true});}b+=decoder.decode();if(window.__arcanaLiderJobs[k])window.__arcanaLiderJobs[k]={done:true,status:r.status,origin:origin,body:b};}).catch(function(){if(window.__arcanaLiderJobs[k])window.__arcanaLiderJobs[k]={done:true,network:true};});})()"""
        try {
            startCall()
            bootstrap()
            checkHold()
            // The verified browser context performs fresh same-origin HTTP reads. Catalogue
            // data must not wait for unrelated page assets or a second navigation to finish.
            if(!json && LiderShortContract.detailSlug(url)!=null) {
                return@withLock renderDetail(url)
            }
            eval(begin)
            withTimeout(12_000) {
                while(true) {
                    val r=objectValue("""JSON.stringify((function(){var o=window.__arcanaLiderJobs&&window.__arcanaLiderJobs[${JSONObject.quote(id)}];return o&&o.done?o:{done:false};})())""")
                    if(r.optBoolean("done")) {
                        if(r.optBoolean("network"))throw ErrorLoadingException("LiderShort bağlantısı tamamlanamadı.")
                        val status=r.optInt("status")
                        if(status==429)rememberHold(r.optString("retryAfter"))
                        if(status in setOf(202,401,403,429)) { accessStopped=true;ready=false;throw ErrorLoadingException("LiderShort erişim veya doğrulama istiyor (HTTP $status).") }
                        if(status!=200 || r.optString("origin")!=LiderShortContract.BASE || r.isNull("body"))throw ErrorLoadingException("LiderShort kaynak yanıtı doğrulanamadı.")
                        val body=r.getString("body")
                        if(!json && LiderShortContract.deniedHtml(body)) {
                            accessStopped=true;ready=false
                            throw ErrorLoadingException("LiderShort kaynak doğrulaması istiyor.")
                        }
                        return@withTimeout body
                    }
                    delay(80)
                }
                @Suppress("UNREACHABLE_CODE") ""
            }
        } catch(e:TimeoutCancellationException) { throw ErrorLoadingException("LiderShort isteği zamanında tamamlanmadı.") }
        finally {
            withContext(NonCancellable+Dispatchers.Main.immediate) {
                browser?.evaluateJavascript("""(function(){var k=${JSONObject.quote(id)},j=window.__arcanaLiderJobs&&window.__arcanaLiderJobs[k];if(j&&j.controller)j.controller.abort();if(window.__arcanaLiderJobs)delete window.__arcanaLiderJobs[k];})()""",null)
            }
            endCall()
        }
    }
    private fun checkHold() {
        if(System.currentTimeMillis()<limits.getLong("retry_until_ms",0L))throw ErrorLoadingException("LiderShort istek sınırı bildirdi; daha sonra deneyin.")
    }
    private suspend fun renderDetail(url:String):String {
        val slug=LiderShortContract.detailSlug(url) ?: throw ErrorLoadingException("LiderShort dizi adresi tanınmadı.")
        val before=starts
        withContext(Dispatchers.Main.immediate) {
            ready=false
            mainFrameError=0;mainNetworkError=0;mainSslError=-1
            browser?.loadUrl(url) ?: throw ErrorLoadingException("LiderShort oturumu kapalı.")
        }
        return withTimeout(20_000) {
            while(true) {
                checkHold()
                if(mainFrameError>=400 || mainSslError>=0)throw ErrorLoadingException("LiderShort dizi erişimi tamamlanmadı.")
                val started=withContext(Dispatchers.Main.immediate) { starts>before && LiderShortContract.sameSeriesPage(browser?.url.orEmpty(),slug) }
                if(!started) { delay(100);continue }
                val state=objectValue("""JSON.stringify({origin:location.origin,requestedPathMatches:(location.pathname.replace(/\/$/,'')==='/tr/videos/$slug'||/^\/tr\/videos\/$slug\/bolum-[1-9][0-9]*\/?$/.test(location.pathname)),done:document.readyState==='complete',h1:!!document.querySelector('h1'),chapters:document.querySelectorAll('a[href*="/videos/$slug/bolum-"]').length,blocked:/Access Denied|Attention Required|you have been blocked|verify you are human|captcha/i.test(document.title+' '+(document.body?.innerText||'').slice(0,500))})""")
                if(state.optBoolean("blocked")) { accessStopped=true;ready=false;throw ErrorLoadingException("LiderShort kaynak doğrulaması istiyor.") }
                if(state.optString("origin")==LiderShortContract.BASE && state.optBoolean("requestedPathMatches") && state.optBoolean("done") && state.optBoolean("h1") && state.optInt("chapters")>0) {
                    val result=objectValue("""JSON.stringify((function(){var html=document.documentElement.outerHTML;if(new TextEncoder().encode(html).byteLength>3000000)return {large:true};return {body:html};})())""")
                    if(result.optBoolean("large") || result.isNull("body"))throw ErrorLoadingException("LiderShort dizi yanıtı sınırı aşıldı.")
                    markReady(url)
                    return@withTimeout result.getString("body")
                }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
    }
    private fun rememberHold(header:String?) = LiderShortHold.remember(limits,header)
    private suspend fun markReady(expected:String) = withContext(Dispatchers.Main.immediate) {
        checkHold()
        val actual=browser?.url.orEmpty()
        if(closed || accessStopped || mainFrameError>=400 || mainSslError>=0 || mainNetworkError!=0 ||
            LiderShortContract.own(actual)==null || !(java.net.URI(actual).path.trimEnd('/')==java.net.URI(expected).path.trimEnd('/') ||
                LiderShortContract.detailSlug(expected)?.let { LiderShortContract.sameSeriesPage(actual,it) }==true))
            throw ErrorLoadingException("LiderShort kaynak erişimi doğrulanamadı.")
        ready=true
    }
    suspend fun cookies(url:String):String {
        if(LiderShortContract.own(url)==null || closed)return ""
        return withContext(Dispatchers.Main.immediate) { CookieManager.getInstance().getCookie(url).orEmpty() }
    }
    fun close() {
        closed=true;ready=false
        handler.post { handler.removeCallbacks(idleClose);destroyOwnedBrowser() }
    }
}
