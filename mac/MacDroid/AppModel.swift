import AppKit
import Foundation
import MacDroidCore
import Observation
import UserNotifications

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
    /// The phone was just forgotten: wait for an explicit "Pair a Phone" instead of opening a pairing window on its own.
    private(set) var awaitingPairRequest = false
    private(set) var listeningPort: Int?
    /// Conditions that last until restart (keychain unavailable, server stopped, Bonjour failed). Never cleared by pairing.
    private(set) var warnings: [String] = []
    /// Problem with the last user action; cleared when the next action starts.
    private(set) var transientError: String?
    private(set) var windowRequest: WindowRequest?
    private(set) var transfers: TransferSnapshot = .empty
    private(set) var clipboard: ClipboardState = .empty
    private(set) var extras: PhoneExtrasState = .empty
    @ObservationIgnored private let phoneExtras: PhoneExtras
    @ObservationIgnored private let mediaArt: IconCache
    @ObservationIgnored private let clipboardSync: ClipboardSync
    @ObservationIgnored private let watcher: PasteboardWatcher
    @ObservationIgnored private let clipboardNotifier: ClipboardNotifier
    @ObservationIgnored private let clipboardImages: URL
    private static let clipboardSendKey = "clipboard.sendToPhone"
    private static let clipboardReceiveKey = "clipboard.receiveFromPhone"
    private static let clipboardSizeKey = "clipboard.historySize"
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
    let files: FilesSettings
    @ObservationIgnored private let transferManager: TransferManager
    @ObservationIgnored private let notifier: ReceivedNotifier
    var selectedSection: SidebarSection? = .phone

    let macName: String
    let fingerprint: String
    let usingTemporaryIdentity: Bool

    @ObservationIgnored private let identity: Identity
    @ObservationIgnored private let pairing: PairingManager
    @ObservationIgnored private let hub: ConnectionHub
    @ObservationIgnored private let advertiser = BonjourAdvertiser()
    @ObservationIgnored private var isOpeningWindow = false
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
        let paired = store.load()
        pairedDevice = paired
        pairing = PairingManager(store: store)
        let hub = ConnectionHub(macName: name, appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0")
        self.hub = hub
        let files = FilesSettings()
        files.phoneName = paired?.name ?? "Your phone"
        self.files = files
        let manager = TransferManager(
            stateDirectory: URL.applicationSupportDirectory.appending(path: "Transfers", directoryHint: .isDirectory),
            location: MacReceiveLocation(settings: files),
            sourceAccess: SecurityScopedSourceAccess(),
            clipboardDirectory: URL.applicationSupportDirectory.appending(path: "ClipboardIncoming", directoryHint: .isDirectory),
            send: { message in try await hub.send(message) }
        )
        transferManager = manager
        let router = NotificationRouter()
        self.router = router
        notifier = ReceivedNotifier(router: router)
        clipboardNotifier = ClipboardNotifier(router: router)
        let watcher = PasteboardWatcher()
        self.watcher = watcher
        let defaults = UserDefaults.standard
        let history = ClipboardHistory(
            directory: URL.applicationSupportDirectory.appending(path: "Clipboard", directoryHint: .isDirectory),
            capacity: defaults.object(forKey: Self.clipboardSizeKey) as? Int ?? ClipboardHistory.defaultCapacity
        )
        clipboardImages = history.imagesDirectory
        clipboardSync = ClipboardSync(
            history: history,
            sendToPhone: defaults.object(forKey: Self.clipboardSendKey) as? Bool ?? true,
            receiveFromPhone: defaults.object(forKey: Self.clipboardReceiveKey) as? Bool ?? true,
            send: { message in try await hub.send(message) },
            sendImage: { url in await manager.sendClipboardImage(url) },
            apply: { content in await MainActor.run { watcher.write(content) } },
            cancelImages: { await manager.cancelClipboardTransfers() }
        )
        let icons = IconCache(directory: URL.applicationSupportDirectory.appending(path: "NotificationIcons", directoryHint: .isDirectory))
        self.icons = icons
        phoneNotifier = PhoneNotifier(router: router, icons: icons)
        let showPhone = UserDefaults.standard.object(forKey: Self.showPhoneNotificationsKey) as? Bool ?? true
        showPhoneNotifications = showPhone
        phoneNotifications = PhoneNotifications(icons: icons, enabled: showPhone, send: { message in try await hub.send(message) })
        let mediaArt = IconCache(directory: URL.applicationSupportDirectory.appending(path: "MediaArt", directoryHint: .isDirectory))
        self.mediaArt = mediaArt
        phoneExtras = PhoneExtras(art: mediaArt, send: { message in try await hub.send(message) })
        if temporary {
            warnings.append("MacDroid couldn't open its keychain, so pairing is unavailable until you restart MacDroid.")
        }
    }

    var phoneStatus: PhoneStatus { StatusPresentation.phoneStatus(paired: pairedDevice, connection: status) }
    var showsPairing: Bool { pairedDevice == nil || isRepairing }

    func start() {
        guard tasks.isEmpty else { return }
        let server = MacDroidServer(identity: identity, pairing: pairing, hub: hub, macName: macName, transfers: transferManager)
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
        router.setUp()
        router.onPhoneResponse = { [weak self] response in self?.handlePhoneResponse(response) }
        tasks.append(Task { [phoneExtras, hub] in await phoneExtras.attach(to: hub) })
        tasks.append(Task { [weak self, phoneExtras] in
            for await state in phoneExtras.states { self?.extras = state }
        })
        tasks.append(Task { [phoneExtras] in
            for await event in phoneExtras.events {
                if case .openLink(let url) = event { await MainActor.run { _ = NSWorkspace.shared.open(url) } }
            }
        })
        tasks.append(Task { [clipboardSync, hub] in await clipboardSync.attach(to: hub) })
        tasks.append(Task { [weak self, clipboardSync] in
            for await state in clipboardSync.states { self?.clipboard = state }
        })
        tasks.append(Task { [weak self, clipboardSync] in
            for await event in clipboardSync.events { await self?.handle(event) }
        })
        watcher.start { [clipboardSync] content, changeCount in
            Task { await clipboardSync.localChange(content, changeCount: changeCount) }
        }
        tasks.append(Task { [phoneNotifications, hub] in await phoneNotifications.attach(to: hub) })
        tasks.append(Task { [weak self, phoneNotifications] in
            for await state in phoneNotifications.states { self?.notifications = state }
        })
        tasks.append(Task { [weak self, phoneNotifications] in
            for await event in phoneNotifications.events { await self?.handle(event) }
        })
        tasks.append(Task { [transferManager, hub] in await transferManager.attach(to: hub) })
        tasks.append(Task { [weak self, transferManager] in
            for await snapshot in transferManager.snapshots { self?.transfers = snapshot }
        })
        tasks.append(Task { [weak self, transferManager] in
            for await event in transferManager.events { await self?.handle(event) }
        })
    }

    func showWindow(_ section: SidebarSection = .phone) {
        selectedSection = section
        // An explicit request must not land on a stale "Code expired"; the view stays expired only while it stays visible.
        if section == .phone, showsPairing, pairingExpired {
            pairingExpired = false
            Task { await ensurePairingCode() }
        }
        windowRequest = WindowRequest(section: section)
    }

    /// The user explicitly asked to pair a phone (menu bar "Pair a Phone…").
    func requestPairing() {
        awaitingPairRequest = false
        showWindow(.phone)
        Task { await ensurePairingCode() }
    }

    /// Called whenever the pairing view is visible: make sure there's a code to scan (but don't replace an expired one silently).
    func ensurePairingCode() async {
        guard showsPairing, invite == nil, !pairingExpired, !awaitingPairRequest else { return }
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
        awaitingPairRequest = true
        await pairing.forget()
        await hub.disconnect()
    }

    var canSendFiles: Bool {
        if case .connected = status { return true }
        return false
    }

    var phoneName: String { pairedDevice?.name ?? "your phone" }

    /// Queues dropped or picked files. Anything that can't be sent (folders, files MacDroid can't read) is named in a banner.
    func sendFiles(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        transientError = nil
        Task {
            let report = await transferManager.sendReporting(files: urls)
            guard let first = report.skipped.first else { return }
            transientError = report.skipped.count == 1
                ? "Couldn't send “\(first.name)”: \(first.reason)."
                : "Couldn't send \(report.skipped.count) files (“\(first.name)” and others): \(first.reason)."
        }
    }

    func pickFilesToSend() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.prompt = "Send"
        panel.message = "Choose files to send to \(phoneName)."
        NSApp.activate()
        guard panel.runModal() == .OK else { return }
        sendFiles(panel.urls)
    }

    // MARK: Small wins (spec §4)

    func ringPhone() { Task { await phoneExtras.ring() } }
    func stopRingingPhone() { Task { await phoneExtras.stopRing() } }
    func mediaCommand(_ kind: MediaCommandKind, positionMs: Int64? = nil) { Task { await phoneExtras.command(kind, positionMs: positionMs) } }
    func mediaArtURL(_ hash: String?) -> URL? { hash.flatMap(mediaArt.url(for:)) }

    /// Services → "Open on Phone".
    func sendLinkToPhone(_ text: String) {
        Task {
            switch await phoneExtras.sendLink(in: text) {
            case .sent: break
            case .noLink: await notifyInfo("No link in the selection")
            case .notConnected: await notifyInfo("Your phone isn't connected")
            case .unsupported: await notifyInfo("Update MacDroid on your phone to use this")
            }
        }
    }

    private func notifyInfo(_ text: String) async {
        guard await router.ensureAuthorized() else { return }
        let content = UNMutableNotificationContent()
        content.title = "MacDroid"
        content.body = text
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "dev.macdroid.info", content: content, trigger: nil))
    }

    // MARK: Clipboard (spec §4)

    private func handle(_ event: ClipboardEvent) async {
        switch event {
        case .receivedFromPhone(let item): await clipboardNotifier.notify(item, from: pairedDevice?.name ?? "your phone")
        }
    }

    func copyClipboardItem(_ id: String) { Task { await clipboardSync.copyToMac(id: id) } }
    func sendClipboardItem(_ id: String) { Task { await clipboardSync.sendToPhone(id: id) } }

    func setClipboardSendToPhone(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: Self.clipboardSendKey)
        Task { await clipboardSync.setSendToPhone(on) }
    }

    func setClipboardReceiveFromPhone(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: Self.clipboardReceiveKey)
        Task { await clipboardSync.setReceiveFromPhone(on) }
    }

    func setClipboardHistorySize(_ size: Int) {
        UserDefaults.standard.set(size, forKey: Self.clipboardSizeKey)
        Task { await clipboardSync.setCapacity(size) }
    }

    func clearClipboardHistory() { Task { await clipboardSync.clearHistory() } }

    func clipboardImageURL(_ item: ClipboardItem) -> URL {
        ClipboardHistory.imageURL(imagesDirectory: clipboardImages, id: item.id)
    }

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

    /// Dismisses several notifications (one app's group) on both devices.
    func dismissNotifications(_ keys: [String]) {
        Task { for key in keys { await phoneNotifications.dismiss(key: key) } }
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

    func cancelTransfer(_ id: String) { Task { await transferManager.cancel(id: id) } }
    func retryTransfer(_ id: String) { Task { await transferManager.retry(id: id) } }

    /// A received file can be deleted or moved after it arrived; rows and actions check before using it.
    func receivedFileExists(_ record: TransferRecord) -> Bool {
        guard record.direction == .fromPhone, let url = record.savedURL else { return true }
        return FileManager.default.fileExists(atPath: url.path(percentEncoded: false))
    }

    func revealReceived(_ record: TransferRecord) {
        guard let url = usableReceivedFile(record) else { return }
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    func openReceived(_ record: TransferRecord) {
        guard let url = usableReceivedFile(record) else { return }
        NSWorkspace.shared.open(url)
    }

    private func usableReceivedFile(_ record: TransferRecord) -> URL? {
        guard let url = record.savedURL else { return nil }
        guard receivedFileExists(record) else {
            transientError = "“\(record.name)” is no longer on this Mac — it was deleted or moved."
            return nil
        }
        transientError = nil
        return url
    }

    private func handle(_ event: TransferEvent) async {
        switch event {
        case .received(let record): await notifier.notifyReceived(record, from: pairedDevice?.name ?? "your phone")
        case .clipboardImageReceived(let url): await clipboardSync.imageReceived(url)
        case .photoReceived, .photoFailed: break // Task 8
        }
    }

    private func beginPairing() async {
        guard !usingTemporaryIdentity else {
            transientError = "Pairing is unavailable because MacDroid couldn't open its keychain. Quit and reopen MacDroid to try again."
            return
        }
        guard !isOpeningWindow else { return }
        isOpeningWindow = true
        defer { isOpeningWindow = false }
        guard let port = listeningPort else { transientError = "MacDroid is still starting — try again in a moment."; return }
        guard let host = LocalAddress.primaryIPv4() else { transientError = "No network connection. Join Wi-Fi and try again."; return }
        transientError = nil
        pairingExpired = false
        awaitingPairRequest = false
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
            // A newer code is already open: this close is for a replaced window, so ignore it.
            if await pairing.currentWindow() != nil { return }
            if let expires = inviteExpiresAt, expires <= Date() { pairingExpired = true }
            invite = nil
            inviteExpiresAt = nil
        case .paired(let device):
            pairedDevice = device
            files.phoneName = device.name
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
