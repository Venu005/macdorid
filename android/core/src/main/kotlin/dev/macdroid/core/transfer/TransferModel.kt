package dev.macdroid.core.transfer

import kotlinx.serialization.Serializable

@Serializable enum class Direction { ToMac, FromMac }

@Serializable enum class Phase { Queued, Offered, Active, Paused, Verifying, Done, Failed, Cancelled }

@Serializable enum class Failure { ChecksumMismatch, NoSpace, Io, Declined, Invalid, SourceUnavailable, Expired }

@Serializable
data class TransferRecord(
    val id: String,
    val direction: Direction,
    /** ToMac: the file's name. FromMac: the sanitised name (the final name may get a " (1)" when saved). */
    val name: String,
    val size: Long,
    val mime: String,
    val phase: Phase,
    val bytesDone: Long = 0,
    val failure: Failure? = null,
    val cancelledByPeer: Boolean = false,
    /** ToMac: the content URI or file path to read. */
    val source: String? = null,
    /** FromMac: the part file's handle (see [PartFile.handle]). */
    val part: String? = null,
    /** FromMac: the SHA-256 from `transfer.finish`, kept until every byte is in. */
    val expectedSha256: String? = null,
    /** FromMac: where the finished file was saved (a URI string), for Open. */
    val savedUri: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isFinished: Boolean get() = phase == Phase.Done || phase == Phase.Failed || phase == Phase.Cancelled

    /** Counts toward the 3-active-outgoing limit. */
    val occupiesSlot: Boolean get() = phase == Phase.Offered || phase == Phase.Active || phase == Phase.Paused || phase == Phase.Verifying
}

data class TransferSnapshot(
    val active: List<TransferRecord> = emptyList(),
    /** Finished transfers, newest first (at most 100). */
    val history: List<TransferRecord> = emptyList(),
    /** Smoothed bytes per second for transfers moving data right now, by id. */
    val rates: Map<String, Double> = emptyMap(),
)

sealed interface TransferEvent {
    /** A file from the Mac was verified and saved ([TransferRecord.savedUri] is set). */
    data class Received(val record: TransferRecord) : TransferEvent
}

/** A file the user chose to send. [source] is a content URI or an absolute path. */
data class OutgoingFile(val source: String, val name: String, val size: Long, val mime: String)
