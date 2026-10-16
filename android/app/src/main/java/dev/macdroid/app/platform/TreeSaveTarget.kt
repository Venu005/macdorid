package dev.macdroid.app.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.macdroid.core.transfer.FileNaming
import dev.macdroid.core.transfer.PartFile
import dev.macdroid.core.transfer.SaveTarget
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A folder the user chose (SAF tree with a persisted grant). Part files are octet-stream, so the provider doesn't add an extension. */
class TreeSaveTarget(private val context: Context, private val tree: Uri) : SaveTarget {
    override fun freeBytes(): Long? = null

    override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? {
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = runCatching {
            DocumentsContract.createDocument(context.contentResolver, folder, "application/octet-stream", name + FileNaming.PART_SUFFIX)
        }.getOrNull() ?: return null
        return TreePart(context, tree, doc, name, mime)
    }
}

class TreePart(context: Context, private val tree: Uri, private val doc: Uri, private val name: String, private val mime: String) : PartFile {
    companion object {
        const val PREFIX = "tree:"

        fun reopen(context: Context, handle: String): PartFile? {
            val parts = handle.removePrefix(PREFIX).split('\n')
            if (parts.size != 4) return null
            val doc = Uri.parse(parts[1])
            val exists = runCatching {
                context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
            return if (exists) TreePart(context, Uri.parse(parts[0]), doc, parts[2], parts[3]) else null
        }
    }

    private val resolver = context.contentResolver
    override val handle = PREFIX + listOf(tree, doc, name, mime).joinToString("\n")
    override fun length(): Long = resolver.openFileDescriptor(doc, "r")?.use { it.statSize } ?: throw IOException("can't open $doc")
    override fun openRead(): InputStream = resolver.openInputStream(doc) ?: throw IOException("can't read $doc")
    override fun openAppend(): OutputStream = resolver.openOutputStream(doc, "wa") ?: throw IOException("can't write $doc")

    /** Renames the part to a free final name; providers that can't rename get a copy instead. */
    override fun finish(): String {
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val taken = childNames()
        val finalName = FileNaming.unique(name) { it in taken }
        runCatching { DocumentsContract.renameDocument(resolver, doc, finalName) }.getOrNull()?.let { return it.toString() }
        // The name already carries its extension: a real MIME type would let the provider add a second one (Plan 11).
        val copyMime = if (finalName.substringAfterLast('.', "").isNotEmpty()) "application/octet-stream" else mime
        val target = DocumentsContract.createDocument(resolver, folder, copyMime, finalName) ?: throw IOException("can't create $finalName")
        (resolver.openInputStream(doc) ?: throw IOException("can't read $doc")).use { input ->
            (resolver.openOutputStream(target) ?: throw IOException("can't write $target")).use { input.copyTo(it, 1 shl 20) }
        }
        DocumentsContract.deleteDocument(resolver, doc)
        return target.toString()
    }

    private fun childNames(): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        } ?: emptySet()
    }

    override fun delete() {
        runCatching { DocumentsContract.deleteDocument(resolver, doc) }
    }
}
