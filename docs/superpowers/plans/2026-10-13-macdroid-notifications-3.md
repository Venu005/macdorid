# MacDroid Notifications — Plan 3: Groundwork and Notification Mirroring

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android notifications appear on the Mac as real macOS notifications that can be replied to, actioned and dismissed (both ways), with per-app mutes owned by the phone, a Mac Notifications page, and an Android Notifications tab that acts as a control panel.

**Architecture:**
- **Protocol first** (Task 1): ten `notification.*` messages in both codecs, with shared fixtures, plus a `hello` whose `features` may be missing.
- **Feature exchange** (Task 2): each side announces `["files","notifications"]`, the Mac hub forwards every feature message, and Android's `ConnectionState.Connected` carries the Mac's features.
- **Pure, tested logic in the cores:**
  - Android `core/notifications` (Task 3): the filter rules, update de-duplication, mutes, the apps-seen list, sync and icons.
  - Mac `MacDroidCore/Notifications` (Tasks 4–5): the 50-entry store, flood gate, category ids, icon cache, the `PhoneNotifications` actor and the presentation text.
- **Thin platform glue:**
  - Mac (Tasks 6–7): one `UNUserNotificationCenter` delegate (the `NotificationRouter`) shared with the received-file notifications, a `PhoneNotifier`, the Notifications page and the settings.
  - Android (Tasks 8–9): a `NotificationListenerService` plus a `NotificationBridge`, the Notifications tab, and the setup step.
- **A cross-platform check** (Task 10) and the final gates and handoff (Task 11).

**Tech Stack:**
- **Mac:** Swift 6 (language mode 6.0), Swift Testing, SwiftUI (macOS 14+), UserNotifications, swift-crypto, XcodeGen.
- **Android:** Kotlin 2.4.21, kotlinx.serialization 1.11, coroutines 1.11, JUnit 4, Compose Material 3 (BOM 2026.09.00), `NotificationListenerService`, `androidx.core` `NotificationCompat`/`RemoteInput`.

**Spec:** `docs/superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md`: §2.1–2.2, §3, §5, §6 and **§8 (the self-review fixes, which win over earlier sections)**. Plan 4 (clipboard) follows on the same branch.

## Global Constraints

- **Branch:** `feat/notifications-clipboard`, created from `main` before Task 1. Don't merge: merging waits for the owner's hands-on check after Plan 4.
- **Handoff rule (`/CLAUDE.md`):** every task's commit also updates `docs/handoff/`.
  - Task 1 adds a CHANGELOG entry at the top headed `## <commit date> — Plan 3: notifications (in progress on feat/notifications-clipboard)`.
  - Each later task adds one bullet under that entry, plus any doc edits its "Handoff" step names.
- **Protocol is a contract:**
  - Every new message gets a fixture in `protocol/fixtures/`, decoded and round-tripped by both test suites.
  - `protocol/PROTOCOL.md` is updated in the same task.
  - The JSON key for an app's package is `package` on the wire. The property is `packageName` in Swift and Kotlin, because `package` is a keyword in Kotlin.
- **Features:**
  - Swift `ProtocolInfo.features = ["files", "notifications"]`.
  - Kotlin `Features.SUPPORTED = listOf("files", "notifications")`.
  - A feature is active only when both sides list it. Plan 4 adds `"clipboard"`.
- **Limits (spec §2.2, §8):**
  - title ≤ 200 characters, text ≤ 2,000, at most 3 buttons
  - icon hash = exactly 16 lowercase hex characters, and the icon PNG ≤ 96×96
  - the Mac keeps the last 50 notifications, in memory only
  - flood rule: a banner only if that app posted ≤ 10 in the last 5 s, counting this one
- **Exclusions (phone, spec §3.1):**
  - MacDroid's own package
  - `FLAG_ONGOING_EVENT` or `FLAG_FOREGROUND_SERVICE`
  - `FLAG_GROUP_SUMMARY`
  - importance ≤ `IMPORTANCE_MIN`
  - empty title and text
  - a muted app
  - "Show on your Mac" off
  - not connected
- **Copy (Mac):**
  - Banner subtitle: "‹appName› · ‹phone name›".
  - Page title "Notifications"; header buttons "Muted apps…" and "Clear all".
  - Empty state: "Notifications from your phone appear here."
  - Errors: "This notification is no longer on your phone", "Couldn't send reply to ‹app›", "Couldn't do that in ‹app›".
  - Missing feature: "Update MacDroid on your phone to use this."
  - Phone page line: "Notifications off on phone".
  - Settings section "Notifications" with the toggle "Show phone notifications".
- **Copy (Android):**
  - Top card: "Show on your Mac", with "Forwarding to ‹Mac name›" or "Off — notifications stay on this phone".
  - Access off: "Notification access is off" with the button "Turn on".
  - Section header "Apps"; footnote "Apps appear here after their first notification. Mutes sync with the Mac."
  - One-time codes note: "Android hides one-time codes from apps like MacDroid, so login codes show without the code."
  - Setup step: title "See phone notifications on your Mac", body "Reply to messages and clear notifications from your Mac. MacDroid only sends them to your paired Mac — never to the internet.", buttons "Allow notification access" and "Not now".
- **Versions** (Task 11 bumps them): Mac `CFBundleShortVersionString` "0.4.0" and `CFBundleVersion` "4"; Android `versionName` "0.4.0" and `versionCode` 4.
- **Build gates** (all pass with no warnings before each commit that touches that side):
  - `cd mac/MacDroidCore && swift test`
  - `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build`
  - `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
- **Swift paths:** use `url.path(percentEncoded: false)`, never `url.path`.
- **No UI test harness exists.** Every testable rule lives in a pure type with unit tests. Views are verified by building them and by the owner's hands-on checklist.
- **Don't touch the owner's phone** from automation. No `adb shell pm grant`, no clearing app data, no taps. Installing a debug build with `adb install -r` is fine only if the owner asks.

## Review Focus

1. **A crafted `notification.icon` hash like `../../Library/x`** must never become a file path outside the icon cache. The Mac must ignore it. → Task 5 `IconCacheTests.rejectsHashesThatAreNotSixteenLowercaseHex`.
2. **Chat apps repost the same notification silently** (read receipts, sorting). The Mac must not alert again for an unchanged notification. → Task 3 `identicalRepostIsNotSentAgain`.
3. **The phone app restarts while the Mac still shows notifications.** On reconnect the Mac must clear the stale ones. → Task 3 `afterARestartSyncIsEmpty`; Task 5 `syncRemovesEntriesThePhoneNoLongerHas`.
4. **Replying to a notification that has already disappeared on the phone** must show "This notification is no longer on your phone" on the Mac and remove the entry, not fail silently. → Task 5 `goneErrorRemovesTheEntryAndExplains`.
5. **A title cut at the 200-character limit in the middle of an emoji** must stay valid text (no lone surrogate). → Task 3 `clipNeverSplitsASurrogatePair`.

---

## File Structure

```
protocol/
  PROTOCOL.md                                         (modify: notification messages, rules, features)
  fixtures/notification.*.json (10 new), hello.no-features.json (new)

mac/MacDroidCore/Sources/MacDroidCore/
  Protocol/Messages.swift                             (modify: notification bodies, Message cases, tolerant Hello, features)
  Protocol/MessageCodec.swift                         (modify: encode/decode the new types)
  Session/ConnectionHub.swift                         (modify: default features, forward every feature message)
  Notifications/NotificationStore.swift               (new: entries, groups, 50 cap)
  Notifications/FloodGate.swift                       (new)
  Notifications/NotificationCategories.swift          (new: category ids, action ids)
  Notifications/IconCache.swift                       (new)
  Notifications/PhoneNotifications.swift              (new: actor)
  Presentation/NotificationPresentation.swift         (new: pure text)
  MacDroidDevServer/main.swift                        (modify, Task 10)
mac/MacDroidCore/Tests/MacDroidCoreTests/
  ProtocolFixtureTests.swift, ConnectionHubTests.swift (modify)
  NotificationStoreTests.swift, PhoneNotificationsTests.swift, NotificationPresentationTests.swift (new)

mac/MacDroid/
  Notifications/NotificationRouter.swift              (new: the only UNUserNotificationCenter delegate)
  Notifications/PhoneNotifier.swift                   (new)
  Files/ReceivedNotifier.swift                        (modify: no longer the delegate)
  AppModel.swift                                      (modify: PhoneNotifications wiring)
  SidebarSection.swift, Views/MainWindow.swift        (modify)
  Views/Notifications/NotificationsView.swift, NotificationCard.swift, MutedAppsSheet.swift (new)
  Views/Settings/GeneralSettingsView.swift            (modify: Notifications section)
  Views/Phone/PairedDeviceView.swift                  (modify: "Notifications off on phone")

android/core/src/main/kotlin/dev/macdroid/core/
  protocol/Messages.kt, protocol/MessageCodec.kt      (modify)
  connection/ConnectionManager.kt                     (modify: Connected.features)
  notifications/NotificationModel.kt                  (new)
  notifications/NotificationRules.kt                  (new)
  notifications/NotificationForwarder.kt              (new)
android/core/src/test/kotlin/dev/macdroid/core/
  protocol/MessageCodecTest.kt, connection/ConnectionManagerTest.kt, TestSupport.kt, CrossPlatformTest.kt (modify)
  notifications/NotificationForwarderTest.kt          (new)

android/app/src/main/
  AndroidManifest.xml                                 (modify: listener service, <queries>)
  java/dev/macdroid/app/MacDroidApp.kt                (modify: hello features, bridge)
  java/dev/macdroid/app/platform/Permissions.kt       (modify: hasNotificationAccess)
  java/dev/macdroid/app/platform/notifications/NotificationText.kt, NotificationReader.kt,
      AppIcons.kt, SharedPrefsNotificationSettings.kt, NotificationBridge.kt,
      MacDroidNotificationListener.kt                 (new)
  java/dev/macdroid/app/ui/presentation/NotificationsPresentation.kt (new, pure)
  java/dev/macdroid/app/ui/notifications/NotificationsScreen.kt, NotificationsViewModel.kt (new)
  java/dev/macdroid/app/ui/main/MainScreen.kt         (modify)
  java/dev/macdroid/app/ui/setup/SetupFlow.kt, SetupViewModel.kt, SetupScreen.kt (modify)
android/app/src/test/java/dev/macdroid/app/
  NotificationTextTest.kt, NotificationsPresentationTest.kt (new), SetupFlowTest.kt (modify)

