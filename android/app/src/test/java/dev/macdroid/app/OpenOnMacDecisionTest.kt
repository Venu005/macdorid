package dev.macdroid.app

import dev.macdroid.app.ui.presentation.OpenOnMacDecision
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.protocol.Features
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenOnMacDecisionTest {
    private val connected = ConnectionState.Connected("Mac", Features.SUPPORTED.toSet())

    @Test fun sendsTheFirstLink() {
        assertEquals(OpenOnMacDecision.Decision("https://x.com/a", "Opening on your Mac"),
            OpenOnMacDecision.decide("look (https://x.com/a).", connected))
    }

    @Test fun explainsWhyNot() {
        assertEquals(OpenOnMacDecision.Decision(null, "No link to open"), OpenOnMacDecision.decide("just words", connected))
        assertEquals(OpenOnMacDecision.Decision(null, "Not connected to your Mac"), OpenOnMacDecision.decide("https://x.com", ConnectionState.Connecting))
        assertEquals(OpenOnMacDecision.Decision(null, "Update MacDroid on your Mac to use this"),
            OpenOnMacDecision.decide("https://x.com", ConnectionState.Connected("Mac", setOf("files"))))
    }
}
