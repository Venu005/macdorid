package dev.macdroid.app

import dev.macdroid.app.ui.presentation.TransferPresentation
import dev.macdroid.core.transfer.Direction
import dev.macdroid.core.transfer.Failure
import dev.macdroid.core.transfer.Phase
import dev.macdroid.core.transfer.TransferRecord
import dev.macdroid.core.transfer.TransferSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferPresentationTest {
    private fun record(
        direction: Direction, phase: Phase, size: Long = 2_100_000_000, done: Long = 0, failure: Failure? = null,
        byPeer: Boolean = false, source: String? = null, saved: String? = null,
    ) = TransferRecord(id = "t", direction = direction, name = "clip.mp4", size = size, mime = "video/mp4", phase = phase,
        bytesDone = done, failure = failure, cancelledByPeer = byPeer, source = source, savedUri = saved, createdAt = 0, updatedAt = 0)

    @Test fun activeRowShowsProgressSpeedAndTimeLeft() {
        val row = TransferPresentation.row(record(Direction.ToMac, Phase.Active, done = 1_300_000_000), 48_000_000.0)
        assertEquals("→ Mac", row.directionLabel)
        assertEquals("1.3 of 2.1 GB · 48 MB/s · 17 s left", row.detail)
        assertEquals(1_300_000_000f / 2_100_000_000f, row.progress!!, 0.0001f)
        assertTrue(row.canCancel)
    }

    @Test fun waitingStates() {
        assertEquals("Waiting to send", TransferPresentation.row(record(Direction.ToMac, Phase.Queued), null).detail)
        assertEquals("Waiting for your Mac", TransferPresentation.row(record(Direction.ToMac, Phase.Offered), null).detail)
        assertEquals("Waiting for you to choose where to save", TransferPresentation.row(record(Direction.FromMac, Phase.Offered), null).detail)
        assertEquals("Paused — waiting for connection", TransferPresentation.row(record(Direction.FromMac, Phase.Paused), null).detail)
        assertEquals("Checking…", TransferPresentation.row(record(Direction.FromMac, Phase.Verifying), null).detail)
        assertEquals("← Mac", TransferPresentation.row(record(Direction.FromMac, Phase.Active), null).directionLabel)
    }

    @Test fun finishedRows() {
        val received = TransferPresentation.row(record(Direction.FromMac, Phase.Done, size = 3_145_728, saved = "content://x"), null)
        assertEquals("Received · 3.1 MB", received.detail)
        assertTrue(received.canOpen)
        assertFalse(received.canCancel)
        assertNull(received.progress)
        assertEquals("Sent · 512 bytes", TransferPresentation.row(record(Direction.ToMac, Phase.Done, size = 512), null).detail)
        assertEquals("Cancelled on your Mac", TransferPresentation.row(record(Direction.ToMac, Phase.Cancelled, byPeer = true), null).detail)
        assertEquals("Cancelled on your phone", TransferPresentation.row(record(Direction.ToMac, Phase.Cancelled), null).detail)
    }

    @Test fun failures() {
        val corrupted = TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.ChecksumMismatch, source = "content://a"), null)
        assertEquals("Transfer failed — file was corrupted in transit", corrupted.detail)
        assertTrue(corrupted.isError && corrupted.canRetry)
        assertFalse(TransferPresentation.row(record(Direction.FromMac, Phase.Failed, failure = Failure.Io), null).canRetry)
        assertEquals("Not enough space on your Mac", TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.NoSpace), null).detail)
        assertEquals("Not enough space on this phone", TransferPresentation.row(record(Direction.FromMac, Phase.Failed, failure = Failure.NoSpace), null).detail)
        assertEquals("Declined on your Mac", TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.Declined), null).detail)
        assertEquals("Not saved", TransferPresentation.row(record(Direction.FromMac, Phase.Failed, failure = Failure.Declined), null).detail)
        assertEquals("The original file is no longer available", TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.SourceUnavailable), null).detail)
        assertEquals("Expired — not finished within 24 hours", TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.Expired), null).detail)
        assertEquals("Transfer failed", TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.Io), null).detail)
    }

    @Test fun progressSummary() {
        assertNull(TransferPresentation.progressSummary(TransferSnapshot()))
        val sending = TransferSnapshot(active = listOf(record(Direction.ToMac, Phase.Active, size = 100, done = 25), record(Direction.ToMac, Phase.Queued, size = 100)))
        assertEquals(TransferPresentation.ProgressSummary("Sending 2 files to your Mac", 12), TransferPresentation.progressSummary(sending))
        val receiving = TransferSnapshot(active = listOf(record(Direction.FromMac, Phase.Active, size = 10, done = 5)))
        assertEquals(TransferPresentation.ProgressSummary("Receiving 1 file from your Mac", 50), TransferPresentation.progressSummary(receiving))
        val both = TransferSnapshot(active = listOf(record(Direction.FromMac, Phase.Active, size = 0), record(Direction.ToMac, Phase.Active, size = 0)))
        assertEquals(TransferPresentation.ProgressSummary("Transferring 2 files", 0), TransferPresentation.progressSummary(both))
    }

    @Test fun recentIsTheNewestThree() {
        val history = (1..5).map { record(Direction.ToMac, Phase.Done).copy(id = "t$it") }
        assertEquals(listOf("t1", "t2", "t3"), TransferPresentation.recent(TransferSnapshot(history = history)).map { it.id })
    }

    @Test fun receivedFileThatWasDeletedSaysSoAndCannotBeOpened() {
        val gone = TransferPresentation.row(record(Direction.FromMac, Phase.Done, size = 3_145_728, saved = "content://x"), null, savedFileExists = false)
        assertEquals("Deleted from this phone", gone.detail)
        assertFalse(gone.canOpen)
        assertFalse(gone.isError)
        assertEquals("Sent · 512 bytes", TransferPresentation.row(record(Direction.ToMac, Phase.Done, size = 512), null, savedFileExists = false).detail)
    }

    // Final review: an undecided incoming file can always be answered from its row, not only from a notification.
    @Test fun undecidedIncomingRowOffersChoosingWhereToSave() {
        assertTrue(TransferPresentation.row(record(Direction.FromMac, Phase.Offered), null).canChooseLocation)
        assertFalse(TransferPresentation.row(record(Direction.ToMac, Phase.Offered), null).canChooseLocation)
        assertFalse(TransferPresentation.row(record(Direction.FromMac, Phase.Active), null).canChooseLocation)
    }
}
