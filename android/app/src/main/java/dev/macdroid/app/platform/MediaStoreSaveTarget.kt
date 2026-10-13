package dev.macdroid.app.platform

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import dev.macdroid.core.transfer.PartFile
import dev.macdroid.core.transfer.SaveTarget
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The default: Download/MacDroid through MediaStore, which needs no permission on API 29+ (spec §4.2).
 * The part file is a pending item (hidden from other apps) under the final name; MediaStore adds " (1)" on a clash.
 */
class MediaStoreSaveTarget(private val context: Context) : SaveTarget {
    override fun freeBytes(): Long? = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull()

    override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MacDroid")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        return MediaStorePart(context.contentResolver, uri)
    }
}

class MediaStorePart(private val resolver: ContentResolver, private val uri: Uri) : PartFile {
    companion object {
        const val PREFIX = "media:"

        fun reopen(context: Context, handle: String): PartFile? {
            val uri = Uri.parse(handle.removePrefix(PREFIX))
            return runCatching {
                requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")).close()
                MediaStorePart(context.contentResolver, uri)
            }.getOrNull()
        }
    }

    override val handle = PREFIX + uri
    override fun length(): Long = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: throw IOException("can't open $uri")
    override fun openRead(): InputStream = resolver.openInputStream(uri) ?: throw IOException("can't read $uri")
    override fun openAppend(): OutputStream = resolver.openOutputStream(uri, "wa") ?: throw IOException("can't write $uri")

    override fun finish(): String {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri.toString()
    }

    override fun delete() {
        runCatching { resolver.delete(uri, null, null) }
    }
}
