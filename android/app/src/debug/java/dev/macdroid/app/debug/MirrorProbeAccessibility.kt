package dev.macdroid.app.debug

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Debug-only: logs System UI windows and presses a Start/Start now/Allow button (spec §9.1 Q1). */
class MirrorProbeAccessibility : AccessibilityService() {
    private val tag = MirrorProbeActivity.TAG
    private val start = Regex("^(start|start now|share screen)$", RegexOption.IGNORE_CASE)

    override fun onServiceConnected() { Log.w(tag, "probe accessibility connected") }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != "com.android.systemui") return
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName != "com.android.systemui") continue
            // Only the capture consent window: skip the lock screen and notification shade.
            val consent = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/screen_share_permission_dialog").isNotEmpty()
            if (!consent) continue
            val button = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull() ?: find(root)
            button?.let { Log.w(tag, "clicking '${it.text}' id=${it.viewIdResourceName} ok=${it.performAction(AccessibilityNodeInfo.ACTION_CLICK)}") }
        }
    }

    private fun dump(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 12) return
        if (!node.text.isNullOrEmpty() || node.viewIdResourceName != null) Log.w(tag, "${"  ".repeat(depth)}${node.className} '${node.text}' id=${node.viewIdResourceName} click=${node.isClickable}")
        for (i in 0 until node.childCount) node.getChild(i)?.let { dump(it, depth + 1) }
    }

    private fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.text?.toString()?.trim()?.let(start::matches) == true) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child)?.let { return it } }
        return null
    }

    override fun onInterrupt() {}
}
