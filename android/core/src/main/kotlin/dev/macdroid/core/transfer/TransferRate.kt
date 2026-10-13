package dev.macdroid.core.transfer

/** Smoothed speed from (bytes so far, monotonic time) samples, sampled at most twice a second. Not thread-safe. */
class TransferRate {
    private var lastBytes = 0L
    private var lastNanos = 0L
    private var started = false

    var bytesPerSecond: Double? = null
        private set

    fun record(bytes: Long, atNanos: Long) {
        if (!started) {
            started = true
            lastBytes = bytes
            lastNanos = atNanos
            return
        }
        val elapsed = (atNanos - lastNanos) / 1e9
        if (elapsed < 0.5) return
        val instant = (bytes - lastBytes) / elapsed
        bytesPerSecond = bytesPerSecond?.let { 0.7 * it + 0.3 * instant } ?: instant
        lastBytes = bytes
        lastNanos = atNanos
    }
}
