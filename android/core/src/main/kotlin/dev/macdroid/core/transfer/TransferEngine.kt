package dev.macdroid.core.transfer

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.TransferFinishBody
import dev.macdroid.core.protocol.TransferIdBody
import dev.macdroid.core.protocol.TransferOfferBody
import dev.macdroid.core.protocol.TransferRejectBody
import dev.macdroid.core.protocol.TransferRejectReason
import dev.macdroid.core.protocol.TransferResultBody
import dev.macdroid.core.protocol.TransferResultError
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs file transfers with the Mac (spec §2): the control handshake, the HTTP byte streams, resume, checksums,
 * the 3-outgoing limit, persistence (24 h) and history (last 100).
 * Decisions run on one confined dispatcher ([control]); byte copying runs on IO and only reports progress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferEngine(
    private val scope: CoroutineScope,
    private val store: TransferStore,
    private val files: FileTransport,
    private val sources: SourceOpener,
    private val saveTarget: () -> SaveTarget,
    private val parts: (String) -> PartFile?,
    private val send: (Message) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = 3_000,
    private val maxRetries: Int = 5,
) {
    companion object {
        const val MAX_ACTIVE_OUTGOING = 3
        const val HISTORY_LIMIT = 100
        const val STATE_LIFETIME_MS = 24L * 60 * 60 * 1000
    }

    private val control = Dispatchers.Default.limitedParallelism(1)
    private val _snapshot = MutableStateFlow(TransferSnapshot())
    val snapshot: StateFlow<TransferSnapshot> = _snapshot.asStateFlow()
    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    // Touched only on [control].
    private val jobs = HashMap<String, Job>()
    private val deciding = HashMap<String, Job>()
    private val completedDigests = HashMap<String, String>()
    private val outbox = ArrayList<Message>()
    private var connected = false
    // Touched from IO threads (one per transfer).
    private val rates = ConcurrentHashMap<String, TransferRate>()

    private sealed interface Outcome {
        data class Complete(val sha256: String) : Outcome
        data class Local(val failure: Failure) : Outcome
        data object Network : Outcome
        data object Conflict : Outcome
        data object Gone : Outcome
    }

    fun start(incoming: Flow<Message>, connected: Flow<Boolean>) {
        scope.launch(control) {
            load()
            launch { incoming.collect { onMessage(it) } }
            launch { connected.distinctUntilChanged().collect { onConnectionChanged(it) } }
        }
    }

    fun cancel(id: String) {
        scope.launch(control) { cancelLocally(id) }
    }

    fun cancelAll() {
        scope.launch(control) { active.map { it.id }.forEach { cancelLocally(it) } }
    }

    // region State

    private val active get() = _snapshot.value.active
    private fun record(id: String) = active.firstOrNull { it.id == id }
    private fun past(id: String) = _snapshot.value.history.firstOrNull { it.id == id }

    private fun add(record: TransferRecord) = _snapshot.update { it.copy(active = it.active + record) }

    private fun update(id: String, change: (TransferRecord) -> TransferRecord) {
        _snapshot.update { s -> s.copy(active = s.active.map { if (it.id == id) change(it).copy(updatedAt = clock()) else it }) }
    }

    /** Moves a finished record to the front of history. */
    private fun archive(record: TransferRecord) {
        rates.remove(record.id)
        _snapshot.update { s ->
            s.copy(
                active = s.active.filterNot { it.id == record.id },
                history = (listOf(record.copy(updatedAt = clock())) + s.history.filterNot { it.id == record.id }).take(HISTORY_LIMIT),
                rates = s.rates - record.id,
            )
        }
        persist()
    }

    private fun persist() {
        val s = _snapshot.value
        runCatching { store.save(StoredTransfers(s.active, s.history)) }
    }

    private fun progress(id: String, bytes: Long) {
        val rate = rates.getOrPut(id) { TransferRate() }.apply { record(bytes, System.nanoTime()) }.bytesPerSecond
        _snapshot.update { s ->
            s.copy(
                active = s.active.map { if (it.id == id) it.copy(bytesDone = bytes) else it },
                rates = if (rate != null) s.rates + (id to rate) else s.rates,
            )
        }
    }

    private fun clearRate(id: String) {
        rates.remove(id)
        _snapshot.update { it.copy(rates = it.rates - id) }
    }

    /** Restores persisted state: anything older than 24 h is cleaned up; interrupted transfers wait to resume. */
    private suspend fun load() {
        val stored = runCatching { store.load() }.getOrDefault(StoredTransfers())
        val cutoff = clock() - STATE_LIFETIME_MS
        val history = stored.history.toMutableList()
        val restored = mutableListOf<TransferRecord>()
        for (record in stored.active) {
            when {
                record.updatedAt < cutoff -> {
                    record.part?.let { handle -> withContext(Dispatchers.IO) { runCatching { parts(handle)?.delete() } } }
                    history.add(0, record.copy(phase = Phase.Failed, failure = Failure.Expired, updatedAt = clock()))
                }
                record.phase == Phase.Queued -> restored += record
                else -> restored += record.copy(phase = Phase.Paused)
            }
        }
        _snapshot.value = TransferSnapshot(restored, history.take(HISTORY_LIMIT))
        persist()
    }

    // endregion

    private suspend fun onMessage(message: Message) {
        when (message) {
            is Message.TransferOffer -> onOffer(message.body)
            is Message.TransferFinish -> onFinish(message.body)
            is Message.TransferCancel -> onPeerCancel(message.body.transferId)
            else -> Unit
        }
    }

    private fun onConnectionChanged(isConnected: Boolean) {
        connected = isConnected
        if (!isConnected) {
            // Stop moving bytes; transfers resume after the reconnect (spec §5 "Paused — waiting for connection").
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            for (record in active) {
                if (record.phase == Phase.Active || record.phase == Phase.Offered) update(record.id) { it.copy(phase = Phase.Paused) }
                clearRate(record.id)
            }
            persist()
            return
        }
        val queued = outbox.toList()
        outbox.clear()
        queued.forEach(::sendOrQueue)
    }

    private fun sendOrQueue(message: Message) {
        if (!send(message)) outbox += message
    }

    private suspend fun cancelLocally(id: String) {
        val record = record(id) ?: return
        jobs.remove(id)?.cancelAndJoin()
        if (record.direction == Direction.FromMac) deletePart(record)
        archive(record.copy(phase = Phase.Cancelled, cancelledByPeer = false))
        if (record.phase != Phase.Queued) sendOrQueue(Message.TransferCancel(TransferIdBody(id)))
    }

    private suspend fun onPeerCancel(id: String) {
        deciding.remove(id)?.cancel()
        val record = record(id) ?: return
        jobs.remove(id)?.cancelAndJoin()
        if (record.direction == Direction.FromMac) deletePart(record)
        archive(record.copy(phase = Phase.Cancelled, cancelledByPeer = true))
    }

    // region Receiving (Mac → phone)

    private fun onOffer(offer: TransferOfferBody) {
        val id = offer.transferId
        val known = record(id)
        if (known != null) {
            if (known.direction != Direction.FromMac) return
            // A re-offer after a reconnect: accept again and resume from the part file's length.
            update(id) { it.copy(phase = Phase.Active) }
            send(Message.TransferAccept(TransferIdBody(id)))
            startDownload(id)
            return
        }
        past(id)?.let { replay(it); return }
        if (offer.size < 0) {
            send(Message.TransferReject(TransferRejectBody(id, TransferRejectReason.Invalid)))
            return
        }
        if (id in deciding) return
        // Deciding may wait for the user ("Ask where to save"), so it runs on its own.
        deciding[id] = scope.launch(control) {
            try {
                decide(offer)
            } finally {
                deciding.remove(id)
            }
        }
    }

    private suspend fun decide(offer: TransferOfferBody) {
        val id = offer.transferId
        val name = FileNaming.sanitize(offer.name)
        val target = saveTarget()
        val free = withContext(Dispatchers.IO) { runCatching { target.freeBytes() }.getOrNull() }
        if (free != null && free < offer.size) return reject(offer, name, TransferRejectReason.NoSpace)
        val part = try {
            target.createPart(name, offer.mime, id)
        } catch (e: IOException) {
            null
        } ?: return reject(offer, name, TransferRejectReason.Declined)
        add(TransferRecord(id = id, direction = Direction.FromMac, name = name, size = offer.size, mime = offer.mime,
            phase = Phase.Active, part = part.handle, createdAt = clock(), updatedAt = clock()))
        persist()
        send(Message.TransferAccept(TransferIdBody(id)))
        startDownload(id)
    }

    private fun reject(offer: TransferOfferBody, name: String, reason: TransferRejectReason) {
        val failure = when (reason) {
            TransferRejectReason.NoSpace -> Failure.NoSpace
            TransferRejectReason.Declined -> Failure.Declined
            TransferRejectReason.Invalid -> Failure.Invalid
        }
        archive(TransferRecord(id = offer.transferId, direction = Direction.FromMac, name = name, size = offer.size, mime = offer.mime,
            phase = Phase.Failed, failure = failure, createdAt = clock(), updatedAt = clock()))
        send(Message.TransferReject(TransferRejectBody(offer.transferId, reason)))
    }

    /** The Mac re-offered an id we already finished (its result got lost): tell it the outcome again. */
    private fun replay(record: TransferRecord) {
        val id = record.id
        when (record.phase) {
            Phase.Done -> send(Message.TransferResult(TransferResultBody(id, ok = true)))
            Phase.Failed -> when (record.failure) {
                Failure.Declined -> send(Message.TransferReject(TransferRejectBody(id, TransferRejectReason.Declined)))
                Failure.Invalid -> send(Message.TransferReject(TransferRejectBody(id, TransferRejectReason.Invalid)))
                else -> send(Message.TransferResult(TransferResultBody(id, ok = false, error = record.failure.resultError())))
            }
            Phase.Cancelled -> send(Message.TransferCancel(TransferIdBody(id)))
            else -> Unit
        }
    }

    private fun startDownload(id: String) {
        val previous = jobs.remove(id)
        jobs[id] = scope.launch(control) {
            previous?.cancelAndJoin() // never let two downloads write the same part file
            runDownload(id)
        }
    }

    private suspend fun runDownload(id: String) {
        var attempts = 0
        while (true) {
            val record = record(id) ?: return
            val part = record.part?.let { handle -> withContext(Dispatchers.IO) { parts(handle) } } ?: return failIncoming(id, Failure.Io)
            when (val outcome = withContext(Dispatchers.IO) { downloadOnce(record, part) }) {
                is Outcome.Complete -> {
                    completedDigests[id] = outcome.sha256
                    update(id) { it.copy(bytesDone = it.size, phase = Phase.Verifying) }
                    clearRate(id)
                    jobs.remove(id)
                    return verifyIfReady(id)
                }
                is Outcome.Local -> return failIncoming(id, outcome.failure)
                Outcome.Gone -> return failIncoming(id, Failure.Io)
                Outcome.Network, Outcome.Conflict -> if (!pauseForRetry(id, ++attempts)) return
            }
        }
    }

    private suspend fun downloadOnce(record: TransferRecord, part: PartFile): Outcome = try {
        val digest = MessageDigest.getInstance("SHA-256")
        val have = local { part.openRead().use { digestAll(it, digest) } } // re-hash what's already on disk
        if (have > record.size) throw LocalIoException(IOException("part file is longer than the offer"))
        val output = local { part.openAppend() }.buffered(TransferClient.BUFFER_SIZE)
        val total = try {
            files.download(record.id, record.size, have, output, digest) { progress(record.id, it) }
        } finally {
            local { output.close() }
        }
        if (total < record.size) Outcome.Network else Outcome.Complete(digest.hex())
    } catch (e: CancellationException) {
        throw e
    } catch (e: LocalIoException) {
        Outcome.Local(if (e.isNoSpace()) Failure.NoSpace else Failure.Io)
    } catch (e: TransferHttpException) {
        when (e.code) {
            404, 410 -> Outcome.Gone
            409 -> Outcome.Conflict
            else -> Outcome.Network
        }
    } catch (e: IOException) {
        Outcome.Network
    }

    private suspend fun onFinish(body: TransferFinishBody) {
        val record = record(body.transferId)?.takeIf { it.direction == Direction.FromMac } ?: return
        update(record.id) { it.copy(expectedSha256 = body.sha256) }
        persist()
        verifyIfReady(record.id)
    }

    /** Verifies once every byte is in and the Mac's checksum has arrived, in whichever order they came. */
    private suspend fun verifyIfReady(id: String) {
        val record = record(id) ?: return
        val actual = completedDigests[id] ?: return
        val expected = record.expectedSha256 ?: return
        completedDigests.remove(id)
        if (actual != expected.lowercase()) return failIncoming(id, Failure.ChecksumMismatch)
        val saved = try {
            withContext(Dispatchers.IO) { (record.part?.let(parts) ?: throw IOException("part file is gone")).finish() }
        } catch (e: IOException) {
            return failIncoming(id, Failure.Io)
        }
        val done = record.copy(phase = Phase.Done, bytesDone = record.size, savedUri = saved)
        archive(done)
        _events.tryEmit(TransferEvent.Received(done))
        send(Message.TransferResult(TransferResultBody(id, ok = true)))
    }

    private suspend fun failIncoming(id: String, failure: Failure) {
        val record = record(id) ?: return
        jobs.remove(id)
        completedDigests.remove(id)
        deletePart(record)
        archive(record.copy(phase = Phase.Failed, failure = failure))
        send(Message.TransferResult(TransferResultBody(id, ok = false, error = failure.resultError())))
    }

    private suspend fun deletePart(record: TransferRecord) {
        val handle = record.part ?: return
        withContext(Dispatchers.IO) { runCatching { parts(handle)?.delete() } }
    }

    // endregion

    /** Marks [id] paused. Returns true when it's worth trying again now (still connected, retries left). */
    private suspend fun pauseForRetry(id: String, attempts: Int): Boolean {
        update(id) { it.copy(phase = Phase.Paused) }
        clearRate(id)
        persist()
        if (!connected || attempts > maxRetries) return false
        delay(retryDelayMs)
        if (!connected || record(id) == null) return false
        update(id) { it.copy(phase = Phase.Active) }
        return true
    }
}

/** Wraps local I/O so its failures fail the transfer instead of pausing it. */
internal inline fun <T> local(block: () -> T): T = try {
    block()
} catch (e: LocalIoException) {
    throw e
} catch (e: IOException) {
    throw LocalIoException(e)
} catch (e: SecurityException) {
    throw LocalIoException(e)
}

internal fun digestAll(input: InputStream, digest: MessageDigest, limit: Long = Long.MAX_VALUE): Long {
    val buffer = ByteArray(TransferClient.BUFFER_SIZE)
    var total = 0L
    while (total < limit) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
        if (count < 0) break
        digest.update(buffer, 0, count)
        total += count
    }
    return total
}

internal fun Throwable.isNoSpace(): Boolean =
    generateSequence(this) { it.cause }.any { error -> error.message?.let { "ENOSPC" in it || "No space left" in it } == true }

internal fun Failure?.resultError(): TransferResultError = when (this) {
    Failure.ChecksumMismatch -> TransferResultError.ChecksumMismatch
    Failure.NoSpace -> TransferResultError.NoSpace
    else -> TransferResultError.Io
}
