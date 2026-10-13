package dev.macdroid.core.transfer

import dev.macdroid.core.eventually
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.TransferIdBody
import dev.macdroid.core.protocol.TransferRejectBody
import dev.macdroid.core.protocol.TransferRejectReason
import dev.macdroid.core.protocol.TransferResultBody
import dev.macdroid.core.protocol.TransferResultError
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferEngineSendTest {
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    private fun file(name: String, bytes: ByteArray): OutgoingFile {
        val file = File(h.dir, name).apply { writeBytes(bytes) }
        return OutgoingFile(file.path, name, bytes.size.toLong(), "application/octet-stream")
    }

    private fun offers() = h.sentOf<Message.TransferOffer>().map { it.body.transferId }
    private fun accept(id: String) = h.deliver(Message.TransferAccept(TransferIdBody(id)))

    @Test fun offersAtMostThreeInOrderAndQueuesTheRest() {
        h.start()
        val ids = h.engine.enqueue((1..5).map { file("f$it.bin", testBytes(10)) })
        eventually { offers().size == 3 }
        assertEquals(ids.take(3), offers())
        eventually { h.active().count { it.phase == Phase.Queued } == 2 }
    }

    // Final review: files queued before the saved state finishes loading (a share cold-starting the service) must survive.
    @Test fun enqueueWhileLoadingSurvivesLoading() {
        repeat(10) { round ->
            // Expired state with a part file makes load() suspend on IO to delete it: the window an early enqueue lands in.
            val dir = java.nio.file.Files.createTempDirectory("load").toFile()
            val part = File(dir, "old.bin.macdroid-part").apply { writeText("x") }
            val store = InMemoryTransferStore(StoredTransfers(active = listOf(
                TransferRecord(id = "old", direction = Direction.FromMac, name = "old.bin", size = 1, mime = "x",
                    phase = Phase.Active, part = part.path, createdAt = 0, updatedAt = 0),
            )))
            val harness = EngineHarness(store = store, dir = dir, now = 25L * 3600 * 1000)
            try {
                val source = File(dir, "early.bin").apply { writeBytes(testBytes(10)) }
                harness.engine.start(harness.incoming, harness.connected)
                val id = harness.engine.enqueue(listOf(OutgoingFile(source.path, source.name, 10, "x"))).single()
                eventually { harness.history().any { it.id == "old" } }
                eventually { harness.sentOf<Message.TransferOffer>().any { it.body.transferId == id } }
            } catch (e: AssertionError) {
                throw AssertionError("round $round: the enqueued file was lost while loading", e)
            } finally {
                harness.close()
            }
        }
    }

    @Test fun acceptUploadsAndSendsTheWholeFileChecksum() {
        h.start()
        val bytes = testBytes(300_000)
        val id = h.engine.enqueue(listOf(file("a.bin", bytes))).single()
        eventually { offers() == listOf(id) }
        accept(id)
        eventually { h.sentOf<Message.TransferFinish>().any { it.body.transferId == id } }
        assertEquals(sha256(bytes), h.sentOf<Message.TransferFinish>().single().body.sha256)
        assertArrayEquals(bytes, h.mac.uploaded.getValue(id).toByteArray())
        assertEquals(Phase.Verifying, h.active().single().phase)
        h.deliver(Message.TransferResult(TransferResultBody(id, ok = true)))
        eventually { h.history().firstOrNull()?.phase == Phase.Done }
    }

    // Review Focus #2
    @Test fun resumedUploadSendsOnlyTheTail() {
        h.start()
        val bytes = testBytes(300_000)
        val id = h.engine.enqueue(listOf(file("a.bin", bytes))).single()
        h.mac.uploaded[id] = ByteArrayOutputStream().apply { write(bytes, 0, 120_000) } // the Mac kept these from before
        eventually { offers() == listOf(id) }
        accept(id)
        eventually { h.sentOf<Message.TransferFinish>().isNotEmpty() }
        assertEquals(listOf(120_000L), h.mac.uploadOffsets.toList())
        assertEquals(sha256(bytes), h.sentOf<Message.TransferFinish>().single().body.sha256)
        assertArrayEquals(bytes, h.mac.uploaded.getValue(id).toByteArray())
    }

    @Test fun droppedUploadRetriesFromTheMacsOffset() {
        h.start()
        h.mac.failNextAfter = 100_000
        val bytes = testBytes(400_000)
        val id = h.engine.enqueue(listOf(file("a.bin", bytes))).single()
        eventually { offers() == listOf(id) }
        accept(id)
        eventually { h.sentOf<Message.TransferFinish>().isNotEmpty() }
        assertEquals(listOf(0L, 100_000L), h.mac.uploadOffsets.toList())
        assertEquals(sha256(bytes), h.sentOf<Message.TransferFinish>().single().body.sha256)
    }

    // Review Focus #5
    @Test fun macAlreadyHasEverythingGoesStraightToFinish() {
        h.start()
        val empty = h.engine.enqueue(listOf(file("empty.txt", ByteArray(0)))).single()
        eventually { offers() == listOf(empty) }
        accept(empty)
        eventually { h.sentOf<Message.TransferFinish>().any { it.body.transferId == empty } }
        assertTrue(h.mac.uploadOffsets.isEmpty())
        assertEquals(sha256(ByteArray(0)), h.sentOf<Message.TransferFinish>().single().body.sha256)
    }

    @Test fun reconnectReoffersUnfinishedTransfersUnderTheSameId() {
        h.start()
        val id = h.engine.enqueue(listOf(file("a.bin", testBytes(10)))).single()
        eventually { offers() == listOf(id) }
        h.connected.value = false
        eventually { h.active().single().phase == Phase.Paused }
        h.connected.value = true
        eventually { offers() == listOf(id, id) }
    }

    @Test fun rejectFailsAndFreesASlot() {
        h.start()
        val ids = h.engine.enqueue((1..4).map { file("f$it.bin", testBytes(10)) })
        eventually { offers().size == 3 }
        h.deliver(Message.TransferReject(TransferRejectBody(ids[0], TransferRejectReason.NoSpace)))
        eventually { offers().size == 4 }
        assertEquals(Failure.NoSpace, h.history().first { it.id == ids[0] }.failure)
    }

    @Test fun failedResultIsRecorded() {
        h.start()
        val id = h.engine.enqueue(listOf(file("a.bin", testBytes(10)))).single()
        eventually { offers() == listOf(id) }
        h.deliver(Message.TransferResult(TransferResultBody(id, ok = false, error = TransferResultError.ChecksumMismatch)))
        eventually { h.history().firstOrNull()?.failure == Failure.ChecksumMismatch }
    }

    @Test fun localCancelIsSentOrQueuedWhenOffline() {
        h.start()
        val (id, other) = h.engine.enqueue(listOf(file("a.bin", testBytes(10)), file("b.bin", testBytes(10))))
        eventually { offers() == listOf(id, other) }
        h.online = false
        h.engine.cancel(id)
        eventually { h.history().firstOrNull()?.phase == Phase.Cancelled }
        assertTrue(h.sentOf<Message.TransferCancel>().isEmpty())
        h.online = true
        h.connected.value = false
        // StateFlow conflates: wait until the engine has seen the disconnect before reconnecting.
        eventually { h.active().single().phase == Phase.Paused }
        h.connected.value = true
        eventually { h.sentOf<Message.TransferCancel>().any { it.body.transferId == id } }
    }

    @Test fun unreadableSourceFailsAndCancels() {
        h.start()
        val gone = OutgoingFile(File(h.dir, "missing.bin").path, "missing.bin", 10, "x")
        val id = h.engine.enqueue(listOf(gone)).single()
        eventually { offers() == listOf(id) }
        accept(id)
        eventually { h.history().firstOrNull()?.failure == Failure.SourceUnavailable }
        assertTrue(h.sentOf<Message.TransferCancel>().any { it.body.transferId == id })
    }

    @Test fun macThatForgotTheIdGetsItOfferedAgain() {
        h.start()
        h.mac.headStatus = 404
        val id = h.engine.enqueue(listOf(file("a.bin", testBytes(10)))).single()
        eventually { offers() == listOf(id) }
        accept(id)
        eventually { offers() == listOf(id, id) }
        h.mac.headStatus = null
        accept(id)
        eventually { h.sentOf<Message.TransferFinish>().isNotEmpty() }
    }

    @Test fun retryQueuesANewTransferForTheSameFile() {
        h.start()
        val id = h.engine.enqueue(listOf(file("a.bin", testBytes(10)))).single()
        eventually { offers() == listOf(id) }
        h.deliver(Message.TransferReject(TransferRejectBody(id, TransferRejectReason.Declined)))
        eventually { h.history().isNotEmpty() }
        val again = h.engine.retry(id)
        assertNotEquals(id, again)
        eventually { offers() == listOf(id, again) }
    }
}
