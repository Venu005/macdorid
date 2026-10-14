package dev.macdroid.app.platform.photos

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.photos.PhotoAccessWatcher
import dev.macdroid.core.photos.PhotoLanes
import dev.macdroid.core.photos.PhotoService
import dev.macdroid.core.protocol.Features
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.TransferPurpose
import dev.macdroid.core.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Serves the Mac's photo browsing (spec §4): requests run on two serial lanes (thumbnails apart from the rest), and library changes
 * reach the Mac as `photos.changed`, debounced by a second.
 */
@OptIn(FlowPreview::class)
class PhotosBridge(
    private val context: Context,
    private val connection: ConnectionManager,
    private val engine: TransferEngine,
    private val scope: CoroutineScope,
) {
    private val library = PhotoLibrary(context)
    private val service = PhotoService(library, connection::send) { id, file -> engine.enqueueWithId(id, file, TransferPurpose.Photo) }
    private val lanes = PhotoLanes(scope, Dispatchers.IO) { service.handle(it) }
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val watcher = PhotoAccessWatcher(library::access) { changes.tryEmit(Unit) }
    val access: StateFlow<PhotoAccess> = watcher.access

    fun start() {
        // Collect quickly and queue: a slow thumbnail batch must never hold up other messages.
        scope.launch {
            connection.incoming.collect { message ->
                if (message.isPhotoRequest()) lanes.submit(message)
            }
        }
        scope.launch {
            changes.debounce(CHANGE_DEBOUNCE_MS).collect {
                if (connection.state.value.macSupports(Features.PHOTOS)) connection.send(Message.PhotosChanged)
            }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                changes.tryEmit(Unit)
            }
        }
        // Images and video only: downloads and files from the Mac must not reload the Mac's photos (follow-up M3).
        for (uri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            context.contentResolver.registerContentObserver(uri, true, observer)
        }
    }

    /** Re-reads the permission (call on resume and after a prompt); a change reloads the Mac's view. */
    fun refreshAccess() = watcher.refresh()

    /** After a permission prompt: also reloads the Mac when a limited-access user picked a different set (follow-up M4). */
    fun afterPrompt() = watcher.afterPrompt()

    private fun Message.isPhotoRequest() =
        this is Message.PhotosAlbums || this is Message.PhotosQuery || this is Message.PhotosThumbs || this is Message.PhotosFetch

    private companion object {
        const val CHANGE_DEBOUNCE_MS = 1_000L
    }
}
