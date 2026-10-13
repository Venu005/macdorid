package dev.macdroid.app.platform

import android.content.Context
import android.net.Uri
import java.io.File

/** Whether a received file is still where it was saved (the user may have deleted it in Files or Photos). Blocking: call off the main thread when you can. */
object ReceivedFiles {
    fun exists(context: Context, savedUri: String): Boolean {
        val uri = Uri.parse(savedUri)
        if (uri.scheme == "file") return uri.path?.let { File(it).exists() } == true
        return runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true }.getOrDefault(false)
    }
}
