package dev.arcana.liderdrama

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.*
import org.json.JSONObject

internal class LiderSettings(context:Context) {
    data class Filters(val q:String="",val genre:String="all",val sort:String="popular",val dubbed:Boolean=false,val episodes:String="all") {
        val default get()=q.isEmpty()&&genre=="all"&&sort=="popular"&&!dubbed&&episodes=="all"
        fun encode()=JSONObject().put("q",q).put("genre",genre).put("sort",sort).put("dubbed",dubbed).put("episodes",episodes).toString()
    }
    private val p=context.applicationContext.getSharedPreferences("arcana_lider_catalogue",Context.MODE_PRIVATE)
    fun read()=Filters(p.getString("q","").orEmpty().trim().take(100),p.getString("genre","all").takeIf{it in LiderContract.genres}?:"all",p.getString("sort","popular").takeIf{it in LiderContract.sorts}?:"popular",p.getBoolean("dubbed",false),p.getString("episodes","all").takeIf{it in LiderContract.counts}?:"all")
    fun show(context:Context,active:()->Boolean,changed:()->Unit) {
        Handler(Looper.getMainLooper()).post {
            val a=context as?Activity?:return@post;if(a.isFinishing||a.isDestroyed||!active())return@post
            val original=read();val box=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(30,12,30,0)}
            val query=EditText(a).apply{hint="Kaynakta ara (boş: ana sayfa)";setSingleLine(true);setText(original.q)};box.addView(query)
            fun select(label:String,options:Map<String,String>,current:String):Spinner {
                box.addView(TextView(a).apply{text=label});val keys=options.keys.toList();return Spinner(a).apply {
                    adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,options.values.toList());setSelection(keys.indexOf(current).coerceAtLeast(0));box.addView(this)
                }
            }
            val genre=select("Tür",LiderContract.genres,original.genre);val sort=select("Arama sıralaması",LiderContract.sorts,original.sort);val counts=select("Bölüm sayısı",LiderContract.counts,original.episodes)
            val dubbed=CheckBox(a).apply{text="Aramada yalnız Türkçe dublajlı";isChecked=original.dubbed};box.addView(dubbed)
            AlertDialog.Builder(a).setTitle(LiderContract.NAME).setMessage("Tür ve bölüm sayısı raflara da uygulanır. Sıralama ve dublaj seçimi arama içindir. Kaydettikten sonra kaynak ana sayfasını yenileyin.").setView(ScrollView(a).apply{addView(box)})
                .setNegativeButton("İptal",null).setPositiveButton("Kaydet"){_,_->if(active()) {
                    p.edit().putString("q",query.text.toString().trim().take(100)).putString("genre",LiderContract.genres.keys.elementAt(genre.selectedItemPosition))
                        .putString("sort",LiderContract.sorts.keys.elementAt(sort.selectedItemPosition)).putString("episodes",LiderContract.counts.keys.elementAt(counts.selectedItemPosition)).putBoolean("dubbed",dubbed.isChecked).apply();changed()
                }}.show()
        }
    }
}
