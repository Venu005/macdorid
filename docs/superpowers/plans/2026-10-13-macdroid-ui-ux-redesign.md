# MacDroid UI/UX Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the scaffold UIs with a native-feeling Mac app (menu-bar popover + sidebar window) and a Material 3 Android app (pairing-centred setup wizard + 5-tab bottom navigation), with no change to connection behaviour.

**Architecture:**
- **Pure presentation logic** is unit-tested:
  - Mac: `StatusPresentation` in `MacDroidCore`.
  - Android: `StatusPresentation` and `SetupFlow` in the app's `ui` packages.
- **Views stay thin.** They read state from `AppModel` (Mac) or small `AndroidViewModel`s (Android).
- **Mac windowing:**
  - The `MenuBarExtra` label view owns opening the `Window("main")` scene: at launch, on `WindowRequest`, and on reopen through `AppDelegate`.
  - The activation policy follows the window.
- **Android navigation:** `navigation-compose`, with a top-level `setup`/`main` switch driven by `SetupFlow`, and a nested tab `NavHost` inside `main`.

**Tech Stack:**
- **Mac:** Swift 6, SwiftUI (macOS 14+: `NavigationSplitView`, `ContentUnavailableView`, `@Bindable`), ServiceManagement, XcodeGen.
- **Android:** Kotlin 2.4.21, Compose BOM 2026.09.00 (Material 3, `material-icons-extended`), `navigation-compose` 2.10.2, CameraX + ML Kit (existing).

**Spec:** `docs/superpowers/specs/2026-10-13-macdroid-ui-ux-redesign-design.md` (builds on `docs/superpowers/specs/2026-10-12-macdroid-round1-design.md`).

## Global Constraints

- **Visual direction is native on each platform:**
  - Mac uses system controls, SF Symbols, `Form` with `.formStyle(.grouped)`, and system colours.
  - Android uses Material 3 with dynamic colour on API 31+ and a static fallback palette on API 29–30. Dark mode follows the system.
- **No changes to connection or protocol behaviour.**
  - In `mac/MacDroidCore`, the only change allowed is the new file `Sources/MacDroidCore/Presentation/StatusPresentation.swift` (pure, additive).
  - `android/core` is not modified.
- **Mac app behaviour:**
  - Always runs in the menu bar.
  - Main window id is `"main"`. Default size 720×480, minimum 600×420.
  - Activation policy is `.regular` while the main window is open, but only if "Show MacDroid in the Dock while its window is open" is on (the default). Otherwise it is `.accessory`.
  - Relaunching the app while it is running opens the window.
  - At launch the window opens unless the app was launched as a login item and a phone is already paired.
- **Mac menu-bar symbols:** `iphone.slash` when not paired, `iphone` when paired but not connected, `iphone.radiowaves.left.and.right` when connected.
- **Mac sidebar order:** Phone, Notifications, Clipboard, Files, then a "MacDroid" section with General, Security, About. Notifications, Clipboard and Files show a "Coming in a later update" page.
- **Mac warning vs. error state:**
  - Persistent warnings (keychain unavailable, server stopped, Bonjour failure) are separate from transient errors and are never cleared by pairing.
  - Pairing is disabled while a temporary identity is in use.
- **Mac pairing QR code:** 240 pt, countdown format `m:ss`, a "New code" button, and an expired state.
- **Android bottom tabs, in order:** Home, Notifications, Clipboard, Files, Settings. The not-yet-built tabs show "Coming in the next update".
- **Android setup step order:** Welcome (first run only) → Local network (API 37+) → Pair with your Mac → Notifications (API 33+, skippable) → Run in background (first setup only).
  - After setup, a missing local-network permission or pairing routes to only that step. A missing notification permission does not force setup.
  - After a successful pairing, the success state shows for 1 s before advancing.
- **Android status card states:**
  - Connected: primary container colour.
  - Connecting/Waiting: surface-variant colour, showing "Looking for your Mac… retrying in N s" with a **Retry now** button.
  - TrustFailure: error container colour, titled "Pairing problem", with a **Pair again** button.
  - Stopped: "Disconnected" with a **Connect** button.
- **Android destructive actions:** "Forget this Mac" and "Pair again" each require confirmation.
- **Versions:** Mac `CFBundleShortVersionString` "0.2.0", `CFBundleVersion` "2". Android `versionName` "0.2.0", `versionCode` 2.
- **Build gates (all must pass with no new warnings):**
  - `cd mac/MacDroidCore && swift test`
  - `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build`
  - `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`

## Review Focus

1. **Upgrading from 0.1.0:** a user with `setupDone=true`, a saved pairing and permissions held must land directly on Home, never in setup. → Task 3, `SetupFlowTest.upgradedUserGoesStraightToMain`.
2. **A permission revoked after setup:**
   - Local network must route only to that step, then back to Home.
   - A revoked notification permission must NOT force setup.
   → Task 3, `SetupFlowTest.revokedLocalNetworkRoutesToThatStepOnly` and `revokedNotificationsDoNotForceSetup`.
3. **The Android reconnect countdown reaches zero:** the status must read "Reconnecting…" rather than "retrying in 0 s" or a negative number. The Mac pairing countdown must clamp at `0:00`. → Task 3, `StatusPresentationTest.waitingCountdownNeverNegative`; Task 1, `StatusPresentationTests.countdownClampsAtZero`.
4. **A manually typed code with spaces, lowercase or dashes must be accepted.** A wrong-length or non-base32 code must be rejected before any network call. → Task 3, `ManualEntryTest`.
5. **Relaunching the Mac app while it runs** (menu-bar icon hidden by the notch) must open the window; the old app silently did nothing. → Task 2, Step 6 manual check (scripted `open` twice, plus a window-appeared log line).

---

## File Structure

```
mac/
  project.yml                                   (modify: version 0.2.0 / 2)
  MacDroidCore/Sources/MacDroidCore/Presentation/StatusPresentation.swift   (new, pure)
  MacDroidCore/Tests/MacDroidCoreTests/StatusPresentationTests.swift          (new)
  MacDroid/
    MacDroidApp.swift          (rewrite: MenuBarExtra + Window("main") + commands)
    AppDelegate.swift          (new: reopen + login-item detection)
    AppModel.swift             (rewrite: warnings/transient error, window requests, repair, expiry loop)
    DockPolicy.swift           (new)
    SidebarSection.swift       (new)
    QRCode.swift               (keep)
    Views/MenuBarLabel.swift   Views/MenuBarPopover.swift   Views/MainWindow.swift
    Views/Phone/PhoneView.swift  Views/Phone/PairingView.swift  Views/Phone/PairedDeviceView.swift
    Views/Settings/GeneralSettingsView.swift  Views/Settings/SecuritySettingsView.swift  Views/Settings/AboutView.swift
    Views/Shared/BannerView.swift  Views/Shared/ComingSoonView.swift  Views/Shared/StatusDot.swift  Views/Shared/QRCodeImage.swift
    (delete) MenuContentView.swift, SettingsView.swift, PairingView.swift
android/app/
  build.gradle.kts             (modify: deps navigation-compose + icons-extended, version 0.2.0 / 2)
  src/main/java/dev/macdroid/app/
    MacDroidApp.kt             (modify: UiPrefs keepConnected + notificationsSkipped)
    service/ServiceSupport.kt  (modify: StatusText delegates; BootReceiver respects keepConnected)
    service/ConnectionService.kt (modify: Disconnect action clears keepConnected)
    ui/theme/Theme.kt          (new)
    ui/presentation/StatusPresentation.kt (new, pure)
    ui/setup/SetupFlow.kt      (new, pure)    ui/setup/ManualEntry.kt (new, pure)
    ui/setup/SetupViewModel.kt ui/setup/SetupScreen.kt ui/setup/PairStep.kt ui/setup/ManualPairSheet.kt
    ui/navigation/AppNavHost.kt
    ui/main/MainScreen.kt  ui/main/HomeScreen.kt  ui/main/HomeViewModel.kt  ui/main/StatusCard.kt
    ui/main/SettingsScreen.kt  ui/main/SettingsViewModel.kt  ui/main/ComingSoonScreen.kt
    ui/MainActivity.kt         (rewrite: theme + AppNavHost)
    ui/QrScanner.kt            (modify: expose QrCameraPreview, drop QrScannerScreen)
    ui/PairingViewModel.kt     (modify: manual code validation)
    (delete) ui/OnboardingScreens.kt (Task 4), ui/HomeScreen.kt (Task 5)
  src/test/java/dev/macdroid/app/
    StatusPresentationTest.kt  SetupFlowTest.kt  ManualEntryTest.kt   (new)
```

---

