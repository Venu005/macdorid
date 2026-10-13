package dev.macdroid.app.platform.clipboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import dev.macdroid.core.transfer.FileNaming
import java.io.File
import java.net.URI
import java.util.UUID

/** Clipboard images in app-private storage (spec §4.3): received ones are shared through a FileProvider. */
object ClipboardImages {
    fun receivedDir(context: Context) = File(context.cacheDir, "clipboard")
    private fun outgoingDir(context: Context) = File(context.cacheDir, "clipboard-out")

    /** A content URI other apps can paste, for a received file (a `file:` URI from the engine). */
    fun contentUri(context: Context, fileUri: String): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".clipboard", File(URI(fileUri)))

    /** Any image the clipboard or the share sheet gives us, as a PNG file in app storage; null if unreadable. */
    fun toPng(context: Context, source: Uri): File? = runCatching {
        val bitmap = context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val dir = outgoingDir(context).apply { mkdirs() }
        val file = File(dir, UUID.randomUUID().toString() + ".png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        prune(dir)
        file
    }.getOrNull()

    /** Keeps the newest [keep] finished files in [dir] (the Recent list shows at most 10). */
    fun prune(dir: File, keep: Int = 10) {
        dir.listFiles { file -> !file.name.endsWith(FileNaming.PART_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }
}
