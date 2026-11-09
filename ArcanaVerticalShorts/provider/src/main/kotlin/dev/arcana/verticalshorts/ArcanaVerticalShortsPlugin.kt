package dev.arcana.verticalshorts

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaVerticalShortsPlugin : Plugin() {
    private var provider: VerticalProvider? = null
    override fun load(context: Context) {
        provider = VerticalProvider(context.applicationContext).also { registerMainAPI(it) }
        openSettings = { owner -> provider?.showSettings(owner) }
    }
    override fun beforeUnload() { provider?.close(); provider = null; openSettings = null }
}
