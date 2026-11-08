package dev.arcana.dramacix

/** The same source SRT/WebVTT normalization for prepared and streamed captions. */
internal object DramacixCaption {
    fun hasTiming(bytes:ByteArray):Boolean =
        Regex("""(?:[0-9]{2}:)?[0-9]{2}:[0-9]{2}[,.][0-9]{3}\s+-->""")
            .containsMatchIn(bytes.toString(Charsets.UTF_8))
    fun vtt(bytes:ByteArray):ByteArray? {
        if(bytes.isEmpty() || bytes.size>3_000_000)return null
        val raw=bytes.toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\n','\r','\t')
        if(!raw.contains("-->"))return null
        val text=if(raw.startsWith("WEBVTT"))raw else {
            if(!Regex("[0-9]{2}:[0-9]{2}:[0-9]{2},[0-9]{3}\\s*-->").containsMatchIn(raw))return null
            "WEBVTT\n\n"+raw.lineSequence().joinToString("\n") { line ->
                if(line.contains("-->"))line.replace(Regex("(?<=[0-9]),(?=[0-9]{3}(?:\\s|$))"),".")else line
            }
        }
        return text.toByteArray(Charsets.UTF_8).takeIf { it.size<=3_000_000 }
    }
}
