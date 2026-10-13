import Foundation
import Testing
@testable import MacDroidCore

func posted(_ key: String, app: String = "com.whatsapp", name: String = "WhatsApp", title: String = "Mom",
            buttons: [String] = [], canReply: Bool = false) -> NotificationPosted {
    NotificationPosted(
        key: key, packageName: app, appName: name, title: title, text: "text \(key)", postedAt: 1,
        actions: buttons.enumerated().map { NotificationButton(index: $0.offset + 2, title: $0.element) },
        canReply: canReply, iconHash: "", isUpdate: false
    )
}

@Suite struct NotificationStoreTests {
    let start = Date(timeIntervalSince1970: 1_000_000)

    @Test func newestFirstAndUpdatesMoveToTheTop() {
        var store = NotificationStore()
        store.upsert(posted("a"), at: start)
        store.upsert(posted("b"), at: start.addingTimeInterval(1))
        store.upsert(posted("a", title: "Mom (2)"), at: start.addingTimeInterval(2))
        #expect(store.entries.map(\.posted.key) == ["a", "b"])
        #expect(store.entries[0].posted.title == "Mom (2)")
    }

    @Test func keepsAtMostFifty() {
        var store = NotificationStore()
        for i in 0..<60 { store.upsert(posted("k\(i)"), at: start.addingTimeInterval(Double(i))) }
        #expect(store.entries.count == 50)
        #expect(store.entries.first?.posted.key == "k59")
        #expect(store.entry(key: "k9") == nil)
    }

    @Test func retainReturnsWhatWasRemoved() {
        var store = NotificationStore()
        ["a", "b", "c"].forEach { store.upsert(posted($0), at: start) }
        #expect(Set(store.retain(keys: ["b"])) == ["a", "c"])
        #expect(store.entries.map(\.posted.key) == ["b"])
    }

    @Test func clearReturnsEveryKey() {
        var store = NotificationStore()
        ["a", "b"].forEach { store.upsert(posted($0), at: start) }
        #expect(Set(store.clear()) == ["a", "b"])
        #expect(store.entries.isEmpty)
    }

    @Test func groupsByAppOrderedByNewestEntry() {
        var store = NotificationStore()
        store.upsert(posted("w1"), at: start)
        store.upsert(posted("g1", app: "com.google.android.gm", name: "Gmail"), at: start.addingTimeInterval(1))
        store.upsert(posted("w2"), at: start.addingTimeInterval(2))
        let groups = store.groups
        #expect(groups.map(\.packageName) == ["com.whatsapp", "com.google.android.gm"])
        #expect(groups[0].entries.map(\.posted.key) == ["w2", "w1"])
    }

    @Test func floodGateAllowsTenPerFiveSecondsPerApp() {
        var gate = FloodGate()
        let results = (0..<11).map { gate.allowsBanner(packageName: "chat", at: start.addingTimeInterval(Double($0) * 0.1)) }
        #expect(results.prefix(10).allSatisfy { $0 })
        #expect(results[10] == false)
        let otherApp = gate.allowsBanner(packageName: "mail", at: start.addingTimeInterval(1))
        let afterTheWindow = gate.allowsBanner(packageName: "chat", at: start.addingTimeInterval(7))
        #expect(otherApp)
        #expect(afterTheWindow)
    }

    @Test func categoryDependsOnReplyAndButtonTitlesOnly() {
        let a = NotificationCategories.spec(for: posted("1", buttons: ["Mark as read"], canReply: true))
        let b = NotificationCategories.spec(for: posted("2", title: "Other", buttons: ["Mark as read"], canReply: true))
        let c = NotificationCategories.spec(for: posted("3", buttons: ["Mark as read"], canReply: false))
        let d = NotificationCategories.spec(for: posted("4", buttons: ["Archive"], canReply: true))
        #expect(a.identifier == b.identifier)
        #expect(a.identifier != c.identifier)
        #expect(a.identifier != d.identifier)
        #expect(a.identifier.hasPrefix(NotificationCategories.prefix))
        #expect(a.buttons == ["Mark as read"])
    }

    @Test func categoriesKeepAtMostThreeButtonsAndMapPositionsToIndices() {
        let notification = posted("1", buttons: ["A", "B", "C", "D"])
        #expect(NotificationCategories.spec(for: notification).buttons == ["A", "B", "C"])
        #expect(NotificationCategories.actionIndices(for: notification) == [2, 3, 4])
        #expect(NotificationCategories.position(ofAction: NotificationCategories.actionIdentifier(position: 2)) == 2)
        #expect(NotificationCategories.position(ofAction: "reply") == nil)
    }
}
