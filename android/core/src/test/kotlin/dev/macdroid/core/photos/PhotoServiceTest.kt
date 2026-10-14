package dev.macdroid.core.photos

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.PhotoAlbumBody
import dev.macdroid.core.protocol.PhotoFetchFailure
import dev.macdroid.core.protocol.PhotoItemBody
import dev.macdroid.core.protocol.PhotoKind
import dev.macdroid.core.protocol.PhotoKindFilter
import dev.macdroid.core.protocol.PhotosAlbumListBody
import dev.macdroid.core.protocol.PhotosAlbumsBody
import dev.macdroid.core.protocol.PhotosFetchBody
import dev.macdroid.core.protocol.PhotosFetchFailedBody
import dev.macdroid.core.protocol.PhotosPageBody
import dev.macdroid.core.protocol.PhotosQueryBody
import dev.macdroid.core.protocol.PhotosThumbBody
import dev.macdroid.core.protocol.PhotosThumbsBody
import dev.macdroid.core.transfer.OutgoingFile
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoServiceTest {
    private class FakeSource(var access: PhotoAccess = PhotoAccess.Full) : PhotoSource {
        val queries = mutableListOf<List<Any?>>()
        override fun access() = access
        override fun albums() = listOf(PhotoAlbumBody("all", "All Photos", 2, "9"))
        override fun page(albumId: String, kind: PhotoKindFilter, cursor: String?, limit: Int): PhotoPageResult {
            queries += listOf(albumId, kind, cursor, limit)
            return PhotoPageResult(listOf(PhotoItemBody("9", PhotoKind.Photo, "a.jpg", "image/jpeg", 1000, 10, 4, 3)), "1:9")
        }
        override fun thumbnail(id: String): ByteArray? = if (id == "9") byteArrayOf(-1, -40) else null
        override fun original(id: String): OutgoingFile? = if (id == "9") OutgoingFile("content://media/external/images/media/9", "a.jpg", 10, "image/jpeg") else null
    }

    private val sent = mutableListOf<Message>()
    private val enqueued = mutableListOf<Pair<String, OutgoingFile>>()
    private fun service(source: PhotoSource) = PhotoService(source, { sent += it; true }, { id, file -> enqueued += id to file; true })

    @Test fun albumsAndPagesCarryTheAccessLevel() {
        val source = FakeSource()
        val s = service(source)
        s.handle(Message.PhotosAlbums(PhotosAlbumsBody("r1")))
        s.handle(Message.PhotosQuery(PhotosQueryBody("r2", "all", PhotoKindFilter.Videos, "5:6", 5_000)))
        assertEquals(Message.PhotosAlbumList(PhotosAlbumListBody("r1", PhotoAccess.Full, source.albums())), sent[0])
        assertEquals("r2", (sent[1] as Message.PhotosPage).body.requestId)
        assertEquals("1:9", (sent[1] as Message.PhotosPage).body.nextCursor)
        assertEquals(listOf("all", PhotoKindFilter.Videos, "5:6", 200), source.queries.single())
    }

    @Test fun withoutAccessNothingIsRead() {
        val source = FakeSource(PhotoAccess.None)
        val s = service(source)
        s.handle(Message.PhotosAlbums(PhotosAlbumsBody("r1")))
        s.handle(Message.PhotosQuery(PhotosQueryBody("r2", "all", PhotoKindFilter.All, null, 10)))
        s.handle(Message.PhotosThumbs(PhotosThumbsBody(listOf("9"))))
        s.handle(Message.PhotosFetch(PhotosFetchBody("9", "t1")))
        assertEquals(
            listOf(
                Message.PhotosAlbumList(PhotosAlbumListBody("r1", PhotoAccess.None, emptyList())),
                Message.PhotosPage(PhotosPageBody("r2", PhotoAccess.None, emptyList(), null)),
                Message.PhotosFetchFailed(PhotosFetchFailedBody("t1", PhotoFetchFailure.Denied)),
            ),
            sent,
        )
        assertEquals(emptyList<List<Any?>>(), source.queries)
        assertEquals(0, enqueued.size)
    }

    @Test fun thumbnailsSkipMissingAndInvalidIds() {
        val s = service(FakeSource())
        s.handle(Message.PhotosThumbs(PhotosThumbsBody(listOf("9", "8", "../x"))))
        assertEquals(listOf(Message.PhotosThumb(PhotosThumbBody("9", "/9g="))), sent)
    }

    @Test fun fetchEnqueuesUnderTheMacsIdOrSaysGone() {
        val s = service(FakeSource())
        s.handle(Message.PhotosFetch(PhotosFetchBody("9", "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f")))
        s.handle(Message.PhotosFetch(PhotosFetchBody("8", "t2")))
        s.handle(Message.PhotosFetch(PhotosFetchBody("../9", "t3")))
        s.handle(Message.PhotosFetch(PhotosFetchBody("9", "../../etc")))
        assertEquals("7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", enqueued.single().first)
        assertEquals(
            listOf(
                Message.PhotosFetchFailed(PhotosFetchFailedBody("t2", PhotoFetchFailure.Gone)),
                Message.PhotosFetchFailed(PhotosFetchFailedBody("t3", PhotoFetchFailure.Gone)),
            ),
            sent,
        )
    }
}