## Task 1: Mac status presentation (pure, tested)

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Presentation/StatusPresentation.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/StatusPresentationTests.swift`

**Interfaces:**
- Consumes (existing core types): `PairedDevice`, `ConnectionStatus`, `PeerInfo`.
- Produces:
  - `enum StatusTone { connected, waiting, notPaired }`
  - `struct PhoneStatus { symbol, title, subtitle: String; tone: StatusTone }`
  - `StatusPresentation`:
    - `phoneStatus(paired: PairedDevice?, connection: ConnectionStatus) -> PhoneStatus`
    - `batteryText(_ connection: ConnectionStatus) -> String`
    - `countdown(remaining: TimeInterval) -> String`

- [ ] **Step 1: Write the failing tests**

`mac/MacDroidCore/Tests/MacDroidCoreTests/StatusPresentationTests.swift`:
```swift
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct StatusPresentationTests {
    let device = PairedDevice(fingerprint: "aa", name: "Nothing Phone (3)", model: "Nothing A024", pairedAt: Date(timeIntervalSince1970: 0))

    @Test func notPaired() {
        let status = StatusPresentation.phoneStatus(paired: nil, connection: .disconnected)
        #expect(status == PhoneStatus(symbol: "iphone.slash", title: "No phone paired", subtitle: "Not paired", tone: .notPaired))
    }

    @Test func pairedButDisconnected() {
        let status = StatusPresentation.phoneStatus(paired: device, connection: .disconnected)
        #expect(status == PhoneStatus(symbol: "iphone", title: "Nothing Phone (3)", subtitle: "Not connected", tone: .waiting))
    }

    @Test func connectedWithBatteryAndCharging() {
        let peer = PeerInfo(device: "Nothing Phone (3)", app: "0.2.0", features: [], battery: 82, charging: true)
        let status = StatusPresentation.phoneStatus(paired: device, connection: .connected(peer))
        #expect(status == PhoneStatus(symbol: "iphone.radiowaves.left.and.right", title: "Nothing Phone (3)", subtitle: "Connected · 82% · charging", tone: .connected))
    }

    @Test func connectedBeforeFirstBatteryReport() {
        let peer = PeerInfo(device: "Pixel", app: "0.2.0", features: [])
        #expect(StatusPresentation.phoneStatus(paired: device, connection: .connected(peer)).subtitle == "Connected")
    }

    @Test func batteryText() {
        #expect(StatusPresentation.batteryText(.disconnected) == "—")
        #expect(StatusPresentation.batteryText(.connected(PeerInfo(device: "P", app: "1", features: []))) == "—")
        #expect(StatusPresentation.batteryText(.connected(PeerInfo(device: "P", app: "1", features: [], battery: 40, charging: false))) == "40%")
        #expect(StatusPresentation.batteryText(.connected(PeerInfo(device: "P", app: "1", features: [], battery: 99, charging: true))) == "99% · charging")
    }

    @Test func countdownFormatsMinutesAndSeconds() {
        #expect(StatusPresentation.countdown(remaining: 112) == "1:52")
        #expect(StatusPresentation.countdown(remaining: 120) == "2:00")
        #expect(StatusPresentation.countdown(remaining: 9.2) == "0:10")
    }

    // Review Focus #3
    @Test func countdownClampsAtZero() {
        #expect(StatusPresentation.countdown(remaining: 0) == "0:00")
        #expect(StatusPresentation.countdown(remaining: -5) == "0:00")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter StatusPresentationTests`
Expected: compile errors (`cannot find 'StatusPresentation' in scope`).

- [ ] **Step 3: Implement**

`mac/MacDroidCore/Sources/MacDroidCore/Presentation/StatusPresentation.swift`:
```swift
import Foundation

public enum StatusTone: Equatable, Sendable {
    case connected
    case waiting
    case notPaired
}

/// What the menu bar, popover and Phone page show for the paired phone.
public struct PhoneStatus: Equatable, Sendable {
    public var symbol: String
    public var title: String
    public var subtitle: String
    public var tone: StatusTone

    public init(symbol: String, title: String, subtitle: String, tone: StatusTone) {
        self.symbol = symbol
        self.title = title
        self.subtitle = subtitle
        self.tone = tone
    }
}

/// Pure mapping from connection state to display text. No UI framework imports.
public enum StatusPresentation {
    public static func phoneStatus(paired: PairedDevice?, connection: ConnectionStatus) -> PhoneStatus {
        guard let paired else {
            return PhoneStatus(symbol: "iphone.slash", title: "No phone paired", subtitle: "Not paired", tone: .notPaired)
        }
        switch connection {
        case .connected(let peer):
            var parts = ["Connected"]
            if let battery = peer.battery { parts.append("\(battery)%") }
            if peer.charging == true { parts.append("charging") }
            return PhoneStatus(symbol: "iphone.radiowaves.left.and.right", title: peer.device,
                               subtitle: parts.joined(separator: " · "), tone: .connected)
        case .disconnected:
            return PhoneStatus(symbol: "iphone", title: paired.name, subtitle: "Not connected", tone: .waiting)
        }
    }

    public static func batteryText(_ connection: ConnectionStatus) -> String {
        guard case .connected(let peer) = connection, let battery = peer.battery else { return "—" }
        return peer.charging == true ? "\(battery)% · charging" : "\(battery)%"
    }

    /// "m:ss", rounding up partial seconds and clamping at 0:00.
    public static func countdown(remaining: TimeInterval) -> String {
        let seconds = max(0, Int(remaining.rounded(.up)))
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test --filter StatusPresentationTests`, then the full `swift test`.
Expected: all pass (59 tests), no warnings.

- [ ] **Step 5: Commit**

```bash
git add mac/MacDroidCore/Sources/MacDroidCore/Presentation mac/MacDroidCore/Tests/MacDroidCoreTests/StatusPresentationTests.swift
git commit -m "feat(mac): pure status presentation for the redesigned UI"
```

---

## Task 2: Mac app redesign (menu-bar popover + sidebar window)

**Files:**
- Modify: `mac/project.yml` (versions)
- Rewrite: `mac/MacDroid/MacDroidApp.swift`, `mac/MacDroid/AppModel.swift`
- Create:
  - `mac/MacDroid/AppDelegate.swift`, `DockPolicy.swift`, `SidebarSection.swift`
  - `Views/MenuBarLabel.swift`, `Views/MenuBarPopover.swift`, `Views/MainWindow.swift`
  - `Views/Phone/PhoneView.swift`, `Views/Phone/PairingView.swift`, `Views/Phone/PairedDeviceView.swift`
  - `Views/Settings/GeneralSettingsView.swift`, `Views/Settings/SecuritySettingsView.swift`, `Views/Settings/AboutView.swift`
  - `Views/Shared/BannerView.swift`, `Views/Shared/ComingSoonView.swift`, `Views/Shared/StatusDot.swift`, `Views/Shared/QRCodeImage.swift`
- Delete: `mac/MacDroid/MenuContentView.swift`, `mac/MacDroid/SettingsView.swift`, `mac/MacDroid/PairingView.swift`

**Interfaces:**
- Consumes from `MacDroidCore`:
  - `StatusPresentation`, `PhoneStatus`, `StatusTone` (Task 1)
  - `PairingManager`, `PairingEvent`, `PairingInvite`, `ConnectionHub` (incl. `disconnect(unlessFingerprint:)`), `MacDroidServer`, `BonjourAdvertiser`, `LocalAddress`, `KeychainIdentityStore`, `UserDefaultsPairedDeviceStore`, `Identity`, `Fingerprint`
  - Existing `QRCode.image(for:)`.
- Produces: app-target only, so no other task depends on it.

Verified behaviour this design relies on (from a throwaway spike on this machine):
- In an `LSUIElement` app, a `Window` scene is **not** opened automatically at launch.
- Calling `openWindow(id:)` from the `MenuBarExtra` label view's `.task` opens it at launch.
- `applicationShouldHandleReopen` fires when the running app is opened again (`open MacDroid.app`).
- `SceneBuilder` rejects `if #available`, so `defaultLaunchBehavior` is not used.

- [ ] **Step 1: Bump versions and remove the old views**

In `mac/project.yml`, set `CFBundleShortVersionString: "0.2.0"` and `CFBundleVersion: "2"`.

Run: `git rm mac/MacDroid/MenuContentView.swift mac/MacDroid/SettingsView.swift mac/MacDroid/PairingView.swift`

- [ ] **Step 2: App model, delegate, dock policy, sidebar sections**

`mac/MacDroid/SidebarSection.swift`:
```swift
enum SidebarSection: String, Hashable, CaseIterable, Identifiable {
    case phone, notifications, clipboard, files, general, security, about

    var id: String { rawValue }

    static let features: [SidebarSection] = [.phone, .notifications, .clipboard, .files]
    static let settings: [SidebarSection] = [.general, .security, .about]

    var title: String {
        switch self {
        case .phone: "Phone"
        case .notifications: "Notifications"
        case .clipboard: "Clipboard"
        case .files: "Files"
        case .general: "General"
        case .security: "Security"
        case .about: "About"
        }
    }

    var symbol: String {
        switch self {
        case .phone: "iphone.gen3"
        case .notifications: "bell.badge"
        case .clipboard: "doc.on.clipboard"
        case .files: "folder"
        case .general: "gearshape"
        case .security: "lock.shield"
        case .about: "info.circle"
        }
    }

    /// Features not built yet show a "Coming in a later update" page.
    var isAvailable: Bool { comingSoonDescription == nil }

    var comingSoonDescription: String? {
        switch self {
        case .notifications: "See your Android notifications on your Mac, reply to messages, and dismiss them on both devices."
        case .clipboard: "Copy on your Mac and paste on your phone — and send your phone's clipboard back with one tap."
        case .files: "Drag files between your Mac and phone in either direction, with progress and history."
        default: nil
        }
    }
}
```

`mac/MacDroid/AppDelegate.swift`:
```swift
import AppKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    /// Set by the app at startup; opens the main window when the user launches MacDroid again.
    static var onReopen: (@MainActor () -> Void)?
    private(set) static var launchedAsLoginItem = false

    func applicationWillFinishLaunching(_ notification: Notification) {
        let event = NSAppleEventManager.shared().currentAppleEvent
        Self.launchedAsLoginItem = event?.eventID == AEEventID(kAEOpenApplication)
            && event?.paramDescriptor(forKeyword: AEKeyword(keyAEPropData))?.enumCodeValue == OSType(keyAELaunchedAsLogInItem)
    }

    /// Launching MacDroid while it's already running (e.g. its menu-bar icon is hidden by the notch) opens the window.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        Self.onReopen?()
        return false
    }

    /// Open the window on launch, except at login when a phone is already paired (stay quietly in the menu bar).
    static func shouldOpenWindowAtLaunch(paired: Bool) -> Bool {
        !launchedAsLoginItem || !paired
    }
}
```

`mac/MacDroid/DockPolicy.swift`:
```swift
import AppKit

/// MacDroid lives in the menu bar; it shows in the Dock and ⌘-Tab only while its window is open.
@MainActor
enum DockPolicy {
    static let showInDockKey = "showInDock"

    static var showInDock: Bool {
        UserDefaults.standard.object(forKey: showInDockKey) as? Bool ?? true
    }

    static func windowDidAppear() {
        NSApp.setActivationPolicy(showInDock ? .regular : .accessory)
        NSApp.activate()
    }

    static func windowDidDisappear() {
        NSApp.setActivationPolicy(.accessory)
    }
}
```

`mac/MacDroid/AppModel.swift` (full replacement):
```swift
import Foundation
import MacDroidCore
import Observation

/// A request to show the main window on a given section. A new value (new `id`) triggers the opener.
struct WindowRequest: Equatable {
    let id = UUID()
    let section: SidebarSection
}

@MainActor @Observable
final class AppModel {
    private(set) var status: ConnectionStatus = .disconnected
    private(set) var pairedDevice: PairedDevice?
    private(set) var invite: PairingInvite?
    private(set) var inviteExpiresAt: Date?
    /// The last code expired without being used; the pairing view shows "Code expired" instead of a stale QR.
    private(set) var pairingExpired = false
    /// True while the user is pairing a replacement phone from the paired view.
    private(set) var isRepairing = false
    private(set) var listeningPort: Int?
    /// Conditions that last until restart (keychain unavailable, server stopped, Bonjour failed). Never cleared by pairing.
    private(set) var warnings: [String] = []
    /// Problem with the last user action; cleared when the next action starts.
    private(set) var transientError: String?
    private(set) var windowRequest: WindowRequest?
    var selectedSection: SidebarSection? = .phone

    let macName: String
    let fingerprint: String
    let usingTemporaryIdentity: Bool

    @ObservationIgnored private let identity: Identity
    @ObservationIgnored private let pairing: PairingManager
    @ObservationIgnored private let hub: ConnectionHub
    @ObservationIgnored private let advertiser = BonjourAdvertiser()
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []

    init() {
        let name = Host.current().localizedName ?? "Mac"
        macName = name
        let loaded: Identity
        var temporary = false
        do {
            loaded = try KeychainIdentityStore().loadOrCreate(commonName: name)
        } catch {
            loaded = try! Identity.generate(commonName: name)
            temporary = true
        }
        identity = loaded
        fingerprint = loaded.fingerprint
        usingTemporaryIdentity = temporary
        let store = UserDefaultsPairedDeviceStore()
        pairedDevice = store.load()
        pairing = PairingManager(store: store)
        hub = ConnectionHub(macName: name, appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0")
        if temporary {
            warnings.append("MacDroid couldn't open its keychain, so pairing is unavailable until you restart MacDroid.")
        }
    }

    var phoneStatus: PhoneStatus { StatusPresentation.phoneStatus(paired: pairedDevice, connection: status) }
    var showsPairing: Bool { pairedDevice == nil || isRepairing }

    func start() {
        guard tasks.isEmpty else { return }
        let server = MacDroidServer(identity: identity, pairing: pairing, hub: hub, macName: macName)
        tasks.append(Task { [weak self] in
            do {
                try await server.run { [weak self] port in await self?.serverStarted(port: port) }
            } catch {
                self?.warnings.append("MacDroid's connection service stopped (\(error.localizedDescription)). Quit and reopen MacDroid.")
            }
        })
        tasks.append(Task { [weak self, hub] in
            for await status in hub.statusUpdates { self?.status = status }
        })
        tasks.append(Task { [weak self, pairing] in
            for await event in pairing.events { await self?.handle(event) }
        })
    }

    func showWindow(_ section: SidebarSection = .phone) {
        selectedSection = section
        windowRequest = WindowRequest(section: section)
    }

    /// Called whenever the pairing view is visible: make sure there's a code to scan (but don't replace an expired one silently).
    func ensurePairingCode() async {
        guard showsPairing, invite == nil, !pairingExpired else { return }
        await beginPairing()
    }

    func newCode() async { await beginPairing() }

    func startRepair() async {
        isRepairing = true
        await beginPairing()
    }

    func cancelPairing() async {
        isRepairing = false
        pairingExpired = false
        await pairing.closeWindow()
    }

    func forgetPhone() async {
        await pairing.forget()
        await hub.disconnect()
    }

    private func beginPairing() async {
        guard !usingTemporaryIdentity else {
            transientError = "Pairing is unavailable because MacDroid couldn't open its keychain. Quit and reopen MacDroid to try again."
            return
        }
        guard let port = listeningPort else { transientError = "MacDroid is still starting — try again in a moment."; return }
        guard let host = LocalAddress.primaryIPv4() else { transientError = "No network connection. Join Wi-Fi and try again."; return }
        transientError = nil
        pairingExpired = false
        let window = await pairing.openWindow()
        invite = PairingInvite(host: host, port: port, fingerprint: fingerprint, code: window.code, name: macName)
        inviteExpiresAt = window.expiresAt
        watchExpiry(code: window.code)
    }

    /// Sleeps until the code expires, then polls until the manager reports it closed (robust to early wake-ups).
    private func watchExpiry(code: String) {
        Task { [pairing] in
            while let current = await pairing.currentWindow(), current.code == code {
                try? await Task.sleep(for: .seconds(max(0.25, current.expiresAt.timeIntervalSinceNow + 0.05)))
            }
        }
    }

    private func serverStarted(port: Int) {
        listeningPort = port
        do {
            try advertiser.start(name: macName, port: UInt16(port), txt: ["v": "1", "fp": Fingerprint.short(fingerprint)])
        } catch {
            warnings.append("Your phone may not find this Mac automatically (Bonjour: \(error.localizedDescription)). Pairing by QR code still works.")
        }
    }

    private func handle(_ event: PairingEvent) async {
        switch event {
        case .windowOpened:
            break
        case .windowClosed:
            if let expires = inviteExpiresAt, expires <= Date() { pairingExpired = true }
            invite = nil
            inviteExpiresAt = nil
        case .paired(let device):
            pairedDevice = device
            isRepairing = false
            pairingExpired = false
            transientError = nil
            // A newly paired phone replaces the old one: end any session authenticated with another certificate.
            await hub.disconnect(unlessFingerprint: device.fingerprint)
        case .forgotten:
            pairedDevice = nil
            isRepairing = false
            await hub.disconnect()
        }
    }
}
```

- [ ] **Step 3: App entry, menu-bar label and popover**

`mac/MacDroid/MacDroidApp.swift` (full replacement):
```swift
import SwiftUI

@main
struct MacDroidApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model: AppModel

    init() {
        let model = AppModel()
        model.start()
        AppDelegate.onReopen = { model.showWindow(.phone) }
        _model = State(initialValue: model)
    }

    var body: some Scene {
        MenuBarExtra {
            MenuBarPopover(model: model)
        } label: {
            MenuBarLabel(model: model)
        }
        .menuBarExtraStyle(.window)

        Window("MacDroid", id: MainWindow.id) {
            MainWindow(model: model)
        }
        .defaultSize(width: 720, height: 480)
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(replacing: .appSettings) {
                Button("Settings…") { model.showWindow(.general) }
                    .keyboardShortcut(",", modifiers: .command)
            }
        }
    }
}
```

`mac/MacDroid/Views/MenuBarLabel.swift`:
```swift
import AppKit
import SwiftUI

/// The menu-bar icon. Lives for the whole app session, so it also owns opening the main window.
struct MenuBarLabel: View {
    let model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Image(systemName: model.phoneStatus.symbol)
            .task {
                if AppDelegate.shouldOpenWindowAtLaunch(paired: model.pairedDevice != nil) {
                    openWindow(id: MainWindow.id)
                }
            }
            .onChange(of: model.windowRequest) { _, request in
                guard request != nil else { return }
                openWindow(id: MainWindow.id)
                NSApp.activate()
            }
    }
}
```

`mac/MacDroid/Views/MenuBarPopover.swift`:
```swift
import AppKit
import MacDroidCore
import SwiftUI

struct MenuBarPopover: View {
    let model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if model.pairedDevice == nil {
                notPaired
            } else {
                header
            }
            ForEach(model.warnings, id: \.self) { warning in
                Label(warning, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(Color.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Divider()
            VStack(spacing: 0) {
                MenuRow(title: "Open MacDroid", shortcut: "⌘O") { model.showWindow(.phone) }
                    .keyboardShortcut("o")
                MenuRow(title: "Settings…", shortcut: "⌘,") { model.showWindow(.general) }
                MenuRow(title: "Quit MacDroid", shortcut: "⌘Q") { NSApp.terminate(nil) }
                    .keyboardShortcut("q")
            }
        }
        .padding(12)
        .frame(width: 280)
    }

    private var notPaired: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("No phone paired").font(.headline)
            Text("Pair your Android phone to get started.").font(.callout).foregroundStyle(.secondary)
            Button {
                model.showWindow(.phone)
            } label: {
                Text("Pair a Phone…").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    private var header: some View {
        let status = model.phoneStatus
        return HStack(spacing: 10) {
            Image(systemName: "iphone.gen3")
                .font(.system(size: 20))
                .frame(width: 40, height: 40)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
                Text(status.title).font(.headline)
                HStack(spacing: 5) {
                    StatusDot(tone: status.tone)
                    Text(status.subtitle).font(.callout).foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// A full-width menu-style row with a hover highlight and a right-aligned shortcut hint.
private struct MenuRow: View {
    let title: String
    let shortcut: String
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title)
                Spacer()
                Text(shortcut).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
            .background(RoundedRectangle(cornerRadius: 6).fill(hovering ? Color.accentColor.opacity(0.18) : Color.clear))
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}
```

- [ ] **Step 4: Main window, shared views, Phone and Settings sections**

`mac/MacDroid/Views/MainWindow.swift`:
```swift
import SwiftUI

struct MainWindow: View {
    static let id = "main"
    @Bindable var model: AppModel

    var body: some View {
        NavigationSplitView {
            List(selection: $model.selectedSection) {
                Section {
                    ForEach(SidebarSection.features) { row($0) }
                }
                Section("MacDroid") {
                    ForEach(SidebarSection.settings) { row($0) }
                }
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200, max: 260)
        } detail: {
            VStack(spacing: 0) {
                ForEach(model.warnings, id: \.self) { BannerView(kind: .warning, message: $0) }
                detail(for: model.selectedSection ?? .phone)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 600, minHeight: 420)
        .onAppear { DockPolicy.windowDidAppear() }
        .onDisappear { DockPolicy.windowDidDisappear() }
    }

    private func row(_ section: SidebarSection) -> some View {
        Label(section.title, systemImage: section.symbol)
            .foregroundStyle(section.isAvailable ? .primary : .secondary)
            .tag(section)
    }

    @ViewBuilder
    private func detail(for section: SidebarSection) -> some View {
        switch section {
        case .phone: PhoneView(model: model)
        case .notifications, .clipboard, .files: ComingSoonView(section: section)
        case .general: GeneralSettingsView()
        case .security: SecuritySettingsView(fingerprint: model.fingerprint)
        case .about: AboutView()
        }
    }
}
```

`mac/MacDroid/Views/Shared/BannerView.swift`:
```swift
import SwiftUI

struct BannerView: View {
    enum Kind { case warning, error }
    let kind: Kind
    let message: String

    private var tint: Color { kind == .warning ? Color.orange : Color.red }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: kind == .warning ? "exclamationmark.triangle.fill" : "xmark.octagon.fill")
                .foregroundStyle(tint)
            Text(message)
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
        .padding([.horizontal, .top], 12)
    }
}
```

`mac/MacDroid/Views/Shared/ComingSoonView.swift`:
```swift
import SwiftUI

struct ComingSoonView: View {
    let section: SidebarSection

    var body: some View {
        ContentUnavailableView {
            Label(section.title, systemImage: section.symbol)
        } description: {
            Text("Coming in a later update.\n\(section.comingSoonDescription ?? "")")
        }
    }
}
```

`mac/MacDroid/Views/Shared/StatusDot.swift`:
```swift
import MacDroidCore
import SwiftUI

struct StatusDot: View {
    let tone: StatusTone

    var body: some View {
        Circle()
            .fill(tone == .connected ? Color.green : Color.secondary)
            .frame(width: 8, height: 8)
    }
}
```

`mac/MacDroid/Views/Shared/QRCodeImage.swift`:
```swift
import SwiftUI

/// The pairing QR code on a white tile so it scans in dark mode too.
struct QRCodeImage: View {
    let text: String

    var body: some View {
        Group {
            if let image = QRCode.image(for: text) {
                Image(nsImage: image).interpolation(.none).resizable().scaledToFit()
            } else {
                Image(systemName: "qrcode").resizable().scaledToFit().foregroundStyle(.secondary)
            }
        }
        .padding(12)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(.quaternary))
    }
}
```

`mac/MacDroid/Views/Phone/PhoneView.swift`:
```swift
import SwiftUI

struct PhoneView: View {
    let model: AppModel

    var body: some View {
        if model.showsPairing {
            PairingView(model: model)
        } else {
            PairedDeviceView(model: model)
        }
    }
}
```

`mac/MacDroid/Views/Phone/PairingView.swift`:
```swift
import MacDroidCore
import SwiftUI

struct PairingView: View {
    let model: AppModel
    @State private var showManual = false

    var body: some View {
        VStack(spacing: 14) {
            if let error = model.transientError { BannerView(kind: .error, message: error) }
            Spacer(minLength: 0)
            Text(model.isRepairing ? "Pair a different phone" : "Pair your Android phone")
                .font(.title2.weight(.semibold))
            Text("Open MacDroid on your phone and scan this code")
                .foregroundStyle(.secondary)
            codeArea
            if model.isRepairing {
                Button("Cancel") { Task { await model.cancelPairing() } }
            }
            Spacer(minLength: 0)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Re-runs once the server has a port, so a window opened during startup still gets a code.
        .task(id: model.listeningPort) { await model.ensurePairingCode() }
    }

    @ViewBuilder
    private var codeArea: some View {
        if model.usingTemporaryIdentity {
            unavailable
        } else if let invite = model.invite, let expires = model.inviteExpiresAt {
            activeCode(invite, expires: expires)
        } else if model.pairingExpired {
            expired
        } else {
            ProgressView().controlSize(.large).frame(width: 240, height: 240)
        }
    }

    private func activeCode(_ invite: PairingInvite, expires: Date) -> some View {
        VStack(spacing: 10) {
            QRCodeImage(text: invite.uri).frame(width: 240, height: 240)
            TimelineView(.periodic(from: .now, by: 1)) { context in
                HStack(spacing: 8) {
                    Text("Expires in \(StatusPresentation.countdown(remaining: expires.timeIntervalSince(context.date)))")
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                    Button("New code") { Task { await model.newCode() } }
                        .buttonStyle(.link)
                }
            }
            DisclosureGroup("Can't scan?", isExpanded: $showManual) {
                Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                    GridRow {
                        Text("Address").foregroundStyle(.secondary)
                        Text("\(invite.host):\(String(invite.port))").textSelection(.enabled)
                    }
                    GridRow {
                        Text("Code").foregroundStyle(.secondary)
                        Text(invite.displayCode).textSelection(.enabled)
                    }
                    GridRow {
                        Text("Fingerprint").foregroundStyle(.secondary)
                        Text(Fingerprint.display(invite.fingerprint))
                    }
                }
                .font(.system(.body, design: .monospaced))
                .padding(.top, 6)
            }
            .frame(width: 360)
        }
    }

    private var expired: some View {
        VStack(spacing: 10) {
            Image(systemName: "clock.badge.xmark").font(.system(size: 44)).foregroundStyle(.secondary)
            Text("Code expired").font(.headline)
            Button("New code") { Task { await model.newCode() } }
                .buttonStyle(.borderedProminent)
        }
        .frame(width: 240, height: 240)
    }

    private var unavailable: some View {
        VStack(spacing: 10) {
            Image(systemName: "lock.trianglebadge.exclamationmark").font(.system(size: 44)).foregroundStyle(.secondary)
            Text("Pairing unavailable").font(.headline)
            Text("MacDroid couldn't open its keychain, so a pairing wouldn't survive a restart. Quit and reopen MacDroid to try again.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .frame(maxWidth: 360)
        }
        .frame(minHeight: 240)
    }
}
```

`mac/MacDroid/Views/Phone/PairedDeviceView.swift`:
```swift
import MacDroidCore
import SwiftUI

struct PairedDeviceView: View {
    let model: AppModel
    @State private var confirmForget = false

    var body: some View {
        let status = model.phoneStatus
        Form {
            Section {
                HStack(spacing: 14) {
                    Image(systemName: "iphone.gen3")
                        .font(.system(size: 30))
                        .frame(width: 64, height: 64)
                        .background(.quaternary, in: RoundedRectangle(cornerRadius: 14))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(status.title).font(.title2.weight(.semibold))
                        HStack(spacing: 6) {
                            StatusDot(tone: status.tone)
                            Text(status.tone == .connected ? "Connected over Wi-Fi" : "Not connected — waiting for your phone")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.vertical, 6)
            }
            Section("Details") {
                LabeledContent("Battery", value: StatusPresentation.batteryText(model.status))
                if let device = model.pairedDevice {
                    LabeledContent("Model", value: device.model)
                    LabeledContent("Paired", value: device.pairedAt.formatted(date: .abbreviated, time: .omitted))
                }
            }
            Section {
                HStack {
                    Button("Pair a Different Phone…") { Task { await model.startRepair() } }
                    Spacer()
                    Button("Forget This Phone…", role: .destructive) { confirmForget = true }
                }
            }
        }
        .formStyle(.grouped)
        .alert("Forget \(model.pairedDevice?.name ?? "this phone")?", isPresented: $confirmForget) {
            Button("Forget", role: .destructive) { Task { await model.forgetPhone() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The phone will be disconnected and will need to be paired again.")
        }
    }
}
```

`mac/MacDroid/Views/Settings/GeneralSettingsView.swift`:
```swift
import ServiceManagement
import SwiftUI

struct GeneralSettingsView: View {
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @State private var loginMessage: String?
    @State private var reverting = false
    @AppStorage(DockPolicy.showInDockKey) private var showInDock = true

    var body: some View {
        Form {
            Section {
                Toggle("Launch at login", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) { _, enabled in
                        if reverting { reverting = false; return }
                        apply(enabled)
                    }
                if let loginMessage {
                    HStack {
                        Text(loginMessage).font(.callout).foregroundStyle(.red)
                        Spacer()
                        if SMAppService.mainApp.status == .requiresApproval {
                            Button("Open Login Items") { SMAppService.openSystemSettingsLoginItems() }
                        }
                    }
                }
                Toggle("Show MacDroid in the Dock while its window is open", isOn: $showInDock)
                    .onChange(of: showInDock) { _, _ in DockPolicy.windowDidAppear() }
            } footer: {
                Text("MacDroid always keeps running in the menu bar.").foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    /// Applies the toggle; if the system refuses, shows why and puts the toggle back to the real state.
    private func apply(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            loginMessage = SMAppService.mainApp.status == .requiresApproval
                ? "Approve MacDroid in System Settings → General → Login Items."
                : nil
        } catch {
            loginMessage = "Couldn't change the login item: \(error.localizedDescription)"
        }
        let actual = SMAppService.mainApp.status == .enabled
        if actual != launchAtLogin {
            reverting = true
            launchAtLogin = actual
        }
    }
}
```

`mac/MacDroid/Views/Settings/SecuritySettingsView.swift`:
```swift
import AppKit
import MacDroidCore
import SwiftUI

struct SecuritySettingsView: View {
    let fingerprint: String

    var body: some View {
        Form {
            Section("This Mac's fingerprint") {
                HStack {
                    Text(Fingerprint.display(fingerprint))
                        .font(.system(.title3, design: .monospaced))
                        .textSelection(.enabled)
                    Spacer()
                    Button("Copy") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(fingerprint, forType: .string)
                    }
                }
                Text("Your phone checks this fingerprint every time it connects. If you pair manually, compare it with the one your phone shows.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }
}
```

`mac/MacDroid/Views/Settings/AboutView.swift`:
```swift
import AppKit
import SwiftUI

struct AboutView: View {
    private var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?" }
    private var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?" }

    var body: some View {
        VStack(spacing: 10) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 72, height: 72)
            Text("MacDroid").font(.title2.weight(.semibold))
            Text("Version \(version) (\(build))").foregroundStyle(.secondary)
            Text("Your Android phone, connected to your Mac — locally, with no account or cloud.")
                .multilineTextAlignment(.center)
                .frame(maxWidth: 360)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
```

- [ ] **Step 5: Build with no warnings**

Run: `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build 2>&1 | grep -E "warning:|error:|BUILD"`
Expected: `** BUILD SUCCEEDED **` and no `warning:` lines from `MacDroid/` sources. The environment's CoreSimulator/CoreDevice noise is not a source warning.

Swift 6 notes:
- If `.foregroundStyle(section.isAvailable ? .primary : .secondary)` fails to type-check, use `HierarchicalShapeStyle.primary` / `.secondary` explicitly.
- If `NSApp.activate()` is flagged as unavailable, use `NSApp.activate(ignoringOtherApps: true)`.
- Report any such fix as a deviation.

- [ ] **Step 6: Run it and verify window behaviour (Review Focus #5)**

```bash
pkill -f "MacDroid.app/Contents/MacOS/MacDroid"; sleep 1
open mac/build/Build/Products/Debug/MacDroid.app; sleep 3
osascript -e 'tell application "System Events" to get name of every window of (first process whose name is "MacDroid")' 2>&1
```
Expected: the main window is open (or, if `osascript` lacks accessibility permission, `pgrep -f MacDroid.app` shows it running and the window is visible on screen). Then:
- Close the window with ⌘W, or `osascript -e 'tell application "MacDroid" to close every window'`.
- Run `open mac/build/Build/Products/Debug/MacDroid.app` again. The window must reappear. This is the reopen path.

Capture screenshots for the reviewer (best effort):
```bash
swift -e 'import CoreGraphics; let l = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as! [[String: Any]]; for w in l where (w[kCGWindowOwnerName as String] as? String) == "MacDroid" { print(w[kCGWindowNumber as String]!) }'
screencapture -x -o -l <id> .superpowers/screens/mac-phone.png
```
If `screencapture` needs Screen Recording permission, skip the screenshots and say so in the report.

Manual-only items, which are listed in the report for the human: the popover look, the Settings sections, Forget confirmation, Pair a Different Phone, and the dock icon appearing and disappearing.

- [ ] **Step 7: Commit**

```bash
git add mac/project.yml mac/MacDroid
git commit -m "feat(mac): menu-bar popover and sidebar window redesign"
```

---

## Task 3: Android foundations — theme, pure presentation and setup logic (tested)

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/java/dev/macdroid/app/MacDroidApp.kt` (`UiPrefs`)
- Modify: `android/app/src/main/java/dev/macdroid/app/service/ServiceSupport.kt` (`StatusText`, `BootReceiver`)
- Modify: `android/app/src/main/java/dev/macdroid/app/service/ConnectionService.kt` (Disconnect action)
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/PairingViewModel.kt` (manual code validation)
- Create:
  - `android/app/src/main/java/dev/macdroid/app/ui/theme/Theme.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/presentation/StatusPresentation.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/setup/SetupFlow.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/setup/ManualEntry.kt`
- Test: `android/app/src/test/java/dev/macdroid/app/StatusPresentationTest.kt`, `SetupFlowTest.kt`, `ManualEntryTest.kt`. The existing `StatusTextTest.kt` must keep passing unchanged.

**Interfaces:**
- Consumes (existing):
  - `ConnectionState` from `dev.macdroid.core.connection`
  - `PairingInvite.normalizeCode(raw)` from `dev.macdroid.core.pairing`
  - `UiPrefs`, `Permissions`, `PairingViewModel`
- Produces:
  - Theme: `MacDroidTheme(content)`.
  - Status presentation:
    - `enum StatusTone { Connected, Pending, Problem, Idle }`
    - `enum StatusAction { None, RetryNow, PairAgain, Connect }`
    - `data class StatusModel(title, subtitle: String, tone: StatusTone, action: StatusAction)`
    - `StatusPresentation.home(state, macName: String, waitingRemainingMs: Long? = null): StatusModel`
    - `StatusPresentation.notificationTitle(state): String`
  - Setup flow:
    - `enum SetupStep { Welcome, LocalNetwork, PairMac, Notifications, Battery }`
    - `data class SetupInputs(...)`
    - `SetupFlow.steps(i)`, `SetupFlow.current(i): SetupStep?`
    - `data class SetupUiState(step, position, total, firstRun, readyToConnect)` with `SetupUiState.from(inputs, keepConnected)`
  - Manual entry: `ManualEntry.normalizedCodeOrNull(raw): String?`
  - New `UiPrefs` properties: `keepConnected: Boolean` (default true) and `notificationsSkipped: Boolean` (default false).

- [ ] **Step 1: Dependencies and version**

In `android/app/build.gradle.kts`:
- Set `versionCode = 2` and `versionName = "0.2.0"`.
- Add to `dependencies` after the material3 line:
```kotlin
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.10.2")
```

- [ ] **Step 2: Write the failing tests**

`android/app/src/test/java/dev/macdroid/app/StatusPresentationTest.kt`:
```kotlin
package dev.macdroid.app

import dev.macdroid.app.ui.presentation.StatusAction
import dev.macdroid.app.ui.presentation.StatusModel
import dev.macdroid.app.ui.presentation.StatusPresentation
import dev.macdroid.app.ui.presentation.StatusTone
import dev.macdroid.core.connection.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusPresentationTest {
    @Test fun connectedUsesTheNameTheMacSent() {
        assertEquals(
            StatusModel("Studio", "Connected", StatusTone.Connected, StatusAction.None),
            StatusPresentation.home(ConnectionState.Connected("Studio"), "Old name"),
        )
    }

    @Test fun waitingShowsLiveCountdownAndRetry() {
        assertEquals(
            StatusModel("Studio", "Looking for your Mac… retrying in 8 s", StatusTone.Pending, StatusAction.RetryNow),
            StatusPresentation.home(ConnectionState.Waiting(30_000), "Studio", waitingRemainingMs = 7_200),
        )
    }

    @Test fun waitingWithoutElapsedTimeUsesTheFullDelay() {
        assertEquals("Looking for your Mac… retrying in 2 s", StatusPresentation.home(ConnectionState.Waiting(1_500), "Studio").subtitle)
    }

    // Review Focus #3
    @Test fun waitingCountdownNeverNegative() {
        assertEquals("Reconnecting…", StatusPresentation.home(ConnectionState.Waiting(1_000), "Studio", waitingRemainingMs = 0).subtitle)
        assertEquals("Reconnecting…", StatusPresentation.home(ConnectionState.Waiting(1_000), "Studio", waitingRemainingMs = -500).subtitle)
    }

    @Test fun connectingHasNoAction() {
        assertEquals(
            StatusModel("Studio", "Connecting…", StatusTone.Pending, StatusAction.None),
            StatusPresentation.home(ConnectionState.Connecting, "Studio"),
        )
    }

    @Test fun trustFailureOffersPairAgain() {
        assertEquals(
            StatusModel("Pairing problem", "Your Mac no longer recognises this phone.", StatusTone.Problem, StatusAction.PairAgain),
            StatusPresentation.home(ConnectionState.TrustFailure("x"), "Studio"),
        )
    }

    @Test fun stoppedOffersConnect() {
        assertEquals(
            StatusModel("Studio", "Disconnected", StatusTone.Idle, StatusAction.Connect),
            StatusPresentation.home(ConnectionState.Stopped, "Studio"),
        )
    }

    @Test fun notificationTitlesAreUnchanged() {
        assertEquals("Connected to Studio", StatusPresentation.notificationTitle(ConnectionState.Connected("Studio")))
        assertEquals("Mac not reachable — retrying in 2s", StatusPresentation.notificationTitle(ConnectionState.Waiting(1_500)))
    }
}
```

`android/app/src/test/java/dev/macdroid/app/SetupFlowTest.kt`:
```kotlin
package dev.macdroid.app

import dev.macdroid.app.ui.setup.SetupFlow
import dev.macdroid.app.ui.setup.SetupInputs
import dev.macdroid.app.ui.setup.SetupStep
import dev.macdroid.app.ui.setup.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFlowTest {
    private fun inputs(
        setupDone: Boolean = false,
        welcomeDone: Boolean = false,
        needsLocalNetwork: Boolean = true,
        hasLocalNetwork: Boolean = false,
        paired: Boolean = false,
        needsNotifications: Boolean = true,
        hasNotifications: Boolean = false,
        notificationsSkipped: Boolean = false,
        batteryDone: Boolean = false,
    ) = SetupInputs(setupDone, welcomeDone, needsLocalNetwork, hasLocalNetwork, paired, needsNotifications, hasNotifications, notificationsSkipped, batteryDone)

    @Test fun firstRunShowsEveryStepInOrder() {
        assertEquals(
            listOf(SetupStep.Welcome, SetupStep.LocalNetwork, SetupStep.PairMac, SetupStep.Notifications, SetupStep.Battery),
            SetupFlow.steps(inputs()),
        )
        assertEquals(SetupStep.Welcome, SetupFlow.current(inputs()))
    }

    @Test fun firstRunSkipsPermissionsTheOsDoesNotHave() {
        assertEquals(
            listOf(SetupStep.Welcome, SetupStep.PairMac, SetupStep.Battery),
            SetupFlow.steps(inputs(needsLocalNetwork = false, needsNotifications = false)),
        )
    }

    @Test fun advancesAsEachStepCompletes() {
        assertEquals(SetupStep.LocalNetwork, SetupFlow.current(inputs(welcomeDone = true)))
        assertEquals(SetupStep.PairMac, SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true)))
        assertEquals(SetupStep.Notifications, SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true, paired = true)))
        assertEquals(SetupStep.Battery, SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true, paired = true, notificationsSkipped = true)))
        assertNull(SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true, paired = true, hasNotifications = true, batteryDone = true)))
    }

    @Test fun progressPosition() {
        val state = SetupUiState.from(inputs(welcomeDone = true, hasLocalNetwork = true), keepConnected = true)
        assertEquals(SetupStep.PairMac, state.step)
        assertEquals(3, state.position)
        assertEquals(5, state.total)
        assertTrue(state.firstRun)
    }

    // Review Focus #1
    @Test fun upgradedUserGoesStraightToMain() {
        assertNull(SetupFlow.current(inputs(setupDone = true, hasLocalNetwork = true, paired = true)))
    }

    // Review Focus #2
    @Test fun revokedLocalNetworkRoutesToThatStepOnly() {
        val revoked = inputs(setupDone = true, hasLocalNetwork = false, paired = true)
        assertEquals(listOf(SetupStep.LocalNetwork), SetupFlow.steps(revoked))
        assertEquals(SetupStep.LocalNetwork, SetupFlow.current(revoked))
        assertFalse(SetupUiState.from(revoked, keepConnected = true).firstRun)
    }

    // Review Focus #2
    @Test fun revokedNotificationsDoNotForceSetup() {
        assertNull(SetupFlow.current(inputs(setupDone = true, hasLocalNetwork = true, paired = true, hasNotifications = false)))
    }

    @Test fun forgottenMacAfterSetupNeedsOnlyPairing() {
        assertEquals(listOf(SetupStep.PairMac), SetupFlow.steps(inputs(setupDone = true, hasLocalNetwork = true, paired = false)))
    }

    @Test fun readyToConnectNeedsPairingPermissionAndPreference() {
        val ready = inputs(setupDone = true, hasLocalNetwork = true, paired = true)
        assertTrue(SetupUiState.from(ready, keepConnected = true).readyToConnect)
        assertFalse(SetupUiState.from(ready, keepConnected = false).readyToConnect)
        assertFalse(SetupUiState.from(ready.copy(hasLocalNetwork = false), keepConnected = true).readyToConnect)
        assertFalse(SetupUiState.from(ready.copy(paired = false), keepConnected = true).readyToConnect)
    }
}
```

`android/app/src/test/java/dev/macdroid/app/ManualEntryTest.kt`:
```kotlin
package dev.macdroid.app

import dev.macdroid.app.ui.setup.ManualEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Review Focus #4
class ManualEntryTest {
    @Test fun acceptsSpacesDashesAndLowercase() {
        assertEquals("MZXW6YTBOI2DGNBVGY3TQOJQGE", ManualEntry.normalizedCodeOrNull(" mzxw 6ytb-oi2d gnbv gy3t qojq ge "))
    }

    @Test fun rejectsWrongLength() {
        assertNull(ManualEntry.normalizedCodeOrNull("MZXW6YTB"))
        assertNull(ManualEntry.normalizedCodeOrNull("MZXW6YTBOI2DGNBVGY3TQOJQGEA"))
    }

    @Test fun rejectsCharactersOutsideBase32() {
        assertNull(ManualEntry.normalizedCodeOrNull("MZXW6YTBOI2DGNBVGY3TQOJQG1"))
    }

    @Test fun rejectsEmpty() {
        assertNull(ManualEntry.normalizedCodeOrNull(""))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: compilation fails (`Unresolved reference 'StatusPresentation'`, `'SetupFlow'`, `'ManualEntry'`).

- [ ] **Step 4: Implement the pure logic**

`android/app/src/main/java/dev/macdroid/app/ui/presentation/StatusPresentation.kt`:
```kotlin
package dev.macdroid.app.ui.presentation

import dev.macdroid.core.connection.ConnectionState

enum class StatusTone { Connected, Pending, Problem, Idle }

enum class StatusAction { None, RetryNow, PairAgain, Connect }

data class StatusModel(val title: String, val subtitle: String, val tone: StatusTone, val action: StatusAction)

/** One source of truth for how connection state reads, on Home and in the notification. */
object StatusPresentation {
    fun home(state: ConnectionState, macName: String, waitingRemainingMs: Long? = null): StatusModel = when (state) {
        is ConnectionState.Connected -> StatusModel(state.macName, "Connected", StatusTone.Connected, StatusAction.None)
        ConnectionState.Connecting -> StatusModel(macName, "Connecting…", StatusTone.Pending, StatusAction.None)
        is ConnectionState.Waiting -> {
            val remaining = waitingRemainingMs ?: state.retryInMs
            val subtitle = if (remaining > 0) "Looking for your Mac… retrying in ${seconds(remaining)} s" else "Reconnecting…"
            StatusModel(macName, subtitle, StatusTone.Pending, StatusAction.RetryNow)
        }
        is ConnectionState.TrustFailure ->
            StatusModel("Pairing problem", "Your Mac no longer recognises this phone.", StatusTone.Problem, StatusAction.PairAgain)
        ConnectionState.Stopped -> StatusModel(macName, "Disconnected", StatusTone.Idle, StatusAction.Connect)
        ConnectionState.NotPaired -> StatusModel("Not paired", "Pair with your Mac to get started.", StatusTone.Idle, StatusAction.None)
    }

    fun notificationTitle(state: ConnectionState): String = when (state) {
        ConnectionState.Stopped -> "Disconnected"
        ConnectionState.NotPaired -> "Not paired"
        ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Connected -> "Connected to ${state.macName}"
        is ConnectionState.Waiting -> "Mac not reachable — retrying in ${seconds(state.retryInMs)}s"
        is ConnectionState.TrustFailure -> "Pairing problem — open MacDroid to re-pair"
    }

    private fun seconds(ms: Long): Long = (ms + 999) / 1000
}
```

`android/app/src/main/java/dev/macdroid/app/ui/setup/SetupFlow.kt`:
```kotlin
package dev.macdroid.app.ui.setup

enum class SetupStep { Welcome, LocalNetwork, PairMac, Notifications, Battery }

data class SetupInputs(
    val setupDone: Boolean,
    val welcomeDone: Boolean,
    val needsLocalNetwork: Boolean,
    val hasLocalNetwork: Boolean,
    val paired: Boolean,
    val needsNotifications: Boolean,
    val hasNotifications: Boolean,
    val notificationsSkipped: Boolean,
    val batteryDone: Boolean,
)

/**
 * Which setup steps to show. First run walks every step; after setup only what's required to
 * work again (local network permission, pairing) is shown. Notifications never force setup.
 */
object SetupFlow {
    fun steps(i: SetupInputs): List<SetupStep> =
        if (i.setupDone) {
            buildList {
                if (i.needsLocalNetwork && !i.hasLocalNetwork) add(SetupStep.LocalNetwork)
                if (!i.paired) add(SetupStep.PairMac)
            }
        } else {
            buildList {
                add(SetupStep.Welcome)
                if (i.needsLocalNetwork) add(SetupStep.LocalNetwork)
                add(SetupStep.PairMac)
                if (i.needsNotifications) add(SetupStep.Notifications)
                add(SetupStep.Battery)
            }
        }

    fun current(i: SetupInputs): SetupStep? = steps(i).firstOrNull { !isDone(it, i) }

    private fun isDone(step: SetupStep, i: SetupInputs): Boolean = when (step) {
        SetupStep.Welcome -> i.welcomeDone
        SetupStep.LocalNetwork -> !i.needsLocalNetwork || i.hasLocalNetwork
        SetupStep.PairMac -> i.paired
        SetupStep.Notifications -> !i.needsNotifications || i.hasNotifications || i.notificationsSkipped
        SetupStep.Battery -> i.batteryDone || i.setupDone
    }
}

data class SetupUiState(
    val step: SetupStep?,
    /** 1-based position of [step] among the steps shown in this run. */
    val position: Int,
    val total: Int,
    val firstRun: Boolean,
    /** Paired, allowed on the local network, and the user wants to stay connected. */
    val readyToConnect: Boolean,
) {
    companion object {
        fun from(i: SetupInputs, keepConnected: Boolean): SetupUiState {
            val steps = SetupFlow.steps(i)
            val step = SetupFlow.current(i)
            return SetupUiState(
                step = step,
                position = if (step == null) steps.size else steps.indexOf(step) + 1,
                total = steps.size,
                firstRun = !i.setupDone,
                readyToConnect = i.paired && (!i.needsLocalNetwork || i.hasLocalNetwork) && keepConnected,
            )
        }
    }
}
```

`android/app/src/main/java/dev/macdroid/app/ui/setup/ManualEntry.kt`:
```kotlin
package dev.macdroid.app.ui.setup

import dev.macdroid.core.pairing.PairingInvite

object ManualEntry {
    private val BASE32_CODE = Regex("^[A-Z2-7]{26}$")

    /** The code the Mac shows is 26 base32 characters; spaces, dashes and case are ignored. */
    fun normalizedCodeOrNull(raw: String): String? = PairingInvite.normalizeCode(raw).takeIf { BASE32_CODE.matches(it) }
}
```

`android/app/src/main/java/dev/macdroid/app/ui/theme/Theme.kt`:
```kotlin
package dev.macdroid.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Material You colours from the wallpaper on Android 12+, a warm fallback palette before that. */
@Composable
fun MacDroidTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF8B5000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBD),
    onPrimaryContainer = Color(0xFF2C1600),
    surface = Color(0xFFFFF8F4),
    background = Color(0xFFFFF8F4),
    surfaceVariant = Color(0xFFF3E4D7),
    onSurfaceVariant = Color(0xFF51443A),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFFFB86E),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF6A3B00),
    onPrimaryContainer = Color(0xFFFFDCBD),
    surface = Color(0xFF19120C),
    background = Color(0xFF19120C),
    surfaceVariant = Color(0xFF51443A),
    onSurfaceVariant = Color(0xFFD5C3B5),
)
```

- [ ] **Step 5: Wire prefs, notification text, boot, disconnect, manual validation**

In `MacDroidApp.kt`, add these to `class UiPrefs` after `setupDone`:
```kotlin
    /** Off after the user taps Disconnect or turns off "Keep connected"; the app then shows a Connect button. */
    var keepConnected: Boolean
        get() = prefs.getBoolean("keepConnected", true)
        set(value) { prefs.edit().putBoolean("keepConnected", value).apply() }

    var notificationsSkipped: Boolean
        get() = prefs.getBoolean("notificationsSkipped", false)
        set(value) { prefs.edit().putBoolean("notificationsSkipped", value).apply() }
