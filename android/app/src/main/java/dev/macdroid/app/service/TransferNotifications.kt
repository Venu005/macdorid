package dev.macdroid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.macdroid.app.R
import dev.macdroid.app.ui.MainActivity
import dev.macdroid.app.ui.files.SaveAsActivity
import dev.macdroid.app.ui.presentation.TransferPresentation
import dev.macdroid.core.transfer.TransferRecord
import dev.macdroid.core.transfer.TransferText

/** Notifications for file transfers: the ongoing progress, received files, and "where to save?" prompts. */
object TransferNotifications {
    const val PROGRESS_CHANNEL = "transfers"
    const val FILES_CHANNEL = "files"
    const val PROGRESS_ID = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(FILES_CHANNEL, "Received files", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** A file from the Mac needs a place ("Ask where to save each time"). Tapping opens the system save screen. */
    fun askWhereToSave(context: Context, transferId: String, name: String, mime: String) {
        createChannels(context)
        val open = PendingIntent.getActivity(
            context, transferId.hashCode(), SaveAsActivity.intent(context, transferId, name, mime),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, FILES_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Save “$name” from your Mac?")
            .setContentText("Tap to choose where to save it")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(askId(transferId), notification)
    }

    fun cancelAsk(context: Context, transferId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(askId(transferId))
    }

    private fun askId(transferId: String) = 1000 + (transferId.hashCode() and 0xffff)

    /** The ongoing notification while transfers run, with Cancel (cancels them all). */
    fun progress(context: Context, summary: TransferPresentation.ProgressSummary): Notification {
        val cancel = PendingIntent.getService(
            context, 2, Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_CANCEL_TRANSFERS),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle(summary.title)
            .setProgress(100, summary.percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    /** "Received <name> from Mac", with Open. */
    fun received(context: Context, record: TransferRecord) {
        val uri = record.savedUri?.let(Uri::parse) ?: return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, record.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(context, record.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, FILES_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Received ${record.name} from Mac")
            .setContentText(TransferText.bytes(record.size))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Open", open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(record.id.hashCode(), notification)
    }
}
