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

        // The window is closed now: a different phone must not get in.
        assertTrue(PairingClient(newIdentity("Intruder")).pair(invite, "Intruder", "JVM") !is PairResult.Success)

        val manager = ConnectionManager(
            InMemoryPairingStore((result as PairResult.Success).mac),
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
