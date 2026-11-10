package dev.arcana.anyshort

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaAnyShortPlugin:Plugin() {
    private var provider:AnyProvider?=null
    override fun load(context:Context){provider=AnyProvider(context.applicationContext).also { registerMainAPI(it) };openSettings={owner->provider?.showSettings(owner)}}
    override fun beforeUnload(){provider?.close();provider=null;openSettings=null}
}
