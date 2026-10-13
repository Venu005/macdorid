package dev.macdroid.app.ui.setup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.platform.Permissions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private data class Local(
        val setupDone: Boolean,
        val hasLocalNetwork: Boolean,
        val hasNotifications: Boolean,
        val notificationsSkipped: Boolean,
        val filesStepDone: Boolean,
    )

    private val graph = application.graph
    private val local = MutableStateFlow(readLocal())
    private val welcomeDone = MutableStateFlow(false)
    private val batteryDone = MutableStateFlow(false)
    private var sawUnpaired = graph.pairingStore.paired.value == null

    /** Pairing state, holding "paired" back for a moment after a fresh pairing so the success screen is seen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val paired: Flow<Boolean> = graph.pairingStore.paired
        .map { it != null }
        .distinctUntilChanged()
        .transformLatest { isPaired ->
            if (isPaired && sawUnpaired) delay(SUCCESS_PAUSE_MS)
            sawUnpaired = !isPaired
            // Pairing can change prefs, so re-read them with each pairing change.
            local.value = readLocal()
            emit(isPaired)
        }

    val macName: StateFlow<String?> = graph.pairingStore.paired
        .map { it?.name }
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.pairingStore.paired.value?.name)

    val state: StateFlow<SetupUiState> = combine(local, welcomeDone, batteryDone, paired, graph.uiPrefs.keepConnectedFlow) { l, welcome, battery, isPaired, keep ->
        SetupUiState.from(inputs(l, welcome, battery, isPaired), keep)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SetupUiState.from(inputs(local.value, false, false, graph.pairingStore.paired.value != null), graph.uiPrefs.keepConnected),
    )

    /** Re-read permissions and preferences (call on resume and after a permission prompt). */
    fun refresh() {
        local.value = readLocal()
    }

    fun completeWelcome() {
        welcomeDone.value = true
    }

    fun skipNotifications() {
        graph.uiPrefs.notificationsSkipped = true
        refresh()
    }

    /** "Where should files from your Mac go?": null keeps Download/MacDroid; a tree URI is a folder the user chose. */
    fun completeFiles(treeUri: Uri?) {
        val app = getApplication<Application>()
        if (treeUri != null) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                graph.filesPrefs.treeUri = treeUri.toString()
            }
        }
        graph.filesPrefs.filesStepDone = true
        refresh()
    }

    fun completeBattery() {
        graph.uiPrefs.setupDone = true
        batteryDone.value = true
        refresh()
    }

    private fun inputs(l: Local, welcome: Boolean, battery: Boolean, isPaired: Boolean) = SetupInputs(
        setupDone = l.setupDone,
        welcomeDone = welcome,
        needsLocalNetwork = Permissions.needsLocalNetwork,
        hasLocalNetwork = l.hasLocalNetwork,
        paired = isPaired,
        needsNotifications = Permissions.needsNotifications,
        hasNotifications = l.hasNotifications,
        notificationsSkipped = l.notificationsSkipped,
        batteryDone = battery,
        filesStepDone = l.filesStepDone,
    )

    private fun readLocal(): Local {
        val app = getApplication<Application>()
        return Local(
            setupDone = graph.uiPrefs.setupDone,
            hasLocalNetwork = Permissions.hasLocalNetwork(app),
            hasNotifications = Permissions.hasNotifications(app),
            notificationsSkipped = graph.uiPrefs.notificationsSkipped,
            filesStepDone = graph.filesPrefs.filesStepDone,
        )
    }

    private companion object {
        const val SUCCESS_PAUSE_MS = 1_000L
    }
}
