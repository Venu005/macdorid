package dev.macdroid.app

import dev.macdroid.app.ui.presentation.ShareDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareDecisionTest {
    @Test fun connectedSends() = assertEquals(ShareDecision.Send, ShareDecision.decide(paired = true, connected = true, count = 2))

    // Review Focus #2
    @Test fun notConnectedAsksToConnectFirst() {
        assertEquals(ShareDecision.ConnectFirst, ShareDecision.decide(paired = true, connected = false, count = 1))
        assertEquals(ShareDecision.ConnectFirst, ShareDecision.decide(paired = false, connected = false, count = 1))
    }

    @Test fun nothingSharedDoesNothing() = assertEquals(ShareDecision.Nothing, ShareDecision.decide(paired = true, connected = true, count = 0))

    @Test fun toastText() {
        assertEquals("Sending 1 file to your Mac", ShareDecision.sendingText(1))
        assertEquals("Sending 3 files to your Mac", ShareDecision.sendingText(3))
    }
}
