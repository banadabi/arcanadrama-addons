package dev.arcana.liderdrama

/** Fixed diagnostic enums only: never log the requested URL, response body, cookie or ticket. */
internal object LiderFailureContext {
    enum class Phase { HOME, CATALOGUE, DETAIL, VIDEO_API, SUBTITLE, OTHER }
    enum class Kind { BROWSER_CHALLENGE, JSON, HTML, TEXT, EMPTY, UNREADABLE }
    fun phase(path:String):Phase=when {
        path=="/api/home"->Phase.HOME
        path=="/api/dramas"->Phase.CATALOGUE
        path.startsWith("/api/drama/")->Phase.DETAIL
        path=="/api/video"->Phase.VIDEO_API
        path.startsWith("/subs/")->Phase.SUBTITLE
        else->Phase.OTHER
    }
    fun kind(mime:String?,mitigated:Boolean,sample:String?):Kind {
        if(mitigated)return Kind.BROWSER_CHALLENGE
        if(sample==null)return Kind.UNREADABLE
        val text=sample.take(4096).trimStart('\uFEFF',' ','\n','\r','\t')
        if(text.contains("cf-chl-",true)||text.contains("/cdn-cgi/challenge-platform/",true))return Kind.BROWSER_CHALLENGE
        val type=mime.orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        return when {
            text.isEmpty()->Kind.EMPTY
            text.startsWith("{")||type=="application/json"||type.endsWith("+json")->Kind.JSON
            text.startsWith("<html",true)||text.startsWith("<!doctype",true)||type=="text/html"->Kind.HTML
            else->Kind.TEXT
        }
    }
}
