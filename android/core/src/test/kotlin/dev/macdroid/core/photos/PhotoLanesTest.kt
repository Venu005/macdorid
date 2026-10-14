package dev.macdroid.core.photos

import dev.macdroid.core.eventually
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.PhotosFetchBody
import dev.macdroid.core.protocol.PhotosThumbsBody
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoLanesTest {
    private val scope = CoroutineScope(SupervisorJob())

    @After fun tearDown() = scope.cancel()

    // Final review I2: a backlog of thumbnail batches must not hold up a fetch (the Mac gives up after 30 s).
    @Test fun aFetchIsNotStuckBehindThumbnails() {
        val release = CountDownLatch(1)
        val handled = CopyOnWriteArrayList<Message>()
        val lanes = PhotoLanes(scope, Dispatchers.IO) { message ->
            if (message is Message.PhotosThumbs) release.await(5, TimeUnit.SECONDS)
            handled += message
        }
        lanes.submit(Message.PhotosThumbs(PhotosThumbsBody(listOf("1"))))
        lanes.submit(Message.PhotosThumbs(PhotosThumbsBody(listOf("2"))))
        lanes.submit(Message.PhotosFetch(PhotosFetchBody("9", "t1")))
        eventually { handled.any { it is Message.PhotosFetch } }
        assertEquals(0, handled.count { it is Message.PhotosThumbs })
        release.countDown()
        eventually { handled.count { it is Message.PhotosThumbs } == 2 }
        // Each lane keeps its own order.
        assertEquals(listOf("1", "2"), handled.filterIsInstance<Message.PhotosThumbs>().map { it.body.ids.single() })
    }
}
