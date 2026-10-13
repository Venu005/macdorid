package dev.macdroid.app.ui.setup

enum class SetupStep { Welcome, LocalNetwork, PairMac, Notifications, Files, Battery }

data class SetupInputs(
    val setupDone: Boolean,
    val welcomeDone: Boolean,
    val needsLocalNetwork: Boolean,
    val hasLocalNetwork: Boolean,
    val paired: Boolean,
    val needsNotifications: Boolean,
    val hasNotifications: Boolean,
    val notificationsSkipped: Boolean,
    val batteryDone: Boolean,
    /** The optional first-run step "Where should files from your Mac go?" was answered. */
    val filesStepDone: Boolean = false,
)

/**
 * Which setup steps to show. First run walks every step; after setup only what's required to
 * work again (local network permission, pairing) is shown. Notifications never force setup.
 */
object SetupFlow {
    fun steps(i: SetupInputs): List<SetupStep> =
        if (i.setupDone) {
            buildList {
                if (i.needsLocalNetwork && !i.hasLocalNetwork) add(SetupStep.LocalNetwork)
                if (!i.paired) add(SetupStep.PairMac)
            }
        } else {
            buildList {
                add(SetupStep.Welcome)
                if (i.needsLocalNetwork) add(SetupStep.LocalNetwork)
                add(SetupStep.PairMac)
                if (i.needsNotifications) add(SetupStep.Notifications)
                add(SetupStep.Files)
                add(SetupStep.Battery)
            }
        }

    fun current(i: SetupInputs): SetupStep? = steps(i).firstOrNull { !isDone(it, i) }

    private fun isDone(step: SetupStep, i: SetupInputs): Boolean = when (step) {
        SetupStep.Welcome -> i.welcomeDone
        SetupStep.LocalNetwork -> !i.needsLocalNetwork || i.hasLocalNetwork
        SetupStep.PairMac -> i.paired
        SetupStep.Notifications -> !i.needsNotifications || i.hasNotifications || i.notificationsSkipped
        SetupStep.Files -> i.filesStepDone || i.setupDone
        SetupStep.Battery -> i.batteryDone || i.setupDone
    }
}

data class SetupUiState(
    val step: SetupStep?,
    /** 1-based position of [step] among the steps shown in this run. */
    val position: Int,
    val total: Int,
    val firstRun: Boolean,
    /** Paired, allowed on the local network, and the user wants to stay connected. */
    val readyToConnect: Boolean,
) {
    companion object {
        fun from(i: SetupInputs, keepConnected: Boolean): SetupUiState {
            val steps = SetupFlow.steps(i)
            val step = SetupFlow.current(i)
            return SetupUiState(
                step = step,
                position = if (step == null) steps.size else steps.indexOf(step) + 1,
                total = steps.size,
                firstRun = !i.setupDone,
                readyToConnect = i.paired && (!i.needsLocalNetwork || i.hasLocalNetwork) && keepConnected,
            )
        }
    }
}
