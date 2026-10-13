package dev.macdroid.app.platform

import android.content.ContentResolver
import android.net.Uri
import dev.macdroid.core.transfer.SourceOpener
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Opens files to send: content URIs from pickers and the share sheet, or paths of cache copies. */
class ContentSourceOpener(private val resolver: ContentResolver) : SourceOpener {
    override fun open(source: String): InputStream =
        if (source.startsWith("/")) File(source).inputStream()
        else resolver.openInputStream(Uri.parse(source)) ?: throw IOException("can't open $source")
}
