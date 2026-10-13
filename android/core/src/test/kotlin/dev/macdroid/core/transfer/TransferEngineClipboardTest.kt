package dev.macdroid.core.transfer

import dev.macdroid.core.eventually
import dev.macdroid.core.protocol.ClipboardLimits
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.TransferOfferBody
import dev.macdroid.core.protocol.TransferPurpose
import dev.macdroid.core.protocol.TransferRejectBody
import dev.macdroid.core.protocol.TransferRejectReason
import dev.macdroid.core.protocol.TransferResultBody
import java.io.File
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferEngineClipboardTest {
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    private fun clipboardOffer(id: String, bytes: ByteArray) {
        h.mac.served[id] = bytes
        h.deliver(Message.TransferOffer(TransferOfferBody(id, "clipboard.png", bytes.size.toLong(), "image/png", TransferPurpose.Clipboard)))
    }

    // Review Focus #4
    @Test fun clipboardOfferUsesTheClipboardTargetAndLeavesNoHistory() {
        h.start()
        val events = CopyOnWriteArrayList<TransferEvent>()
        h.scope.launch { h.engine.events.collect { events += it } }
        val png = testBytes(4_000)
        clipboardOffer("c1", png)
        h.finish("c1", png)
        eventually { h.sentOf<Message.TransferResult>().any { it.body == TransferResultBody("c1", ok = true) } }
        eventually { events.isNotEmpty() }
        val event = events.single() as TransferEvent.ClipboardReceived
        assertEquals("image/png", event.mime)
        val file = File(URI(event.uri))
        assertEquals(h.clipboardFolder.canonicalPath, file.parentFile.canonicalPath)
        assertArrayEquals(png, file.readBytes())
        assertFalse(File(h.folder, "clipboard.png").exists())
        assertTrue(h.history().isEmpty())
        assertTrue(h.active().isEmpty())
    }

    @Test fun oversizedClipboardOfferIsRejectedAsInvalid() {
        h.start()
        h.deliver(Message.TransferOffer(TransferOfferBody("c1", "clipboard.png", ClipboardLimits.MAX_IMAGE_BYTES + 1, "image/png", TransferPurpose.Clipboard)))
        eventually { h.sentOf<Message.TransferReject>().any { it.body == TransferRejectBody("c1", TransferRejectReason.Invalid) } }
        assertTrue(h.history().isEmpty())
    }

    @Test fun enqueuedClipboardImageIsOfferedWithThePurposeAndHiddenFromFiles() {
        h.start()
        val file = File(h.dir, "shot.png").apply { writeBytes(testBytes(2_000)) }
        val id = h.engine.enqueueClipboard(OutgoingFile(file.path, "clipboard.png", file.length(), "image/png"))
        eventually { h.sentOf<Message.TransferOffer>().any { it.body.transferId == id && it.body.purpose == TransferPurpose.Clipboard } }
        assertTrue(h.engine.snapshot.value.files().active.isEmpty())
        assertEquals(1, h.engine.snapshot.value.active.size)
    }
    // Final review Important 1: newer clipboard content must win.
    @Test fun aNewClipboardImageCancelsTheOlderOne() {
        h.start()
        val a = File(h.dir, "a.png").apply { writeBytes(testBytes(100)) }
        val b = File(h.dir, "b.png").apply { writeBytes(testBytes(200)) }
        val first = h.engine.enqueueClipboard(OutgoingFile(a.path, "clipboard.png", a.length(), "image/png"))
        eventually { h.sentOf<Message.TransferOffer>().any { it.body.transferId == first } }
        h.engine.enqueueClipboard(OutgoingFile(b.path, "clipboard.png", b.length(), "image/png"))
        eventually { h.sentOf<Message.TransferCancel>().any { it.body.transferId == first } }
    }

    @Test fun cancellingClipboardTransfersLeavesFilesAlone() {
        h.start()
        val img = File(h.dir, "a.png").apply { writeBytes(testBytes(100)) }
        val doc = File(h.dir, "doc.txt").apply { writeBytes(testBytes(100)) }
        val file = h.engine.enqueue(listOf(OutgoingFile(doc.path, doc.name, doc.length(), "text/plain"))).single()
        val clip = h.engine.enqueueClipboard(OutgoingFile(img.path, "clipboard.png", img.length(), "image/png"))
        eventually { h.sentOf<Message.TransferOffer>().size == 2 }
        h.engine.cancelClipboard()
        eventually { h.sentOf<Message.TransferCancel>().any { it.body.transferId == clip } }
        Thread.sleep(100)
        assertTrue(h.sentOf<Message.TransferCancel>().none { it.body.transferId == file })
        assertEquals(listOf(file), h.active().map { it.id })
    }

    @Test fun disconnectingCancelsOutgoingClipboardTransfers() {
        h.start()
        val img = File(h.dir, "a.png").apply { writeBytes(testBytes(100)) }
        val id = h.engine.enqueueClipboard(OutgoingFile(img.path, "clipboard.png", img.length(), "image/png"))
        eventually { h.sentOf<Message.TransferOffer>().any { it.body.transferId == id } }
        h.online = false
        h.connected.value = false
        eventually { h.active().isEmpty() }
        h.online = true
        h.connected.value = true
        eventually { h.sentOf<Message.TransferCancel>().any { it.body.transferId == id } }
        assertEquals(1, h.sentOf<Message.TransferOffer>().count { it.body.transferId == id })
    }

    @Test fun clipboardTransfersAreNotRestoredAfterARestart() {
        val record = TransferRecord(id = "c1", direction = Direction.ToMac, name = "clipboard.png", size = 10, mime = "image/png",
            phase = Phase.Active, source = "/x.png", createdAt = h.now, updatedAt = h.now, purpose = TransferPurpose.Clipboard)
        val harness = EngineHarness(store = InMemoryTransferStore(StoredTransfers(active = listOf(record))))
        try {
            harness.start()
            Thread.sleep(200)
            assertTrue(harness.active().isEmpty())
            assertTrue(harness.sentOf<Message.TransferOffer>().isEmpty())
        } finally {
            harness.close()
        }
    }
}
