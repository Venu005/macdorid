package dev.macdroid.core.protocol

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class MalformedMessageException(cause: Throwable? = null) : Exception("malformed message", cause)

object MessageCodec {
    const val PROTOCOL_VERSION = 1

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(message: Message, id: String = UUID.randomUUID().toString()): String {
        val (type, body) = when (message) {
            is Message.Hello -> "hello" to json.encodeToJsonElement(HelloBody.serializer(), message.body)
            is Message.DeviceStatus -> "device.status" to json.encodeToJsonElement(DeviceStatusBody.serializer(), message.body)
            is Message.TransferOffer -> "transfer.offer" to json.encodeToJsonElement(TransferOfferBody.serializer(), message.body)
            is Message.TransferAccept -> "transfer.accept" to json.encodeToJsonElement(TransferIdBody.serializer(), message.body)
            is Message.TransferReject -> "transfer.reject" to json.encodeToJsonElement(TransferRejectBody.serializer(), message.body)
            is Message.TransferFinish -> "transfer.finish" to json.encodeToJsonElement(TransferFinishBody.serializer(), message.body)
            is Message.TransferResult -> "transfer.result" to json.encodeToJsonElement(TransferResultBody.serializer(), message.body)
            is Message.TransferCancel -> "transfer.cancel" to json.encodeToJsonElement(TransferIdBody.serializer(), message.body)
            is Message.NotificationPosted -> "notification.posted" to json.encodeToJsonElement(NotificationPostedBody.serializer(), message.body)
            is Message.NotificationRemoved -> "notification.removed" to json.encodeToJsonElement(NotificationKeyBody.serializer(), message.body)
            is Message.NotificationSync -> "notification.sync" to json.encodeToJsonElement(NotificationSyncBody.serializer(), message.body)
            is Message.NotificationIcon -> "notification.icon" to json.encodeToJsonElement(NotificationIconBody.serializer(), message.body)
            is Message.NotificationApps -> "notification.apps" to json.encodeToJsonElement(NotificationAppsBody.serializer(), message.body)
            is Message.NotificationReply -> "notification.reply" to json.encodeToJsonElement(NotificationReplyBody.serializer(), message.body)
            is Message.NotificationAction -> "notification.action" to json.encodeToJsonElement(NotificationActionBody.serializer(), message.body)
            is Message.NotificationDismiss -> "notification.dismiss" to json.encodeToJsonElement(NotificationKeyBody.serializer(), message.body)
            is Message.NotificationMute -> "notification.mute" to json.encodeToJsonElement(NotificationMuteBody.serializer(), message.body)
            is Message.NotificationError -> "notification.error" to json.encodeToJsonElement(NotificationErrorBody.serializer(), message.body)
            is Message.ClipboardSet -> "clipboard.set" to json.encodeToJsonElement(ClipboardSetBody.serializer(), message.body)
            is Message.DeviceRing -> "device.ring" to json.encodeToJsonElement(DeviceRingBody.serializer(), message.body)
            is Message.RingState -> "device.ringState" to json.encodeToJsonElement(RingStateBody.serializer(), message.body)
            is Message.LinkOpen -> "link.open" to json.encodeToJsonElement(LinkOpenBody.serializer(), message.body)
            is Message.MediaState -> "media.state" to json.encodeToJsonElement(MediaStateBody.serializer(), message.body)
            is Message.MediaArt -> "media.art" to json.encodeToJsonElement(MediaArtBody.serializer(), message.body)
            is Message.MediaCommand -> "media.command" to json.encodeToJsonElement(MediaCommandBody.serializer(), message.body)
            is Message.PhotosAlbums -> "photos.albums" to json.encodeToJsonElement(PhotosAlbumsBody.serializer(), message.body)
            is Message.PhotosAlbumList -> "photos.albumList" to json.encodeToJsonElement(PhotosAlbumListBody.serializer(), message.body)
            is Message.PhotosQuery -> "photos.query" to json.encodeToJsonElement(PhotosQueryBody.serializer(), message.body)
            is Message.PhotosPage -> "photos.page" to json.encodeToJsonElement(PhotosPageBody.serializer(), message.body)
            is Message.PhotosThumbs -> "photos.thumbs" to json.encodeToJsonElement(PhotosThumbsBody.serializer(), message.body)
            is Message.PhotosThumb -> "photos.thumb" to json.encodeToJsonElement(PhotosThumbBody.serializer(), message.body)
            is Message.PhotosFetch -> "photos.fetch" to json.encodeToJsonElement(PhotosFetchBody.serializer(), message.body)
            is Message.PhotosFetchFailed -> "photos.fetchFailed" to json.encodeToJsonElement(PhotosFetchFailedBody.serializer(), message.body)
            Message.PhotosChanged -> "photos.changed" to JsonObject(emptyMap())
            is Message.Unknown -> throw IllegalArgumentException("cannot encode unknown message type ${message.type}")
        }
        return buildJsonObject {
            put("v", PROTOCOL_VERSION)
            put("type", type)
            put("id", id)
            put("body", body)
        }.toString()
    }

