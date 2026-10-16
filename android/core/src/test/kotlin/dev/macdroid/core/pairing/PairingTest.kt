package dev.macdroid.core.pairing

import dev.macdroid.core.Fixtures
import dev.macdroid.core.MockMac
import dev.macdroid.core.protocol.MessageCodec
import dev.macdroid.core.protocol.PairRequest
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    @Test fun parsesSharedFixtureUri() {
        val invite = PairingInvite.parse(Fixtures.text("pairing-uri.txt"))
        assertEquals(
            PairingInvite(
                host = "192.168.1.20",
                port = 47999,
                fingerprint = "8a3ba983c43884812d5198df10a6891c193f3fb67d1e5b44c6902e9fde37b45a",
                code = "MZXW6YTBOI2DGNBVGY3TQOJQGE",
                name = "Sam's Mac+Pro ✨",
            ),
            invite,
        )
    }

    @Test fun rejectsInvalidUris() {
        val fp = "8a3ba983c43884812d5198df10a6891c193f3fb67d1e5b44c6902e9fde37b45a"
        assertNull(PairingInvite.parse("https://pair?host=1.2.3.4&port=1&fp=$fp&code=A"))
        assertNull(PairingInvite.parse("macdroid://pair?host=1.2.3.4&port=1&code=A"))
        assertNull(PairingInvite.parse("macdroid://pair?host=1.2.3.4&port=70000&fp=$fp&code=A"))
        assertNull(PairingInvite.parse("macdroid://pair?host=1.2.3.4&port=1&fp=NOTHEX&code=A"))
        assertNull(PairingInvite.parse("macdroid://pair?host=&port=1&fp=$fp&code=A"))
        assertNull(PairingInvite.parse("not a uri at all"))
    }

    @Test fun normalizesTypedCodes() {
        assertEquals("MZXW6YTBOI2D", PairingInvite.normalizeCode(" mzxw 6ytb-oi2d "))
    }

    // Plan 11: only base32 characters (A–Z, 2–7) can be part of a code.
    @Test fun normalizeCodeKeepsOnlyBase32() {
        assertEquals("ABC", PairingInvite.normalizeCode("ab-1 8c"))
        assertEquals("AB27", PairingInvite.normalizeCode("ab2 7é"))
    }

    // Plan 11: a huge reply is a failure, not an out-of-memory crash.
    @Test fun pairingResponseBodyIsBounded() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueJson(200, """{"macName":"${"x".repeat(2 * 1024 * 1024)}"}""")
            mac.start()
            val result = PairingClient(mac.phoneIdentity).pair(invite(mac), "My Phone", "Nothing Phone (3)")
            assertTrue(result.toString(), result is PairResult.Failed)
        }
    }

    private fun invite(mac: MockMac, code: String = "CODE") =
        PairingInvite(mac.server.hostName, mac.server.port, mac.fingerprint, code, "Test Mac")

    @Test fun successfulPairingReturnsMacAndSendsRequest() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueJson(200, """{"macName":"Studio"}""")
            mac.start()
            val result = PairingClient(mac.phoneIdentity).pair(invite(mac), "My Phone", "Nothing Phone (3)")
            assertEquals(PairResult.Success(PairedMac(mac.fingerprint, "Studio", mac.server.hostName, mac.server.port)), result)
            val recorded = mac.server.takeRequest()
            assertEquals("/v1/pair", recorded.url.encodedPath)
            assertEquals(
                PairRequest("CODE", "My Phone", "Nothing Phone (3)"),
                MessageCodec.json.decodeFromString(PairRequest.serializer(), recorded.body!!.utf8()),
            )
        }
    }

    @Test fun forbiddenMeansCodeRejected() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueJson(403, "")
            mac.start()
            assertEquals(PairResult.CodeRejected, PairingClient(mac.phoneIdentity).pair(invite(mac), "P", "M"))
        }
    }

    @Test fun wrongFingerprintIsMismatch() = runBlocking {
        MockMac().use { mac ->
            mac.start()
            val result = PairingClient(mac.phoneIdentity).pair(invite(mac).copy(fingerprint = "00".repeat(32)), "P", "M")
            assertEquals(PairResult.FingerprintMismatch, result)
        }
    }

    // The Mac's TLS layer rejects unpaired phones once its window is closed, so no 403 ever arrives.
    @Test fun macRejectingOurCertificateIsCodeRejected() = runBlocking {
        MockMac(trustsPhone = false).use { mac ->
            mac.enqueueJson(200, """{"macName":"Studio"}""")
            mac.start()
            assertEquals(PairResult.CodeRejected, PairingClient(mac.phoneIdentity).pair(invite(mac), "P", "M"))
        }
    }

    @Test fun wrongFingerprintWinsOverRejectedCertificate() = runBlocking {
        MockMac(trustsPhone = false).use { mac ->
            mac.start()
            val result = PairingClient(mac.phoneIdentity).pair(invite(mac).copy(fingerprint = "00".repeat(32)), "P", "M")
            assertEquals(PairResult.FingerprintMismatch, result)
        }
    }

    @Test fun unreachableMacIsFailed() = runBlocking {
        val deadPort = ServerSocket(0).use { it.localPort }
        val identity = MockMac().use { it.phoneIdentity }
        val result = PairingClient(identity).pair(PairingInvite("127.0.0.1", deadPort, "00".repeat(32), "C", "Mac"), "P", "M")
        assertTrue(result is PairResult.Failed)
    }

    @Test fun probeCapturesServerFingerprint() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueJson(403, "")
            mac.start()
            assertEquals(mac.fingerprint, PairingClient(mac.phoneIdentity).probeFingerprint(mac.server.hostName, mac.server.port))
        }
    }

    @Test fun inMemoryStoreEmitsChanges() {
        val store = InMemoryPairingStore()
        val mac = PairedMac("aa", "Mac", "h", 1)
        store.save(mac)
        assertEquals(mac, store.paired.value)
        store.clear()
        assertNull(store.paired.value)
    }
}
