package dev.arcana.lidershort

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaLiderShortPlugin : Plugin() {
    private var provider: LiderShortProvider? = null
    override fun load(context: Context) {
        provider = LiderShortProvider(context.applicationContext).also { registerMainAPI(it) }
        openSettings = { owner -> provider?.showSettings(owner) }
    }
    override fun beforeUnload() { provider?.close(); provider = null; openSettings = null }
}
