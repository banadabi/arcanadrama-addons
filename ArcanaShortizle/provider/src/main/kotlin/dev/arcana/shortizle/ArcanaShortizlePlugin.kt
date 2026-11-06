package dev.arcana.shortizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaShortizlePlugin : Plugin() {
    private var provider: ArcanaShortizleProvider? = null
    override fun load(context: Context) {
        provider=ArcanaShortizleProvider(context.applicationContext).also { registerMainAPI(it) }
    }
    override fun beforeUnload() { provider?.close();provider=null }
}
