package dev.arcana.shortizle

import android.content.Context

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Source-authorized public-page/API integration. No account login, crawler, worker or persistent ticket store. */
class ArcanaShortizleProvider(private val context: Context) : MainAPI() {
    override var name = "(Arcana Drama) Shortİzle"
    override var mainUrl = BASE
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val mainPage = mainPageOf(
        "latest" to "Yeni Eklenenler",
        "front" to "Shortizle Ana Sayfa",
        "platform:dramabox" to "DramaBox",
        "platform:shortmax" to "ShortMax",
        "platform:netshort" to "NetShort",
        "platform:dramawave" to "DramaWave",
        "platform:reelshort" to "ReelShort",
        "platform:flextv" to "FlexTV",
        "platform:freereels" to "FreeReels",
    )

    private val directHttps = context.packageName != "com.kaan.play"
    @Volatile private var httpsTransport:ArcanaHttpsTransport?=null
    @Synchronized private fun https():ArcanaHttpsTransport=httpsTransport?.takeUnless{it.isClosed}?:ArcanaHttpsTransport(::checkedUrl).also{httpsTransport=it}
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? = if(directHttps)https() else null

    private val jar = SessionJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(jar).connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .dns { host ->
            val addresses = Dns.SYSTEM.lookup(host).filterNot { privateAddress(it) }
            if (addresses.isEmpty()) throw IOException("Shortizle public address unavailable")
            addresses
        }.build()
    private val homeMutex = Mutex()
    private var cachedHome: Pair<Long, JSONObject>? = null
    @Volatile private var transport: ArcanaMediaSession? = null

    private data class Reply(val code: Int, val body: String)
    private data class Media(val url: String, val type: String, val subtitle: String?, val quality: Int, val cookies: List<Cookie>, val credentialed: Boolean)

    private class SessionJar : CookieJar {
        private val entries = linkedMapOf<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(entries) {
            val now = System.currentTimeMillis()
            entries.entries.removeAll { it.value.expiresAt <= now }
            cookies.forEach { c ->
                val key = "${c.domain}|${c.path}|${c.name}"
                if (c.expiresAt > now) entries[key] = c else entries.remove(key)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(entries) {
            entries.values.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }.toList()
        }

    }

    private fun checkedUrl(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw ErrorLoadingException("Shortizle adresi geçersiz.")
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443 || !allowedHost(url.host))
            throw ErrorLoadingException("Shortizle kaynak adresi tanınmadı.")
        return url
    }

