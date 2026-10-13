package dev.macdroid.app.platform.notifications

import android.content.Context
import dev.macdroid.core.notifications.NotificationSettings
import dev.macdroid.core.notifications.NotificationSettingsStore
import dev.macdroid.core.protocol.MessageCodec

/** The phone-owned notification settings (master switch, apps seen, mutes) as JSON in SharedPreferences. */
class SharedPrefsNotificationSettings(context: Context) : NotificationSettingsStore {
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)

    override fun load(): NotificationSettings = prefs.getString(KEY, null)
        ?.let { runCatching { MessageCodec.json.decodeFromString(NotificationSettings.serializer(), it) }.getOrNull() }
        ?: NotificationSettings()

    override fun save(settings: NotificationSettings) {
        prefs.edit().putString(KEY, MessageCodec.json.encodeToString(NotificationSettings.serializer(), settings)).apply()
    }

    private companion object {
        const val KEY = "settings"
    }
}
