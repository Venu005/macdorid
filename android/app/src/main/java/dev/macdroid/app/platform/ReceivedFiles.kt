package dev.macdroid.app.platform

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException

/** Whether a received file is still where it was saved (the user may have deleted it in Files or Photos). Blocking: call off the main thread when you can. */
object ReceivedFiles {
    fun exists(context: Context, savedUri: String): Boolean {
        val uri = Uri.parse(savedUri)
        if (uri.scheme == "file") return uri.path?.let { File(it).exists() } == true
        return exists { requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r")) { "no descriptor" }.close() }
    }

    /**
     * Only "not found" means deleted. Losing access (a released folder grant, ownership lost after a reinstall) isn't
     * proof the file is gone, so the row doesn't claim it.
     */
    fun exists(open: () -> Unit): Boolean = try {
        open()
        true
    } catch (e: FileNotFoundException) {
        false
    } catch (e: Exception) {
        true
    }
}
