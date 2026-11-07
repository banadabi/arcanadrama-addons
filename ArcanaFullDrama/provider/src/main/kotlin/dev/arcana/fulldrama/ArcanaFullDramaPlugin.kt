package dev.arcana.fulldrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaFullDramaPlugin : Plugin() {
    private var provider: FullDramaProvider? = null
    override fun load(context: Context) {
        openSettings = { owner -> FullDramaLogin.show(owner) }
        provider = FullDramaProvider(context.applicationContext).also { registerMainAPI(it) }
    }
    override fun beforeUnload() { provider?.close(); provider = null; openSettings = null }
}
