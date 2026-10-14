package dev.macdroid.app.ui.main

import android.app.Application
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.platform.Permissions
import dev.macdroid.app.service.ConnectionService
import dev.macdroid.app.ui.presentation.PhotosAccessText
import dev.macdroid.core.pairing.PairedMac
import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.tls.Fingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SettingsUi(
    val mac: PairedMac?,
    val keepConnected: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val photosAccess: PhotoAccess = PhotoAccess.None,
) {
    val macSummary: String get() = mac?.let { "${it.name} · ${Fingerprint.display(it.fingerprint)}" } ?: "Not paired"
    val photosSummary: String get() = PhotosAccessText.label(photosAccess)
    val permissionsSummary: String get() = if (notificationsAllowed) "All set ✓" else "Notifications are off — tap to turn on"
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val local = MutableStateFlow(read())

    val ui: StateFlow<SettingsUi> = combine(graph.pairingStore.paired, local, graph.uiPrefs.keepConnectedFlow) { mac, l, keep ->
        l.copy(mac = mac, keepConnected = keep)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun refresh() {
        graph.photosBridge.refreshAccess()
        local.value = read()
    }

    fun setKeepConnected(on: Boolean) {
        val app = getApplication<Application>()
        graph.uiPrefs.keepConnected = on
        if (on) {
            ConnectionService.start(app)
        } else {
            ConnectionService.stop(app)
            graph.connectionManager.stop()
        }
        refresh()
    }

    private fun read(): SettingsUi {
        val app = getApplication<Application>()
        return SettingsUi(
            mac = graph.pairingStore.paired.value,
            keepConnected = graph.uiPrefs.keepConnected,
            notificationsAllowed = Permissions.hasNotifications(app),
            batteryUnrestricted = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName),
            photosAccess = graph.photosBridge.access.value,
        )
    }
}
