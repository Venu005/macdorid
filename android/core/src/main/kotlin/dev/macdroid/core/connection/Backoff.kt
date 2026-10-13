package dev.macdroid.core.connection

/** Exponential backoff: initial, ×2 each step, capped. Used only from the connection loop. */
class Backoff(private val initialMs: Long = 1_000, private val maxMs: Long = 30_000) {
    private var current = initialMs

    fun next(): Long {
        val value = current
        current = (current * 2).coerceAtMost(maxMs)
        return value
    }

    fun reset() {
        current = initialMs
    }
}