```

In `service/ServiceSupport.kt`:
- Replace the body of `StatusText.title` with a delegation, keeping `LOCAL_NETWORK_NEEDED`:
```kotlin
    fun title(state: ConnectionState): String = dev.macdroid.app.ui.presentation.StatusPresentation.notificationTitle(state)
```
- In `BootReceiver.onReceive`, add `context.graph.uiPrefs.keepConnected &&` to the condition, before `Permissions.hasLocalNetwork(context)`.

In `service/ConnectionService.kt` `onStartCommand`, replace
`if (intent?.action == ACTION_DISCONNECT) stopSelf()` with:
```kotlin
        if (intent?.action == ACTION_DISCONNECT) {
            graph.uiPrefs.keepConnected = false // the app shows "Disconnected · Connect" instead of reconnecting on its own
            stopSelf()
        }
```

In `ui/PairingViewModel.kt` `onManual`, insert right after the host/port validation block (before `_state.value = UiState.Working`):
```kotlin
        val code = ManualEntry.normalizedCodeOrNull(codeInput)
        if (code == null) {
            _state.value = UiState.Error("The code is 26 letters and numbers — check it against “Can't scan?” on your Mac.")
            return
        }
```
Then:
- Change the `UiState.Confirm(host, port, PairingInvite.normalizeCode(codeInput), fingerprint)` construction to `UiState.Confirm(host, port, code, fingerprint)`.
- Add `import dev.macdroid.app.ui.setup.ManualEntry`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :core:test`
Expected: `BUILD SUCCESSFUL`; the new tests pass, `StatusTextTest`/`ScanGateTest`/`NsdMacLocatorTest` still pass, no new warnings.

