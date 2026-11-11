package dev.arcana.liderdrama

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Normal source-owned browser retrieval before loadLinks returns. No user dialog. */
internal class LiderAutomaticCaptions(
    context:Context,
    private val permitted:()->Unit,
    private val rateLimited:(String?)->Unit,
    private val admission:suspend()->Unit
) {
    private val app=context.applicationContext
    // Old native-caption denial preferences are preserved. This browser transport
    // has its own failure ledger; API/issuer-wide stops remain authoritative.
    private val prefs=app.getSharedPreferences("arcana_lider_automatic_caption_access",Context.MODE_PRIVATE)
    private val backoff=LiderAutomaticCaptionBackoff({prefs.getLong("until",0)},{prefs.edit().putLong("until",it).commit()},System::currentTimeMillis)
    private val mutex=Mutex()
    private val ids=AtomicLong()
    @Volatile private var closed=false
    @Volatile private var cancelActive:(()->Unit)?=null
    @Volatile private var activeRun=0L
    suspend fun resolve(raw:String,expectedSlug:String,expectedNumber:Int,base:OkHttpClient,budgetMs:Long):String {
        val queuedAt=SystemClock.elapsedRealtime()
        return mutex.withLock {
        val target=LiderAutomaticCaptionPolicy.target(raw,expectedSlug,expectedNumber)
        val available=LiderAutomaticCaptionPolicy.budget(budgetMs,SystemClock.elapsedRealtime()-queuedAt)
        permitted();currentCoroutineContext().ensureActive()
        if(closed||available<1500||backoff.blocked())throw LiderCaptionUnavailable()
        // The host's loadLinks step is30s; API work already consumed part of it.
        LiderAutomaticCaptionAttempt.run(permitted,{block(300_000L)}) {
            withTimeoutOrNull(available) { browser(target,base) }?:throw LiderCaptionUnavailable()
        }
        }
    }
    private fun block(duration:Long) {
        backoff.failure(duration)
    }
    private suspend fun browser(target:String,base:OkHttpClient):String=withContext(Dispatchers.Main.immediate) {
        val parent=currentCoroutineContext();val scope=CoroutineScope(parent)
        suspendCancellableCoroutine { continuation ->
            val ticket=LiderAutomaticCaptionTicket();val jobId=ids.incrementAndGet()
            activeRun=jobId
            val emitted=hashSetOf<String>();var web:WebView?=null;var bodyJob:Job?=null
            fun mark(event:String) {if(emitted.add(event))Log.i("ArcanaLiderAuto","job=$jobId event=$event")}
            fun live()=!closed&&ticket.live()&&continuation.isActive&&runCatching{permitted()}.isSuccess
            fun dispose() {
                ticket.close();if(activeRun==jobId){cancelActive=null;activeRun=0L}
                web?.let{it.stopLoading();it.webViewClient=WebViewClient();it.destroy()};web=null
                mark("CLOSED")
            }
            fun failed(error:Throwable=LiderCaptionUnavailable()) {
                if(!ticket.live())return
                val authoritative=runCatching{permitted()}.exceptionOrNull()?:error
                mark("FAILED");dispose();bodyJob?.cancel()
                if(continuation.isActive)continuation.resumeWithException(authoritative)
            }
            fun limited(retry:String?) {
                // Observed429 is persisted even if cancellation just won the race.
                val error=try{rateLimited(retry);LiderCaptionUnavailable()}catch(e:Exception){e}
                mark("RATE_LIMITED");failed(error)
            }
            try {
                permitted();if(!live())throw LiderCaptionUnavailable()
                val view=WebView(app);web=view
                val cm=CookieManager.getInstance();val actualUa=view.settings.userAgentString
                require(actualUa.isNotBlank())
                fun clearance()=cm.getCookie(target).orEmpty().split(';').firstOrNull{
                    it.substringBefore('=').trim()=="cf_clearance"
                }?.substringAfter('=',"")?.takeIf{it.isNotBlank()}
                val initialClearance=clearance()
                fun confirm(generation:Long) {
                    if(!live()||!ticket.admit(generation))return
                    mark("BODY_ADMITTED")
                    bodyJob=scope.launch {
                        try {
                            // Browser navigation uses the normal renderer. Native API/body
                            // confirmation retains the existing shared3s admission policy.
                            admission();permitted();ensureActive()
                            if(!live())throw CancellationException()
                            val cookie=LiderAutomaticCaptionPolicy.cookie(cm.getCookie(target).orEmpty())
                            val text=fetch(target,base,actualUa,cookie)
                            permitted();ensureActive()
                            if(!live())throw CancellationException()
                            mark("BODY_VERIFIED");dispose()
                            if(continuation.isActive)continuation.resume(text)
                        }catch(e:CancellationException){throw e}
                        catch(e:Exception){failed(if(e is com.lagradost.cloudstream3.ErrorLoadingException)e else LiderCaptionUnavailable())}
                    }
                }
                fun probe(document:String) {
                    if(!live()||view.url!=document||!LiderAutomaticCaptionPolicy.same(document,target))return
                    val generation=ticket.generation()
                    if(clearance()?.let{it!=initialClearance}==true){confirm(generation);return}
                    if(!ticket.probe(generation))return
                    // Bounded Boolean DOM cue only. No document or cookies are exported.
                    view.evaluateJavascript(LiderCaptionProbe.script(document,target)){answer->
                        if(live()&&view.url==document&&generation==ticket.generation()&&answer=="true")confirm(generation)
                    }
                }
                view.settings.apply {
                    javaScriptEnabled=true;domStorageEnabled=true
                    allowFileAccess=false;allowContentAccess=false
                    javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture=true
                }
                cm.setAcceptThirdPartyCookies(view,true) // Per-view only; global preference untouched.
                view.webViewClient=object:WebViewClient() {
                    override fun shouldOverrideUrlLoading(v:WebView?,r:WebResourceRequest?):Boolean {
                        if(!live()||r==null)return true
                        val raw=r.url.toString()
                        if(LiderVerificationFramePolicy.allowSubframe(raw,r.isForMainFrame,r.method))return false
                        return !LiderAutomaticCaptionPolicy.navigation(raw,target)
                    }
                    override fun shouldInterceptRequest(v:WebView?,r:WebResourceRequest?):WebResourceResponse? {
                        if(!live()||r==null)return WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
                        val raw=r.url.toString()
                        return if(LiderVerificationFramePolicy.allowRequest(raw,r.isForMainFrame,r.method,
                            LiderAutomaticCaptionPolicy.navigation(raw,target),LiderAutomaticCaptionPolicy.resource(raw,r.method,target)))null
                        else WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
                    }
                    override fun onPageStarted(v:WebView?,url:String?,icon:android.graphics.Bitmap?){ticket.navigation()}
                    override fun onPageCommitVisible(v:WebView?,url:String?){if(v===view&&url!=null&&live())probe(url)}
                    override fun onPageFinished(v:WebView?,url:String?){if(v===view&&url!=null&&live())probe(url)}
                    override fun onReceivedHttpError(v:WebView?,r:WebResourceRequest?,response:WebResourceResponse?) {
                        if(response?.statusCode==429)limited(response.responseHeaders?.entries?.firstOrNull{it.key.equals("Retry-After",true)}?.value)
                        // A normal managed source challenge may complete in this same renderer.
                        // No widget is clicked and no error document authenticates a caption.
                    }
                    override fun onReceivedError(v:WebView?,r:WebResourceRequest?,e:android.webkit.WebResourceError?){if(r?.isForMainFrame==true&&live())failed()}
                }
                view.setDownloadListener{url,_,_,mime,_->
                    if(live()&&LiderAutomaticCaptionPolicy.same(url,target)&&mime.orEmpty().substringBefore(';').lowercase(java.util.Locale.ROOT)in
                        setOf("text/vtt","text/plain","application/x-subrip","application/octet-stream")){
                        mark("DOWNLOAD_OBSERVED");confirm(ticket.generation())
                    }
                }
                cancelActive={failed()}
                continuation.invokeOnCancellation {Handler(Looper.getMainLooper()).post{bodyJob?.cancel();dispose()}}
                permitted();if(!live())throw LiderCaptionUnavailable()
                mark("STARTED")
                // Real HTTPS navigation, never loadData/about:blank as a forged source origin.
                view.loadUrl(target)
            }catch(e:Exception){failed(if(e is com.lagradost.cloudstream3.ErrorLoadingException)e else LiderCaptionUnavailable())}
        }
    }
    private suspend fun fetch(target:String,base:OkHttpClient,ua:String,cookie:String):String {
        permitted();currentCoroutineContext().ensureActive()
        val client=base.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).build()
        val request=Request.Builder().url(target).header("User-Agent",ua).header("Accept-Encoding","identity")
            .header("Referer",LiderContract.BASE+"/").header("Origin",LiderContract.BASE)
        if(cookie.isNotBlank())request.header("Cookie",cookie)
        val call=client.newCall(request.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation{call.cancel()}
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(LiderCaptionUnavailable())}
                override fun onResponse(call:Call,response:Response) {
                    try {
                        val text=response.use{r->
                            if(r.code==429){rateLimited(r.header("Retry-After"));throw LiderCaptionUnavailable()}
                            permitted()
                            if(!continuation.isActive)throw CancellationException()
                            if(r.code in setOf(401,403)){block(1_800_000);throw LiderCaptionUnavailable()}
                            if(r.code!=200||r.request.url.toString()!=target||r.body.contentLength()>LiderAutomaticCaptionPolicy.LIMIT)throw LiderCaptionUnavailable()
                            val input=r.body.source();input.request(LiderAutomaticCaptionPolicy.LIMIT.toLong()+1)
                            if(input.buffer.size>LiderAutomaticCaptionPolicy.LIMIT)throw LiderCaptionUnavailable()
                            val result=LiderAutomaticCaptionPolicy.body(r.code,target,r.request.url.toString(),input.readByteArray())
                            permitted();if(!continuation.isActive)throw CancellationException()
                            result
                        }
                        if(continuation.isActive)continuation.resume(text,onCancellation={_,_,_->})
                    }catch(e:Exception){response.close();if(continuation.isActive)continuation.resumeWithException(if(e is com.lagradost.cloudstream3.ErrorLoadingException)e else LiderCaptionUnavailable())}
                }
            })
        }
    }
    fun close(){closed=true;Handler(Looper.getMainLooper()).post{cancelActive?.invoke();cancelActive=null}}
}
