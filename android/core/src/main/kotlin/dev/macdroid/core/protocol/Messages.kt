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

@Serializable
data class TransferOfferBody(val transferId: String, val name: String, val size: Long, val mime: String)

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

/** A control-channel message. Later plans add notification, clipboard and transfer types. */
sealed interface Message {
    data class Hello(val body: HelloBody) : Message
    data class DeviceStatus(val body: DeviceStatusBody) : Message
    data class TransferOffer(val body: TransferOfferBody) : Message
    data class TransferAccept(val body: TransferIdBody) : Message
    data class TransferReject(val body: TransferRejectBody) : Message
    data class TransferFinish(val body: TransferFinishBody) : Message
    data class TransferResult(val body: TransferResultBody) : Message
    data class TransferCancel(val body: TransferIdBody) : Message
    /** A type this version doesn't understand; decoded so callers can ignore it, never encoded. */
    data class Unknown(val type: String) : Message
}
