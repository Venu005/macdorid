package dev.macdroid.core.transfer

import dev.macdroid.core.MockMac
import dev.macdroid.core.tls.TlsClientFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransferClientTest {
    private val mac = MockMac()
    private lateinit var client: TransferClient
    private val data = ByteArray(3_145_728) { (it * 31 + 7).toByte() }

    @Before fun setUp() {
        mac.start()
        val http = TransferClient.forFiles(TlsClientFactory.pinned(mac.phoneIdentity, mac.fingerprint))
        client = TransferClient { TransferClient.Endpoint(http, mac.server.url("/")) }
    }

    @After fun tearDown() = mac.close()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").apply { update(bytes) }.hex()
    private fun digestOf(prefix: ByteArray) = MessageDigest.getInstance("SHA-256").apply { update(prefix) }

    @Test fun uploadOffsetReadsHead() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(200).addHeader("Upload-Offset", "42").build())
        assertEquals(42L, client.uploadOffset("t1"))
        val request = mac.server.takeRequest()
        assertEquals("HEAD", request.method)
        assertEquals("/v1/files/t1", request.target)
    }

    @Test fun uploadSendsTheWholeFileWithoutContentRange() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(200).addHeader("Upload-Offset", data.size.toString()).build())
        val digest = MessageDigest.getInstance("SHA-256")
        val reported = client.upload("t1", data.size.toLong(), 0, ByteArrayInputStream(data), digest) {}
        assertEquals(data.size.toLong(), reported)
        val request = mac.server.takeRequest()
        assertEquals("PUT", request.method)
        assertNull(request.headers["Content-Range"])
        assertArrayEquals(data, request.body!!.toByteArray())
        assertEquals(sha(data), digest.hex())
    }

    // Review Focus #2
    @Test fun resumedUploadSendsOnlyTheTailWithContentRange() = runBlocking {
        val offset = 1_048_583
        mac.server.enqueue(MockResponse.Builder().code(200).addHeader("Upload-Offset", data.size.toString()).build())
        val digest = digestOf(data.copyOfRange(0, offset))
        client.upload("t1", data.size.toLong(), offset.toLong(), ByteArrayInputStream(data, offset, data.size - offset), digest) {}
        val request = mac.server.takeRequest()
        assertEquals("bytes $offset-${data.size - 1}/${data.size}", request.headers["Content-Range"])
        assertArrayEquals(data.copyOfRange(offset, data.size), request.body!!.toByteArray())
        assertEquals(sha(data), digest.hex())
    }

    @Test fun conflictCarriesTheMacOffset() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(409).addHeader("Upload-Offset", "10").build())
        val error = runCatching { client.upload("t1", 20, 5, ByteArrayInputStream(ByteArray(15)), MessageDigest.getInstance("SHA-256")) {} }.exceptionOrNull()
        assertTrue(error is TransferHttpException)
        assertEquals(409, (error as TransferHttpException).code)
        assertEquals(10L, error.uploadOffset)
    }

    @Test fun downloadFromAnOffsetSendsRangeAndHashesEverything() = runBlocking {
        val offset = 2_000_000
        mac.server.enqueue(MockResponse.Builder().code(206).body(Buffer().write(data, offset, data.size - offset)).build())
        val out = ByteArrayOutputStream()
        val digest = digestOf(data.copyOfRange(0, offset))
        val total = client.download("t1", data.size.toLong(), offset.toLong(), out, digest) {}
        assertEquals(data.size.toLong(), total)
        assertEquals("bytes=$offset-", mac.server.takeRequest().headers["Range"])
        assertArrayEquals(data.copyOfRange(offset, data.size), out.toByteArray())
        assertEquals(sha(data), digest.hex())
    }

    @Test fun downloadFromZeroSendsNoRange() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(data)).build())
        client.download("t1", data.size.toLong(), 0, ByteArrayOutputStream(), MessageDigest.getInstance("SHA-256")) {}
        assertNull(mac.server.takeRequest().headers["Range"])
    }

    @Test fun droppedConnectionIsANetworkErrorAndKeepsWhatArrived() = runBlocking {
        mac.server.enqueue(
            MockResponse.Builder().code(200).body(Buffer().write(data))
                .throttleBody(64 * 1024, 5, TimeUnit.MILLISECONDS)
                .onResponseBody(SocketEffect.ShutdownConnection)
                .build(),
        )
        val out = ByteArrayOutputStream()
        val error = runCatching { client.download("t1", data.size.toLong(), 0, out, MessageDigest.getInstance("SHA-256")) {} }.exceptionOrNull()
        assertTrue("expected IOException, got $error", error is IOException)
        assertFalse(error is LocalIoException)
        assertTrue(out.size() < data.size)
    }

    @Test fun localWriteFailureIsLocal() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(data)).build())
        val broken = object : OutputStream() { override fun write(b: Int) = throw IOException("No space left on device") }
        val error = runCatching { client.download("t1", data.size.toLong(), 0, broken, MessageDigest.getInstance("SHA-256")) {} }.exceptionOrNull()
        assertTrue(error is LocalIoException)
    }

    @Test fun notFoundIsReported() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(404).build())
        val error = runCatching { client.uploadOffset("gone") }.exceptionOrNull()
        assertEquals(404, (error as TransferHttpException).code)
    }

    @Test fun cancellingStopsADownloadPromptly() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(data)).throttleBody(1024, 100, TimeUnit.MILLISECONDS).build())
        val job = async { client.download("t1", data.size.toLong(), 0, ByteArrayOutputStream(), MessageDigest.getInstance("SHA-256")) {} }
        delay(300)
        job.cancel()
        withTimeout(2_000) { runCatching { job.await() } }
        assertTrue(job.isCancelled)
    }

    @Test fun noEndpointMeansNotPaired() {
        val unpaired = TransferClient { null }
        assertThrows(IOException::class.java) { runBlocking { unpaired.uploadOffset("t1") } }
    }
}
