package dev.macdroid.core.mirror

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.MirrorPhaseName
import dev.macdroid.core.protocol.MirrorStartBody
import dev.macdroid.core.protocol.MirrorStateBody
import dev.macdroid.core.protocol.MirrorStopReason

/** Encoder size and bitrate (spec §3.2). */
object MirrorSize {
    fun fit(width: Int, height: Int, maxSize: Int): Pair<Int, Int> {
        val scale = minOf(1.0, maxSize.toDouble() / maxOf(width, height))
        fun round(v: Int) = maxOf(16, ((v * scale).toInt() / 16) * 16)
        return round(width) to round(height)
    }

    fun bitrate(maxSize: Int): Int = if (maxSize >= 1920) 8_000_000 else 4_000_000
}

enum class VideoAdmit { Send, Drop, DropAndRequestKey }

/**
 * Never let latency build up behind a slow network (spec §3.4). Thresholds are time at the stream's bitrate:
 * video over 300 ms queued → drop until a key frame fits; audio over 200 ms queued → drop the chunk.
 */
class SendPolicy(bitrateBps: Int) {
    val videoLimitBytes: Long = bitrateBps.toLong() / 8 * 300 / 1000
    val audioLimitBytes: Long = bitrateBps.toLong() / 8 * 200 / 1000
    private var dropping = false

    fun video(keyFrame: Boolean, queuedBytes: Long): VideoAdmit {
        if (queuedBytes > videoLimitBytes) {
            if (dropping) return VideoAdmit.Drop
            dropping = true
            return if (keyFrame) VideoAdmit.Drop else VideoAdmit.DropAndRequestKey
        }
        if (dropping) {
            if (!keyFrame) return VideoAdmit.Drop
            dropping = false
        }
        return VideoAdmit.Send
    }

    fun audio(queuedBytes: Long): Boolean = queuedBytes <= audioLimitBytes
}

/**
 * The phone's side of one mirroring session (spec §3.1, §3.6): reports state to the Mac and decides when it ends.
 * [onEnd] runs when something other than the capture itself ended the session (the Mac, a timeout, a lost connection),
 * so the bridge can stop the capture service. Not thread-safe: call from one thread.
 */
class MirrorPhoneSession(
    private val send: (Message) -> Boolean,
    private val clockMs: () -> Long,
    private val onEnd: (MirrorStopReason) -> Unit,
) {
    var current: String? = null
        private set
    var options: MirrorStartBody? = null
        private set
    var isStreaming = false
        private set
    private var startedAt = 0L

    fun start(body: MirrorStartBody): Boolean {
        if (current != null) endHere(MirrorStopReason.Mac, report = true)
        current = body.sessionId
        options = body
        isStreaming = false
        startedAt = clockMs()
        send(Message.MirrorState(MirrorStateBody(body.sessionId, MirrorPhaseName.Starting)))
        return true
    }

    fun consentDenied() {
        val id = current ?: return
        send(Message.MirrorState(MirrorStateBody(id, MirrorPhaseName.Stopped, reason = MirrorStopReason.Denied)))
        clear()
    }

    fun streaming(width: Int, height: Int, rotation: Int, audio: Boolean, control: Boolean) {
        val id = current ?: return
        isStreaming = true
        send(Message.MirrorState(MirrorStateBody(id, MirrorPhaseName.Streaming, width, height, rotation, audio = audio, control = control)))
    }

    fun ended(reason: MirrorStopReason) {
        val id = current ?: return
        send(Message.MirrorState(MirrorStateBody(id, MirrorPhaseName.Stopped, reason = reason)))
        clear()
    }

    fun macStopped(sessionId: String) {
        if (sessionId == current) endHere(MirrorStopReason.Mac, report = false)
    }

    fun checkTimeout() {
        val id = current ?: return
        if (isStreaming || clockMs() - startedAt < CONSENT_TIMEOUT_MS) return
        send(Message.MirrorState(MirrorStateBody(id, MirrorPhaseName.Stopped, reason = MirrorStopReason.ConsentTimeout)))
        clear()
        onEnd(MirrorStopReason.ConsentTimeout)
    }

    fun connectionLost() {
        if (current != null) endHere(MirrorStopReason.Mac, report = false)
    }

    private fun endHere(reason: MirrorStopReason, report: Boolean) {
        val id = current ?: return
        if (report) send(Message.MirrorState(MirrorStateBody(id, MirrorPhaseName.Stopped, reason = reason)))
        clear()
        onEnd(reason)
    }

    private fun clear() {
        current = null
        options = null
        isStreaming = false
    }

    companion object {
        const val CONSENT_TIMEOUT_MS = 60_000L
    }
}
