package dev.macdroid.core.protocol

import kotlinx.serialization.Serializable

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

/** A control-channel message. Later plans add notification, clipboard and transfer types. */
sealed interface Message {
    data class Hello(val body: HelloBody) : Message
    data class DeviceStatus(val body: DeviceStatusBody) : Message
    /** A type this version doesn't understand; decoded so callers can ignore it, never encoded. */
    data class Unknown(val type: String) : Message
}
