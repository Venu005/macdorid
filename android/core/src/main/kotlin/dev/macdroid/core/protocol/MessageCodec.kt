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
                else -> Message.Unknown(type)
            }
        } catch (e: IllegalArgumentException) { // includes SerializationException
            throw MalformedMessageException(e)
        }
    }
}
