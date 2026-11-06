package dev.arcana.narto

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Original standard MainAPI. Site-issued guest cookies and source URLs never enter repository metadata. */
class NartoProvider(private val context:Context):MainAPI(){
    override var name="(Arcana Drama) Narto"
    override var mainUrl=BASE
    override var lang="tr"
    override val supportedTypes=setOf(TvType.TvSeries,TvType.Anime,TvType.Movie)
    override val hasMainPage=true
    override val hasQuickSearch=true
    @Volatile private var browserCookies:CookieManager?=null
    @Volatile private var userAgent="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val bootstrapMutex=Mutex()
    private val directHttps=context.packageName!="com.kaan.play"
    @Volatile private var httpsTransport:ArcanaHttpsTransport?=null
    @Synchronized private fun https():ArcanaHttpsTransport=httpsTransport?.takeUnless{it.isClosed}?:ArcanaHttpsTransport(::publicUrl).also{httpsTransport=it}
    override fun getVideoInterceptor(extractorLink:ExtractorLink):Interceptor?=if(directHttps)https() else null
    @Volatile private var mediaSession:NartoMediaSession?=null
    private val jar=BrowserJar()
    private val client=OkHttpClient.Builder().cookieJar(jar).followRedirects(false).followSslRedirects(false)
        .connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS)
        .dns { host -> Dns.SYSTEM.lookup(host).filterNot(::privateAddress).also { if(it.isEmpty())throw IOException("Narto public address unavailable") } }.build()

    private inner class BrowserJar:CookieJar{
        private val cookies=linkedMapOf<String,Cookie>()
        override fun saveFromResponse(url:HttpUrl,values:List<Cookie>)=synchronized(cookies){
            cookies.entries.removeAll{it.value.expiresAt<=System.currentTimeMillis()}
            for(c in values){val k="${c.domain}|${c.path}|${c.name}";if(c.expiresAt>System.currentTimeMillis())cookies[k]=c else cookies.remove(k)}
            if(cookies.size>512)cookies.clear()
        }
        override fun loadForRequest(url:HttpUrl):List<Cookie> = synchronized(cookies){
            val result=linkedMapOf<String,Cookie>()
            if(webHost(url.host))browserCookies?.getCookie(url.toString())?.split(';')?.forEach{part->
                val key=part.substringBefore('=').trim();val value=part.substringAfter('=',"").trim()
                if(key.isNotBlank()&&value.isNotBlank())runCatching{Cookie.Builder().name(key).value(value).hostOnlyDomain(url.host).path("/").secure().build()}.getOrNull()?.let{result[key]=it}
            }
            cookies.values.filter{it.expiresAt>System.currentTimeMillis()&&it.matches(url)}.forEach{result[it.name]=it}
            result.values.toList()
        }
    }
    private data class Reply(val code:Int,val body:String,val url:HttpUrl,val location:String?)
    private suspend fun initializeBrowserState()=withContext(Dispatchers.Main.immediate){
        if(browserCookies==null){browserCookies=CookieManager.getInstance();userAgent=WebSettings.getDefaultUserAgent(context)}
    }
    /** Let the publisher's normal page JavaScript initialize its own guest cookie. No fabricated challenge values. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun bootstrap()=bootstrapMutex.withLock{
        initializeBrowserState()
        withTimeout(15_000){suspendCancellableCoroutine<Unit>{continuation->
            val handler=Handler(Looper.getMainLooper());var view:WebView?=null
            fun close(){val old=view;view=null;old?.webViewClient=WebViewClient();old?.stopLoading();old?.destroy()}
            continuation.invokeOnCancellation{handler.post{close()}}
            handler.post{
                try{
                    if(!continuation.isActive)return@post
                    if(browserCookies?.acceptCookie()!=true)throw ErrorLoadingException("Narto tarayıcı oturumu için çerez izni gerekiyor.")
                    view=WebView(context).apply{
                        settings.javaScriptEnabled=true;settings.domStorageEnabled=true
                        settings.allowFileAccess=false;settings.allowContentAccess=false
                        settings.setSupportMultipleWindows(false)
                        userAgent=settings.userAgentString
                        webViewClient=object:WebViewClient(){
                            override fun shouldOverrideUrlLoading(v:WebView?,request:android.webkit.WebResourceRequest?):Boolean =
                                request?.url?.let{it.scheme!="https"||!webHost(it.host.orEmpty())} ?: true
                            override fun onPageFinished(v:WebView?,url:String?){
                                val target=url?.toHttpUrlOrNull()
                                if(target!=null&&webHost(target.host)&&browserCookies?.getCookie(target.toString()).orEmpty().split(';').any{it.trim().startsWith("nd_ck=")}){
                                    close();if(continuation.isActive)continuation.resume(Unit){_,_,_->}
                                }
                            }
                        }
                        loadUrl(BASE+"/?lang=tr-TR")
                    }
                }catch(e:Exception){close();if(continuation.isActive)continuation.resumeWithException(e)}
            }
        }}
    }
    private fun publicUrl(raw:String):HttpUrl{
        val u=raw.toHttpUrlOrNull() ?: throw ErrorLoadingException("Narto kaynak adresi geçersiz.")
        if(u.scheme!="https"||u.port!=443||u.username.isNotEmpty()||u.password.isNotEmpty())throw ErrorLoadingException("Narto HTTPS adresi bekleniyor.")
        return u
    }
    private fun webUrl(raw:String):HttpUrl=publicUrl(raw).also{if(!webHost(it.host))throw ErrorLoadingException("Narto sayfa sunucusu uyuşmadı.")}
    private suspend fun request(raw:String,accept:String="text/html, application/json;q=0.9",limit:Int=12_000_000):Reply=withContext(Dispatchers.IO){
        val url=webUrl(raw);val call=client.newCall(Request.Builder().url(url).header("User-Agent",userAgent).header("Accept-Encoding","identity").header("Accept",accept).build())
        val response:Response=suspendCancellableCoroutine{c->
            c.invokeOnCancellation{call.cancel()}
            call.enqueue(object:Callback{
                override fun onFailure(call:Call,e:IOException){if(c.isActive)c.resumeWithException(e)}
                override fun onResponse(call:Call,response:Response){if(c.isActive)c.resume(response){_,r,_->r.close()}else response.close()}
            })
        }
        response.use{r->
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            r.body.byteStream().use{input->while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;if(out.size()+n>limit)throw ErrorLoadingException("Narto yanıt boyutu sınırı aşıldı.");out.write(buffer,0,n)}}
            Reply(r.code,out.toString("UTF-8"),url,r.header("Location"))
        }
    }
    private suspend fun web(raw:String,accept:String="text/html, application/json;q=0.9",limit:Int=12_000_000):Reply{
        initializeBrowserState();var target=webUrl(raw);var bootstrapped=false
        repeat(6){
            val r=request(target.toString(),accept,limit)
            if(r.code in listOf(301,302,303,307,308)&&r.location!=null){target=webUrl(target.resolve(r.location)?.toString()?:throw ErrorLoadingException("Narto yönlendirmesi geçersiz."));return@repeat}
            if(r.code==200&&r.body.contains("Checking your browser")){
                if(bootstrapped)throw ErrorLoadingException("Narto tarayıcı doğrulamasını tamamlayamadı.")
                bootstrap();bootstrapped=true;return@repeat
            }
            return r
        }
        throw ErrorLoadingException("Narto yönlendirme sınırı aşıldı.")
    }
    private fun checked(r:Reply):String{
        if(r.code==429)throw ErrorLoadingException("Narto istek sınırına ulaşıldı; daha sonra yeniden dene.")
        if(r.code !in 200..299)throw ErrorLoadingException("Narto isteği tamamlanamadı (HTTP ${r.code}).")
        return r.body
    }
    private fun text(o:JSONObject,key:String):String?=(o.opt(key) as? String)?.trim()?.takeIf{it.isNotEmpty()&&it!="null"}
    private fun arrayObjects(a:JSONArray?):List<JSONObject> =(0 until(a?.length()?:0)).mapNotNull{a?.optJSONObject(it)}
    private fun absolute(raw:String,base:HttpUrl=BASE.toHttpUrlOrNull()!!):String=base.resolve(raw)?.toString()?:throw ErrorLoadingException("Narto bağlantısı geçersiz.")
    private fun hint(u:HttpUrl):String=u.fragment?.takeIf{it.startsWith("arcana-narto=")}?.substringAfter('=')?:"short"
    private fun withHint(raw:String,kind:String):String=webUrl(absolute(raw)).newBuilder().fragment("arcana-narto=$kind").build().toString()
    private fun cacheDir():File=File(context.cacheDir,"arcana-narto-v1").also{
        if(!it.exists()&&!it.mkdir())throw IOException("Narto cache directory unavailable")
        if(it.canonicalFile.parentFile!=context.cacheDir.canonicalFile)throw IOException("Narto cache directory invalid")
    }
    private fun cacheFile(key:String)=File(cacheDir(),MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}+".json")
    private fun cached(key:String):JSONObject?=runCatching{val f=cacheFile(key);if(!f.isFile||f.length()>3_000_000||System.currentTimeMillis()-f.lastModified()>300_000)return@runCatching null;JSONObject(f.readText())}.getOrNull()
    private fun saveCache(key:String,value:JSONObject){
        val dir=cacheDir();val data=value.toString().toByteArray(Charsets.UTF_8);if(data.size>3_000_000)return
        val lock=File(dir,"catalog.lock")
        if(lock.canonicalFile.parentFile!=dir.canonicalFile)throw IOException("Narto cache lock invalid")
        java.io.FileOutputStream(lock,true).channel.use { channel ->
            val held=try{channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){null} ?: return
            held.use {
                val target=cacheFile(key)
                val files=dir.listFiles()?.filter{it.isFile&&it.name.matches(Regex("[a-f0-9]{64}\\.json"))}?.sortedBy{it.lastModified()}.orEmpty()
                var bytes=files.filter{it!=target}.sumOf{it.length()};var count=files.count{it!=target}
                for(f in files){
                    if(bytes+data.size<=8_000_000&&count+1<=64)break
                    if(f==target)continue
                    val size=f.length();if(f.delete()){bytes-=size;count--}
                }
                if(bytes+data.size>8_000_000||count+1>64)return
                val temp=File.createTempFile("catalog-",".tmp",dir)
                try{temp.writeBytes(data);if(!temp.renameTo(target))throw IOException("Narto cache write failed")}finally{temp.delete()}
            }
        }
    }
    private fun metadataCatalog(p:JSONObject):JSONObject?{
        val providers=JSONArray()
        for(o in arrayObjects(p.optJSONArray("providers"))){val key=text(o,"key");val label=text(o,"label");if(key!=null&&label!=null)providers.put(JSONObject().put("key",key).put("label",label))}
        val sections=JSONArray();var cacheable=true
        for(section in arrayObjects(p.optJSONArray("sections"))){
            val items=JSONArray()
            for(item in arrayObjects(section.optJSONArray("items"))){
                val raw=text(item,"watch_url")?:continue
                val u=runCatching{webUrl(absolute(raw))}.getOrNull()
                val allowedQuery=setOf("provider","book_id","title","lang","target_lang","from","type","type_context")
                if(u==null||(u.encodedPath!="/search/import"&&!u.encodedPath.startsWith("/detail/watch/"))||!allowedQuery.containsAll(u.queryParameterNames)){cacheable=false;continue}
                val safe=JSONObject()
                for(k in listOf("book_id","title","description","poster_url","watch_url","source_type","category_name")){
                    val value=item.opt(k);if(value is String||value is Number)safe.put(k,value)
                }
                val tags=JSONArray();val original=item.optJSONArray("tag_names")
                for(i in 0 until(original?.length()?:0))(original?.opt(i) as? String)?.let{tags.put(it)}
                safe.put("tag_names",tags).put("is_adult",item.optBoolean("is_adult",false));items.put(safe)
            }
            sections.put(JSONObject().put("tab_key",text(section,"tab_key")).put("tab_label",text(section,"tab_label")).put("page",section.optInt("page",1)).put("has_prev",section.optBoolean("has_prev",false)).put("items",items))
        }
        return if(cacheable)JSONObject().put("ok",true).put("providers",providers).put("sections",sections) else null
    }
    private fun providers():Map<String,String>{
        val pairs=linkedMapOf<String,String>();pairs.putAll(DEFAULT_PROVIDERS)
        cached("provider-registry")?.optJSONArray("providers")?.let{a->arrayObjects(a).forEach{o->val k=text(o,"key");val n=text(o,"label");if(k!=null&&n!=null)pairs[k]=n}}
        return pairs
    }
    override val mainPage:List<MainPageData> get(){
        val p=providers();val rows=mutableListOf<MainPageData>()
        fun add(provider:String,kind:String,label:String){rows+=mainPageOf(JSONObject().put("provider",provider).put("kind",kind).toString() to label)}
        add("anyreel","short","AnyReel");add("moviebox","anime","Anime · MovieBox");add("moviebox","movie-series","Film ve Diziler · MovieBox")
        for(k in listOf("dramabox","bibishort","dramawave"))p[k]?.let{add(k,"short",it)}
        for((k,n) in p)if(k !in setOf("anyreel","dramabox","bibishort","dramawave"))add(k,"short",n)
        return rows
    }
    private suspend fun catalog(provider:String,kind:String,page:Int):JSONObject{
        val key="catalog|$provider|$kind|$page";cached(key)?.let{return it}
        val b=BASE.toHttpUrlOrNull()!!.newBuilder().addPathSegments("home/providers/sections").addQueryParameter("provider",provider).addQueryParameter("lang","tr-TR").addQueryParameter("target_lang","tr-TR")
        if(kind!="short")b.addQueryParameter("type_context",kind)
        if(page>1){val first=catalog(provider,kind,1);for(s in arrayObjects(first.optJSONArray("sections")))text(s,"tab_key")?.let{b.addQueryParameter("tab_pages[$it]",page.toString())}}
        val p=JSONObject(checked(web(b.build().toString(),"application/json",3_000_000)))
        if(!p.optBoolean("ok",false))throw ErrorLoadingException("Narto bu sağlayıcının listesini vermedi.")
        metadataCatalog(p)?.let { safe ->
            saveCache(key,safe)
            if(kind=="short")saveCache("provider-registry",JSONObject().put("providers",safe.getJSONArray("providers")))
        }
        return p
    }
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse{
        val args=runCatching{JSONObject(request.data)}.getOrNull();val provider=args?.optString("provider")?.takeIf{it.isNotBlank()}?:"anyreel";val kind=args?.optString("kind")?:"short"
        if(kind !in setOf("short","anime","movie-series"))throw ErrorLoadingException("Narto içerik türü geçersiz.")
        val p=catalog(provider,kind,page.coerceAtLeast(1));val items=arrayObjects(p.optJSONArray("sections")).flatMap{arrayObjects(it.optJSONArray("items"))}.mapNotNull{card(it,kind)}.distinctBy{it.url}
        return newHomePageResponse(request.name,items,items.isNotEmpty())
    }
    private fun card(o:JSONObject,kind:String):SearchResponse?{
        val title=text(o,"title")?:return null;val url=text(o,"watch_url")?:return null
        return newTvSeriesSearchResponse(title,withHint(url,kind),if(kind=="anime")TvType.Anime else TvType.TvSeries){posterUrl=text(o,"poster_url")?.let{absolute(it)}}
    }
    override suspend fun search(query:String):List<SearchResponse>{
        val u=BASE.toHttpUrlOrNull()!!.newBuilder().addPathSegment("search").addQueryParameter("lang","tr-TR").addQueryParameter("q",query).build()
        val r=web(u.toString());val doc=Jsoup.parse(checked(r));val seen=linkedSetOf<String>()
        return doc.select("a.card-link-overlay[href]").mapNotNull{a->
            val url=absolute(a.attr("href"),r.url);if(!seen.add(url))return@mapNotNull null
            val parent=a.parent();val title=a.attr("aria-label").ifBlank{a.text()}.ifBlank{parent?.selectFirst("img")?.attr("alt").orEmpty()}
            if(title.isBlank())return@mapNotNull null
            newTvSeriesSearchResponse(title,withHint(url,"short"),TvType.TvSeries){posterUrl=parent?.selectFirst("img")?.let{it.attr("data-src").ifBlank{it.attr("src")}}?.takeIf{it.isNotBlank()}?.let{absolute(it,r.url)}}
        }
    }
    private data class Watch(val doc:Document,val code:String,val url:HttpUrl){
        private val values=hashMapOf<String,Any?>()
        fun value(name:String):Any?{
            if(values.containsKey(name))return values[name]
            val m=Regex("\\b(?:const|let|var)\\s+"+Regex.escape(name)+"\\s*=\\s*").find(code)?:return null
            return runCatching{JSONTokener(code.substring(m.range.last+1)).nextValue()}.getOrNull().also{values[name]=it}
        }
        fun string(name:String)=(value(name) as? String)?.takeIf{it.isNotBlank()&&it!="null"}
        fun number(name:String)=(value(name) as? Number)?.toLong()?:0L
        fun episodes()=value("episodeItemsRaw") as? JSONArray
        fun slug():String{val p=url.pathSegments;return if(p.size>=3&&p[0]=="detail"&&p[1]=="watch")p[2]else throw ErrorLoadingException("Narto dizi kimliği bulunamadı.")}
    }
    private fun watch(r:Reply):Watch{
        val doc=Jsoup.parse(checked(r));val code=doc.select("script").map{it.data()}.filter{it.contains("episodeItemsRaw")}.joinToString("\n")
        return Watch(doc,code,r.url)
    }
    private fun episodeUrl(slug:String,n:Int):String=BASE.toHttpUrlOrNull()!!.newBuilder().addPathSegments("detail/watch").addPathSegment(slug).addPathSegment(n.toString()).addQueryParameter("lang","tr-TR").build().toString()
    private fun routeNumber(e:JSONObject)=e.optInt("route_episode_number",0)
    override suspend fun load(url:String):LoadResponse?{
        if(url.trimStart().startsWith('{'))return null
        val input=webUrl(url);val kind=hint(input);val expectedSlug=input.pathSegments.takeIf{it.size>=3&&it[0]=="detail"&&it[1]=="watch"}?.get(2)
        if(expectedSlug==null&&input.encodedPath!="/search/import")throw ErrorLoadingException("Narto içerik bağlantısı tanınmadı.")
        var w=watch(web(input.newBuilder().fragment(null).build().toString()))
        if(expectedSlug!=null&&w.slug()!=expectedSlug)throw ErrorLoadingException("Narto istenen diziden farklı bir sayfa verdi.")
        if(w.episodes()==null){val first=w.doc.selectFirst("a.left-episode-item[href],a.ep-item[href],a.episode-item[href]")?:throw ErrorLoadingException("Narto gerçek bölüm listesi bulunamadı.");w=watch(web(absolute(first.attr("href"),w.url)))}
        val slug=w.slug();if(expectedSlug!=null&&slug!=expectedSlug)throw ErrorLoadingException("Narto dizi kimliği uyuşmadı.")
        val movie=w.number("movieId");if(movie<=0)throw ErrorLoadingException("Narto dizi kayıt kimliği eksik.")
        val source=w.string("movieSourceAppName").orEmpty()
        val episodes=arrayObjects(w.episodes()).filter{routeNumber(it)>0&&it.optLong("id")>0}.sortedBy(::routeNumber).map{e->
            val n=routeNumber(e);val data=JSONObject().put("kind","arcana_narto_episode").put("slug",slug).put("movie",movie).put("id",e.optLong("id")).put("n",n).put("provider",source).toString()
            newEpisode(data){name=text(e,"title")?:"Bölüm $n";season=1;episode=n;posterUrl=text(e,"thumb_url")}
        }
        if(episodes.isEmpty())throw ErrorLoadingException("Narto bölüm kayıtları boş.")
        val title=w.string("movieTitle")?:w.doc.selectFirst(".desktop-title,h1")?.text()?:throw ErrorLoadingException("Narto dizi adı eksik.")
        val poster=w.string("moviePosterUrl")?:w.doc.selectFirst(".desktop-cover,.info-cover")?.attr("src")
        val plot=w.doc.selectFirst(".desktop-intro,.info-intro")?.text()
        val tags=w.doc.select(".desktop-tags .desktop-tag,.info-tags .info-tag").map{it.text().removePrefix("#")}.distinct()
        val result:LoadResponse=if(w.value("singleEpisodeMode")==true&&episodes.size==1)
            newMovieLoadResponse(title,episodeUrl(slug,1),TvType.Movie,episodes.first().data)
        else newTvSeriesLoadResponse(title,episodeUrl(slug,1),if(kind=="anime")TvType.Anime else TvType.TvSeries,episodes)
        result.posterUrl=poster?.let{absolute(it,w.url)};result.plot=plot;result.tags=tags
        Regex("\\((19[0-9]{2}|20[0-9]{2})\\)\\s*$").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let{result.year=it}
        return result
    }
    private suspend fun refreshed(w:Watch,n:Int):JSONObject?{
        val raw=w.string("refreshSourceBaseUrl")?:return null;val b=webUrl(raw).newBuilder()
        if(w.slug()!=webUrl(raw).pathSegments.getOrNull(2))throw ErrorLoadingException("Narto yenileme dizisi uyuşmadı.")
        b.addPathSegment(n.toString()).addPathSegment("refresh-source").setQueryParameter("force","1")
        w.string("refreshSourceContextToken")?.let{b.setQueryParameter("rs_ctx",it)}
        var u=b.build()
        if(w.value("refreshSourceUseEdge")==true||w.value("refreshSourceCandyjarEdgeDirect")==true){val edge=webUrl(w.string("refreshSourceEdgeBase")?:throw ErrorLoadingException("Narto yenileme sunucusu eksik."));u=edge.newBuilder().encodedPath((if(w.value("refreshSourceUseEdge")==true)"/e/rs" else "")+u.encodedPath).encodedQuery(u.encodedQuery).build()}
        val r=web(u.toString(),"application/json",3_000_000);val p=runCatching{JSONObject(r.body)}.getOrNull()
        if(p?.optBoolean("unavailable")==true&&p.optString("unavailable_reason")=="delisted")throw ErrorLoadingException("Bu içerik Narto kaynağında yayından kaldırılmış.")
        checked(r);if(p==null||!p.optBoolean("ok",false))throw ErrorLoadingException("Narto bu bölümün kaynağını hazırlayamadı.")
        return p
    }
    /** Only the publisher's own proxy token has a documented exp. Other CDN timestamps may be signing buckets. */
    private fun proxyNearExpiry(raw:String):Boolean=runCatching {
        val u=publicUrl(raw);val parts=u.pathSegments
        if(!webHost(u.host)||parts.size<3||parts[0]!="e"||parts[1] !in setOf("m","s"))return@runCatching false
        val encoded=parts[2].substringBefore('.')
        val bytes=android.util.Base64.decode(encoded,android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        if(bytes.size>16_384)return@runCatching false
        val exp=JSONObject(bytes.toString(Charsets.UTF_8)).optLong("exp",0)
        exp>0 && exp<=System.currentTimeMillis()/1000+60
    }.getOrDefault(false)
    @Synchronized private fun relay():NartoMediaSession=mediaSession?.takeUnless{it.isClosed}?:NartoMediaSession(::publicUrl).also{mediaSession=it}
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean{
        val payload=runCatching{JSONObject(data)}.getOrNull()?:return false
        if(payload.optString("kind")!="arcana_narto_episode")return false
        val slug=text(payload,"slug")?:return false;val n=payload.optInt("n");if(n<=0)return false
        val w=watch(web(episodeUrl(slug,n)))
        if(w.slug()!=slug||w.number("movieId")!=payload.optLong("movie")||w.number("initialEpisodeNumber")!=n.toLong()||w.number("initialEpisodeDbId")!=payload.optLong("id"))throw ErrorLoadingException("Narto bölüm kimliği istenen kayıtla uyuşmadı.")
        if(w.string("movieSourceAppName").orEmpty()!=payload.optString("provider"))throw ErrorLoadingException("Narto sağlayıcı kimliği değişti.")
        val original=arrayObjects(w.episodes()).firstOrNull{routeNumber(it)==n&&it.optLong("id")==payload.optLong("id")}?:throw ErrorLoadingException("Narto bölüm kaydı bulunamadı.")
        var e=original
        if(text(e,"play_url")==null||text(e,"play_url")?.let{proxyNearExpiry(absolute(it,w.url))}==true){e=refreshed(w,n)?:throw ErrorLoadingException("Narto bu bölümün bağlantısını yenileyemedi.")
            if(e.has("movie_id")&&e.optLong("movie_id")!=payload.optLong("movie"))throw ErrorLoadingException("Narto yenileme dizi kimliği uyuşmadı.")
            if(e.has("episode_id")&&e.optLong("episode_id")!=payload.optLong("id"))throw ErrorLoadingException("Narto yenileme bölüm kimliği uyuşmadı.")
        }
        val url=text(e,"play_url")?:text(e,"direct_play_url")?:throw ErrorLoadingException("Narto bu bölüm için oynatılabilir kaynak vermedi.")
        publicUrl(absolute(url,w.url));val isHls=url.substringBefore('?').contains(".m3u8")||e.optBoolean("direct_play_is_hls",false)
        val referer=if(w.doc.selectFirst("video#player")?.attr("referrerpolicy")=="no-referrer")"" else w.url.toString()
        val streamClient=client.newBuilder().callTimeout(0,TimeUnit.MILLISECONDS).followRedirects(true).followSslRedirects(false)
            .addNetworkInterceptor{chain->publicUrl(chain.request().url.toString());chain.proceed(chain.request())}.build()
        fun resource(full:String,kind:String):String=if(directHttps)https().register(full,referer,streamClient,kind,userAgent) else relay().register(full,referer,streamClient,kind,userAgent)
        val playback=resource(absolute(url,w.url),if(isHls)"hls" else "mp4")
        val subs=arrayObjects(e.optJSONArray("multi_subtitles")).sortedBy{if(it.optString("language_code").startsWith("tr",true))0 else 1}
        val used=linkedSetOf<String>()
        for(sub in subs){
            val remote=text(sub,"subtitle_url")?:continue;val full=absolute(remote,w.url);publicUrl(full)
            if(!used.add(full))continue
            val code=text(sub,"language_code").orEmpty();val label=if(code.startsWith("tr",true))"Türkçe" else text(sub,"label")?:code.ifBlank{"Altyazı"}
            val ext="vtt"
            subtitleCallback(newSubtitleFile(code.ifBlank{label},resource(full,ext)))
        }
        text(e,"subtitle_url")?.let{remote->val full=absolute(remote,w.url);publicUrl(full);if(used.add(full))subtitleCallback(newSubtitleFile("Altyazı",resource(full,"vtt")))}
        callback(newExtractorLink(name,"$name · ${payload.optString("provider")}",playback,if(isHls)ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO){quality=Qualities.Unknown.value;headers=emptyMap();this.referer=""})
        return true
    }
    fun close(){mediaSession?.close();mediaSession=null;httpsTransport?.close();httpsTransport=null}
    companion object{
        private const val BASE="https://narto-drama.com"
        private fun webHost(h:String)=h=="narto-drama.com"||h.endsWith(".narto-drama.com")||h=="nartodrama.com"||h.endsWith(".nartodrama.com")
        private fun privateAddress(a:InetAddress):Boolean{
            if(a.isAnyLocalAddress||a.isLoopbackAddress||a.isLinkLocalAddress||a.isSiteLocalAddress||a.isMulticastAddress)return true
            val b=a.address
            if(b.size==16)return(b[0].toInt()and 0xfe)==0xfc
            val x=b[0].toInt()and 255;val y=b[1].toInt()and 255
            return x==0||x>=224||(x==100&&y in 64..127)||(x==198&&y in 18..19)
        }
    }
}
