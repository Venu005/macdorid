package dev.macdroid.core

import dev.macdroid.core.calls.CallCommand
import dev.macdroid.core.calls.CallNotification
import dev.macdroid.core.calls.CallTracker
import dev.macdroid.core.calls.CallType
import dev.macdroid.core.clipboard.ClipboardHub
import dev.macdroid.core.clipboard.SendResult
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.connection.MacLocator
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.control.GestureSegment
import dev.macdroid.core.control.GestureTracker
import dev.macdroid.core.extras.MediaArtwork
import dev.macdroid.core.extras.MediaControl
import dev.macdroid.core.extras.MediaSnapshot
import dev.macdroid.core.extras.MediaTracker
import dev.macdroid.core.mirror.MirrorFrame
import dev.macdroid.core.notifications.InMemoryNotificationSettingsStore
import dev.macdroid.core.notifications.NotificationCommand
import dev.macdroid.core.notifications.NotificationForwarder
import dev.macdroid.core.notifications.PhoneNotification
import dev.macdroid.core.pairing.InMemoryPairingStore
import dev.macdroid.core.pairing.PairResult
import dev.macdroid.core.pairing.PairingClient
import dev.macdroid.core.pairing.PairingInvite
import dev.macdroid.core.photos.PhotoPageResult
import dev.macdroid.core.photos.PhotoService
import dev.macdroid.core.photos.PhotoSource
import dev.macdroid.core.protocol.CallActionKind
import dev.macdroid.core.protocol.DeviceStatusBody
import dev.macdroid.core.protocol.Features
import dev.macdroid.core.protocol.HelloBody
import dev.macdroid.core.protocol.InputKeyName
import dev.macdroid.core.protocol.InputNoticeBody
import dev.macdroid.core.protocol.InputNoticeReason
import dev.macdroid.core.protocol.LinkOpenBody
import dev.macdroid.core.protocol.MediaCommandKind
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.MirrorPhaseName
import dev.macdroid.core.protocol.MirrorStateBody
import dev.macdroid.core.protocol.MirrorStopReason
import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.PhotoAlbumBody
import dev.macdroid.core.protocol.PhotoItemBody
import dev.macdroid.core.protocol.PhotoKind
import dev.macdroid.core.protocol.PhotoKindFilter
import dev.macdroid.core.protocol.PointerAction
import dev.macdroid.core.protocol.RingAction
import dev.macdroid.core.protocol.RingStateBody
import dev.macdroid.core.protocol.TransferPurpose
import dev.macdroid.core.tls.ClientIdentity
import dev.macdroid.core.tls.TlsClientFactory
import dev.macdroid.core.transfer.*
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run via scripts/cross-platform-test.sh, which starts the Swift dev server and sets the URI. */
class CrossPlatformTest {
    /** Two items; "2" has a 4,000-byte original and a tiny JPEG thumbnail. */
    private class FakePhotos(private val original: File) : PhotoSource {
        override fun access() = PhotoAccess.Full
        override fun albums() = listOf(PhotoAlbumBody("all", "All Photos", 2, "2"))
        override fun page(albumId: String, kind: PhotoKindFilter, cursor: String?, limit: Int) = PhotoPageResult(
            listOf(
                PhotoItemBody("2", PhotoKind.Photo, "IMG_2.jpg", "image/jpeg", 1_791_892_700_000, 4_000, 4, 3),
                PhotoItemBody("1", PhotoKind.Photo, "IMG_1.jpg", "image/jpeg", 1_791_892_600_000, 10, 4, 3),
            ),
            null,
        )
        override fun thumbnail(id: String) = if (id == "2") byteArrayOf(-1, -40, -1, -32, 0, 16) else null
        override fun original(id: String) = if (id == "2") OutgoingFile(original.path, "IMG_2.jpg", original.length(), "image/jpeg") else null
    }

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
        val manager = ConnectionManager(store, { phone }, MacLocator.None, { HelloBody(app = "test", device = "MacDroid Test Phone", features = Features.SUPPORTED) })
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
        val photoFile = File.createTempFile("photo", ".jpg").apply { writeBytes(ByteArray(4_000) { (it % 251).toByte() }); deleteOnExit() }
        val photoService = PhotoService(FakePhotos(photoFile), manager::send) { id, file -> engine.enqueueWithId(id, file, TransferPurpose.Photo) }
        val photoRequests = Channel<Message>(Channel.UNLIMITED)
        val photoCollector = scope.launch { manager.incoming.collect { photoRequests.trySend(it) } }
        val photoWorker = scope.launch { for (m in photoRequests) photoService.handle(m) }
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
            exchangeClipboard(manager, engine, received)
            exchangeSmallWins(manager, received)
            exchangeCalls(manager, received)
            exchangePhotos(received)
            exchangeMirror(manager, store, phone, received)
            if (phoneFile != null && receiveDir != null) sendFilesBothWaysWithADrop(manager, engine, transport, File(phoneFile))
        } finally {
            photoCollector.cancel()
            photoWorker.cancel()
            collector.cancel()
            manager.stop()
            scope.cancel()
        }
    }

    /** The Mac browses, takes a thumbnail, saves photo 2, and asks for one that's gone. */
    /** The Mac starts mirroring; a wrong session is refused; the fixture stream and audio go through; the phone stops. */
    private suspend fun exchangeMirror(manager: ConnectionManager, store: InMemoryPairingStore, phone: ClientIdentity, received: List<Message>) {
        withTimeout(20_000) { while (received.none { it is Message.MirrorStart }) delay(50) }
        val start = received.filterIsInstance<Message.MirrorStart>().first().body
        val mac = store.paired.value!!
        val http = TlsClientFactory.pinned(phone, mac.fingerprint).newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        fun open(session: String): Pair<WebSocket, CompletableDeferred<Boolean>> {
            val opened = CompletableDeferred<Boolean>()
            val url = HttpUrl.Builder().scheme("https").host(mac.host).port(mac.port).addPathSegments("v1/mirror").build()
            val socket = http.newWebSocket(Request.Builder().url(url).header("X-MacDroid-Session", session).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(true) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { opened.complete(false) }
            })
            return socket to opened
        }
        assertFalse("a wrong session must be refused", withTimeout(10_000) { open("not-the-session").second.await() })
        val (socket, opened) = open(start.sessionId)
        assertTrue(withTimeout(10_000) { opened.await() })
        manager.send(Message.MirrorState(MirrorStateBody(start.sessionId, MirrorPhaseName.Streaming, 320, 240, 0, audio = true, control = true)))
        val h264 = File(System.getProperty("fixturesDir"), "h264/sample.h264").readBytes()
        var micros = 1_000L
        for ((type, key, bytes) in h264Frames(h264)) {
            socket.send(MirrorFrame.encode(type, key, micros, bytes).toByteString())
            micros += 33_333
        }
        repeat(3) { socket.send(MirrorFrame.encode(MirrorFrame.TYPE_AUDIO, false, micros + it * 10_000, ByteArray(1_920)).toByteString()) }
        withTimeout(10_000) { while (received.none { it is Message.MirrorKeyframe }) delay(50) }
        withTimeout(10_000) { while (received.none { it is Message.InputText }) delay(50) }
        val pointers = received.filterIsInstance<Message.InputPointer>().map { it.body }
        assertEquals(listOf(PointerAction.Down, PointerAction.Up), pointers.map { it.action })
        // The phone's rules turn that into a tap at the middle of a 1080×2400 screen.
        val tracker = GestureTracker()
        tracker.down((pointers[0].x * 1080).toFloat(), (pointers[0].y * 2400).toFloat(), 0)
        assertEquals(listOf(GestureSegment(540f, 1200f, 540f, 1200f, 50, first = true, last = true)), tracker.up(540f, 1200f, 60))
        assertEquals(InputKeyName.Back, received.filterIsInstance<Message.InputKey>().single().body.key)
        assertEquals(1, received.count { it is Message.InputScroll })
        // No text field focused on this "phone": say so.
        assertTrue(manager.send(Message.InputNotice(InputNoticeBody(InputNoticeReason.NoTextField))))
        delay(300)
        delay(500)
        manager.send(Message.MirrorState(MirrorStateBody(start.sessionId, MirrorPhaseName.Stopped, reason = MirrorStopReason.User)))
        socket.close(1000, null)
        delay(300)
    }

    /** The fixture as the phone sends it: SPS+PPS as one config frame, then one frame per slice. */
    private fun h264Frames(data: ByteArray): List<Triple<Int, Boolean, ByteArray>> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) { starts += i; i += 4 }
            else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) { starts += i; i += 3 }
            else i++
        }
        val nals = starts.mapIndexed { n, s -> data.copyOfRange(s, if (n + 1 < starts.size) starts[n + 1] else data.size) }
        fun type(nal: ByteArray): Int { val header = if (nal[2] == 1.toByte()) nal[3] else nal[4]; return header.toInt() and 0x1F }
        val out = mutableListOf<Triple<Int, Boolean, ByteArray>>()
        var params = ByteArray(0)
        for (nal in nals) {
            when (type(nal)) {
                7, 8 -> params += nal
                1, 5 -> {
                    if (params.isNotEmpty()) { out += Triple(MirrorFrame.TYPE_CONFIG, false, params); params = ByteArray(0) }
                    out += Triple(MirrorFrame.TYPE_VIDEO, type(nal) == 5, nal)
                }
            }
        }
        return out
    }

    private suspend fun exchangePhotos(received: List<Message>) {
        withTimeout(20_000) {
            while (received.none { it is Message.PhotosFetch && it.body.id == "424242" }) delay(50)
        }
        val save = received.filterIsInstance<Message.PhotosFetch>().first { it.body.id == "2" }
        withTimeout(20_000) {
            while (received.none { it is Message.TransferResult && it.body.transferId == save.body.transferId && it.body.ok }) delay(50)
        }
    }

    /** The Mac rings and sends a link; the phone answers, sends a link back, and shares its media. */
    /** The phone rings; the Mac answers and dials a number; the call ends (Plan 10). */
    private suspend fun exchangeCalls(manager: ConnectionManager, received: List<Message>) {
        val tracker = CallTracker(manager::send)
        tracker.connected()
        val all = setOf(CallActionKind.Answer, CallActionKind.Decline, CallActionKind.HangUp)
        tracker.posted(CallNotification("call-1", CallType.Incoming, "Mom", "+15551234567", all))
        withTimeout(10_000) { while (received.none { it is Message.CallAction }) delay(50) }
        assertEquals(CallCommand.Fire("call-1", CallActionKind.Answer), tracker.handle(received.filterIsInstance<Message.CallAction>().first()))
        withTimeout(10_000) { while (received.none { it is Message.CallDial }) delay(50) }
        assertEquals(CallCommand.Dial("+15550100000"), tracker.handle(received.filterIsInstance<Message.CallDial>().first()))
        tracker.removed("call-1")
        delay(300)
    }

    private suspend fun exchangeSmallWins(manager: ConnectionManager, received: List<Message>) {
        withTimeout(10_000) { while (received.none { it is Message.DeviceRing }) delay(50) }
        assertEquals(RingAction.Start, received.filterIsInstance<Message.DeviceRing>().first().body.action)
        assertTrue(manager.send(Message.RingState(RingStateBody(true))))
        withTimeout(10_000) { while (received.none { it is Message.LinkOpen }) delay(50) }
        assertEquals("https://example.com/mac", received.filterIsInstance<Message.LinkOpen>().first().body.url)
        assertTrue(manager.send(Message.LinkOpen(LinkOpenBody("https://example.com/phone"))))
        val tracker = MediaTracker(manager::send)
        tracker.connected()
        tracker.update(listOf(MediaSnapshot("com.example.music", "Music", "Song", "Artist", 200_000, 1_000, System.currentTimeMillis(),
            playing = true, canPrevious = true, canNext = true, canSeek = true,
            art = MediaArtwork("0123456789abcdef", "/9j/4AAQ"), lastActiveAt = System.currentTimeMillis())))
        withTimeout(10_000) { while (received.none { it is Message.MediaCommand }) delay(50) }
        assertEquals(MediaControl("com.example.music", MediaCommandKind.Pause, null),
            tracker.handle(received.filterIsInstance<Message.MediaCommand>().first()))
    }

    /** The Mac's copy arrives; the phone sends text and a small image back. */
    private suspend fun exchangeClipboard(manager: ConnectionManager, engine: TransferEngine, received: List<Message>) {
        withTimeout(10_000) { while (received.none { it is Message.ClipboardSet }) delay(50) }
        assertEquals("hello from mac", received.filterIsInstance<Message.ClipboardSet>().first().body.text)
        val hub = ClipboardHub(manager::send, { engine.enqueueClipboard(it) }, { true })
        assertEquals(SendResult.Sent, hub.sendText("hello from phone", manager.state.value))
        val png = File.createTempFile("clip", ".png").apply { writeBytes(ByteArray(3_000) { it.toByte() }); deleteOnExit() }
        assertEquals(SendResult.Sent, hub.sendImage(OutgoingFile(png.path, "clipboard.png", png.length(), "image/png"), manager.state.value))
        // The phone only gets transfer.result for its own uploads, and the file section hasn't started yet.
        withTimeout(20_000) {
            while (received.none { it is Message.TransferResult && it.body.ok }) delay(100)
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
