package dev.macdroid.app.ui.presentation

import dev.macdroid.core.clipboard.ClipItem
import dev.macdroid.core.clipboard.SendResult

/** Text for the Clipboard tab and the send toasts (spec §4.2). */
object ClipboardPresentation {
    fun toast(result: SendResult): String = when (result) {
        SendResult.Sent -> "Sent to Mac"
        SendResult.NotConnected -> "Not connected to your Mac"
        SendResult.Empty -> "Clipboard is empty"
        SendResult.TooLarge -> "Too large to send"
        SendResult.MacTooOld -> "Update MacDroid on your Mac to use this"
    }

    fun preview(item: ClipItem): String {
        if (item.kind == ClipItem.Kind.Image) return "Image"
        val flat = item.text.orEmpty().lines().joinToString(" ") { it.trim() }.trim()
        return if (flat.length <= 80) flat else flat.take(79) + "…"
    }

    fun subtitle(item: ClipItem, now: Long): String {
        val direction = if (item.direction == ClipItem.Direction.ToMac) "→ Mac" else "← from Mac"
        return "$direction · ${relative(now - item.at)}"
    }

    private fun relative(ms: Long): String = when {
        ms < 60_000 -> "just now"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        ms < 86_400_000 -> "${ms / 3_600_000} h ago"
        else -> "${ms / 86_400_000} d ago"
    }
}
