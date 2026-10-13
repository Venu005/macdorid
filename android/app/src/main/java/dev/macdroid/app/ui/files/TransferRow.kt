package dev.macdroid.app.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macdroid.app.ui.presentation.TransferPresentation
import dev.macdroid.core.transfer.Direction
import dev.macdroid.core.transfer.TransferRecord

@Composable
fun TransferRow(
    record: TransferRecord,
    rate: Double?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    savedFileExists: Boolean = true,
) {
    val row = TransferPresentation.row(record, rate, savedFileExists)
    ListItem(
        modifier = if (row.canOpen) Modifier.clickable(onClick = onOpen) else Modifier,
        leadingContent = {
            Icon(
                if (record.direction == Direction.ToMac) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                contentDescription = row.directionLabel,
                tint = if (row.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = {
            Column {
                row.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
                Text(row.detail, color = if (row.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.canRetry) TextButton(onClick = onRetry) { Text("Retry") }
                if (row.canCancel) IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = "Cancel") }
            }
        },
    )
}
