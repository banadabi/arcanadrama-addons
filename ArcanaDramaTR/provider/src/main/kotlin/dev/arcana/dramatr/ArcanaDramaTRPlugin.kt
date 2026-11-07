package dev.arcana.dramatr

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaDramaTRPlugin : Plugin() {
    private var provider: DramaTRProvider? = null
    override fun load(context: Context) {
        openSettings = { owner -> DramaTRLogin.show(owner) }
        provider = DramaTRProvider().also { registerMainAPI(it) }
    }
    override fun beforeUnload() { provider?.close(); provider=null; openSettings=null }
}
