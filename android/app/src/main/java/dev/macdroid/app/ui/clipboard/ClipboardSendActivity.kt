package dev.macdroid.app.ui.clipboard

import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.macdroid.app.graph
import dev.macdroid.app.ui.presentation.ClipboardPresentation
import kotlinx.coroutines.launch

/**
 * Invisible: Android only lets the focused app read the clipboard (spec §4.2), so the tile and the notification
 * button open this for a moment, read once focused, send, and close.
 */
class ClipboardSendActivity : ComponentActivity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        lifecycleScope.launch {
            val result = graph.clipboardBridge.sendClip(clip)
            Toast.makeText(applicationContext, ClipboardPresentation.toast(result), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
