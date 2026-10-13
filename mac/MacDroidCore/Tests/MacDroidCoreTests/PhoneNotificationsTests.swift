import Foundation
import Testing
@testable import MacDroidCore

@Suite struct PhoneNotificationsTests {
    final class Harness: Sendable {
        let sent: Collector<Message>
        let events: Collector<PhoneNotificationEvent>
        let center: PhoneNotifications
        let icons: IconCache
        let listener: Task<Void, Never>

        init(failSends: Bool = false) throws {
            let sent = Collector<Message>()
            let events = Collector<PhoneNotificationEvent>()
            let icons = IconCache(directory: try makeTemporaryDirectory())
            let center = PhoneNotifications(icons: icons, now: { Date(timeIntervalSince1970: 1_000_000) }, send: { message in
                if failSends { throw HubError.notConnected }
                await sent.append(message)
            })
            let stream = center.events // subscribe before anything happens
            self.sent = sent
            self.events = events
            self.icons = icons
            self.center = center
            self.listener = Task { for await event in stream { await events.append(event) } }
        }
    }

    @Test func postedAppearsGroupedAndShowsABanner() async throws {
        let h = try Harness()
        let notification = posted("k1")
        await h.center.handle(.notificationPosted(notification))
        #expect(await h.center.state.groups.first?.entries.first?.posted == notification)
        try await eventually { await h.events.items == [.show(notification, banner: true)] }
        h.listener.cancel()
    }

    @Test func aFloodStopsBannersButKeepsEntries() async throws {
        let h = try Harness()
        for i in 0..<11 { await h.center.handle(.notificationPosted(posted("k\(i)"))) }
        #expect(await h.center.state.count == 11)
        try await eventually { await h.events.items.count == 11 }
        #expect(await h.events.items.last == .show(posted("k10"), banner: false))
        h.listener.cancel()
    }

    @Test func removedRemovesTheEntryAndTheBanner() async throws {
        let h = try Harness()
        await h.center.handle(.notificationPosted(posted("k1")))
        await h.center.handle(.notificationRemoved(NotificationKey(key: "k1")))
        #expect(await h.center.state.count == 0)
        try await eventually { await h.events.items.last == .remove(keys: ["k1"]) }
        h.listener.cancel()
    }

    @Test func syncRemovesEntriesThePhoneNoLongerHas() async throws {
        let h = try Harness()
        await h.center.handle(.notificationPosted(posted("a")))
        await h.center.handle(.notificationPosted(posted("b")))
        await h.center.handle(.notificationSync(NotificationSync(keys: ["b"])))
        #expect(await h.center.state.groups.flatMap(\.entries).map(\.posted.key) == ["b"])
        try await eventually { await h.events.items.last == .remove(keys: ["a"]) }
        h.listener.cancel()
    }

    @Test func iconsAreCached() async throws {
        let h = try Harness()
        await h.center.handle(.notificationIcon(NotificationIcon(hash: "3f2a9c1d0b7e4f52", png: "iVBORw0KGgo=")))
        #expect(h.icons.url(for: "3f2a9c1d0b7e4f52") != nil)
        h.listener.cancel()
    }

    @Test func appsMessageUpdatesState() async throws {
        let h = try Harness()
        let app = PhoneApp(packageName: "com.whatsapp", appName: "WhatsApp", iconHash: "", muted: false)
        await h.center.handle(.notificationApps(NotificationApps(forwarding: false, access: true, apps: [app])))
        let state = await h.center.state
        #expect(state.apps == [app])
        #expect(state.phoneForwarding == false)
        #expect(state.phoneAccess == true)
        h.listener.cancel()
    }

    @Test func dismissRemovesLocallyAndTellsThePhone() async throws {
        let h = try Harness()
        await h.center.handle(.notificationPosted(posted("k1")))
        await h.center.dismiss(key: "k1")
        #expect(await h.center.state.count == 0)
        #expect(await h.sent.items == [.notificationDismiss(NotificationKey(key: "k1"))])
        h.listener.cancel()
    }

