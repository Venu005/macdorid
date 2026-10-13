package dev.macdroid.core.extras

import dev.macdroid.core.protocol.MediaArtBody
import dev.macdroid.core.protocol.MediaCommandKind
import dev.macdroid.core.protocol.MediaStateBody
import dev.macdroid.core.protocol.Message

data class MediaArtwork(val hash: String, val jpegBase64: String)

/** One media session as the app read it. [lastActiveAt] is when it was last playing (epoch ms). */
data class MediaSnapshot(
    val packageName: String,
    val appName: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val positionMs: Long,
    val positionAt: Long,
    val playing: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canSeek: Boolean,
    val art: MediaArtwork?,
    val lastActiveAt: Long,
)

/** A command for the app to apply to [packageName]'s media controller. */
data class MediaControl(val packageName: String, val command: MediaCommandKind, val positionMs: Long?)

/**
 * Chooses which phone media session the Mac shows and sends `media.state` only when something meaningful changes
 * (spec §2, §3.3). Thread-safe.
 */
class MediaTracker(private val send: (Message) -> Boolean, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val STALE_MS = 300_000L
        const val JUMP_MS = 2_000L
    }

    private val lock = Any()
    private var connected = false
    private var latest: List<MediaSnapshot> = emptyList()
    private var shown: MediaSnapshot? = null
    private var lastSent: MediaStateBody? = null
    private val artSent = HashSet<String>()

    fun connected() {
        synchronized(lock) {
            connected = true
            artSent.clear()
            lastSent = null
            publish(force = true)
        }
    }

    fun disconnected() {
        synchronized(lock) { connected = false }
    }

    fun update(sessions: List<MediaSnapshot>) {
        synchronized(lock) {
            latest = sessions
            publish(force = false)
        }
    }

    fun handle(message: Message): MediaControl? {
        if (message !is Message.MediaCommand) return null
        val target = synchronized(lock) { shown } ?: return null
        return MediaControl(target.packageName, message.body.command, message.body.positionMs)
    }

    private fun choose(now: Long): MediaSnapshot? =
        latest.firstOrNull { it.playing }
            ?: latest.filter { now - it.lastActiveAt <= STALE_MS }.maxByOrNull { it.lastActiveAt }

    private fun publish(force: Boolean) {
        val now = clock()
        shown = choose(now)
        if (!connected) return
        val current = shown
        val body = current?.toBody() ?: MediaStateBody(active = false)
        val previous = lastSent
        if (!force && previous != null && !meaningfullyDifferent(previous, body)) return
        if (current?.art != null && artSent.add(current.art.hash)) {
            send(Message.MediaArt(MediaArtBody(current.art.hash, current.art.jpegBase64)))
        }
        if (send(Message.MediaState(body))) lastSent = body
    }

    private fun meaningfullyDifferent(old: MediaStateBody, new: MediaStateBody): Boolean {
        if (old.active != new.active) return true
        if (!new.active) return false
        if (old.packageName != new.packageName || old.title != new.title || old.artist != new.artist ||
            old.durationMs != new.durationMs || old.playing != new.playing || old.artHash != new.artHash ||
            old.canPrevious != new.canPrevious || old.canNext != new.canNext || old.canSeek != new.canSeek
        ) return true
        val projected = (old.positionMs ?: 0) + if (old.playing == true) (new.positionAt ?: 0) - (old.positionAt ?: 0) else 0
        return kotlin.math.abs((new.positionMs ?: 0) - projected) > JUMP_MS
    }

    private fun MediaSnapshot.toBody() = MediaStateBody(
        active = true, packageName = packageName, appName = appName, title = title, artist = artist,
        durationMs = durationMs, positionMs = positionMs, positionAt = positionAt, playing = playing,
        canPrevious = canPrevious, canNext = canNext, canSeek = canSeek, artHash = art?.hash.orEmpty(),
    )
}
