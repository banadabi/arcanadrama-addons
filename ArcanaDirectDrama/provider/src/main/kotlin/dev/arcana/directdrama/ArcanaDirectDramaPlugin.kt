package dev.arcana.directdrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaDirectDramaPlugin : Plugin() {
    private var provider: DirectProvider? = null
    override fun load(context: Context) {
        provider = DirectProvider(context.applicationContext).also { registerMainAPI(it) }
        openSettings = { owner -> provider?.showSettings(owner) }
    }
    override fun beforeUnload() { provider?.close(); provider = null; openSettings = null }
}
