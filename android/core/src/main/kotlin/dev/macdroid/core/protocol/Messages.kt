package dev.macdroid.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class HelloBody(
    val protocol: Int = MessageCodec.PROTOCOL_VERSION,
    val app: String,
    val device: String,
    val features: List<String> = emptyList(),
)

@Serializable
data class DeviceStatusBody(val battery: Int, val charging: Boolean)

@Serializable
data class PairRequest(val code: String, val deviceName: String, val model: String)

@Serializable
data class PairResponse(val macName: String)

@Serializable
data class ServerInfo(val macName: String, val protocol: Int)

/** What a transfer is for; absent on the wire means a file (spec §8.9). */
@Serializable
enum class TransferPurpose {
    @SerialName("file") File,
    @SerialName("clipboard") Clipboard,
    /** A photo the Mac asked for with `photos.fetch` (Plan 6). */
    @SerialName("photo") Photo,
}

@Serializable
data class TransferOfferBody(
    val transferId: String,
    val name: String,
    val size: Long,
    val mime: String,
    /** null for files, so it's left off the wire; Clipboard for clipboard images (spec §2.3). */
    val purpose: TransferPurpose? = null,
)

/** Body of `transfer.accept` and `transfer.cancel`. */
@Serializable
data class TransferIdBody(val transferId: String)

@Serializable
enum class TransferRejectReason {
    @SerialName("no_space") NoSpace,
    @SerialName("declined") Declined,
    @SerialName("invalid") Invalid,
}

@Serializable
data class TransferRejectBody(val transferId: String, val reason: TransferRejectReason)

/** [sha256] is the lowercase hex SHA-256 of the whole file. */
@Serializable
data class TransferFinishBody(val transferId: String, val sha256: String)

@Serializable
enum class TransferResultError {
    @SerialName("checksum_mismatch") ChecksumMismatch,
    @SerialName("no_space") NoSpace,
    @SerialName("io") Io,
}

@Serializable
data class TransferResultBody(val transferId: String, val ok: Boolean, val error: TransferResultError? = null)

@Serializable
enum class RingAction { @SerialName("start") Start, @SerialName("stop") Stop }

@Serializable
data class DeviceRingBody(val action: RingAction)

@Serializable
data class RingStateBody(val ringing: Boolean)

@Serializable
data class LinkOpenBody(val url: String)

/** What's playing; `active = false` means nothing (other fields null, so they're left off the wire). */
@Serializable
data class MediaStateBody(
    val active: Boolean,
    @SerialName("package") val packageName: String? = null,
    val appName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val positionAt: Long? = null,
    val playing: Boolean? = null,
    val canPrevious: Boolean? = null,
    val canNext: Boolean? = null,
    val canSeek: Boolean? = null,
    val artHash: String? = null,
)

@Serializable
data class MediaArtBody(val hash: String, val jpeg: String)

@Serializable
enum class MediaCommandKind {
    @SerialName("play") Play,
    @SerialName("pause") Pause,
    @SerialName("next") Next,
    @SerialName("previous") Previous,
    @SerialName("seek") Seek,
}

@Serializable
data class MediaCommandBody(val command: MediaCommandKind, val positionMs: Long? = null)

object ClipboardLimits {
    /** An encoded `clipboard.set` frame must fit the Mac's WebSocket frame limit (spec §8.1). */
    const val MAX_FRAME_BYTES = 1_048_576
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
}

@Serializable
enum class ClipboardKind { @SerialName("text") Text }

@Serializable
data class ClipboardSetBody(val itemId: String, val kind: ClipboardKind = ClipboardKind.Text, val text: String)

/** Names used in `hello.features`; a feature is active only when both sides list it (spec §2.1). */
object Features {
    const val FILES = "files"
    const val NOTIFICATIONS = "notifications"
    const val CLIPBOARD = "clipboard"
    const val RING = "ring"
    const val LINKS = "links"
    const val MEDIA = "media"
    const val PHOTOS = "photos"
    const val MIRROR = "mirror"
    /** What this app implements. */
    val SUPPORTED: List<String> = listOf(FILES, NOTIFICATIONS, CLIPBOARD, RING, LINKS, MEDIA, PHOTOS, MIRROR)
}

@Serializable
data class NotificationButton(val index: Int, val title: String)

@Serializable
data class NotificationPostedBody(
    val key: String,
    @SerialName("package") val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val actions: List<NotificationButton>,
    val canReply: Boolean,
    val iconHash: String,
    val isUpdate: Boolean,
)

/** Body of `notification.removed` and `notification.dismiss`. */
@Serializable
data class NotificationKeyBody(val key: String)

@Serializable
data class NotificationSyncBody(val keys: List<String>)

@Serializable
data class NotificationIconBody(val hash: String, val png: String)

@Serializable
data class PhoneAppBody(@SerialName("package") val packageName: String, val appName: String, val iconHash: String, val muted: Boolean)

@Serializable
data class NotificationAppsBody(val forwarding: Boolean, val access: Boolean, val apps: List<PhoneAppBody>)

@Serializable
data class NotificationReplyBody(val key: String, val text: String)

@Serializable
data class NotificationActionBody(val key: String, val actionIndex: Int)

@Serializable
data class NotificationMuteBody(@SerialName("package") val packageName: String, val muted: Boolean)

@Serializable
enum class NotificationErrorReason {
    @SerialName("gone") Gone,
    @SerialName("failed") Failed,
}

@Serializable
data class NotificationErrorBody(val key: String, val reason: NotificationErrorReason)

/** A control-channel message. */
// Photos (Plan 6)

