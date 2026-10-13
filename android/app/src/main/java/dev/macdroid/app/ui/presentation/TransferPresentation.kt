package dev.macdroid.app.ui.presentation

import dev.macdroid.core.transfer.Direction
import dev.macdroid.core.transfer.Failure
import dev.macdroid.core.transfer.Phase
import dev.macdroid.core.transfer.TransferRecord
import dev.macdroid.core.transfer.TransferSnapshot
import dev.macdroid.core.transfer.TransferText

data class TransferRowModel(
    val title: String,
    val directionLabel: String,
    val detail: String,
    /** 0…1 while bytes are moving or paused; null when there's no bar to show. */
    val progress: Float?,
    val isError: Boolean,
    val canCancel: Boolean,
    val canRetry: Boolean,
    val canOpen: Boolean,
    /** An incoming file is waiting for "where to save": the row can open the question (the notification may be gone). */
    val canChooseLocation: Boolean = false,
)

/** Transfer → text for the Files tab, Home and notifications. Mirrors the Mac's TransferPresentation with "phone"/"Mac" swapped. */
object TransferPresentation {
    data class ProgressSummary(val title: String, val percent: Int)

    /** [savedFileExists]: whether a received file is still there (the user may have deleted it). */
    fun row(record: TransferRecord, rate: Double?, savedFileExists: Boolean = true): TransferRowModel {
        val toMac = record.direction == Direction.ToMac
        val fraction = if (record.size > 0) (record.bytesDone.toFloat() / record.size).coerceIn(0f, 1f) else null
        val canRetry = toMac && record.source != null
        val base = TransferRowModel(
            title = record.name, directionLabel = if (toMac) "→ Mac" else "← Mac", detail = "", progress = null,
            isError = false, canCancel = !record.isFinished, canRetry = false, canOpen = false,
        )
        return when (record.phase) {
            Phase.Queued -> base.copy(detail = "Waiting to send")
            Phase.Offered -> base.copy(detail = if (toMac) "Waiting for your Mac" else "Waiting for you to choose where to save", canChooseLocation = !toMac)
            Phase.Active -> base.copy(detail = TransferText.line(record.bytesDone, record.size, rate), progress = fraction)
            Phase.Paused -> base.copy(detail = "Paused — waiting for connection", progress = fraction)
            Phase.Verifying -> base.copy(detail = "Checking…", progress = 1f)
            Phase.Done -> if (!toMac && !savedFileExists) base.copy(detail = "Deleted from this phone") else base.copy(
                detail = "${if (toMac) "Sent" else "Received"} · ${TransferText.bytes(record.size)}",
                canOpen = !toMac && record.savedUri != null,
            )
            Phase.Failed -> base.copy(detail = failureText(record.failure, toMac), isError = true, canRetry = canRetry)
            Phase.Cancelled -> base.copy(
                detail = if (record.cancelledByPeer) "Cancelled on your Mac" else "Cancelled on your phone",
                canRetry = canRetry,
            )
        }
    }

    fun recent(snapshot: TransferSnapshot, limit: Int = 3): List<TransferRecord> = snapshot.history.take(limit)

    /** The ongoing notification: what's moving and how far along, overall. Null when nothing is active. */
    fun progressSummary(snapshot: TransferSnapshot): ProgressSummary? {
        val active = snapshot.active
        if (active.isEmpty()) return null
        val count = active.size
        val files = if (count == 1) "1 file" else "$count files"
        val title = when {
            active.all { it.direction == Direction.ToMac } -> "Sending $files to your Mac"
            active.all { it.direction == Direction.FromMac } -> "Receiving $files from your Mac"
            else -> "Transferring $files"
        }
        val total = active.sumOf { it.size }
        val percent = if (total > 0) (active.sumOf { it.bytesDone } * 100 / total).toInt() else 0
        return ProgressSummary(title, percent)
    }

    private fun failureText(failure: Failure?, toMac: Boolean): String = when (failure) {
        Failure.ChecksumMismatch -> "Transfer failed — file was corrupted in transit"
        Failure.NoSpace -> if (toMac) "Not enough space on your Mac" else "Not enough space on this phone"
        Failure.Declined -> if (toMac) "Declined on your Mac" else "Not saved"
        Failure.SourceUnavailable -> "The original file is no longer available"
        Failure.Expired -> "Expired — not finished within 24 hours"
        Failure.Io, Failure.Invalid, null -> "Transfer failed"
    }
}
