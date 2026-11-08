package dev.arcana.dramacix

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
internal class DramacixSession(context: Context) {
    private val context=context.applicationContext
    private val lock=Mutex()
    private val handler=Handler(Looper.getMainLooper())
    private var browser: WebView?=null
    @Volatile private var ready=false
    @Volatile private var closed=false
    @Volatile private var accessStopped=false
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
            u.scheme=="https" && u.host=="dramacix.com" && u.path=="/" -> "source-root"
            u.scheme=="https" && u.host=="dramacix.com" -> "source-other"
            u.scheme=="https" && u.host=="www.dramacix.com" -> "source-www"
            u.scheme=="http" && u.host in setOf("dramacix.com","www.dramacix.com") -> "source-http"
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
        handler.removeCallbacks(idleClose);busy=true
    }
    private suspend fun endCall() = withContext(NonCancellable+Dispatchers.Main.immediate) {
        handler.removeCallbacks(idleClose)
        busy=false
        if(!ready || closed)destroyOwnedBrowser()else handler.postDelayed(idleClose,90_000)
    }

    private suspend fun eval(script: String): String = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { c ->
            val view=browser
            if(view==null || closed) { c.resumeWithException(ErrorLoadingException("DramaCix tarayıcı oturumu kapalı."));return@suspendCancellableCoroutine }
            view.evaluateJavascript(script) { value -> if(c.isActive)c.resume(value,onCancellation={_,_,_->}) }
        }
    }
    private suspend fun objectValue(script: String): JSONObject {
        val raw=eval(script)
        return try { JSONObject(JSONTokener(raw).nextValue() as String) }
            catch (_: Exception) { throw ErrorLoadingException("DramaCix tarayıcı yanıtı tanınmadı.") }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun bootstrap() {
        if(ready)return
        if(closed)throw ErrorLoadingException("DramaCix oturumu kapalı.")
        if(accessStopped)throw ErrorLoadingException("DramaCix kaynak erişimi durdu. Kaynak sitesini ayarlardan normal açın.")
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
                        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                            val reject=DramacixContract.own(request.url.toString())==null
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
                            handler.cancel()
                        }
                        override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse) {
                            if(request.isForMainFrame && response.statusCode>=400)mainFrameError=response.statusCode
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
            browser!!.loadUrl(DramacixContract.BASE+"/")
        }
        val started=android.os.SystemClock.elapsedRealtime()
        var last=JSONObject()
        try {
            withTimeout(20_000) {
                while(true) {
                    if(mainFrameError>=400) { if(mainFrameError in setOf(401,403,429))accessStopped=true;throw ErrorLoadingException("DramaCix kaynak erişimi istiyor (HTTP $mainFrameError). Ayarlardan siteyi normal açın.") }
                    // Do not evaluate the initial empty JavaScript document while the first navigation is pending.
                    val navigationStarted=withContext(Dispatchers.Main.immediate) {
                        if(closed || browser==null)throw ErrorLoadingException("DramaCix tarayıcı oturumu kapalı.")
                        starts>0 && urlKind(browser?.originalUrl)=="source-root"
                    }
                    if(!navigationStarted) { delay(100);continue }
                    val state=objectValue("""JSON.stringify({origin:location.origin,title:document.title,blocked:/Access Denied|Attention Required|you have been blocked|verify you are human/i.test(document.title+' '+(document.body?.innerText||'').slice(0,500)),cards:document.querySelectorAll('a.card[href]').length,doc:document.readyState,vw:innerWidth,vh:innerHeight,bodyChars:(document.body?.innerText||'').length,menu:!!document.getElementById('mobileMenu'),pathKind:location.pathname==='/'?'root':location.pathname==='/giris'?'login':'other'})""")
                    last=JSONObject().put("originMatches",state.optString("origin")==DramacixContract.BASE)
                        .put("cards",state.optInt("cards")).put("viewportWidth",state.optInt("vw")).put("viewportHeight",state.optInt("vh"))
                        .put("bodyChars",state.optInt("bodyChars")).put("menu",state.optBoolean("menu"))
                        .put("doc",state.optString("doc").takeIf { it in setOf("loading","interactive","complete") } ?: "unknown")
                        .put("pathKind",state.optString("pathKind").takeIf { it in setOf("root","login","other") } ?: "unknown")
                    if(state.optBoolean("blocked")) { accessStopped=true;throw ErrorLoadingException("DramaCix kaynak doğrulaması istiyor. Ayarlardan siteyi normal açın.") }
                    if(state.optString("origin")==DramacixContract.BASE && state.optInt("cards")>0) { ready=true;android.util.Log.i("ArcanaDramacix","bootstrap cards=${state.optInt("cards")} elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}");break }
                    delay(100)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Only bounded structure/enum fields: never a URL, page title, HTML, cookie or account field.
            withContext(NonCancellable+Dispatchers.Main.immediate) {
                last.put("httpError",mainFrameError).put("networkError",mainNetworkError).put("sslError",mainSslError).put("sslUrlKind",sslKind)
                    .put("starts",starts).put("finishes",finishes).put("blockedNavigations",blockedNavigations).put("blockedKind",blockedKind)
                    .put("viewUrlKind",urlKind(browser?.url)).put("originalUrlKind",urlKind(browser?.originalUrl)).put("progress",browser?.progress ?: -1)
                android.util.Log.i("ArcanaDramacix","bootstrap timeout "+last.toString())
            }
            throw ErrorLoadingException("DramaCix normal tarayıcı başlangıcı tamamlanmadı. Kaynak sitesini eklenti ayarından açın.")
        }
    }
    /** Values are read from this actual renderer, including the publisher's software-GPU branch. */
    suspend fun gpuFlag(): Int = lock.withLock {
        try {
            startCall()
            bootstrap()
            val result=objectValue("""JSON.stringify({g:(function(){try{var c=document.createElement('canvas'),gl=c.getContext('webgl')||c.getContext('experimental-webgl');if(!gl)return 0;var d=gl.getExtension('WEBGL_debug_renderer_info'),r=(d?gl.getParameter(d.UNMASKED_RENDERER_WEBGL):'')||'';return /swiftshader|llvmpipe|mesa offscreen|software|virgl/i.test(r)?0:1;}catch(e){return 0;}})()})""")
            result.optInt("g").also { if(it !in 0..1)throw ErrorLoadingException("DramaCix oynatıcı yeteneği doğrulanamadı.") }
        } finally { endCall() }
    }
    suspend fun text(url: String,referer: String=DramacixContract.BASE+"/",json: Boolean=false,ajax: Boolean=false): String = lock.withLock {
        if(DramacixContract.own(url)==null || DramacixContract.own(referer)==null)throw ErrorLoadingException("DramaCix kaynak adresi tanınmadı.")
        val id=UUID.randomUUID().toString().replace("-","")
        val headers=JSONObject().put("Accept",if(json)"application/json"else"text/html, */*;q=0.8")
        if(ajax)headers.put("X-Requested-With","XMLHttpRequest")
        val begin="""(function(){var k=${JSONObject.quote(id)};window.__arcanaCixJobs=window.__arcanaCixJobs||{};var c=new AbortController();window.__arcanaCixJobs[k]={done:false,controller:c};fetch(${JSONObject.quote(url)},{credentials:'same-origin',redirect:'error',referrer:${JSONObject.quote(referer)},headers:$headers,signal:c.signal}).then(async function(r){var origin=new URL(r.url).origin;if(r.status!==200){if(r.body)await r.body.cancel();if(window.__arcanaCixJobs[k])window.__arcanaCixJobs[k]={done:true,status:r.status,origin:origin,body:''};return;}var reader=r.body&&r.body.getReader();if(!reader)throw Error('STREAM_REQUIRED');var decoder=new TextDecoder('utf-8'),size=0,b='';for(;;){var item=await reader.read();if(item.done)break;size+=item.value.byteLength;if(size>3000000){await reader.cancel();c.abort();throw Error('BODY_LIMIT');}b+=decoder.decode(item.value,{stream:true});}b+=decoder.decode();if(window.__arcanaCixJobs[k])window.__arcanaCixJobs[k]={done:true,status:r.status,origin:origin,body:b};}).catch(function(){if(window.__arcanaCixJobs[k])window.__arcanaCixJobs[k]={done:true,network:true};});})()"""
        try {
            startCall()
            bootstrap()
            // The verified browser context performs fresh same-origin HTTP reads. Catalogue
            // data must not wait for unrelated page assets or a second navigation to finish.
            eval(begin)
            withTimeout(12_000) {
                while(true) {
                    val r=objectValue("""JSON.stringify((function(){var o=window.__arcanaCixJobs&&window.__arcanaCixJobs[${JSONObject.quote(id)}];return o&&o.done?o:{done:false};})())""")
                    if(r.optBoolean("done")) {
                        if(r.optBoolean("network"))throw ErrorLoadingException("DramaCix bağlantısı tamamlanamadı.")
                        val status=r.optInt("status")
                        if(status in setOf(202,401,403,429)) { accessStopped=true;ready=false;throw ErrorLoadingException("DramaCix erişim veya doğrulama istiyor (HTTP $status). Kaynak sitesini ayarlardan açın.") }
                        if(status!=200 || r.optString("origin")!=DramacixContract.BASE || r.isNull("body"))throw ErrorLoadingException("DramaCix kaynak yanıtı doğrulanamadı.")
                        val body=r.getString("body")
                        if(!json && DramacixContract.deniedHtml(body)) {
                            accessStopped=true;ready=false
                            throw ErrorLoadingException("DramaCix kaynak doğrulaması istiyor. Kaynak sitesini ayarlardan normal açın.")
                        }
                        return@withTimeout body
                    }
                    delay(80)
                }
                @Suppress("UNREACHABLE_CODE") ""
            }
        } catch(e:TimeoutCancellationException) { throw ErrorLoadingException("DramaCix isteği zamanında tamamlanmadı.") }
        finally {
            withContext(NonCancellable+Dispatchers.Main.immediate) {
                browser?.evaluateJavascript("""(function(){var k=${JSONObject.quote(id)},j=window.__arcanaCixJobs&&window.__arcanaCixJobs[k];if(j&&j.controller)j.controller.abort();if(window.__arcanaCixJobs)delete window.__arcanaCixJobs[k];})()""",null)
            }
            endCall()
        }
    }
    fun invalidate() { ready=false;accessStopped=false }
    fun close() {
        closed=true;ready=false
        handler.post { handler.removeCallbacks(idleClose);destroyOwnedBrowser() }
    }
}
