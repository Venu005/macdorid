package dev.macdroid.app.platform.extras

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.macdroid.app.R

/** A link from the Mac becomes a tap-to-open notification: Android forbids opening it from the background (spec §3.2). */
object LinkNotifications {
    private const val CHANNEL_ID = "links"

    fun show(context: Context, url: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Links", NotificationManager.IMPORTANCE_HIGH))
        val uri = Uri.parse(url)
        val id = url.hashCode()
        val open = PendingIntent.getActivity(
            context, id, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Link from your Mac")
            .setContentText(uri.host ?: url)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }
}
