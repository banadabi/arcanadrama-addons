package dev.arcana.directdrama

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Validation only: never rewrites an issued path, query, signature or ticket. */
internal object DirectUrlPolicy {
    private fun decoded(value: String): String {
        var result=value
        repeat(4) {
            if ('%' !in result) return result
            val next=URLDecoder.decode(result.replace("+","%2B"),"UTF-8")
            require(next.none { it.code<0x20 || it.code==0x7f || it=='\uFFFD' })
            if(next==result)return result
            result=next
        }
        require('%' !in result)
        return result
    }
    private fun path(uri: URI): String {
        val value=decoded(uri.rawPath.orEmpty()).replace('\\','/').replace(Regex("/+"),"/")
        val segments=mutableListOf<String>()
        for(raw in value.split('/')) {
            when(val part=raw.substringBefore(';').lowercase()) {
                "", "." -> Unit
                ".." -> if(segments.isNotEmpty())segments.removeAt(segments.lastIndex)
                else -> segments.add(part)
            }
        }
        return "/"+segments.joinToString("/")
    }
    fun readOnly(uri: URI): Boolean = runCatching { allowed(uri,0) }.getOrDefault(false)
    private fun allowed(uri: URI,depth: Int): Boolean {
        require(depth<=4 && uri.scheme?.lowercase() in setOf("http","https") && !uri.host.isNullOrBlank() && uri.rawUserInfo==null)
        val host=uri.host.lowercase().trimEnd('.')
        val source=host=="directdrama.com" || host.endsWith(".directdrama.com")
        val canonicalPath=path(uri)
        if(canonicalPath.endsWith("/api/altyazi.php"))return false
        val params=uri.rawQuery.orEmpty().split('&',';').filter { it.isNotEmpty() }.map { pair ->
            val p=pair.split('=',limit=2)
            val name=decoded(p[0]).lowercase()
            require(name.length<=160 && name.all { it in 'a'..'z' || it in '0'..'9' || it in "_-.[ ]" && it!=' ' })
            name to p.getOrElse(1){""}
        }
        if(source && canonicalPath.startsWith("/api/stream/") &&
            params.any { it.first.substringBefore('[').substringBefore('.') in setOf("failed","refresh","recheck") })return false
        val wrapped=params.filter { it.first=="url" }
        if(wrapped.isEmpty())return true
        if(wrapped.size!=1)return false
        // URL query values use form decoding; only names/paths preserve literal plus signs.
        val value=URLDecoder.decode(wrapped.single().second,"UTF-8")
        val direct=runCatching { URI(value) }.getOrNull()
        val target=if(direct?.scheme?.lowercase() in setOf("http","https"))value else {
            runCatching { String(Base64.getDecoder().decode(value),Charsets.UTF_8) }.getOrNull()
                ?: runCatching { String(Base64.getUrlDecoder().decode(value),Charsets.UTF_8) }.getOrNull() ?: return false
        }
        return allowed(URI(target),depth+1)
    }
}
