package dev.macdroid.app.platform.mirror

import android.content.Context
import android.media.AudioManager

/**
 * The phone's media volume from before mirroring muted it, kept on disk so it comes back even if the app was killed
 * mid-session (final review I6). [restoreIfLeft] runs at app start.
 */
object MirrorVolume {
    private const val PREFS = "mirror"
    private const val KEY = "savedVolume"

    fun remember(context: Context, volume: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, volume).apply()
    }

    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun restoreIfLeft(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return
        context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, prefs.getInt(KEY, 0), 0)
        forget(context)
    }
}
