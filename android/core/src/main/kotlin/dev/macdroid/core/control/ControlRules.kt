package dev.macdroid.core.control

/** One piece of a stroke for `dispatchGesture` (spec §3.5). [first] starts a stroke; [last] lifts the finger. */
data class GestureSegment(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val durationMs: Long,
    val first: Boolean,
    val last: Boolean,
)

/**
 * Mac pointer events → stroke segments: a tap, a long-press (held 500 ms within slop), or a drag that follows the mouse
 * as one continued stroke. Not thread-safe.
 */
class GestureTracker(private val slopPx: Float = 10f, private val longPressMs: Long = 500) {
    private enum class State { Idle, Pending, Stroking }
    private var state = State.Idle
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L

    fun down(x: Float, y: Float, tMs: Long): List<GestureSegment> {
        val ended = cancel()
        state = State.Pending
        downX = x; downY = y; downT = tMs
        lastX = x; lastY = y; lastT = tMs
        return ended
    }

    fun move(x: Float, y: Float, tMs: Long): List<GestureSegment> = when (state) {
        State.Idle -> emptyList()
        State.Pending ->
            if (distance(x, y, downX, downY) <= slopPx) emptyList()
            else listOf(segment(downX, downY, x, y, tMs - downT, first = true, last = false)).also { stroking(x, y, tMs) }
        State.Stroking -> listOf(segment(lastX, lastY, x, y, tMs - lastT, first = false, last = false)).also { stroking(x, y, tMs) }
    }

    fun up(x: Float, y: Float, tMs: Long): List<GestureSegment> {
        val out = when (state) {
            State.Idle -> emptyList()
            State.Pending -> listOf(GestureSegment(downX, downY, downX, downY, TAP_MS, first = true, last = true))
            State.Stroking -> listOf(segment(lastX, lastY, x, y, tMs - lastT, first = false, last = true))
        }
        state = State.Idle
        return out
    }

    /** Call regularly while a button is down: starts a long-press once held still long enough. */
    fun tick(tMs: Long): List<GestureSegment> {
        if (state != State.Pending || tMs - downT < longPressMs) return emptyList()
        stroking(downX, downY, tMs)
        return listOf(GestureSegment(downX, downY, downX, downY, MAX_SEGMENT_MS, first = true, last = false))
    }

    fun cancel(): List<GestureSegment> {
        val out = if (state == State.Stroking) listOf(GestureSegment(lastX, lastY, lastX, lastY, 1, first = false, last = true)) else emptyList()
        state = State.Idle
        return out
    }

    private fun stroking(x: Float, y: Float, tMs: Long) {
        state = State.Stroking
        lastX = x; lastY = y; lastT = tMs
    }

    private fun segment(fx: Float, fy: Float, tx: Float, ty: Float, elapsed: Long, first: Boolean, last: Boolean) =
        GestureSegment(fx, fy, tx, ty, elapsed.coerceIn(1, MAX_SEGMENT_MS), first, last)

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float) = kotlin.math.hypot(ax - bx, ay - by)

    companion object {
        const val TAP_MS = 50L
        const val MAX_SEGMENT_MS = 100L
    }
}

/**
 * Segments wait here while the previous one plays. Middle segments that pile up merge into one, so a fast drag stays smooth
 * instead of replaying every mouse sample late (Review Focus #3).
 */
class SegmentQueue {
    private val items = ArrayDeque<GestureSegment>()
    val isEmpty: Boolean get() = items.isEmpty()

    @Synchronized fun offer(segment: GestureSegment) {
        val tail = items.lastOrNull()
        if (tail != null && !tail.first && !tail.last && !segment.first && !segment.last) {
            items[items.lastIndex] = tail.copy(toX = segment.toX, toY = segment.toY,
                durationMs = (tail.durationMs + segment.durationMs).coerceAtMost(GestureTracker.MAX_SEGMENT_MS))
        } else {
            items.addLast(segment)
        }
    }

    @Synchronized fun next(): GestureSegment? = items.removeFirstOrNull()
}

/** A scroll as one short swipe in finger direction, kept on screen (spec §3.5). */
object ScrollGesture {
    const val DURATION_MS = 120L

    fun swipe(x: Double, y: Double, dx: Double, dy: Double, width: Int, height: Int): GestureSegment {
        fun clamp(v: Double) = v.coerceIn(0.02, 0.98)
        val fx = clamp(x); val fy = clamp(y)
        return GestureSegment((fx * width).toFloat(), (fy * height).toFloat(), (clamp(fx + dx) * width).toFloat(), (clamp(fy + dy) * height).toFloat(),
            DURATION_MS, first = true, last = true)
    }
}

