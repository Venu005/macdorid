package dev.macdroid.app.platform.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import dev.macdroid.core.clipboard.ClipItem
import dev.macdroid.core.clipboard.ClipboardApply
import dev.macdroid.core.clipboard.ClipboardHub
import dev.macdroid.core.clipboard.SendResult
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.transfer.OutgoingFile
import dev.macdroid.core.transfer.TransferEngine
import dev.macdroid.core.transfer.TransferEvent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connects the Mac's clipboard items to Android's clipboard, and the phone's explicit sends to the Mac (spec §4.2–4.3). */
class ClipboardBridge(
    private val context: Context,
    private val connection: ConnectionManager,
    private val engine: TransferEngine,
    val prefs: ClipboardPrefs,
    private val scope: CoroutineScope,
) {
    val hub = ClipboardHub(connection::send, { engine.enqueueClipboard(it) }, { prefs.receiveFromMac.value }, cancelImages = engine::cancelClipboard)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    fun start() {
        scope.launch { connection.incoming.collect { message -> hub.handle(message)?.let { apply(it) } } }
        scope.launch {
            engine.events.collect { event ->
                if (event is TransferEvent.ClipboardReceived) hub.imageReceived(event.uri, event.mime)?.let { apply(it) }
            }
        }
    }

    /** Sends what was on the clipboard. Read [clip] in a focused activity, which Android requires; the rest runs off the main thread. */
    suspend fun sendClip(clip: ClipData?): SendResult = withContext(Dispatchers.IO) {
        val data = clip?.takeIf { it.itemCount > 0 } ?: return@withContext SendResult.Empty
        val item = data.getItemAt(0)
        val uri = item.uri
        if (uri != null && data.description.hasMimeType("image/*")) sendImage(uri)
        else hub.sendText(item.coerceToText(context)?.toString(), connection.state.value)
    }

    /** "Copy to Mac" from the share sheet: shared text, or a single image. */
    suspend fun sendShared(text: String?, image: Uri?): SendResult = withContext(Dispatchers.IO) {
        if (image != null) sendImage(image) else hub.sendText(text, connection.state.value)
    }

    /** Recent → tap: copy it to this phone's clipboard again. */
    fun copyLocally(item: ClipItem) {
        val image = item.imageUri
        val clip = when {
            item.kind == ClipItem.Kind.Image && image != null -> imageClip(image)
            else -> ClipData.newPlainText("MacDroid", item.text.orEmpty())
        } ?: return
        runCatching { clipboard.setPrimaryClip(clip) }
    }

    private fun sendImage(source: Uri): SendResult {
        val png = ClipboardImages.toPng(context, source)
        val file = png?.let { OutgoingFile(it.path, "clipboard.png", it.length(), "image/png") }
        return hub.sendImage(file, connection.state.value)
    }

    private suspend fun apply(item: ClipboardApply) = withContext(Dispatchers.Main) {
        val clip = when (item) {
            is ClipboardApply.Text -> ClipData.newPlainText("From your Mac", item.text)
            is ClipboardApply.Image -> imageClip(item.uri)
        } ?: return@withContext
        runCatching { clipboard.setPrimaryClip(clip) } // allowed in the background (spec §4.3)
        withContext(Dispatchers.IO) { ClipboardImages.prune(ClipboardImages.receivedDir(context)) }
    }

    /** Received images are `file:` URIs in the clipboard folder; images we sent are plain paths of PNG copies. */
    private fun imageClip(uriOrPath: String): ClipData? = runCatching {
        val fileUri = if (uriOrPath.startsWith("/")) File(uriOrPath).toURI().toString() else uriOrPath
        ClipData.newUri(context.contentResolver, "Image", ClipboardImages.contentUri(context, fileUri))
    }.getOrNull()
}
