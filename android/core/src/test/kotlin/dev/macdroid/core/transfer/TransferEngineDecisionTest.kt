package dev.macdroid.core.transfer

import dev.macdroid.core.eventually
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.TransferIdBody
import dev.macdroid.core.protocol.TransferOfferBody
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A save target that waits for the test, like an "Ask where to save" prompt. */
class GateTarget(private val folder: File) : SaveTarget {
    val asked = CopyOnWriteArrayList<String>()
    val answer = CompletableDeferred<Boolean>() // true: save into the folder; false: decline
    @Volatile var cancelled = false

    override fun freeBytes(): Long? = null

    override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? {
        asked += transferId
        return try {
            if (answer.await()) FolderSaveTarget(folder).createPart(name, mime, transferId) else null
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        }
    }
}

class TransferEngineDecisionTest {
    private val dir: File = Files.createTempDirectory("decide").toFile()
    private val gate = GateTarget(File(dir, "Download/MacDroid"))
    private val h = EngineHarness(dir = dir, target = gate)

    @After fun tearDown() = h.close()

    private fun offer(id: String = "t1") = h.deliver(Message.TransferOffer(TransferOfferBody(id, "../IMG.jpg", 10, "image/jpeg")))

    @Test fun undecidedOfferIsVisibleAsOffered() {
        h.start()
        h.mac.served["t1"] = testBytes(10)
        offer()
        eventually { gate.asked.size == 1 }
        val pending = h.active().single()
        assertEquals(Phase.Offered, pending.phase)
        assertEquals("IMG.jpg", pending.name)
        assertNull(pending.part)
        gate.answer.complete(true)
        eventually { h.sentOf<Message.TransferAccept>().size == 1 }
        eventually { h.active().singleOrNull()?.part != null }
    }

    @Test fun peerCancelWhileDecidingCancelsTheQuestion() {
        h.start()
        offer()
        eventually { gate.asked.size == 1 }
        h.deliver(Message.TransferCancel(TransferIdBody("t1")))
        eventually { gate.cancelled }
        eventually { h.history().firstOrNull()?.phase == Phase.Cancelled }
        assertTrue(h.history().first().cancelledByPeer)
        assertTrue(h.active().isEmpty())
        assertTrue(h.sentOf<Message.TransferAccept>().isEmpty() && h.sentOf<Message.TransferReject>().isEmpty())
    }

    @Test fun localCancelWhileDecidingTellsTheMac() {
        h.start()
        offer()
        eventually { gate.asked.size == 1 }
        h.engine.cancel("t1")
        eventually { gate.cancelled }
        eventually { h.sentOf<Message.TransferCancel>().any { it.body.transferId == "t1" } }
    }

    @Test fun reofferWhileDecidingIsIgnored() {
        h.start()
        offer()
        eventually { gate.asked.size == 1 }
        offer()
        Thread.sleep(100)
        assertEquals(1, gate.asked.size)
        assertTrue(h.sent.isEmpty())
    }

    @Test fun disconnectLeavesAnUndecidedOfferAlone() {
        h.start()
        offer()
        eventually { gate.asked.size == 1 }
        h.connected.value = false
        Thread.sleep(100)
        assertEquals(Phase.Offered, h.active().single().phase)
        assertTrue(!gate.cancelled)
    }

    // Review Focus #5
    @Test fun restartForgetsUndecidedOffers() {
        val store = InMemoryTransferStore(StoredTransfers(active = listOf(
            TransferRecord(id = "t1", direction = Direction.FromMac, name = "a", size = 1, mime = "x", phase = Phase.Offered,
                createdAt = h.now, updatedAt = h.now),
        )))
        val harness = EngineHarness(store = store, dir = dir, target = gate)
        try {
            harness.start()
            Thread.sleep(100)
            assertTrue(harness.active().isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test fun pausedUploadIsRetriedWhileConnected() {
        val harness = EngineHarness(dir = dir, resumeIntervalMs = 300)
        try {
            harness.start()
            harness.mac.headStatus = 500 // every attempt fails, until the retries run out
            val source = File(dir, "up.bin").apply { writeBytes(testBytes(1_000)) }
            val id = harness.engine.enqueue(listOf(OutgoingFile(source.path, source.name, 1_000, "x"))).single()
            eventually { harness.sentOf<Message.TransferOffer>().size == 1 }
            harness.deliver(Message.TransferAccept(TransferIdBody(id)))
            Thread.sleep(250) // 3 retries 20 ms apart have given up by now
            assertEquals(Phase.Paused, harness.active().single().phase)
            harness.mac.headStatus = null
            eventually { harness.sentOf<Message.TransferOffer>().size == 2 } // the periodic resume offers it again
            harness.deliver(Message.TransferAccept(TransferIdBody(id)))
            eventually { harness.sentOf<Message.TransferFinish>().any { it.body.transferId == id } }
        } finally {
            harness.close()
        }
    }
}
