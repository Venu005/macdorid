package dev.macdroid.app

import dev.macdroid.app.ui.presentation.StatusAction
import dev.macdroid.app.ui.presentation.StatusModel
import dev.macdroid.app.ui.presentation.StatusPresentation
import dev.macdroid.app.ui.presentation.StatusTone
import dev.macdroid.core.connection.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusPresentationTest {
    @Test fun connectedUsesTheNameTheMacSent() {
        assertEquals(
            StatusModel("Studio", "Connected", StatusTone.Connected, StatusAction.None),
            StatusPresentation.home(ConnectionState.Connected("Studio"), "Old name"),
        )
    }

    @Test fun waitingShowsLiveCountdownAndRetry() {
        assertEquals(
            StatusModel("Studio", "Looking for your Mac… retrying in 8 s", StatusTone.Pending, StatusAction.RetryNow),
            StatusPresentation.home(ConnectionState.Waiting(30_000), "Studio", waitingRemainingMs = 7_200),
        )
    }

    @Test fun waitingWithoutElapsedTimeUsesTheFullDelay() {
        assertEquals("Looking for your Mac… retrying in 2 s", StatusPresentation.home(ConnectionState.Waiting(1_500), "Studio").subtitle)
    }

    // Review Focus #3
    @Test fun waitingCountdownNeverNegative() {
        assertEquals("Reconnecting…", StatusPresentation.home(ConnectionState.Waiting(1_000), "Studio", waitingRemainingMs = 0).subtitle)
        assertEquals("Reconnecting…", StatusPresentation.home(ConnectionState.Waiting(1_000), "Studio", waitingRemainingMs = -500).subtitle)
    }

    @Test fun connectingHasNoAction() {
        assertEquals(
            StatusModel("Studio", "Connecting…", StatusTone.Pending, StatusAction.None),
            StatusPresentation.home(ConnectionState.Connecting, "Studio"),
        )
    }

    @Test fun trustFailureOffersPairAgain() {
        assertEquals(
            StatusModel("Pairing problem", "Your Mac no longer recognises this phone.", StatusTone.Problem, StatusAction.PairAgain),
            StatusPresentation.home(ConnectionState.TrustFailure("x"), "Studio"),
        )
    }

    @Test fun stoppedOffersConnect() {
        assertEquals(
            StatusModel("Studio", "Disconnected", StatusTone.Idle, StatusAction.Connect),
            StatusPresentation.home(ConnectionState.Stopped, "Studio"),
        )
    }

    @Test fun notificationTitlesAreUnchanged() {
        assertEquals("Connected to Studio", StatusPresentation.notificationTitle(ConnectionState.Connected("Studio")))
        assertEquals("Mac not reachable — retrying in 2s", StatusPresentation.notificationTitle(ConnectionState.Waiting(1_500)))
    }
}
