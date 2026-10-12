package dev.macdroid.app

import dev.macdroid.app.service.StatusText
import dev.macdroid.core.connection.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTextTest {
    @Test fun titles() {
        assertEquals("Connected to Studio", StatusText.title(ConnectionState.Connected("Studio")))
        assertEquals("Mac not reachable — retrying in 2s", StatusText.title(ConnectionState.Waiting(1_500)))
        assertEquals("Connecting…", StatusText.title(ConnectionState.Connecting))
        assertEquals("Not paired", StatusText.title(ConnectionState.NotPaired))
        assertEquals("Disconnected", StatusText.title(ConnectionState.Stopped))
        assertEquals("Pairing problem — open MacDroid to re-pair", StatusText.title(ConnectionState.TrustFailure("x")))
    }
}