scripts/cross-platform-test.sh                        (modify, Task 10)
docs/handoff/*                                        (every task)
docs/superpowers/plans/2026-10-13-macdroid-notifications-clipboard-followups.md (new, Task 11)
```

---

### Task 1: Protocol — notification messages and a `hello` without `features`

**Files:**
- Create: `protocol/fixtures/notification.posted.json`, `notification.removed.json`, `notification.sync.json`, `notification.icon.json`, `notification.apps.json`, `notification.reply.json`, `notification.action.json`, `notification.dismiss.json`, `notification.mute.json`, `notification.error.json`, `hello.no-features.json`
- Modify: `protocol/PROTOCOL.md`
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Protocol/Messages.swift`, `Protocol/MessageCodec.swift`
- Modify: `android/core/src/main/kotlin/dev/macdroid/core/protocol/Messages.kt`, `protocol/MessageCodec.kt`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/ProtocolFixtureTests.swift`, `android/core/src/test/kotlin/dev/macdroid/core/protocol/MessageCodecTest.kt`
- Handoff: `docs/handoff/CHANGELOG.md` (new entry), `docs/handoff/ARCHITECTURE.md` (message types line)

**Interfaces:**
- Produces (Swift, `MacDroidCore`):
  - `NotificationButton(index:title:)`
  - `NotificationPosted(key:packageName:appName:title:text:postedAt:actions:canReply:iconHash:isUpdate:)`, with `postedAt: Int64`
  - `NotificationKey(key:)`, `NotificationSync(keys:)`, `NotificationIcon(hash:png:)`
  - `PhoneApp(packageName:appName:iconHash:muted:)` (`Identifiable`, `id == packageName`)
  - `NotificationApps(forwarding:access:apps:)`
  - `NotificationReply(key:text:)`, `NotificationActionRequest(key:actionIndex:)`, `NotificationMute(packageName:muted:)`
  - `NotificationErrorReason` (`.gone`, `.failed`) and `NotificationError(key:reason:)`
  - `Message` cases: `.notificationPosted`, `.notificationRemoved(NotificationKey)`, `.notificationSync`, `.notificationIcon`, `.notificationApps`, `.notificationReply`, `.notificationAction(NotificationActionRequest)`, `.notificationDismiss(NotificationKey)`, `.notificationMute`, `.notificationError`
  - `Feature.files`, `Feature.notifications`, `Feature.clipboard` (String constants)
  - `ProtocolInfo.features: [String]` = `["files", "notifications"]`
- Produces (Kotlin, `dev.macdroid.core.protocol`):
  - `NotificationButton(index, title)`
  - `NotificationPostedBody(key, packageName, appName, title, text, postedAt: Long, actions, canReply, iconHash, isUpdate)`
  - `NotificationKeyBody(key)`, `NotificationSyncBody(keys)`, `NotificationIconBody(hash, png)`
  - `PhoneAppBody(packageName, appName, iconHash, muted)`
  - `NotificationAppsBody(forwarding, access, apps)`
  - `NotificationReplyBody(key, text)`, `NotificationActionBody(key, actionIndex)`, `NotificationMuteBody(packageName, muted)`
  - `NotificationErrorReason { Gone, Failed }` and `NotificationErrorBody(key, reason)`
  - `Message` subclasses: `NotificationPosted`, `NotificationRemoved`, `NotificationSync`, `NotificationIcon`, `NotificationApps`, `NotificationReply`, `NotificationAction`, `NotificationDismiss`, `NotificationMute`, `NotificationError`
  - `object Features { FILES, NOTIFICATIONS, CLIPBOARD; SUPPORTED = listOf(FILES, NOTIFICATIONS) }`

- [ ] **Step 1: Create the branch**

```bash
git checkout main && git pull --ff-only 2>/dev/null; git checkout -b feat/notifications-clipboard
```

- [ ] **Step 2: Write the fixtures**

`protocol/fixtures/notification.posted.json`:
```json
{
  "v": 1,
  "type": "notification.posted",
  "id": "6f1c2a0e-0000-4000-8000-000000000010",
  "body": {
    "key": "0|com.whatsapp|1|chat|10123",
    "package": "com.whatsapp",
    "appName": "WhatsApp",
    "title": "Mom",
    "text": "Did you eat?",
    "postedAt": 1791892800000,
    "actions": [{ "index": 1, "title": "Mark as read" }],
    "canReply": true,
    "iconHash": "3f2a9c1d0b7e4f52",
    "isUpdate": false
  }
}
```

`protocol/fixtures/notification.removed.json`:
```json
{ "v": 1, "type": "notification.removed", "id": "6f1c2a0e-0000-4000-8000-000000000011", "body": { "key": "0|com.whatsapp|1|chat|10123" } }
```

`protocol/fixtures/notification.sync.json`:
```json
{ "v": 1, "type": "notification.sync", "id": "6f1c2a0e-0000-4000-8000-000000000012", "body": { "keys": ["0|com.whatsapp|1|chat|10123", "0|com.google.android.gm|7|null|10140"] } }
```

`protocol/fixtures/notification.icon.json`:
```json
{ "v": 1, "type": "notification.icon", "id": "6f1c2a0e-0000-4000-8000-000000000013", "body": { "hash": "3f2a9c1d0b7e4f52", "png": "iVBORw0KGgo=" } }
```

`protocol/fixtures/notification.apps.json`:
```json
{
  "v": 1,
  "type": "notification.apps",
  "id": "6f1c2a0e-0000-4000-8000-000000000014",
  "body": {
    "forwarding": true,
    "access": true,
    "apps": [
      { "package": "com.whatsapp", "appName": "WhatsApp", "iconHash": "3f2a9c1d0b7e4f52", "muted": false },
      { "package": "com.discord", "appName": "Discord", "iconHash": "", "muted": true }
    ]
  }
}
```

`protocol/fixtures/notification.reply.json`:
```json
{ "v": 1, "type": "notification.reply", "id": "6f1c2a0e-0000-4000-8000-000000000015", "body": { "key": "0|com.whatsapp|1|chat|10123", "text": "On my way" } }
```

`protocol/fixtures/notification.action.json`:
```json
{ "v": 1, "type": "notification.action", "id": "6f1c2a0e-0000-4000-8000-000000000016", "body": { "key": "0|com.whatsapp|1|chat|10123", "actionIndex": 1 } }
```

`protocol/fixtures/notification.dismiss.json`:
```json
{ "v": 1, "type": "notification.dismiss", "id": "6f1c2a0e-0000-4000-8000-000000000017", "body": { "key": "0|com.whatsapp|1|chat|10123" } }
```

`protocol/fixtures/notification.mute.json`:
```json
{ "v": 1, "type": "notification.mute", "id": "6f1c2a0e-0000-4000-8000-000000000018", "body": { "package": "com.discord", "muted": true } }
```

`protocol/fixtures/notification.error.json`:
```json
{ "v": 1, "type": "notification.error", "id": "6f1c2a0e-0000-4000-8000-000000000019", "body": { "key": "0|com.whatsapp|1|chat|10123", "reason": "gone" } }
```

`protocol/fixtures/hello.no-features.json` (decode-only: an older app that leaves `features` out):
```json
{ "v": 1, "type": "hello", "id": "6f1c2a0e-0000-4000-8000-000000000020", "body": { "protocol": 1, "app": "0.3.0", "device": "Nothing Phone (3)" } }
```

- [ ] **Step 3: Write the failing Swift tests**

In `ProtocolFixtureTests.swift`, extend `messageFixtures` and add tests:

```swift
    static let messageFixtures = [
        "hello.json", "device.status.json",
        "transfer.offer.json", "transfer.accept.json", "transfer.reject.json", "transfer.finish.json",
        "transfer.result.json", "transfer.result.failed.json", "transfer.cancel.json",
        "notification.posted.json", "notification.removed.json", "notification.sync.json", "notification.icon.json",
        "notification.apps.json", "notification.reply.json", "notification.action.json", "notification.dismiss.json",
        "notification.mute.json", "notification.error.json",
    ]

    @Test func notificationFixturesDecode() throws {
        let key = "0|com.whatsapp|1|chat|10123"
        #expect(try MessageCodec.decode(Fixtures.text("notification.posted.json")) == .notificationPosted(NotificationPosted(
            key: key, packageName: "com.whatsapp", appName: "WhatsApp", title: "Mom", text: "Did you eat?",
            postedAt: 1_791_892_800_000, actions: [NotificationButton(index: 1, title: "Mark as read")],
            canReply: true, iconHash: "3f2a9c1d0b7e4f52", isUpdate: false
        )))
        #expect(try MessageCodec.decode(Fixtures.text("notification.removed.json")) == .notificationRemoved(NotificationKey(key: key)))
        #expect(try MessageCodec.decode(Fixtures.text("notification.sync.json")) == .notificationSync(NotificationSync(keys: [key, "0|com.google.android.gm|7|null|10140"])))
        #expect(try MessageCodec.decode(Fixtures.text("notification.icon.json")) == .notificationIcon(NotificationIcon(hash: "3f2a9c1d0b7e4f52", png: "iVBORw0KGgo=")))
        #expect(try MessageCodec.decode(Fixtures.text("notification.apps.json")) == .notificationApps(NotificationApps(forwarding: true, access: true, apps: [
            PhoneApp(packageName: "com.whatsapp", appName: "WhatsApp", iconHash: "3f2a9c1d0b7e4f52", muted: false),
            PhoneApp(packageName: "com.discord", appName: "Discord", iconHash: "", muted: true),
        ])))
        #expect(try MessageCodec.decode(Fixtures.text("notification.reply.json")) == .notificationReply(NotificationReply(key: key, text: "On my way")))
        #expect(try MessageCodec.decode(Fixtures.text("notification.action.json")) == .notificationAction(NotificationActionRequest(key: key, actionIndex: 1)))
        #expect(try MessageCodec.decode(Fixtures.text("notification.dismiss.json")) == .notificationDismiss(NotificationKey(key: key)))
        #expect(try MessageCodec.decode(Fixtures.text("notification.mute.json")) == .notificationMute(NotificationMute(packageName: "com.discord", muted: true)))
        #expect(try MessageCodec.decode(Fixtures.text("notification.error.json")) == .notificationError(NotificationError(key: key, reason: .gone)))
    }

    @Test func helloWithoutFeaturesDecodesAsEmpty() throws {
        #expect(try MessageCodec.decode(Fixtures.text("hello.no-features.json")) == .hello(Hello(app: "0.3.0", device: "Nothing Phone (3)", features: [])))
    }

    @Test func unknownNotificationErrorReasonIsMalformed() {
        let text = #"{"v":1,"type":"notification.error","id":"x","body":{"key":"k","reason":"bored"}}"#
        #expect(throws: MessageCodecError.malformed) { try MessageCodec.decode(text) }
    }

    @Test func supportedFeaturesAreFilesAndNotifications() {
        #expect(ProtocolInfo.features == [Feature.files, Feature.notifications])
    }
```

- [ ] **Step 4: Run the Swift tests to see them fail**

Run: `cd mac/MacDroidCore && swift test --filter ProtocolFixtureTests`
Expected: compile errors (`NotificationPosted`, `Feature` … not found).

- [ ] **Step 5: Implement the Swift types**

In `Messages.swift`, change `ProtocolInfo` and `Hello`, and add the bodies before `public enum Message`:

```swift
public enum ProtocolInfo {
    public static let version = 1
    public static let serviceType = "_macdroid._tcp"
    /// Features this app implements, sent in `hello` (spec §2.1). Plan 4 adds `Feature.clipboard`.
    public static let features: [String] = [Feature.files, Feature.notifications]
}

/// Names used in `hello.features`. A feature is active only when both sides list it.
public enum Feature {
    public static let files = "files"
    public static let notifications = "notifications"
    public static let clipboard = "clipboard"
}

public struct Hello: Codable, Equatable, Sendable {
    public var `protocol`: Int
    public var app: String
    public var device: String
    public var features: [String]

    public init(protocol protocolVersion: Int = ProtocolInfo.version, app: String, device: String, features: [String]) {
        self.protocol = protocolVersion
        self.app = app
        self.device = device
        self.features = features
    }

    /// Older apps may leave `features` out; that means none (spec §2.1).
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.protocol = try container.decode(Int.self, forKey: .`protocol`)
        self.app = try container.decode(String.self, forKey: .app)
        self.device = try container.decode(String.self, forKey: .device)
        self.features = try container.decodeIfPresent([String].self, forKey: .features) ?? []
    }
}
```

```swift
// MARK: Notifications (spec §2.2)

public struct NotificationButton: Codable, Equatable, Sendable {
    /// Index in the Android notification's `actions`.
    public var index: Int
    public var title: String

    public init(index: Int, title: String) {
        self.index = index
        self.title = title
    }
}

public struct NotificationPosted: Codable, Equatable, Sendable {
    /// Android's `StatusBarNotification.key`; opaque to the Mac.
    public var key: String
    public var packageName: String
    public var appName: String
    public var title: String
    public var text: String
    /// Epoch milliseconds on the phone.
    public var postedAt: Int64
    public var actions: [NotificationButton]
    public var canReply: Bool
    /// 16 lowercase hex characters, or empty when the phone had no icon.
    public var iconHash: String
    public var isUpdate: Bool

    enum CodingKeys: String, CodingKey {
        case key, packageName = "package", appName, title, text, postedAt, actions, canReply, iconHash, isUpdate
    }

    public init(
        key: String, packageName: String, appName: String, title: String, text: String, postedAt: Int64,
        actions: [NotificationButton], canReply: Bool, iconHash: String, isUpdate: Bool
    ) {
        self.key = key
        self.packageName = packageName
        self.appName = appName
        self.title = title
        self.text = text
        self.postedAt = postedAt
        self.actions = actions
        self.canReply = canReply
        self.iconHash = iconHash
        self.isUpdate = isUpdate
    }
}

/// Body of `notification.removed` and `notification.dismiss`.
public struct NotificationKey: Codable, Equatable, Sendable {
    public var key: String

    public init(key: String) { self.key = key }
}

public struct NotificationSync: Codable, Equatable, Sendable {
    /// Every forwarded notification still showing on the phone.
    public var keys: [String]

    public init(keys: [String]) { self.keys = keys }
}

public struct NotificationIcon: Codable, Equatable, Sendable {
    public var hash: String
    /// Base64 PNG, at most 96×96.
    public var png: String

    public init(hash: String, png: String) {
        self.hash = hash
        self.png = png
    }
}

public struct PhoneApp: Codable, Equatable, Sendable, Identifiable {
    public var packageName: String
    public var appName: String
    public var iconHash: String
    public var muted: Bool

    public var id: String { packageName }

    enum CodingKeys: String, CodingKey {
        case packageName = "package", appName, iconHash, muted
    }

    public init(packageName: String, appName: String, iconHash: String, muted: Bool) {
        self.packageName = packageName
        self.appName = appName
        self.iconHash = iconHash
        self.muted = muted
    }
}

public struct NotificationApps: Codable, Equatable, Sendable {
    /// The phone's "Show on your Mac" switch.
    public var forwarding: Bool
    /// The phone has notification access.
    public var access: Bool
    public var apps: [PhoneApp]

    public init(forwarding: Bool, access: Bool, apps: [PhoneApp]) {
        self.forwarding = forwarding
        self.access = access
        self.apps = apps
    }
}

public struct NotificationReply: Codable, Equatable, Sendable {
    public var key: String
    public var text: String

    public init(key: String, text: String) {
        self.key = key
        self.text = text
    }
}

/// Body of `notification.action`.
public struct NotificationActionRequest: Codable, Equatable, Sendable {
    public var key: String
    public var actionIndex: Int

    public init(key: String, actionIndex: Int) {
        self.key = key
        self.actionIndex = actionIndex
    }
}

public struct NotificationMute: Codable, Equatable, Sendable {
    public var packageName: String
    public var muted: Bool

    enum CodingKeys: String, CodingKey {
        case packageName = "package", muted
    }

    public init(packageName: String, muted: Bool) {
        self.packageName = packageName
        self.muted = muted
    }
}

public enum NotificationErrorReason: String, Codable, Sendable {
    case gone
    case failed
}

public struct NotificationError: Codable, Equatable, Sendable {
    public var key: String
    public var reason: NotificationErrorReason

    public init(key: String, reason: NotificationErrorReason) {
        self.key = key
        self.reason = reason
    }
}
```

Add the cases to `Message` (after `transferCancel`), and update its doc comment to "A control-channel message.":

```swift
    case notificationPosted(NotificationPosted)
    case notificationRemoved(NotificationKey)
    case notificationSync(NotificationSync)
    case notificationIcon(NotificationIcon)
    case notificationApps(NotificationApps)
    case notificationReply(NotificationReply)
    case notificationAction(NotificationActionRequest)
    case notificationDismiss(NotificationKey)
    case notificationMute(NotificationMute)
    case notificationError(NotificationError)
```

In `MessageCodec.swift`, add to `encode` (before `.unknown`):

```swift
        case .notificationPosted(let body): return try encode(type: "notification.posted", id: id, body: body)
        case .notificationRemoved(let body): return try encode(type: "notification.removed", id: id, body: body)
        case .notificationSync(let body): return try encode(type: "notification.sync", id: id, body: body)
        case .notificationIcon(let body): return try encode(type: "notification.icon", id: id, body: body)
        case .notificationApps(let body): return try encode(type: "notification.apps", id: id, body: body)
        case .notificationReply(let body): return try encode(type: "notification.reply", id: id, body: body)
        case .notificationAction(let body): return try encode(type: "notification.action", id: id, body: body)
        case .notificationDismiss(let body): return try encode(type: "notification.dismiss", id: id, body: body)
        case .notificationMute(let body): return try encode(type: "notification.mute", id: id, body: body)
        case .notificationError(let body): return try encode(type: "notification.error", id: id, body: body)
```

and to `decode` (before `case let other`):

```swift
            case "notification.posted": return .notificationPosted(try decoder.decode(Incoming<NotificationPosted>.self, from: data).body)
            case "notification.removed": return .notificationRemoved(try decoder.decode(Incoming<NotificationKey>.self, from: data).body)
            case "notification.sync": return .notificationSync(try decoder.decode(Incoming<NotificationSync>.self, from: data).body)
            case "notification.icon": return .notificationIcon(try decoder.decode(Incoming<NotificationIcon>.self, from: data).body)
            case "notification.apps": return .notificationApps(try decoder.decode(Incoming<NotificationApps>.self, from: data).body)
            case "notification.reply": return .notificationReply(try decoder.decode(Incoming<NotificationReply>.self, from: data).body)
            case "notification.action": return .notificationAction(try decoder.decode(Incoming<NotificationActionRequest>.self, from: data).body)
            case "notification.dismiss": return .notificationDismiss(try decoder.decode(Incoming<NotificationKey>.self, from: data).body)
            case "notification.mute": return .notificationMute(try decoder.decode(Incoming<NotificationMute>.self, from: data).body)
            case "notification.error": return .notificationError(try decoder.decode(Incoming<NotificationError>.self, from: data).body)
```

`ConnectionHub.handle` has an exhaustive `switch`. Make it compile now: add the new cases to the `.transferOffer, …` forwarding case. Task 2 replaces that case with `default:`.

```swift
        case .transferOffer, .transferAccept, .transferReject, .transferFinish, .transferResult, .transferCancel,
             .notificationPosted, .notificationRemoved, .notificationSync, .notificationIcon, .notificationApps,
             .notificationReply, .notificationAction, .notificationDismiss, .notificationMute, .notificationError:
            messageBroadcast.yield(message)
```

- [ ] **Step 6: Run the Swift tests**

Run: `cd mac/MacDroidCore && swift test`
Expected: PASS (all suites, no warnings).

- [ ] **Step 7: Write the failing Kotlin tests**

In `MessageCodecTest.kt`, extend `messageFixtures` with the 10 `notification.*.json` names (same order as Swift), and add:

```kotlin
    @Test fun notificationFixturesDecode() {
        val key = "0|com.whatsapp|1|chat|10123"
        assertEquals(
            Message.NotificationPosted(NotificationPostedBody(key, "com.whatsapp", "WhatsApp", "Mom", "Did you eat?", 1_791_892_800_000,
                listOf(NotificationButton(1, "Mark as read")), canReply = true, iconHash = "3f2a9c1d0b7e4f52", isUpdate = false)),
            MessageCodec.decode(Fixtures.text("notification.posted.json")),
        )
        assertEquals(Message.NotificationRemoved(NotificationKeyBody(key)), MessageCodec.decode(Fixtures.text("notification.removed.json")))
        assertEquals(Message.NotificationSync(NotificationSyncBody(listOf(key, "0|com.google.android.gm|7|null|10140"))), MessageCodec.decode(Fixtures.text("notification.sync.json")))
        assertEquals(Message.NotificationIcon(NotificationIconBody("3f2a9c1d0b7e4f52", "iVBORw0KGgo=")), MessageCodec.decode(Fixtures.text("notification.icon.json")))
        assertEquals(
            Message.NotificationApps(NotificationAppsBody(forwarding = true, access = true, apps = listOf(
                PhoneAppBody("com.whatsapp", "WhatsApp", "3f2a9c1d0b7e4f52", muted = false),
                PhoneAppBody("com.discord", "Discord", "", muted = true),
            ))),
            MessageCodec.decode(Fixtures.text("notification.apps.json")),
        )
        assertEquals(Message.NotificationReply(NotificationReplyBody(key, "On my way")), MessageCodec.decode(Fixtures.text("notification.reply.json")))
        assertEquals(Message.NotificationAction(NotificationActionBody(key, 1)), MessageCodec.decode(Fixtures.text("notification.action.json")))
        assertEquals(Message.NotificationDismiss(NotificationKeyBody(key)), MessageCodec.decode(Fixtures.text("notification.dismiss.json")))
        assertEquals(Message.NotificationMute(NotificationMuteBody("com.discord", muted = true)), MessageCodec.decode(Fixtures.text("notification.mute.json")))
        assertEquals(Message.NotificationError(NotificationErrorBody(key, NotificationErrorReason.Gone)), MessageCodec.decode(Fixtures.text("notification.error.json")))
    }

    @Test fun helloWithoutFeaturesDecodesAsEmpty() {
        assertEquals(
            Message.Hello(HelloBody(protocol = 1, app = "0.3.0", device = "Nothing Phone (3)", features = emptyList())),
            MessageCodec.decode(Fixtures.text("hello.no-features.json")),
        )
    }

    @Test fun unknownNotificationErrorReasonIsMalformed() {
        assertThrows(MalformedMessageException::class.java) {
            MessageCodec.decode("""{"v":1,"type":"notification.error","id":"x","body":{"key":"k","reason":"bored"}}""")
        }
    }

    @Test fun supportedFeaturesAreFilesAndNotifications() {
        assertEquals(listOf("files", "notifications"), Features.SUPPORTED)
    }
```

- [ ] **Step 8: Run the Kotlin tests to see them fail**

Run: `cd android && ./gradlew :core:test --tests 'dev.macdroid.core.protocol.MessageCodecTest'`
Expected: compilation fails (unresolved `NotificationPostedBody`, `Features`, …).

- [ ] **Step 9: Implement the Kotlin types**

Append to `Messages.kt` (before `sealed interface Message`):

```kotlin
/** Names used in `hello.features`; a feature is active only when both sides list it (spec §2.1). */
object Features {
    const val FILES = "files"
    const val NOTIFICATIONS = "notifications"
    const val CLIPBOARD = "clipboard"
    /** What this app implements. Plan 4 adds [CLIPBOARD]. */
    val SUPPORTED: List<String> = listOf(FILES, NOTIFICATIONS)
}

@Serializable
data class NotificationButton(val index: Int, val title: String)

@Serializable
data class NotificationPostedBody(
    val key: String,
    @SerialName("package") val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val actions: List<NotificationButton>,
    val canReply: Boolean,
    val iconHash: String,
    val isUpdate: Boolean,
)

/** Body of `notification.removed` and `notification.dismiss`. */
@Serializable
data class NotificationKeyBody(val key: String)

@Serializable
data class NotificationSyncBody(val keys: List<String>)

@Serializable
data class NotificationIconBody(val hash: String, val png: String)

@Serializable
data class PhoneAppBody(@SerialName("package") val packageName: String, val appName: String, val iconHash: String, val muted: Boolean)

@Serializable
data class NotificationAppsBody(val forwarding: Boolean, val access: Boolean, val apps: List<PhoneAppBody>)

@Serializable
data class NotificationReplyBody(val key: String, val text: String)

@Serializable
data class NotificationActionBody(val key: String, val actionIndex: Int)

@Serializable
data class NotificationMuteBody(@SerialName("package") val packageName: String, val muted: Boolean)

@Serializable
enum class NotificationErrorReason {
    @SerialName("gone") Gone,
    @SerialName("failed") Failed,
}

@Serializable
data class NotificationErrorBody(val key: String, val reason: NotificationErrorReason)
```

Add to `sealed interface Message` (after `TransferCancel`), and change its KDoc to "A control-channel message.":

```kotlin
    data class NotificationPosted(val body: NotificationPostedBody) : Message
    data class NotificationRemoved(val body: NotificationKeyBody) : Message
    data class NotificationSync(val body: NotificationSyncBody) : Message
    data class NotificationIcon(val body: NotificationIconBody) : Message
    data class NotificationApps(val body: NotificationAppsBody) : Message
    data class NotificationReply(val body: NotificationReplyBody) : Message
    data class NotificationAction(val body: NotificationActionBody) : Message
    data class NotificationDismiss(val body: NotificationKeyBody) : Message
    data class NotificationMute(val body: NotificationMuteBody) : Message
    data class NotificationError(val body: NotificationErrorBody) : Message
```

In `MessageCodec.encode`, add before `is Message.Unknown`:

```kotlin
            is Message.NotificationPosted -> "notification.posted" to json.encodeToJsonElement(NotificationPostedBody.serializer(), message.body)
            is Message.NotificationRemoved -> "notification.removed" to json.encodeToJsonElement(NotificationKeyBody.serializer(), message.body)
            is Message.NotificationSync -> "notification.sync" to json.encodeToJsonElement(NotificationSyncBody.serializer(), message.body)
            is Message.NotificationIcon -> "notification.icon" to json.encodeToJsonElement(NotificationIconBody.serializer(), message.body)
            is Message.NotificationApps -> "notification.apps" to json.encodeToJsonElement(NotificationAppsBody.serializer(), message.body)
            is Message.NotificationReply -> "notification.reply" to json.encodeToJsonElement(NotificationReplyBody.serializer(), message.body)
            is Message.NotificationAction -> "notification.action" to json.encodeToJsonElement(NotificationActionBody.serializer(), message.body)
            is Message.NotificationDismiss -> "notification.dismiss" to json.encodeToJsonElement(NotificationKeyBody.serializer(), message.body)
            is Message.NotificationMute -> "notification.mute" to json.encodeToJsonElement(NotificationMuteBody.serializer(), message.body)
            is Message.NotificationError -> "notification.error" to json.encodeToJsonElement(NotificationErrorBody.serializer(), message.body)
