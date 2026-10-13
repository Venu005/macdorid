package dev.macdroid.core.transfer

import dev.macdroid.core.eventually
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.TransferFinishBody
import dev.macdroid.core.protocol.TransferIdBody
import dev.macdroid.core.protocol.TransferOfferBody
import dev.macdroid.core.protocol.TransferRejectBody
import dev.macdroid.core.protocol.TransferRejectReason
import dev.macdroid.core.protocol.TransferResultBody
import dev.macdroid.core.protocol.TransferResultError
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wires a TransferEngine to fakes: an in-memory Mac, a temp Download folder and recorded control messages. */
class EngineHarness(
    val store: TransferStore = InMemoryTransferStore(),
    val dir: File = Files.createTempDirectory("engine").toFile(),
    target: SaveTarget? = null,
    var now: Long = 1_000_000_000L,
) {
    val mac = FakeMacFiles()
    val sent = CopyOnWriteArrayList<Message>()
    @Volatile var online = true
    val incoming = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val connected = MutableStateFlow(true)
    val folder = File(dir, "Download/MacDroid")
    private val saveTarget = target ?: FolderSaveTarget(folder)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = TransferEngine(
        scope = scope, store = store, files = mac, sources = FileSourceOpener(),
        saveTarget = { saveTarget }, parts = { FolderSaveTarget.reopen(it) },
        send = { message -> if (online) sent.add(message) else false },
        clock = { now }, retryDelayMs = 20, maxRetries = 3,
    )

    fun start() {
        engine.start(incoming, connected)
        eventually { incoming.subscriptionCount.value > 0 }
    }

    fun deliver(message: Message) = runBlocking { incoming.emit(message) }
    fun offer(id: String, name: String, bytes: ByteArray) {
        mac.served[id] = bytes
        deliver(Message.TransferOffer(TransferOfferBody(id, name, bytes.size.toLong(), "application/octet-stream")))
    }
    /** Like the real Mac, only sends finish after the phone accepted. */
    fun finish(id: String, bytes: ByteArray) {
        eventually { sentOf<Message.TransferAccept>().any { it.body.transferId == id } }
        deliver(Message.TransferFinish(TransferFinishBody(id, sha256(bytes))))
    }
    inline fun <reified T : Message> sentOf(): List<T> = sent.filterIsInstance<T>()
    fun history() = engine.snapshot.value.history
    fun active() = engine.snapshot.value.active
    fun close() = scope.cancel()
}

class TransferEngineReceiveTest {
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    @Test fun offerIsAcceptedDownloadedAndVerifiedOnFinish() {
        h.start()
        val events = CopyOnWriteArrayList<TransferEvent>()
        h.scope.launch { h.engine.events.collect { events += it } }
        val bytes = testBytes(300_000)
        h.offer("t1", "photo.jpg", bytes)
        eventually { h.sentOf<Message.TransferAccept>().any { it.body.transferId == "t1" } }
        h.finish("t1", bytes)
        eventually { h.sentOf<Message.TransferResult>().any { it.body == TransferResultBody("t1", ok = true) } }
        assertArrayEquals(bytes, File(h.folder, "photo.jpg").readBytes())
        assertFalse(File(h.folder, "photo.jpg.macdroid-part").exists())
        assertEquals(Phase.Done, h.history().first().phase)
        eventually { events.size == 1 }
    }

    @Test fun finishBeforeTheBytesWaitsForThem() {
        h.start()
        h.mac.gate = CompletableDeferred()
        val bytes = testBytes(10_000)
        h.offer("t1", "a.bin", bytes)
        h.finish("t1", bytes)
        Thread.sleep(100)
        assertTrue(h.sentOf<Message.TransferResult>().isEmpty())
        h.mac.gate!!.complete(Unit)
        eventually { h.sentOf<Message.TransferResult>().any { it.body.ok } }
    }

