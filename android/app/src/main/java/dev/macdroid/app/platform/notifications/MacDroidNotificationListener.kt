package dev.macdroid.app.platform.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.macdroid.app.graph

/** The system binds this once the user grants "Notification access" (spec §3.1). */
class MacDroidNotificationListener : NotificationListenerService() {
    private val bridge get() = graph.notificationBridge

    override fun onListenerConnected() {
        bridge.listenerConnected(this)
        graph.mediaWatcher.refresh()
    }

    override fun onListenerDisconnected() = bridge.listenerDisconnected()

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val ranking = Ranking().takeIf { rankingMap.getRanking(sbn.key, it) }
        bridge.posted(sbn, ranking)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) = bridge.removed(sbn.key)
}