```

and in `decode` before `else ->`:

```kotlin
                "notification.posted" -> Message.NotificationPosted(json.decodeFromJsonElement(NotificationPostedBody.serializer(), body))
                "notification.removed" -> Message.NotificationRemoved(json.decodeFromJsonElement(NotificationKeyBody.serializer(), body))
                "notification.sync" -> Message.NotificationSync(json.decodeFromJsonElement(NotificationSyncBody.serializer(), body))
                "notification.icon" -> Message.NotificationIcon(json.decodeFromJsonElement(NotificationIconBody.serializer(), body))
                "notification.apps" -> Message.NotificationApps(json.decodeFromJsonElement(NotificationAppsBody.serializer(), body))
                "notification.reply" -> Message.NotificationReply(json.decodeFromJsonElement(NotificationReplyBody.serializer(), body))
                "notification.action" -> Message.NotificationAction(json.decodeFromJsonElement(NotificationActionBody.serializer(), body))
                "notification.dismiss" -> Message.NotificationDismiss(json.decodeFromJsonElement(NotificationKeyBody.serializer(), body))
                "notification.mute" -> Message.NotificationMute(json.decodeFromJsonElement(NotificationMuteBody.serializer(), body))
                "notification.error" -> Message.NotificationError(json.decodeFromJsonElement(NotificationErrorBody.serializer(), body))
