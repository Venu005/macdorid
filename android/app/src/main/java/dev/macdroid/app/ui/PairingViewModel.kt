package dev.macdroid.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.platform.DeviceInfo
import dev.macdroid.core.pairing.PairResult
import dev.macdroid.core.pairing.PairingClient
import dev.macdroid.core.pairing.PairingInvite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PairingViewModel(application: Application) : AndroidViewModel(application) {
    sealed interface UiState {
        data object Idle : UiState
        data object Working : UiState
        data class Confirm(val host: String, val port: Int, val code: String, val fingerprint: String) : UiState
        data class Error(val message: String) : UiState
    }

    private val graph = application.graph
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onScanned(raw: String) {
        if (_state.value != UiState.Idle) return
        val invite = PairingInvite.parse(raw)
        if (invite == null) {
            _state.value = UiState.Error("That QR code isn't a MacDroid pairing code.")
            return
        }
        pair(invite)
    }

    /** Manual entry. Accepts "host:port" in the address field when the port field is empty. */
    fun onManual(hostInput: String, portInput: String, codeInput: String) {
        val trimmed = hostInput.trim()
        val (host, portText) = if (portInput.isBlank() && trimmed.count { it == ':' } == 1) {
            trimmed.substringBefore(':') to trimmed.substringAfter(':')
        } else {
            trimmed to portInput.trim()
        }
        val port = portText.toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            _state.value = UiState.Error("Enter the address and port exactly as your Mac shows them.")
            return
        }
        _state.value = UiState.Working
        viewModelScope.launch {
            val fingerprint = client().probeFingerprint(host, port)
            _state.value = if (fingerprint == null) {
                UiState.Error("Couldn't reach a Mac at $host:$port. Check the address and that pairing is open on the Mac.")
            } else {
                UiState.Confirm(host, port, PairingInvite.normalizeCode(codeInput), fingerprint)
            }
        }
    }

    fun confirmFingerprint() {
        val confirm = _state.value as? UiState.Confirm ?: return
        _state.value = UiState.Idle
        pair(PairingInvite(confirm.host, confirm.port, confirm.fingerprint, confirm.code, "Mac"))
    }

    fun reset() {
        _state.value = UiState.Idle
    }

    private fun pair(invite: PairingInvite) {
        _state.value = UiState.Working
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = client().pair(invite, DeviceInfo.name(app), DeviceInfo.model())
            _state.value = when (result) {
                is PairResult.Success -> {
                    graph.pairingStore.save(result.mac)
                    UiState.Idle
                }
                PairResult.CodeRejected -> UiState.Error("The Mac rejected the code — it may have expired. Choose Pair a Phone on the Mac again.")
                PairResult.FingerprintMismatch -> UiState.Error("Security check failed: this isn't the Mac that showed the code.")
                is PairResult.Failed -> UiState.Error("Couldn't reach the Mac (${result.reason}). Make sure both devices are on the same Wi-Fi.")
            }
        }
    }

    /** Creating the Keystore identity the first time takes a moment, so keep it off the main thread. */
    private suspend fun client(): PairingClient = withContext(Dispatchers.Default) { graph.pairingClient() }
}
