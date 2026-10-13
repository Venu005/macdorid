package dev.macdroid.app.ui.presentation

import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.extras.LinkExtractor
import dev.macdroid.core.protocol.Features

/** "Share → Open on Mac" (spec §3.2): what to send, and what to tell the user. */
object OpenOnMacDecision {
    data class Decision(val url: String?, val toast: String)

    fun decide(text: String?, state: ConnectionState): Decision {
        val link = LinkExtractor.firstLink(text) ?: return Decision(null, "No link to open")
        return when {
            state !is ConnectionState.Connected -> Decision(null, "Not connected to your Mac")
            !state.macSupports(Features.LINKS) -> Decision(null, "Update MacDroid on your Mac to use this")
            else -> Decision(link, "Opening on your Mac")
        }
    }
}