```

- [ ] **Step 10: Run the Kotlin tests**

Run: `cd android && ./gradlew :core:test`
Expected: PASS.

- [ ] **Step 11: Update `protocol/PROTOCOL.md`**

- In the control-channel table, change the `hello` row's body to `` `protocol`, `app`, `device`, `features[]` (may be missing → none) ``.
- Add these rows after `transfer.cancel`:

```markdown
| `notification.posted` | phone → Mac | `key`, `package`, `appName`, `title` (≤ 200), `text` (≤ 2000), `postedAt` (epoch ms), `actions` [`{index, title}`] (≤ 3), `canReply`, `iconHash` (16 lowercase hex or empty), `isUpdate` |
| `notification.removed` | phone → Mac | `key` |
| `notification.sync` | phone → Mac | `keys` — forwarded notifications still showing on the phone; sent after every `hello` |
| `notification.icon` | phone → Mac | `hash`, `png` (base64, ≤ 96×96) — once per hash per connection, before it's referenced |
| `notification.apps` | phone → Mac | `forwarding`, `access`, `apps` [`{package, appName, iconHash, muted}`] |
| `notification.reply` | Mac → phone | `key`, `text` |
| `notification.action` | Mac → phone | `key`, `actionIndex` (index in the Android notification's actions) |
| `notification.dismiss` | Mac → phone | `key` |
| `notification.mute` | Mac → phone | `package`, `muted` |
| `notification.error` | phone → Mac | `key`, `reason` (`gone` \| `failed`) |
```

- Then add this section after "Transfer rules":

```markdown
### Features
`hello.features` lists what each side implements: `files`, `notifications`, and `clipboard` once Plan 4 lands. A feature is used only when both sides list it. A missing `features` means none.

### Notification rules
- The phone owns the mute list. `notification.mute` is a request; the phone answers with a fresh `notification.apps`.
- The phone sends `notification.apps` and then `notification.sync` after every `hello`, and `notification.apps` again whenever mutes, the "Show on your Mac" switch or notification access change.
- A repost with an unchanged title, text, buttons and reply ability isn't sent again. A changed one is sent with `isUpdate: true`.
- Nothing is queued while disconnected, and nothing is replayed on reconnect.
- `iconHash` is exactly 16 lowercase hex characters. The Mac ignores any other value, because it becomes a file name.
```

- [ ] **Step 12: Handoff and commit**

At the top of `docs/handoff/CHANGELOG.md` (under the format line), add:

```markdown
## <today's date> — Plan 3: notifications (in progress on feat/notifications-clipboard)
- **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-notifications-3.md` (spec: `docs/superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md`).
- Task 1: `notification.*` messages (10 fixtures) in both codecs; `hello` without `features` decodes as none; `ProtocolInfo.features` / `Features.SUPPORTED` = files, notifications. Verified: `swift test`, `./gradlew :core:test`.
```

In `docs/handoff/ARCHITECTURE.md`, extend the "Current types" line with the ten `notification.*` types (Plan 3).

```bash
git add protocol mac/MacDroidCore android/core docs/handoff
git commit -m "feat(protocol): notification messages and tolerant hello features

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Feature exchange and forwarding feature messages

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Session/ConnectionHub.swift`
- Modify: `android/core/src/main/kotlin/dev/macdroid/core/connection/ConnectionManager.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/MacDroidApp.kt` (hello features)
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/ConnectionHubTests.swift`, `android/core/src/test/kotlin/dev/macdroid/core/connection/ConnectionManagerTest.kt`, `android/core/src/test/kotlin/dev/macdroid/core/TestSupport.kt`, `android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt`
- Handoff: CHANGELOG bullet; ARCHITECTURE (hub forwards every feature message; `Connected.features`)

**Interfaces:**
- Consumes: Task 1's `ProtocolInfo.features`, `Feature`, `Features.SUPPORTED`.
- Produces:
  - Swift: `ConnectionHub(macName:appVersion:features:)`, whose `features` defaults to `ProtocolInfo.features`.
  - Swift: `hub.messages` yields every message except `hello`, `device.status` and unknown types.
  - Swift: `PeerInfo.supports(_ feature: String) -> Bool`.
  - Kotlin: `ConnectionState.Connected(macName: String, features: Set<String> = emptySet())`.
  - Kotlin: `fun ConnectionState.macSupports(feature: String): Boolean`.
  - Kotlin test helper: `MockMac(macName, trustsPhone, features: List<String> = emptyList())`.

- [ ] **Step 1: Write the failing Swift tests**

In `ConnectionHubTests.swift`:
- Change the two hello expectations in `sendsHelloFirst` and `helloIsFirstFrameEvenWithConcurrentSend` to `features: ProtocolInfo.features`.
- Add these tests:

```swift
    @Test func notificationMessagesReachTheMessagesStream() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let received = Collector<Message>()
        let stream = hub.messages
        let listener = Task { for await message in stream { await received.append(message) } }
        let transport = FakeTransport()
        let task = start(hub, transport)
        let posted = Message.notificationPosted(NotificationPosted(
            key: "k1", packageName: "com.whatsapp", appName: "WhatsApp", title: "Mom", text: "Hi", postedAt: 1,
            actions: [], canReply: true, iconHash: "", isUpdate: false
        ))
        let apps = Message.notificationApps(NotificationApps(forwarding: true, access: true, apps: []))
        try transport.receive(phoneHello)
        try transport.receive(posted)
        try transport.receive(apps)
        try await eventually { await received.items == [posted, apps] }
        transport.hangUp(); await task.value
        listener.cancel()
    }

    @Test func peerInfoSupportsListedFeaturesOnly() {
        let peer = PeerInfo(device: "Phone", app: "0.4.0", features: [Feature.files, Feature.notifications])
        #expect(peer.supports(Feature.notifications))
        #expect(!peer.supports(Feature.clipboard))
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd mac/MacDroidCore && swift test --filter ConnectionHubTests`
Expected: FAIL. `supports` doesn't exist, and the hello still carries `[]`.

- [ ] **Step 3: Implement (Swift)**

In `ConnectionHub.swift`:

```swift
public struct PeerInfo: Equatable, Sendable {
    // … existing properties and init unchanged …

    /// The phone listed `feature` in its hello (spec §2.1).
    public func supports(_ feature: String) -> Bool { features.contains(feature) }
}
```

Change the init's default, and replace the forwarding case in `handle(_:session:)`:

```swift
    public init(macName: String, appVersion: String, features: [String] = ProtocolInfo.features) {
        self.hello = Hello(app: appVersion, device: macName, features: features)
    }
```

```swift
        case .unknown(let type):
            logger.debug("ignoring message type \(type)")
        default:
            // Feature messages (transfers, notifications, clipboard) go to their managers.
            messageBroadcast.yield(message)
```

(Delete the explicit `.transferOffer, …, .notificationError:` case from Task 1.) Update the `messages` doc comment to "Control messages for feature managers (transfers, notifications, clipboard), in arrival order."

- [ ] **Step 4: Run the Swift tests**

Run: `cd mac/MacDroidCore && swift test`
Expected: PASS.

- [ ] **Step 5: Write the failing Kotlin test**

In `TestSupport.kt`, give `MockMac` a features parameter and send it in its hello:

```kotlin
class MockMac(val macName: String = "Test Mac", trustsPhone: Boolean = true, private val features: List<String> = emptyList()) : java.io.Closeable {
```

```kotlin
                                dev.macdroid.core.protocol.Message.Hello(
                                    dev.macdroid.core.protocol.HelloBody(app = "0.1.0", device = macName, features = features),
                                ),
```

In `ConnectionManagerTest.kt` add:

```kotlin
    @Test fun connectedStateCarriesTheMacsFeatures(): Unit = runBlocking {
        MockMac(features = listOf("files", "notifications")).use { mac ->
            mac.enqueueSession()
            mac.start()
            val manager = manager(mac, InMemoryPairingStore(mac.pairedMac()))
            manager.start()
            try {
                val state = manager.awaitState { it is ConnectionState.Connected }
                assertEquals(ConnectionState.Connected("Test Mac", setOf("files", "notifications")), state)
                assertTrue(state.macSupports("notifications"))
                assertFalse(state.macSupports("clipboard"))
            } finally {
                manager.stop()
            }
        }
    }
```

(Import `org.junit.Assert.assertTrue` and `assertFalse` if they're missing.)

- [ ] **Step 6: Run it to see it fail**

Run: `cd android && ./gradlew :core:test --tests 'dev.macdroid.core.connection.ConnectionManagerTest'`
Expected: compilation fails (`Connected` has no `features`; `macSupports` unresolved).

- [ ] **Step 7: Implement (Kotlin)**

In `ConnectionManager.kt`:

```kotlin
    /** [features] are what the Mac listed in its hello (spec §2.1). */
    data class Connected(val macName: String, val features: Set<String> = emptySet()) : ConnectionState
```

Add after the `ConnectionState` interface:

```kotlin
/** Connected to a Mac that listed [feature] in its hello. */
fun ConnectionState.macSupports(feature: String): Boolean = this is ConnectionState.Connected && feature in features
```

In `handleText`:

```kotlin
            setState(gen, ConnectionState.Connected(message.body.device, message.body.features.toSet()))
```

In `MacDroidApp.kt` (`AppGraph`), send the features:

```kotlin
        hello = { HelloBody(app = BuildConfig.VERSION_NAME, device = DeviceInfo.name(context), features = Features.SUPPORTED) },
```

(Import `dev.macdroid.core.protocol.Features`.)

In `CrossPlatformTest.kt`, the Swift dev server now sends features, so replace the exact `Connected` equality:

```kotlin
            val connected = withTimeout(10_000) { manager.state.first { it is ConnectionState.Connected } } as ConnectionState.Connected
            assertEquals("MacDroid Dev Server", connected.macName)
            assertTrue(connected.macSupports(Features.NOTIFICATIONS))
```

(Import `dev.macdroid.core.connection.macSupports` and `dev.macdroid.core.protocol.Features`.)

- [ ] **Step 8: Run all gates**

Run:
- `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
- `cd mac/MacDroidCore && swift test`

Expected: PASS, with no warnings.

- [ ] **Step 9: Handoff and commit**

- CHANGELOG bullet: "Task 2: both sides announce `files`, `notifications`; the Mac hub forwards every feature message; Android `Connected` carries the Mac's features."
- ARCHITECTURE: note `ConnectionState.Connected(macName, features)` and that `ConnectionHub.messages` carries every feature message.

```bash
git add mac/MacDroidCore android docs/handoff
git commit -m "feat: exchange hello features; hub forwards feature messages

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 3: Android core — `NotificationForwarder` (filter, de-dupe, mutes, apps, sync, icons)

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/notifications/NotificationModel.kt`
- Create: `android/core/src/main/kotlin/dev/macdroid/core/notifications/NotificationRules.kt`
- Create: `android/core/src/main/kotlin/dev/macdroid/core/notifications/NotificationForwarder.kt`
- Test: `android/core/src/test/kotlin/dev/macdroid/core/notifications/NotificationForwarderTest.kt`
- Handoff: CHANGELOG bullet; ARCHITECTURE (Android core table: `notifications` row)

**Interfaces:**
- Consumes: Task 1's protocol bodies (`NotificationPostedBody`, `NotificationButton`, `PhoneAppBody`, …).
- Produces (package `dev.macdroid.core.notifications`):
  - `data class PhoneNotification(key, packageName, appName, title, text, postedAt: Long, actions: List<NotificationButton>, canReply: Boolean, ongoing: Boolean = false, groupSummary: Boolean = false, minImportance: Boolean = false)`
  - `data class AppIcon(hash: String, pngBase64: String)`
  - `@Serializable data class SeenApp(packageName, appName, iconHash = "", muted = false, countDay: Long = 0, count: Int = 0)`
  - `@Serializable data class NotificationSettings(forwarding: Boolean = true, apps: List<SeenApp> = emptyList())`
  - `interface NotificationSettingsStore { fun load(): NotificationSettings; fun save(settings: NotificationSettings) }` and `class InMemoryNotificationSettingsStore`
  - `sealed interface NotificationCommand { key }`: `Reply(key, text)`, `Action(key, actionIndex)`, `Dismiss(key)`
  - `data class NotificationsState(forwarding: Boolean, access: Boolean, apps: List<SeenApp>)`
  - `object NotificationRules { TITLE_LIMIT = 200; TEXT_LIMIT = 2_000; BUTTON_LIMIT = 3; fun excluded(n, ownPackage): Boolean; fun clip(value, limit): String }`
  - `class NotificationForwarder(ownPackage, store, send: (Message) -> Boolean, iconFor: (String) -> AppIcon?, clock: () -> Long = System::currentTimeMillis, zone: ZoneId = ZoneId.systemDefault())`, with:
    - `val state: StateFlow<NotificationsState>`
    - `connected(activeKeys: Set<String>)`, `disconnected()`, `accessChanged(granted: Boolean)`
    - `posted(notification: PhoneNotification)`, `removed(key: String)`
    - `setForwarding(on: Boolean)`, `setMuted(packageName: String, muted: Boolean)`
    - `handle(message: Message): NotificationCommand?`
    - `reportError(key: String, reason: NotificationErrorReason)`

- [ ] **Step 1: Write the failing tests**

`NotificationForwarderTest.kt`:

```kotlin
package dev.macdroid.core.notifications

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.NotificationActionBody
import dev.macdroid.core.protocol.NotificationButton
import dev.macdroid.core.protocol.NotificationErrorBody
import dev.macdroid.core.protocol.NotificationErrorReason
import dev.macdroid.core.protocol.NotificationKeyBody
import dev.macdroid.core.protocol.NotificationMuteBody
import dev.macdroid.core.protocol.NotificationReplyBody
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationForwarderTest {
    private val sent = mutableListOf<Message>()
    private var now = 1_791_892_800_000L // 2026-10-13T00:00Z
    private val store = InMemoryNotificationSettingsStore()
    private val icons = mapOf("com.whatsapp" to AppIcon("3f2a9c1d0b7e4f52", "iVBORw0KGgo="))

    private fun forwarder(s: NotificationSettingsStore = store) =
        NotificationForwarder("dev.macdroid.app", s, { sent.add(it); true }, { icons[it] }, clock = { now }, zone = ZoneOffset.UTC)

    private fun chat(key: String = "k1", text: String = "Did you eat?", packageName: String = "com.whatsapp", appName: String = "WhatsApp") =
        PhoneNotification(key, packageName, appName, "Mom", text, now, listOf(NotificationButton(1, "Mark as read")), canReply = true)

    private inline fun <reified T : Message> sentOf(): List<T> = sent.filterIsInstance<T>()

    @Test fun forwardsWhileConnectedWithTheIconFirst() {
        val f = forwarder()
        f.connected(emptySet())
        sent.clear()
        f.posted(chat())
        val posted = sentOf<Message.NotificationPosted>().single().body
        assertEquals("k1", posted.key)
        assertEquals("3f2a9c1d0b7e4f52", posted.iconHash)
        assertFalse(posted.isUpdate)
        assertTrue(sent.indexOfFirst { it is Message.NotificationIcon } < sent.indexOfFirst { it is Message.NotificationPosted })
    }

    @Test fun nothingIsSentWhileDisconnectedButTheAppIsRemembered() {
        val f = forwarder()
        f.posted(chat())
        assertTrue(sent.isEmpty())
        assertEquals(listOf("com.whatsapp"), f.state.value.apps.map { it.packageName })
    }

    @Test fun excludedNotificationsAreNeverSent() {
        val f = forwarder()
        f.connected(emptySet())
        sent.clear()
        f.posted(chat(packageName = "dev.macdroid.app"))
        f.posted(chat(key = "a").copy(ongoing = true))
        f.posted(chat(key = "b").copy(groupSummary = true))
        f.posted(chat(key = "c").copy(minImportance = true))
        f.posted(chat(key = "d").copy(title = " ", text = ""))
        assertTrue(sentOf<Message.NotificationPosted>().isEmpty())
    }

    @Test fun mutedAppsAndTheMasterSwitchStopForwarding() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat(key = "k1"))
        f.setMuted("com.whatsapp", true)
        sent.clear()
        f.posted(chat(key = "k2"))
        assertTrue(sentOf<Message.NotificationPosted>().isEmpty())
        f.setMuted("com.whatsapp", false)
        f.setForwarding(false)
        f.posted(chat(key = "k3"))
        assertTrue(sentOf<Message.NotificationPosted>().isEmpty())
    }

    @Test fun iconIsSentOncePerConnection() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat(key = "k1"))
        f.posted(chat(key = "k2"))
        assertEquals(1, sentOf<Message.NotificationIcon>().size)
        f.disconnected()
        f.connected(setOf("k1", "k2"))
        f.posted(chat(key = "k3"))
        assertEquals(2, sentOf<Message.NotificationIcon>().size)
    }

    @Test fun identicalRepostIsNotSentAgain() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat())
        f.posted(chat())
        assertEquals(1, sentOf<Message.NotificationPosted>().size)
    }

    @Test fun changedRepostIsSentAsAnUpdate() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat())
        f.posted(chat(text = "Did you eat? Call me"))
        val bodies = sentOf<Message.NotificationPosted>().map { it.body }
        assertEquals(listOf(false, true), bodies.map { it.isUpdate })
    }

    @Test fun removingAForwardedNotificationTellsTheMac() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat())
        f.removed("k1")
        f.removed("never-sent")
        assertEquals(listOf(Message.NotificationRemoved(NotificationKeyBody("k1"))), sentOf<Message.NotificationRemoved>())
    }

    @Test fun connectingSendsAppsThenSyncOfStillActiveKeys() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat(key = "k1"))
        f.posted(chat(key = "k2"))
        f.disconnected()
        sent.clear()
        f.connected(setOf("k2", "unrelated"))
        assertTrue(sent.first { it !is Message.NotificationIcon } is Message.NotificationApps)
        assertEquals(listOf("k2"), sentOf<Message.NotificationSync>().single().body.keys)
    }

    @Test fun afterARestartSyncIsEmpty() {
        forwarder().apply { connected(emptySet()); posted(chat()) }
        sent.clear()
        val restarted = forwarder() // same store, fresh process memory
        restarted.connected(setOf("k1"))
        assertEquals(emptyList<String>(), sentOf<Message.NotificationSync>().single().body.keys)
    }

    @Test fun muteFromTheMacIsAppliedAndConfirmed() {
        val f = forwarder()
        f.connected(emptySet())
        f.posted(chat())
        sent.clear()
        assertNull(f.handle(Message.NotificationMute(NotificationMuteBody("com.whatsapp", true))))
        assertTrue(f.state.value.apps.single().muted)
        assertTrue(sentOf<Message.NotificationApps>().single().body.apps.single().muted)
    }

    @Test fun replyActionAndDismissBecomeCommands() {
        val f = forwarder()
        assertEquals(NotificationCommand.Reply("k1", "On my way"), f.handle(Message.NotificationReply(NotificationReplyBody("k1", "On my way"))))
        assertEquals(NotificationCommand.Action("k1", 1), f.handle(Message.NotificationAction(NotificationActionBody("k1", 1))))
        assertEquals(NotificationCommand.Dismiss("k1"), f.handle(Message.NotificationDismiss(NotificationKeyBody("k1"))))
    }

    @Test fun errorsAreReportedToTheMac() {
        forwarder().reportError("k1", NotificationErrorReason.Gone)
        assertEquals(listOf(Message.NotificationError(NotificationErrorBody("k1", NotificationErrorReason.Gone))), sent)
    }

    @Test fun titleAndTextAreClippedAndButtonsLimitedToThree() {
        val f = forwarder()
        f.connected(emptySet())
        val buttons = (0..4).map { NotificationButton(it, "B$it") }
        f.posted(chat().copy(title = "t".repeat(250), text = "x".repeat(2_500), actions = buttons))
        val body = sentOf<Message.NotificationPosted>().single().body
        assertEquals(200, body.title.length)
        assertTrue(body.title.endsWith("…"))
        assertEquals(2_000, body.text.length)
        assertEquals(listOf(0, 1, 2), body.actions.map { it.index })
    }

    @Test fun clipNeverSplitsASurrogatePair() {
        val value = "a".repeat(198) + "😀" + "tail" // the emoji occupies chars 198–199
        val clipped = NotificationRules.clip(value, 200)
        assertTrue(clipped.endsWith("…"))
        assertFalse(clipped.dropLast(1).last().isHighSurrogate())
    }

    @Test fun countsDistinctNotificationsPerDay() {
        val f = forwarder()
        f.posted(chat(key = "k1"))
        f.posted(chat(key = "k1", text = "again")) // same notification, updated
        f.posted(chat(key = "k2"))
        assertEquals(2, f.state.value.apps.single().count)
        now += 24 * 60 * 60 * 1000L
        f.posted(chat(key = "k3"))
        assertEquals(1, f.state.value.apps.single().count)
    }

    @Test fun settingsSurviveARestart() {
        forwarder().apply { posted(chat()); setMuted("com.whatsapp", true); setForwarding(false) }
        val state = forwarder().state.value
        assertFalse(state.forwarding)
        assertTrue(state.apps.single().muted)
    }

    @Test fun accessChangesAreSentToTheMac() {
        val f = forwarder()
        f.connected(emptySet())
        sent.clear()
        f.accessChanged(true)
        assertTrue(sentOf<Message.NotificationApps>().single().body.access)
        assertTrue(f.state.value.access)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd android && ./gradlew :core:test --tests 'dev.macdroid.core.notifications.*'`
Expected: compilation fails (package doesn't exist).

- [ ] **Step 3: Implement the model and rules**

`NotificationModel.kt`:

```kotlin
package dev.macdroid.core.notifications

import dev.macdroid.core.protocol.NotificationButton
import kotlinx.serialization.Serializable

/** A phone notification, already read out of Android's StatusBarNotification by the app. */
data class PhoneNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** The buttons, without the reply button; [NotificationButton.index] is the index in `Notification.actions`. */
    val actions: List<NotificationButton>,
    val canReply: Boolean,
    /** `FLAG_ONGOING_EVENT` or `FLAG_FOREGROUND_SERVICE`. */
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
    /** The channel's importance is `IMPORTANCE_MIN` or lower. */
    val minImportance: Boolean = false,
)

/** An app icon as sent to the Mac: [hash] is the first 16 lowercase hex chars of the PNG's SHA-256. */
data class AppIcon(val hash: String, val pngBase64: String)

/** An app that posted a forwardable notification, as listed in the Notifications tab. */
@Serializable
data class SeenApp(
    val packageName: String,
    val appName: String,
    val iconHash: String = "",
    val muted: Boolean = false,
    /** Epoch day that [count] belongs to. */
    val countDay: Long = 0,
    /** Distinct notifications on [countDay]. */
    val count: Int = 0,
)

@Serializable
data class NotificationSettings(val forwarding: Boolean = true, val apps: List<SeenApp> = emptyList())

/** Persists [NotificationSettings]. The app stores them in SharedPreferences. */
interface NotificationSettingsStore {
    fun load(): NotificationSettings
    fun save(settings: NotificationSettings)
}

class InMemoryNotificationSettingsStore(private var settings: NotificationSettings = NotificationSettings()) : NotificationSettingsStore {
    override fun load() = settings
    override fun save(settings: NotificationSettings) { this.settings = settings }
}

/** What the Mac asked the phone to do; the app carries it out with the notification listener. */
sealed interface NotificationCommand {
    val key: String
    data class Reply(override val key: String, val text: String) : NotificationCommand
    data class Action(override val key: String, val actionIndex: Int) : NotificationCommand
    data class Dismiss(override val key: String) : NotificationCommand
}

/** What the Notifications tab shows. */
data class NotificationsState(val forwarding: Boolean, val access: Boolean, val apps: List<SeenApp>)
```

`NotificationRules.kt`:

```kotlin
package dev.macdroid.core.notifications

/** Which notifications go to the Mac, and how much of them (spec §3.1, §2.2). */
object NotificationRules {
    const val TITLE_LIMIT = 200
    const val TEXT_LIMIT = 2_000
    const val BUTTON_LIMIT = 3

    /** True for notifications that never go to the Mac. Mutes and the master switch are checked by the forwarder. */
    fun excluded(n: PhoneNotification, ownPackage: String): Boolean =
        n.packageName == ownPackage || n.ongoing || n.groupSummary || n.minImportance || (n.title.isBlank() && n.text.isBlank())

    /** Cuts [value] to at most [limit] chars ending in "…", never leaving half a surrogate pair. */
    fun clip(value: String, limit: Int): String {
        if (value.length <= limit) return value
        var cut = value.take(limit - 1)
        if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return "$cut…"
    }
}
```

- [ ] **Step 4: Implement the forwarder**

`NotificationForwarder.kt`:

```kotlin
package dev.macdroid.core.notifications

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.NotificationAppsBody
import dev.macdroid.core.protocol.NotificationErrorBody
import dev.macdroid.core.protocol.NotificationErrorReason
import dev.macdroid.core.protocol.NotificationIconBody
import dev.macdroid.core.protocol.NotificationKeyBody
import dev.macdroid.core.protocol.NotificationPostedBody
import dev.macdroid.core.protocol.NotificationSyncBody
import dev.macdroid.core.protocol.PhoneAppBody
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides which phone notifications go to the Mac and keeps the phone-owned settings (spec §2.2, §3.1, §8).
 * Thread-safe: the listener, the connection and the UI call in from different threads.
 */
class NotificationForwarder(
    private val ownPackage: String,
    private val store: NotificationSettingsStore,
    private val send: (Message) -> Boolean,
    private val iconFor: (String) -> AppIcon?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val lock = Any()
    private var settings = store.load()
    private var access = false
    private var connected = false
    /** Forwarded keys (in memory for the listener's lifetime, spec §8.3) with what was last sent for each (§8.4). */
    private val forwarded = LinkedHashMap<String, NotificationPostedBody>()
    private val iconsSent = HashSet<String>()
    /** Keys counted toward today's per-app count, so updates of one notification count once. */
    private val counted = HashSet<String>()
    private val _state = MutableStateFlow(NotificationsState(settings.forwarding, access, settings.apps))
    val state: StateFlow<NotificationsState> = _state.asStateFlow()

    /** The Mac connected (and supports notifications). [activeKeys] are the notifications showing on the phone now. */
    fun connected(activeKeys: Set<String>) {
        synchronized(lock) {
            connected = true
            iconsSent.clear()
            forwarded.keys.retainAll(activeKeys)
            sendApps()
            send(Message.NotificationSync(NotificationSyncBody(forwarded.keys.toList())))
        }
    }

    fun disconnected() {
        synchronized(lock) { connected = false }
    }

    fun accessChanged(granted: Boolean) {
        synchronized(lock) {
            if (granted == access) return
            access = granted
            publish()
            sendApps()
        }
    }

    fun posted(notification: PhoneNotification) {
        if (NotificationRules.excluded(notification, ownPackage)) return
        val icon = iconFor(notification.packageName) // may render a bitmap; done outside the lock
        synchronized(lock) {
            val app = remember(notification, icon?.hash.orEmpty())
            if (!connected || !settings.forwarding || app.muted) return
            val body = NotificationPostedBody(
                key = notification.key,
                packageName = notification.packageName,
                appName = notification.appName,
                title = NotificationRules.clip(notification.title, NotificationRules.TITLE_LIMIT),
                text = NotificationRules.clip(notification.text, NotificationRules.TEXT_LIMIT),
                postedAt = notification.postedAt,
                actions = notification.actions.take(NotificationRules.BUTTON_LIMIT),
                canReply = notification.canReply,
                iconHash = app.iconHash,
                isUpdate = notification.key in forwarded,
            )
            val previous = forwarded[notification.key]
            if (previous != null && previous.sameContent(body)) return
            if (icon != null) sendIcon(icon)
            if (send(Message.NotificationPosted(body))) forwarded[notification.key] = body
        }
    }

    fun removed(key: String) {
        synchronized(lock) {
            if (forwarded.remove(key) != null && connected) send(Message.NotificationRemoved(NotificationKeyBody(key)))
        }
    }

    fun setForwarding(on: Boolean) {
        synchronized(lock) {
            if (settings.forwarding == on) return
            settings = settings.copy(forwarding = on)
            save()
            sendApps()
        }
    }

    fun setMuted(packageName: String, muted: Boolean) {
        synchronized(lock) {
            val app = settings.apps.firstOrNull { it.packageName == packageName } ?: return
            if (app.muted != muted) {
                settings = settings.copy(apps = settings.apps.map { if (it.packageName == packageName) it.copy(muted = muted) else it })
                save()
            }
            sendApps() // always confirm, so the Mac's switch settles on the phone's truth
        }
    }

    /** A message from the Mac. Returns what the app must do on the phone, if anything. */
    fun handle(message: Message): NotificationCommand? = when (message) {
        is Message.NotificationMute -> {
            setMuted(message.body.packageName, message.body.muted)
            null
        }
        is Message.NotificationReply -> NotificationCommand.Reply(message.body.key, message.body.text)
        is Message.NotificationAction -> NotificationCommand.Action(message.body.key, message.body.actionIndex)
        is Message.NotificationDismiss -> NotificationCommand.Dismiss(message.body.key)
        else -> null
    }

    fun reportError(key: String, reason: NotificationErrorReason) {
        send(Message.NotificationError(NotificationErrorBody(key, reason)))
    }

    private fun remember(n: PhoneNotification, iconHash: String): SeenApp {
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toEpochDay()
        val existing = settings.apps.firstOrNull { it.packageName == n.packageName }
        val firstTime = counted.add(n.key)
        val base = if (existing?.countDay == today) existing.count else 0
        val app = SeenApp(
            packageName = n.packageName,
            appName = n.appName,
            iconHash = iconHash.ifEmpty { existing?.iconHash.orEmpty() },
            muted = existing?.muted ?: false,
            countDay = today,
            count = if (firstTime || base == 0) base + 1 else base,
        )
        if (app != existing) {
            settings = settings.copy(apps = (settings.apps.filterNot { it.packageName == n.packageName } + app).sortedBy { it.appName.lowercase() })
            save()
        }
        if (existing == null || existing.appName != app.appName || existing.iconHash != app.iconHash) sendApps()
        return app
    }

    private fun sendApps() {
        if (!connected) return
        for (app in settings.apps) {
            if (app.iconHash.isNotEmpty() && app.iconHash !in iconsSent) iconFor(app.packageName)?.let(::sendIcon)
        }
        val apps = settings.apps.map { PhoneAppBody(it.packageName, it.appName, it.iconHash, it.muted) }
        send(Message.NotificationApps(NotificationAppsBody(settings.forwarding, access, apps)))
    }

    private fun sendIcon(icon: AppIcon) {
        if (iconsSent.add(icon.hash)) send(Message.NotificationIcon(NotificationIconBody(icon.hash, icon.pngBase64)))
    }

    private fun save() {
        runCatching { store.save(settings) }
        publish()
    }

    private fun publish() {
        _state.value = NotificationsState(settings.forwarding, access, settings.apps)
    }
}

private fun NotificationPostedBody.sameContent(other: NotificationPostedBody) =
    title == other.title && text == other.text && actions == other.actions && canReply == other.canReply
```

- [ ] **Step 5: Run the tests**

Run: `cd android && ./gradlew :core:test`
Expected: PASS. If `countsDistinctNotificationsPerDay` fails, check `remember`. The count must rise only for a key not yet `counted`, and must restart at 1 on a new day.

- [ ] **Step 6: Handoff and commit**

- CHANGELOG bullet: "Task 3: Android core `NotificationForwarder`: exclusions, update de-dupe, mutes owned by the phone, apps seen with daily counts, sync of still-active keys, icons once per connection."
- ARCHITECTURE: add an Android core row: `notifications | NotificationModel.kt, NotificationRules.kt, NotificationForwarder.kt | which notifications go to the Mac; phone-owned settings`.

```bash
git add android/core docs/handoff
git commit -m "feat(android-core): notification forwarder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Mac core — `NotificationStore`, `FloodGate`, `NotificationCategories`

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Notifications/NotificationStore.swift`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Notifications/FloodGate.swift`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Notifications/NotificationCategories.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/NotificationStoreTests.swift`
- Handoff: CHANGELOG bullet

**Interfaces:**
- Consumes: Task 1's `NotificationPosted`.
- Produces:
  - `NotificationEntry(posted:receivedAt:)`, which is `Identifiable` (`id == posted.key`).
  - `NotificationGroup(packageName:appName:iconHash:entries:)`.
  - `NotificationStore`, with:
    - `limit = 50` and `entries: [NotificationEntry]`
    - `upsert(_:at:)`
    - `remove(key:) -> NotificationEntry?` (discardable)
    - `retain(keys:) -> [String]`
    - `clear() -> [String]`
    - `entry(key:)`
    - `groups: [NotificationGroup]`
  - `FloodGate`, with `window = 5`, `limit = 10` and `mutating allowsBanner(packageName:at:) -> Bool`.
  - `NotificationCategorySpec(identifier:canReply:buttons:)`.
  - `NotificationCategories`, with:
    - `prefix = "dev.macdroid.phone."`
    - `replyAction = "reply"`
    - `spec(for:)`
    - `actionIdentifier(position:)`
    - `position(ofAction:) -> Int?`
    - `actionIndices(for:) -> [Int]`

- [ ] **Step 1: Write the failing tests**

`NotificationStoreTests.swift`:

```swift
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
        #expect(gate.allowsBanner(packageName: "mail", at: start.addingTimeInterval(1)))
        #expect(gate.allowsBanner(packageName: "chat", at: start.addingTimeInterval(7)))
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
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd mac/MacDroidCore && swift test --filter NotificationStoreTests`
Expected: compile errors (types not found).

- [ ] **Step 3: Implement**

`NotificationStore.swift`:

```swift
import Foundation

public struct NotificationEntry: Equatable, Sendable, Identifiable {
    public var posted: NotificationPosted
    public var receivedAt: Date
    public var id: String { posted.key }

    public init(posted: NotificationPosted, receivedAt: Date) {
        self.posted = posted
        self.receivedAt = receivedAt
    }
}

public struct NotificationGroup: Equatable, Sendable, Identifiable {
    public var packageName: String
    public var appName: String
    public var iconHash: String
    /// Newest first.
    public var entries: [NotificationEntry]
    public var id: String { packageName }

    public init(packageName: String, appName: String, iconHash: String, entries: [NotificationEntry]) {
        self.packageName = packageName
        self.appName = appName
        self.iconHash = iconHash
        self.entries = entries
    }
}

/// The Mac's list of phone notifications (spec §3.3): newest first, at most 50, in memory only.
public struct NotificationStore: Equatable, Sendable {
    public static let limit = 50
    public private(set) var entries: [NotificationEntry] = []

    public init() {}

    /// Adds a notification, or replaces the one with the same key, and moves it to the top.
    public mutating func upsert(_ posted: NotificationPosted, at date: Date) {
        entries.removeAll { $0.posted.key == posted.key }
        entries.insert(NotificationEntry(posted: posted, receivedAt: date), at: 0)
        if entries.count > Self.limit { entries.removeLast(entries.count - Self.limit) }
    }

    @discardableResult
    public mutating func remove(key: String) -> NotificationEntry? {
        guard let index = entries.firstIndex(where: { $0.posted.key == key }) else { return nil }
        return entries.remove(at: index)
    }

    /// Keeps only `keys` (spec §2.2 `notification.sync`); returns the keys removed.
    public mutating func retain(keys: Set<String>) -> [String] {
        let removed = entries.filter { !keys.contains($0.posted.key) }.map(\.posted.key)
        entries.removeAll { !keys.contains($0.posted.key) }
        return removed
    }

    /// Removes everything; returns the keys removed.
    public mutating func clear() -> [String] {
        let keys = entries.map(\.posted.key)
        entries.removeAll()
        return keys
    }

    public func entry(key: String) -> NotificationEntry? {
        entries.first { $0.posted.key == key }
    }

    /// One group per app, ordered by each app's newest notification.
    public var groups: [NotificationGroup] {
        var order: [String] = []
        var byPackage: [String: NotificationGroup] = [:]
        for entry in entries {
            let posted = entry.posted
            if byPackage[posted.packageName] == nil {
                order.append(posted.packageName)
                byPackage[posted.packageName] = NotificationGroup(
                    packageName: posted.packageName, appName: posted.appName, iconHash: posted.iconHash, entries: []
                )
            }
            byPackage[posted.packageName]?.entries.append(entry)
        }
        return order.compactMap { byPackage[$0] }
    }
}
```

`FloodGate.swift`:

```swift
import Foundation

/// Limits banners from chatty apps (spec §8.6): an app's notification shows a banner only if that app
/// sent at most 10 in the last 5 seconds, counting this one. The rest still reach the Notifications page.
public struct FloodGate: Sendable {
    public static let window: TimeInterval = 5
    public static let limit = 10
    private var recent: [String: [Date]] = [:]

    public init() {}

    public mutating func allowsBanner(packageName: String, at date: Date) -> Bool {
        var times = (recent[packageName] ?? []).filter { date.timeIntervalSince($0) < Self.window }
        times.append(date)
        recent[packageName] = times
        return times.count <= Self.limit
    }
}
```

`NotificationCategories.swift`:

```swift
import Crypto
import Foundation

/// A macOS notification category: a reply field and/or up to 3 buttons.
public struct NotificationCategorySpec: Hashable, Sendable {
    public let identifier: String
    public let canReply: Bool
    public let buttons: [String]
}

/// Category and action identifiers for mirrored notifications (spec §3.2). One category per distinct
/// (reply, button titles) combination, so the same combination reuses the same registered category.
public enum NotificationCategories {
    public static let prefix = "dev.macdroid.phone."
    public static let replyAction = "reply"
    static let actionPrefix = "action."

    public static func spec(for posted: NotificationPosted) -> NotificationCategorySpec {
        let buttons = posted.actions.prefix(3).map(\.title)
        let signature = (posted.canReply ? "r" : "n") + "|" + buttons.joined(separator: "\u{1F}")
        let digest = SHA256.hash(data: Data(signature.utf8)).prefix(8).map { String(format: "%02x", $0) }.joined()
        return NotificationCategorySpec(identifier: prefix + digest, canReply: posted.canReply, buttons: Array(buttons))
    }

    /// The Android action index for each button position in `spec(for:)`.
    public static func actionIndices(for posted: NotificationPosted) -> [Int] {
        posted.actions.prefix(3).map(\.index)
    }

    public static func actionIdentifier(position: Int) -> String { actionPrefix + String(position) }

    public static func position(ofAction identifier: String) -> Int? {
        guard identifier.hasPrefix(actionPrefix) else { return nil }
        return Int(identifier.dropFirst(actionPrefix.count))
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd mac/MacDroidCore && swift test`
Expected: PASS, with no warnings.

- [ ] **Step 5: Handoff and commit**

CHANGELOG bullet: "Task 4: Mac core `NotificationStore` (50, grouped by app), `FloodGate` (10 per 5 s per app), `NotificationCategories`."

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac-core): notification store, flood gate and categories

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Mac core — `IconCache`, `PhoneNotifications` actor, `NotificationPresentation`

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Notifications/IconCache.swift`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Notifications/PhoneNotifications.swift`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Presentation/NotificationPresentation.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/PhoneNotificationsTests.swift`, `NotificationPresentationTests.swift`
- Handoff: CHANGELOG bullet; ARCHITECTURE (Mac core table: a Notifications row; Presentation row)

**Interfaces:**
- Consumes:
  - Task 1's messages.
  - Task 2's `PeerInfo.supports`.
  - Task 4's `NotificationStore`, `FloodGate` and `NotificationGroup`.
  - `Broadcast` (internal, `Session/Broadcast.swift`).
- Produces:
  - `IconCache(directory:)`, with `isValid(hash:)` (static), `save(hash:pngBase64:) -> Bool` (discardable) and `url(for:) -> URL?`.
  - `PhoneNotificationsState(groups:apps:phoneForwarding:phoneAccess:connected:phoneSupportsNotifications:count:)`, plus `.empty`.
  - `PhoneNotificationEvent`: `.show(NotificationPosted, banner: Bool)`, `.remove(keys: [String])`, `.problem(String)`.
  - `actor PhoneNotifications(icons:enabled:now:send:)`, with:
    - `states` and `events` streams, and `state`
    - `attach(to:)`
    - `setEnabled(_:)`
    - `reply(key:text:)`, `perform(key:actionIndex:)`, `dismiss(key:)`, `clearAll()`
    - `setMuted(packageName:muted:)`
    - `entry(key:) -> NotificationEntry?`
  - `NotificationPresentation`, with:
    - `relativeTime(_:now:)`
    - `subtitle(appName:phoneName:)`
    - `pageNotice(_:) -> String?`
    - `phonePageLine(_:) -> String?`
    - `errorText(_:appName:wasReply:)`
    - `bannerTitle(_:)`

- [ ] **Step 1: Write the failing tests**

`PhoneNotificationsTests.swift`:

```swift
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
```

`NotificationPresentationTests.swift`:

```swift
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
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd mac/MacDroidCore && swift test --filter 'PhoneNotificationsTests|IconCacheTests|NotificationPresentationTests'`
Expected: compile errors.

- [ ] **Step 3: Implement `IconCache`**

```swift
import Foundation

/// App icons from the phone, cached on disk by hash (spec §2.2). The hash becomes a file name,
/// so only exactly 16 lowercase hex characters are accepted.
public struct IconCache: Sendable {
    public let directory: URL
    static let maxBytes = 256 * 1024

    public init(directory: URL) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    public static func isValid(hash: String) -> Bool {
        hash.count == 16 && hash.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
    }

    @discardableResult
    public func save(hash: String, pngBase64: String) -> Bool {
        guard Self.isValid(hash: hash),
              let data = Data(base64Encoded: pngBase64), !data.isEmpty, data.count <= Self.maxBytes else { return false }
        let url = file(hash)
        if FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) { return true }
        do {
            try data.write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    public func url(for hash: String) -> URL? {
        guard Self.isValid(hash: hash) else { return nil }
        let url = file(hash)
        return FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) ? url : nil
    }

    private func file(_ hash: String) -> URL { directory.appending(path: hash + ".png") }
}
```

- [ ] **Step 4: Implement `NotificationPresentation`**

```swift
import Foundation

/// Text for the Notifications page, banners and the Phone page (spec §3.3, §5).
public enum NotificationPresentation {
    public static func relativeTime(_ date: Date, now: Date) -> String {
        let seconds = max(0, now.timeIntervalSince(date))
        if seconds < 60 { return "now" }
        if seconds < 3_600 { return "\(Int(seconds / 60)) min ago" }
        if seconds < 86_400 { return "\(Int(seconds / 3_600)) h ago" }
        return "\(Int(seconds / 86_400)) d ago"
    }

    public static func subtitle(appName: String, phoneName: String) -> String { "\(appName) · \(phoneName)" }

    /// A notification with no title shows the app's name instead.
    public static func bannerTitle(_ posted: NotificationPosted) -> String {
        posted.title.trimmingCharacters(in: .whitespaces).isEmpty ? posted.appName : posted.title
    }

    /// The banner at the top of the Notifications page, if the phone can't send notifications right now.
    public static func pageNotice(_ state: PhoneNotificationsState) -> String? {
        guard state.connected else { return nil }
        if !state.phoneSupportsNotifications { return "Update MacDroid on your phone to use this." }
        if state.phoneAccess == false { return "Notification access is off on your phone. Open MacDroid on your phone to turn it on." }
        if state.phoneForwarding == false { return "“Show on your Mac” is off in MacDroid on your phone." }
        return nil
    }

    /// The Phone page's line when the phone isn't sending notifications.
    public static func phonePageLine(_ state: PhoneNotificationsState) -> String? {
        state.phoneAccess == false || state.phoneForwarding == false ? "Notifications off on phone" : nil
    }

    public static func errorText(_ reason: NotificationErrorReason, appName: String, wasReply: Bool) -> String {
        switch reason {
        case .gone: "This notification is no longer on your phone"
        case .failed: wasReply ? "Couldn't send reply to \(appName)" : "Couldn't do that in \(appName)"
        }
    }
}
```

- [ ] **Step 5: Implement `PhoneNotifications`**

```swift
import Foundation

public struct PhoneNotificationsState: Equatable, Sendable {
    public var groups: [NotificationGroup]
    public var apps: [PhoneApp]
    /// The phone's "Show on your Mac" switch, from its last `notification.apps` (nil until one arrives).
    public var phoneForwarding: Bool?
    public var phoneAccess: Bool?
    public var connected: Bool
    /// The connected phone listed "notifications" in its hello.
    public var phoneSupportsNotifications: Bool
    public var count: Int

    public init(
        groups: [NotificationGroup] = [], apps: [PhoneApp] = [], phoneForwarding: Bool? = nil, phoneAccess: Bool? = nil,
        connected: Bool = false, phoneSupportsNotifications: Bool = false, count: Int = 0
    ) {
        self.groups = groups
        self.apps = apps
        self.phoneForwarding = phoneForwarding
        self.phoneAccess = phoneAccess
        self.connected = connected
        self.phoneSupportsNotifications = phoneSupportsNotifications
        self.count = count
    }

    public static let empty = PhoneNotificationsState()
}

public enum PhoneNotificationEvent: Equatable, Sendable {
    /// Post or replace a macOS notification. `banner == false`: the page only (flood gate).
    case show(NotificationPosted, banner: Bool)
    /// Remove delivered macOS notifications.
    case remove(keys: [String])
    /// Something the user should read on the Notifications page.
    case problem(String)
}

/// The Mac side of notification mirroring (spec §3.2): keeps the page's entries and the phone's app list,
/// and turns the user's replies, actions, dismissals and mutes into messages.
public actor PhoneNotifications {
    public nonisolated var states: AsyncStream<PhoneNotificationsState> { stateBroadcast.stream() }
    public nonisolated var events: AsyncStream<PhoneNotificationEvent> { eventBroadcast.stream() }
    public private(set) var state = PhoneNotificationsState.empty

    private let stateBroadcast = Broadcast<PhoneNotificationsState>(replayLatest: true, initial: .empty)
    private let eventBroadcast = Broadcast<PhoneNotificationEvent>(replayLatest: false)
    private let icons: IconCache
    private let now: @Sendable () -> Date
    private let send: @Sendable (Message) async throws -> Void
    private var enabled: Bool
    private var store = NotificationStore()
    private var flood = FloodGate()
    private var apps: [PhoneApp] = []
    private var phoneForwarding: Bool?
    private var phoneAccess: Bool?
    private var connected = false
    private var phoneSupports = false
    /// Keys with a reply in flight, so an error names the right thing.
    private var replying: Set<String> = []
    private var tasks: [Task<Void, Never>] = []

    public init(
        icons: IconCache, enabled: Bool = true, now: @escaping @Sendable () -> Date = { Date() },
        send: @escaping @Sendable (Message) async throws -> Void
    ) {
        self.icons = icons
        self.enabled = enabled
        self.now = now
        self.send = send
    }

    public func attach(to hub: ConnectionHub) {
        guard tasks.isEmpty else { return }
        let messages = hub.messages
        let statuses = hub.statusUpdates
        tasks.append(Task { [weak self] in
            for await message in messages { await self?.handle(message) }
        })
        tasks.append(Task { [weak self] in
            for await status in statuses { await self?.connectionChanged(status) }
        })
    }

    public func entry(key: String) -> NotificationEntry? { store.entry(key: key) }

    /// The Mac's "Show phone notifications" setting. Off: incoming notifications are ignored.
    public func setEnabled(_ on: Bool) { enabled = on }

    func handle(_ message: Message) {
        switch message {
        case .notificationPosted(let posted):
            guard enabled else { return }
            let date = now()
            store.upsert(posted, at: date)
            let banner = flood.allowsBanner(packageName: posted.packageName, at: date)
            publish()
            eventBroadcast.yield(.show(posted, banner: banner))
        case .notificationRemoved(let body):
            store.remove(key: body.key)
            publish()
            eventBroadcast.yield(.remove(keys: [body.key]))
        case .notificationSync(let body):
            let removed = store.retain(keys: Set(body.keys))
            guard !removed.isEmpty else { return }
            publish()
            eventBroadcast.yield(.remove(keys: removed))
        case .notificationIcon(let body):
            icons.save(hash: body.hash, pngBase64: body.png)
        case .notificationApps(let body):
            apps = body.apps
            phoneForwarding = body.forwarding
            phoneAccess = body.access
            publish()
        case .notificationError(let body):
            let appName = store.entry(key: body.key)?.posted.appName ?? "the app"
            let wasReply = replying.remove(body.key) != nil
            if body.reason == .gone {
                store.remove(key: body.key)
                publish()
                eventBroadcast.yield(.remove(keys: [body.key]))
            }
            eventBroadcast.yield(.problem(NotificationPresentation.errorText(body.reason, appName: appName, wasReply: wasReply)))
        default:
            break
        }
    }

    func connectionChanged(_ status: ConnectionStatus) {
        let (isConnected, supports): (Bool, Bool) = switch status {
        case .connected(let peer): (true, peer.supports(Feature.notifications))
        case .disconnected: (false, phoneSupports)
        }
        guard isConnected != connected || supports != phoneSupports else { return }
        connected = isConnected
        phoneSupports = supports
        publish()
    }

    public func reply(key: String, text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        replying.insert(key)
        do {
            try await send(.notificationReply(NotificationReply(key: key, text: trimmed)))
        } catch {
            replying.remove(key)
            eventBroadcast.yield(.problem("Your phone isn't connected, so the reply wasn't sent"))
        }
    }

    public func perform(key: String, actionIndex: Int) async {
        do {
            try await send(.notificationAction(NotificationActionRequest(key: key, actionIndex: actionIndex)))
        } catch {
            eventBroadcast.yield(.problem("Your phone isn't connected"))
        }
    }

    public func dismiss(key: String) async {
        store.remove(key: key)
        publish()
        eventBroadcast.yield(.remove(keys: [key]))
        try? await send(.notificationDismiss(NotificationKey(key: key)))
    }

    public func clearAll() async {
        let keys = store.clear()
        guard !keys.isEmpty else { return }
        publish()
        eventBroadcast.yield(.remove(keys: keys))
        for key in keys { try? await send(.notificationDismiss(NotificationKey(key: key))) }
    }

    /// Asks the phone (which owns the list, spec §2.2) to mute or unmute; shows the change right away.
    public func setMuted(packageName: String, muted: Bool) async {
        guard let index = apps.firstIndex(where: { $0.packageName == packageName }) else { return }
        let before = apps[index].muted
        apps[index].muted = muted
        publish()
        do {
            try await send(.notificationMute(NotificationMute(packageName: packageName, muted: muted)))
        } catch {
            if let i = apps.firstIndex(where: { $0.packageName == packageName }) { apps[i].muted = before }
            publish()
            eventBroadcast.yield(.problem("Your phone isn't connected, so the change wasn't saved"))
        }
    }

    private func publish() {
        state = PhoneNotificationsState(
            groups: store.groups, apps: apps, phoneForwarding: phoneForwarding, phoneAccess: phoneAccess,
            connected: connected, phoneSupportsNotifications: phoneSupports, count: store.entries.count
        )
        stateBroadcast.yield(state)
    }
}
```

Note: `posted(_:…)` in the tests is the free helper function from `NotificationStoreTests.swift` (Task 4); it's visible across the test target.

- [ ] **Step 6: Run the tests**

Run: `cd mac/MacDroidCore && swift test`
Expected: PASS, with no warnings. If a `switch` expression with tuple bindings fails to type-check on this toolchain, replace it with an `if case .connected(let peer) = status { … } else { … }` that sets the same two values.

- [ ] **Step 7: Handoff and commit**

- CHANGELOG bullet: "Task 5: Mac core `PhoneNotifications` actor (entries, apps, banners via flood gate, sync, errors, mute requests), `IconCache` (hash-validated), `NotificationPresentation`."
- ARCHITECTURE: add a Mac core row: `Notifications | Notifications/NotificationStore.swift, FloodGate.swift, NotificationCategories.swift, IconCache.swift, PhoneNotifications.swift (actor) | mirrored notifications: entries, phone app list, banners, replies/actions/dismiss/mute`. Add `NotificationPresentation.swift` to the Presentation row.

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac-core): PhoneNotifications actor, icon cache and presentation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: Mac app — one notification router, `PhoneNotifier`, and `AppModel` wiring

**Files:**
- Create: `mac/MacDroid/Notifications/NotificationRouter.swift`
- Create: `mac/MacDroid/Notifications/PhoneNotifier.swift`
- Modify: `mac/MacDroid/Files/ReceivedNotifier.swift` (no longer the delegate)
- Modify: `mac/MacDroid/AppModel.swift`
- Handoff: CHANGELOG bullet; ARCHITECTURE (Mac app: `Notifications/` folder, router is the only delegate)

**Interfaces:**
- Consumes:
  - Task 4's `NotificationCategories` and `NotificationCategorySpec`.
  - Task 5's `PhoneNotifications`, `PhoneNotificationsState`, `PhoneNotificationEvent`, `IconCache` and `NotificationPresentation`.
- Produces (used by Task 7's views):
  - `AppModel.notifications: PhoneNotificationsState`
  - `AppModel.notificationProblem: String?` and `AppModel.macNotificationsDenied: Bool`
  - `AppModel.showPhoneNotifications: Bool` (persisted under `"showPhoneNotifications"`, default `true`) and `AppModel.setShowPhoneNotifications(_ on: Bool)`
  - `AppModel` methods:
    - `replyToNotification(_ key: String, text: String)`
    - `performNotificationAction(_ key: String, index: Int)`
    - `dismissNotification(_ key: String)` and `clearAllNotifications()`
    - `setAppMuted(_ packageName: String, muted: Bool)`
    - `iconURL(_ hash: String) -> URL?`
    - `refreshNotificationPermission() async` and `dismissNotificationProblem()`
  - `NotificationRouter`:
    - `setUp()` and `register(_ category: UNNotificationCategory)`
    - `ensureAuthorized() async -> Bool`
    - `static isDenied() async -> Bool` (nonisolated)
    - `onPhoneResponse: ((PhoneNotificationResponse) -> Void)?`
  - `PhoneNotificationResponse(key:kind:)` with `Kind` `.open`, `.dismiss`, `.reply(String)`, `.button(Int)`.

There are no unit tests in this task: the app target has no test harness. The logic it calls is tested in Tasks 4–5. It is verified by the Mac app build and by the owner's checklist (Task 11).

- [ ] **Step 1: Create `NotificationRouter.swift`**

```swift
import AppKit
import UserNotifications

/// The app's only `UNUserNotificationCenter` delegate (spec §8.7). macOS allows one delegate and one category set,
/// so received-file notifications and mirrored phone notifications share this router.
@MainActor
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate {
    private var categories: [String: UNNotificationCategory] = [:]
    private var authorized: Bool?
    /// Responses to mirrored phone notifications, already read out of the system response.
    var onPhoneResponse: ((PhoneNotificationResponse) -> Void)?

    func setUp() {
        UNUserNotificationCenter.current().delegate = self
        register(ReceivedNotifier.makeCategory())
    }

    /// Adds a category. macOS replaces the whole set on every call, so the full set is always sent.
    func register(_ category: UNNotificationCategory) {
        guard categories[category.identifier] == nil else { return }
        categories[category.identifier] = category
        UNUserNotificationCenter.current().setNotificationCategories(Set(categories.values))
    }

    /// Asks for permission the first time anything needs to notify.
    func ensureAuthorized() async -> Bool {
        if let authorized { return authorized }
        let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
        authorized = granted
        return granted
    }

    /// The user turned MacDroid's notifications off in System Settings.
    nonisolated static func isDenied() async -> Bool {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .denied
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if response.notification.request.content.categoryIdentifier == ReceivedNotifier.category {
            await ReceivedNotifier.respond(to: response)
        } else if let parsed = PhoneNotifier.parse(response) {
            await MainActor.run { onPhoneResponse?(parsed) }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}
```

- [ ] **Step 2: Rewrite `ReceivedNotifier.swift`**

```swift
import AppKit
import MacDroidCore
import UserNotifications

/// "Received <name>" notifications with Show in Finder and Open (file-transfer spec §3.2).
/// Permission, categories and responses go through `NotificationRouter`, the app's only delegate.
@MainActor
final class ReceivedNotifier {
    nonisolated static let category = "dev.macdroid.received"
    nonisolated static let showAction = "show"
    nonisolated static let openAction = "open"
    nonisolated static let pathKey = "path"
    private let router: NotificationRouter

    init(router: NotificationRouter) { self.router = router }

    static func makeCategory() -> UNNotificationCategory {
        UNNotificationCategory(
            identifier: category,
            actions: [
                UNNotificationAction(identifier: showAction, title: "Show in Finder"),
                UNNotificationAction(identifier: openAction, title: "Open"),
            ],
            intentIdentifiers: []
        )
    }

    func notifyReceived(_ record: TransferRecord, from phoneName: String) async {
        guard let url = record.savedURL, await router.ensureAuthorized() else { return }
        let content = UNMutableNotificationContent()
        content.title = "Received \(record.name)"
        content.body = "From \(phoneName)"
        content.categoryIdentifier = Self.category
        content.userInfo = [Self.pathKey: url.path(percentEncoded: false)]
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: record.id, content: content, trigger: nil))
    }

    /// Open, or Show in Finder (the default click and "Show in Finder").
    nonisolated static func respond(to response: UNNotificationResponse) async {
        guard let path = response.notification.request.content.userInfo[pathKey] as? String else { return }
        let open = response.actionIdentifier == openAction
        await MainActor.run {
            let url = URL(fileURLWithPath: path)
            if open { NSWorkspace.shared.open(url) } else { NSWorkspace.shared.activateFileViewerSelecting([url]) }
        }
    }
}
```

- [ ] **Step 3: Create `PhoneNotifier.swift`**

```swift
import Foundation
import MacDroidCore
import UserNotifications

/// What the user did with a mirrored notification, read out of the system response.
struct PhoneNotificationResponse: Sendable, Equatable {
    enum Kind: Sendable, Equatable {
        case open
        case dismiss
        case reply(String)
        /// The Android action index.
        case button(Int)
    }

    let key: String
    let kind: Kind
}

/// Posts mirrored phone notifications as macOS notifications (spec §3.2).
@MainActor
final class PhoneNotifier {
    nonisolated static let keyInfo = "phoneKey"
    nonisolated static let indicesInfo = "actionIndices"
    private let router: NotificationRouter
    private let icons: IconCache

    init(router: NotificationRouter, icons: IconCache) {
        self.router = router
        self.icons = icons
    }

    /// `banner == false` (the flood gate said no) posts nothing; the entry is still on the page.
    func show(_ posted: NotificationPosted, banner: Bool, phoneName: String) async {
        guard banner, await router.ensureAuthorized() else { return }
        let spec = NotificationCategories.spec(for: posted)
        router.register(Self.category(for: spec))
        let content = UNMutableNotificationContent()
        content.title = NotificationPresentation.bannerTitle(posted)
        content.subtitle = NotificationPresentation.subtitle(appName: posted.appName, phoneName: phoneName)
        content.body = posted.text
        content.threadIdentifier = posted.packageName
        content.categoryIdentifier = spec.identifier
        content.sound = .default
        content.userInfo = [Self.keyInfo: posted.key, Self.indicesInfo: NotificationCategories.actionIndices(for: posted)]
        if let attachment = iconAttachment(posted.iconHash) { content.attachments = [attachment] }
        // Same identifier as before: an update replaces the delivered notification.
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: posted.key, content: content, trigger: nil))
    }

    func remove(_ keys: [String]) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: keys)
    }

    /// macOS moves attachment files into its own store (spec §8.8), so attach a copy of the cached icon.
    private func iconAttachment(_ hash: String) -> UNNotificationAttachment? {
        guard let icon = icons.url(for: hash) else { return nil }
        let copy = FileManager.default.temporaryDirectory.appending(path: "macdroid-icon-\(UUID().uuidString).png")
        do {
            try FileManager.default.copyItem(at: icon, to: copy)
            return try UNNotificationAttachment(identifier: "icon", url: copy)
        } catch {
            return nil
        }
    }

    static func category(for spec: NotificationCategorySpec) -> UNNotificationCategory {
        var actions: [UNNotificationAction] = []
        if spec.canReply {
            actions.append(UNTextInputNotificationAction(
                identifier: NotificationCategories.replyAction, title: "Reply", options: [],
                textInputButtonTitle: "Send", textInputPlaceholder: "Reply"
            ))
        }
        for (position, title) in spec.buttons.enumerated() {
            actions.append(UNNotificationAction(identifier: NotificationCategories.actionIdentifier(position: position), title: title))
        }
        // .customDismissAction: closing the banner reports UNNotificationDismissActionIdentifier (dismiss sync).
        return UNNotificationCategory(identifier: spec.identifier, actions: actions, intentIdentifiers: [], options: [.customDismissAction])
    }

    nonisolated static func parse(_ response: UNNotificationResponse) -> PhoneNotificationResponse? {
        let content = response.notification.request.content
        guard content.categoryIdentifier.hasPrefix(NotificationCategories.prefix),
              let key = content.userInfo[keyInfo] as? String else { return nil }
        switch response.actionIdentifier {
        case UNNotificationDefaultActionIdentifier:
            return PhoneNotificationResponse(key: key, kind: .open)
        case UNNotificationDismissActionIdentifier:
            return PhoneNotificationResponse(key: key, kind: .dismiss)
        case NotificationCategories.replyAction:
            let text = (response as? UNTextInputNotificationResponse)?.userText ?? ""
            return PhoneNotificationResponse(key: key, kind: .reply(text))
        default:
            let indices = content.userInfo[indicesInfo] as? [Int] ?? []
            guard let position = NotificationCategories.position(ofAction: response.actionIdentifier),
                  indices.indices.contains(position) else { return nil }
            return PhoneNotificationResponse(key: key, kind: .button(indices[position]))
        }
    }
}
```

- [ ] **Step 4: Wire `AppModel`**

In `AppModel.swift`:

1. Add these properties next to `transfers`:

```swift
    private(set) var notifications: PhoneNotificationsState = .empty
    /// The last problem with a reply, button or mute (spec §5); shown on the Notifications page.
    private(set) var notificationProblem: String?
    /// MacDroid's notifications are turned off in System Settings.
    private(set) var macNotificationsDenied = false
    /// Settings → Notifications → "Show phone notifications" (spec §3.2). Off: incoming notifications are ignored.
    private(set) var showPhoneNotifications: Bool
    static let showPhoneNotificationsKey = "showPhoneNotifications"
    @ObservationIgnored let router: NotificationRouter
    @ObservationIgnored private let phoneNotifications: PhoneNotifications
    @ObservationIgnored private let phoneNotifier: PhoneNotifier
    @ObservationIgnored private let icons: IconCache
