package dev.macdroid.core.extras

import java.net.URI

/** Which links travel between the devices (spec §2). Mirrors Swift `LinkRules` exactly. */
object LinkExtractor {
    const val MAX_BYTES = 8_192
    private val PATTERN = Regex("""(?i)\bhttps?://[^\s<>"']+""")
    private const val TRAILING = ".,;:!?)]}'\""

    fun firstLink(text: String?): String? {
        val found = PATTERN.find(text ?: return null)?.value ?: return null
        val link = found.trimEnd { it in TRAILING }
        return link.takeIf(::isAllowed)
    }

    fun isAllowed(url: String): Boolean {
        if (url.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
    }
}
