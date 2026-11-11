package dev.arcana.liderdrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArcanaLiderDramaPlugin:Plugin() {
    private var provider:LiderProvider?=null
    override fun load(context:Context){val p=LiderProvider(context.applicationContext);provider=p;openSettings={owner->p.showSettings(owner)};registerMainAPI(p)}
    override fun beforeUnload(){provider?.close();provider=null;openSettings=null}
}
