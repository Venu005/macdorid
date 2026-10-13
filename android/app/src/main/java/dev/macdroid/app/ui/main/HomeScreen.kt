package dev.macdroid.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.macdroid.app.ui.presentation.StatusAction

@Composable
fun HomeScreen(onPairAgain: () -> Unit, viewModel: HomeViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmPairAgain by remember { mutableStateOf(false) }
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
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Clipboard, files and notifications will show here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmPairAgain) {
        AlertDialog(
            onDismissRequest = { confirmPairAgain = false },
            title = { Text("Pair again?") },
            text = { Text("This phone will forget ${ui.macName ?: "your Mac"}. Then choose Pair a Phone on your Mac and scan the new code.") },
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
