package dev.macdroid.app.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.platform.DeviceInfo
import dev.macdroid.app.ui.setup.ManualEntry
import dev.macdroid.core.pairing.PairResult
import dev.macdroid.core.pairing.PairingClient
import dev.macdroid.core.pairing.PairingInvite
import kotlinx.coroutines.CancellationException
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
        /** Terminal: pairing succeeded; blocks further scans until the user forgets the Mac. */
        data object Paired : UiState
    }

    private val graph = application.graph
    private val scanGate = ScanGate()
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onScanned(raw: String) {
        if (_state.value != UiState.Idle) return
        val now = SystemClock.elapsedRealtime()
        if (scanGate.shouldIgnore(raw, now)) return
        val invite = PairingInvite.parse(raw)
        if (invite == null) {
            scanGate.markFailed(raw, now)
            _state.value = UiState.Error("That QR code isn't a MacDroid pairing code.")
            return
        }
        pair(invite, raw)
    }

    /** Manual entry. Accepts "host:port" in the address field when the port field is empty. */
    fun onManual(hostInput: String, portInput: String, codeInput: String) {
        if (_state.value != UiState.Idle) return
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
        val code = ManualEntry.normalizedCodeOrNull(codeInput)
        if (code == null) {
            _state.value = UiState.Error("The code is 26 letters and numbers — check it against “Can't scan?” on your Mac.")
            return
        }
        _state.value = UiState.Working
        viewModelScope.launch {
            val fingerprint = try {
                client().probeFingerprint(host, port)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.value = if (fingerprint == null) {
                UiState.Error("Couldn't reach a Mac at $host:$port. Check the address and that pairing is open on the Mac.")
            } else {
                UiState.Confirm(host, port, code, fingerprint)
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

    /** Called when the pairing screen appears: drop a finished or failed attempt left over from earlier. */
    fun resetIfFinished() {
        if (_state.value is UiState.Paired || _state.value is UiState.Error) _state.value = UiState.Idle
    }

    private fun pair(invite: PairingInvite, scannedRaw: String? = null) {
        _state.value = UiState.Working
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = try {
                client().pair(invite, DeviceInfo.name(app), DeviceInfo.model())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PairResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            val next = when (result) {
                is PairResult.Success -> try {
                    graph.pairingStore.save(result.mac)
                    graph.uiPrefs.keepConnected = true
                    UiState.Paired
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    UiState.Error("Paired, but couldn't save the pairing on this phone (${e.message ?: e.javaClass.simpleName}). Try again.")
                }
                PairResult.CodeRejected -> UiState.Error("The Mac rejected the code — it may have expired. Choose Pair a Phone on the Mac again.")
                PairResult.FingerprintMismatch -> UiState.Error("Security check failed: this isn't the Mac that showed the code.")
                is PairResult.Failed -> UiState.Error("Couldn't reach the Mac (${result.reason}). Make sure both devices are on the same Wi-Fi.")
            }
            if (next is UiState.Error && scannedRaw != null) scanGate.markFailed(scannedRaw, SystemClock.elapsedRealtime())
            _state.value = next
        }
    }

    /** Creating the Keystore identity the first time takes a moment, so keep it off the main thread. */
    private suspend fun client(): PairingClient = withContext(Dispatchers.Default) { graph.pairingClient() }
}
