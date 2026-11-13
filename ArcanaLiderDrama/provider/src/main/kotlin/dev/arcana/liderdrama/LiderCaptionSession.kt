package dev.arcana.liderdrama

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import android.util.Log

internal class LiderCaptionUnavailable:IOException("Lider caption unavailable; source verification may be required")

/** Normal publisher verification of the actual denied resource. No global cookie deletion/import. */
internal class LiderCaptionSession(
    context:Context,
    private val permitted:()->Unit,
    private val rateLimited:(String?)->Unit,
    private val verificationAdmission:suspend()->Unit
) {
    private val prefs=context.applicationContext.getSharedPreferences("arcana_lider_caption_access",Context.MODE_PRIVATE)
    @Volatile private var holdUntil=prefs.getLong("until",0L)
    private val gate=LiderCaptionGate()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    @Volatile private var closed=false
    @Volatile private var cachedPair:Pair<String,String>?=null
    @Volatile private var isolated:OkHttpClient?=null
    @Volatile private var dialog:AlertDialog?=null
    @Volatile private var activeWeb:WebView?=null
    data class RequestContext(val client:OkHttpClient,val ua:String,val cookie:String?)
    fun cached(url:String):String?=cachedPair?.takeIf{it.first==url}?.second
    fun beforeRequest(url:String) {
        requireCaption(url)
        if(closed||maxOf(holdUntil,prefs.getLong("until",0))>System.currentTimeMillis())throw LiderCaptionUnavailable()
    }
    suspend fun requestContext(url:String,base:OkHttpClient,defaultUa:String):RequestContext {
        beforeRequest(url) // Skipped captions never replace the actually denied target.
        val verifiedUa=prefs.getString("verified_ua",null)
        if(verifiedUa.isNullOrBlank())return RequestContext(base,defaultUa,null)
        val cookie=try {withContext(Dispatchers.Main.immediate){CookieManager.getInstance().getCookie(url).orEmpty()}}
            catch(e:CancellationException){throw e}catch(_:Exception){throw LiderCaptionUnavailable()}
        if(cookie.any{it.code<32||it.code==127})throw LiderCaptionUnavailable()
        val client=isolated?:synchronized(this) {
            isolated?:base.newBuilder().cookieJar(CookieJar.NO_COOKIES).build().also{isolated=it}
        }
        return RequestContext(client,verifiedUa,cookie.takeIf{it.isNotBlank()})
    }
    fun blocked(url:String) {
        requireCaption(url)
        val obsolete=dialog
        if(!gate.deny(url) {
            cachedPair=null
            holdUntil=maxOf(holdUntil,prefs.getLong("until",0),System.currentTimeMillis()+1800000L)
            prefs.edit().putLong("until",holdUntil).commit()
        })throw ErrorLoadingException("LiderDrama altyazı bekleme kaydı yazılamadı.")
        Handler(Looper.getMainLooper()).post{if(dialog===obsolete)obsolete?.dismiss()}
    }
    fun show(ownerContext:Context) {
        Handler(Looper.getMainLooper()).post {
            val owner=ownerContext as?Activity?:return@post
            if(owner.isFinishing||owner.isDestroyed||closed)return@post
            try{permitted()}catch(_:Exception) {
                Toast.makeText(owner,"Kaynak bekleme süresi dolunca yeniden deneyin.",Toast.LENGTH_LONG).show();return@post
            }
            if(!gate.hasTarget()) {
                Toast.makeText(owner,"Önce altyazısı olan bölümü açın.",Toast.LENGTH_LONG).show();return@post
            }
            val cm=CookieManager.getInstance()
            if(!cm.acceptCookie()) {
                Toast.makeText(owner,"Kaynak doğrulaması için uygulama tarayıcısında çerez izni gerekiyor.",Toast.LENGTH_LONG).show();return@post
            }
            dialog?.dismiss()
            val ticket=gate.begin()?:return@post
            val target=ticket.url
            requireCaption(target)
            val web=try{WebView(owner)}catch(_:Exception) {
                gate.end(ticket);Toast.makeText(owner,"Uygulama tarayıcısı açılamadı.",Toast.LENGTH_LONG).show();return@post
            }
            web.layoutParams=android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,(owner.resources.displayMetrics.heightPixels*0.55f).toInt()
            )
            val actualUa=web.settings.userAgentString
            val before=clearance(cm.getCookie(target))
            val documents=LiderCaptionNavigation()
            var verificationJob:Job?=null
            var pendingDownloadTrigger=false
            val keyJs=JSONObject.quote("arcanaLiderCaption"+ticket.generation)
            val controlJs=JSONObject.quote("arcanaLiderControl"+ticket.generation)
            val readerJs=JSONObject.quote("arcanaLiderReader"+ticket.generation)
            lateinit var ownDialog:AlertDialog
            fun owned():Boolean=!closed&&gate.live(ticket)&&dialog===ownDialog&&activeWeb===web
            fun live():Boolean=owned()&&!owner.isFinishing&&!owner.isDestroyed&&runCatching{permitted()}.isSuccess
            val emitted=java.util.EnumSet.noneOf(LiderVerificationEvent::class.java)
            fun mark(event:LiderVerificationEvent) {
                // Each fixed enum can be emitted once per dialog: bounded even if callbacks repeat.
                synchronized(emitted){if(!emitted.add(event))return}
                runCatching{Log.i("ArcanaLiderVerify","session="+ticket.generation+" event="+event.name)}
            }
            fun abort() {
                verificationJob?.cancel(null)
                runCatching{web.evaluateJavascript("(function(){var c=window[$controlJs],r=window[$readerJs];if(c)c.abort();if(r)r.cancel().catch(function(){});})();",null)}
            }
            fun unavailable() {
                val current=gate.live(ticket)&&dialog===ownDialog&&activeWeb===web
                gate.end(ticket);documents.close();abort();runCatching{web.stopLoading()}
                if(current&&!owner.isFinishing&&!owner.isDestroyed)
                    Toast.makeText(owner,"Altyazı doğrulanamadı. Kaynak beklemesi korunuyor.",Toast.LENGTH_LONG).show()
                runCatching{ownDialog.dismiss()}
            }
            fun complete(text:String) {
                if(!timed(text)||!live()){unavailable();return}
                var persistenceFailed=false
                val saved=gate.complete(ticket) {
                    if(!live())false else prefs.edit().putLong("until",0).putString("verified_ua",actualUa).commit().also {
                        if(it){holdUntil=0;cachedPair=target to text}else persistenceFailed=true
                    }
                }
                if(saved) {
                    mark(LiderVerificationEvent.BODY_VERIFIED)
                    Toast.makeText(owner,"Kaynak doğrulandı. Bölümü yeniden açabilirsiniz.",Toast.LENGTH_LONG).show()
                    ownDialog.dismiss()
                } else {
                    mark(if(persistenceFailed)LiderVerificationEvent.PERSIST_FAILED else LiderVerificationEvent.PERMISSION_STOP)
                    unavailable()
                }
            }
            fun limited(retry:String?) {
                gate.end(ticket);documents.close()
                // Commit the global stop BEFORE touching a possibly dismissed/destroyed WebView.
                runCatching{rateLimited(retry)}
                mark(LiderVerificationEvent.RATE_LIMITED)
                abort();runCatching{web.stopLoading()}
                if(!owner.isFinishing&&!owner.isDestroyed)
                    Toast.makeText(owner,"Kaynak istek sınırı bildirdi. Daha sonra deneyin.",Toast.LENGTH_LONG).show()
                runCatching{ownDialog.dismiss()}
            }
            fun verifyBody(downloadTriggered:Boolean=false) {
                if(!live()){
                    if(owned()){mark(LiderVerificationEvent.PERMISSION_STOP);unavailable()}
                    return
                }
                if(documents.hasAdmitted())return
                if(downloadTriggered)pendingDownloadTrigger=true
                // Merge a concurrent download into the current probe/queue; do not lose that trigger.
                if(verificationJob?.isActive==true)return
                val attempt=documents.queue()?:return
                val document=attempt.document
                mark(LiderVerificationEvent.QUEUED)
                val job=scope.launch(start=CoroutineStart.LAZY) {
                    var timedDocument=false
                    val initialClearance=clearance(cm.getCookie(target))
                    val initiallyFresh=initialClearance!=null&&initialClearance!=before
                    if(!pendingDownloadTrigger&&!initiallyFresh){
                        if(!sameCaption(document,target)||web.url!=document){
                            mark(LiderVerificationEvent.NO_SOURCE_DOCUMENT)
                        }else if(documents.reserveProbe(attempt)){
                            val observed=withTimeoutOrNull(1500){
                                suspendCancellableCoroutine<Boolean>{continuation->
                                    try{
                                        web.evaluateJavascript(LiderCaptionProbe.script(document,target)){answer->
                                            if(continuation.isActive)
                                                continuation.resume(answer=="true"&&live()&&documents.pending(attempt))
                                        }
                                    }catch(_:Exception){
                                        if(continuation.isActive)continuation.resume(false)
                                    }
                                }
                            }
                            timedDocument=observed==true
                            if(observed==null)mark(LiderVerificationEvent.DOCUMENT_PROBE_TIMED_OUT)
                            if(timedDocument)mark(LiderVerificationEvent.TIMED_DOCUMENT_OBSERVED)
                        }else mark(LiderVerificationEvent.DOCUMENT_PROBE_LIMIT)
                    }
                    if(!documents.pending(attempt)){mark(LiderVerificationEvent.STALE_QUEUE_IGNORED);return@launch}
                    if(!live()){
                        if(owned()){mark(LiderVerificationEvent.PERMISSION_STOP);unavailable()}
                        else documents.abandon(attempt)
                        return@launch
                    }
                    // A cue is only a trigger. A forged cue cannot clear a gate or skip the real200 body.
                    val clearanceAfterProbe=clearance(cm.getCookie(target))
                    val freshAfterProbe=clearanceAfterProbe!=null&&clearanceAfterProbe!=before
                    if(!pendingDownloadTrigger&&!freshAfterProbe&&!timedDocument){
                        documents.abandon(attempt);mark(LiderVerificationEvent.DOCUMENT_NO_TRIGGER);return@launch
                    }
                    try{verificationAdmission()}
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){
                        if(documents.pending(attempt)&&owned()){mark(LiderVerificationEvent.PERMISSION_STOP);unavailable()}
                        return@launch
                    }
                    // A -> B -> A is still a new document. Old queued jobs may never inject.
                    if(!documents.pending(attempt)){
                        mark(LiderVerificationEvent.STALE_QUEUE_IGNORED);return@launch
                    }
                    if(!live()){
                        if(owned()){mark(LiderVerificationEvent.PERMISSION_STOP);unavailable()}
                        else documents.abandon(attempt)
                        return@launch
                    }
                    if(web.url!=document||!sourceDocument(document)){
                        mark(LiderVerificationEvent.NO_SOURCE_DOCUMENT);unavailable();return@launch
                    }
                    val now=clearance(cm.getCookie(target))
                    if(!pendingDownloadTrigger&&(now==null||now==before)&&!timedDocument){
                        documents.abandon(attempt);mark(LiderVerificationEvent.DOCUMENT_NO_TRIGGER);return@launch
                    }
                    if(!gate.admitBody(ticket)){
                        mark(LiderVerificationEvent.BUDGET_ALREADY_SPENT);unavailable();return@launch
                    }
                    if(!documents.admit(attempt)||!live()){unavailable();return@launch}
                    val targetJs=JSONObject.quote(target)
                    val documentJs=JSONObject.quote(document)
                    // Download notifications never unlock anything. ONE browser GET after actual
                    // publisher download or fresh clearance must deliver exactURL/status200 and a completed body.
                    val script="""
                        (function(){
                          if(location.origin!=="https://liderdrama.com"||location.href!==$documentJs)return;
                          var key=$keyJs,controller=new AbortController(),responseBody=null,reader=null;window[$controlJs]=controller;
                          window[key]=null;
                          var timer=setTimeout(function(){controller.abort();},22000);
                          fetch($targetJs,{credentials:"same-origin",redirect:"error",cache:"no-store",signal:controller.signal})
                          .then(async function(r){
                            responseBody=r.body;
                            if(r.status!==200){window[key]={status:r.status,retry:r.headers.get("Retry-After")};return;}
                            if(r.url!==$targetJs||Number(r.headers.get("Content-Length")||0)>2000000)throw new Error("invalid");
                            reader=responseBody.getReader();window[$readerJs]=reader;
                            var chunks=[],size=0;
                            while(true){
                              var part=await reader.read();if(part.done)break;
                              size+=part.value.byteLength;
                              if(size>2000000){await reader.cancel();throw new Error("size");}
                              chunks.push(part.value);
                            }
                            var bytes=new Uint8Array(size),offset=0;
                            chunks.forEach(function(c){bytes.set(c,offset);offset+=c.byteLength;});
                            window[key]={status:r.status,url:r.url,text:new TextDecoder("utf-8",{fatal:true}).decode(bytes)};
                          }).catch(function(){window[key]={failed:true};})
                          .finally(function(){
                            controller.abort();
                            try{
                              var canceled=reader?reader.cancel():(responseBody?responseBody.cancel():null);
                              if(canceled)canceled.catch(function(){});
                            }catch(ignore){}
                            clearTimeout(timer);
                          });
                        })();
                    """.trimIndent()
                    // Admission/injection-attempt is NOT proof that an HTTP request was sent.
                    mark(LiderVerificationEvent.ADMITTED_INJECTION_ATTEMPT)
                    web.evaluateJavascript(script,null)
                    val handler=Handler(Looper.getMainLooper())
                    var polls=0
                    val poll=object:Runnable {
                        override fun run() {
                            if(!live()||!documents.current(attempt)||web.url!=document){
                                mark(LiderVerificationEvent.DOCUMENT_CHANGED_AFTER_INJECTION);unavailable();return
                            }
                            if(polls++>=96){unavailable();return}
                            web.evaluateJavascript("JSON.stringify(window[$keyJs]||null)") { encoded->
                                if(!live()||!documents.current(attempt)||web.url!=document){
                                    mark(LiderVerificationEvent.DOCUMENT_CHANGED_AFTER_INJECTION);unavailable();return@evaluateJavascript
                                }
                                val raw=runCatching{JSONTokener(encoded).nextValue()as?String}.getOrNull()
                                val result=raw?.let{runCatching{JSONObject(it)}.getOrNull()}
                                if(result==null){handler.postDelayed(this,250);return@evaluateJavascript}
                                if(result.optInt("status")==429) {
                                    limited(result.optString("retry").takeIf{it!="null"&&it.isNotBlank()});return@evaluateJavascript
                                }
                                val text=result.opt("text")as?String
                                if(result.optInt("status")==200&&result.optString("url")==target&&text!=null&&timed(text))
                                    complete(text)
                                else {
                                    mark(when(result.optInt("status")){
                                        401->LiderVerificationEvent.BODY_HTTP_401
                                        403->LiderVerificationEvent.BODY_HTTP_403
                                        in 100..599->LiderVerificationEvent.BODY_HTTP_OTHER
                                        else->LiderVerificationEvent.BODY_REJECTED
                                    })
                                    unavailable()
                                }
                            }
                        }
                    }
                    handler.postDelayed(poll,250)
                }
                verificationJob=job
                job.start()
            }
            web.settings.apply {
                javaScriptEnabled=true;domStorageEnabled=true
                allowFileAccess=false;allowContentAccess=false
                javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture=true
            }
            // Required by the publisher's embedded challenge flow, only for this WebView.
            // Global cookie preference and all stored sessions remain untouched.
            cm.setAcceptThirdPartyCookies(web,true)
            web.webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?):Boolean {
                    if(!live()||request==null)return true
                    val raw=request.url.toString()
                    if(LiderVerificationFramePolicy.allowSubframe(raw,request.isForMainFrame,request.method))return false
                    return !navigation(raw,target)
                }
                override fun shouldInterceptRequest(view:WebView?,request:WebResourceRequest?):WebResourceResponse? {
                    if(!live()||request==null)
                        return WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
                    val raw=request.url.toString()
                    val allowed=LiderVerificationFramePolicy.allowRequest(raw,request.isForMainFrame,request.method,
                        navigation(raw,target),resource(raw,request.method,target))
                    if(!allowed)return WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
                    return null
                }
                override fun onPageStarted(view:WebView?,url:String?,favicon:android.graphics.Bitmap?) {
                    pendingDownloadTrigger=false
                    val admitted=documents.navigationStarted() // Generation changes before cancellation.
                    if(admitted){
                        mark(LiderVerificationEvent.AFTER_ADMISSION_NAV_STOP);unavailable()
                    } else {
                        val old=verificationJob
                        verificationJob=null
                        if(old?.isActive==true){
                            old.cancel(null)
                            mark(LiderVerificationEvent.QUEUED_NAV_CANCELLED)
                        }
                        // No request admitted: allow the publisher's normal next document to commit.
                    }
                }
                override fun onPageCommitVisible(view:WebView?,url:String?) {
                    if(view===web&&live()&&url!=null&&sourceDocument(url)&&web.url==url){
                        val ready=documents.committed(url)
                        mark(LiderVerificationEvent.DOCUMENT_COMMITTED)
                        if(ready!=null)verifyBody(ready.download)
                    }
                }
                override fun onReceivedHttpError(view:WebView?,request:WebResourceRequest?,response:WebResourceResponse?) {
                    if(response?.statusCode==429) {
                        limited(response.responseHeaders?.entries?.firstOrNull{it.key.equals("Retry-After",true)}?.value)
                    } else if(response?.statusCode in setOf(401,403)&&documents.hasAdmitted()&&
                        sameCaption(request?.url?.toString().orEmpty(),target)&&gate.live(ticket)) {
                        mark(if(response?.statusCode==401)LiderVerificationEvent.BODY_HTTP_401 else LiderVerificationEvent.BODY_HTTP_403)
                        unavailable()
                    } else if(request?.isForMainFrame==true&&response?.statusCode in setOf(401,403)){
                        mark(if(response?.statusCode==401)LiderVerificationEvent.INITIAL_HTTP_401 else LiderVerificationEvent.INITIAL_HTTP_403)
                    }
                }
                override fun onReceivedError(view:WebView?,request:WebResourceRequest?,error:android.webkit.WebResourceError?) {
                    if(request?.isForMainFrame==true&&live()){
                        mark(LiderVerificationEvent.MAIN_NETWORK_ERROR);unavailable()
                    }
                }
                override fun onPageFinished(view:WebView?,url:String?) {
                    if(view!==web||!live()||url==null||!sameCaption(url,target)||web.url!=url)return
                    val ready=documents.completed(url,false)
                    if(ready!=null)verifyBody(ready.download)
                    else if(documents.committedDocument()==null)mark(LiderVerificationEvent.NO_SOURCE_DOCUMENT)
                }
            }
            web.setDownloadListener { url,_,_,mime,_-> 
                if(!live())return@setDownloadListener
                if(sameCaption(url,target)&&
                    mime.orEmpty().substringBefore(';').lowercase(java.util.Locale.ROOT)in
                    setOf("text/vtt","text/plain","application/x-subrip","application/octet-stream")){
                    mark(LiderVerificationEvent.DOWNLOAD_OBSERVED)
                    val current=web.url
                    if(current!=null&&sourceDocument(current)){
                        val ready=documents.completed(current,true)
                        if(ready!=null)verifyBody(ready.download)
                        else if(documents.committedDocument()==null)mark(LiderVerificationEvent.NO_SOURCE_DOCUMENT)
                    }else mark(LiderVerificationEvent.NO_SOURCE_DOCUMENT)
                }
            }
            ownDialog=AlertDialog.Builder(owner).setTitle("LiderDrama altyazı doğrulaması")
                .setMessage("Sitenin doğrulamasını tamamlayın. Ardından bölümü yeniden açın.")
                .setView(web).setNegativeButton("Kapat",null).create()
            // OS-only native window identity for the owned test controller. AlertDialog's
            // visible title stays the human-readable caption verification title above.
            ownDialog.window?.let { nativeWindow->
                val attributes=nativeWindow.attributes
                attributes.setTitle("app.arcana.drama/ArcanaLiderVerify-"+java.util.UUID.randomUUID().toString())
                nativeWindow.attributes=attributes
            }
            ownDialog.setOnDismissListener {
                mark(LiderVerificationEvent.CLOSED)
                gate.end(ticket);documents.close();abort()
                if(dialog===ownDialog)dialog=null
                if(activeWeb===web)activeWeb=null
                web.stopLoading();web.webViewClient=WebViewClient();web.destroy()
            }
            dialog=ownDialog;activeWeb=web
            ownDialog.show()
            mark(LiderVerificationEvent.OPENED)
            web.loadUrl(target)
        }
    }
    fun close() {
        closed=true;gate.cancel();cachedPair=null;scope.cancel(null)
        Handler(Looper.getMainLooper()).post{dialog?.dismiss();dialog=null;activeWeb=null}
    }
    companion object {
        fun timed(text:String):Boolean=LiderCaptionPolicy.timed(text)
        private fun clearance(header:String?):String?=header.orEmpty().split(';')
            .firstOrNull{it.substringBefore('=').trim()=="cf_clearance"}?.substringAfter('=',"")?.takeIf{it.isNotBlank()}
        private fun sourceDocument(raw:String)=runCatching {
            val u=URI(raw);u.scheme=="https"&&u.host=="liderdrama.com"&&u.rawUserInfo==null&&u.port in setOf(-1,443)
        }.getOrDefault(false)
        private fun requireCaption(raw:String) {
            val u=URI(raw)
            require(sourceDocument(raw)&&u.rawQuery==null&&u.rawFragment==null)
            require(u.rawPath.matches(Regex("/subs/[A-Za-z0-9][A-Za-z0-9-]{0,239}/[1-9][0-9]{0,4}\\.(?:vtt|srt)")))
        }
        private fun sameCaption(raw:String,target:String):Boolean=runCatching {
            val u=URI(raw);val t=URI(target)
            sourceDocument(raw)&&u.rawPath==t.rawPath&&
                (u.rawQuery==null||u.rawQuery.split('&').all{it.substringBefore('=').startsWith("__cf_chl_")})
        }.getOrDefault(false)
        private fun navigation(raw:String,target:String):Boolean=runCatching {
            val u=URI(raw);sameCaption(raw,target)||(sourceDocument(raw)&&u.rawPath.startsWith("/cdn-cgi/"))
        }.getOrDefault(false)
        private fun resource(raw:String,method:String,target:String):Boolean=runCatching {
            val u=URI(raw);require(u.scheme=="https"&&u.rawUserInfo==null&&u.port in setOf(-1,443))
            when {
                u.host=="challenges.cloudflare.com"->method in setOf("GET","POST")
                u.host=="liderdrama.com"&&u.rawPath.startsWith("/cdn-cgi/")->method in setOf("GET","POST")
                sameCaption(raw,target)->method=="GET"
                else->false
            }
        }.getOrDefault(false)
    }
}
