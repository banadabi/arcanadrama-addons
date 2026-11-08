package dev.arcana.dramavakti

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URI
import kotlin.coroutines.resume

/** The user's normal source login stays in this host app's WebView cookie store. */
object DramaVaktiLogin {
    private const val PREFS="arcana_dramavakti_account"
    fun enabled(context:Context)=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean("enabled",false)
    suspend fun userAgent(context:Context):String=withContext(Dispatchers.Main.immediate) { WebSettings.getDefaultUserAgent(context.applicationContext) }

    private const val LOGIN = "https://dramavakti.com/hesap"
    private val hosts = setOf("dramavakti.com")

    private fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in hosts &&
            uri.port in setOf(-1, 443) && uri.rawUserInfo == null
    }.getOrDefault(false)

    /** Call only for source pages/API. No cookie is returned for a media CDN. */
    suspend fun cookies(url: String): String {
        if (!allowed(url)) return ""
        return withContext(Dispatchers.Main.immediate) {
            CookieManager.getInstance().getCookie(url).orEmpty()
        }
    }

    /** Preserve normal source session renewal using the platform's cookie domain rules. */
    suspend fun saveCookies(url: String, values: List<String>) {
        if (!allowed(url) || values.isEmpty()) return
        val manager = withContext(Dispatchers.Main.immediate) {
            val cookies = CookieManager.getInstance()
            for (value in values) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    cookies.setCookie(url, value) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
            cookies
        }
        withContext(Dispatchers.IO) { manager.flush() }
    }

    fun show(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { show(context) }
            return
        }
        var current = context
        var activity: Activity? = null
        for (depth in 0 until 16) {
            if (current is Activity) {
                activity = current as Activity
                break
            }
            if (current is ContextWrapper) {
                val next = (current as ContextWrapper).baseContext
                if (next === current) break
                current = next
            } else break
        }
        val owner = activity
        if (owner == null || owner.isFinishing || owner.isDestroyed) {
            Toast.makeText(context, "DramaVakti girişini eklentinin ayar düğmesinden açın.", Toast.LENGTH_LONG).show()
            return
        }
        showDialog(context)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean("enabled",true).apply()
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(TextView(context).apply {
            text = "DramaVakti hesabı"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(padding, padding, padding, padding / 2)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.addView(TextView(context).apply {
            text = "DramaVakti hesabınızla kaynak sitesinde giriş yapın. İşiniz bitince Kapat’a basıp dizi sayfasını yeniden açın."
            setPadding(padding, padding / 2, padding, padding / 2)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val browser = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !allowed(request.url.toString())

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !allowed(url)

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame && !allowed(request.url.toString())) {
                        return WebResourceResponse(
                            "text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                            ByteArrayInputStream("Yalnız DramaVakti hesap sayfası açılabilir.".toByteArray(Charsets.UTF_8))
                        )
                    }
                    return null
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && !allowed(url)) view.stopLoading()
                }
            }
        }
        layout.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        layout.addView(Button(context).apply {
            text = "Kapat"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(layout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && browser.canGoBack()) {
                if (event.action == KeyEvent.ACTION_UP) browser.goBack()
                true
            } else false
        }
        dialog.setOnDismissListener {
            // Persist only the normal browser store; never clear another source's cookies/data.
            try {
                CookieManager.getInstance().flush()
            } finally {
                browser.stopLoading()
                (browser.parent as? ViewGroup)?.removeView(browser)
                browser.removeAllViews()
                browser.destroy()
            }
        }
        dialog.show()
        val metrics = context.resources.displayMetrics
        dialog.window?.apply {
            // Keep the form's weighted viewport measurable and permit its normal keyboard.
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout((metrics.widthPixels * 0.95).toInt(), (metrics.heightPixels * 0.88).toInt())
        }
        browser.loadUrl(LOGIN)
    }
}