- [ ] **Step 7: Commit**

```bash
git add android/app
git commit -m "feat(android): Material You theme, status and setup presentation logic"
```

---

## Task 4: Android setup wizard and navigation host

**Files:**
- Create:
  - `android/app/src/main/java/dev/macdroid/app/ui/setup/SetupViewModel.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/setup/SetupScreen.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/setup/PairStep.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/setup/ManualPairSheet.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/navigation/AppNavHost.kt`
- Rewrite: `android/app/src/main/java/dev/macdroid/app/ui/MainActivity.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/QrScanner.kt`
- Delete: `android/app/src/main/java/dev/macdroid/app/ui/OnboardingScreens.kt`

**Interfaces:**
- Consumes:
  - Task 3: `SetupFlow`, `SetupInputs`, `SetupStep`, `SetupUiState`, `MacDroidTheme`, `UiPrefs.keepConnected`/`notificationsSkipped`.
  - Existing: `PairingViewModel` (`state`, `onScanned`, `onManual`, `confirmFingerprint`, `reset`, `resetIfFinished`, and `UiState` with `Idle`, `Working`, `Confirm`, `Error`, `Paired`), `Permissions`, `ConnectionService.start/stop`, `AppGraph`, and the existing `dev.macdroid.app.ui.HomeScreen(graph, onForget)`.
