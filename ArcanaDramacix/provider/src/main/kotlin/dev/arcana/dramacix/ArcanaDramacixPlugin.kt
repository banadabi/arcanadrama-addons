package dev.arcana.dramacix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaDramacixPlugin:Plugin() {
    private var provider:DramacixProvider?=null
    override fun load(context:Context) {
        openSettings={ owner -> provider?.showSettings(owner) }
        provider=DramacixProvider(context.applicationContext).also { registerMainAPI(it) }
    }
    override fun beforeUnload() { provider?.close();provider=null;openSettings=null }
}
