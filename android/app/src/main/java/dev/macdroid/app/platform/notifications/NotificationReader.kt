package dev.macdroid.app.platform.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dev.macdroid.core.notifications.PhoneNotification
import dev.macdroid.core.protocol.NotificationButton

/** Reads a StatusBarNotification into the pure [PhoneNotification] the forwarder understands (spec §3.1, §8.5). */
object NotificationReader {
    fun read(context: Context, sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?): PhoneNotification {
        val n = sbn.notification
        val extras = n.extras
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages
        val (title, text) = NotificationText.pick(
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE),
            messages?.lastOrNull()?.text,
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        val buttons = n.actions.orEmpty().withIndex()
            .filter { (_, action) -> action.remoteInputs.isNullOrEmpty() && !action.title.isNullOrBlank() }
            .map { (index, action) -> NotificationButton(index, action.title.toString()) }
        val importance = ranking?.importance ?: NotificationManager.IMPORTANCE_UNSPECIFIED
        return PhoneNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName(context, sbn.packageName),
            title = title,
            text = text,
            postedAt = sbn.postTime,
            actions = buttons,
            canReply = replyAction(n) != null,
            ongoing = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0,
            groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            minImportance = importance != NotificationManager.IMPORTANCE_UNSPECIFIED && importance <= NotificationManager.IMPORTANCE_MIN,
        )
    }

    /** The action with a free-form reply box, if any. */
    fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true }

    private fun appName(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
