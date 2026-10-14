package dev.macdroid.core.mirror

import org.junit.Assert.assertTrue
import org.junit.Test

class SmallSendBufferTest {
    // Final review I4: a backlog must stay in OkHttp's queue (visible to SendPolicy), not hide in a large kernel buffer.
    @Test fun socketsGetASmallSendBufferAndNoDelay() {
        SmallSendBuffer(65_536).createSocket().use { socket ->
            assertTrue(socket.tcpNoDelay)
            assertTrue("send buffer ${socket.sendBufferSize}", socket.sendBufferSize in 1..(65_536 * 2)) // Linux reports double
        }
    }
}