- Produces:
  - `SetupViewModel` with `state: StateFlow<SetupUiState>`, `macName: StateFlow<String?>`, `refresh()`, `completeWelcome()`, `skipNotifications()`, `completeBattery()`.
  - `AppNavHost(graph)`, using routes `Routes.SETUP` and `Routes.MAIN`.
  - `forgetMac(context, graph)`.
  - `QrCameraPreview(enabled, onResult, onCameraError, modifier)`, now `internal`.
- In this task the `main` route still shows the existing `HomeScreen`. Task 5 replaces it with the tabbed `MainScreen`.

- [ ] **Step 1: Expose the camera preview and remove the old onboarding**

In `ui/QrScanner.kt`:
- Delete the `QrScannerScreen` composable.
- Rename `private fun CameraPreview(` to `internal fun QrCameraPreview(`.
- Remove imports that are now unused (`rememberLauncherForActivityResult`, `ActivityResultContracts`, `Manifest`, `Column`, `TextButton`, `Text`, `Box`, `fillMaxSize`, `fillMaxWidth`, `padding`, `Alignment`, `dp`, `LaunchedEffect`, `Permissions`). Keep everything `QrCameraPreview` uses.

Run: `git rm android/app/src/main/java/dev/macdroid/app/ui/OnboardingScreens.kt`

