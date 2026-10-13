package dev.macdroid.app

import dev.macdroid.app.platform.FinishedSends
import dev.macdroid.core.transfer.Direction
import dev.macdroid.core.transfer.Failure
import dev.macdroid.core.transfer.Phase
import dev.macdroid.core.transfer.TransferRecord
import org.junit.Assert.assertEquals
import org.junit.Test

// Final review: Retry needs a failed send's source, so only completed sends are cleaned up.
class FinishedSendsTest {
    private val root = "/data/cache/outgoing"
    private fun sent(id: String, phase: Phase, source: String, direction: Direction = Direction.ToMac) =
        TransferRecord(id = id, direction = direction, name = id, size = 1, mime = "x", phase = phase,
            failure = if (phase == Phase.Failed) Failure.Io else null, source = source, createdAt = 0, updatedAt = 0)

    @Test fun onlyCompletedSendsAreCleanedUp() {
        val history = listOf(
            sent("copyDone", Phase.Done, "$root/a/copy.jpg"),
            sent("copyFailed", Phase.Failed, "$root/b/copy.jpg"),
            sent("uriDone", Phase.Done, "content://media/1"),
            sent("uriCancelled", Phase.Cancelled, "content://media/2"),
            sent("received", Phase.Done, "content://media/3", Direction.FromMac),
        )
        val cleanup = FinishedSends.cleanup(history, root)
        assertEquals(listOf("$root/a/copy.jpg"), cleanup.copiesToDelete)
        assertEquals(listOf("content://media/1"), cleanup.grantsToRelease)
    }
}
