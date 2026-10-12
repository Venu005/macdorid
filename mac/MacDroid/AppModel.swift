import Foundation
import MacDroidCore
import Observation

@MainActor @Observable
final class AppModel {
    private(set) var status: ConnectionStatus = .disconnected
    private(set) var pairedDevice: PairedDevice?
    private(set) var invite: PairingInvite?
    private(set) var inviteExpiresAt: Date?
    private(set) var listeningPort: Int?
    private(set) var errorMessage: String?
    let macName: String
    let fingerprint: String

    @ObservationIgnored private let identity: Identity
    @ObservationIgnored private let pairing: PairingManager
    @ObservationIgnored private let hub: ConnectionHub
    @ObservationIgnored private let advertiser = BonjourAdvertiser()
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []

    init() {
        let name = Host.current().localizedName ?? "Mac"
        macName = name
        var startupError: String?
        let loaded: Identity
        do {
            loaded = try KeychainIdentityStore().loadOrCreate(commonName: name)
        } catch {
            startupError = "Keychain unavailable (\(error)). Pairing will not survive a restart."
            loaded = try! Identity.generate(commonName: name)
        }
        identity = loaded
        fingerprint = loaded.fingerprint
        let store = UserDefaultsPairedDeviceStore()
        pairedDevice = store.load()
        pairing = PairingManager(store: store)
        hub = ConnectionHub(macName: name, appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0")
        errorMessage = startupError
    }

    var statusSymbol: String {
        switch (pairedDevice, status) {
        case (nil, _): "iphone.slash"
        case (_, .connected): "iphone.radiowaves.left.and.right"
        case (_, .disconnected): "iphone"
        }
    }

    func start() {
        guard tasks.isEmpty else { return }
        let server = MacDroidServer(identity: identity, pairing: pairing, hub: hub, macName: macName)
        tasks.append(Task { [weak self] in
            do {
                try await server.run { [weak self] port in await self?.serverStarted(port: port) }
            } catch {
                self?.errorMessage = "Server stopped: \(error)"
            }
        })
        tasks.append(Task { [weak self, hub] in
            for await status in hub.statusUpdates { self?.status = status }
        })
        tasks.append(Task { [weak self, pairing] in
            for await event in pairing.events { await self?.handle(event) }
        })
    }

    func beginPairing() async {
        guard let port = listeningPort else { errorMessage = "Server is still starting — try again in a moment."; return }
        guard let host = LocalAddress.primaryIPv4() else { errorMessage = "No network connection. Join Wi-Fi and try again."; return }
        let window = await pairing.openWindow()
        errorMessage = nil
        invite = PairingInvite(host: host, port: port, fingerprint: fingerprint, code: window.code, name: macName)
        inviteExpiresAt = window.expiresAt
        let delay = max(0, window.expiresAt.timeIntervalSinceNow)
        Task { [pairing] in
            try? await Task.sleep(for: .seconds(delay))
            _ = await pairing.currentWindow() // closes the window on expiry → .windowClosed event
        }
    }

    func cancelPairing() async { await pairing.closeWindow() }

    func forgetPhone() async {
        await pairing.forget()
        await hub.disconnect()
    }

    private func serverStarted(port: Int) {
        listeningPort = port
        do {
            try advertiser.start(name: macName, port: UInt16(port), txt: ["v": "1", "fp": Fingerprint.short(fingerprint)])
        } catch {
            errorMessage = "Bonjour advertising failed: \(error)"
        }
    }

    private func handle(_ event: PairingEvent) async {
        switch event {
        case .windowOpened: break
        case .windowClosed: invite = nil; inviteExpiresAt = nil
        case .paired(let device):
            pairedDevice = device
            errorMessage = nil
            // A newly paired phone replaces the old one: end any session authenticated with another certificate.
            await hub.disconnect(unlessFingerprint: device.fingerprint)
        case .forgotten:
            pairedDevice = nil
            await hub.disconnect()
        }
    }
}
