package dev.arcana.izlegec
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
@CloudstreamPlugin
class ArcanaIzleGecPlugin : Plugin() {
    private var provider: IzleGecProvider? = null
    override fun load(context: Context) {
        openSettings = { settingsContext -> IzleGecLogin.show(settingsContext) }
        provider=IzleGecProvider(
            sessionCookies = { url -> IzleGecLogin.cookies(url) },
            saveSessionCookies = { url, values -> IzleGecLogin.saveCookies(url, values) }
        ).also { registerMainAPI(it) }
    }
    override fun beforeUnload() { provider?.close(); provider=null; openSettings=null }
}
