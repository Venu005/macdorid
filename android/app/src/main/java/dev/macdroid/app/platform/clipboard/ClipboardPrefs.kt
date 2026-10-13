package dev.macdroid.app.platform.clipboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Clipboard tab's "Get Mac clipboard" switch (default on). */
class ClipboardPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("clipboard", Context.MODE_PRIVATE)
    private val _receive = MutableStateFlow(prefs.getBoolean(KEY, true))
    val receiveFromMac: StateFlow<Boolean> = _receive.asStateFlow()

    fun setReceiveFromMac(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        _receive.value = on
    }

    private companion object {
        const val KEY = "receiveFromMac"
    }
}
