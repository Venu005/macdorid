package dev.macdroid.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRulesTest {
    private fun seg(fx: Float, fy: Float, tx: Float, ty: Float, d: Long, first: Boolean, last: Boolean) = GestureSegment(fx, fy, tx, ty, d, first, last)

    @Test fun aQuickClickIsATap() {
        val t = GestureTracker()
        assertEquals(emptyList<GestureSegment>(), t.down(100f, 200f, 0))
        assertEquals(emptyList<GestureSegment>(), t.move(104f, 203f, 30)) // within slop
        assertEquals(listOf(seg(100f, 200f, 100f, 200f, 50, first = true, last = true)), t.up(104f, 203f, 80))
    }

    // Review Focus #3
    @Test fun aDragIsOneContinuedStroke() {
        val t = GestureTracker()
        t.down(100f, 200f, 0)
        assertEquals(listOf(seg(100f, 200f, 100f, 260f, 40, first = true, last = false)), t.move(100f, 260f, 40))
        assertEquals(listOf(seg(100f, 260f, 100f, 320f, 16, first = false, last = false)), t.move(100f, 320f, 56))
        assertEquals(listOf(seg(100f, 320f, 100f, 340f, 1, first = false, last = true)), t.up(100f, 340f, 56))
    }

    @Test fun holdingStillIsALongPress() {
        val t = GestureTracker()
        t.down(50f, 50f, 0)
        assertEquals(emptyList<GestureSegment>(), t.tick(499))
        assertEquals(listOf(seg(50f, 50f, 50f, 50f, 100, first = true, last = false)), t.tick(500))
        assertEquals(listOf(seg(50f, 50f, 50f, 50f, 100, first = false, last = true)), t.up(50f, 50f, 900))
    }

    @Test fun segmentDurationsAreClamped() {
        val t = GestureTracker()
        t.down(0f, 0f, 0)
        assertEquals(100, t.move(0f, 500f, 5_000).single().durationMs)
    }

    @Test fun cancelEndsAStrokeInProgress() {
        val t = GestureTracker()
        t.down(0f, 0f, 0)
        t.move(0f, 50f, 20)
        assertEquals(listOf(seg(0f, 50f, 0f, 50f, 1, first = false, last = true)), t.cancel())
        assertEquals(emptyList<GestureSegment>(), t.cancel())
    }

    // Review Focus #3
    @Test fun pendingMovesCoalesce() {
        val q = SegmentQueue()
        q.offer(seg(0f, 0f, 0f, 10f, 10, first = true, last = false))
        q.offer(seg(0f, 10f, 0f, 20f, 10, first = false, last = false))
        q.offer(seg(0f, 20f, 0f, 30f, 10, first = false, last = false))
        q.offer(seg(0f, 30f, 0f, 35f, 5, first = false, last = true))
        assertEquals(seg(0f, 0f, 0f, 10f, 10, first = true, last = false), q.next())
        assertEquals(seg(0f, 10f, 0f, 30f, 20, first = false, last = false), q.next())
        assertEquals(seg(0f, 30f, 0f, 35f, 5, first = false, last = true), q.next())
        assertNull(q.next())
        assertTrue(q.isEmpty)
    }

    @Test fun scrollIsAShortClampedSwipe() {
        assertEquals(seg(540f, 1200f, 540f, 900f, 120, first = true, last = true), ScrollGesture.swipe(0.5, 0.5, 0.0, -0.125, 1080, 2400))
        assertEquals(seg(540f, 2352f, 540f, 48f, 120, first = true, last = true), ScrollGesture.swipe(0.5, 0.99, 0.0, -5.0, 1080, 2400))
    }

    @Test fun textGoesInAtTheCursor() {
        assertEquals("helXlo" to 4, TextEdit.insert("hello", 3, 3, "X"))
        assertEquals("hXo" to 2, TextEdit.insert("hello", 1, 4, "X")) // replaces the selection
        assertEquals("helloX" to 6, TextEdit.insert("hello", -1, -1, "X")) // no cursor: append
        assertEquals("hllo" to 1, TextEdit.backspace("hello", 2, 2))
        assertEquals("ho" to 1, TextEdit.backspace("hello", 1, 4))
        assertEquals("hello" to 0, TextEdit.backspace("hello", 0, 0))
    }

    // Review Focus #5
    @Test fun backspaceRemovesAWholeEmoji() {
        val text = "hi 🙂"
        assertEquals("hi " to 3, TextEdit.backspace(text, text.length, text.length))
    }

    private data class Node(override val viewId: String? = null, override val text: String? = null, override val clickable: Boolean = false,
                            override val children: List<UiNode> = emptyList()) : UiNode

    // Review Focus #1
    @Test fun onlyTheMirroringConsentIsMatched() {
        val share = Node("android:id/button1", "Share screen", clickable = true)
        val dialog = Node(children = listOf(Node("com.android.systemui:id/screen_share_permission_dialog", children = listOf(
            Node(text = "Share your screen with MacDroid?"), Node("android:id/button2", "Cancel", true), share))))
        assertSame(share, ConsentMatcher.startButton(dialog))
        val older = Node(children = listOf(Node(text = "Start recording or casting with MacDroid?"), Node(null, "Start now", true)))
        assertEquals("Start now", ConsentMatcher.startButton(older)?.text)
        val other = Node(children = listOf(Node(text = "Start a workout?"), Node(null, "Start", true)))
        assertNull(ConsentMatcher.startButton(other))
        val otherApp = Node(children = listOf(Node(text = "Share your screen with Zoom?"), Node(null, "Share screen", true)))
        assertNull(ConsentMatcher.startButton(otherApp))
    }
}
