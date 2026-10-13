package dev.macdroid.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanGateTest {
    @Test fun `nothing is ignored before a failure`() {
        assertFalse(ScanGate().shouldIgnore("macdroid://x", 0))
    }

    @Test fun `same code is ignored shortly after failing`() {
        val gate = ScanGate(5_000)
        gate.markFailed("macdroid://x", 1_000)
        assertTrue(gate.shouldIgnore("macdroid://x", 3_000))
    }

    @Test fun `same code is accepted again after the window`() {
        val gate = ScanGate(5_000)
        gate.markFailed("macdroid://x", 1_000)
        assertFalse(gate.shouldIgnore("macdroid://x", 6_000))
    }

    @Test fun `a different code is never ignored`() {
        val gate = ScanGate(5_000)
        gate.markFailed("macdroid://x", 1_000)
        assertFalse(gate.shouldIgnore("macdroid://y", 1_500))
    }
}