    fun decode(text: String): Message {
        try {
            val envelope = json.parseToJsonElement(text).jsonObject
            val type = (envelope["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw MalformedMessageException()
            val body = envelope["body"] ?: JsonObject(emptyMap())
            return when (type) {
                "hello" -> Message.Hello(json.decodeFromJsonElement(HelloBody.serializer(), body))
                "device.status" -> Message.DeviceStatus(json.decodeFromJsonElement(DeviceStatusBody.serializer(), body))
                "transfer.offer" -> Message.TransferOffer(json.decodeFromJsonElement(TransferOfferBody.serializer(), body))
                "transfer.accept" -> Message.TransferAccept(json.decodeFromJsonElement(TransferIdBody.serializer(), body))
                "transfer.reject" -> Message.TransferReject(json.decodeFromJsonElement(TransferRejectBody.serializer(), body))
                "transfer.finish" -> Message.TransferFinish(json.decodeFromJsonElement(TransferFinishBody.serializer(), body))
                "transfer.result" -> Message.TransferResult(json.decodeFromJsonElement(TransferResultBody.serializer(), body))
                "transfer.cancel" -> Message.TransferCancel(json.decodeFromJsonElement(TransferIdBody.serializer(), body))
                "notification.posted" -> Message.NotificationPosted(json.decodeFromJsonElement(NotificationPostedBody.serializer(), body))
                "notification.removed" -> Message.NotificationRemoved(json.decodeFromJsonElement(NotificationKeyBody.serializer(), body))
                "notification.sync" -> Message.NotificationSync(json.decodeFromJsonElement(NotificationSyncBody.serializer(), body))
                "notification.icon" -> Message.NotificationIcon(json.decodeFromJsonElement(NotificationIconBody.serializer(), body))
                "notification.apps" -> Message.NotificationApps(json.decodeFromJsonElement(NotificationAppsBody.serializer(), body))
                "notification.reply" -> Message.NotificationReply(json.decodeFromJsonElement(NotificationReplyBody.serializer(), body))
                "notification.action" -> Message.NotificationAction(json.decodeFromJsonElement(NotificationActionBody.serializer(), body))
                "notification.dismiss" -> Message.NotificationDismiss(json.decodeFromJsonElement(NotificationKeyBody.serializer(), body))
                "notification.mute" -> Message.NotificationMute(json.decodeFromJsonElement(NotificationMuteBody.serializer(), body))
                "notification.error" -> Message.NotificationError(json.decodeFromJsonElement(NotificationErrorBody.serializer(), body))
                "clipboard.set" -> Message.ClipboardSet(json.decodeFromJsonElement(ClipboardSetBody.serializer(), body))
                "device.ring" -> Message.DeviceRing(json.decodeFromJsonElement(DeviceRingBody.serializer(), body))
                "device.ringState" -> Message.RingState(json.decodeFromJsonElement(RingStateBody.serializer(), body))
                "link.open" -> Message.LinkOpen(json.decodeFromJsonElement(LinkOpenBody.serializer(), body))
                "media.state" -> Message.MediaState(json.decodeFromJsonElement(MediaStateBody.serializer(), body))
                "media.art" -> Message.MediaArt(json.decodeFromJsonElement(MediaArtBody.serializer(), body))
                "media.command" -> Message.MediaCommand(json.decodeFromJsonElement(MediaCommandBody.serializer(), body))
                "photos.albums" -> Message.PhotosAlbums(json.decodeFromJsonElement(PhotosAlbumsBody.serializer(), body))
                "photos.albumList" -> Message.PhotosAlbumList(json.decodeFromJsonElement(PhotosAlbumListBody.serializer(), body))
                "photos.query" -> Message.PhotosQuery(json.decodeFromJsonElement(PhotosQueryBody.serializer(), body))
                "photos.page" -> Message.PhotosPage(json.decodeFromJsonElement(PhotosPageBody.serializer(), body))
                "photos.thumbs" -> Message.PhotosThumbs(json.decodeFromJsonElement(PhotosThumbsBody.serializer(), body))
                "photos.thumb" -> Message.PhotosThumb(json.decodeFromJsonElement(PhotosThumbBody.serializer(), body))
                "photos.fetch" -> Message.PhotosFetch(json.decodeFromJsonElement(PhotosFetchBody.serializer(), body))
                "photos.fetchFailed" -> Message.PhotosFetchFailed(json.decodeFromJsonElement(PhotosFetchFailedBody.serializer(), body))
                "photos.changed" -> Message.PhotosChanged
                else -> Message.Unknown(type)
            }
        } catch (e: IllegalArgumentException) { // includes SerializationException
            throw MalformedMessageException(e)
        }
    }
}
