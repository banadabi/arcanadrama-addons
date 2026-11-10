package dev.arcana.minidiziizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaMiniDiziIzlePlugin : Plugin() {
    private var provider: MiniProvider? = null
    override fun load(context: Context) {
        val p = MiniProvider(context.applicationContext)
        provider = p;openSettings = { owner -> p.settings(owner) };registerMainAPI(p)
    }
    override fun beforeUnload() { provider?.close();provider=null;openSettings=null }
}
