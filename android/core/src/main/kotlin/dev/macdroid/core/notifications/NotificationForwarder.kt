package dev.macdroid.core.notifications

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.NotificationAppsBody
import dev.macdroid.core.protocol.NotificationErrorBody
import dev.macdroid.core.protocol.NotificationErrorReason
import dev.macdroid.core.protocol.NotificationIconBody
import dev.macdroid.core.protocol.NotificationKeyBody
import dev.macdroid.core.protocol.NotificationPostedBody
import dev.macdroid.core.protocol.NotificationSyncBody
import dev.macdroid.core.protocol.PhoneAppBody
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides which phone notifications go to the Mac and keeps the phone-owned settings (spec §2.2, §3.1, §8).
 * Thread-safe: the listener, the connection and the UI call in from different threads.
 */
class NotificationForwarder(
    private val ownPackage: String,
    private val store: NotificationSettingsStore,
    private val send: (Message) -> Boolean,
    private val iconFor: (String) -> AppIcon?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val lock = Any()
    private var settings = store.load()
    private var access = false
    private var connected = false
    /** Forwarded keys (in memory for the listener's lifetime, spec §8.3) with what was last sent for each (§8.4). */
    private val forwarded = LinkedHashMap<String, NotificationPostedBody>()
    private val iconsSent = HashSet<String>()
    /** Keys counted toward today's per-app count, so updates of one notification count once. */
    private val counted = HashSet<String>()
    private val _state = MutableStateFlow(NotificationsState(settings.forwarding, access, settings.apps))
    val state: StateFlow<NotificationsState> = _state.asStateFlow()

    /** The Mac connected (and supports notifications). [activeKeys] are the notifications showing on the phone now. */
    fun connected(activeKeys: Set<String>) {
        synchronized(lock) {
            connected = true
            iconsSent.clear()
            forwarded.keys.retainAll(activeKeys)
            sendApps()
            send(Message.NotificationSync(NotificationSyncBody(forwarded.keys.toList())))
        }
    }

    fun disconnected() {
        synchronized(lock) { connected = false }
    }

    fun accessChanged(granted: Boolean) {
        synchronized(lock) {
            if (granted == access) return
            access = granted
            publish()
            sendApps()
        }
    }

    fun posted(notification: PhoneNotification) {
        if (NotificationRules.excluded(notification, ownPackage)) return
        val icon = iconFor(notification.packageName) // may render a bitmap; done outside the lock
        synchronized(lock) {
            val app = remember(notification, icon?.hash.orEmpty())
            if (!connected || !settings.forwarding || app.muted) return
            val body = NotificationPostedBody(
                key = notification.key,
                packageName = notification.packageName,
                appName = notification.appName,
                title = NotificationRules.clip(notification.title, NotificationRules.TITLE_LIMIT),
                text = NotificationRules.clip(notification.text, NotificationRules.TEXT_LIMIT),
                postedAt = notification.postedAt,
                actions = notification.actions.take(NotificationRules.BUTTON_LIMIT),
                canReply = notification.canReply,
                iconHash = app.iconHash,
                isUpdate = notification.key in forwarded,
            )
            val previous = forwarded[notification.key]
            if (previous != null && previous.sameContent(body)) return
            if (icon != null) sendIcon(icon)
            if (send(Message.NotificationPosted(body))) forwarded[notification.key] = body
        }
    }

    fun removed(key: String) {
        synchronized(lock) {
            if (forwarded.remove(key) != null && connected) send(Message.NotificationRemoved(NotificationKeyBody(key)))
        }
    }

    fun setForwarding(on: Boolean) {
        synchronized(lock) {
            if (settings.forwarding == on) return
            settings = settings.copy(forwarding = on)
            save()
            sendApps()
        }
    }

    fun setMuted(packageName: String, muted: Boolean) {
        synchronized(lock) {
            val app = settings.apps.firstOrNull { it.packageName == packageName } ?: return
            if (app.muted != muted) {
                settings = settings.copy(apps = settings.apps.map { if (it.packageName == packageName) it.copy(muted = muted) else it })
                save()
            }
            sendApps() // always confirm, so the Mac's switch settles on the phone's truth
        }
    }

    /** A message from the Mac. Returns what the app must do on the phone, if anything. */
    fun handle(message: Message): NotificationCommand? = when (message) {
        is Message.NotificationMute -> {
            setMuted(message.body.packageName, message.body.muted)
            null
        }
        is Message.NotificationReply -> NotificationCommand.Reply(message.body.key, message.body.text)
        is Message.NotificationAction -> NotificationCommand.Action(message.body.key, message.body.actionIndex)
        is Message.NotificationDismiss -> NotificationCommand.Dismiss(message.body.key)
        else -> null
    }

    fun reportError(key: String, reason: NotificationErrorReason) {
        send(Message.NotificationError(NotificationErrorBody(key, reason)))
    }

    private fun remember(n: PhoneNotification, iconHash: String): SeenApp {
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toEpochDay()
        val existing = settings.apps.firstOrNull { it.packageName == n.packageName }
        val firstTime = counted.add(n.key)
        val base = if (existing?.countDay == today) existing.count else 0
        val app = SeenApp(
            packageName = n.packageName,
            appName = n.appName,
            iconHash = iconHash.ifEmpty { existing?.iconHash.orEmpty() },
            muted = existing?.muted ?: false,
            countDay = today,
            count = if (firstTime || base == 0) base + 1 else base,
        )
        if (app != existing) {
            settings = settings.copy(apps = (settings.apps.filterNot { it.packageName == n.packageName } + app).sortedBy { it.appName.lowercase() })
            save()
        }
        if (existing == null || existing.appName != app.appName || existing.iconHash != app.iconHash) sendApps()
        return app
    }

    private fun sendApps() {
        if (!connected) return
        for (app in settings.apps) {
            if (app.iconHash.isNotEmpty() && app.iconHash !in iconsSent) iconFor(app.packageName)?.let(::sendIcon)
        }
        val apps = settings.apps.map { PhoneAppBody(it.packageName, it.appName, it.iconHash, it.muted) }
        send(Message.NotificationApps(NotificationAppsBody(settings.forwarding, access, apps)))
    }

    private fun sendIcon(icon: AppIcon) {
        if (iconsSent.add(icon.hash)) send(Message.NotificationIcon(NotificationIconBody(icon.hash, icon.pngBase64)))
    }

    private fun save() {
        runCatching { store.save(settings) }
        publish()
    }

    private fun publish() {
        _state.value = NotificationsState(settings.forwarding, access, settings.apps)
    }
}

private fun NotificationPostedBody.sameContent(other: NotificationPostedBody) =
    title == other.title && text == other.text && actions == other.actions && canReply == other.canReply
