package dev.macdroid.app.platform.calls

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.service.notification.StatusBarNotification
import dev.macdroid.core.calls.CallLabels
import dev.macdroid.core.calls.CallNotification
import dev.macdroid.core.calls.CallNumbers
import dev.macdroid.core.calls.CallType
import dev.macdroid.core.protocol.CallActionKind

/** Reads a phone app's call notification (Plan 10 spec §2.1). Null for anything else. */
object CallReader {
    fun read(sbn: StatusBarNotification): CallNotification? {
        val n = sbn.notification
        val type = callType(n) ?: return null
        val person = person(n)
        val name = person?.name?.toString()?.takeIf { it.isNotBlank() }
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val actions = CallActionKind.entries.filter { intent(n, it) != null }.toSet()
        return CallNotification(sbn.key, type, name, CallNumbers.fromUri(person?.uri), actions)
    }

    /** The notification's own intent for [kind]: the call-style extra, else a button with that label. */
    fun intent(n: Notification, kind: CallActionKind): PendingIntent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val key = when (kind) {
                CallActionKind.Answer -> Notification.EXTRA_ANSWER_INTENT
                CallActionKind.Decline -> Notification.EXTRA_DECLINE_INTENT
                CallActionKind.HangUp -> Notification.EXTRA_HANG_UP_INTENT
            }
            pendingIntent(n, key)?.let { return it }
        }
        return n.actions?.firstOrNull { CallLabels.kindOf(it.title?.toString()) == kind }?.actionIntent
    }

    private fun callType(n: Notification): CallType? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0)) {
                Notification.CallStyle.CALL_TYPE_INCOMING -> return CallType.Incoming
                Notification.CallStyle.CALL_TYPE_ONGOING -> return CallType.Ongoing
                Notification.CallStyle.CALL_TYPE_SCREENING -> return CallType.Screening
            }
        }
        if (n.category != Notification.CATEGORY_CALL) return null
        val ongoing = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
        return CallLabels.legacyType(ongoing, n.actions.orEmpty().map { it.title?.toString() })
    }

    @Suppress("DEPRECATION")
    private fun person(n: Notification): Person? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> n.extras.getParcelable(Notification.EXTRA_CALL_PERSON)
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun pendingIntent(n: Notification, key: String): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) n.extras.getParcelable(key, PendingIntent::class.java)
        else n.extras.getParcelable(key)
}
