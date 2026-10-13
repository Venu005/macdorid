package dev.macdroid.app.ui.clipboard

import android.app.Application
import android.content.ClipData
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.core.clipboard.ClipItem
import dev.macdroid.core.clipboard.SendResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ClipboardViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(val recent: List<ClipItem>, val receiveFromMac: Boolean)

    private val bridge = application.graph.clipboardBridge

    val state: StateFlow<UiState> = combine(bridge.hub.recent, bridge.prefs.receiveFromMac) { recent, receive -> UiState(recent, receive) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState(bridge.hub.recent.value, bridge.prefs.receiveFromMac.value))

    /** [clip] must be read by the screen while the app is in front (spec §4.2). */
    suspend fun send(clip: ClipData?): SendResult = bridge.sendClip(clip)
    fun setReceiveFromMac(on: Boolean) = bridge.prefs.setReceiveFromMac(on)
    fun copyAgain(item: ClipItem) = bridge.copyLocally(item)
}
