package dev.macdroid.core.transfer

/** Safe, collision-free names for received files (spec §2.3). Mirrors the Swift version; shared vectors: `file-naming.json`. */
object FileNaming {
    const val MAX_BYTES = 200
    const val PART_SUFFIX = ".macdroid-part"
    private const val MAX_EXTENSION_BYTES = 20
    private val unsafe = setOf(':'.code, '*'.code, '?'.code, '"'.code, '<'.code, '>'.code, '|'.code)

    fun sanitize(raw: String): String {
        val last = raw.split('/', '\\').last()
        val cleaned = StringBuilder()
        last.codePoints().forEach { cp -> if (cp >= 0x20 && cp != 0x7F && cp !in unsafe) cleaned.appendCodePoint(cp) }
        val name = cleaned.toString().trim(' ').trimStart('.').trim(' ')
        if (name.isEmpty()) return "file"
        if (utf8Length(name) <= MAX_BYTES) return name
        val (base, ext) = splitExtension(name)
        return truncateUtf8(base, MAX_BYTES - utf8Length(ext)) + ext
    }

    /** [name], or `name (1)`, `name (2)`, … (before the extension), whichever [isTaken] rejects first. */
    fun unique(name: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(name)) return name
        val (base, ext) = splitExtension(name)
        var counter = 1
        while (true) {
            val suffix = " ($counter)"
            val candidate = truncateUtf8(base, MAX_BYTES - utf8Length(suffix) - utf8Length(ext)) + suffix + ext
            if (!isTaken(candidate)) return candidate
            counter++
        }
    }

    /** Splits at the last dot. A leading dot or an extension over 20 bytes counts as no extension. */
    internal fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name to ""
        val ext = name.substring(dot)
        if (utf8Length(ext) > MAX_EXTENSION_BYTES) return name to ""
        return name.substring(0, dot) to ext
    }

    private fun utf8Length(text: String) = text.toByteArray(Charsets.UTF_8).size

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        val out = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val length = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + length > maxBytes) break
            out.appendCodePoint(cp)
            bytes += length
            index += Character.charCount(cp)
        }
        return out.toString()
    }
}
