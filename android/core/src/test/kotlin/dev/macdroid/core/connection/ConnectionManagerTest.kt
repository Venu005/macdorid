package dev.macdroid.core.connection

import dev.macdroid.core.MockMac
import dev.macdroid.core.eventually
import dev.macdroid.core.pairing.InMemoryPairingStore
import dev.macdroid.core.pairing.PairingStore
import dev.macdroid.core.protocol.DeviceStatusBody
import dev.macdroid.core.protocol.HelloBody
import dev.macdroid.core.protocol.Message
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionManagerTest {
    private val phoneHello = HelloBody(app = "0.1.0", device = "Test Phone")

    private fun manager(mac: MockMac, store: PairingStore, locator: MacLocator = MacLocator.None, backoff: Backoff = Backoff(50, 200)) =
        ConnectionManager(store, { mac.phoneIdentity }, locator, { phoneHello }, backoff = backoff)

    private suspend fun ConnectionManager.awaitState(timeoutMs: Long = 5_000, predicate: (ConnectionState) -> Boolean) =
        withTimeout(timeoutMs) { state.first(predicate) }

    @Test fun backoffDoublesAndCaps() {
        val backoff = Backoff(1_000, 30_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(7) { backoff.next() })
        backoff.reset()
        assertEquals(1_000L, backoff.next())
    }

    @Test fun txtRecordMatching() {
        val fp = "8a3ba983c43884812d5198df10a6891c193f3fb67d1e5b44c6902e9fde37b45a"
        assertTrue(MacLocator.txtMatches(mapOf("fp" to "8a3ba983c4388481".toByteArray(), "v" to "1".toByteArray()), fp))
        assertFalse(MacLocator.txtMatches(mapOf("fp" to "0000000000000000".toByteArray()), fp))
        assertFalse(MacLocator.txtMatches(emptyMap(), fp))
    }

    @Test fun connectsAndExchangesHello(): Unit = runBlocking {
        MockMac().use { mac ->
            mac.enqueueSession()
            mac.start()
            val manager = manager(mac, InMemoryPairingStore(mac.pairedMac()))
            manager.start()
            try {
                assertEquals(ConnectionState.Connected("Test Mac"), manager.awaitState { it is ConnectionState.Connected })
                assertEquals(Message.Hello(phoneHello), mac.received.poll(5, TimeUnit.SECONDS))
            } finally {
                manager.stop()
            }
        }
    }

    @Test fun sendDeliversMessages(): Unit = runBlocking {
        MockMac().use { mac ->
            mac.enqueueSession()
            mac.start()
            val manager = manager(mac, InMemoryPairingStore(mac.pairedMac()))
            manager.start()
            try {
                manager.awaitState { it is ConnectionState.Connected }
                assertTrue(manager.send(Message.DeviceStatus(DeviceStatusBody(55, false))))
                mac.received.poll(5, TimeUnit.SECONDS) // hello
                assertEquals(Message.DeviceStatus(DeviceStatusBody(55, false)), mac.received.poll(5, TimeUnit.SECONDS))
            } finally {
                manager.stop()
            }
            assertFalse(manager.send(Message.DeviceStatus(DeviceStatusBody(1, false))))
            assertEquals(ConnectionState.Stopped, manager.state.value)
        }
    }

    @Test fun waitsWhileNotPaired(): Unit = runBlocking {
        MockMac().use { mac ->
            mac.enqueueSession()
            mac.start()
            val store = InMemoryPairingStore()
            val manager = manager(mac, store)
            manager.start()
            try {
                manager.awaitState { it == ConnectionState.NotPaired }
                store.save(mac.pairedMac())
                manager.awaitState { it is ConnectionState.Connected }
            } finally {
                manager.stop()
            }
        }
    }

    @Test fun reconnectsAfterMacCloses(): Unit = runBlocking {
        MockMac().use { mac ->
            mac.enqueueSession(closeAfterHello = true)
            mac.enqueueSession()
            mac.start()
            val manager = manager(mac, InMemoryPairingStore(mac.pairedMac()))
            manager.start()
            try {
                eventually { mac.server.requestCount >= 2 }
                manager.awaitState { it is ConnectionState.Connected }
            } finally {
                manager.stop()
            }
        }
    }

    @Test fun wrongPinIsTrustFailure(): Unit = runBlocking {
        MockMac().use { mac ->
            mac.start()
            val manager = manager(mac, InMemoryPairingStore(mac.pairedMac().copy(fingerprint = "00".repeat(32))))
            manager.start()
            try {
                manager.awaitState { it is ConnectionState.TrustFailure }
            } finally {
                manager.stop()
            }
        }
    }

    // Review Focus #1
    @Test fun movesToAddressReportedByLocator(): Unit = runBlocking {
        MockMac().use { mac ->
            mac.enqueueSession()
            mac.start()
            val deadPort = ServerSocket(0).use { it.localPort }
            val store = InMemoryPairingStore(mac.pairedMac().copy(port = deadPort))
            val locator = MacLocator { flowOf(MacAddress(mac.server.hostName, mac.server.port)) }
            val manager = manager(mac, store, locator, backoff = Backoff(10_000, 10_000))
            manager.start()
            try {
                manager.awaitState { it is ConnectionState.Connected }
                assertEquals(mac.server.port, store.paired.value?.port)
            } finally {
                manager.stop()
            }
        }
    }

    @Test fun nudgeRetriesImmediately(): Unit = runBlocking {
        MockMac().use { mac ->
            mac.server.enqueue(MockResponse.Builder().code(500).build())
            mac.enqueueSession()
            mac.start()
            val manager = manager(mac, InMemoryPairingStore(mac.pairedMac()), backoff = Backoff(10_000, 10_000))
            manager.start()
            try {
                manager.awaitState { it is ConnectionState.Waiting }
                manager.nudge()
                manager.awaitState(timeoutMs = 2_000) { it is ConnectionState.Connected }
            } finally {
                manager.stop()
            }
        }
    }
}
