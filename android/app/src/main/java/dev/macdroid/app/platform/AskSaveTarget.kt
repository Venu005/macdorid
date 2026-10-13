package dev.macdroid.app.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.macdroid.core.transfer.PartFile
import dev.macdroid.core.transfer.SaveTarget
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** "Ask where to save each time": the user picks a file with CreateDocument, and bytes are written straight into it. */
class AskSaveTarget(private val context: Context, private val prompter: SavePrompter) : SaveTarget {
    override fun freeBytes(): Long? = null

    override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? =
        prompter.ask(transferId, name, mime)?.let { DocumentPart(context, it) }
}

/** The user's chosen document is the part file; it already has its final name, so finishing changes nothing. */
class DocumentPart(context: Context, private val uri: Uri) : PartFile {
    companion object {
        const val PREFIX = "doc:"

        fun reopen(context: Context, handle: String): PartFile? {
            val uri = Uri.parse(handle.removePrefix(PREFIX))
            val writable = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            return if (writable) DocumentPart(context, uri) else null
        }
    }

    private val resolver = context.contentResolver
    override val handle = PREFIX + uri
    override fun length(): Long = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: throw IOException("can't open $uri")
    override fun openRead(): InputStream = resolver.openInputStream(uri) ?: throw IOException("can't read $uri")
    override fun openAppend(): OutputStream = resolver.openOutputStream(uri, "wa") ?: throw IOException("can't write $uri")
    override fun finish(): String = uri.toString()

    override fun delete() {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }
}
