import Foundation
import Testing
@testable import MacDroidCore

@Suite struct NotificationPresentationTests {
    let now = Date(timeIntervalSince1970: 1_000_000)

    @Test func relativeTimes() {
        #expect(NotificationPresentation.relativeTime(now.addingTimeInterval(-20), now: now) == "now")
        #expect(NotificationPresentation.relativeTime(now.addingTimeInterval(-120), now: now) == "2 min ago")
        #expect(NotificationPresentation.relativeTime(now.addingTimeInterval(-7_200), now: now) == "2 h ago")
        #expect(NotificationPresentation.relativeTime(now.addingTimeInterval(-200_000), now: now) == "2 d ago")
    }

    @Test func subtitleAndTitle() {
        #expect(NotificationPresentation.subtitle(appName: "WhatsApp", phoneName: "Nothing Phone (3)") == "WhatsApp · Nothing Phone (3)")
        #expect(NotificationPresentation.bannerTitle(posted("k", title: "")) == "WhatsApp")
        #expect(NotificationPresentation.bannerTitle(posted("k", title: "Mom")) == "Mom")
    }

    @Test func pageNotices() {
        var state = PhoneNotificationsState(connected: true, phoneSupportsNotifications: false)
        #expect(NotificationPresentation.pageNotice(state) == "Update MacDroid on your phone to use this.")
        state.phoneSupportsNotifications = true
        state.phoneAccess = false
        #expect(NotificationPresentation.pageNotice(state) == "Notification access is off on your phone. Open MacDroid on your phone to turn it on.")
        state.phoneAccess = true
        state.phoneForwarding = false
        #expect(NotificationPresentation.pageNotice(state) == "“Show on your Mac” is off in MacDroid on your phone.")
        state.phoneForwarding = true
        #expect(NotificationPresentation.pageNotice(state) == nil)
        #expect(NotificationPresentation.pageNotice(PhoneNotificationsState(connected: false)) == nil)
    }

    @Test func phonePageLine() {
        #expect(NotificationPresentation.phonePageLine(PhoneNotificationsState(phoneAccess: false)) == "Notifications off on phone")
        #expect(NotificationPresentation.phonePageLine(PhoneNotificationsState(phoneForwarding: false, phoneAccess: true)) == "Notifications off on phone")
        #expect(NotificationPresentation.phonePageLine(PhoneNotificationsState(phoneForwarding: true, phoneAccess: true)) == nil)
    }

    @Test func errorTexts() {
        #expect(NotificationPresentation.errorText(.gone, appName: "WhatsApp", wasReply: true) == "This notification is no longer on your phone")
        #expect(NotificationPresentation.errorText(.failed, appName: "WhatsApp", wasReply: true) == "Couldn't send reply to WhatsApp")
        #expect(NotificationPresentation.errorText(.failed, appName: "Gmail", wasReply: false) == "Couldn't do that in Gmail")
    }
}
