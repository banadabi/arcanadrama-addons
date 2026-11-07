package dev.arcana.fulldrama

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Legitimate source-issued WebView state. No navigator/fingerprint overrides or cookie export. */
internal class FullDramaSession(context: Context) {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private val preferences = this.context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    @Volatile var userAgent: String = ""
        private set
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var stopOwnedBootstrap: (() -> Unit)? = null
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun bootstrap(): Unit = mutex.withLock {
        if (closed) throw ErrorLoadingException("FullDrama oturumu kapandı.")
        val age = System.currentTimeMillis() - preferences.getLong("last_normal_bootstrap",0)
        if (FullDramaLogin.cookies(FullDramaContract.BASE).isNotBlank() && (ready || age in 0..600_000)) {
            userAgent = withContext(Dispatchers.Main.immediate) { WebSettings.getDefaultUserAgent(context) }
            ready = true; return@withLock
        }
        withTimeout(12_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val handler = Handler(Looper.getMainLooper())
                var browser: WebView? = null
                var finished = false
                fun finish(error: String? = null) {
                    if (finished) return
                    finished = true
                    val old = browser; browser = null
                    old?.webViewClient = WebViewClient(); old?.stopLoading(); old?.destroy()
                    stopOwnedBootstrap = null
                    if (continuation.isActive) {
                        if (error == null) { ready = true; preferences.edit().putLong("last_normal_bootstrap",System.currentTimeMillis()).apply(); continuation.resume(Unit) }
                        else continuation.resumeWithException(ErrorLoadingException(error))
                    }
                }
                continuation.invokeOnCancellation { handler.post { finish("FullDrama isteği iptal edildi.") } }
                stopOwnedBootstrap = { handler.post { finish("FullDrama oturumu kapandı.") } }
                handler.post {
                    try {
                        if (!continuation.isActive || closed) { finish("FullDrama isteği iptal edildi."); return@post }
                        if (!CookieManager.getInstance().acceptCookie()) { finish("FullDrama kaynak oturumu için çerez izni gerekiyor."); return@post }
                        browser = WebView(context).apply {
                            userAgent = settings.userAgentString
                            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                            settings.allowFileAccess = false; settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webChromeClient = object : WebChromeClient() { override fun onConsoleMessage(message: ConsoleMessage) = true }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    runCatching { FullDramaContract.own(request.url.toString()); false }.getOrDefault(true)
                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (request.isForMainFrame && response.statusCode >= 400) {
                                        finish(if (response.statusCode in setOf(401,403,429)) "FullDrama erişim veya doğrulama istiyor. Eklenti ayarından kaynak sitesini açın."
                                            else "FullDrama kaynak sayfası HTTP ${response.statusCode} yanıtı verdi.")
                                    }
                                }
                                override fun onPageFinished(view: WebView, url: String) {
                                    if (finished || !continuation.isActive || closed) return
                                    val u = runCatching { FullDramaContract.own(url) }.getOrNull() ?: return
                                    if (u.path in setOf("/","/series") && view.title.orEmpty().contains("FullDrama",true) &&
                                        CookieManager.getInstance().getCookie(url).orEmpty().isNotBlank()) {
                                        view.evaluateJavascript("""(function(){return JSON.stringify(Array.from(document.querySelectorAll('a[href]')).map(function(a){try{var u=new URL(a.href);if(u.origin==='https://fulldrama.xyz'&&u.pathname==='/series'&&u.searchParams.has('category'))return {key:u.searchParams.get('category'),name:a.textContent.trim()};}catch(e){}return null;}).filter(Boolean).slice(0,80));})()""") { value ->
                                            if (!finished) {
                                                runCatching {
                                                    val body = JSONTokener(value).nextValue() as? String
                                                    val rows = body?.let(::JSONArray) ?: JSONArray()
                                                    val clean = JSONArray()
                                                    val seen = mutableSetOf<String>()
                                                    for (i in 0 until rows.length()) {
                                                        val o = rows.optJSONObject(i) ?: continue
                                                        val key = o.optString("key"); val label = o.optString("name").replace(Regex("\\s+")," ").trim()
                                                        if (key.matches(Regex("[a-z0-9][a-z0-9_-]{0,79}")) && label.length in 1..80 && seen.add(key)) clean.put(JSONObject().put("key",key).put("name",label))
                                                    }
                                                    if (clean.length() > 0) preferences.edit().putString("categories",clean.toString()).apply()
                                                }
                                                finish()
                                            }
                                        }
                                    }
                                }
                            }
                            loadUrl(FullDramaContract.BASE + "/series")
                        }
                    } catch (_: Exception) { finish("FullDrama tarayıcı başlangıcı tamamlanamadı.") }
                }
            }
        }
    }
    fun close() { closed = true; stopOwnedBootstrap?.invoke() }
    companion object {
        private const val PREF = "arcana.fulldrama.source_state"
        fun invalidate(context: Context) { context.applicationContext.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putLong("last_normal_bootstrap",0).apply() }
        fun categories(context: Context): List<Pair<String,String>> {
            val raw = context.applicationContext.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("categories",null)
            val found = runCatching {
                val a = JSONArray(raw ?: "[]")
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val key = o.optString("key"); val label = o.optString("name")
                    if (key.matches(Regex("[a-z0-9][a-z0-9_-]{0,79}")) && label.length in 1..80) key to label else null
                }.distinctBy { it.first }.take(80)
            }.getOrDefault(emptyList())
            return found.ifEmpty { listOf("dramawave" to "DramaWave","netshort" to "NetShort","reelshort" to "ReelShort","shortmax" to "ShortMax","flextv" to "FlexTV","stardusttv" to "StarDustTV","dramabox" to "DramaBox","netshort-dublajli" to "NetShort (Dublajlı)","dramawave-dublajli" to "DramaWave (Dublajlı)","shortmax-dublajli" to "ShortMax (Dublajlı)","reelshort-dublajli" to "ReelShort (Dublajlı)","dotdrama" to "DotDrama") }
        }
    }
}