```

2. In `init()`, replace `notifier = ReceivedNotifier()` with:

```swift
        let router = NotificationRouter()
        self.router = router
        notifier = ReceivedNotifier(router: router)
        let icons = IconCache(directory: URL.applicationSupportDirectory.appending(path: "NotificationIcons", directoryHint: .isDirectory))
        self.icons = icons
        phoneNotifier = PhoneNotifier(router: router, icons: icons)
        let showPhone = UserDefaults.standard.object(forKey: Self.showPhoneNotificationsKey) as? Bool ?? true
        showPhoneNotifications = showPhone
        phoneNotifications = PhoneNotifications(icons: icons, enabled: showPhone, send: { message in try await hub.send(message) })
```

(`hub` is the local constant already created earlier in `init`.)

3. In `start()`, replace `notifier.setUp()` with:

```swift
        router.setUp()
        router.onPhoneResponse = { [weak self] response in self?.handlePhoneResponse(response) }
        tasks.append(Task { [phoneNotifications, hub] in await phoneNotifications.attach(to: hub) })
        tasks.append(Task { [weak self, phoneNotifications] in
            for await state in phoneNotifications.states { self?.notifications = state }
        })
        tasks.append(Task { [weak self, phoneNotifications] in
            for await event in phoneNotifications.events { await self?.handle(event) }
        })
