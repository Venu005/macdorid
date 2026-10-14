package dev.macdroid.app.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Debug-only: 30 s of capture → JPEGs every 2 s, frame rate per second, onStop, and 15 s of audio RMS. */
class MirrorProbeService : Service() {
    private val tag = MirrorProbeActivity.TAG
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("probe", "Probe", NotificationManager.IMPORTANCE_LOW))
        startForeground(77, Notification.Builder(this, "probe").setContentTitle("Mirror probe").setSmallIcon(android.R.drawable.ic_menu_camera).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")!!
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("code", 0), data)
            ?: run { Log.w(tag, "no projection"); stopSelf(); return START_NOT_STICKY }
        projection = mp
        val thread = HandlerThread("probe").apply { start() }
        val handler = Handler(thread.looper)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.w(tag, "projection onStop") }
        }, handler)
        val metrics = resources.displayMetrics
        val scale = 360f / maxOf(metrics.widthPixels, metrics.heightPixels)
        val w = (metrics.widthPixels * scale).toInt()
        val h = (metrics.heightPixels * scale).toInt()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var frames = 0
        var saved = 0L
        val dir = File(getExternalFilesDir("probe"), "").apply { mkdirs() }
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            frames++
            val now = System.currentTimeMillis()
            if (now - saved >= 2_000) {
                saved = now
                val plane = image.planes[0]
                val bitmap = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                File(dir, "frame-$now.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                Log.w(tag, "saved frame-$now.jpg")
            }
            image.close()
        }, handler)
        mp.createVirtualDisplay("probe", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
        thread {
            repeat(30) {
                Thread.sleep(1_000)
                Log.w(tag, "fps=$frames")
                frames = 0
            }
        }
        thread { probeAudio(mp) }
        handler.postDelayed({ Log.w(tag, "probe done"); mp.stop(); stopSelf() }, 32_000)
        return START_NOT_STICKY
    }

    private fun probeAudio(mp: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val record = runCatching { AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config).build() }
            .getOrElse { Log.w(tag, "audio capture unavailable: $it"); return }
        val audio = getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val buffer = ShortArray(48_000 * 2)
        record.startRecording()
        for (second in 1..15) {
            if (second == 11) audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            val n = record.read(buffer, 0, buffer.size)
            var sum = 0.0
            for (i in 0 until n) sum += buffer[i].toDouble() * buffer[i]
            Log.w(tag, "audio second=$second muted=${second >= 11} rms=${if (n > 0) sqrt(sum / n).toInt() else -1}")
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        record.stop()
        record.release()
    }
}
