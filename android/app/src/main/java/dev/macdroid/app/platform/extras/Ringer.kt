package dev.macdroid.app.platform.extras

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.app.NotificationCompat
import dev.macdroid.app.R
import dev.macdroid.app.service.ConnectionService
import dev.macdroid.core.extras.RingSession
import dev.macdroid.core.extras.StopReason
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.RingAction
import dev.macdroid.core.protocol.RingStateBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ring my phone (spec §3.1): full alarm volume, looping alarm tone, flashing torch and a Stop notification.
 * Stops on Stop, a volume key (caught by a temporary MediaSession), the Mac, 30 s, or a disconnect.
 * Main thread only.
 */
class Ringer(private val context: Context, private val scope: CoroutineScope, private val send: (Message) -> Boolean) {
    private val session = RingSession()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private var ringtone: Ringtone? = null
    private var savedVolume: Int? = null
    private var torchId: String? = null
    private var volumeKeys: MediaSession? = null
    private var jobs: List<Job> = emptyList()

    fun handle(message: Message) {
        if (message !is Message.DeviceRing) return
        when (message.body.action) {
            RingAction.Start -> start()
            RingAction.Stop -> stop(StopReason.Mac)
        }
    }

    fun start() {
        if (!session.start(System.currentTimeMillis())) return
        savedVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrNull()
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = tone?.let { RingtoneManager.getRingtone(context, it) }?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
        volumeKeys = MediaSession(context, "MacDroidRing").apply {
            setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 1, 0) {
                override fun onAdjustVolume(direction: Int) = stop(StopReason.VolumeKey)
            })
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            isActive = true
        }
        torchId = backTorch()
        jobs = listOf(
            scope.launch { flashTorch() },
            scope.launch {
                delay(TIMEOUT_MS)
                stop(StopReason.Timeout)
            },
        )
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        send(Message.RingState(RingStateBody(true)))
    }

    fun stop(reason: StopReason) {
        if (!session.stop()) return
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        ringtone?.stop()
        ringtone = null
        volumeKeys?.run {
            isActive = false
            release()
        }
        volumeKeys = null
        torchId?.let { id -> runCatching { camera.setTorchMode(id, false) } }
        torchId = null
        savedVolume?.let { volume -> runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) } }
        savedVolume = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        send(Message.RingState(RingStateBody(false)))
    }

    private suspend fun flashTorch() {
        val id = torchId ?: return
        var on = true
        while (scope.isActive) {
            try {
                camera.setTorchMode(id, on)
            } catch (e: CameraAccessException) {
                return // camera busy: ring without the torch
            } catch (e: IllegalArgumentException) {
                return
            }
            on = !on
            delay(FLASH_MS)
        }
    }

    private fun backTorch(): String? = runCatching {
        camera.cameraIdList.firstOrNull { id ->
            val chars = camera.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    private fun notification(): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ring", NotificationManager.IMPORTANCE_HIGH))
        val stop = PendingIntent.getService(
            context, 3,
            Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_STOP_RING),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Ringing — requested by your Mac")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .setContentIntent(stop)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "ring"
        const val NOTIFICATION_ID = 40
        const val TIMEOUT_MS = 30_000L
        const val FLASH_MS = 250L
    }
}