```

4. Add these methods (anywhere after `retryTransfer`):

```swift
    // MARK: Phone notifications (spec §3)

    func replyToNotification(_ key: String, text: String) {
        notificationProblem = nil
        Task { await phoneNotifications.reply(key: key, text: text) }
    }

    func performNotificationAction(_ key: String, index: Int) {
        notificationProblem = nil
        Task { await phoneNotifications.perform(key: key, actionIndex: index) }
    }

    func dismissNotification(_ key: String) {
        Task { await phoneNotifications.dismiss(key: key) }
    }

    func clearAllNotifications() {
        Task { await phoneNotifications.clearAll() }
    }

    func setAppMuted(_ packageName: String, muted: Bool) {
        notificationProblem = nil
        Task { await phoneNotifications.setMuted(packageName: packageName, muted: muted) }
    }

    func setShowPhoneNotifications(_ on: Bool) {
        showPhoneNotifications = on
        UserDefaults.standard.set(on, forKey: Self.showPhoneNotificationsKey)
        Task { await phoneNotifications.setEnabled(on) }
    }

    func iconURL(_ hash: String) -> URL? { icons.url(for: hash) }

    func refreshNotificationPermission() async {
        macNotificationsDenied = await NotificationRouter.isDenied()
    }

    func dismissNotificationProblem() { notificationProblem = nil }

    private func handle(_ event: PhoneNotificationEvent) async {
        switch event {
        case .show(let posted, let banner):
            await phoneNotifier.show(posted, banner: banner, phoneName: pairedDevice?.name ?? "Your phone")
        case .remove(let keys):
            phoneNotifier.remove(keys)
        case .problem(let text):
            notificationProblem = text
        }
    }

    private func handlePhoneResponse(_ response: PhoneNotificationResponse) {
        switch response.kind {
        case .open: showWindow(.notifications)
        case .dismiss: dismissNotification(response.key)
        case .reply(let text): replyToNotification(response.key, text: text)
        case .button(let index): performNotificationAction(response.key, index: index)
        }
    }
```

- [ ] **Step 5: Build the Mac app**

Run: `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build 2>&1 | grep -E "warning:|error:|BUILD" | grep -v "appintentsmetadataprocessor"`
Expected: `** BUILD SUCCEEDED **` with no compiler warnings. Swift 6 may report non-Sendable `UNNotificationResponse` or `UNNotificationSettings` crossing actors. If it does, keep that work in the `nonisolated` functions as written (parse there, pass only `PhoneNotificationResponse`), and don't add `@preconcurrency` imports.

- [ ] **Step 6: Run the core tests**

Run: `cd mac/MacDroidCore && swift test`
Expected: PASS.

- [ ] **Step 7: Handoff and commit**

- CHANGELOG bullet: "Task 6: Mac `NotificationRouter` (the only notification delegate; received-file and phone categories together), `PhoneNotifier` (banners with reply/buttons/dismiss, icon attachment), `AppModel` wiring and the `showPhoneNotifications` setting."
- ARCHITECTURE (Mac app section): "`Notifications/`: `NotificationRouter` is the only `UNUserNotificationCenter` delegate; `PhoneNotifier` posts mirrored notifications; `ReceivedNotifier` posts received-file notifications through the router."

```bash
git add mac/MacDroid docs/handoff
git commit -m "feat(mac): post mirrored notifications through one notification router

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Mac UI — Notifications page, muted-apps sheet, settings and Phone page line

**Files:**
- Create: `mac/MacDroid/Views/Notifications/NotificationsView.swift`
- Create: `mac/MacDroid/Views/Notifications/NotificationCard.swift`
- Create: `mac/MacDroid/Views/Notifications/MutedAppsSheet.swift`
- Create: `mac/MacDroid/Views/Shared/AppIconView.swift`
- Modify: `mac/MacDroid/SidebarSection.swift` (Notifications is no longer "coming soon")
- Modify: `mac/MacDroid/Views/MainWindow.swift`
- Modify: `mac/MacDroid/Views/Settings/GeneralSettingsView.swift` (takes the model; Notifications section)
- Modify: `mac/MacDroid/Views/Phone/PairedDeviceView.swift`
- Handoff: CHANGELOG bullet; ARCHITECTURE (views list); STATUS (Notifications is live on the Mac)

**Interfaces:**
- Consumes: Task 6's `AppModel` API; Task 5's `NotificationPresentation`.
- Produces: `GeneralSettingsView(model:)` (Plan 4 adds a Clipboard section here) and `AppIconView(url:size:)`.

- [ ] **Step 1: `AppIconView.swift`**

```swift
import AppKit
import SwiftUI

/// A phone app's icon from the icon cache, or a placeholder.
struct AppIconView: View {
    let url: URL?
    var size: CGFloat = 20

    var body: some View {
        if let url, let image = NSImage(contentsOf: url) {
            Image(nsImage: image)
                .resizable()
                .interpolation(.high)
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: size * 0.22))
        } else {
            Image(systemName: "app.fill")
                .resizable()
                .foregroundStyle(.secondary)
                .frame(width: size, height: size)
        }
    }
}
```

- [ ] **Step 2: `NotificationCard.swift`**

```swift
import MacDroidCore
import SwiftUI

/// One mirrored notification. Hover shows Reply, the app's buttons and ✕ (dismiss on both devices).
struct NotificationCard: View {
    let entry: NotificationEntry
    let model: AppModel
    @State private var hovering = false
    @State private var replying = false
    @State private var replyText = ""

    var body: some View {
        let posted = entry.posted
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(NotificationPresentation.bannerTitle(posted)).font(.headline).lineLimit(1)
                Spacer(minLength: 4)
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    Text(NotificationPresentation.relativeTime(entry.receivedAt, now: context.date))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Button { model.dismissNotification(posted.key) } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .help("Dismiss on both devices")
                    .opacity(hovering ? 1 : 0)
            }
            if !posted.text.isEmpty {
                Text(posted.text).lineLimit(4).foregroundStyle(.secondary)
            }
            if (hovering || replying) && (posted.canReply || !posted.actions.isEmpty) {
                HStack(spacing: 8) {
                    if posted.canReply { Button("Reply") { replying = true } }
                    ForEach(posted.actions.prefix(3), id: \.index) { action in
                        Button(action.title) { model.performNotificationAction(posted.key, index: action.index) }
                    }
                }
                .controlSize(.small)
            }
            if replying {
                HStack {
                    TextField("Reply", text: $replyText)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(sendReply)
                    Button("Send", action: sendReply)
                        .disabled(replyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Button("Cancel") { replying = false; replyText = "" }
                }
                .controlSize(.small)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
    }

    private func sendReply() {
        let text = replyText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        model.replyToNotification(entry.posted.key, text: text)
        replyText = ""
        replying = false
    }
}
```

- [ ] **Step 3: `MutedAppsSheet.swift`**

```swift
import MacDroidCore
import SwiftUI

/// One checkbox per app the phone has seen (spec §3.3). The phone owns the list; changes are requests.
struct MutedAppsSheet: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Muted apps").font(.title2.weight(.semibold))
            Text("Notifications from checked apps stay on your phone. This list syncs with MacDroid on your phone.")
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if model.notifications.apps.isEmpty {
                Text("Apps appear here after their first notification.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 160)
            } else {
                List(model.notifications.apps) { app in
                    Toggle(isOn: Binding(get: { app.muted }, set: { model.setAppMuted(app.packageName, muted: $0) })) {
                        HStack(spacing: 8) {
                            AppIconView(url: model.iconURL(app.iconHash))
                            Text(app.appName)
                        }
                    }
                    .toggleStyle(.checkbox)
                }
                .frame(minHeight: 240)
            }
            if let problem = model.notificationProblem {
                Text(problem).font(.callout).foregroundStyle(.red)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 420)
    }
}
```

- [ ] **Step 4: `NotificationsView.swift`**

```swift
import AppKit
import MacDroidCore
import SwiftUI

/// The Notifications page (spec §3.3, layout A): one collapsible group per app, newest first.
struct NotificationsView: View {
    let model: AppModel
    @State private var collapsed: Set<String> = []
    @State private var showingMutedApps = false

    var body: some View {
        let state = model.notifications
        VStack(spacing: 0) {
            if let notice = NotificationPresentation.pageNotice(state) { BannerView(kind: .warning, message: notice) }
            if model.macNotificationsDenied {
                HStack {
                    BannerView(kind: .warning, message: "MacDroid isn't allowed to show notifications on this Mac.")
                    Button("Open System Settings") {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
                            NSWorkspace.shared.open(url)
                        }
                    }
                    .padding(.trailing, 12)
                    .padding(.top, 12)
                }
            }
            if let problem = model.notificationProblem { BannerView(kind: .error, message: problem) }
            if state.groups.isEmpty {
                ContentUnavailableView("No notifications", systemImage: "bell", description: Text("Notifications from your phone appear here."))
            } else {
                List {
                    ForEach(state.groups) { group in
                        Section {
                            if !collapsed.contains(group.packageName) {
                                ForEach(group.entries) { NotificationCard(entry: $0, model: model) }
                            }
                        } header: {
                            groupHeader(group)
                        }
                    }
                }
                .listStyle(.inset)
            }
        }
        .navigationTitle("Notifications")
        .toolbar {
            ToolbarItemGroup {
                Button("Muted apps…") { showingMutedApps = true }
                Button("Clear all") { model.clearAllNotifications() }.disabled(state.count == 0)
            }
        }
        .sheet(isPresented: $showingMutedApps) { MutedAppsSheet(model: model) }
        .task { await model.refreshNotificationPermission() }
        .onDisappear { model.dismissNotificationProblem() }
    }

    private func groupHeader(_ group: NotificationGroup) -> some View {
        Button {
            if collapsed.contains(group.packageName) { collapsed.remove(group.packageName) } else { collapsed.insert(group.packageName) }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: collapsed.contains(group.packageName) ? "chevron.right" : "chevron.down")
                    .font(.caption.weight(.semibold))
                    .frame(width: 10)
                AppIconView(url: model.iconURL(group.iconHash))
                Text(group.appName).font(.headline)
                Text("\(group.entries.count)").foregroundStyle(.secondary)
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
```

