package dev.macdroid.core.photos

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.PhotoAlbumBody
import dev.macdroid.core.protocol.PhotoFetchFailure
import dev.macdroid.core.protocol.PhotoItemBody
import dev.macdroid.core.protocol.PhotoKindFilter
import dev.macdroid.core.protocol.PhotosAlbumListBody
import dev.macdroid.core.protocol.PhotosFetchFailedBody
import dev.macdroid.core.protocol.PhotosPageBody
import dev.macdroid.core.protocol.PhotosThumbBody
import dev.macdroid.core.transfer.OutgoingFile
import java.util.Base64

data class PhotoPageResult(val items: List<PhotoItemBody>, val nextCursor: String?)

/** The phone's library as MacDroid sees it (MediaStore on the phone, a fake in tests). */
interface PhotoSource {
    fun access(): PhotoAccess
    /** "all" first, then each bucket. */
    fun albums(): List<PhotoAlbumBody>
    fun page(albumId: String, kind: PhotoKindFilter, cursor: String?, limit: Int): PhotoPageResult
    /** A JPEG at most 256 px on its longest side, or null when the item is gone or not visible. */
    fun thumbnail(id: String): ByteArray?
    fun original(id: String): OutgoingFile?
}

/**
 * Answers the Mac's photos.* requests (spec §3–4). Keeps no state of its own; call [handle] off the main thread
 * (thumbnails and queries do disk I/O), through [PhotoLanes].
 */
class PhotoService(
    private val source: PhotoSource,
    private val send: (Message) -> Boolean,
    private val enqueue: (transferId: String, file: OutgoingFile) -> Boolean,
) {
    fun handle(message: Message) {
        when (message) {
            is Message.PhotosAlbums -> {
                val access = source.access()
                val albums = if (access == PhotoAccess.None) emptyList() else source.albums()
                send(Message.PhotosAlbumList(PhotosAlbumListBody(message.body.requestId, access, albums)))
            }
            is Message.PhotosQuery -> {
                val body = message.body
                val access = source.access()
                val page = if (access == PhotoAccess.None) PhotoPageResult(emptyList(), null)
                    else source.page(body.albumId, body.kind, body.cursor, PhotoQueries.pageLimit(body.limit))
                send(Message.PhotosPage(PhotosPageBody(body.requestId, access, page.items, page.nextCursor)))
            }
            is Message.PhotosThumbs -> {
                if (source.access() == PhotoAccess.None) return
                for (id in PhotoQueries.thumbIds(message.body.ids)) {
                    val jpeg = source.thumbnail(id) ?: continue
                    if (!send(Message.PhotosThumb(PhotosThumbBody(id, Base64.getEncoder().encodeToString(jpeg))))) return
                }
            }
            is Message.PhotosFetch -> {
                val body = message.body
                if (!PhotoQueries.isValidTransferId(body.transferId)) return
                fun fail(reason: PhotoFetchFailure) = send(Message.PhotosFetchFailed(PhotosFetchFailedBody(body.transferId, reason)))
                when {
                    !PhotoQueries.isValidId(body.id) -> fail(PhotoFetchFailure.Gone)
                    source.access() == PhotoAccess.None -> fail(PhotoFetchFailure.Denied)
                    else -> source.original(body.id)?.let { enqueue(body.transferId, it) } ?: fail(PhotoFetchFailure.Gone)
                }
            }
            else -> Unit
        }
    }
}