- [ ] **Step 2: Setup view model**

`android/app/src/main/java/dev/macdroid/app/ui/setup/SetupViewModel.kt`:
```kotlin
package dev.macdroid.app.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.platform.Permissions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private data class Local(
        val setupDone: Boolean,
        val hasLocalNetwork: Boolean,
        val hasNotifications: Boolean,
        val notificationsSkipped: Boolean,
        val keepConnected: Boolean,
    )

    private val graph = application.graph
    private val local = MutableStateFlow(readLocal())
    private val welcomeDone = MutableStateFlow(false)
    private val batteryDone = MutableStateFlow(false)
    private var sawUnpaired = graph.pairingStore.paired.value == null

    /** Pairing state, holding "paired" back for a moment after a fresh pairing so the success screen is seen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val paired: Flow<Boolean> = graph.pairingStore.paired
        .map { it != null }
        .distinctUntilChanged()
        .transformLatest { isPaired ->
            if (isPaired && sawUnpaired) delay(SUCCESS_PAUSE_MS)
            sawUnpaired = !isPaired
            emit(isPaired)
        }

    val macName: StateFlow<String?> = graph.pairingStore.paired
        .map { it?.name }
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.pairingStore.paired.value?.name)

    val state: StateFlow<SetupUiState> = combine(local, welcomeDone, batteryDone, paired) { l, welcome, battery, isPaired ->
        SetupUiState.from(inputs(l, welcome, battery, isPaired), l.keepConnected)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SetupUiState.from(inputs(local.value, false, false, graph.pairingStore.paired.value != null), local.value.keepConnected),
    )

    /** Re-read permissions and preferences (call on resume and after a permission prompt). */
    fun refresh() {
        local.value = readLocal()
    }

    fun completeWelcome() {
        welcomeDone.value = true
    }

    fun skipNotifications() {
        graph.uiPrefs.notificationsSkipped = true
        refresh()
    }

    fun completeBattery() {
        graph.uiPrefs.setupDone = true
        batteryDone.value = true
        refresh()
    }

    private fun inputs(l: Local, welcome: Boolean, battery: Boolean, isPaired: Boolean) = SetupInputs(
        setupDone = l.setupDone,
        welcomeDone = welcome,
        needsLocalNetwork = Permissions.needsLocalNetwork,
        hasLocalNetwork = l.hasLocalNetwork,
        paired = isPaired,
        needsNotifications = Permissions.needsNotifications,
        hasNotifications = l.hasNotifications,
        notificationsSkipped = l.notificationsSkipped,
        batteryDone = battery,
    )

    private fun readLocal(): Local {
        val app = getApplication<Application>()
        return Local(
            setupDone = graph.uiPrefs.setupDone,
            hasLocalNetwork = Permissions.hasLocalNetwork(app),
            hasNotifications = Permissions.hasNotifications(app),
            notificationsSkipped = graph.uiPrefs.notificationsSkipped,
            keepConnected = graph.uiPrefs.keepConnected,
        )
    }

    private companion object {
        const val SUCCESS_PAUSE_MS = 1_000L
    }
}
```

- [ ] **Step 3: Setup screens**

`android/app/src/main/java/dev/macdroid/app/ui/setup/SetupScreen.kt`:
```kotlin
package dev.macdroid.app.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.macdroid.app.platform.Permissions
import dev.macdroid.app.ui.PairingViewModel

@Composable
fun SetupScreen(viewModel: SetupViewModel, pairingViewModel: PairingViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val macName by viewModel.macName.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (state.firstRun && state.total > 1) {
            LinearProgressIndicator(
                progress = { state.position.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        AnimatedContent(targetState = state.step, label = "setup-step", modifier = Modifier.weight(1f)) { step ->
            when (step) {
                SetupStep.Welcome -> WelcomeStep(onContinue = viewModel::completeWelcome)
                SetupStep.LocalNetwork -> LocalNetworkStep(onResult = viewModel::refresh)
                SetupStep.PairMac -> PairStep(pairingViewModel, macName)
                SetupStep.Notifications -> NotificationsStep(onResult = viewModel::refresh, onSkip = viewModel::skipNotifications)
                SetupStep.Battery -> BatteryStep(onDone = viewModel::completeBattery)
                null -> Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    StepLayout(
        icon = Icons.Rounded.Laptop,
        title = "Welcome to MacDroid",
        body = "Your phone and your Mac, working together — right over your Wi-Fi. No account, and nothing leaves your network.",
        primaryLabel = "Get started",
        onPrimary = onContinue,
    )
}

@Composable
private fun LocalNetworkStep(onResult: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    StepLayout(
        icon = Icons.Rounded.Wifi,
        title = "Find your Mac on Wi-Fi",
        body = "MacDroid talks to your Mac directly over your local network. Android asks your permission for apps to reach devices on your network.",
        primaryLabel = "Allow",
        onPrimary = { launcher.launch(Permissions.ACCESS_LOCAL_NETWORK) },
        secondaryLabel = "Open settings",
        onSecondary = { context.openAppSettings() },
    )
}

@Composable
private fun NotificationsStep(onResult: () -> Unit, onSkip: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    StepLayout(
        icon = Icons.Rounded.NotificationsActive,
        title = "Stay in the loop",
        body = "Allow notifications so MacDroid can show whether your Mac is connected.",
        primaryLabel = "Allow",
        onPrimary = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        secondaryLabel = "Skip",
        onSecondary = onSkip,
    )
}

@Composable
private fun BatteryStep(onDone: () -> Unit) {
    val context = LocalContext.current
    StepLayout(
        icon = Icons.Rounded.BatteryChargingFull,
        title = "Keep MacDroid running",
        body = "Android may pause apps in the background. Set MacDroid to “Unrestricted” so it stays connected to your Mac.\n\nOn Nothing phones: App info → Battery → Unrestricted.",
        primaryLabel = "Open battery settings",
        onPrimary = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
        secondaryLabel = "Done",
        onSecondary = onDone,
    )
}

/** Shared layout for permission-style steps: icon in a tonal circle, title, one-line reason, actions at the bottom. */
@Composable
internal fun StepLayout(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(112.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        extra()
        Spacer(Modifier.weight(1f))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(primaryLabel) }
        if (secondaryLabel != null) {
            TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondaryLabel) }
        }
    }
}

internal fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}
```

