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
}
