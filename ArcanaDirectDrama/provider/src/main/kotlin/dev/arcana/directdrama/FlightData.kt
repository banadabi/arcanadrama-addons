package dev.arcana.directdrama

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.util.Collections
import java.util.IdentityHashMap

/** Decode published Flight data, never evaluate scripts or load referenced modules. */
internal object FlightData {
    fun objects(html: String): List<JSONObject> {
        require(html.length <= 4_000_000)
        val chunks = Jsoup.parse(html).select("script").mapNotNull { script ->
            val text = script.data().trim().removeSuffix(";")
            if (!text.startsWith("self.__next_f.push(")) return@mapNotNull null
            runCatching { JSONArray(text.removePrefix("self.__next_f.push(").removeSuffix(")")) }
                .getOrNull()?.takeIf { it.length() == 2 && it.optInt(0, -1) == 1 }?.opt(1) as? String
        }
        val bytes = chunks.joinToString("").toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8_000_000)
        val records = mutableListOf<Any>()
        val texts = mutableMapOf<String, String>()
        var pos = 0
        fun find(char: Byte, from: Int) = (from until bytes.size).firstOrNull { bytes[it] == char } ?: -1
        while (pos < bytes.size) {
            if (bytes[pos] == 10.toByte()) { pos++; continue }
            val colon = find(':'.code.toByte(), pos)
            if (colon < 0 || colon - pos > 16) break
            val id = String(bytes, pos, colon - pos, Charsets.US_ASCII)
            if (!id.matches(Regex("[0-9a-fA-F]*"))) break
            val start = colon + 1
            if (start >= bytes.size) break
            val tag = bytes[start].toInt().toChar()
            val raw: String
            if (tag in "TAOobUSsLlGgMmV") {
                val comma = find(','.code.toByte(), start + 1)
                if (comma < 0 || comma - start > 10) break
                val count = String(bytes, start + 1, comma - start - 1, Charsets.US_ASCII).toIntOrNull(16) ?: break
                if (count < 0 || count > bytes.size - comma - 1) break
                raw = if (tag == 'T') String(bytes, comma + 1, count, Charsets.UTF_8) else ""
                if (tag == 'T') texts[id] = raw
                pos = comma + 1 + count
            } else {
                var end = find(10, start)
                if (end < 0) end = bytes.size
                raw = String(bytes, start, end - start, Charsets.UTF_8)
                pos = end + 1
            }
            if (raw.firstOrNull() in listOf('[', '{', '"'))
                runCatching { JSONTokener(raw).nextValue() }.getOrNull()?.let(records::add)
        }
        val found = mutableListOf<JSONObject>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Pair<Any, Int>>()
        records.forEach { pending.add(it to 0) }
        var visits = 0
        while (pending.isNotEmpty()) {
            require(++visits <= 100_000)
            val (value, depth) = pending.removeLast()
            if (depth > 80) continue
            when (value) {
                is JSONObject -> if (seen.add(value)) {
                    // Text references may contain a long publisher-issued URL. Resolve data only.
                    value.keys().asSequence().toList().forEach { key ->
                        val v = value.opt(key)
                        if (v is String && v.startsWith("$") && v.drop(1).matches(Regex("[0-9a-fA-F]+")))
                            texts[v.drop(1)]?.let { value.put(key, it) }
                    }
                    found.add(value)
                    value.keys().forEach { key -> value.opt(key)?.let { pending.add(it to depth + 1) } }
                }
                is JSONArray -> if (seen.add(value))
                    for (i in 0 until value.length()) value.opt(i)?.let { pending.add(it to depth + 1) }
                is String -> if (depth < 2 && value.trimStart().firstOrNull() in listOf('[', '{'))
                    runCatching { JSONTokener(value).nextValue() }.getOrNull()?.let { pending.add(it to depth + 1) }
            }
        }
        return found
    }
}
