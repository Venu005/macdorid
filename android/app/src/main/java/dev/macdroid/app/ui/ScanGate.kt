package dev.macdroid.app.ui

/** Stops a QR code that is still in frame from re-triggering after it failed (invalid, rejected, unreachable). */
class ScanGate(private val suppressMs: Long = 5_000) {
    private var failedRaw: String? = null
    private var failedAt = 0L

    fun markFailed(raw: String, nowMs: Long) {
        failedRaw = raw
        failedAt = nowMs
    }

    fun shouldIgnore(raw: String, nowMs: Long): Boolean = raw == failedRaw && nowMs - failedAt < suppressMs
}