    private suspend fun http(url: String, referer: String = BASE, body: JSONObject? = null,
                             extra: Map<String, String> = emptyMap()): Reply = withContext(Dispatchers.IO) {
        val target = checkedUrl(url)
        val request = Request.Builder().url(target).header("User-Agent", UA).header("Accept-Encoding", "identity")
            .header("Referer", referer).header("Origin", BASE).header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
        extra.forEach { (key, value) -> request.header(key, value) }
        if (body != null) request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = client.newCall(request.build())
        val response: Response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() }
                    else response.close()
                }
            })
        }
        response.use { r ->
            val stream = r.body.byteStream()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = stream.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_PAGE) throw ErrorLoadingException("Shortizle yanıtı çok büyük.")
                out.write(buffer, 0, n)
            }
            Reply(r.code, out.toString("UTF-8"))
        }
    }

    private fun successful(reply: Reply): String {
        if (reply.code !in 200..299) throw ErrorLoadingException("Shortizle isteği tamamlanamadı (HTTP ${reply.code}).")
        return reply.body
    }
    private fun props(html: String): JSONObject {
        val doc = Jsoup.parse(html)
        val script = doc.selectFirst("script[data-page=app][type=application/json]")
        val raw = script?.data()?.takeIf { it.isNotBlank() } ?: doc.selectFirst("[data-page]")?.attr("data-page")
            ?: throw ErrorLoadingException("Shortizle sayfa verisi bulunamadı.")
        return JSONObject(raw).getJSONObject("props")
    }
    private suspend fun pageProps(url: String): JSONObject = props(successful(http(url)))
    private suspend fun home(): JSONObject = homeMutex.withLock {
        cachedHome?.takeIf { System.currentTimeMillis() - it.first < 120_000 }?.second?.let { return@withLock it }
        pageProps(BASE + "/").also { cachedHome = System.currentTimeMillis() to it }
    }
    private fun text(o: JSONObject, key: String): String? = (o.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    private fun absolute(value: String?): String? = value?.let { BASE.toHttpUrlOrNull()?.resolve(it)?.toString() }
    private fun poster(o: JSONObject): String? {
        val direct = text(o, "poster") ?: text(o, "cover")
        if (direct != null) return absolute(direct)
        val css = text(o, "poster_gradient").orEmpty()
        val raw = Regex("url\\(['\"]?([^)'\"]+)['\"]?\\)").find(css)?.groupValues?.get(1)
        return absolute(raw)
    }
    private fun route(kind: String, slug: String, season: Int? = null, episode: Int? = null): String {
        if (slug.isBlank() || slug.any { it == '/' || it == '\\' || it == '\u0000' } || slug == "." || slug == "..")
            throw ErrorLoadingException("Shortizle dizi kimliği geçersiz.")
        val builder = BASE.toHttpUrlOrNull()!!.newBuilder().addPathSegment(kind).addPathSegment(slug)
        if (season != null) builder.addPathSegment(season.toString())
        if (episode != null) builder.addPathSegment(episode.toString())
        return builder.build().toString()
    }
    private fun card(item: JSONObject): SearchResponse? {
        if (text(item, "type")?.let { it != "series" } == true) return null
        val slug = text(item, "slug") ?: return null
        val title = text(item, "name") ?: text(item, "title") ?: return null
        return newTvSeriesSearchResponse(title, route("icerik", slug), TvType.TvSeries) {
            posterUrl = poster(item)
            item.optInt("episode_count").takeIf { it > 0 }?.let { episodes = it }
        }
    }
    private fun cards(array: JSONArray?): List<SearchResponse> = (0 until (array?.length() ?: 0)).mapNotNull { i ->
        array?.optJSONObject(i)?.let(::card)
    }
    private fun withPage(url: String, page: Int): String = url.toHttpUrlOrNull()!!.newBuilder().addQueryParameter("page", page.coerceAtLeast(1).toString()).build().toString()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "front") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val data = home()
            val newest = data.optJSONArray("newest")
            val dub = JSONArray(); val sub = JSONArray()
            for (i in 0 until (newest?.length() ?: 0)) {
                val item = newest?.optJSONObject(i) ?: continue
                if (item.optBoolean("has_dublaj")) dub.put(item)
                if (item.optBoolean("has_altyazi")) sub.put(item)
            }
            return newHomePageResponse(listOf(
                HomePageList("Popüler Diziler", cards(data.optJSONArray("desktopTrending"))),
                HomePageList("En Çok İzlenen 10", cards(data.optJSONArray("top10"))),
                HomePageList("Yeni Türkçe Dublajlılar", cards(dub)),
                HomePageList("Yeni Türkçe Altyazılılar", cards(sub)),
            ).filter { it.list.isNotEmpty() }, false)
        }
        val url = if (request.data == "latest") BASE + "/yeni-cikanlar"
            else if (request.data.startsWith("platform:")) route("platform", request.data.substringAfter(':'))
            else throw ErrorLoadingException("Shortizle kategori isteği tanınmadı.")
        val data = pageProps(withPage(url, page))
        val titles = data.optJSONObject("titles") ?: return newHomePageResponse(request.name, emptyList(), false)
        return newHomePageResponse(request.name, cards(titles.optJSONArray("data")), page < titles.optInt("last_page", page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = BASE.toHttpUrlOrNull()!!.newBuilder().addPathSegment("search").addQueryParameter("q", query.trim()).build().toString()
        return cards(pageProps(url).optJSONObject("titles")?.optJSONArray("data"))
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (url.trimStart().startsWith("{")) return null // host direct route tries opaque episode data first
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.host != "shortizle.com" || parsed.pathSegments.size != 2 || parsed.pathSegments[0] != "icerik") return null
        val slug = parsed.pathSegments[1]
        val title = pageProps(route("icerik", slug)).optJSONObject("title") ?: return null
        if (text(title, "type") != "series" || text(title,"slug") != slug) return null
        val name = text(title, "name") ?: return null
        val picture = poster(title)
        val ordered = sortedMapOf<Pair<Int, Int>, Episode>(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        val seasons = title.optJSONArray("seasons") ?: JSONArray()
        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val sn = season.optInt("number"); if (sn <= 0) continue
            val entries = season.optJSONArray("episodes") ?: continue
            for (j in 0 until entries.length()) {
                val ep = entries.optJSONObject(j) ?: continue
                val en = ep.optInt("number"); if (en <= 0) continue
                val payload = JSONObject().put("slug", slug).put("season", sn).put("episode", en).put("id",ep.optLong("id"))
                ordered[sn to en] = newEpisode(payload.toString()) {
                    this.name = text(ep, "name") ?: "$en. Bölüm"
                    this.season = sn; this.episode = en; this.posterUrl = picture
                }
            }
        }
        val tags = mutableListOf<String>()
        val genres = title.optJSONArray("genres")
        for (i in 0 until (genres?.length() ?: 0)) {
            val g = genres?.opt(i)
            (if (g is JSONObject) text(g,"name") else g as? String)?.let { tags.add(it) }
        }
        val platform = title.opt("platform")
        (if (platform is JSONObject) text(platform,"name") ?: text(platform,"label") else platform as? String)?.let { tags.add(it) }
        return newTvSeriesLoadResponse(name, route("icerik", slug), TvType.TvSeries, ordered.values.toList()) {
            posterUrl = picture; plot = text(title, "description"); this.tags = tags.distinct()
            title.optInt("year").takeIf { it > 0 }?.let { year = it }
        }
    }

    @Synchronized private fun playbackUrl(media: Media, watch: String): String {
        if(media.credentialed && media.cookies.isEmpty())throw ErrorLoadingException("Shortizle medya oturumu alınamadı.")
        val scopedJar=SessionJar().apply { saveFromResponse(checkedUrl(media.url),media.cookies) }
        val mediaClient=client.newBuilder().cookieJar(scopedJar).callTimeout(0,TimeUnit.MILLISECONDS)
            .followRedirects(true).followSslRedirects(false)
            .addNetworkInterceptor { network ->
                checkedUrl(network.request().url.toString())
                network.proceed(network.request())
            }.build()
        if(directHttps)return https().register(media.url,watch,mediaClient,media.type,UA,BASE)
        val relay=transport?.takeUnless { it.isClosed } ?: ArcanaMediaSession(::checkedUrl).also { transport=it }
        return relay.register(media.url,watch,mediaClient,media.type)
    }
    fun close() { transport?.close();transport=null;httpsTransport?.close();httpsTransport=null }

    /** Plugin subtitles are URL-origin entries in Cloudstream; serve validated bytes over the same private HTTP transport. */
    private suspend fun localSubtitle(url:String,watch:String):String? = withContext(Dispatchers.IO) {
        val body=successful(http(url,watch)).trimStart('\uFEFF',' ','\n','\r','\t')
        if(!body.contains("-->") || body.startsWith("<") || body.startsWith("#EXTM3U"))return@withContext null
        val format=when {
            body.startsWith("WEBVTT")->"vtt"
            Regex("[0-9]{2}:[0-9]{2}:[0-9]{2},[0-9]{3}\\s*-->").containsMatchIn(body)->"srt"
            else->return@withContext null
        }
        if(directHttps)return@withContext https().register(url,watch,client,format,UA,BASE,body.toByteArray(Charsets.UTF_8))
        synchronized(this@ArcanaShortizleProvider) {
            val relay=transport?.takeUnless { it.isClosed } ?: ArcanaMediaSession(::checkedUrl).also { transport=it }
            relay.registerText(body.toByteArray(Charsets.UTF_8),format,url,watch,client)
        }
    }

    private suspend fun resolve(source: JSONObject, watch: String): Media? {
        val resolve = text(source, "resolve_url")?.let { absolute(it) }
        val direct = text(source, "url")?.let { absolute(it) }
        var data = if (resolve != null) JSONObject(successful(http(resolve, watch, extra=mapOf("X-Storage-Pilot" to "1","X-Protected-Storage" to "1"))))
            else if (direct != null) JSONObject().put("url",direct).put("type",source.optString("type","hls")) else return null
        if (data.optString("type") == "protected-storage") {
            val base = data.optString("base_url")
            if (base !in STORAGE) throw ErrorLoadingException("Shortizle depolama geçidi tanınmadı.")
            val sid = JSONObject(successful(http(base+"/session",watch,JSONObject()))).getString("sid")
            if (!sid.matches(Regex("[a-f0-9]{64}")) || resolve == null) throw ErrorLoadingException("Shortizle oturumu geçersiz.")
            val ticket = JSONObject(successful(http(resolve,watch,extra=mapOf("X-Protected-Storage" to "1","X-Storage-Session" to sid))))
            if (ticket.optString("base_url") != base || ticket.optString("type") != "protected-storage") throw ErrorLoadingException("Shortizle yetkilendirme verisi geçersiz.")
            data = JSONObject(successful(http(base+"/authorize",watch,JSONObject().put("ticket",ticket.getString("ticket")))))
            data.put("type","hls").put("credentialed",true)
            val expires = data.optLong("expires")
            if (expires > 0 && expires*1000 <= System.currentTimeMillis()+60_000) throw ErrorLoadingException("Shortizle bağlantısının ömrü oynatma için yetersiz.")
        } else if (data.optJSONObject("gateway_bootstrap") != null) {
            val boot = data.getJSONObject("gateway_bootstrap")
            if (boot.optString("url") != GATEWAY+"/authorize") throw ErrorLoadingException("Shortizle medya geçidi tanınmadı.")
            val token = boot.optString("token")
            if (token.isBlank() || token.length > 20_000) throw ErrorLoadingException("Shortizle medya izni geçersiz.")
            data = JSONObject(successful(http(GATEWAY+"/authorize",watch,JSONObject().put("token",token))))
            if (!data.optBoolean("credentialed")) throw ErrorLoadingException("Shortizle medya oturumu kurulamadı.")
        }
        val media = text(data,"url")?.let { absolute(it) } ?: return null
        checkedUrl(media)
        val type = data.optString("type",source.optString("type","hls"))
        if (type !in listOf("hls","native","mp4")) return null
        val sub = text(data,"subtitle_url")?.let { absolute(it) } ?: text(source,"subtitle_url")?.let { absolute(it) }
        val q = (data.opt("quality") as? Number)?.toInt() ?: (source.opt("quality") as? Number)?.toInt() ?: Qualities.Unknown.value
        return Media(media,type,sub,q,jar.loadForRequest(checkedUrl(media)),data.optBoolean("credentialed",false))
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean,
                                   subtitleCallback: (SubtitleFile)->Unit, callback: (ExtractorLink)->Unit): Boolean {
        val payload = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val slug = text(payload,"slug") ?: return false
        val sn = payload.optInt("season");val en = payload.optInt("episode")
        if (sn<=0 || en<=0) return false
        val watch = route("watch",slug,sn,en)
        val p = pageProps(watch)
        val actualTitle=p.optJSONObject("title")
        val actualEpisode=p.optJSONObject("currentEpisode")
        val expectedId=payload.optLong("id")
        if(actualTitle==null || text(actualTitle,"slug")!=slug || actualEpisode==null || actualEpisode.optInt("season_number")!=sn || actualEpisode.optInt("number")!=en || (expectedId>0 && actualEpisode.optLong("id")!=expectedId))
            throw ErrorLoadingException("Shortizle bölüm kimliği istenen bölümle uyuşmadı.")
        if (!p.optBoolean("access_granted",false) || p.optJSONObject("paywall") != null) throw ErrorLoadingException("Shortizle bu bölüm için erişim izni istiyor.")
        val groups = p.optJSONObject("externalSources") ?: return false
        val subtitleUrls = linkedSetOf<String>()
        val episodeSubs = p.optJSONObject("currentEpisode")?.optJSONArray("subtitles")
        for(i in 0 until (episodeSubs?.length() ?: 0)) {
            val sub=episodeSubs?.optJSONObject(i) ?: continue
            val url=text(sub,"url")?.let { absolute(it) } ?: continue
            checkedUrl(url)
            val locale=text(sub,"locale") ?: text(sub,"lang") ?: "und"
            val local=try { localSubtitle(url,watch) } catch(e:CancellationException){throw e} catch(_:Exception){null} ?: continue
            subtitleUrls.add(url)
            subtitleCallback(newSubtitleFile(locale,local))
        }
        var emitted=0
        for(kind in listOf("dublaj","altyazi")) {
            val sources=groups.optJSONArray(kind) ?: continue
            for(i in 0 until sources.length()) {
                val source=sources.optJSONObject(i) ?: continue
                if(source.optString("type") !in listOf("hls","mp4","native")) continue
                val result=try { resolve(source,watch) } catch(e:CancellationException){throw e} catch(_:Exception){null} ?: continue
                result.subtitle?.let { url ->
                    checkedUrl(url)
                    if(subtitleUrls.add(url)) {
                        val local=try { localSubtitle(url,watch) } catch(e:CancellationException){throw e} catch(_:Exception){null}
                        if(local!=null)subtitleCallback(newSubtitleFile("tr-TR",local))
                    }
                }
                val mode=if(kind=="dublaj")"Türkçe Dublaj" else "Türkçe Altyazı"
                val playback=playbackUrl(result,watch)
                callback(newExtractorLink(name,"Shortizle $mode",playback,
                    if(result.type=="hls")ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    quality=result.quality;referer="";headers=emptyMap()
                })
                emitted++
                break // one currently authorized source per edition; no duplicate/expired alternatives
            }
        }
        if(emitted==0)throw ErrorLoadingException("Shortizle bu bölüm için oynatılabilir kaynak vermedi.")
        return true
    }

    companion object {
        private const val BASE="https://shortizle.com"
        private const val GATEWAY="https://stream.shortizle.com"
        private const val UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val MAX_PAGE=3_000_000
        private val STORAGE=setOf("https://storage-pilot.shortizle.com","https://prd-media-rep.shortizle.com")
        private fun allowedHost(host:String)=host in setOf("shortizle.com","stream.shortizle.com","storage-pilot.shortizle.com","prd-media-rep.shortizle.com") || host=="mybigdramapool.com" || host.endsWith(".mybigdramapool.com") || host.endsWith(".b-cdn.net")
        private fun privateAddress(address:InetAddress):Boolean {
            if(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress)return true
            val bytes=address.address
            return bytes.size==16 && (bytes[0].toInt() and 0xfe)==0xfc
        }
    }
}
