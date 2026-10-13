package dev.macdroid.app.platform.extras

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.macdroid.app.platform.notifications.MacDroidNotificationListener
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.extras.MediaArtwork
import dev.macdroid.core.extras.MediaControl
import dev.macdroid.core.extras.MediaSnapshot
import dev.macdroid.core.extras.MediaTracker
import dev.macdroid.core.protocol.Features
import dev.macdroid.core.protocol.MediaCommandKind
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches the phone's media sessions (available through MacDroid's notification access) and feeds [MediaTracker]
 * (spec §3.3). Session callbacks run on the main thread; artwork is encoded off it.
 */
class MediaWatcher(private val context: Context, private val connection: ConnectionManager, private val scope: CoroutineScope) {
    val tracker = MediaTracker(connection::send)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MacDroidNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val controllers = LinkedHashMap<String, Pair<MediaController, MediaController.Callback>>() // by package
    private val lastActive = HashMap<String, Long>()
    private val artCache = HashMap<String, MediaArtwork?>()
    private var watching = false
    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { rebind(it.orEmpty()) }

    fun start() {
        scope.launch {
            connection.state.map { it.macSupports(Features.MEDIA) }.distinctUntilChanged().collect { ready ->
                if (ready) tracker.connected() else tracker.disconnected()
            }
        }
        scope.launch {
            connection.incoming.collect { message ->
                val control = tracker.handle(message) ?: return@collect
                withContext(Dispatchers.Main) { apply(control) }
            }
        }
        main.post { refresh() }
    }

    /** (Re)reads the sessions; call when notification access may have changed. Main thread. */
    fun refresh() {
        try {
            if (!watching) {
                sessions.addOnActiveSessionsChangedListener(changed, listener, main)
                watching = true
            }
            rebind(sessions.getActiveSessions(listener))
        } catch (e: SecurityException) {
            rebind(emptyList()) // no notification access: nothing to show (spec §3.3)
        }
    }

    private fun rebind(list: List<MediaController>) {
        val present = list.associateBy { it.packageName }
        for ((pkg, pair) in controllers.toMap()) {
            if (pkg !in present) {
                pair.first.unregisterCallback(pair.second)
                controllers.remove(pkg)
            }
        }
        for ((pkg, controller) in present) {
            if (pkg in controllers) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onSessionDestroyed() {
                    controllers.remove(pkg)?.let { it.first.unregisterCallback(it.second) }
                    publish()
                }
            }
            controller.registerCallback(callback, main)
            controllers[pkg] = controller to callback
        }
        publish()
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val raw = controllers.values.mapNotNull { (controller, _) -> read(controller, now) }
        scope.launch(Dispatchers.Default) {
            tracker.update(raw.map { (snapshot, bitmap, key) -> snapshot.copy(art = artwork(key, bitmap)) })
        }
    }

    /** Reads one controller on the main thread; artwork is encoded later, off it. */
    private fun read(controller: MediaController, now: Long): Triple<MediaSnapshot, Bitmap?, String>? {
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState ?: return null
        val playing = state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING
        val pkg = controller.packageName
        if (playing) lastActive[pkg] = now
        val elapsed = if (playing) (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed else 0f
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val snapshot = MediaSnapshot(
            packageName = pkg,
            appName = appName(pkg),
            title = title,
            artist = artist,
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            positionMs = (state.position + elapsed.toLong()).coerceAtLeast(0),
            positionAt = now,
            playing = playing,
            canPrevious = (state.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            canNext = (state.actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            canSeek = (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L,
            art = null,
            lastActiveAt = lastActive[pkg] ?: 0L,
        )
        return Triple(snapshot, bitmap, "$pkg|$title|$artist")
    }

    private fun artwork(key: String, bitmap: Bitmap?): MediaArtwork? = synchronized(artCache) {
        artCache.getOrPut(key) { bitmap?.let(::encode) }
    }

    private fun encode(bitmap: Bitmap): MediaArtwork? = runCatching {
        val scale = minOf(1f, ART_PX.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).take(8).joinToString("") { "%02x".format(it) }
        MediaArtwork(hash, Base64.getEncoder().encodeToString(jpeg))
    }.getOrNull()

    private fun apply(control: MediaControl) {
        val controls = controllers[control.packageName]?.first?.transportControls ?: return
        when (control.command) {
            MediaCommandKind.Play -> controls.play()
            MediaCommandKind.Pause -> controls.pause()
            MediaCommandKind.Next -> controls.skipToNext()
            MediaCommandKind.Previous -> controls.skipToPrevious()
            MediaCommandKind.Seek -> control.positionMs?.let(controls::seekTo)
        }
    }

    private fun appName(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        const val ART_PX = 300
    }
}
