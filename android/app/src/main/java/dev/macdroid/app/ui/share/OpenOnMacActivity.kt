package dev.macdroid.app.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.macdroid.app.graph
import dev.macdroid.app.ui.presentation.OpenOnMacDecision
import dev.macdroid.core.protocol.LinkOpenBody
import dev.macdroid.core.protocol.Message

/** "Share → Open on Mac": sends the first link in the shared text; no UI of its own. */
class OpenOnMacActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val decision = OpenOnMacDecision.decide(text, graph.connectionManager.state.value)
        val toast = when {
            decision.url == null -> decision.toast
            graph.connectionManager.send(Message.LinkOpen(LinkOpenBody(decision.url))) -> decision.toast
            else -> "Not connected to your Mac"
        }
        Toast.makeText(applicationContext, toast, Toast.LENGTH_SHORT).show()
        finish()
    }
}
