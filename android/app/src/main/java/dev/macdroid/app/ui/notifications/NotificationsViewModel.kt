package dev.macdroid.app.ui.notifications

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.notifications.NotificationsState
import dev.macdroid.core.protocol.Features
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class NotificationsViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(val notifications: NotificationsState, val macName: String?, val connected: Boolean, val macSupports: Boolean)

    private val graph = application.graph
    private val bridge = graph.notificationBridge

    val state: StateFlow<UiState> = combine(bridge.forwarder.state, graph.connectionManager.state, graph.pairingStore.paired) { n, connection, mac ->
        UiState(
            notifications = n,
            macName = (connection as? ConnectionState.Connected)?.macName ?: mac?.name,
            connected = connection is ConnectionState.Connected,
            macSupports = connection.macSupports(Features.NOTIFICATIONS),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UiState(bridge.forwarder.state.value, graph.pairingStore.paired.value?.name, connected = false, macSupports = false),
    )

    fun refreshAccess() = bridge.refreshAccess()
    fun setForwarding(on: Boolean) = bridge.forwarder.setForwarding(on)
    fun setShown(packageName: String, shown: Boolean) = bridge.forwarder.setMuted(packageName, muted = !shown)
}
