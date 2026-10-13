package dev.macdroid.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dev.macdroid.app.graph
import dev.macdroid.core.transfer.FileNaming
import dev.macdroid.core.transfer.OutgoingFile
import dev.macdroid.core.transfer.TransferRecord
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns picked or shared URIs into queued transfers (spec §4.2). */
object SendFiles {
    const val COPY_LIMIT_BYTES = 100L * 1024 * 1024
    private const val COPIES_DIR = "outgoing"

    /** Returns how many files were queued (unreadable ones are skipped). */
    suspend fun enqueue(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val files = uris.mapNotNull { describe(context, it) }
        if (files.isNotEmpty()) context.graph.transferEngine.enqueue(files)
        files.size
    }

    private fun describe(context: Context, uri: Uri): OutgoingFile? {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        }
        if (size < 0) size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1
        if (size < 0) return null
        val displayName = name ?: uri.lastPathSegment ?: "file"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        // Keep access across process death where the provider allows it (Photo Picker and documents do).
        val persisted = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true }.getOrDefault(false)
        val source = when (ShareSource.plan(persisted, size, COPY_LIMIT_BYTES)) {
            ShareSource.Keep -> uri.toString()
            ShareSource.Copy -> copyToCache(context, uri, displayName) ?: return null
        }
        return OutgoingFile(source, displayName, size, mime)
    }

    private fun copyToCache(context: Context, uri: Uri, name: String): String? = runCatching {
        val dir = File(File(context.cacheDir, COPIES_DIR), UUID.randomUUID().toString()).apply { mkdirs() }
        val file = File(dir, FileNaming.sanitize(name))
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> file.outputStream().use { input.copyTo(it, 1 shl 20) } }
        file.path
    }.getOrNull()

    /** Deletes cache copies and releases read grants of completed sends, once each ([handled] remembers which). */
    fun tidyUp(context: Context, history: List<TransferRecord>, handled: MutableSet<String>) {
        val cleanup = FinishedSends.cleanup(history, File(context.cacheDir, COPIES_DIR).path)
        cleanup.copiesToDelete.filter(handled::add).forEach { File(it).parentFile?.deleteRecursively() }
        cleanup.grantsToRelease.filter(handled::add).forEach { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
}
