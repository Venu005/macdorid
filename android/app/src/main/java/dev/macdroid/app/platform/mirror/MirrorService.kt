package dev.macdroid.app.platform.mirror

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import dev.macdroid.app.graph
import dev.macdroid.app.platform.control.MacDroidControlService
import dev.macdroid.core.mirror.MirrorFrame
import dev.macdroid.core.mirror.MirrorSize
import dev.macdroid.core.mirror.SendPolicy
import dev.macdroid.core.mirror.SmallSendBuffer
import dev.macdroid.core.mirror.VideoAdmit
import dev.macdroid.core.protocol.MirrorStartBody
import dev.macdroid.core.protocol.MirrorStopReason
import dev.macdroid.core.tls.TlsClientFactory
import java.util.concurrent.TimeUnit

/** One mirroring session's capture: foreground service of type mediaProjection (spec §3.1–3.4, §3.6). Main thread except the encoder/audio callbacks. */
class MirrorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var audio: AudioCapturer? = null
    private var stream: MediaStreamClient? = null
    private var policy: SendPolicy? = null
    @Volatile private var lastConfig: ByteArray? = null
    private var options: MirrorStartBody? = null
    private var size: Triple<Int, Int, Int>? = null
    private var audioOn = false
    private var savedVolume: Int? = null
    private var finished = false
    private var awake: PowerManager.WakeLock? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) resizeIfNeeded() }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** The system stopped the service (task manager, low memory): clean up and tell the Mac (final review I6). */
    override fun onDestroy() {
        finish(MirrorStopReason.Ended)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish(MirrorStopReason.User)
            return START_NOT_STICKY
        }
        val body = intent?.let { readOptions(it) }
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val macName = graph.pairingStore.paired.value?.name ?: "your Mac"
        startForeground(MirrorNotifications.SERVICE_ID,
            MirrorNotifications.service(this, macName, Intent(this, MirrorService::class.java).setAction(ACTION_STOP)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (body == null || data == null) {
            finish(MirrorStopReason.Error)
            return START_NOT_STICKY
        }
        try {
            begin(body, intent.getIntExtra(EXTRA_CODE, 0), data)
        } catch (e: Exception) {
            // A codec or capture refusing to start must end this session, not the whole app (final review I6).
            finish(MirrorStopReason.Error)
        }
        return START_NOT_STICKY
    }

    private fun begin(body: MirrorStartBody, code: Int, data: Intent) {
        val mac = graph.pairingStore.paired.value ?: return finish(MirrorStopReason.Error)
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) ?: return finish(MirrorStopReason.Error)
        options = body
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { finish(MirrorStopReason.Ended) } }
        }, handler)
        val bitrate = MirrorSize.bitrate(body.maxSize)
        policy = SendPolicy(bitrate)
        val http = TlsClientFactory.pinned(graph.identity, mac.fingerprint, graph.httpClient).newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .socketFactory(SmallSendBuffer(SEND_BUFFER_BYTES)) // backlog stays visible to the send policy (final review I4)
            .build()
        stream = MediaStreamClient(http, mac.host, mac.port, body.sessionId,
            onOpen = {
                lastConfig?.let { stream?.send(it) }
                encoder?.requestKeyFrame()
            },
            onGone = { handler.post { finish(MirrorStopReason.Error) } },
        ).also { it.open() }
        val (w, h, rotation) = screenSize(body.maxSize).also { size = it }
        encoder = ScreenEncoder(mp, resources.displayMetrics.densityDpi, body.fps, bitrate, ::onVideo).also { it.start(w, h) }
        audioOn = body.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            AudioCapturer(mp, ::onAudio).let { capturer -> capturer.start().also { if (it) audio = capturer } }
        if (audioOn && MUTE_PHONE_SPEAKER) {
            val am = getSystemService(AudioManager::class.java)
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC).also { MirrorVolume.remember(this, it) }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
        displays.registerDisplayListener(displayListener, handler)
        // Android ends capture when the phone locks (probe Q3): keep the screen on while mirroring.
        @Suppress("DEPRECATION")
        awake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "MacDroid:mirror")
            .apply { acquire(4 * 60 * 60 * 1000L) }
        running = this
        graph.mirrorBridge.session.streaming(w, h, rotation, audio = audioOn, control = MacDroidControlService.running.value)
    }

    // Encoder thread.
    private fun onVideo(config: Boolean, keyFrame: Boolean, micros: Long, data: ByteArray) {
        val stream = stream ?: return
        val policy = policy ?: return
        if (config) {
            val frame = MirrorFrame.encode(MirrorFrame.TYPE_CONFIG, false, micros, data)
            lastConfig = frame
            stream.send(frame)
            return
        }
        when (synchronized(policy) { policy.video(keyFrame, stream.queuedBytes) }) {
            VideoAdmit.Send -> stream.send(MirrorFrame.encode(MirrorFrame.TYPE_VIDEO, keyFrame, micros, data))
            VideoAdmit.DropAndRequestKey -> encoder?.requestKeyFrame()
            VideoAdmit.Drop -> Unit
        }
    }

    // Audio thread.
    private fun onAudio(micros: Long, data: ByteArray, length: Int) {
        val stream = stream ?: return
        val policy = policy ?: return
        if (synchronized(policy) { policy.audio(stream.queuedBytes) }) {
            stream.send(MirrorFrame.encode(MirrorFrame.TYPE_AUDIO, false, micros, data, 0, length))
        }
    }

    /** The display's size as currently shown (natural size swapped when rotated), fitted to maxSize (spec §3.2). */
    private fun screenSize(maxSize: Int): Triple<Int, Int, Int> {
        val (w, h, rotation) = ScreenSize.current(this)
        val (fw, fh) = MirrorSize.fit(w, h, maxSize)
        return Triple(fw, fh, rotation)
    }

    private fun resizeIfNeeded() {
        val body = options ?: return
        val next = screenSize(body.maxSize)
        if (next == size) return
        size = next
        try {
            encoder?.resize(next.first, next.second)
        } catch (e: Exception) {
            finish(MirrorStopReason.Error)
            return
        }
        graph.mirrorBridge.session.streaming(next.first, next.second, next.third, audio = audioOn, control = MacDroidControlService.running.value)
    }

    /** [reason] null: the session already ended elsewhere (the Mac, a lost connection); nothing to report. */
    private fun finish(reason: MirrorStopReason?) {
        if (finished) return
        finished = true
        running = null
        runCatching { displays.unregisterDisplayListener(displayListener) }
        awake?.let { if (it.isHeld) it.release() }
        awake = null
        audio?.stop()
        encoder?.release()
        stream?.close()
        projection?.stop()
        savedVolume?.let {
            getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
            MirrorVolume.forget(this)
        }
        if (reason != null) graph.mirrorBridge.session.ended(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun readOptions(intent: Intent): MirrorStartBody? {
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return null
        return MirrorStartBody(session, intent.getIntExtra(EXTRA_MAX, 1920), intent.getIntExtra(EXTRA_FPS, 60), intent.getBooleanExtra(EXTRA_AUDIO, false))
    }

    companion object {
        private const val ACTION_STOP = "dev.macdroid.app.mirror.STOP"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_MAX = "maxSize"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        /** From the Task 1 probe, question 4: mute the phone's speaker while mirroring audio only if capture keeps working. */
        private const val MUTE_PHONE_SPEAKER = true
        /** ~130 ms at 8 Mbps. */
        private const val SEND_BUFFER_BYTES = 128 * 1024
        @Volatile private var running: MirrorService? = null

        fun start(context: Context, options: MirrorStartBody, resultCode: Int, data: Intent) {
            context.startForegroundService(Intent(context, MirrorService::class.java)
                .putExtra(EXTRA_SESSION, options.sessionId).putExtra(EXTRA_MAX, options.maxSize).putExtra(EXTRA_FPS, options.fps)
                .putExtra(EXTRA_AUDIO, options.audio).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data))
        }

        /** The session ended elsewhere; stop capturing without reporting again. */
        fun stop() {
            running?.let { service -> service.handler.post { service.finish(null) } }
        }

        fun requestKeyFrame() {
            running?.encoder?.requestKeyFrame()
        }
    }
}
