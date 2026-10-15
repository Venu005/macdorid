package dev.macdroid.app.platform.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.macdroid.app.graph

/** The system binds this once the user grants "Notification access" (spec §3.1). */
class MacDroidNotificationListener : NotificationListenerService() {
    private val bridge get() = graph.notificationBridge

    override fun onListenerConnected() {
        bridge.listenerConnected(this)
        graph.callBridge.listenerConnected(this)
        graph.mediaWatcher.refresh()
    }

    override fun onListenerDisconnected() {
        bridge.listenerDisconnected()
        graph.callBridge.listenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (graph.callBridge.posted(sbn)) {
            // Calls go to the Mac's call panel, not its notification list (Plan 10); one that was mirrored before
            // (a VoIP notification turned into a call under the same key) leaves the list (minor 8).
            bridge.removed(sbn.key)
            return
        }
        graph.callBridge.removed(sbn.key) // and a call that became an ordinary notification ends as a call
        val ranking = Ranking().takeIf { rankingMap.getRanking(sbn.key, it) }
        bridge.posted(sbn, ranking)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {
        graph.callBridge.removed(sbn.key)
        bridge.removed(sbn.key)
    }
}
