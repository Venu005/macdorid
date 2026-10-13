package dev.macdroid.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.macdroid.app.ui.presentation.StatusAction
import dev.macdroid.app.ui.presentation.StatusModel
import dev.macdroid.app.ui.presentation.StatusTone

private val ConnectedGreen = Color(0xFF2E7D32)

@Composable
fun StatusCard(status: StatusModel, onAction: (StatusAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (status.tone) {
        StatusTone.Connected -> colors.primaryContainer to colors.onPrimaryContainer
        StatusTone.Problem -> colors.errorContainer to colors.onErrorContainer
        StatusTone.Pending, StatusTone.Idle -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (status.tone == StatusTone.Problem) Icons.Rounded.ErrorOutline else Icons.Rounded.Laptop,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Text(status.title, style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status.tone) {
                    StatusTone.Connected -> Box(Modifier.size(8.dp).background(ConnectedGreen, CircleShape))
                    StatusTone.Pending -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = content)
                    else -> Unit
                }
                Text(status.subtitle, style = MaterialTheme.typography.bodyLarge)
            }
            if (status.tone == StatusTone.Connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip(Icons.Rounded.Wifi, "Wi-Fi", content)
                    InfoChip(Icons.Rounded.BatteryChargingFull, "Battery shared", content)
                }
            }
            when (status.action) {
                StatusAction.RetryNow -> OutlinedButton(onClick = { onAction(status.action) }) { Text("Retry now") }
                StatusAction.PairAgain -> Button(
                    onClick = { onAction(status.action) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                ) { Text("Pair again") }
                StatusAction.Connect -> Button(onClick = { onAction(status.action) }) { Text("Connect") }
                StatusAction.None -> Unit
            }
        }
    }
}

@Composable
private fun InfoChip(icon: ImageVector, label: String, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = content.copy(alpha = 0.10f), contentColor = content) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
