package dev.macdroid.app.platform.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import dev.macdroid.core.control.GestureSegment
import dev.macdroid.core.control.SegmentQueue

/**
 * Plays [GestureSegment]s one at a time; a drag is one continued stroke (spec §3.5). Segments that arrive while one plays
 * wait in a [SegmentQueue], which merges them so the finger keeps up with the mouse. Main thread.
 */
class GesturePlayer(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val queue = SegmentQueue()
    private var stroke: GestureDescription.StrokeDescription? = null
    private var playing = false

    fun play(segments: List<GestureSegment>) {
        segments.forEach(queue::offer)
        pump()
    }

    private fun pump() {
        if (playing) return
        val segment = queue.next() ?: return
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
            playing = false
            stroke = null
        }
    }
}
