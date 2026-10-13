package dev.macdroid.core

import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.connection.MacLocator
import dev.macdroid.core.pairing.InMemoryPairingStore
import dev.macdroid.core.pairing.PairResult
import dev.macdroid.core.pairing.PairingClient
import dev.macdroid.core.pairing.PairingInvite
import dev.macdroid.core.protocol.DeviceStatusBody
import dev.macdroid.core.protocol.HelloBody
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.tls.ClientIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

        val manager = ConnectionManager(
            InMemoryPairingStore(result.mac),
            { phone },
            MacLocator.None,
            { HelloBody(app = "test", device = "MacDroid Test Phone") },
        )
        manager.start()
        try {
            assertEquals(ConnectionState.Connected("MacDroid Dev Server"), withTimeout(10_000) { manager.state.first { it is ConnectionState.Connected } })
            assertTrue(manager.send(Message.DeviceStatus(DeviceStatusBody(77, false))))
            delay(500)
        } finally {
            manager.stop()
        }
    }
}
