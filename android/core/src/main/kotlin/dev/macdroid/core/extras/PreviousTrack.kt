package dev.macdroid.core.extras

/**
 * Media apps treat "previous" after the first few seconds as "restart this track" (Media3's default is 3 s).
 * The Mac's ⏮ means the previous track, so past that window the app first jumps to the start, then goes back.
 */
object PreviousTrack {
    const val RESTART_WINDOW_MS = 3_000L

    fun needsRewindFirst(positionMs: Long, canSeek: Boolean): Boolean = canSeek && positionMs > RESTART_WINDOW_MS
}
