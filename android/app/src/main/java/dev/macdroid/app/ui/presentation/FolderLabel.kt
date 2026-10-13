package dev.macdroid.app.ui.presentation

import java.net.URLDecoder

/** A readable name for the save folder: the default, or the path inside a chosen SAF tree. */
object FolderLabel {
    const val DEFAULT = "Download/MacDroid"

    fun of(treeUri: String?): String {
        if (treeUri == null) return DEFAULT
        val encoded = treeUri.substringAfter("/tree/", missingDelimiterValue = treeUri.substringAfterLast('/'))
        val decoded = URLDecoder.decode(encoded, "UTF-8") // the Charset overload needs API 33
        val path = if (':' in decoded) decoded.substringAfter(':') else decoded
        return path.ifEmpty { "Internal storage" }
    }
}
