package dev.macdroid.app.platform.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import dev.macdroid.core.control.GestureSegment
import dev.macdroid.core.control.ScrollBuffer
import dev.macdroid.core.control.SegmentQueue

/**
 * Plays [GestureSegment]s one at a time; a drag is one continued stroke (spec §3.5). Segments that arrive while one plays
 * wait in a [SegmentQueue], which merges them so the finger keeps up with the mouse. Main thread.
 */
class GesturePlayer(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val queue = SegmentQueue()
    private val scrolls = ScrollBuffer()
    private var width = 0
    private var height = 0
    private var stroke: GestureDescription.StrokeDescription? = null
    private var playing = false

    fun play(segments: List<GestureSegment>) {
        segments.forEach(queue::offer)
        pump()
    }

    /** A scroll batch: it adds up with others until the phone is free for the next swipe. */
    fun scroll(x: Double, y: Double, dx: Double, dy: Double, tMs: Long, width: Int, height: Int) {
        this.width = width
        this.height = height
        scrolls.add(x, y, dx, dy, tMs)
        pump()
    }

    private fun pump() {
        if (playing) return
        val segment = queue.next() ?: scrolls.take(width, height) ?: return
        val path = Path().apply {
            moveTo(segment.fromX, segment.fromY)
            lineTo(segment.toX, segment.toY)
        }
        val previous = stroke
        val next = if (segment.first || previous == null) {
            GestureDescription.StrokeDescription(path, 0, segment.durationMs, !segment.last)
        } else {
            previous.continueStroke(path, 0, segment.durationMs, !segment.last)
        }
        stroke = if (segment.last) null else next
        playing = true
        val started = service.dispatchGesture(GestureDescription.Builder().addStroke(next).build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                playing = false
                pump()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                playing = false
                stroke = null
                pump()
            }
        }, main)
        if (!started) {
            // Refused (e.g. the service is rebinding): drop this segment and carry on with the queue on the next loop,
            // so queued input doesn't wait for the next event (Plan 8 minor #4).
            playing = false
            stroke = null
            main.post { pump() }
        }
    }
}
