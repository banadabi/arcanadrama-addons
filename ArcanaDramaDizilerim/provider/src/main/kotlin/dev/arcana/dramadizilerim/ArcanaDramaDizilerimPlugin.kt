package dev.arcana.dramadizilerim

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaDramaDizilerimPlugin:Plugin() {
    private var provider:DramaDizilerimProvider?=null
    override fun load(context:Context) {
        openSettings={ owner -> DramaDizilerimLogin.show(owner) }
        provider=DramaDizilerimProvider(context.applicationContext).also { registerMainAPI(it) }
    }
    override fun beforeUnload() { provider?.close();provider=null;openSettings=null }
}
