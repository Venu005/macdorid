package dev.macdroid.app.platform.control

import android.content.Context
import dev.macdroid.app.BuildConfig

/** "Start mirroring without touching the phone" (spec §7); always off in store builds. */
object ControlPrefs {
    private const val PREFS = "control"
    private const val AUTO_START = "autoStart"

    fun autoStart(context: Context): Boolean =
        !BuildConfig.STORE_BUILD && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_START, true)

    fun setAutoStart(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_START, on).apply()
    }
}
