package dev.macdroid.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.macdroid.app.R
import dev.macdroid.app.ui.files.SaveAsActivity

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
}
