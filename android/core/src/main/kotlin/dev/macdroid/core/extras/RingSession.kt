package dev.macdroid.core.extras

enum class StopReason { Tapped, VolumeKey, Mac, Timeout, Disconnected }

/** Ring-my-phone state (spec §3.1): one ring at a time (the 30 s auto-stop is the Ringer's timer). Not thread-safe; use from one thread. */
class RingSession {
    var ringing: Boolean = false
        private set

    fun start(): Boolean {
        if (ringing) return false
        ringing = true
        return true
    }

    fun stop(): Boolean {
        if (!ringing) return false
        ringing = false
        return true
    }
}
