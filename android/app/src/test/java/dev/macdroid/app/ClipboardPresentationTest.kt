package dev.macdroid.app

import dev.macdroid.app.ui.presentation.ClipboardPresentation
import dev.macdroid.core.clipboard.ClipItem
import dev.macdroid.core.clipboard.SendResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardPresentationTest {
    @Test fun toasts() {
        assertEquals("Sent to Mac", ClipboardPresentation.toast(SendResult.Sent))
        assertEquals("Not connected to your Mac", ClipboardPresentation.toast(SendResult.NotConnected))
        assertEquals("Clipboard is empty", ClipboardPresentation.toast(SendResult.Empty))
        assertEquals("Too large to send", ClipboardPresentation.toast(SendResult.TooLarge))
        assertEquals("Update MacDroid on your Mac to use this", ClipboardPresentation.toast(SendResult.MacTooOld))
    }

    @Test fun previewsAndSubtitles() {
        val now = 10_000_000L
        val text = ClipItem("a", ClipItem.Kind.Text, "Meeting moved\nto 4:30", null, ClipItem.Direction.ToMac, now - 30_000)
        assertEquals("Meeting moved to 4:30", ClipboardPresentation.preview(text))
        assertEquals("→ Mac · just now", ClipboardPresentation.subtitle(text, now))
        val image = ClipItem("b", ClipItem.Kind.Image, null, "file:/x.png", ClipItem.Direction.FromMac, now - 2 * 3_600_000)
        assertEquals("Image", ClipboardPresentation.preview(image))
        assertEquals("← from Mac · 2 h ago", ClipboardPresentation.subtitle(image, now))
        assertEquals(80, ClipboardPresentation.preview(text.copy(text = "x".repeat(200))).length)
    }
}