    @Test fun checksumMismatchDeletesThePart() {
        h.start()
        h.offer("t1", "a.bin", testBytes(1_000))
        h.finish("t1", testBytes(1_000, seed = 9))
        eventually { h.sentOf<Message.TransferResult>().any { it.body == TransferResultBody("t1", false, TransferResultError.ChecksumMismatch) } }
        assertFalse(File(h.folder, "a.bin.macdroid-part").exists())
        assertFalse(File(h.folder, "a.bin").exists())
    }

    @Test fun notEnoughSpaceIsRejected() {
        val small = object : SaveTarget {
            override fun freeBytes() = 10L
            override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? = error("must not create a part")
        }
        val harness = EngineHarness(target = small)
        try {
            harness.start()
            harness.offer("t1", "big.bin", testBytes(100))
            eventually { harness.sentOf<Message.TransferReject>().any { it.body == TransferRejectBody("t1", TransferRejectReason.NoSpace) } }
            assertEquals(Failure.NoSpace, harness.history().first().failure)
        } finally {
            harness.close()
        }
    }

    @Test fun noPartMeansDeclined() {
        val declining = object : SaveTarget {
            override fun freeBytes(): Long? = null
            override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? = null
        }
        val harness = EngineHarness(target = declining)
        try {
            harness.start()
            harness.offer("t1", "a.bin", testBytes(1))
            eventually { harness.sentOf<Message.TransferReject>().any { it.body.reason == TransferRejectReason.Declined } }
        } finally {
            harness.close()
        }
    }

    // Review Focus #2
    @Test fun interruptedDownloadResumesFromPartLength() {
        h.start()
        h.mac.failNextAfter = 100_000
        val bytes = testBytes(400_000)
        h.offer("t1", "big.bin", bytes)
        eventually { h.mac.downloadOffsets.size == 2 }
        assertEquals(listOf(0L, 100_000L), h.mac.downloadOffsets.toList())
        h.finish("t1", bytes)
        eventually { h.sentOf<Message.TransferResult>().any { it.body.ok } }
        assertArrayEquals(bytes, File(h.folder, "big.bin").readBytes())
    }

    @Test fun disconnectPausesAndTheMacsReofferResumes() {
        h.start()
        h.mac.gate = CompletableDeferred()
        val bytes = testBytes(50_000)
        h.offer("t1", "a.bin", bytes)
        eventually { h.mac.downloadOffsets.size == 1 }
        h.connected.value = false
        eventually { h.active().firstOrNull()?.phase == Phase.Paused }
        h.mac.gate!!.complete(Unit)
        h.connected.value = true
        h.offer("t1", "a.bin", bytes) // the Mac re-offers after reconnecting
        h.finish("t1", bytes)
        eventually { h.sentOf<Message.TransferResult>().any { it.body.ok } }
        assertEquals(2, h.sentOf<Message.TransferAccept>().size)
    }

    // Final review: a resumed download must not write the part file while the cancelled one still can.
    @Test fun aResumedDownloadWaitsForTheCancelledOneToStop() {
        h.start()
        val release = java.util.concurrent.CountDownLatch(1)
        h.mac.blockNextDownload = release
        val bytes = testBytes(50_000)
        try {
            h.offer("t1", "a.bin", bytes)
            eventually { h.mac.downloadOffsets.size == 1 }
            h.connected.value = false
            eventually { h.active().firstOrNull()?.phase == Phase.Paused }
            h.connected.value = true
            h.offer("t1", "a.bin", bytes) // the Mac re-offers after reconnecting
            eventually { h.sentOf<Message.TransferAccept>().size == 2 }
            Thread.sleep(200)
            assertEquals("a second download started while the first could still write", 1, h.mac.downloadOffsets.size)
        } finally {
            release.countDown()
        }
        h.finish("t1", bytes)
        eventually { h.sentOf<Message.TransferResult>().any { it.body.ok } }
        assertArrayEquals(bytes, File(h.folder, "a.bin").readBytes())
    }

