package dev.macdroid.app.platform

import android.content.Context
import dev.macdroid.core.pairing.PairedMac
import dev.macdroid.core.pairing.PairingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

class PrefsPairingStore(context: Context) : PairingStore {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    override val paired: StateFlow<PairedMac?> = state.asStateFlow()

    override fun save(mac: PairedMac) {
        prefs.edit().putString(KEY, Json.encodeToString(PairedMac.serializer(), mac)).apply()
        state.value = mac
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
        state.value = null
    }

    private fun load(): PairedMac? = prefs.getString(KEY, null)?.let {
        runCatching { Json.decodeFromString(PairedMac.serializer(), it) }.getOrNull()
    }

    private companion object { const val KEY = "pairedMac" }
}
