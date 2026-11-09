package dev.arcana.verticalshorts

import android.os.Build
import android.os.Looper
import android.webkit.WebView
import com.lagradost.cloudstream3.ErrorLoadingException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small binding to the installed WebView's standard support-library boundary.
 * Protocol: AndroidX WebKit 1.12.1, Apache-2.0 public source signatures.
 * Uses the provider's own canonical interfaces; no host Jetpack dependency,
 * hidden Android API, feature override, source guard or browser identity change.
 */
internal object DocumentScripts {
    class Handle internal constructor(private val handler: InvocationHandler, private val remove: Method) {
        private val removed = AtomicBoolean(false)
        fun remove() {
            if (removed.compareAndSet(false, true)) runCatching { handler.invoke(null, remove, null) }
        }
    }

    fun install(view: WebView, script: String, origin: String): Handle {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (Build.VERSION.SDK_INT < 28) throw unsupported()
        return try {
            val loader = WebView.getWebViewClassLoader()
            val glue = Class.forName("org.chromium.support_lib_glue.SupportLibReflectionUtil", false, loader)
            val factory = glue.getDeclaredMethod("createWebViewProviderFactory").invoke(null) as InvocationHandler
            val factoryApi = Class.forName("org.chromium.support_lib_boundary.WebViewProviderFactoryBoundaryInterface", false, loader)
            val features = factory.invoke(null, factoryApi.getMethod("getSupportedFeatures"), null) as Array<*>
            if (!features.contains("DOCUMENT_START_SCRIPT:1")) {
                android.util.Log.i("ArcanaVerticalShorts", "document_start result=feature_unavailable")
                throw unsupported()
            }
            android.util.Log.i("ArcanaVerticalShorts", "document_start result=feature_available")
            val provider = factory.invoke(null, factoryApi.getMethod("createWebView", WebView::class.java), arrayOf(view)) as InvocationHandler
            val providerApi = Class.forName("org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface", false, loader)
            val add = providerApi.getMethod("addDocumentStartJavaScript", String::class.java, Array<String>::class.java)
            val handler = provider.invoke(null, add, arrayOf(script, arrayOf(origin))) as InvocationHandler
            val handlerApi = Class.forName("org.chromium.support_lib_boundary.ScriptHandlerBoundaryInterface", false, loader)
            Handle(handler, handlerApi.getMethod("remove"))
        } catch (e: LinkageError) {
            android.util.Log.i("ArcanaVerticalShorts", "document_start result=linkage exception=" + e.javaClass.simpleName)
            throw unsupported()
        } catch (e: Exception) {
            android.util.Log.i("ArcanaVerticalShorts", "document_start result=bridge exception=" + e.javaClass.simpleName)
            throw unsupported()
        }
    }

    private fun unsupported() = ErrorLoadingException("Bu uygulamada kaynağın normal tarayıcı akışı desteklenmiyor.")
}
