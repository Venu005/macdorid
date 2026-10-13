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
    /// The phone was just forgotten: wait for an explicit "Pair a Phone" instead of opening a pairing window on its own.
    private(set) var awaitingPairRequest = false
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
