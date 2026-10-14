package dev.macdroid.app.platform.mirror

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.macdroid.app.R
import dev.macdroid.core.protocol.MirrorStartBody

/** "Mirror to <Mac>? Tap to start" and the ongoing "Mirroring to <Mac> · Stop" (spec §3.1). */
object MirrorNotifications {
    private const val REQUEST_CHANNEL = "mirror_requests"
    const val CHANNEL = "mirror"
    private const val REQUEST_ID = 4201
    const val SERVICE_ID = 4202

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(REQUEST_CHANNEL, "Mirroring requests", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mirroring", NotificationManager.IMPORTANCE_LOW))
    }

    fun showRequest(context: Context, body: MirrorStartBody, macName: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val tap = PendingIntent.getActivity(context, 0, MirrorConsentActivity.intent(context, body), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, REQUEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Mirror to $macName?")
            .setContentText("Tap to start")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(REQUEST_ID, notification)
    }

    fun cancelRequest(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(REQUEST_ID)

    fun service(context: Context, macName: String, stop: Intent): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Mirroring to $macName")
            .setOngoing(true)
            .addAction(0, "Stop", PendingIntent.getService(context, 1, stop, PendingIntent.FLAG_IMMUTABLE))
            .build()
}
