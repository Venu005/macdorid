package dev.macdroid.core.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** The Mac this phone trusts, plus its last known address. */
@Serializable
data class PairedMac(val fingerprint: String, val name: String, val host: String, val port: Int)

interface PairingStore {
    val paired: StateFlow<PairedMac?>
    fun save(mac: PairedMac)
    fun clear()
}

class InMemoryPairingStore(initial: PairedMac? = null) : PairingStore {
    private val state = MutableStateFlow(initial)
    override val paired: StateFlow<PairedMac?> = state.asStateFlow()
    override fun save(mac: PairedMac) { state.value = mac }
    override fun clear() { state.value = null }
}
