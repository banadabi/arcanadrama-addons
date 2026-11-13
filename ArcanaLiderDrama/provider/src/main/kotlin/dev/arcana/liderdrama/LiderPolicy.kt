package dev.arcana.liderdrama

import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Validate semantics without rewriting issued tickets, paths, queries or signatures. */
internal object LiderPolicy {
    private fun decode(value: String): String {
        var text = value
        repeat(4) {
            if('%' !in text)return text
            text = URLDecoder.decode(text.replace("+","%2B"),"UTF-8")
            require(text.none { it.code < 32 || it.code == 127 || it == '\uFFFD' })
        }
        require('%' !in text);return text
    }
    fun normalizedPath(u: URI): String {
        val parts = mutableListOf<String>()
        for(segment in decode(u.rawPath.orEmpty()).replace('\\','/').split('/')) {
            when(val part = segment.substringBefore(';').lowercase(java.util.Locale.ROOT)) {
                "","." -> Unit
                ".." -> if(parts.isNotEmpty())parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return "/"+parts.joinToString("/")
    }
    private fun params(u: URI): List<Pair<String,String>> = u.rawQuery?.split('&')?.map { raw ->
        val p = raw.split('=',limit=2)
        URLDecoder.decode(p[0],"UTF-8") to URLDecoder.decode(p.getOrElse(1){""},"UTF-8")
    }.orEmpty().also { list -> require(list.size <= 64 && list.map { it.first }.toSet().size == list.size) }
    fun request(raw: String,post: Boolean) {
        val u=URI(raw)
        require(!post&&u.scheme=="https"&&u.host=="liderdrama.com"&&u.port in setOf(-1,443)&&u.rawUserInfo==null&&u.rawFragment==null)
        require(u.rawPath==u.path&&u.path.split('/').none{it in setOf(".","..")||';' in it})
        val p=u.path;val query=params(u).toMap()
        when {
            p=="/api/home"->require(query==mapOf("lang" to "tr"))
            p=="/api/dramas"->{
                require(query.keys.containsAll(setOf("page","limit","dil","lang","sort"))&&query.keys.all{it in setOf("page","limit","dil","lang","sort","dubbed","genre","search","episodes")})
                require(query["page"]?.toIntOrNull() in 1..100000&&query["limit"]=="24"&&query["dil"]=="tr"&&query["lang"]=="tr"&&query["sort"] in LiderContract.sorts)
                query["dubbed"]?.let{require(it=="1")};query["genre"]?.let{require(it in LiderContract.genres&&it!="all")}
                query["episodes"]?.let{require(it in LiderContract.counts&&it!="all")};query["search"]?.let{require(it.length in 2..100&&it.none{c->c.code<32||c.code==127})}
            }
            p.matches(Regex("/api/drama/[A-Za-z0-9][A-Za-z0-9-]{0,239}"))->require(query==mapOf("lang" to "tr"))
            p=="/api/video"->{require(query.keys==setOf("slug","ep"));LiderContract.slug(query.getValue("slug"));require(query["ep"]?.toIntOrNull() in 1..10000)}
            p.matches(Regex("/subs/[A-Za-z0-9][A-Za-z0-9-]{0,239}/[1-9][0-9]{0,4}\\.(?:vtt|srt|m3u8)"))->require(query.isEmpty())
            else->throw IllegalArgumentException("Undeclared source request")
        }
    }
    fun readOnlyMedia(u: URI) = validateMedia(u,0)
    private fun validateMedia(u: URI,depth: Int) {
        require(depth<=4)
        val path=normalizedPath(u);val host=u.host.orEmpty().lowercase(java.util.Locale.ROOT).trimEnd('.')
        require(u.scheme=="https"&&host.isNotBlank()&&u.port in setOf(-1,443)&&u.rawUserInfo==null&&u.rawFragment==null)
        require(path.split('/').none{it in setOf("admin","auth","login","logout","unlock","progress","rating","favorites","translate","translation")})
        require(!path.endsWith("/api/altyazi.php"))
        val query=params(u).map{decode(it.first).lowercase(java.util.Locale.ROOT) to it.second}
        require(query.none{it.first.substringBefore('[').substringBefore('.') in setOf("failed","refresh","recheck","delete","translate","translation")})
        val source=host=="liderdrama.com"||host.endsWith(".liderdrama.com")
        if(source) {
            require(path !in setOf("/api/home","/api/dramas","/api/video")&&!path.startsWith("/api/drama/"))
            require(path.startsWith("/subs/")||path.startsWith("/api/dramabox-stream")||!path.startsWith("/api/"))
        }
        val wrapped=query.filter{it.first.substringBefore('[').substringBefore('.') in setOf("url","target")}
        require(wrapped.size<=1)
        wrapped.singleOrNull()?.let{(_,value)->
            val direct=runCatching{URI(value)}.getOrNull()
            val text=if(direct?.scheme=="https")value else {
                runCatching{String(Base64.getDecoder().decode(value),Charsets.UTF_8)}.getOrNull()
                    ?:runCatching{String(Base64.getUrlDecoder().decode(value),Charsets.UTF_8)}.getOrNull()
                    ?:error("Unrecognized media URL wrapper")
            }
            validateMedia(URI(text),depth+1)
        }
    }
    fun publicAddress(a: InetAddress): Boolean {
        if(a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress)return false
        val b = a.address
        if(b.size == 16)return (b[0].toInt() and 0xfe) != 0xfc
        val first = b[0].toInt() and 255;val second = b[1].toInt() and 255
        return !(first == 0 || first >= 224 || first == 100 && second in 64..127 || first == 198 && second in 18..19)
    }
    fun retryDeadline(raw: String?,now: Long): Long {
        val minimum = if(now > Long.MAX_VALUE-300000)Long.MAX_VALUE else now+300000
        val value = raw.orEmpty().trim()
        val deadline = if(value.isNotEmpty() && value.all { it in '0'..'9' }) {
            val digits = value.trimStart('0').ifEmpty { "0" }
            val seconds = if(digits.length > 18)Long.MAX_VALUE else digits.toLong()
            if(seconds > (Long.MAX_VALUE-now)/1000)Long.MAX_VALUE else now+seconds*1000
        } else runCatching { ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() ?: minimum
        return maxOf(minimum,deadline)
    }
    fun denial(j: JSONObject): Int? {
        val objects = listOfNotNull(j,j.optJSONObject("data"),j.optJSONObject("error"))
        val fields = objects.flatMap { obj -> listOf("code","status","status_code","error").mapNotNull { k ->
            obj.opt(k)?.takeUnless { it == JSONObject.NULL }?.toString()?.lowercase(java.util.Locale.ROOT)
        } }
        if(fields.any { it == "429" || "rate_limit" in it || "too_many" in it })return 429
        if(fields.any { it in setOf("401","403","forbidden","unauthorized","denied","captcha","challenge","access_denied") })return 403
        return null
    }
    private fun json(body: String): JSONObject? = body.trimStart('\uFEFF',' ','\n','\r','\t').takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun error(j: JSONObject): Boolean = j.opt("success") == false || j.opt("ok") == false ||
        j.opt("error")?.let { it != JSONObject.NULL && it !in setOf(false,0,"0","false","") } == true ||
        j.optString("status").lowercase(java.util.Locale.ROOT) in setOf("error","failed","denied","forbidden","unauthorized")
    fun bodyDenial(body: String): Int? = json(body)?.let(::denial) ?: if(challenge(body))403 else null
    fun retryHint(body: String): String? = json(body)?.let { j ->
        (j.opt("retry_after") ?: j.optJSONObject("data")?.opt("retry_after"))?.takeUnless { it == JSONObject.NULL }?.toString()
    }
    fun inspectMediaDocument(code: Int,kind: String,mime: String?): Boolean {
        if(code != 200 || kind == "key")return false
        val type = mime.orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        return kind in setOf("hls","vtt","srt") || type in setOf("application/json","text/html","text/plain","application/xhtml+xml") || type.endsWith("+json")
    }
    fun errorDocument(body: String): Boolean {
        val text = body.trimStart('\uFEFF',' ','\n','\r','\t')
        return json(text)?.let { denial(it) != null || error(it) } == true ||
            text.startsWith("<html",ignoreCase=true) || text.startsWith("<!doctype",ignoreCase=true)
    }
    fun challenge(body: String): Boolean {
        val text = body.take(16384).trimStart('\uFEFF',' ','\n','\r','\t')
        if(text.startsWith("WEBVTT") || text.startsWith("#EXTM3U"))return false
        if(Regex("(?is)^<(?:!doctype|html|head|title|div)").containsMatchIn(text)) {
            return "cf-chl-" in text || Regex("(?is)<title>\\s*(?:just a moment\\.{0,3}|access denied|forbidden|attention required!?)\\s*</title>").containsMatchIn(text)
        }
        return Regex("(?is)^(?:access denied|forbidden|too many requests)(?:[.!:]|$)").containsMatchIn(text)
    }
}
