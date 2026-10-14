package dev.macdroid.core.photos

import dev.macdroid.core.protocol.Message
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs the Mac's photo requests in two serial lanes: thumbnails in one, everything else (albums, pages, fetches)
 * in the other, so a backlog of thumbnail batches from fast scrolling never delays a fetch past the Mac's
 * 30 s timeout (final review I2). Order is kept within each lane.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoLanes(scope: CoroutineScope, dispatcher: CoroutineDispatcher, private val handle: (Message) -> Unit) {
    private val requests = Channel<Message>(Channel.UNLIMITED)
    private val thumbnails = Channel<Message>(Channel.UNLIMITED)

    init {
        for (lane in listOf(requests, thumbnails)) {
            scope.launch(dispatcher.limitedParallelism(1)) {
                for (message in lane) runCatching { handle(message) }
            }
        }
    }

    fun submit(message: Message) {
        (if (message is Message.PhotosThumbs) thumbnails else requests).trySend(message)
    }
}
