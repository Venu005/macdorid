package dev.macdroid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.macdroid.app.AppGraph
import dev.macdroid.app.service.StatusText
import dev.macdroid.core.connection.ConnectionState

@Composable
fun HomeScreen(graph: AppGraph, onForget: () -> Unit) {
    val state by graph.connectionManager.state.collectAsStateWithLifecycle()
    val paired by graph.pairingStore.paired.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("MacDroid", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(StatusText.title(state), style = MaterialTheme.typography.titleMedium)
                paired?.let { Text("${it.name} · ${it.host}:${it.port}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (state is ConnectionState.TrustFailure) {
            Text(
                "Your Mac no longer recognises this phone (or a different Mac answered). Tap Forget, then pair again.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(onClick = onForget) { Text("Forget this Mac") }
    }
}
