package dev.macdroid.app.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.macdroid.core.transfer.TransferRecord

/** The two ways to pick files to send: the system Photo Picker (no storage permission) and the document picker. */
class SendPickers(val pickPhotos: () -> Unit, val pickFiles: () -> Unit)

@Composable
fun rememberSendPickers(onPicked: (List<Uri>) -> Unit): SendPickers {
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { if (it.isNotEmpty()) onPicked(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onPicked(it) }
    return remember(photos, files) {
        SendPickers(
            pickPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            pickFiles = { files.launch(arrayOf("*/*")) },
        )
    }
}

/**
 * Opens a received file in whatever app handles its type, or says it's gone if the user deleted it. [exists] comes from
 * the view model's off-main-thread check, so a tap does no storage I/O on the main thread (Plan 11).
 */
fun Context.openReceived(record: TransferRecord, exists: Boolean) {
    val saved = record.savedUri ?: return
    if (!exists) {
        Toast.makeText(this, "“${record.name}” was deleted from this phone", Toast.LENGTH_LONG).show()
        return
    }
    runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(saved), record.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
}
