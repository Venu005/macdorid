package dev.macdroid.app.platform.control

import android.view.accessibility.AccessibilityNodeInfo
import dev.macdroid.core.control.UiNode

/** An accessibility node seen through [UiNode], for [dev.macdroid.core.control.ConsentMatcher]. */
class NodeAdapter(val node: AccessibilityNodeInfo) : UiNode {
    override val viewId: String? get() = node.viewIdResourceName
    override val text: String? get() = node.text?.toString()
    override val clickable: Boolean get() = node.isClickable
    override val children: List<UiNode> get() = (0 until node.childCount).mapNotNull { node.getChild(it)?.let(::NodeAdapter) }
}