@Serializable enum class PhotoAccess { @SerialName("full") Full, @SerialName("partial") Partial, @SerialName("none") None }
@Serializable enum class PhotoKindFilter { @SerialName("all") All, @SerialName("photos") Photos, @SerialName("videos") Videos }
@Serializable enum class PhotoKind { @SerialName("photo") Photo, @SerialName("video") Video }

@Serializable data class PhotoAlbumBody(val id: String, val name: String, val count: Int, val coverId: String? = null)

@Serializable data class PhotoItemBody(
    val id: String,
    val kind: PhotoKind,
    val name: String,
    val mime: String,
    val takenAt: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long? = null,
)

@Serializable data class PhotosAlbumsBody(val requestId: String)
@Serializable data class PhotosAlbumListBody(val requestId: String, val access: PhotoAccess, val albums: List<PhotoAlbumBody>)
@Serializable data class PhotosQueryBody(val requestId: String, val albumId: String, val kind: PhotoKindFilter, val cursor: String? = null, val limit: Int)
@Serializable data class PhotosPageBody(val requestId: String, val access: PhotoAccess, val items: List<PhotoItemBody>, val nextCursor: String? = null)
@Serializable data class PhotosThumbsBody(val ids: List<String>)
@Serializable data class PhotosThumbBody(val id: String, val jpeg: String)
@Serializable data class PhotosFetchBody(val id: String, val transferId: String)
@Serializable enum class PhotoFetchFailure { @SerialName("gone") Gone, @SerialName("denied") Denied }
@Serializable data class PhotosFetchFailedBody(val transferId: String, val reason: PhotoFetchFailure)

// Mirroring (Plan 7)

@Serializable data class MirrorStartBody(val sessionId: String, val maxSize: Int, val fps: Int, val audio: Boolean)

@Serializable enum class MirrorPhaseName { @SerialName("starting") Starting, @SerialName("streaming") Streaming, @SerialName("stopped") Stopped }

@Serializable enum class MirrorStopReason {
    @SerialName("user") User, @SerialName("mac") Mac, @SerialName("denied") Denied, @SerialName("consentTimeout") ConsentTimeout,
    @SerialName("noPermission") NoPermission, @SerialName("ended") Ended, @SerialName("error") Error,
}

@Serializable data class MirrorStateBody(
    val sessionId: String,
    val state: MirrorPhaseName,
    val width: Int? = null,
    val height: Int? = null,
    val rotation: Int? = null,
    val reason: MirrorStopReason? = null,
    val audio: Boolean? = null,
    val control: Boolean? = null,
)

@Serializable data class MirrorSessionBody(val sessionId: String)
@Serializable data class MirrorPingBody(val t0: Long)
@Serializable data class MirrorPongBody(val t0: Long, val t1: Long, val t2: Long)

sealed interface Message {
    data class Hello(val body: HelloBody) : Message
    data class DeviceStatus(val body: DeviceStatusBody) : Message
    data class TransferOffer(val body: TransferOfferBody) : Message
    data class TransferAccept(val body: TransferIdBody) : Message
    data class TransferReject(val body: TransferRejectBody) : Message
    data class TransferFinish(val body: TransferFinishBody) : Message
    data class TransferResult(val body: TransferResultBody) : Message
    data class TransferCancel(val body: TransferIdBody) : Message
    data class NotificationPosted(val body: NotificationPostedBody) : Message
    data class NotificationRemoved(val body: NotificationKeyBody) : Message
    data class NotificationSync(val body: NotificationSyncBody) : Message
    data class NotificationIcon(val body: NotificationIconBody) : Message
    data class NotificationApps(val body: NotificationAppsBody) : Message
    data class NotificationReply(val body: NotificationReplyBody) : Message
    data class NotificationAction(val body: NotificationActionBody) : Message
    data class NotificationDismiss(val body: NotificationKeyBody) : Message
    data class NotificationMute(val body: NotificationMuteBody) : Message
    data class NotificationError(val body: NotificationErrorBody) : Message
    data class ClipboardSet(val body: ClipboardSetBody) : Message
    data class DeviceRing(val body: DeviceRingBody) : Message
    data class RingState(val body: RingStateBody) : Message
    data class LinkOpen(val body: LinkOpenBody) : Message
    data class MediaState(val body: MediaStateBody) : Message
    data class MediaArt(val body: MediaArtBody) : Message
    data class MediaCommand(val body: MediaCommandBody) : Message
    data class PhotosAlbums(val body: PhotosAlbumsBody) : Message
    data class PhotosAlbumList(val body: PhotosAlbumListBody) : Message
    data class PhotosQuery(val body: PhotosQueryBody) : Message
    data class PhotosPage(val body: PhotosPageBody) : Message
    data class PhotosThumbs(val body: PhotosThumbsBody) : Message
    data class PhotosThumb(val body: PhotosThumbBody) : Message
    data class PhotosFetch(val body: PhotosFetchBody) : Message
    data class PhotosFetchFailed(val body: PhotosFetchFailedBody) : Message
    data object PhotosChanged : Message
    data class MirrorStart(val body: MirrorStartBody) : Message
    data class MirrorState(val body: MirrorStateBody) : Message
    data class MirrorStop(val body: MirrorSessionBody) : Message
    data class MirrorKeyframe(val body: MirrorSessionBody) : Message
    data class MirrorPing(val body: MirrorPingBody) : Message
    data class MirrorPong(val body: MirrorPongBody) : Message
    /** A type this version doesn't understand; decoded so callers can ignore it, never encoded. */
    data class Unknown(val type: String) : Message
}