- [ ] **Step 5: Sidebar, main window, settings and Phone page**

In `SidebarSection.swift`, delete the `.notifications` line from `comingSoonDescription`, so only `.clipboard` stays "coming soon".

In `MainWindow.swift`, update `detail(for:)`:

```swift
        case .phone: PhoneView(model: model)
        case .notifications: NotificationsView(model: model)
        case .files: FilesView(model: model)
        case .clipboard: ComingSoonView(section: section)
        case .general: GeneralSettingsView(model: model)
```

In `GeneralSettingsView.swift`:
- Replace `@Bindable var files: FilesSettings` with `let model: AppModel` and `@State private var showingMutedApps = false`.
- Start `body` with `@Bindable var files = model.files` (the existing `$files` bindings keep working).
- After the "Received files" section, add:

```swift
            Section {
                Toggle("Show phone notifications", isOn: Binding(get: { model.showPhoneNotifications }, set: { model.setShowPhoneNotifications($0) }))
                LabeledContent("Apps") {
                    Button("Muted apps…") { showingMutedApps = true }
                }
            } header: {
                Text("Notifications")
            } footer: {
                Text("Notifications from your phone appear in Notification Center. Android hides one-time codes from apps like MacDroid.")
                    .foregroundStyle(.secondary)
            }
```

and after `.formStyle(.grouped)`:

```swift
        .sheet(isPresented: $showingMutedApps) { MutedAppsSheet(model: model) }
```

In `PairedDeviceView.swift`'s "Details" section, after the Battery row:

```swift
                if let line = NotificationPresentation.phonePageLine(model.notifications) {
                    LabeledContent("Notifications", value: line)
                }
```

- [ ] **Step 6: Build**

Run: `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build 2>&1 | grep -E "warning:|error:|BUILD" | grep -v "appintentsmetadataprocessor"`
Expected: `** BUILD SUCCEEDED **`, with no compiler warnings.

- [ ] **Step 7: Handoff and commit**

- CHANGELOG bullet: "Task 7: Mac Notifications page (grouped by app, hover Reply/buttons/✕, Clear all), Muted apps sheet, Settings → Notifications, Phone page line."
- ARCHITECTURE: add `Notifications (NotificationsView, NotificationCard, MutedAppsSheet)` to the Views list, and note that the Notifications sidebar item is live.
- STATUS: under "Known limitations", change "Notifications and Clipboard … placeholders" to say that only Clipboard is still a placeholder (Plan 3 in progress).

```bash
git add mac/MacDroid docs/handoff
git commit -m "feat(mac): Notifications page, muted apps and settings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Android platform — notification listener and bridge

**Files:**
- Create in `android/app/src/main/java/dev/macdroid/app/platform/notifications/`: `NotificationText.kt`, `NotificationReader.kt`, `AppIcons.kt`, `SharedPrefsNotificationSettings.kt`, `NotificationBridge.kt` and `MacDroidNotificationListener.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/dev/macdroid/app/MacDroidApp.kt` (`notificationBridge`)
- Modify: `android/app/src/main/java/dev/macdroid/app/platform/Permissions.kt` (`hasNotificationAccess`)
- Test: `android/app/src/test/java/dev/macdroid/app/NotificationTextTest.kt`
- Handoff: CHANGELOG bullet; ARCHITECTURE (Android app: `platform/notifications/`); DEV-GUIDE (granting notification access while developing)

**Interfaces:**
- Consumes:
  - Task 3's `NotificationForwarder`, `PhoneNotification`, `AppIcon`, `NotificationSettingsStore`, `NotificationSettings` and `NotificationCommand`.
  - Task 2's `macSupports` and `Features`.
- Produces:
  - `AppGraph.notificationBridge: NotificationBridge`, with:
    - `forwarder: NotificationForwarder`
    - `start()` and `refreshAccess()`
    - `listenerConnected(service)` and `listenerDisconnected()`
    - `posted(sbn, ranking)` and `removed(key)`
  - `Permissions.hasNotificationAccess(context): Boolean`.
  - `NotificationText.pick(conversationTitle, title, lastMessage, bigText, text): Pair<String, String>`.

- [ ] **Step 1: Write the failing test**

`NotificationTextTest.kt`:

```kotlin
package dev.macdroid.app

