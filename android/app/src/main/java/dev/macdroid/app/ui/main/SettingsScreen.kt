package dev.macdroid.app.ui.main

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.macdroid.app.BuildConfig

@Composable
fun SettingsScreen(onForget: () -> Unit, viewModel: SettingsViewModel = viewModel(), filesViewModel: FilesSettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val files by filesViewModel.ui.collectAsStateWithLifecycle()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) filesViewModel.chooseFolder(uri) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { filesViewModel.refresh() }
    var showMac by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)) }
        item { SectionHeader("Connection") }
        item {
            ListItem(
                headlineContent = { Text("Paired Mac") },
                supportingContent = { Text(ui.macSummary) },
                leadingContent = { Icon(Icons.Rounded.Laptop, contentDescription = null) },
                modifier = Modifier.clickable(enabled = ui.mac != null) { showMac = true },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Keep connected in background") },
                supportingContent = { Text("Stays connected while you use other apps") },
                leadingContent = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                trailingContent = { Switch(checked = ui.keepConnected, onCheckedChange = viewModel::setKeepConnected) },
            )
        }
        item { SectionHeader("Files") }
        item {
            ListItem(
                headlineContent = { Text("Save files from your Mac to") },
                supportingContent = {
                    Text(
                        if (files.folderProblem) "Can't save to your chosen folder — choose it again" else files.folderLabel,
                        color = if (files.folderProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                trailingContent = { if (files.customFolder) TextButton(onClick = filesViewModel::useDefaultFolder) { Text("Use default") } },
                modifier = Modifier.clickable { folderPicker.launch(null) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Ask where to save each time") },
                supportingContent = { Text("Choose a place for every file instead of saving automatically") },
                leadingContent = { Icon(Icons.Rounded.SaveAs, contentDescription = null) },
                trailingContent = { Switch(checked = files.askEachTime, onCheckedChange = filesViewModel::setAskEachTime) },
            )
        }
        item { SectionHeader("Setup") }
        item {
            ListItem(
                headlineContent = { Text("Permissions") },
                supportingContent = { Text(ui.permissionsSummary) },
                leadingContent = { Icon(Icons.Rounded.Shield, contentDescription = null) },
                modifier = Modifier.clickable { context.openNotificationSettings() },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Battery") },
                supportingContent = { Text(if (ui.batteryUnrestricted) "Unrestricted ✓" else "Optimized — tap to allow background use") },
                leadingContent = { Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null) },
                modifier = Modifier.clickable { context.openBatterySettings() },
            )
        }
        item { SectionHeader("About") }
        item {
            ListItem(
                headlineContent = { Text("Version") },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
                leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Forget this Mac", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(enabled = ui.mac != null) { confirmForget = true },
            )
        }
    }

    val mac = ui.mac
    if (showMac && mac != null) {
        AlertDialog(
            onDismissRequest = { showMac = false },
            title = { Text(mac.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Address: ${mac.host}:${mac.port}")
                    Text("Fingerprint")
                    Text(mac.fingerprint.uppercase().chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { showMac = false }) { Text("Done") } },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget ${mac?.name ?: "this Mac"}?") },
            text = { Text("MacDroid will disconnect, and you'll need to pair again to reconnect.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    onForget()
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp),
    )
}

private fun Context.openNotificationSettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
}

private fun Context.openBatterySettings() {
    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
