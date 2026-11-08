package dev.arcana.dramavakti

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaDramaVaktiPlugin:Plugin() {
    private var provider:DramaVaktiProvider?=null
    override fun load(context:Context) {
        openSettings={ owner -> DramaVaktiLogin.show(owner) }
        provider=DramaVaktiProvider(context.applicationContext).also { registerMainAPI(it) }
    }
    override fun beforeUnload() { provider?.close();provider=null;openSettings=null }
}