    @Test func clearAllDismissesEverything() async throws {
        let h = try Harness()
        await h.center.handle(.notificationPosted(posted("a")))
        await h.center.handle(.notificationPosted(posted("b")))
        await h.center.clearAll()
        #expect(await h.center.state.count == 0)
        let sent = await h.sent.items
        #expect(sent.count == 2)
        #expect(sent.contains(.notificationDismiss(NotificationKey(key: "a"))))
        #expect(sent.contains(.notificationDismiss(NotificationKey(key: "b"))))
        h.listener.cancel()
    }

    @Test func goneErrorRemovesTheEntryAndExplains() async throws {
        let h = try Harness()
        await h.center.handle(.notificationPosted(posted("k1")))
        await h.center.reply(key: "k1", text: "On my way")
        await h.center.handle(.notificationError(NotificationError(key: "k1", reason: .gone)))
        #expect(await h.center.state.count == 0)
        try await eventually { await h.events.items.contains(.problem("This notification is no longer on your phone")) }
        h.listener.cancel()
    }

    @Test func failedReplyNamesTheApp() async throws {
        let h = try Harness()
        await h.center.handle(.notificationPosted(posted("k1")))
        await h.center.reply(key: "k1", text: "Hi")
        await h.center.handle(.notificationError(NotificationError(key: "k1", reason: .failed)))
        try await eventually { await h.events.items.contains(.problem("Couldn't send reply to WhatsApp")) }
        h.listener.cancel()
    }

    @Test func replyWhileDisconnectedExplains() async throws {
        let h = try Harness(failSends: true)
        await h.center.reply(key: "k1", text: "Hi")
        try await eventually { await h.events.items.contains(.problem("Your phone isn't connected, so the reply wasn't sent")) }
        h.listener.cancel()
    }

    @Test func disabledIgnoresPostedNotifications() async throws {
        let h = try Harness()
        await h.center.setEnabled(false)
        await h.center.handle(.notificationPosted(posted("k1")))
        #expect(await h.center.state.count == 0)
        h.listener.cancel()
    }

    @Test func mutingUpdatesOptimisticallyAndAsksThePhone() async throws {
        let h = try Harness()
        await h.center.handle(.notificationApps(NotificationApps(forwarding: true, access: true, apps: [
            PhoneApp(packageName: "com.discord", appName: "Discord", iconHash: "", muted: false),
        ])))
        await h.center.setMuted(packageName: "com.discord", muted: true)
        #expect(await h.center.state.apps.first?.muted == true)
        #expect(await h.sent.items == [.notificationMute(NotificationMute(packageName: "com.discord", muted: true))])
        h.listener.cancel()
    }

    @Test func connectionStatusTracksPhoneSupport() async throws {
        let h = try Harness()
        await h.center.connectionChanged(.connected(PeerInfo(device: "Phone", app: "0.3.0", features: [Feature.files])))
        #expect(await h.center.state.connected)
        #expect(await h.center.state.phoneSupportsNotifications == false)
        h.listener.cancel()
    }
}

@Suite struct IconCacheTests {
    @Test func rejectsHashesThatAreNotSixteenLowercaseHex() throws {
        let cache = IconCache(directory: try makeTemporaryDirectory())
        for bad in ["../../Library/x", "3F2A9C1D0B7E4F52", "3f2a9c1d0b7e4f5", "3f2a9c1d0b7e4f52/", "", "zzzzzzzzzzzzzzzz"] {
            #expect(cache.save(hash: bad, pngBase64: "iVBORw0KGgo=") == false)
            #expect(cache.url(for: bad) == nil)
        }
    }

    @Test func rejectsInvalidBase64AndKeepsGoodIcons() throws {
        let cache = IconCache(directory: try makeTemporaryDirectory())
        #expect(cache.save(hash: "0123456789abcdef", pngBase64: "not base64!") == false)
        #expect(cache.save(hash: "0123456789abcdef", pngBase64: "iVBORw0KGgo="))
        let url = try #require(cache.url(for: "0123456789abcdef"))
        #expect(try Data(contentsOf: url) == Data(base64Encoded: "iVBORw0KGgo="))
    }
}
