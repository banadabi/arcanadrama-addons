package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import kotlin.Throws

/**
 * CloudStream Android eklenti temel sınıfı.
 * Gerçek CloudStream mimarisinde `BasePlugin` library modülünde yer alır;
 * `Plugin` ise Android `Context` ile yükleme sağlayan Android spesifik sınıftır.
 */
abstract class Plugin : BasePlugin() {
    /**
     * Called when your Plugin is loaded
     * @param context Context
     */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // If not overridden by an extension then try the cross-platform load()
        load()
    }

    /**
     * This will contain your resources if you specified requiresResources in gradle
     */
    var resources: Resources? = null

    /**
     * This will add a button in the settings allowing you to add custom settings
     */
    var openSettings: ((context: Context) -> Unit)? = null
}
