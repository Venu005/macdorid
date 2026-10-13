package dev.macdroid.app.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun FilesScreen(viewModel: FilesViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickers = rememberSendPickers(viewModel::send)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Files", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)) }
        if (ui.folderProblem) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        "Can't save to your chosen folder — choose it again in Settings. Until then MacDroid asks where to save each file.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        item { SendCard(ui.connected, pickers) }
        if (ui.snapshot.active.isNotEmpty()) {
            item { SectionTitle("In progress") }
            items(ui.snapshot.active, key = { it.id }) { record ->
                TransferRow(
                    record, ui.snapshot.rates[record.id], { viewModel.cancel(record.id) }, { viewModel.retry(record.id) }, { context.openReceived(record) },
                    onChooseLocation = { context.startActivity(SaveAsActivity.intent(context, record.id, record.name, record.mime)) },
                )
            }
        }
        item { SectionTitle("Recent") }
        if (ui.snapshot.history.isEmpty()) {
            item {
                Text("Files you send or receive appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
        items(ui.snapshot.history, key = { "h-" + it.id }) { record ->
            TransferRow(record, null, { viewModel.cancel(record.id) }, { viewModel.retry(record.id) }, { context.openReceived(record) }, savedFileExists = record.id !in ui.deleted)
        }
    }
}

@Composable
fun SendCard(connected: Boolean, pickers: SendPickers) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Send to Mac", style = MaterialTheme.typography.titleMedium)
            if (!connected) Text("Connect to your Mac first", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = pickers.pickPhotos, enabled = connected) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                    Text("Photos", modifier = Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(onClick = pickers.pickFiles, enabled = connected) {
                    Icon(Icons.Rounded.Description, contentDescription = null)
                    Text("Files", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp))
}