`android/app/src/main/java/dev/macdroid/app/ui/setup/PairStep.kt`:
```kotlin
package dev.macdroid.app.ui.setup

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.macdroid.app.platform.Permissions
import dev.macdroid.app.ui.PairingViewModel
import dev.macdroid.app.ui.QrCameraPreview
import dev.macdroid.core.tls.Fingerprint

/** The hero setup step: camera inline, manual entry as a fallback, result shown in place. */
@Composable
fun PairStep(viewModel: PairingViewModel, macName: String?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Clear a stale result once per visit to this step — not on rotation (rememberSaveable survives it).
    var resetDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!resetDone) {
            viewModel.resetIfFinished()
            resetDone = true
        }
    }
    var showManual by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Pair with your Mac", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
        Text(
            "On your Mac, open MacDroid from the menu bar and choose Pair a Phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
        )
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                PairingViewModel.UiState.Working -> Message(null, "Pairing with your Mac…", null) { CircularProgressIndicator() }
                PairingViewModel.UiState.Paired -> Message(Icons.Rounded.CheckCircle, "Paired with ${macName ?: "your Mac"}", null)
                is PairingViewModel.UiState.Error -> Message(Icons.Rounded.ErrorOutline, s.message, null, iconTint = MaterialTheme.colorScheme.error) {
                    Button(onClick = viewModel::reset) { Text("Try again") }
                }
                else -> CameraArea(enabled = s == PairingViewModel.UiState.Idle, onResult = viewModel::onScanned)
            }
        }
        TextButton(
            onClick = { showManual = true },
            enabled = state == PairingViewModel.UiState.Idle,
            modifier = Modifier.padding(vertical = 12.dp),
        ) { Text("Enter code manually") }
    }

    if (showManual) {
        ManualPairSheet(
            onDismiss = { showManual = false },
            onSubmit = { host, port, code ->
                showManual = false
                viewModel.onManual(host, port, code)
            },
        )
    }
    (state as? PairingViewModel.UiState.Confirm)?.let { confirm ->
        AlertDialog(
            onDismissRequest = viewModel::reset,
            title = { Text("Check the fingerprint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Does your Mac show this fingerprint under “Can't scan?”")
                    Text(Fingerprint.display(confirm.fingerprint), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmFingerprint) { Text("They match") } },
            dismissButton = { TextButton(onClick = viewModel::reset) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CameraArea(enabled: Boolean, onResult: (String) -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(Permissions.granted(context, Manifest.permission.CAMERA)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
        asked = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasCamera = Permissions.granted(context, Manifest.permission.CAMERA) }
    LaunchedEffect(Unit) { if (!hasCamera && !asked) launcher.launch(Manifest.permission.CAMERA) }
    when {
        cameraError -> Message(Icons.Rounded.NoPhotography, "Camera unavailable", "Close other apps using the camera, or enter the code manually.")
        hasCamera -> QrCameraPreview(enabled, onResult, { cameraError = true }, Modifier.fillMaxSize())
        else -> Message(Icons.Rounded.PhotoCamera, "Camera access needed", "Allow the camera to scan the code, or enter it manually.") {
            Button(onClick = { if (asked) context.openAppSettings() else launcher.launch(Manifest.permission.CAMERA) }) {
                Text(if (asked) "Open settings" else "Allow camera")
            }
        }
    }
}

@Composable
private fun Message(
    icon: ImageVector?,
    title: String,
    body: String?,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    action: @Composable () -> Unit = {},
) {
    Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = iconTint)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        action()
    }
}
```

`android/app/src/main/java/dev/macdroid/app/ui/setup/ManualPairSheet.kt`:
```kotlin
package dev.macdroid.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualPairSheet(onDismiss: () -> Unit, onSubmit: (host: String, port: String, code: String) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Enter pairing details", style = MaterialTheme.typography.titleLarge)
            Text(
                "Find these under “Can't scan?” in MacDroid on your Mac.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                host, { host = it },
                label = { Text("Address") }, placeholder = { Text("192.168.1.20") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) },
                label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                code, { code = it },
                label = { Text("Code") }, placeholder = { Text("MZXW 6YTB …") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSubmit(host, port, code) },
                enabled = host.isNotBlank() && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Connect") }
        }
    }
}
```

- [ ] **Step 4: Navigation host and activity**

`android/app/src/main/java/dev/macdroid/app/ui/navigation/AppNavHost.kt`:
```kotlin
package dev.macdroid.app.ui.navigation

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.macdroid.app.AppGraph
import dev.macdroid.app.service.ConnectionService
import dev.macdroid.app.ui.HomeScreen
import dev.macdroid.app.ui.setup.SetupScreen
import dev.macdroid.app.ui.setup.SetupViewModel

object Routes {
    const val SETUP = "setup"
    const val MAIN = "main"
}

/** Top level: the setup wizard whenever something required is missing, otherwise the main app. */
@Composable
fun AppNavHost(graph: AppGraph) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val setupViewModel: SetupViewModel = viewModel()
    val setup by setupViewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setupViewModel.refresh() }

    val needsSetup = setup.step != null
    val start = remember { if (needsSetup) Routes.SETUP else Routes.MAIN }
    LaunchedEffect(needsSetup) {
        val target = if (needsSetup) Routes.SETUP else Routes.MAIN
        if (nav.currentDestination?.route != target) {
            nav.navigate(target) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(setup.readyToConnect) {
        if (setup.readyToConnect) {
            ConnectionService.start(context)
            graph.connectionManager.nudge()
        }
    }

    NavHost(nav, startDestination = start) {
        composable(Routes.SETUP) { SetupScreen(setupViewModel) }
        composable(Routes.MAIN) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                HomeScreen(graph, onForget = {
                    forgetMac(context, graph)
                    setupViewModel.refresh()
                })
            }
        }
    }
}

/** Stops the connection and forgets the Mac; setup then shows only the pairing step. */
fun forgetMac(context: Context, graph: AppGraph) {
    ConnectionService.stop(context)
    graph.connectionManager.stop()
    graph.pairingStore.clear()
}
```

`android/app/src/main/java/dev/macdroid/app/ui/MainActivity.kt` (full replacement):
```kotlin
package dev.macdroid.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.macdroid.app.graph
import dev.macdroid.app.ui.navigation.AppNavHost
import dev.macdroid.app.ui.theme.MacDroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = graph
        setContent {
            MacDroidTheme {
                Surface(Modifier.fillMaxSize()) { AppNavHost(graph) }
            }
        }
    }
}
```

- [ ] **Step 5: Build, test, install, smoke check**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :core:test`
Expected: `BUILD SUCCESSFUL` with no new warnings. If a Material 3 API in this BOM is not experimental, a lone `@OptIn(ExperimentalMaterial3Api::class)` is harmless.

Install on the phone if one is attached (`adb devices` lists it; use that serial):
```bash
ANDROID_SERIAL=<serial> ./gradlew :app:installDebug
adb -s <serial> shell am start -n dev.macdroid.app/.ui.MainActivity && sleep 3
adb -s <serial> logcat -d -t 300 | grep -E "AndroidRuntime|FATAL" || echo "no crash"
mkdir -p ../.superpowers/screens && adb -s <serial> exec-out screencap -p > ../.superpowers/screens/android-task4.png
```
Expected: no crash. The paired phone lands on the main route, because setup isn't needed. Do **not** run `pm clear`, grant permissions or tap on the phone; the setup screens are checked by the human in Task 5's checklist.

- [ ] **Step 6: Commit**

```bash
git add android/app
git commit -m "feat(android): pairing-centred setup wizard and navigation host"
```

---

## Task 5: Android main app — bottom navigation, Home, Settings, coming-soon tabs

**Files:**
- Create:
  - `android/app/src/main/java/dev/macdroid/app/ui/main/MainScreen.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/main/HomeScreen.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/main/HomeViewModel.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/main/StatusCard.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/main/SettingsScreen.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/main/SettingsViewModel.kt`
  - `android/app/src/main/java/dev/macdroid/app/ui/main/ComingSoonScreen.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/navigation/AppNavHost.kt` (the main route uses `MainScreen`)
- Delete: `android/app/src/main/java/dev/macdroid/app/ui/HomeScreen.kt`

**Interfaces:**
- Consumes:
  - Task 3: `StatusPresentation.home`, `StatusModel`, `StatusTone`, `StatusAction`, `UiPrefs.keepConnected`.
  - Task 4: `forgetMac(context, graph)`, `Routes`.
  - Existing: `AppGraph` (`connectionManager`, `pairingStore`, `uiPrefs`), `ConnectionService.start/stop`, `Permissions`, `Fingerprint.display`, `PairedMac`, `BuildConfig.VERSION_NAME`.
- Produces: `MainScreen(onForget: () -> Unit)`. This is the final UI.

- [ ] **Step 1: View models**

`android/app/src/main/java/dev/macdroid/app/ui/main/HomeViewModel.kt`:
```kotlin
package dev.macdroid.app.ui.main

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.service.ConnectionService
import dev.macdroid.app.ui.presentation.StatusModel
import dev.macdroid.app.ui.presentation.StatusPresentation
import dev.macdroid.core.connection.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeUi(val status: StatusModel, val macName: String?)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph

    /** Each state with the moment it was entered, so "retrying in N s" can count down live. */
    private val stateSince = graph.connectionManager.state.map { it to SystemClock.elapsedRealtime() }
    private val ticker = flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(1_000)
        }
    }

    val ui: StateFlow<HomeUi> = combine(stateSince, graph.pairingStore.paired, ticker) { since, mac, now ->
        val (state, enteredAt) = since
        val remaining = (state as? ConnectionState.Waiting)?.let { it.retryInMs - (now - enteredAt) }
        HomeUi(StatusPresentation.home(state, mac?.name ?: "Your Mac", remaining), mac?.name)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HomeUi(
            StatusPresentation.home(graph.connectionManager.state.value, graph.pairingStore.paired.value?.name ?: "Your Mac"),
            graph.pairingStore.paired.value?.name,
        ),
    )

    fun retryNow() = graph.connectionManager.nudge()

    fun connect() {
        graph.uiPrefs.keepConnected = true
        ConnectionService.start(getApplication())
        graph.connectionManager.nudge()
    }
}
```

`android/app/src/main/java/dev/macdroid/app/ui/main/SettingsViewModel.kt`:
```kotlin
package dev.macdroid.app.ui.main

import android.app.Application
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.platform.Permissions
import dev.macdroid.app.service.ConnectionService
import dev.macdroid.core.pairing.PairedMac
import dev.macdroid.core.tls.Fingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SettingsUi(
    val mac: PairedMac?,
    val keepConnected: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val macSummary: String get() = mac?.let { "${it.name} · ${Fingerprint.display(it.fingerprint)}" } ?: "Not paired"
    val permissionsSummary: String get() = if (notificationsAllowed) "All set ✓" else "Notifications are off — tap to turn on"
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val local = MutableStateFlow(read())

    val ui: StateFlow<SettingsUi> = combine(graph.pairingStore.paired, local) { mac, l -> l.copy(mac = mac) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun refresh() {
        local.value = read()
    }

    fun setKeepConnected(on: Boolean) {
        val app = getApplication<Application>()
        graph.uiPrefs.keepConnected = on
        if (on) {
            ConnectionService.start(app)
            graph.connectionManager.nudge()
        } else {
            ConnectionService.stop(app)
            graph.connectionManager.stop()
        }
        refresh()
    }

    private fun read(): SettingsUi {
        val app = getApplication<Application>()
        return SettingsUi(
            mac = graph.pairingStore.paired.value,
            keepConnected = graph.uiPrefs.keepConnected,
            notificationsAllowed = Permissions.hasNotifications(app),
            batteryUnrestricted = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName),
        )
    }
}
```

- [ ] **Step 2: Screens**

`android/app/src/main/java/dev/macdroid/app/ui/main/StatusCard.kt`:
```kotlin
package dev.macdroid.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.macdroid.app.ui.presentation.StatusAction
import dev.macdroid.app.ui.presentation.StatusModel
import dev.macdroid.app.ui.presentation.StatusTone

