package dev.macdroid.app.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.macdroid.app.platform.Permissions
import dev.macdroid.app.ui.PairingViewModel

@Composable
fun SetupScreen(viewModel: SetupViewModel, pairingViewModel: PairingViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val macName by viewModel.macName.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (state.firstRun && state.total > 1) {
            LinearProgressIndicator(
                progress = { state.position.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        AnimatedContent(targetState = state.step, label = "setup-step", modifier = Modifier.weight(1f)) { step ->
            when (step) {
                SetupStep.Welcome -> WelcomeStep(onContinue = viewModel::completeWelcome)
                SetupStep.LocalNetwork -> LocalNetworkStep(onResult = viewModel::refresh)
                SetupStep.PairMac -> PairStep(pairingViewModel, macName)
                SetupStep.Notifications -> NotificationsStep(
                    needsPermission = Permissions.needsNotifications && !Permissions.hasNotifications(LocalContext.current),
                    onResult = viewModel::refresh,
                    onSkip = viewModel::skipNotifications,
                )
                SetupStep.Files -> FilesStep(onDone = viewModel::completeFiles)
                SetupStep.Battery -> BatteryStep(onDone = viewModel::completeBattery)
                null -> Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    StepLayout(
        icon = Icons.Rounded.Laptop,
        title = "Welcome to MacDroid",
        body = "Your phone and your Mac, working together — right over your Wi-Fi. No account, and nothing leaves your network.",
        primaryLabel = "Get started",
        onPrimary = onContinue,
    )
}

@Composable
private fun LocalNetworkStep(onResult: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    StepLayout(
        icon = Icons.Rounded.Wifi,
        title = "Find your Mac on Wi-Fi",
        body = "MacDroid talks to your Mac directly over your local network. Android asks your permission for apps to reach devices on your network.",
        primaryLabel = "Allow",
        onPrimary = { launcher.launch(Permissions.ACCESS_LOCAL_NETWORK) },
        secondaryLabel = "Open settings",
        onSecondary = { context.openAppSettings() },
    )
}

@Composable
private fun NotificationsStep(needsPermission: Boolean, onResult: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val openAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onResult()
        if (!Permissions.hasNotificationAccess(context)) openAccess()
    }
    StepLayout(
        icon = Icons.Rounded.NotificationsActive,
        title = "See phone notifications on your Mac",
        body = "Reply to messages and clear notifications from your Mac. MacDroid only sends them to your paired Mac — never to the internet.",
        primaryLabel = "Allow notification access",
        onPrimary = {
            when {
                needsPermission -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                !Permissions.hasNotificationAccess(context) -> openAccess()
                else -> onResult()
            }
        },
        secondaryLabel = "Not now",
        onSecondary = onSkip,
    )
}

@Composable
private fun FilesStep(onDone: (Uri?) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) onDone(uri) }
    StepLayout(
        icon = Icons.Rounded.Folder,
        title = "Where should files from your Mac go?",
        body = "Files you send from your Mac are saved automatically. You can change this later in Settings.",
        primaryLabel = "Use Download/MacDroid",
        onPrimary = { onDone(null) },
        secondaryLabel = "Choose a folder",
        onSecondary = { launcher.launch(null) },
    )
}

@Composable
private fun BatteryStep(onDone: () -> Unit) {
    val context = LocalContext.current
    StepLayout(
        icon = Icons.Rounded.BatteryChargingFull,
        title = "Keep MacDroid running",
        body = "Android may pause apps in the background. Set MacDroid to “Unrestricted” so it stays connected to your Mac.\n\nOn Nothing phones: App info → Battery → Unrestricted.",
        primaryLabel = "Open battery settings",
        onPrimary = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
        secondaryLabel = "Done",
        onSecondary = onDone,
    )
}

/** Shared layout for permission-style steps: icon in a tonal circle, title, one-line reason, actions at the bottom. */
@Composable
internal fun StepLayout(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(112.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        extra()
        Spacer(Modifier.weight(1f))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(primaryLabel) }
        if (secondaryLabel != null) {
            TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondaryLabel) }
        }
    }
}

internal fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}
