package dev.arcana.liderdrama

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Only the two envelopes actually returned by the normal video API. No request or ticket minting. */
internal object LiderIssuedResource {
    data class Resource(val url: String, val referer: String, val kind: String) {
        override fun toString() = "LiderIssuedResource([redacted])"
    }
    private val mediaKeys = setOf("url", "ref", "exp", "sig", "t", "ext")
    private val captionKeys = setOf("url", "ref", "exp", "sig")
    private val observedRefererHosts = setOf("m.mydramawave.com", "www.mydramawave.com")

    private fun field(raw: String): String = URLDecoder.decode(raw, "UTF-8").also {
        require(it.all { c -> c.code in 32..126 })
    }

    private fun decoded(raw: String): String {
        val bytes = Base64.getDecoder().decode(raw)
        require(Base64.getEncoder().encodeToString(bytes) == raw)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            .also { require(it.isNotEmpty() && it.all { c -> c.code in 32..126 }) }
    }

    private fun absolute(raw: String, max: Int): URI {
        require(raw.length <= max && raw.none { it.code < 32 || it.code == 127 || it == '\\' })
        val u = URI(raw)
        require(u.scheme == "https" && !u.host.isNullOrBlank() && u.port in setOf(-1, 443))
        require(u.rawUserInfo == null && u.rawFragment == null && u.normalize().rawPath == u.rawPath)
        return u
    }

    private fun directTarget(u: URI) {
        require("live.php" !in LiderPolicy.normalizedPath(u).split('/'))
        for (part in u.rawQuery?.split('&').orEmpty()) {
            var key = part.substringBefore('=')
            repeat(4) { if ('%' in key) key = field(key.replace("+", "%2B")) }
            require('%' !in key && key.length <= 128)
            val root = key.lowercase(java.util.Locale.ROOT).substringBefore('[').substringBefore('.')
            require(root !in setOf("url", "target"))
        }
    }

    fun relay(raw: String, captionFormat: String? = null, nowSeconds: Long = System.currentTimeMillis() / 1000): Resource? {
        val u = URI(raw)
        if (u.host != "liderdrama.com" || u.rawPath != "/live.php") return null
        absolute(raw, 16000)
        val fields = u.rawQuery?.split('&')?.map {
            val p = it.split('=', limit = 2)
            require(p.size == 2)
            field(p[0]) to field(p[1])
        } ?: error("Missing issued envelope")
        require(fields.map { it.first }.toSet().size == fields.size)
        val q = fields.toMap()
        require(q.keys == if (captionFormat == null) mediaKeys else captionKeys)
        require(q.getValue("sig").matches(Regex("[0-9a-fA-F]{64}")))
        val expiry = q.getValue("exp")
        require(expiry.matches(Regex("[1-9][0-9]{0,18}")) && expiry.toLong() > nowSeconds)
        val target = decoded(q.getValue("url"))
        val destination = absolute(target, 16000)
        directTarget(destination) // Reject known nested wrappers, including encoded aliases.
        LiderPolicy.readOnlyMedia(destination)
        val referer = decoded(q.getValue("ref"))
        val reference = absolute(referer, 4096)
        require(reference.host.lowercase(java.util.Locale.ROOT) in observedRefererHosts)
        require(reference.rawPath in setOf("", "/") && reference.rawQuery == null)
        val kind = if (captionFormat == null) {
            when (q.getValue("t") to q.getValue("ext")) {
                "m3u8" to ".m3u8" -> "hls"
                "mp4" to ".mp4" -> "mp4"
                else -> error("Unknown issued media descriptor")
            }
        } else captionFormat.also { require(it in setOf("vtt", "srt")) }
        val suffix = if (kind == "hls") ".m3u8" else "." + kind
        require(destination.rawPath.lowercase(java.util.Locale.ROOT).endsWith(suffix))
        return Resource(target, referer, kind)
    }
}
