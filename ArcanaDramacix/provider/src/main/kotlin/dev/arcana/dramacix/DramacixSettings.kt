package dev.arcana.dramacix

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Only this provider's catalogue preferences. Never changes host subtitle/account settings. */
internal class DramacixSettings(context:Context) {
    private val preferences=context.applicationContext.getSharedPreferences("arcana-dramacix-catalogue",Context.MODE_PRIVATE)
    fun requests():List<DramacixCatalogue.Request> {
        val genre=preferences.getString("genre","").orEmpty().takeIf { it in DramacixCatalogue.genres }.orEmpty()
        val query=preferences.getString("query","").orEmpty().trim().takeIf { it.length<=160 && it.none(Char::isISOControl) }.orEmpty()
        val platform=runCatching { DramacixCatalogue.platformInput(preferences.getString("platform","").orEmpty()) }.getOrDefault("")
        return DramacixCatalogue.rows(genre,query,platform)
    }
    fun show(context:Context,onLogin:()->Unit) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { Handler(Looper.getMainLooper()).post { show(context,onLogin) };return }
        var c=context;var owner:Activity?=null
        for(depth in 0 until 16) {
            if(c is Activity) { owner=c;break }
            if(c !is ContextWrapper)break
            val next=c.baseContext;if(next===c)break;c=next
        }
        val activity=owner?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val pad=(16*context.resources.displayMetrics.density).toInt()
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(pad,pad,pad,pad) }
        fun text(value:String) { body.addView(TextView(context).apply { text=value;setPadding(0,pad/2,0,pad/2) }) }
        text("Tür seçimi popüler, yeni ve dublajlı listelere uygulanır.")
        val keys=DramacixCatalogue.genres.keys.toList()
        val genres=Spinner(context).apply {
            adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,DramacixCatalogue.genres.values.toList())
            setSelection(keys.indexOf(preferences.getString("genre","")).coerceAtLeast(0))
        };body.addView(genres)
        text("Platform kataloğu (isteğe bağlı)")
        val platform=EditText(context).apply { setSingleLine();hint="Örn: dramawave veya platform bağlantısı";filters=arrayOf(InputFilter.LengthFilter(320));setText(preferences.getString("platform","")) };body.addView(platform)
        text("Katalog içinde ara (isteğe bağlı)")
        val query=EditText(context).apply { setSingleLine();hint="Boş bırakınca normal kataloglar gösterilir";filters=arrayOf(InputFilter.LengthFilter(160));setText(preferences.getString("query","")) };body.addView(query)
        text("Arama doluyken yalnız sonuçlar gösterilir. Kaydettikten sonra kaynak ana sayfasını yenile.")
        val dialog=AlertDialog.Builder(activity).setTitle("(Arcana Drama) Cix").setView(body)
            .setPositiveButton("Kaydet",null).setNegativeButton("İptal",null)
            .setNeutralButton("Kaynak sitesi") { _,_->DramacixLogin.show(activity);onLogin() }.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value=runCatching {
                val p=DramacixCatalogue.platformInput(platform.text.toString())
                val q=query.text.toString().trim();val g=keys[genres.selectedItemPosition]
                DramacixCatalogue.rows(g,q,p);Triple(g,q,p)
            }.getOrNull()
            if(value==null)Toast.makeText(context,"Tür veya platform bağlantısı tanınmadı.",Toast.LENGTH_LONG).show()
            else {
                preferences.edit().putString("genre",value.first).putString("query",value.second).putString("platform",value.third).apply()
                dialog.dismiss();Toast.makeText(context,"Katalog seçimi kaydedildi. Kaynak ana sayfasını yenile.",Toast.LENGTH_LONG).show()
            }
        }
    }
}