private val ConnectedGreen = Color(0xFF2E7D32)

@Composable
fun StatusCard(status: StatusModel, onAction: (StatusAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (status.tone) {
        StatusTone.Connected -> colors.primaryContainer to colors.onPrimaryContainer
        StatusTone.Problem -> colors.errorContainer to colors.onErrorContainer
        StatusTone.Pending, StatusTone.Idle -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (status.tone == StatusTone.Problem) Icons.Rounded.ErrorOutline else Icons.Rounded.Laptop,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Text(status.title, style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status.tone) {
                    StatusTone.Connected -> Box(Modifier.size(8.dp).background(ConnectedGreen, CircleShape))
                    StatusTone.Pending -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = content)
                    else -> Unit
                }
                Text(status.subtitle, style = MaterialTheme.typography.bodyLarge)
            }
            if (status.tone == StatusTone.Connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip(Icons.Rounded.Wifi, "Wi-Fi", content)
                    InfoChip(Icons.Rounded.BatteryChargingFull, "Battery shared", content)
                }
            }
            when (status.action) {
                StatusAction.RetryNow -> OutlinedButton(onClick = { onAction(status.action) }) { Text("Retry now") }
                StatusAction.PairAgain -> Button(
                    onClick = { onAction(status.action) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                ) { Text("Pair again") }
                StatusAction.Connect -> Button(onClick = { onAction(status.action) }) { Text("Connect") }
                StatusAction.None -> Unit
            }
        }
    }
}

@Composable
private fun InfoChip(icon: ImageVector, label: String, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = content.copy(alpha = 0.10f), contentColor = content) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
```

`android/app/src/main/java/dev/macdroid/app/ui/main/HomeScreen.kt`:
```kotlin
package dev.macdroid.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.macdroid.app.ui.presentation.StatusAction

@Composable
fun HomeScreen(onPairAgain: () -> Unit, viewModel: HomeViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmPairAgain by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("MacDroid", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp))
        StatusCard(ui.status) { action ->
            when (action) {
                StatusAction.RetryNow -> viewModel.retryNow()
                StatusAction.Connect -> viewModel.connect()
                StatusAction.PairAgain -> confirmPairAgain = true
                StatusAction.None -> Unit
            }
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Clipboard, files and notifications will show here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmPairAgain) {
        AlertDialog(
            onDismissRequest = { confirmPairAgain = false },
            title = { Text("Pair again?") },
            text = { Text("This phone will forget ${ui.macName ?: "your Mac"}. Then choose Pair a Phone on your Mac and scan the new code.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPairAgain = false
                    onPairAgain()
                }) { Text("Pair again") }
            },
            dismissButton = { TextButton(onClick = { confirmPairAgain = false }) { Text("Cancel") } },
        )
    }
}
```

`android/app/src/main/java/dev/macdroid/app/ui/main/ComingSoonScreen.kt`:
```kotlin
package dev.macdroid.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ComingSoonScreen(icon: ImageVector, title: String, description: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text("Coming in the next update", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
```

`android/app/src/main/java/dev/macdroid/app/ui/main/SettingsScreen.kt`:
```kotlin
package dev.macdroid.app.ui.main

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.macdroid.app.BuildConfig

@Composable
fun SettingsScreen(onForget: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var showMac by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)) }
        item { SectionHeader("Connection") }
        item {
            ListItem(
                headlineContent = { Text("Paired Mac") },
                supportingContent = { Text(ui.macSummary) },
                leadingContent = { Icon(Icons.Rounded.Laptop, contentDescription = null) },
                modifier = Modifier.clickable(enabled = ui.mac != null) { showMac = true },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Keep connected in background") },
                supportingContent = { Text("Stays connected while you use other apps") },
                leadingContent = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                trailingContent = { Switch(checked = ui.keepConnected, onCheckedChange = viewModel::setKeepConnected) },
            )
        }
        item { SectionHeader("Setup") }
        item {
            ListItem(
                headlineContent = { Text("Permissions") },
                supportingContent = { Text(ui.permissionsSummary) },
                leadingContent = { Icon(Icons.Rounded.Shield, contentDescription = null) },
                modifier = Modifier.clickable { context.openNotificationSettings() },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Battery") },
                supportingContent = { Text(if (ui.batteryUnrestricted) "Unrestricted ✓" else "Optimized — tap to allow background use") },
                leadingContent = { Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null) },
                modifier = Modifier.clickable { context.openBatterySettings() },
            )
        }
        item { SectionHeader("About") }
        item {
            ListItem(
                headlineContent = { Text("Version") },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
                leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Forget this Mac", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(enabled = ui.mac != null) { confirmForget = true },
            )
        }
    }

    val mac = ui.mac
    if (showMac && mac != null) {
        AlertDialog(
            onDismissRequest = { showMac = false },
            title = { Text(mac.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Address: ${mac.host}:${mac.port}")
                    Text("Fingerprint")
                    Text(mac.fingerprint.uppercase().chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { showMac = false }) { Text("Done") } },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget ${mac?.name ?: "this Mac"}?") },
            text = { Text("MacDroid will disconnect, and you'll need to pair again to reconnect.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    onForget()
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp),
    )
}

private fun Context.openNotificationSettings() {
    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}

private fun Context.openBatterySettings() {
    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
```

`android/app/src/main/java/dev/macdroid/app/ui/main/MainScreen.kt`:
```kotlin
package dev.macdroid.app.ui.main

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

enum class MainTab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Rounded.Home),
    Notifications("notifications", "Notifications", Icons.Rounded.Notifications),
    Clipboard("clipboard", "Clipboard", Icons.Rounded.ContentPaste),
    Files("files", "Files", Icons.Rounded.Folder),
    Settings("settings", "Settings", Icons.Rounded.Settings),
}

@Composable
fun MainScreen(onForget: () -> Unit) {
    val tabs = rememberNavController()
    val backStack by tabs.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            tabs.navigate(tab.route) {
                                popUpTo(tabs.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(tabs, startDestination = MainTab.Home.route, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(MainTab.Home.route) { HomeScreen(onPairAgain = onForget) }
            composable(MainTab.Notifications.route) {
                ComingSoonScreen(MainTab.Notifications.icon, "Notifications", "See your phone's notifications on your Mac, reply to messages, and dismiss them on both devices.")
            }
            composable(MainTab.Clipboard.route) {
                ComingSoonScreen(MainTab.Clipboard.icon, "Clipboard", "Copy on your Mac, paste on your phone — and send what you copied back with one tap.")
            }
            composable(MainTab.Files.route) {
                ComingSoonScreen(MainTab.Files.icon, "Files", "Send files and photos between your phone and your Mac in either direction.")
            }
            composable(MainTab.Settings.route) { SettingsScreen(onForget = onForget) }
        }
    }
}
```

- [ ] **Step 3: Switch the main route and delete the old Home**

In `ui/navigation/AppNavHost.kt`, replace the whole `composable(Routes.MAIN) { … }` block with:
```kotlin
        composable(Routes.MAIN) {
            MainScreen(onForget = {
                forgetMac(context, graph)
                setupViewModel.refresh()
            })
        }
```
Then:
- Replace `import dev.macdroid.app.ui.HomeScreen` with `import dev.macdroid.app.ui.main.MainScreen`.
- Remove the imports that are now unused (`Box`, `fillMaxSize`, `safeDrawingPadding`, `Modifier`).

Run: `git rm android/app/src/main/java/dev/macdroid/app/ui/HomeScreen.kt`

- [ ] **Step 4: Build, test, install, screenshot**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :core:test`
Expected: `BUILD SUCCESSFUL`, no new warnings.

If the phone is attached:
```bash
ANDROID_SERIAL=<serial> ./gradlew :app:installDebug
adb -s <serial> shell am start -n dev.macdroid.app/.ui.MainActivity && sleep 3
adb -s <serial> logcat -d -t 300 | grep -E "AndroidRuntime|FATAL" || echo "no crash"
adb -s <serial> exec-out screencap -p > ../.superpowers/screens/android-home.png
```
Expected: no crash. The screenshot shows the Home tab with the status card and the bottom bar. View it and describe it in the report. Leave the app installed. Do not tap, grant permissions or clear data.

- [ ] **Step 5: Commit**

```bash
git add android/app
git commit -m "feat(android): bottom navigation with Home, Settings and coming-soon tabs"
```

- [ ] **Step 6: Human end-to-end checklist (for the user; record results)**

The Mac app is from Task 2 and the phone build from this task. Check:
1. **Mac launch:** the window opens on Phone. Close it and the Dock icon disappears. Relaunch from Finder while it's running and the window reappears.
2. **Mac popover:** shows the phone, status and battery. "Settings… ⌘," opens the window on General.
3. **Mac Settings:**
   - Launch at login toggles; if it fails it reverts with a message.
   - With the Dock toggle off, no Dock icon appears while the window is open.
   - Security shows the fingerprint, and Copy works.
4. **Phone Home:** shows Connected with chips. Quit the Mac app → "Looking for your Mac… retrying in N s" counting down → **Retry now** works after reopening the Mac app.
5. **Phone:** notification **Disconnect** → Home shows "Disconnected · Connect" → Connect reconnects.
6. **Phone Settings:**
   - Keep connected off stops the connection; on restarts it.
   - Battery row shows the real status.
   - Forget asks for confirmation, then returns to the pairing step.
7. **Re-pair:** scan the Mac's QR in the inline camera. "Paired with …" shows for about a second, then Home.
8. **Manual entry:**
   - A short code is rejected with the 26-character message.
   - The correct details lead to the fingerprint dialog, then pairing.
9. **Mac Pair a Different Phone… / Cancel / expired code:** the expired state shows "New code".
10. **Dark mode** looks right on both.
11. **First run:** clear MacDroid's data in phone Settings → Apps → MacDroid → Storage. Expect Welcome → Local network → Pair → Notifications (Skip works) → Battery → Home, with the progress bar advancing. The Mac must then pair the phone again.

---

## Self-Review Notes

- **Spec coverage:**
  - §2.1 app behaviour (Task 2: label opener, reopen, DockPolicy, login-item rule). One refinement: the window doesn't open at a login-item launch when already paired.
  - §2.2 menu-bar icon (Tasks 1, 2).
  - §2.3 popover (Task 2; the quick-action grid is omitted this round, as the spec says).
  - §2.4 window, sidebar, Phone states and Settings sections (Task 2).
  - §2.5 errors and warnings (Task 2: `warnings` vs `transientError`, temporary identity).
  - §3.1 navigation (Tasks 3, 4).
  - §3.2 theme (Task 3).
  - §3.3 wizard (Task 4).
  - §3.4 tabs, Home states, Settings (Task 5).
  - §3.5 notification text from one source (Task 3).
  - §5 follow-ups:
    - persistent warnings, expiry loop, login toggle revert and temporary-identity block (Task 2)
    - forget confirmation and camera Settings link with resume re-check (Tasks 4, 5)
    - rotation-safe reset (Task 4, `rememberSaveable`)
    - manual code validation (Task 3)
  - §6 testing (Tasks 1, 3, plus build gates and screenshots in Tasks 2, 4, 5).
- **Decisions beyond the spec (rulings):**
  - Mac "reconnecting" has no separate state: the Mac hub only knows connected/disconnected, so paired-but-disconnected shows grey "Not connected — waiting for your phone".
  - Android `PairingViewModel`, `ScanGate` and `QrScanner.kt` stay in the `ui` package (reused, not moved) to avoid churn.
  - Setup uses one `setup` route with an internal step switch rather than one route per step.
  - Screenshots are best-effort. The setup screens are human-checked, because driving them needs clearing app data on the user's phone.
