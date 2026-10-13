package dev.macdroid.app.ui.clipboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.macdroid.app.graph
import dev.macdroid.app.ui.presentation.ClipboardPresentation
import kotlinx.coroutines.launch

/** "Share → Copy to Mac": shared text or one image goes to the Mac clipboard, not to Downloads (spec §4.2). */
class CopyToMacActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val image = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.takeIf { intent.type?.startsWith("image/") == true }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        lifecycleScope.launch {
            // Read the shared image while this activity still holds the share's read grant.
            val result = graph.clipboardBridge.sendShared(text, image)
            Toast.makeText(applicationContext, ClipboardPresentation.toast(result), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
