package dev.macdroid.app.ui.presentation

import dev.macdroid.core.notifications.NotificationsState
import dev.macdroid.core.notifications.SeenApp

/** Text for the Notifications tab (spec §3.3). */
object NotificationsPresentation {
    data class TopCard(val title: String, val subtitle: String, val showsSwitch: Boolean, val actionLabel: String?)

    const val FOOTNOTE = "Apps appear here after their first notification. Mutes sync with the Mac."
    const val OTP_NOTE = "Android hides one-time codes from apps like MacDroid, so login codes show without the code."

    fun topCard(state: NotificationsState, macName: String?, macSupports: Boolean, connected: Boolean): TopCard = when {
        !state.access -> TopCard(
            "Notification access is off",
            "Allow MacDroid to read notifications so it can show them on your Mac.",
            showsSwitch = false,
            actionLabel = "Turn on",
        )
        connected && !macSupports -> TopCard(
            "Update MacDroid on your Mac to use this",
            "The MacDroid on your Mac can't show phone notifications yet.",
            showsSwitch = false,
            actionLabel = null,
        )
        else -> TopCard(
            "Show on your Mac",
            if (state.forwarding) "Forwarding to ${macName ?: "your Mac"}" else "Off — notifications stay on this phone",
            showsSwitch = true,
            actionLabel = null,
        )
    }

    fun appSubtitle(app: SeenApp, today: Long): String = when {
        app.muted -> "Muted"
        app.countDay == today && app.count > 0 -> "${app.count} today"
        else -> "Shown on your Mac"
    }
}
