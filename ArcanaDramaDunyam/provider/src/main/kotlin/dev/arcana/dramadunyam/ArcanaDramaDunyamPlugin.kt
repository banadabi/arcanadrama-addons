package dev.arcana.dramadunyam

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaDramaDunyamPlugin : Plugin() {
    private var provider: DunyaProvider? = null
    override fun load(context: Context) {
        provider = DunyaProvider(context.applicationContext).also { registerMainAPI(it) }
        openSettings = { owner -> provider?.showSettings(owner) }
    }
    override fun beforeUnload() { provider?.close(); provider = null; openSettings = null }
}
