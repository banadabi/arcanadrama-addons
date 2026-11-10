package dev.arcana.minidiziizle

import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Validate semantics without rewriting issued tickets, paths, queries or signatures. */
internal object MiniPolicy {
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
    }.orEmpty().also { list -> require(list.size <= 10 && list.map { it.first }.toSet().size == list.size) }
    fun request(raw: String,post: Boolean) {
        val u = URI(raw)
        require(u.scheme == "https" && u.port in setOf(-1,443) && u.rawUserInfo == null && u.rawFragment == null)
        val source = u.host == "minidiziizle.com"
        if(!source) {
            require(post && u.host in setOf("media.minidiziizle.com","storage-pilot.shortizle.com") && u.rawPath in setOf("/session","/authorize") && u.rawQuery == null)
            return
        }
        require(!post && u.rawPath == u.path && u.path.split('/').none { it in setOf(".","..") || ';' in it })
        val p = u.path;val query = params(u).toMap()
        if(p == "/wp-json/minidizizle/v1/arama") {
            require(query.keys == setOf("q"));val q = query.getValue("q")
            require(q.length in 2..100 && q.none { it.code < 32 || it.code == 127 });return
        }
        if(p.matches(Regex("/wp-json/minidizizle/v1/(?:pool-)?playback/[1-9][0-9]{0,15}"))) {
            require(query.keys == setOf("token","dil","say","r2_capable") || query.keys == setOf("token","dil","say","r2_capable","sid"))
            require(query["token"]!!.length in 1..2048 && query.getValue("token").none { it.code < 32 || it.code == 127 })
            require(query["dil"] in setOf("altyazi","dublaj") && query["say"] == "1" && query["r2_capable"] == "1")
            query["sid"]?.let { require(it.matches(Regex("[a-f0-9]{64}"))) };return
        }
        require(query.isEmpty())
        require(p == "/" || p.matches(Regex("/(?:icerik|bolum)/[a-z0-9][a-z0-9-]{0,279}/?")) ||
            p in MiniContract.lists || p == "/kategori/" || p.matches(Regex("/(?:yeni-eklenenler|en-cok-izlenenler|dublajli-mini-diziler|altyazili-mini-diziler|kategori/[a-z0-9-]+)/page/[1-9][0-9]{0,4}/")))
    }
    fun readOnlyMedia(u: URI) {
        val path = normalizedPath(u)
        require(path.split('/').none { it in setOf("session","authorize","auth","login","logout","admin","wp-admin","wp-json","progress","rating","favorites","unlock") })
        if(u.host.lowercase(java.util.Locale.ROOT) in setOf("minidiziizle.com","media.minidiziizle.com","storage-pilot.shortizle.com"))require(path.split('/').none { it == "api" })
        require(params(u).none { it.first.lowercase(java.util.Locale.ROOT) in setOf("url","target","failed","refresh","recheck","delete") })
        if(u.host.lowercase(java.util.Locale.ROOT) == "minidiziizle.com")require(path.startsWith("/wp-content/uploads/"))
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
