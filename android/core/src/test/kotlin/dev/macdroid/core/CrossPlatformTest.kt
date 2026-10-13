package dev.macdroid.core

import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.connection.MacLocator
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.pairing.InMemoryPairingStore
import dev.macdroid.core.pairing.PairResult
import dev.macdroid.core.pairing.PairingClient
import dev.macdroid.core.pairing.PairingInvite
import dev.macdroid.core.protocol.DeviceStatusBody
import dev.macdroid.core.protocol.Features
import dev.macdroid.core.protocol.HelloBody
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.notifications.InMemoryNotificationSettingsStore
import dev.macdroid.core.notifications.NotificationCommand
import dev.macdroid.core.notifications.NotificationForwarder
import dev.macdroid.core.notifications.PhoneNotification
import dev.macdroid.core.tls.ClientIdentity
import dev.macdroid.core.transfer.*
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run via scripts/cross-platform-test.sh, which starts the Swift dev server and sets the URI. */
class CrossPlatformTest {
    private fun newIdentity(name: String) = HeldCertificate.Builder().ecdsa256().commonName(name).build()
        .let { ClientIdentity(it.keyPair.private, it.certificate) }

    @Test fun pairsConnectsAndReportsBatteryToSwiftServer() = runBlocking {
        val uri = System.getProperty("macdroidDevUri")
        assumeTrue("set MACDROID_DEV_URI (see scripts/cross-platform-test.sh)", uri != null)
        val invite = requireNotNull(PairingInvite.parse(uri!!)) { "bad URI: $uri" }
        val phone = newIdentity("MacDroid Test Phone")

        val result = PairingClient(phone).pair(invite, "MacDroid Test Phone", "JVM")
        assertTrue("pairing failed: $result", result is PairResult.Success)

        // The window is closed now: a different phone must not get in, and is told to rescan rather than
        // that the Mac is an impostor (the Mac's certificate is the pinned one; it just refuses ours).
        val intruder = newIdentity("Intruder")
        assertEquals(PairResult.CodeRejected, PairingClient(intruder).pair(invite, "Intruder", "JVM"))

        // A phone the Mac doesn't trust that tries to connect lands in TrustFailure (spec §7 re-pair path).
        val stranger = ConnectionManager(
            InMemoryPairingStore((result as PairResult.Success).mac),
            { intruder },
            MacLocator.None,
            { HelloBody(app = "test", device = "Intruder") },
        )
        stranger.start()
        try {
            withTimeout(10_000) { stranger.state.first { it is ConnectionState.TrustFailure } }
        } finally {
            stranger.stop()
        }

        val store = InMemoryPairingStore(result.mac)
        val manager = ConnectionManager(store, { phone }, MacLocator.None, { HelloBody(app = "test", device = "MacDroid Test Phone") })
        val phoneFile = System.getProperty("macdroidPhoneFile")
        val receiveDir = System.getProperty("macdroidPhoneReceiveDir")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = SlowTransport(TransferClient(FileEndpoint(store, { phone })::current))
        val engine = TransferEngine(
            scope = scope, store = InMemoryTransferStore(), files = transport, sources = FileSourceOpener(),
            saveTarget = { FolderSaveTarget(File(receiveDir ?: System.getProperty("java.io.tmpdir"))) },
            parts = { FolderSaveTarget.reopen(it) }, send = manager::send, retryDelayMs = 200,
        )
        engine.start(manager.incoming, manager.state.map { it is ConnectionState.Connected }) // before connecting: incoming has no replay
        val received = CopyOnWriteArrayList<Message>()
        val collector = scope.launch { manager.incoming.collect { received.add(it) } }
        manager.start()
        try {
            val connected = withTimeout(10_000) { manager.state.first { it is ConnectionState.Connected } } as ConnectionState.Connected
            assertEquals("MacDroid Dev Server", connected.macName)
            assertTrue(connected.macSupports(Features.NOTIFICATIONS))
            assertTrue(manager.send(Message.DeviceStatus(DeviceStatusBody(77, false))))
            delay(500)
            exchangeANotification(manager, received)
            if (phoneFile != null && receiveDir != null) sendFilesBothWaysWithADrop(manager, engine, transport, File(phoneFile))
        } finally {
            collector.cancel()
            manager.stop()
            scope.cancel()
        }
    }

    /** The phone forwards a chat notification; the dev server replies "On my way"; then the phone removes it. */
    private suspend fun exchangeANotification(manager: ConnectionManager, received: List<Message>) {
        val forwarder = NotificationForwarder("dev.macdroid.test", InMemoryNotificationSettingsStore(), manager::send, { null })
        forwarder.accessChanged(true)
        forwarder.connected(emptySet())
        forwarder.posted(PhoneNotification("k1", "com.example.chat", "Chat", "Mom", "Did you eat?", System.currentTimeMillis(), emptyList(), canReply = true))
        withTimeout(10_000) {
            while (received.none { it is Message.NotificationReply }) delay(50)
        }
        val reply = received.filterIsInstance<Message.NotificationReply>().single().body
        assertEquals("k1", reply.key)
        assertEquals("On my way", reply.text)
        assertEquals(NotificationCommand.Reply("k1", "On my way"), forwarder.handle(Message.NotificationReply(reply)))
        forwarder.removed("k1")
        delay(300)
    }

    /** The dev server sends its file when the phone connects; the phone sends one back. Cut the link mid-file and expect both to resume. */
    private suspend fun sendFilesBothWaysWithADrop(manager: ConnectionManager, engine: TransferEngine, transport: SlowTransport, file: File) {
        engine.enqueue(listOf(OutgoingFile(file.path, file.name, file.length(), "application/octet-stream")))
        withTimeout(30_000) {
            engine.snapshot.first { s ->
                s.active.any { it.direction == Direction.ToMac && it.bytesDone > 2_000_000 } &&
                    s.active.any { it.direction == Direction.FromMac && it.bytesDone > 2_000_000 }
            }
        }
        manager.stop()
        delay(500)
        manager.start()
        withTimeout(120_000) { engine.snapshot.first { s -> s.history.count { it.phase == Phase.Done } == 2 } }
        assertTrue("upload resumed: ${transport.uploadStarts}", transport.uploadStarts.any { it > 0 })
        assertTrue("download resumed: ${transport.downloadStarts}", transport.downloadStarts.any { it > 0 })
        delay(1_000) // let the final transfer.result reach the Mac before the test stops the socket (stop() drops queued frames)
    }
}

/** A slowed-down transport, so the test can cut the connection mid-file. Records which offset each attempt started from. */
private class SlowTransport(private val delegate: FileTransport) : FileTransport {
    val uploadStarts = CopyOnWriteArrayList<Long>()
    val downloadStarts = CopyOnWriteArrayList<Long>()

    override suspend fun uploadOffset(id: String) = delegate.uploadOffset(id)

    override suspend fun upload(id: String, size: Long, offset: Long, input: InputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        uploadStarts += offset
        val slow = object : FilterInputStream(input) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(5)
                return super.read(b, off, minOf(len, 32 * 1024))
            }
        }
        return delegate.upload(id, size, offset, slow, digest, onProgress)
    }

    override suspend fun download(id: String, size: Long, offset: Long, output: OutputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        downloadStarts += offset
        val slow = object : FilterOutputStream(output) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                Thread.sleep(5)
                out.write(b, off, len)
            }
        }
        return delegate.download(id, size, offset, slow, digest, onProgress)
    }
}
