package dev.macdroid.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.macdroid.app.platform.Permissions
import dev.macdroid.core.tls.Fingerprint

@Composable
fun StepScaffold(title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyLarge)
        content()
    }
}

@Composable
fun LocalNetworkScreen(onResult: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    StepScaffold(
        "Allow local network access",
        "MacDroid talks to your Mac directly over your Wi-Fi — nothing goes through the internet. Android needs your permission for that.",
    ) {
        Button(onClick = { launcher.launch(Permissions.ACCESS_LOCAL_NETWORK) }) { Text("Allow") }
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }) { Text("Open app settings") }
    }
}

@Composable
fun PairScreen(viewModel: PairingViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.resetIfFinished() }
    var mode by rememberSaveable { mutableStateOf("choose") }
    when (mode) {
        "scan" -> QrScannerScreen(enabled = state == PairingViewModel.UiState.Idle, onResult = viewModel::onScanned, onCancel = { mode = "choose" })
        "manual" -> ManualPairScreen(viewModel, onCancel = { mode = "choose" })
        else -> StepScaffold(
            "Pair with your Mac",
            "On your Mac, click the MacDroid icon in the menu bar, choose Pair a Phone, then scan the QR code it shows.",
        ) {
            Button(onClick = { mode = "scan" }) { Text("Scan QR code") }
            TextButton(onClick = { mode = "manual" }) { Text("Enter details manually") }
        }
    }
    PairingDialogs(state, onDismiss = viewModel::reset, onConfirm = viewModel::confirmFingerprint)
}

@Composable
private fun ManualPairScreen(viewModel: PairingViewModel, onCancel: () -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    StepScaffold("Enter pairing details", "Type the address and code shown under “Can't scan?” on your Mac.") {
        OutlinedTextField(host, { host = it }, label = { Text("Address (e.g. 192.168.1.20)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it }, label = { Text("Code") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.onManual(host, port, code) }, enabled = host.isNotBlank() && code.isNotBlank()) { Text("Connect") }
            TextButton(onClick = onCancel) { Text("Back") }
        }
    }
}

@Composable
private fun PairingDialogs(state: PairingViewModel.UiState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    when (state) {
        PairingViewModel.UiState.Working -> AlertDialog(
            onDismissRequest = {}, confirmButton = {},
            title = { Text("Pairing…") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
        )
        is PairingViewModel.UiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            title = { Text("Couldn't pair") }, text = { Text(state.message) },
        )
        is PairingViewModel.UiState.Confirm -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onConfirm) { Text("They match") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            title = { Text("Check the fingerprint") },
            text = { Text("Does your Mac show this fingerprint?\n\n${Fingerprint.display(state.fingerprint)}") },
        )
        PairingViewModel.UiState.Idle, PairingViewModel.UiState.Paired -> Unit
    }
}

@Composable
fun FinishSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val notificationsOk = remember(version) { Permissions.hasNotifications(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version++ }
    StepScaffold("Almost done", "Two more things keep MacDroid connected in the background.") {
        if (Permissions.needsNotifications) {
            Text("Show MacDroid's status notification", style = MaterialTheme.typography.titleMedium)
            if (notificationsOk) {
                Text("✓ Allowed")
            } else {
                Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
            }
        }
        Text("Let MacDroid run in the background", style = MaterialTheme.typography.titleMedium)
        Text(
            "Find MacDroid in the list and set it to “Not optimized”. On Nothing phones also open App info → Battery and choose “Unrestricted”.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }) { Text("Open battery settings") }
        Button(onClick = onDone) { Text("Done") }
    }
}
