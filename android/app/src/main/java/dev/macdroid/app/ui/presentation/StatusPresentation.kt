package dev.macdroid.app.ui.presentation

import dev.macdroid.core.connection.ConnectionState

enum class StatusTone { Connected, Pending, Problem, Idle }

enum class StatusAction { None, RetryNow, PairAgain, Connect }

data class StatusModel(val title: String, val subtitle: String, val tone: StatusTone, val action: StatusAction)

/** One source of truth for how connection state reads, on Home and in the notification. */
object StatusPresentation {
    fun home(state: ConnectionState, macName: String, waitingRemainingMs: Long? = null): StatusModel = when (state) {
        is ConnectionState.Connected -> StatusModel(state.macName, "Connected", StatusTone.Connected, StatusAction.None)
        ConnectionState.Connecting -> StatusModel(macName, "Connecting…", StatusTone.Pending, StatusAction.None)
        is ConnectionState.Waiting -> {
            val remaining = waitingRemainingMs ?: state.retryInMs
            val subtitle = if (remaining > 0) "Looking for your Mac… retrying in ${seconds(remaining)} s" else "Reconnecting…"
            StatusModel(macName, subtitle, StatusTone.Pending, StatusAction.RetryNow)
        }
        is ConnectionState.TrustFailure ->
            StatusModel("Pairing problem", "Your Mac no longer recognises this phone.", StatusTone.Problem, StatusAction.PairAgain)
        ConnectionState.Stopped -> StatusModel(macName, "Disconnected", StatusTone.Idle, StatusAction.Connect)
        ConnectionState.NotPaired -> StatusModel("Not paired", "Pair with your Mac to get started.", StatusTone.Idle, StatusAction.None)
    }

    fun notificationTitle(state: ConnectionState): String = when (state) {
        ConnectionState.Stopped -> "Disconnected"
        ConnectionState.NotPaired -> "Not paired"
        ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Connected -> "Connected to ${state.macName}"
        is ConnectionState.Waiting -> "Mac not reachable — retrying in ${seconds(state.retryInMs)}s"
        is ConnectionState.TrustFailure -> "Pairing problem — open MacDroid to re-pair"
    }

    private fun seconds(ms: Long): Long = (ms + 999) / 1000
}
