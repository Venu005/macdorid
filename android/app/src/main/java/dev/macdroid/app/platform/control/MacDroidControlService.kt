package dev.macdroid.app.platform.control

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.macdroid.app.graph
import dev.macdroid.app.platform.mirror.ScreenSize
import dev.macdroid.core.control.ConsentMatcher
import dev.macdroid.core.control.GestureTracker
import dev.macdroid.core.control.ScrollGesture
import dev.macdroid.core.control.TextEdit
import dev.macdroid.core.protocol.InputKeyName
import dev.macdroid.core.protocol.InputNoticeBody
import dev.macdroid.core.protocol.InputNoticeReason
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.PointerAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "MacDroid control" (spec §3.5): plays the Mac's input as gestures, global actions and text edits, and presses
 * "Share screen" on Android's capture consent while a mirroring session waits for it. Reads nothing else on screen.
 */
class MacDroidControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val tracker = GestureTracker()
    private lateinit var player: GesturePlayer
    private val longPress = Runnable { player.play(tracker.tick(SystemClock.uptimeMillis())) }

    override fun onServiceConnected() {
        player = GesturePlayer(this)
        instance = this
        _running.value = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopped()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopped()
        super.onDestroy()
    }

    private fun stopped() {
        instance = null
        _running.value = false
    }

    override fun onInterrupt() {}

    /** Auto-Start (spec §3.1 step 4): only System UI events, only while a session waits, only MacDroid's consent. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != SYSTEM_UI) return
        if (!ControlPrefs.autoStart(this) || !graph.mirrorBridge.session.awaitingConsent) return
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName != SYSTEM_UI) continue
            val button = ConsentMatcher.startButton(NodeAdapter(root)) as? NodeAdapter ?: continue
            button.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
    }

    /** Input from the Mac; the bridge has checked the session is streaming. Main thread. */
    fun handle(message: Message) {
        val now = SystemClock.uptimeMillis()
        when (message) {
            is Message.InputPointer -> {
                val (w, h) = ScreenSize.current(this)
                val x = (message.body.x * w).toFloat()
                val y = (message.body.y * h).toFloat()
                val segments = when (message.body.action) {
                    PointerAction.Down -> tracker.down(x, y, now).also { main.postDelayed(longPress, LONG_PRESS_MS) }
                    PointerAction.Move -> tracker.move(x, y, now)
                    PointerAction.Up -> tracker.up(x, y, now)
                    PointerAction.Cancel -> tracker.cancel()
                }
                if (message.body.action != PointerAction.Down) main.removeCallbacks(longPress)
                player.play(segments)
            }
            is Message.InputScroll -> {
                val (w, h) = ScreenSize.current(this)
                val b = message.body
                player.play(listOf(ScrollGesture.swipe(b.x, b.y, b.dx, b.dy, w, h)))
            }
            is Message.InputKey -> key(message.body.key)
            is Message.InputText -> edit { text, start, end -> TextEdit.insert(text, start, end, message.body.text.take(MAX_TEXT)) }
            else -> Unit
        }
    }

    private fun key(key: InputKeyName) {
        when (key) {
            InputKeyName.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            InputKeyName.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            InputKeyName.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            InputKeyName.Power -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            InputKeyName.VolumeUp -> volume(AudioManager.ADJUST_RAISE)
            InputKeyName.VolumeDown -> volume(AudioManager.ADJUST_LOWER)
            InputKeyName.Backspace -> edit { text, start, end -> TextEdit.backspace(text, start, end) }
        }
    }

    private fun volume(direction: Int) {
        getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    /** Edits the focused text field, or tells the Mac there isn't one (spec §8). */
    private fun edit(change: (text: String, selStart: Int, selEnd: Int) -> Pair<String, Int>) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        if (node == null) {
            graph.connectionManager.send(Message.InputNotice(InputNoticeBody(InputNoticeReason.NoTextField)))
            return
        }
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val (text, cursor) = change(current, node.textSelectionStart, node.textSelectionEnd)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val LONG_PRESS_MS = 510L
        private const val MAX_TEXT = 4096
        private val _running = MutableStateFlow(false)
        /** The user has turned "MacDroid control" on and Android has connected it. */
        val running: StateFlow<Boolean> = _running.asStateFlow()
        @Volatile var instance: MacDroidControlService? = null
            private set
    }
}