/**
 * Scroll batches from the Mac wait here until the phone is free to play the next swipe, adding up meanwhile, so a long
 * scroll doesn't queue seconds of swipes (spec §3.5 "coalesced"). A swipe is never shorter than [minFraction] of the
 * screen: anything shorter would be a tap under Android's touch slop (final review C1).
 */
class ScrollBuffer(private val minFraction: Double = 0.03, private val staleMs: Long = 500) {
    private var x = 0.0
    private var y = 0.0
    private var dx = 0.0
    private var dy = 0.0
    private var lastT: Long? = null

    @Synchronized fun add(x: Double, y: Double, dx: Double, dy: Double, tMs: Long) {
        val last = lastT
        if (last == null || tMs - last > staleMs) {
            this.x = x; this.y = y
            this.dx = 0.0; this.dy = 0.0
        }
        this.dx += dx
        this.dy += dy
        lastT = tMs
    }

    /** The next swipe, or null while what has built up is still too short. */
    @Synchronized fun take(width: Int, height: Int): GestureSegment? {
        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) < minFraction) return null
        val swipe = ScrollGesture.swipe(x, y, dx, dy, width, height)
        dx = 0.0; dy = 0.0
        return swipe
    }
}

/** Typing into the focused field (spec §3.5): insert at the cursor or over the selection; delete whole characters. */
object TextEdit {
    fun insert(current: String, selStart: Int, selEnd: Int, text: String): Pair<String, Int> {
        val (start, end) = range(current, selStart, selEnd)
        return (current.substring(0, start) + text + current.substring(end)) to start + text.length
    }

    fun backspace(current: String, selStart: Int, selEnd: Int): Pair<String, Int> {
        val (start, end) = range(current, selStart, selEnd)
        if (start != end) return (current.substring(0, start) + current.substring(end)) to start
        if (start == 0) return current to 0
        val before = current.offsetByCodePoints(start, -1) // a whole emoji, not half of it (Review Focus #5)
        return (current.substring(0, before) + current.substring(start)) to before
    }

    /** Return alone in a single-line field means "submit" (search, send), not a newline (final review I5). */
    fun submits(text: String, multiLine: Boolean): Boolean = text == "\n" && !multiLine

    private fun range(current: String, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val a = if (selStart < 0) current.length else selStart.coerceAtMost(current.length)
        val b = if (selEnd < 0) current.length else selEnd.coerceAtMost(current.length)
        return minOf(a, b) to maxOf(a, b)
    }
}

/**
 * Password fields read back as bullets, so typing into one edits what MacDroid typed there, not the bullets (final
 * review I1). Unknown when the field holds text MacDroid didn't type. Main thread.
 */
class SecretShadow {
    private var key: String? = null
    private var text = ""

    /** What the field holds, if known: empty for an empty field, or what was typed here, if it still has that length. */
    fun current(key: String, shownLength: Int): String? = when {
        shownLength == 0 -> ""
        key == this.key && text.length == shownLength -> text
        else -> null
    }

    fun remember(key: String, text: String) {
        this.key = key
        this.text = text
    }
}

/** What [ConsentMatcher] needs from an accessibility node (a fake in tests). */
interface UiNode {
    val viewId: String?
    val text: String?
    val clickable: Boolean
    val children: List<UiNode>
}

/**
 * Finds the "Share screen" button on Android's capture consent for MacDroid, and nothing else (spec §3.1 step 4, probe Q1b).
 */
object ConsentMatcher {
    private const val CONTAINER = "com.android.systemui:id/screen_share_permission_dialog"
    private const val POSITIVE = "android:id/button1"
    private const val APP = "MacDroid"
    private val labels = setOf("share screen", "start", "start now")

    fun startButton(root: UiNode): UiNode? {
        val nodes = flatten(root)
        val container = nodes.firstOrNull { it.viewId == CONTAINER }
        if (container != null) {
            // System UI reuses this dialog for its own recorder and Cast: only when it names MacDroid (final review I3).
            val inside = flatten(container)
            if (inside.none { it.text?.contains(APP) == true }) return null
            return inside.firstOrNull { it.viewId == POSITIVE && it.clickable }
        }
        // Older dialogs without the container id: only when the question is for MacDroid ("… with MacDroid?"), so the
        // shade with MacDroid's own notification and another app's "Start" doesn't match.
        if (nodes.none { it.text?.contains("with $APP", ignoreCase = true) == true }) return null
        return nodes.firstOrNull { it.clickable && it.text?.trim()?.lowercase() in labels }
    }

    private fun flatten(node: UiNode, depth: Int = 0): List<UiNode> =
        if (depth > 30) emptyList() else listOf(node) + node.children.flatMap { flatten(it, depth + 1) }
}