import dev.macdroid.app.platform.notifications.NotificationText
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {
    @Test fun conversationTitleAndNewestMessageWin() {
        assertEquals("Family" to "Dinner at 8", NotificationText.pick("Family", "Mom", "Dinner at 8", "old big text", "2 new messages"))
    }

    @Test fun fallsBackToTitleBigTextThenText() {
        assertEquals("Amazon" to "Your order shipped and arrives Tuesday", NotificationText.pick(null, "Amazon", null, "Your order shipped and arrives Tuesday", "Your order shipped"))
        assertEquals("Amazon" to "Your order shipped", NotificationText.pick("  ", "Amazon", "", null, "Your order shipped"))
    }

    @Test fun missingEverythingIsEmptyAndTrimmed() {
        assertEquals("" to "", NotificationText.pick(null, null, null, null, null))
        assertEquals("Mom" to "Hi", NotificationText.pick(null, " Mom ", null, null, " Hi\n"))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'dev.macdroid.app.NotificationTextTest'`
Expected: compilation fails.

- [ ] **Step 3: `NotificationText.kt`**

```kotlin
package dev.macdroid.app.platform.notifications

/** Picks what the Mac shows (spec §8.5). */
object NotificationText {
    /** Title: the conversation title, else EXTRA_TITLE. Text: the newest MessagingStyle message, else EXTRA_BIG_TEXT, else EXTRA_TEXT. */
    fun pick(
        conversationTitle: CharSequence?,
        title: CharSequence?,
        lastMessage: CharSequence?,
        bigText: CharSequence?,
        text: CharSequence?,
    ): Pair<String, String> {
        val chosenTitle = listOf(conversationTitle, title).firstNotNullOfOrNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        val chosenText = listOf(lastMessage, bigText, text).firstNotNullOfOrNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        return chosenTitle to chosenText
    }
}
```

- [ ] **Step 4: Run the test**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'dev.macdroid.app.NotificationTextTest'`
Expected: PASS.

- [ ] **Step 5: `NotificationReader.kt`**

```kotlin
package dev.macdroid.app.platform.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dev.macdroid.core.notifications.PhoneNotification
import dev.macdroid.core.protocol.NotificationButton

/** Reads a StatusBarNotification into the pure [PhoneNotification] the forwarder understands (spec §3.1, §8.5). */
object NotificationReader {
    fun read(context: Context, sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?): PhoneNotification {
        val n = sbn.notification
        val extras = n.extras
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages
        val (title, text) = NotificationText.pick(
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE),
            messages?.lastOrNull()?.text,
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        val buttons = n.actions.orEmpty().withIndex()
            .filter { (_, action) -> action.remoteInputs.isNullOrEmpty() && !action.title.isNullOrBlank() }
            .map { (index, action) -> NotificationButton(index, action.title.toString()) }
        val importance = ranking?.importance ?: NotificationManager.IMPORTANCE_UNSPECIFIED
        return PhoneNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName(context, sbn.packageName),
            title = title,
            text = text,
            postedAt = sbn.postTime,
            actions = buttons,
            canReply = replyAction(n) != null,
            ongoing = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0,
            groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            minImportance = importance != NotificationManager.IMPORTANCE_UNSPECIFIED && importance <= NotificationManager.IMPORTANCE_MIN,
        )
    }

    /** The action with a free-form reply box, if any. */
    fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true }

    private fun appName(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
```

- [ ] **Step 6: `AppIcons.kt` and `SharedPrefsNotificationSettings.kt`**

```kotlin
package dev.macdroid.app.platform.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import dev.macdroid.core.notifications.AppIcon
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** App icons as 96×96 PNGs with a 16-hex hash (spec §2.2), rendered once per app per process. */
class AppIcons(private val context: Context) {
    private data class Entry(val icon: AppIcon?)
    private val cache = ConcurrentHashMap<String, Entry>()

    fun icon(packageName: String): AppIcon? =
        cache.getOrPut(packageName) { Entry(runCatching { render(packageName) }.getOrNull()) }.icon

    private fun render(packageName: String): AppIcon {
        val drawable = context.packageManager.getApplicationIcon(packageName).mutate()
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, SIZE, SIZE)
        drawable.draw(Canvas(bitmap))
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        val hash = MessageDigest.getInstance("SHA-256").digest(png).take(8).joinToString("") { "%02x".format(it) }
        return AppIcon(hash, Base64.getEncoder().encodeToString(png))
    }

    private companion object {
        const val SIZE = 96
    }
}
```

```kotlin
package dev.macdroid.app.platform.notifications

import android.content.Context
import dev.macdroid.core.notifications.NotificationSettings
import dev.macdroid.core.notifications.NotificationSettingsStore
import dev.macdroid.core.protocol.MessageCodec

/** The phone-owned notification settings (master switch, apps seen, mutes) as JSON in SharedPreferences. */
class SharedPrefsNotificationSettings(context: Context) : NotificationSettingsStore {
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)

    override fun load(): NotificationSettings = prefs.getString(KEY, null)
        ?.let { runCatching { MessageCodec.json.decodeFromString(NotificationSettings.serializer(), it) }.getOrNull() }
        ?: NotificationSettings()

    override fun save(settings: NotificationSettings) {
        prefs.edit().putString(KEY, MessageCodec.json.encodeToString(NotificationSettings.serializer(), settings)).apply()
    }

    private companion object {
        const val KEY = "settings"
    }
}
```

- [ ] **Step 7: `NotificationBridge.kt`**

```kotlin
package dev.macdroid.app.platform.notifications

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.macdroid.app.platform.Permissions
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.notifications.NotificationCommand
import dev.macdroid.core.notifications.NotificationForwarder
import dev.macdroid.core.protocol.Features
import dev.macdroid.core.protocol.NotificationErrorReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Connects the notification listener, the connection and the forwarder (spec §3.1).
 * Every callback runs in order on one worker, off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationBridge(
    private val context: Context,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
) {
    val forwarder = NotificationForwarder(
        ownPackage = context.packageName,
        store = SharedPrefsNotificationSettings(context),
        send = connection::send,
        iconFor = AppIcons(context)::icon,
    )
    private val worker = Dispatchers.Default.limitedParallelism(1)
    @Volatile private var listener: NotificationListenerService? = null
    @Volatile private var macReady = false

    fun start() {
        scope.launch(worker) { refreshAccess() }
        scope.launch(worker) {
            connection.state.map { it.macSupports(Features.NOTIFICATIONS) }.distinctUntilChanged().collect { ready ->
                macReady = ready
                if (ready) forwarder.connected(activeKeys()) else forwarder.disconnected()
            }
        }
        scope.launch(worker) {
            connection.incoming.collect { message ->
                val command = forwarder.handle(message) ?: return@collect
                execute(command)?.let { forwarder.reportError(command.key, it) }
            }
        }
    }

    fun refreshAccess() {
        forwarder.accessChanged(Permissions.hasNotificationAccess(context))
    }

    fun listenerConnected(service: NotificationListenerService) {
        listener = service
        scope.launch(worker) {
            refreshAccess()
            if (macReady) forwarder.connected(activeKeys()) // a sync now that the active list is readable
        }
    }

    fun listenerDisconnected() {
        listener = null
        scope.launch(worker) { refreshAccess() }
    }

    fun posted(sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?) {
        scope.launch(worker) { forwarder.posted(NotificationReader.read(context, sbn, ranking)) }
    }

    fun removed(key: String) {
        scope.launch(worker) { forwarder.removed(key) }
    }

    private fun activeKeys(): Set<String> =
        runCatching { listener?.activeNotifications?.map { it.key }?.toSet() }.getOrNull().orEmpty()

    /** Carries out a Mac request. Returns the error to report, or null on success. */
    private fun execute(command: NotificationCommand): NotificationErrorReason? {
        val service = listener ?: return NotificationErrorReason.Failed
        val sbn = runCatching { service.activeNotifications }.getOrNull()?.firstOrNull { it.key == command.key }
        if (sbn == null) return if (command is NotificationCommand.Dismiss) null else NotificationErrorReason.Gone
        return try {
            when (command) {
                is NotificationCommand.Dismiss -> service.cancelNotification(command.key)
                is NotificationCommand.Action -> {
                    val action = sbn.notification.actions?.getOrNull(command.actionIndex) ?: return NotificationErrorReason.Gone
                    action.actionIntent.send()
                }
                is NotificationCommand.Reply -> {
                    val action = NotificationReader.replyAction(sbn.notification) ?: return NotificationErrorReason.Failed
                    val inputs = action.remoteInputs
                    val freeForm = inputs.first { it.allowFreeFormInput }
                    val results = Bundle().apply { putCharSequence(freeForm.resultKey, command.text) }
                    val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    RemoteInput.addResultsToIntent(inputs, fillIn, results)
                    action.actionIntent.send(service, 0, fillIn)
                }
            }
            null
        } catch (e: PendingIntent.CanceledException) {
            NotificationErrorReason.Failed
        } catch (e: SecurityException) {
            NotificationErrorReason.Failed
        }
    }
}
```

- [ ] **Step 8: `MacDroidNotificationListener.kt`**

```kotlin
package dev.macdroid.app.platform.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.macdroid.app.graph

/** The system binds this once the user grants "Notification access" (spec §3.1). */
class MacDroidNotificationListener : NotificationListenerService() {
    private val bridge get() = graph.notificationBridge

    override fun onListenerConnected() = bridge.listenerConnected(this)

    override fun onListenerDisconnected() = bridge.listenerDisconnected()

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val ranking = Ranking().takeIf { rankingMap.getRanking(sbn.key, it) }
        bridge.posted(sbn, ranking)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) = bridge.removed(sbn.key)
}
```

Note: if the compiler reports a nullability mismatch on these overrides (it depends on the SDK's annotations), match the SDK's declared nullability and return early on `null`.

- [ ] **Step 9: Permissions, graph and manifest**

`Permissions.kt`, add:

```kotlin
    /** The user granted MacDroid "Notification access" (spec §3.1). */
    fun hasNotificationAccess(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
```

`MacDroidApp.kt` (`AppGraph`), after `transferEngine`:

```kotlin
    /** Mirrors notifications to the Mac (spec §3.1); runs for the whole process like the transfer engine. */
    val notificationBridge = NotificationBridge(context, connectionManager, appScope).also { it.start() }
```

`AndroidManifest.xml`, add inside `<manifest>` (after the permissions). This makes launcher apps' names and icons visible without `QUERY_ALL_PACKAGES`:

```xml
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>
```

and inside `<application>`:

```xml
        <service
            android:name=".platform.notifications.MacDroidNotificationListener"
            android:exported="true"
            android:label="MacDroid"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
```

- [ ] **Step 10: Run the Android gates**

Run: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, with no `w:` warnings.

- [ ] **Step 11: Handoff and commit**

- CHANGELOG bullet: "Task 8: Android `MacDroidNotificationListener` + `NotificationBridge` (reads notifications, carries out reply/action/dismiss, reports `gone`/`failed`), icons, settings persistence, launcher `<queries>`."
- ARCHITECTURE (Android app): "`platform/notifications/`: the listener service, `NotificationBridge` (listener ↔ connection ↔ `NotificationForwarder`), `NotificationReader`, `AppIcons`, settings store."
- DEV-GUIDE gotcha: "Notification access is granted by the owner in Settings → Notifications → Device & app notifications (or the setup step). Never grant it from adb automation. The listener only runs once access is granted."

```bash
git add android docs/handoff
git commit -m "feat(android): notification listener and bridge

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Android UI — Notifications tab and the setup step

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/ui/presentation/NotificationsPresentation.kt`
- Create: `android/app/src/main/java/dev/macdroid/app/ui/notifications/NotificationsViewModel.kt`
- Create: `android/app/src/main/java/dev/macdroid/app/ui/notifications/NotificationsScreen.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/main/MainScreen.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/setup/SetupFlow.kt`, `SetupViewModel.kt`, `SetupScreen.kt`
- Test: `android/app/src/test/java/dev/macdroid/app/NotificationsPresentationTest.kt`, `SetupFlowTest.kt`
- Handoff: CHANGELOG bullet; ARCHITECTURE (Notifications tab live; setup step order); STATUS

**Interfaces:**
- Consumes:
  - Task 8's `AppGraph.notificationBridge` and `Permissions.hasNotificationAccess`.
  - Task 3's `NotificationsState` and `SeenApp`.
- Produces:
  - `NotificationsPresentation.topCard(state, macName, macSupports, connected): TopCard`, where `TopCard(title, subtitle, showsSwitch, actionLabel)`.
  - `NotificationsPresentation.appSubtitle(app, today)`.
  - `SetupInputs.hasNotificationAccess`.

- [ ] **Step 1: Write the failing tests**

`NotificationsPresentationTest.kt`:

```kotlin
package dev.macdroid.app

import dev.macdroid.app.ui.presentation.NotificationsPresentation
import dev.macdroid.core.notifications.NotificationsState
import dev.macdroid.core.notifications.SeenApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsPresentationTest {
    private val on = NotificationsState(forwarding = true, access = true, apps = emptyList())

    @Test fun forwardingCardNamesTheMac() {
        val card = NotificationsPresentation.topCard(on, "Venusai's MacBook Air", macSupports = true, connected = true)
        assertEquals("Show on your Mac", card.title)
        assertEquals("Forwarding to Venusai's MacBook Air", card.subtitle)
        assertTrue(card.showsSwitch)
    }

    @Test fun offCardSaysNotificationsStayHere() {
        val card = NotificationsPresentation.topCard(on.copy(forwarding = false), "Mac", macSupports = true, connected = false)
        assertEquals("Off — notifications stay on this phone", card.subtitle)
    }

    @Test fun accessOffAsksToTurnItOn() {
        val card = NotificationsPresentation.topCard(on.copy(access = false), "Mac", macSupports = true, connected = true)
        assertEquals("Notification access is off", card.title)
        assertEquals("Turn on", card.actionLabel)
        assertFalse(card.showsSwitch)
    }

    @Test fun oldMacAsksForAnUpdate() {
        val card = NotificationsPresentation.topCard(on, "Mac", macSupports = false, connected = true)
        assertEquals("Update MacDroid on your Mac to use this", card.title)
        assertFalse(card.showsSwitch)
    }

    @Test fun appSubtitles() {
        val today = 20_374L
        assertEquals("Muted", NotificationsPresentation.appSubtitle(SeenApp("a", "A", muted = true, countDay = today, count = 4), today))
        assertEquals("12 today", NotificationsPresentation.appSubtitle(SeenApp("a", "A", countDay = today, count = 12), today))
        assertEquals("Shown on your Mac", NotificationsPresentation.appSubtitle(SeenApp("a", "A", countDay = today - 1, count = 3), today))
    }
}
```

In `SetupFlowTest.kt`:
- Add `hasNotificationAccess: Boolean = false` to the `inputs(...)` helper, passed as the new last constructor argument `hasNotificationAccess = hasNotificationAccess`.
- Change `firstRunSkipsPermissionsTheOsDoesNotHave`: the Notifications step now always shows on a first run, because notification access exists on every Android version:

```kotlin
    @Test fun firstRunSkipsPermissionsTheOsDoesNotHave() {
        assertEquals(
            listOf(SetupStep.Welcome, SetupStep.PairMac, SetupStep.Notifications, SetupStep.Files, SetupStep.Battery),
            SetupFlow.steps(inputs(needsLocalNetwork = false, needsNotifications = false)),
        )
    }
```

- In `advancesAsEachStepCompletes`, change the last assertion to also grant access:

```kotlin
        assertNull(SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true, paired = true, hasNotifications = true, hasNotificationAccess = true, batteryDone = true, filesStepDone = true)))
```

- And add:

```kotlin
    @Test fun notificationsStepNeedsBothThePermissionAndAccess() {
        val base = inputs(welcomeDone = true, hasLocalNetwork = true, paired = true)
        assertEquals(SetupStep.Notifications, SetupFlow.current(base.copy(hasNotifications = true)))
        assertEquals(SetupStep.Notifications, SetupFlow.current(base.copy(hasNotificationAccess = true)))
        assertEquals(SetupStep.Files, SetupFlow.current(base.copy(hasNotifications = true, hasNotificationAccess = true)))
        assertEquals(SetupStep.Files, SetupFlow.current(base.copy(needsNotifications = false, hasNotificationAccess = true)))
        assertEquals(SetupStep.Files, SetupFlow.current(base.copy(notificationsSkipped = true)))
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: compilation fails (`NotificationsPresentation`, `hasNotificationAccess`).

- [ ] **Step 3: Implement the presentation and the setup flow**

`NotificationsPresentation.kt`:

```kotlin
package dev.macdroid.app.ui.presentation

import dev.macdroid.core.notifications.NotificationsState
import dev.macdroid.core.notifications.SeenApp

/** Text for the Notifications tab (spec §3.3). */
object NotificationsPresentation {
    data class TopCard(val title: String, val subtitle: String, val showsSwitch: Boolean, val actionLabel: String?)

    const val FOOTNOTE = "Apps appear here after their first notification. Mutes sync with the Mac."
    const val OTP_NOTE = "Android hides one-time codes from apps like MacDroid, so login codes show without the code."

    fun topCard(state: NotificationsState, macName: String?, macSupports: Boolean, connected: Boolean): TopCard = when {
        !state.access -> TopCard(
            "Notification access is off",
            "Allow MacDroid to read notifications so it can show them on your Mac.",
            showsSwitch = false,
            actionLabel = "Turn on",
        )
        connected && !macSupports -> TopCard(
            "Update MacDroid on your Mac to use this",
            "The MacDroid on your Mac can't show phone notifications yet.",
            showsSwitch = false,
            actionLabel = null,
        )
        else -> TopCard(
            "Show on your Mac",
            if (state.forwarding) "Forwarding to ${macName ?: "your Mac"}" else "Off — notifications stay on this phone",
            showsSwitch = true,
            actionLabel = null,
        )
    }

    fun appSubtitle(app: SeenApp, today: Long): String = when {
        app.muted -> "Muted"
        app.countDay == today && app.count > 0 -> "${app.count} today"
        else -> "Shown on your Mac"
    }
}
```

`SetupFlow.kt`:
- Add `val hasNotificationAccess: Boolean = false,` as the last `SetupInputs` property, with the KDoc "MacDroid has notification access (spec §3.1).".
- In the first-run list, replace `if (i.needsNotifications) add(SetupStep.Notifications)` with `add(SetupStep.Notifications)`.
- Change the `isDone` case to:

```kotlin
        SetupStep.Notifications -> i.notificationsSkipped || ((!i.needsNotifications || i.hasNotifications) && i.hasNotificationAccess)
```

`SetupViewModel.kt`:
- Add `val hasNotificationAccess: Boolean,` to `Local`.
- Read it in `readLocal()` as `hasNotificationAccess = Permissions.hasNotificationAccess(app),`.
- Pass `hasNotificationAccess = l.hasNotificationAccess,` in `inputs(...)`.
- Also call `graph.notificationBridge.refreshAccess()` at the start of `refresh()`, so the forwarder learns about a grant as soon as the user returns.

- [ ] **Step 4: Run the tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: The setup step UI**

In `SetupScreen.kt`:
- Pass the state: `SetupStep.Notifications -> NotificationsStep(needsPermission = Permissions.needsNotifications && !Permissions.hasNotifications(LocalContext.current), onResult = viewModel::refresh, onSkip = viewModel::skipNotifications)`.
- Replace `NotificationsStep` with:

```kotlin
@Composable
private fun NotificationsStep(needsPermission: Boolean, onResult: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val openAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onResult()
        if (!Permissions.hasNotificationAccess(context)) openAccess()
    }
    StepLayout(
        icon = Icons.Rounded.NotificationsActive,
        title = "See phone notifications on your Mac",
        body = "Reply to messages and clear notifications from your Mac. MacDroid only sends them to your paired Mac — never to the internet.",
        primaryLabel = "Allow notification access",
        onPrimary = {
            when {
                needsPermission -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                !Permissions.hasNotificationAccess(context) -> openAccess()
                else -> onResult()
            }
        },
        secondaryLabel = "Not now",
        onSecondary = onSkip,
    )
}
```

(`AppNavHost` already calls `setupViewModel.refresh()` on resume, so coming back from the system screen moves the wizard on.)

- [ ] **Step 6: The Notifications tab**

`NotificationsViewModel.kt`:

```kotlin
package dev.macdroid.app.ui.notifications

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.notifications.NotificationsState
import dev.macdroid.core.protocol.Features
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class NotificationsViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(val notifications: NotificationsState, val macName: String?, val connected: Boolean, val macSupports: Boolean)

    private val graph = application.graph
    private val bridge = graph.notificationBridge

    val state: StateFlow<UiState> = combine(bridge.forwarder.state, graph.connectionManager.state, graph.pairingStore.paired) { n, connection, mac ->
        UiState(
            notifications = n,
            macName = (connection as? ConnectionState.Connected)?.macName ?: mac?.name,
            connected = connection is ConnectionState.Connected,
            macSupports = connection.macSupports(Features.NOTIFICATIONS),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UiState(bridge.forwarder.state.value, graph.pairingStore.paired.value?.name, connected = false, macSupports = false),
    )

    fun refreshAccess() = bridge.refreshAccess()
    fun setForwarding(on: Boolean) = bridge.forwarder.setForwarding(on)
    fun setShown(packageName: String, shown: Boolean) = bridge.forwarder.setMuted(packageName, muted = !shown)
}
```

`NotificationsScreen.kt`:

```kotlin
package dev.macdroid.app.ui.notifications

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.macdroid.app.ui.presentation.NotificationsPresentation
import dev.macdroid.core.notifications.SeenApp
import java.time.LocalDate

/** The Notifications tab: a control panel, not a history (spec §3.3). */
@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAccess() }
    val today = remember { LocalDate.now().toEpochDay() }
    val card = NotificationsPresentation.topCard(state.notifications, state.macName, state.macSupports, state.connected)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Notifications", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp)) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(card.title, style = MaterialTheme.typography.titleMedium)
                        Text(card.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (card.showsSwitch) Switch(checked = state.notifications.forwarding, onCheckedChange = viewModel::setForwarding)
                    card.actionLabel?.let { label ->
                        Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text(label) }
                    }
                }
            }
        }
        item {
            Text("Apps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        }
        items(state.notifications.apps, key = { it.packageName }) { app ->
            AppRow(app, NotificationsPresentation.appSubtitle(app, today)) { shown -> viewModel.setShown(app.packageName, shown) }
        }
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(NotificationsPresentation.FOOTNOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(NotificationsPresentation.OTP_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AppRow(app: SeenApp, subtitle: String, onShownChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val icon = remember(app.packageName) {
        runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
        } else {
            Spacer(Modifier.size(32.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.appName, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = !app.muted, onCheckedChange = onShownChange)
    }
}
```

In `MainScreen.kt`, replace the Notifications `ComingSoonScreen(...)` composable with `NotificationsScreen()` (import `dev.macdroid.app.ui.notifications.NotificationsScreen`).

- [ ] **Step 7: Run the Android gates**

Run: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, with no warnings.

- [ ] **Step 8: Handoff and commit**

- CHANGELOG bullet: "Task 9: Android Notifications tab (master switch, apps with switches and daily counts, access card), setup step asks for notification access (6 steps still)."
- ARCHITECTURE (Android): Notifications tab live (`ui/notifications/`); the setup Notifications step asks for `POST_NOTIFICATIONS` and notification access.
- STATUS: under "Known limitations", note that only Clipboard is still a placeholder on both sides.

```bash
git add android docs/handoff
git commit -m "feat(android): Notifications tab and notification access in setup

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Cross-platform — a notification and a reply through the real server and client

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidDevServer/main.swift`
- Modify: `android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt`
- Modify: `scripts/cross-platform-test.sh`
- Handoff: CHANGELOG bullet; DEV-GUIDE (what the cross-platform script now covers)

**Interfaces:**
- Consumes: `PhoneNotifications` (Task 5) and `NotificationForwarder` (Task 3).
- Produces these dev-server log lines:
  - `NOTIFICATION posted key=<key> title=<title> banner=<true|false>`
  - `NOTIFICATION removed key=<key>`
  - `NOTIFICATION apps count=<n> access=<bool>`
  - `NOTIFICATION replied key=<key>`

- [ ] **Step 1: Dev server**

In `main.swift`, after `await transfers.attach(to: hub)`:

```swift
let notifications = PhoneNotifications(
    icons: IconCache(directory: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "macdroid-devserver-icons-\(UUID().uuidString)")),
    send: { try await hub.send($0) }
)
await notifications.attach(to: hub)

// Replies "On my way" to the first notification that can be replied to (exercises Mac → phone).
let notificationPrinter = Task {
    var replied = false
    for await event in notifications.events {
        switch event {
        case .show(let posted, let banner):
            print("NOTIFICATION posted key=\(posted.key) title=\(posted.title) banner=\(banner)")
            if posted.canReply, !replied {
                replied = true
                await notifications.reply(key: posted.key, text: "On my way")
                print("NOTIFICATION replied key=\(posted.key)")
            }
        case .remove(let keys):
            for key in keys { print("NOTIFICATION removed key=\(key)") }
        case .problem(let text):
            print("NOTIFICATION problem \(text)")
        }
    }
}

let appsPrinter = Task {
    for await state in notifications.states where state.phoneAccess != nil {
        print("NOTIFICATION apps count=\(state.apps.count) access=\(state.phoneAccess ?? false)")
    }
}
```

At the end, add `notificationPrinter.cancel()` and `appsPrinter.cancel()` next to the other `.cancel()` calls.

- [ ] **Step 2: Kotlin side**

In `CrossPlatformTest.pairsConnectsAndReportsBatteryToSwiftServer`, collect the incoming messages before `manager.start()` (the flow has no replay):

```kotlin
        val received = java.util.concurrent.CopyOnWriteArrayList<Message>()
        val collector = scope.launch { manager.incoming.collect { received.add(it) } }
```

After the battery assertion and `delay(500)`, before the file section:

```kotlin
            exchangeANotification(manager, received)
```

and cancel `collector` in the `finally`. Add the helper:

```kotlin
    /** The phone forwards a chat notification; the dev server replies "On my way"; then the phone removes it. */
    private suspend fun exchangeANotification(manager: ConnectionManager, received: List<Message>) {
        val forwarder = NotificationForwarder("dev.macdroid.test", InMemoryNotificationSettingsStore(), manager::send, { null })
        forwarder.accessChanged(true)
        forwarder.connected(emptySet())
        forwarder.posted(PhoneNotification("k1", "com.example.chat", "Chat", "Mom", "Did you eat?", System.currentTimeMillis(), emptyList(), canReply = true))
        withTimeout(10_000) {
            while (received.none { it is Message.NotificationReply }) delay(50)
        }
        val reply = received.filterIsInstance<Message.NotificationReply>().single().body
        assertEquals("k1", reply.key)
        assertEquals("On my way", reply.text)
        assertEquals(NotificationCommand.Reply("k1", "On my way"), forwarder.handle(Message.NotificationReply(reply)))
        forwarder.removed("k1")
        delay(300)
    }
```

(Imports: `dev.macdroid.core.notifications.*`, `kotlinx.coroutines.launch`.)

- [ ] **Step 3: Script assertions**

In `scripts/cross-platform-test.sh`, before the `cmp` lines, add:

```bash
grep -q 'NOTIFICATION apps count=1 access=true' "$LOG" || { echo "the Mac never got the phone's app list"; cat "$LOG"; exit 1; }
grep -q 'NOTIFICATION posted key=k1 title=Mom banner=true' "$LOG" || { echo "the Mac never got the notification"; cat "$LOG"; exit 1; }
grep -q 'NOTIFICATION replied key=k1' "$LOG" || { echo "the Mac never replied"; cat "$LOG"; exit 1; }
grep -q 'NOTIFICATION removed key=k1' "$LOG" || { echo "the Mac never saw the removal"; cat "$LOG"; exit 1; }
```

- [ ] **Step 4: Run it**

Run: `./scripts/cross-platform-test.sh`
Expected: `cross-platform test passed` (about a minute).

- [ ] **Step 5: Handoff and commit**

- CHANGELOG bullet: "Task 10: cross-platform script now forwards a notification, gets a reply from the Swift server, and removes it."
- DEV-GUIDE: update the cross-platform comment to "pairing, battery, a notification with a reply, and 24 MiB files both ways with a mid-file disconnect".

```bash
git add mac/MacDroidCore android/core scripts docs/handoff
git commit -m "test: notifications in the cross-platform run

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Versions, final gates, handoff and the owner's checklist

**Files:**
- Modify: `mac/project.yml` (0.4.0 / 4), `android/app/build.gradle.kts` (0.4.0 / 4)
- Create: `docs/superpowers/plans/2026-10-13-macdroid-notifications-clipboard-followups.md`
- Modify: `docs/handoff/CHANGELOG.md`, `STATUS.md`, `ROADMAP.md`, `README.md`

- [ ] **Step 1: Bump the versions**

- `mac/project.yml`: `CFBundleShortVersionString: "0.4.0"` and `CFBundleVersion: "4"`.
- `android/app/build.gradle.kts`: `versionCode = 4` and `versionName = "0.4.0"`.

- [ ] **Step 2: Run every gate**

Run, in order:
- `cd mac/MacDroidCore && swift test`
- `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build`
- `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
- `./scripts/cross-platform-test.sh`

Expected: all pass. The Mac build has no compiler warnings, and Gradle has no `w:` lines.

- [ ] **Step 3: Follow-ups file with the owner's checklist**

Create `docs/superpowers/plans/2026-10-13-macdroid-notifications-clipboard-followups.md`:

```markdown
# Notifications + clipboard (Plans 3 and 4) — follow-ups

## Owner's hands-on checklist — notifications (spec §6)
Install Android 0.4.0 and build the Mac app (DEV-GUIDE). Grant notification access in the setup step (or Notifications tab → Turn on).
- [ ] A WhatsApp message arrives on the Mac as a banner: "WhatsApp · <phone>", with the title, the text and the app icon.
- [ ] Reply from the banner → the reply is sent from the phone.
- [ ] Reply from the Notifications page (hover → Reply) → sent.
- [ ] Press a notification button (e.g. "Mark as read") from the Mac → it happens on the phone.
- [ ] Dismiss on the Mac (✕ or close the banner) → gone on the phone. Dismiss on the phone → gone on the Mac.
- [ ] Mute an app in Muted apps… on the Mac → its switch turns off in the phone's Notifications tab; and the reverse.
- [ ] Turn off "Show on your Mac" on the phone → nothing arrives; the Mac page and the Phone page say so.
- [ ] Revoke notification access → the phone tab shows "Notification access is off — Turn on"; the Mac says so.
- [ ] Disconnect (Wi-Fi off), clear notifications on the phone, reconnect → the Mac's list tidies up.
- [ ] A busy group chat → after 10 in 5 s, no more banners, but the page keeps filling.
- [ ] Click a banner → MacDroid opens on the Notifications page.
- [ ] A login code (OTP) → shows without the code (expected, Android 15+).

## Deferred / rulings
(Execution rulings and deferred review findings go here.)
```

- [ ] **Step 4: Handoff**

- CHANGELOG: change the in-progress entry's heading to `## <date> — Plan 3: notifications (done on feat/notifications-clipboard)`, and add a bullet: "Task 11: versions 0.4.0/4; all gates and the cross-platform run pass; owner checklist in the follow-ups file."
- STATUS:
  - "Branches": add `feat/notifications-clipboard`: Plan 3 done, Plan 4 next; not merged.
  - "In flight": Plan 3 done (pending the owner's checklist); Plan 4 next.
- ROADMAP: under "Next: Plans 3 and 4", mark step 2's Plan 3 as written and executed, and state that Plan 4 is next.
- README: round table, Plan 3 → "Done on `feat/notifications-clipboard`; owner check after Plan 4".

- [ ] **Step 5: Commit**

```bash
git add mac/project.yml android/app/build.gradle.kts docs
git commit -m "chore: Plan 3 done — versions 0.4.0, handoff and owner checklist

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
