package dev.macdroid.core.clipboard

import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.protocol.ClipboardLimits
import dev.macdroid.core.protocol.ClipboardSetBody
import dev.macdroid.core.protocol.Features
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.transfer.OutgoingFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHubTest {
    private val sent = mutableListOf<Message>()
    private val images = mutableListOf<OutgoingFile>()
    private var receive = true
    private val hub = ClipboardHub({ sent.add(it); true }, { images.add(it) }, { receive }, clock = { 1_000L })
    private val connected = ConnectionState.Connected("Mac", Features.SUPPORTED.toSet())

    @Test fun sendsTextWhenConnectedAndRecordsIt() {
        assertEquals(SendResult.Sent, hub.sendText("hello", connected))
        assertEquals("hello", (sent.single() as Message.ClipboardSet).body.text)
        assertEquals(ClipItem.Direction.ToMac, hub.recent.value.single().direction)
    }

    @Test fun explainsWhyNothingWasSent() {
        assertEquals(SendResult.Empty, hub.sendText("", connected))
        assertEquals(SendResult.Empty, hub.sendText(null, connected))
        assertEquals(SendResult.NotConnected, hub.sendText("hi", ConnectionState.Connecting))
        assertEquals(SendResult.MacTooOld, hub.sendText("hi", ConnectionState.Connected("Mac", setOf(Features.FILES))))
        assertTrue(sent.isEmpty())
    }

    // Review Focus #3
    @Test fun textThatWouldOverflowTheFrameIsTooLarge() {
        assertEquals(SendResult.TooLarge, hub.sendText("\n".repeat(600_000), connected))
        assertTrue(ClipboardHub.fitsInAFrame("a".repeat(900_000)))
    }

    @Test fun receivedTextIsAppliedAndRecorded() {
        val apply = hub.handle(Message.ClipboardSet(ClipboardSetBody("x", text = "from mac")))
        assertEquals(ClipboardApply.Text("from mac"), apply)
        assertEquals(ClipItem.Direction.FromMac, hub.recent.value.single().direction)
    }

    @Test fun receivingSwitchedOffIgnoresTheMac() {
        receive = false
        assertNull(hub.handle(Message.ClipboardSet(ClipboardSetBody("x", text = "from mac"))))
        assertNull(hub.imageReceived("file:/x.png", "image/png"))
        assertTrue(hub.recent.value.isEmpty())
    }

    @Test fun recentKeepsTheLastTen() {
        repeat(12) { hub.sendText("item $it", connected) }
        assertEquals(10, hub.recent.value.size)
        assertEquals("item 11", hub.recent.value.first().text)
    }

    @Test fun imagesGoThroughTheTransferChannel() {
        val png = OutgoingFile("/cache/clipboard-out/a.png", "clipboard.png", 2_000, "image/png")
        assertEquals(SendResult.Sent, hub.sendImage(png, connected))
        assertEquals(listOf(png), images)
        assertEquals(ClipItem.Kind.Image, hub.recent.value.single().kind)
        assertEquals(SendResult.TooLarge, hub.sendImage(png.copy(size = ClipboardLimits.MAX_IMAGE_BYTES + 1), connected))
        assertEquals(SendResult.Empty, hub.sendImage(null, connected))
    }

    @Test fun linksAreRecognised() {
        assertEquals(ClipItem.Kind.Link, ClipboardHub.kindOf("https://github.com/Venu005/macdroid"))
        assertEquals(ClipItem.Kind.Text, ClipboardHub.kindOf("see https://example.com"))
    }
    // Final review Important 1: a text copy replaces an image still on its way.
    @Test fun sendingTextCancelsPendingImages() {
        var cancels = 0
        val hub = ClipboardHub({ true }, { }, { true }, clock = { 1L }, cancelImages = { cancels++ })
        assertEquals(SendResult.Sent, hub.sendText("newer", connected))
        assertEquals(1, cancels)
    }
}
