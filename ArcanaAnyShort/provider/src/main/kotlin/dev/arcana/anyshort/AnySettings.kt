package dev.arcana.anyshort

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import org.json.JSONObject

internal class AnySettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("arcana_anyshort_catalogue", Context.MODE_PRIVATE)
    fun request(): String {
        val q = preferences.getString("query", "").orEmpty().trim().take(300)
        return if (q.isEmpty()) "home" else JSONObject().put("q", q).toString()
    }
    fun show(context: Context, active: () -> Boolean, changed: () -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val activity = context as? Activity ?: return@post
            if (activity.isFinishing || activity.isDestroyed || !active()) return@post
            val field = EditText(activity).apply {
                hint = "Kaynak kataloğunda ara (boş: ana sayfa)"
                setText(preferences.getString("query", "")); setSingleLine(true)
            }
            AlertDialog.Builder(activity).setTitle(AnyContract.NAME)
                .setMessage("Kaydettikten sonra kaynak ana sayfasını yenileyin.")
                .setView(field).setNegativeButton("İptal", null)
                .setPositiveButton("Kaydet") { _, _ ->
                    if (active()) { preferences.edit().putString("query", field.text.toString().trim().take(300)).apply(); changed() }
                }.show()
        }
    }
}
