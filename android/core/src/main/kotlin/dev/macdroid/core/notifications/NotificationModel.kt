package dev.macdroid.core.notifications

import dev.macdroid.core.protocol.NotificationButton
import kotlinx.serialization.Serializable

/** A phone notification, already read out of Android's StatusBarNotification by the app. */
data class PhoneNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** The buttons, without the reply button; [NotificationButton.index] is the index in `Notification.actions`. */
    val actions: List<NotificationButton>,
    val canReply: Boolean,
    /** `FLAG_ONGOING_EVENT` or `FLAG_FOREGROUND_SERVICE`. */
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
    /** The channel's importance is `IMPORTANCE_MIN` or lower. */
    val minImportance: Boolean = false,
)

/** An app icon as sent to the Mac: [hash] is the first 16 lowercase hex chars of the PNG's SHA-256. */
data class AppIcon(val hash: String, val pngBase64: String)

/** An app that posted a forwardable notification, as listed in the Notifications tab. */
@Serializable
data class SeenApp(
    val packageName: String,
    val appName: String,
    val iconHash: String = "",
    val muted: Boolean = false,
    /** Epoch day that [count] belongs to. */
    val countDay: Long = 0,
    /** Distinct notifications on [countDay]. */
    val count: Int = 0,
)

@Serializable
data class NotificationSettings(val forwarding: Boolean = true, val apps: List<SeenApp> = emptyList())

/** Persists [NotificationSettings]. The app stores them in SharedPreferences. */
interface NotificationSettingsStore {
    fun load(): NotificationSettings
    fun save(settings: NotificationSettings)
}

class InMemoryNotificationSettingsStore(private var settings: NotificationSettings = NotificationSettings()) : NotificationSettingsStore {
    override fun load() = settings
    override fun save(settings: NotificationSettings) { this.settings = settings }
}

/** What the Mac asked the phone to do; the app carries it out with the notification listener. */
sealed interface NotificationCommand {
    val key: String
    data class Reply(override val key: String, val text: String) : NotificationCommand
    data class Action(override val key: String, val actionIndex: Int) : NotificationCommand
    data class Dismiss(override val key: String) : NotificationCommand
}

/** What the Notifications tab shows. */
data class NotificationsState(val forwarding: Boolean, val access: Boolean, val apps: List<SeenApp>)
