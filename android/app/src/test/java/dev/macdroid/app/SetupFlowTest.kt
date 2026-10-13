package dev.macdroid.app

import dev.macdroid.app.ui.setup.SetupFlow
import dev.macdroid.app.ui.setup.SetupInputs
import dev.macdroid.app.ui.setup.SetupStep
import dev.macdroid.app.ui.setup.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFlowTest {
    private fun inputs(
        setupDone: Boolean = false,
        welcomeDone: Boolean = false,
        needsLocalNetwork: Boolean = true,
        hasLocalNetwork: Boolean = false,
        paired: Boolean = false,
        needsNotifications: Boolean = true,
        hasNotifications: Boolean = false,
        notificationsSkipped: Boolean = false,
        batteryDone: Boolean = false,
        filesStepDone: Boolean = false,
    ) = SetupInputs(setupDone, welcomeDone, needsLocalNetwork, hasLocalNetwork, paired, needsNotifications, hasNotifications, notificationsSkipped, batteryDone, filesStepDone)

    @Test fun firstRunShowsEveryStepInOrder() {
        assertEquals(
            listOf(SetupStep.Welcome, SetupStep.LocalNetwork, SetupStep.PairMac, SetupStep.Notifications, SetupStep.Files, SetupStep.Battery),
            SetupFlow.steps(inputs()),
        )
        assertEquals(SetupStep.Welcome, SetupFlow.current(inputs()))
    }

    @Test fun firstRunSkipsPermissionsTheOsDoesNotHave() {
        assertEquals(
            listOf(SetupStep.Welcome, SetupStep.PairMac, SetupStep.Files, SetupStep.Battery),
            SetupFlow.steps(inputs(needsLocalNetwork = false, needsNotifications = false)),
        )
    }

    @Test fun advancesAsEachStepCompletes() {
        assertEquals(SetupStep.LocalNetwork, SetupFlow.current(inputs(welcomeDone = true)))
        assertEquals(SetupStep.PairMac, SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true)))
        assertEquals(SetupStep.Notifications, SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true, paired = true)))
        assertEquals(SetupStep.Files, SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true, paired = true, notificationsSkipped = true)))
        assertEquals(SetupStep.Battery, SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true, paired = true, notificationsSkipped = true, filesStepDone = true)))
        assertNull(SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true, paired = true, hasNotifications = true, batteryDone = true, filesStepDone = true)))
    }

    @Test fun progressPosition() {
        val state = SetupUiState.from(inputs(welcomeDone = true, hasLocalNetwork = true), keepConnected = true)
        assertEquals(SetupStep.PairMac, state.step)
        assertEquals(3, state.position)
        assertEquals(6, state.total)
        assertTrue(state.firstRun)
    }

    // Review Focus #1
    @Test fun upgradedUserGoesStraightToMain() {
        assertNull(SetupFlow.current(inputs(setupDone = true, hasLocalNetwork = true, paired = true)))
    }

    // Review Focus #2
    @Test fun revokedLocalNetworkRoutesToThatStepOnly() {
        val revoked = inputs(setupDone = true, hasLocalNetwork = false, paired = true)
        assertEquals(listOf(SetupStep.LocalNetwork), SetupFlow.steps(revoked))
        assertEquals(SetupStep.LocalNetwork, SetupFlow.current(revoked))
        assertFalse(SetupUiState.from(revoked, keepConnected = true).firstRun)
    }

    // Review Focus #2
    @Test fun revokedNotificationsDoNotForceSetup() {
        assertNull(SetupFlow.current(inputs(setupDone = true, hasLocalNetwork = true, paired = true, hasNotifications = false)))
    }

    @Test fun forgottenMacAfterSetupNeedsOnlyPairing() {
        assertEquals(listOf(SetupStep.PairMac), SetupFlow.steps(inputs(setupDone = true, hasLocalNetwork = true, paired = false)))
    }

    @Test fun readyToConnectNeedsPairingPermissionAndPreference() {
        val ready = inputs(setupDone = true, hasLocalNetwork = true, paired = true)
        assertTrue(SetupUiState.from(ready, keepConnected = true).readyToConnect)
        assertFalse(SetupUiState.from(ready, keepConnected = false).readyToConnect)
        assertFalse(SetupUiState.from(ready.copy(hasLocalNetwork = false), keepConnected = true).readyToConnect)
        assertFalse(SetupUiState.from(ready.copy(paired = false), keepConnected = true).readyToConnect)
    }

    @Test fun filesStepIsFirstRunOnly() {
        assertNull(SetupFlow.current(inputs(setupDone = true, hasLocalNetwork = true, paired = true, filesStepDone = false)))
    }
}
