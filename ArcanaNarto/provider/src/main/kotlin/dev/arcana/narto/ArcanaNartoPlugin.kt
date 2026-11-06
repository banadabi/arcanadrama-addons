package dev.arcana.narto
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
@CloudstreamPlugin
class ArcanaNartoPlugin:Plugin(){
 private var provider:NartoProvider?=null
 override fun load(context:Context){provider=NartoProvider(context.applicationContext).also{registerMainAPI(it)}}
 override fun beforeUnload(){provider?.close();provider=null}
}
