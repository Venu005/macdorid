package dev.macdroid.app.ui.presentation

/** What the share target does (spec §4.2): send silently when connected, otherwise ask to connect first. */
enum class ShareDecision {
    Send, ConnectFirst, Nothing;

    companion object {
        fun decide(paired: Boolean, connected: Boolean, count: Int): ShareDecision = when {
            count == 0 -> Nothing
            !paired || !connected -> ConnectFirst
            else -> Send
        }

        fun sendingText(count: Int) = if (count == 1) "Sending 1 file to your Mac" else "Sending $count files to your Mac"
    }
}
