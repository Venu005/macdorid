package dev.macdroid.app.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import dev.macdroid.app.graph
import dev.macdroid.app.service.ConnectionService
import dev.macdroid.app.ui.MainActivity
import dev.macdroid.app.ui.presentation.ShareDecision
import dev.macdroid.core.connection.ConnectionState

/** "Share → MacDroid": queues the files and gets out of the way (spec §4.2). It shows no UI of its own. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        val decision = ShareDecision.decide(
            paired = graph.pairingStore.paired.value != null,
            connected = graph.connectionManager.state.value is ConnectionState.Connected,
            count = uris.size,
        )
        when (decision) {
            ShareDecision.Send -> {
                // This activity holds the share's read grant; the intent passes it on to the service.
                ConnectionService.sendFiles(this, uris)
                Toast.makeText(this, ShareDecision.sendingText(uris.size), Toast.LENGTH_SHORT).show()
            }
            ShareDecision.ConnectFirst -> {
                Toast.makeText(this, "Connect to your Mac first", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ShareDecision.Nothing -> Unit
        }
        finish()
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}