    // Review Focus #1
    @Test fun reofferOfDoneIdReplaysResult() {
        h.start()
        val bytes = testBytes(1_000)
        h.offer("t1", "a.bin", bytes)
        h.finish("t1", bytes)
        eventually { h.sentOf<Message.TransferResult>().size == 1 }
        h.offer("t1", "a.bin", bytes)
        eventually { h.sentOf<Message.TransferResult>().size == 2 }
        assertFalse(File(h.folder, "a (1).bin.macdroid-part").exists())
        assertEquals(1, h.sentOf<Message.TransferAccept>().size)
    }

    @Test fun peerCancelDeletesThePart() {
        h.start()
        h.mac.gate = CompletableDeferred()
        h.offer("t1", "a.bin", testBytes(1_000))
        eventually { File(h.folder, "a.bin.macdroid-part").exists() }
        h.deliver(Message.TransferCancel(TransferIdBody("t1")))
        eventually { h.history().firstOrNull()?.phase == Phase.Cancelled }
        assertTrue(h.history().first().cancelledByPeer)
        eventually { !File(h.folder, "a.bin.macdroid-part").exists() }
    }

    @Test fun localWriteFailureFailsWithIo() {
        val broken = object : SaveTarget {
            override fun freeBytes(): Long? = null
            override suspend fun createPart(name: String, mime: String, transferId: String): PartFile = object : PartFile {
                override val handle = "broken"
                override fun length() = 0L
                override fun openRead() = java.io.ByteArrayInputStream(ByteArray(0))
                override fun openAppend(): OutputStream = object : OutputStream() { override fun write(b: Int) = throw IOException("EIO") }
                override fun finish() = error("unreachable")
                override fun delete() {}
            }
        }
        val harness = EngineHarness(target = broken)
        try {
            harness.start()
            harness.offer("t1", "a.bin", testBytes(100_000))
            eventually { harness.sentOf<Message.TransferResult>().any { it.body == TransferResultBody("t1", false, TransferResultError.Io) } }
        } finally {
            harness.close()
        }
    }

    // Review Focus #5
    @Test fun zeroByteDownloadCompletes() {
        h.start()
        h.offer("t1", "empty.txt", ByteArray(0))
        h.finish("t1", ByteArray(0))
        eventually { h.sentOf<Message.TransferResult>().any { it.body.ok } }
        assertTrue(File(h.folder, "empty.txt").exists())
        assertEquals(listOf(0L), h.mac.downloadOffsets.toList())
    }

    @Test fun stateOlderThan24HoursIsCleanedAtStart() {
        val part = File(h.dir, "old.bin.macdroid-part").apply { writeText("x") }
        val store = InMemoryTransferStore(StoredTransfers(active = listOf(
            TransferRecord(id = "old", direction = Direction.FromMac, name = "old.bin", size = 5, mime = "x", phase = Phase.Active,
                part = part.path, createdAt = 0, updatedAt = 0),
        )))
        val harness = EngineHarness(store = store, dir = h.dir, now = 25L * 3600 * 1000)
        try {
            harness.start()
            eventually { harness.history().firstOrNull()?.failure == Failure.Expired }
            assertTrue(harness.active().isEmpty())
            assertFalse(part.exists())
        } finally {
            harness.close()
        }
    }

    @Test fun interruptedStateComesBackPaused() {
        val store = InMemoryTransferStore(StoredTransfers(active = listOf(
            TransferRecord(id = "t1", direction = Direction.FromMac, name = "a", size = 5, mime = "x", phase = Phase.Active, createdAt = 0, updatedAt = 0),
        )))
        val harness = EngineHarness(store = store, now = 3_600_000)
        try {
            harness.start()
            eventually { harness.active().firstOrNull()?.phase == Phase.Paused }
        } finally {
            harness.close()
        }
    }
}
