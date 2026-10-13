package dev.macdroid.app.ui.clipboard

import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.macdroid.app.ui.presentation.ClipboardPresentation
import dev.macdroid.core.clipboard.ClipItem
import kotlinx.coroutines.launch

/** The Clipboard tab (spec §4.4): Send now, "Get Mac clipboard", and the last 10 items. */
@Composable
fun ClipboardScreen(viewModel: ClipboardViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Clipboard", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp)) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Send clipboard to Mac", style = MaterialTheme.typography.titleMedium)
                    Text("Copies what's on this phone's clipboard to your Mac", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        // The app is in front, so reading the clipboard here is allowed.
                        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                        scope.launch {
                            val result = viewModel.send(clip)
                            Toast.makeText(context, ClipboardPresentation.toast(result), Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send now")
                    }
                }
            }
        }
        item {
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Get Mac clipboard", style = MaterialTheme.typography.titleMedium)
                        Text("Automatic when you copy on the Mac", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.receiveFromMac, onCheckedChange = viewModel::setReceiveFromMac)
                }
            }
        }
        if (state.recent.isNotEmpty()) {
            item {
                Text("Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp))
            }
            items(state.recent, key = { it.id }) { item ->
                RecentRow(item) {
                    viewModel.copyAgain(item)
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
private fun RecentRow(item: ClipItem, onClick: () -> Unit) {
    val icon = when (item.kind) {
        ClipItem.Kind.Image -> Icons.Rounded.Image
        ClipItem.Kind.Link -> Icons.Rounded.Link
        ClipItem.Kind.Text -> Icons.AutoMirrored.Rounded.Notes
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ClipboardPresentation.preview(item), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(ClipboardPresentation.subtitle(item, System.currentTimeMillis()), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
