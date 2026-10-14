package dev.macdroid.core.transfer

import dev.macdroid.core.eventually
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.TransferPurpose
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferEnginePhotoTest {
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    @Test fun enqueueWithIdUsesTheMacsIdAndPurpose() {
        h.start()
        val file = File(h.dir, "IMG.jpg").apply { writeBytes(testBytes(500)) }
        assertTrue(h.engine.enqueueWithId("7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", OutgoingFile(file.path, "IMG.jpg", 500, "image/jpeg"), TransferPurpose.Photo))
        eventually { h.sentOf<Message.TransferOffer>().any { it.body.transferId == "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f" && it.body.purpose == TransferPurpose.Photo } }
        assertTrue(h.engine.snapshot.value.files().active.isEmpty()) // hidden from the phone's Files UI
    }

    @Test fun aRepeatedIdIsIgnored() {
        h.start()
        val file = File(h.dir, "IMG.jpg").apply { writeBytes(testBytes(500)) }
        val outgoing = OutgoingFile(file.path, "IMG.jpg", 500, "image/jpeg")
        assertTrue(h.engine.enqueueWithId("dup", outgoing, TransferPurpose.Photo))
        eventually { h.engine.snapshot.value.active.any { it.id == "dup" } }
        assertFalse(h.engine.enqueueWithId("dup", outgoing, TransferPurpose.Photo))
        Thread.sleep(100)
        assertEquals(1, h.sentOf<Message.TransferOffer>().count { it.body.transferId == "dup" })
    }

    // Follow-up M8: a phone busy sending files still answers the Mac's photo fetches (the Mac gives up after 30 s).
    @Test fun photosHaveTheirOwnSlots() {
        h.start()
        val files = (1..4).map { n -> File(h.dir, "f$n.bin").apply { writeBytes(testBytes(100)) } }
        h.engine.enqueue(files.map { OutgoingFile(it.path, it.name, 100, "application/octet-stream") })
        eventually { h.sentOf<Message.TransferOffer>().size == 3 } // the 4th file waits: the Mac hasn't accepted any
        val photo = File(h.dir, "IMG.jpg").apply { writeBytes(testBytes(500)) }
        assertTrue(h.engine.enqueueWithId("p1", OutgoingFile(photo.path, "IMG.jpg", 500, "image/jpeg"), TransferPurpose.Photo))
        eventually { h.sentOf<Message.TransferOffer>().any { it.body.transferId == "p1" } }
        Thread.sleep(100)
        assertEquals(4, h.sentOf<Message.TransferOffer>().size)
    }
}
