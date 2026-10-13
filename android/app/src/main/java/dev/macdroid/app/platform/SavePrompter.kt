package dev.macdroid.app.platform

import android.content.Context
import android.net.Uri
import dev.macdroid.app.service.TransferNotifications
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Lets a background transfer ask where to save. It posts a notification that opens [dev.macdroid.app.ui.files.SaveAsActivity].
 * The question waits for an answer with no timeout (ruling 6); cancelling the transfer cancels it.
 */
class SavePrompter(private val context: Context) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Uri?>>()

    suspend fun ask(transferId: String, name: String, mime: String): Uri? {
        val answer = CompletableDeferred<Uri?>()
        pending[transferId] = answer
        TransferNotifications.askWhereToSave(context, transferId, name, mime)
        try {
            return answer.await()
        } finally {
            pending.remove(transferId)
            TransferNotifications.cancelAsk(context, transferId)
        }
    }

    fun answer(transferId: String, uri: Uri?) {
        pending[transferId]?.complete(uri)
    }
}
