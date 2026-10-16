package dev.macdroid.app.ui.main

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.macdroid.app.ui.files.FilesViewModel
import dev.macdroid.app.ui.files.TransferRow
import dev.macdroid.app.ui.files.openReceived
import dev.macdroid.app.ui.files.rememberSendPickers
import dev.macdroid.app.ui.presentation.StatusAction
import dev.macdroid.app.ui.presentation.TransferPresentation

@Composable
fun HomeScreen(onPairAgain: () -> Unit, viewModel: HomeViewModel = viewModel(), filesViewModel: FilesViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val files by filesViewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickers = rememberSendPickers(filesViewModel::send)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { filesViewModel.refresh() }
    var sendMenu by remember { mutableStateOf(false) }
    var confirmPairAgain by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("MacDroid", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp))
        StatusCard(ui.status) { action ->
            when (action) {
                StatusAction.RetryNow -> viewModel.retryNow()
                StatusAction.Connect -> viewModel.connect()
                StatusAction.PairAgain -> confirmPairAgain = true
                StatusAction.None -> Unit
            }
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                val recent = TransferPresentation.recent(files.snapshot)
                if (recent.isEmpty()) {
                    Text(
                        "Files you send or receive show here. Clipboard and notifications come in later updates.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                recent.forEach { record ->
                    TransferRow(record, null, { filesViewModel.cancel(record.id) }, { filesViewModel.retry(record.id) }, { context.openReceived(record, record.id !in files.deleted) }, savedFileExists = record.id !in files.deleted)
                }
            }
        }
        Spacer(Modifier.height(88.dp)) // room for the Send button
    }
    Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
        ExtendedFloatingActionButton(
            onClick = {
                if (files.connected) sendMenu = true
                else Toast.makeText(context, "Connect to your Mac first", Toast.LENGTH_SHORT).show() // was silent (Plan 11)
            },
            icon = { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null) },
            text = { Text("Send") },
            expanded = true,
        )
        DropdownMenu(expanded = sendMenu, onDismissRequest = { sendMenu = false }) {
            DropdownMenuItem(text = { Text("Photos") }, onClick = { sendMenu = false; pickers.pickPhotos() })
            DropdownMenuItem(text = { Text("Files") }, onClick = { sendMenu = false; pickers.pickFiles() })
        }
    }
    }
    if (confirmPairAgain) {
        AlertDialog(
            onDismissRequest = { confirmPairAgain = false },
            title = { Text("Pair again?") },
            text = { Text("This phone will forget ${ui.macName ?: "your Mac"}. Then, on your Mac, open MacDroid → Phone and choose Pair a Phone (or Pair a Different Phone), and scan the new code.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPairAgain = false
                    onPairAgain()
                }) { Text("Pair again") }
            },
            dismissButton = { TextButton(onClick = { confirmPairAgain = false }) { Text("Cancel") } },
        )
    }
}
