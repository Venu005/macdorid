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

let receiveDir = URL(fileURLWithPath: value(after: "--receive-dir") ?? NSTemporaryDirectory() + "macdroid-received")
let sendOnConnect = value(after: "--send-on-connect").map { URL(fileURLWithPath: $0) }
try FileManager.default.createDirectory(at: receiveDir, withIntermediateDirectories: true)

struct FolderLocation: ReceiveLocationProvider {
    let folder: URL
    func saveLocation(forName name: String, size: Int64) async -> SaveLocation? { .folder(folder) }
}

let transfers = TransferManager(
    stateDirectory: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "macdroid-devserver-\(UUID().uuidString)"),
    location: FolderLocation(folder: receiveDir),
    send: { try await hub.send($0) }
)
await transfers.attach(to: hub)

let window = await pairing.openWindow()
print("PAIRING_URI \(PairingInvite(host: host, port: port, fingerprint: identity.fingerprint, code: window.code, name: macName).uri)")

let statusPrinter = Task {
    var sentFile = false
    for await status in hub.statusUpdates {
        switch status {
        case .disconnected:
            print("STATUS disconnected")
        case .connected(let peer):
            print("STATUS connected device=\(peer.device) battery=\(peer.battery.map(String.init) ?? "-")")
            if !sentFile, let sendOnConnect {
                sentFile = true
                _ = await transfers.send(files: [sendOnConnect])
            }
        }
    }
}

let transferPrinter = Task {
    for await event in transfers.events {
        if case .received(let record) = event { print("RECEIVED \(record.name) size=\(record.size)") }
    }
}

let sentPrinter = Task {
    var printed = Set<String>()
    for await snapshot in transfers.snapshots {
        for record in snapshot.history where record.direction == .toPhone && record.phase == .done && !printed.contains(record.id) {
            printed.insert(record.id)
            print("SENT \(record.name)")
        }
    }
}

let server = MacDroidServer(identity: identity, pairing: pairing, hub: hub, macName: macName,
                            configuration: ServerConfiguration(host: "0.0.0.0", port: port), transfers: transfers)
try await server.run { boundPort in print("LISTENING \(boundPort)") }
statusPrinter.cancel()
transferPrinter.cancel()
sentPrinter.cancel()
