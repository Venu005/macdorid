package dev.macdroid.app.ui.setup

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.macdroid.app.platform.Permissions
import dev.macdroid.app.ui.PairingViewModel
import dev.macdroid.app.ui.QrCameraPreview
import dev.macdroid.core.tls.Fingerprint

/** The hero setup step: camera inline, manual entry as a fallback, result shown in place. */
@Composable
fun PairStep(viewModel: PairingViewModel, macName: String?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Clear a stale result once per visit to this step — not on rotation (rememberSaveable survives it).
    var resetDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!resetDone) {
            viewModel.resetIfFinished()
            resetDone = true
        }
    }
    var showManual by rememberSaveable { mutableStateOf(false) }
    var cameraAsked by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Pair with your Mac", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
        Text(
            "On your Mac, open MacDroid → Phone and choose Pair a Phone (or Pair a Different Phone).",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
        )
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                PairingViewModel.UiState.Working -> Message(null, "Pairing with your Mac…", null) { CircularProgressIndicator() }
                PairingViewModel.UiState.Paired -> Message(Icons.Rounded.CheckCircle, "Paired with ${macName ?: "your Mac"}", null)
                is PairingViewModel.UiState.Error -> Message(Icons.Rounded.ErrorOutline, s.message, null, iconTint = MaterialTheme.colorScheme.error) {
                    Button(onClick = viewModel::reset) { Text("Try again") }
                }
                else -> CameraArea(enabled = s == PairingViewModel.UiState.Idle, asked = cameraAsked, onAsked = { cameraAsked = true }, onResult = viewModel::onScanned)
            }
        }
        TextButton(
            onClick = { showManual = true },
            enabled = state == PairingViewModel.UiState.Idle,
            modifier = Modifier.padding(vertical = 12.dp),
        ) { Text("Enter code manually") }
    }

    if (showManual) {
        ManualPairSheet(
            onDismiss = { showManual = false },
            onSubmit = { host, port, code ->
                showManual = false
                viewModel.onManual(host, port, code)
            },
        )
    }
    (state as? PairingViewModel.UiState.Confirm)?.let { confirm ->
        AlertDialog(
            onDismissRequest = viewModel::reset,
            title = { Text("Check the fingerprint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Does your Mac show this fingerprint under “Can't scan?”")
                    Text(Fingerprint.display(confirm.fingerprint), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmFingerprint) { Text("They match") } },
            dismissButton = { TextButton(onClick = viewModel::reset) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CameraArea(enabled: Boolean, asked: Boolean, onAsked: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(Permissions.granted(context, Manifest.permission.CAMERA)) }
    var cameraError by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
        onAsked()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasCamera = Permissions.granted(context, Manifest.permission.CAMERA) }
    LaunchedEffect(Unit) { if (!hasCamera && !asked) launcher.launch(Manifest.permission.CAMERA) }
    when {
        cameraError -> Message(Icons.Rounded.NoPhotography, "Camera unavailable", "Close other apps using the camera, or enter the code manually.")
        hasCamera -> QrCameraPreview(enabled, onResult, { cameraError = true }, Modifier.fillMaxSize())
        else -> Message(Icons.Rounded.PhotoCamera, "Camera access needed", "Allow the camera to scan the code, or enter it manually.") {
            Button(onClick = { if (asked) context.openAppSettings() else launcher.launch(Manifest.permission.CAMERA) }) {
                Text(if (asked) "Open settings" else "Allow camera")
            }
        }
    }
}

@Composable
private fun Message(
    icon: ImageVector?,
    title: String,
    body: String?,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    action: @Composable () -> Unit = {},
) {
    Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = iconTint)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        action()
    }
}
