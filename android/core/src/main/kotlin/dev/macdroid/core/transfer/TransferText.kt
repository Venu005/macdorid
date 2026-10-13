package dev.macdroid.core.transfer

import java.util.Locale
import kotlin.math.ceil

/** "1.3 of 2.1 GB · 48 MB/s · 17 s left" (decimal units). Mirrors the Swift version; vectors: `transfer-text.json`. */
object TransferText {
    private class Unit(val divisor: Double, val label: String)

    fun bytes(count: Long): String {
        val unit = unitFor(count) ?: return "$count bytes"
        return "${oneDecimal(count / unit.divisor)} ${unit.label}"
    }

    fun line(done: Long, total: Long, bytesPerSecond: Double?): String {
        val parts = mutableListOf(progress(done, total))
        if (bytesPerSecond != null && bytesPerSecond > 0) {
            parts += speed(bytesPerSecond)
            val remaining = total - done
            if (remaining > 0) parts += timeLeft(remaining / bytesPerSecond)
        }
        return parts.joinToString(" · ")
    }

    private fun progress(done: Long, total: Long): String {
        val unit = unitFor(total) ?: return "$done of $total bytes"
        return "${oneDecimal(done / unit.divisor)} of ${oneDecimal(total / unit.divisor)} ${unit.label}"
    }

    private fun speed(rate: Double): String = when {
        rate >= 1_000_000 -> (rate / 1_000_000).let { if (it >= 10) "${noDecimals(it)} MB/s" else "${oneDecimal(it)} MB/s" }
        rate >= 1_000 -> "${noDecimals(rate / 1_000)} KB/s"
        else -> "${rate.toLong()} bytes/s"
    }

    private fun timeLeft(seconds: Double): String {
        val total = ceil(seconds).toLong()
        return when {
            total < 60 -> "$total s left"
            total < 3600 -> "${ceil(total / 60.0).toLong()} min left"
            else -> "${total / 3600} h ${ceil((total % 3600) / 60.0).toLong()} min left"
        }
    }

    private fun unitFor(count: Long): Unit? = when {
        count >= 1_000_000_000 -> Unit(1e9, "GB")
        count >= 1_000_000 -> Unit(1e6, "MB")
        count >= 1_000 -> Unit(1e3, "KB")
        else -> null
    }

    private fun oneDecimal(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    private fun noDecimals(value: Double) = String.format(Locale.ROOT, "%.0f", value)
}
