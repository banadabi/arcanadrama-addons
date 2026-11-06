package dev.arcana.narto

/** Use the canonical ISO code understood by both Cloudstream and Play. */
internal fun nartoSubtitleLanguage(languageCode: String?, label: String?): String {
    val code = languageCode.orEmpty().trim()
    return when {
        code.equals("tr", true) || code.startsWith("tr-", true) || code.startsWith("tr_", true) -> "tr"
        code.isNotBlank() -> code
        !label.isNullOrBlank() -> label
        else -> "Altyazı"
    }
}
