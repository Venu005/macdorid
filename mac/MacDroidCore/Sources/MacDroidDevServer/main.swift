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
    func saveLocation(for offer: IncomingOffer) async -> SaveLocation? { .folder(folder) }
}

let transfers = TransferManager(
    stateDirectory: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "macdroid-devserver-\(UUID().uuidString)"),
    location: FolderLocation(folder: receiveDir),
    clipboardDirectory: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "macdroid-devserver-clipboard-\(UUID().uuidString)"),
    send: { try await hub.send($0) }
)
await transfers.attach(to: hub)

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

actor DevPasteboard {
    private var count = 0
    func apply(_ content: ClipboardContent) -> Int {
        count += 1
        switch content {
        case .text(let text): print("CLIPBOARD applied text=\(text)")
        case .image(let png): print("CLIPBOARD applied image bytes=\(png.count)")
        }
        return 1_000 + count
    }
}
let devPasteboard = DevPasteboard()
let clipboard = ClipboardSync(
    history: ClipboardHistory(directory: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "macdroid-devserver-history-\(UUID().uuidString)")),
    send: { try await hub.send($0) },
    sendImage: { url in await transfers.sendClipboardImage(url) },
    apply: { await devPasteboard.apply($0) }
)
await clipboard.attach(to: hub)

let extras = PhoneExtras(
    art: IconCache(directory: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "macdroid-devserver-art-\(UUID().uuidString)")),
    send: { try await hub.send($0) }
)
await extras.attach(to: hub)

let extrasPrinter = Task {
    var commanded = false
    var lastRinging = false
    for await state in extras.states {
        if state.ringing != lastRinging {
            lastRinging = state.ringing
            print("RING state ringing=\(state.ringing)")
        }
        if state.media.active, !commanded {
            commanded = true
            print("MEDIA state title=\(state.media.title ?? "") playing=\(state.media.playing ?? false) art=\(state.media.artHash ?? "")")
            await extras.command(.pause)
        }
    }
}

let linkPrinter = Task {
    for await event in extras.events {
        if case .openLink(let url) = event { print("LINK open \(url.absoluteString)") }
    }
}

let window = await pairing.openWindow()
print("PAIRING_URI \(PairingInvite(host: host, port: port, fingerprint: identity.fingerprint, code: window.code, name: macName).uri)")

let statusPrinter = Task {
    var sentFile = false
    var sentClipboard = false
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
            if !sentClipboard {
                sentClipboard = true
                Task {
                    try? await Task.sleep(for: .milliseconds(500))
                    await clipboard.localChange(.text("hello from mac"), changeCount: 1)
                    await extras.ring()
                    _ = await extras.sendLink(in: "see https://example.com/mac")
                }
            }
        }
    }
}

let transferPrinter = Task {
    for await event in transfers.events {
        switch event {
        case .received(let record): print("RECEIVED \(record.name) size=\(record.size)")
        case .clipboardImageReceived(let url):
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? -1
            print("CLIPBOARD image size=\(size)")
            await clipboard.imageReceived(url)
        case .photoReceived, .photoFailed: break // Task 9
        }
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
notificationPrinter.cancel()
appsPrinter.cancel()
extrasPrinter.cancel()
linkPrinter.cancel()
