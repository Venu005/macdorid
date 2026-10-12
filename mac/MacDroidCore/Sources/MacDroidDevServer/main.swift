import Foundation
import MacDroidCore

// Headless server for the cross-platform test: fresh identity, pairing window open for 10 minutes.
setvbuf(stdout, nil, _IONBF, 0)

let arguments = CommandLine.arguments
func value(after flag: String) -> String? {
    guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
    return arguments[index + 1]
}

let macName = "MacDroid Dev Server"
let host = value(after: "--host") ?? LocalAddress.primaryIPv4() ?? "127.0.0.1"
let port = Int(value(after: "--port") ?? "") ?? 47999
let identity = try Identity.generate(commonName: macName)
let pairing = PairingManager(store: InMemoryPairedDeviceStore(), windowDuration: 600)
let hub = ConnectionHub(macName: macName, appVersion: "dev")

let window = await pairing.openWindow()
print("PAIRING_URI \(PairingInvite(host: host, port: port, fingerprint: identity.fingerprint, code: window.code, name: macName).uri)")

let statusPrinter = Task {
    for await status in hub.statusUpdates {
        switch status {
        case .disconnected: print("STATUS disconnected")
        case .connected(let peer): print("STATUS connected device=\(peer.device) battery=\(peer.battery.map(String.init) ?? "-")")
        }
    }
}

let server = MacDroidServer(identity: identity, pairing: pairing, hub: hub, macName: macName,
                            configuration: ServerConfiguration(host: "0.0.0.0", port: port))
try await server.run { boundPort in print("LISTENING \(boundPort)") }
statusPrinter.cancel()
