package dev.macdroid.app

import dev.macdroid.app.platform.NsdMacLocator
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NsdMacLocatorTest {
    private val v6 = InetAddress.getByAddress(ByteArray(15) + byteArrayOf(1))
    private val v4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 7))

    @Test fun picksIpv4EvenWhenIpv6Listed_first() = assertEquals("192.168.1.7", NsdMacLocator.pickIpv4(listOf(v6, v4)))
    @Test fun nullWhenOnlyIpv6() = assertNull(NsdMacLocator.pickIpv4(listOf(v6)))
    @Test fun nullWhenEmpty() = assertNull(NsdMacLocator.pickIpv4(emptyList()))
}
