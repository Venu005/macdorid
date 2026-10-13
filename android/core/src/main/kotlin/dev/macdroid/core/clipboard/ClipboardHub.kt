package dev.macdroid.core.clipboard

import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.protocol.ClipboardLimits
import dev.macdroid.core.protocol.ClipboardSetBody
import dev.macdroid.core.protocol.Features
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.MessageCodec
import dev.macdroid.core.transfer.OutgoingFile
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One clipboard item on the Clipboard tab's "Recent" list. */
data class ClipItem(val id: String, val kind: Kind, val text: String?, val imageUri: String?, val direction: Direction, val at: Long) {
    enum class Kind { Text, Link, Image }
    enum class Direction { ToMac, FromMac }
}

enum class SendResult { Sent, NotConnected, MacTooOld, Empty, TooLarge }

/** What the app must put on the phone's clipboard. */
sealed interface ClipboardApply {
    data class Text(val text: String) : ClipboardApply
    data class Image(val uri: String, val mime: String) : ClipboardApply
}

/**
 * The phone side of clipboard sync (spec §4.2–4.4): explicit sends (always sent, spec §8.2),
 * received items, and the in-memory "Recent" list. Thread-safe.
 */
class ClipboardHub(
    private val send: (Message) -> Boolean,
    private val enqueueImage: (OutgoingFile) -> Unit,
    private val receiveEnabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val RECENT_LIMIT = 10
        private const val ZERO_ID = "00000000-0000-0000-0000-000000000000"
        private val LINK = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

        fun kindOf(text: String): ClipItem.Kind = if (LINK.matches(text.trim())) ClipItem.Kind.Link else ClipItem.Kind.Text

        /** The encoded `clipboard.set` frame, JSON escaping included, fits the 1 MiB limit (spec §8.1). */
        fun fitsInAFrame(text: String): Boolean =
            MessageCodec.encode(Message.ClipboardSet(ClipboardSetBody(ZERO_ID, text = text)), id = ZERO_ID)
                .toByteArray(Charsets.UTF_8).size <= ClipboardLimits.MAX_FRAME_BYTES
    }

    private val _recent = MutableStateFlow<List<ClipItem>>(emptyList())
    val recent: StateFlow<List<ClipItem>> = _recent.asStateFlow()

    fun sendText(text: String?, state: ConnectionState): SendResult {
        val value = text?.takeIf { it.isNotEmpty() } ?: return SendResult.Empty
        reachable(state)?.let { return it }
        if (!fitsInAFrame(value)) return SendResult.TooLarge
        if (!send(Message.ClipboardSet(ClipboardSetBody(UUID.randomUUID().toString(), text = value)))) return SendResult.NotConnected
        record(ClipItem(UUID.randomUUID().toString(), kindOf(value), value, null, ClipItem.Direction.ToMac, clock()))
        return SendResult.Sent
    }

    /** [file] is a PNG the app already wrote to its own storage. */
    fun sendImage(file: OutgoingFile?, state: ConnectionState): SendResult {
        if (file == null) return SendResult.Empty
        reachable(state)?.let { return it }
        if (file.size > ClipboardLimits.MAX_IMAGE_BYTES) return SendResult.TooLarge
        enqueueImage(file)
        record(ClipItem(UUID.randomUUID().toString(), ClipItem.Kind.Image, null, file.source, ClipItem.Direction.ToMac, clock()))
        return SendResult.Sent
    }

    fun handle(message: Message): ClipboardApply? {
        if (message !is Message.ClipboardSet || !receiveEnabled()) return null
        val text = message.body.text
        record(ClipItem(UUID.randomUUID().toString(), kindOf(text), text, null, ClipItem.Direction.FromMac, clock()))
        return ClipboardApply.Text(text)
    }

    fun imageReceived(uri: String, mime: String): ClipboardApply? {
        if (!receiveEnabled()) return null
        record(ClipItem(UUID.randomUUID().toString(), ClipItem.Kind.Image, null, uri, ClipItem.Direction.FromMac, clock()))
        return ClipboardApply.Image(uri, mime)
    }

    fun item(id: String): ClipItem? = _recent.value.firstOrNull { it.id == id }

    private fun reachable(state: ConnectionState): SendResult? = when {
        state !is ConnectionState.Connected -> SendResult.NotConnected
        !state.macSupports(Features.CLIPBOARD) -> SendResult.MacTooOld
        else -> null
    }

    private fun record(item: ClipItem) {
        _recent.update { (listOf(item) + it).take(RECENT_LIMIT) }
    }
}
