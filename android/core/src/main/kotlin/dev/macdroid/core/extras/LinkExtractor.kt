package dev.macdroid.core.extras

/** Which links travel between the devices (spec §2). Mirrors Swift `LinkRules` exactly. */
object LinkExtractor {
    const val MAX_BYTES = 8_192
    private val PATTERN = Regex("""(?i)\bhttps?://[^\s<>"']+""")
    private const val TRAILING = ".,;:!?)]}'\""
    private val OPENERS = mapOf(')' to '(', ']' to '[', '}' to '{')

    fun firstLink(text: String?): String? {
        val found = PATTERN.find(text ?: return null)?.value ?: return null
        var link = found
        while (link.isNotEmpty() && link.last() in TRAILING) {
            // A closing bracket the link itself opened (Wikipedia's "Mercury_(planet)") belongs to it.
            val opener = OPENERS[link.last()]
            if (opener != null && link.count { it == opener } >= link.count { it == link.last() }) break
            link = link.dropLast(1)
        }
        return link.takeIf(::isAllowed)
    }

    fun isAllowed(url: String): Boolean {
        if (url.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
        // Not java.net.URI: it rejects `|`, `{`, `^` and IDN hosts that the Mac accepts, so links were dropped (Plan 11).
        val colon = url.indexOf(':')
        if (colon < 0) return false
        val scheme = url.substring(0, colon).lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (!url.startsWith("//", colon + 1)) return false
        val rest = url.substring(colon + 3)
        val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it })
        val host = authority.substringAfterLast('@').substringBefore(':')
        return host.isNotEmpty() && authority.none { it.isWhitespace() || it.isISOControl() }
    }
}
