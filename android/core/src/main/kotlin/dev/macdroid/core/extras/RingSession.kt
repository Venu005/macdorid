package dev.macdroid.core.extras

enum class StopReason { Tapped, VolumeKey, Mac, Timeout, Disconnected }

/** Ring-my-phone state (spec §3.1): one ring at a time, auto-stop after [timeoutMs]. Not thread-safe; use from one thread. */
class RingSession(private val timeoutMs: Long = 30_000) {
    var ringing: Boolean = false
        private set
    private var startedAt = 0L

    fun start(now: Long): Boolean {
        if (ringing) return false
        ringing = true
        startedAt = now
        return true
    }

    fun stop(): Boolean {
        if (!ringing) return false
        ringing = false
        return true
    }

    fun timedOut(now: Long): Boolean = ringing && now - startedAt >= timeoutMs
}
