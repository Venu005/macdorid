import Foundation

public struct ClipboardState: Equatable, Sendable {
    public var items: [ClipboardItem]
    public var capacity: Int
    public var sendToPhone: Bool
    public var receiveFromPhone: Bool
    public var connected: Bool
    public var phoneSupportsClipboard: Bool
    /// Something wasn't sent (too large); shown quietly on the Clipboard page.
    public var note: String?

    public init(
        items: [ClipboardItem] = [], capacity: Int = ClipboardHistory.defaultCapacity, sendToPhone: Bool = true,
        receiveFromPhone: Bool = true, connected: Bool = false, phoneSupportsClipboard: Bool = false, note: String? = nil
    ) {
        self.items = items
        self.capacity = capacity
        self.sendToPhone = sendToPhone
        self.receiveFromPhone = receiveFromPhone
        self.connected = connected
        self.phoneSupportsClipboard = phoneSupportsClipboard
        self.note = note
    }

    public static let empty = ClipboardState()
}

public enum ClipboardEvent: Equatable, Sendable {
    /// Something from the phone is now on the Mac clipboard (the app shows "Clipboard from <phone>").
    case receivedFromPhone(ClipboardItem)
}

/// Clipboard sync on the Mac (spec §4): sends local changes, applies the phone's items, keeps the history.
/// Echo prevention (spec §8.2): `apply` returns the pasteboard change count it produced, and the watcher's
/// report of that change count is ignored.
public actor ClipboardSync {
    public nonisolated var states: AsyncStream<ClipboardState> { stateBroadcast.stream() }
    public nonisolated var events: AsyncStream<ClipboardEvent> { eventBroadcast.stream() }
    public private(set) var state = ClipboardState.empty

    private let stateBroadcast = Broadcast<ClipboardState>(replayLatest: true, initial: .empty)
    private let eventBroadcast = Broadcast<ClipboardEvent>(replayLatest: false)
    private var history: ClipboardHistory
    private var sendOn: Bool
    private var receiveOn: Bool
    private var connected = false
    private var phoneSupports = false
    private var note: String?
    private var ownChanges: Set<Int> = []
    private let now: @Sendable () -> Date
    private let send: @Sendable (Message) async throws -> Void
    private let sendImage: @Sendable (URL) async -> Bool
    private let apply: @Sendable (ClipboardContent) async -> Int
    private let cancelImages: @Sendable () async -> Void
    private var tasks: [Task<Void, Never>] = []

    public init(
        history: ClipboardHistory, sendToPhone: Bool = true, receiveFromPhone: Bool = true,
        now: @escaping @Sendable () -> Date = { Date() },
        send: @escaping @Sendable (Message) async throws -> Void,
        sendImage: @escaping @Sendable (URL) async -> Bool,
        apply: @escaping @Sendable (ClipboardContent) async -> Int,
        cancelImages: @escaping @Sendable () async -> Void = {}
    ) {
        self.history = history
        self.sendOn = sendToPhone
        self.receiveOn = receiveFromPhone
        self.now = now
        self.send = send
        self.sendImage = sendImage
        self.apply = apply
        self.cancelImages = cancelImages
        self.state = Self.makeState(history, sendToPhone, receiveFromPhone, false, false, nil)
        stateBroadcast.yield(state)
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

    /// The pasteboard changed (spec §4.1). `changeCount` is `NSPasteboard.changeCount` at that change.
    public func localChange(_ content: ClipboardContent, changeCount: Int) async {
        let ours = ownChanges.remove(changeCount) != nil
        ownChanges = ownChanges.filter { $0 > changeCount } // older own changes can no longer be reported
        guard !ours, sendOn, connected, phoneSupports else { return }
        switch content {
        case .text(let text):
            guard !text.isEmpty else { return }
            guard ClipboardRules.fitsInAFrame(text) else { return setNote("Not sent to your phone: text over 1 MB") }
            await cancelImages() // newer content wins over an image still on its way
            do {
                try await send(.clipboardSet(ClipboardSet(itemId: Self.newID(), text: text)))
            } catch {
                return
            }
            history.add(content, direction: .toPhone, id: Self.newID(), date: now())
        case .image(let png):
            guard png.count <= ClipboardLimits.maxImageBytes else { return setNote("Not sent to your phone: image over 10 MB") }
            let item = history.add(content, direction: .toPhone, id: Self.newID(), date: now())
            guard let url = history.outgoingCopy(of: item) else { return }
            _ = await sendImage(url)
        }
        note = nil
        publish()
    }

    func handle(_ message: Message) async {
        guard case .clipboardSet(let body) = message, receiveOn else { return }
        await applyFromPhone(.text(body.text))
    }

    /// A clipboard image from the phone arrived (TransferManager's `clipboardImageReceived`). The file is removed after.
    public func imageReceived(_ url: URL) async {
        defer { try? FileManager.default.removeItem(at: url) }
        guard receiveOn, let png = try? Data(contentsOf: url) else { return }
        await applyFromPhone(.image(png))
    }

    func connectionChanged(_ status: ConnectionStatus) {
        switch status {
        case .connected(let peer):
            connected = true
            phoneSupports = peer.supports(Feature.clipboard)
        case .disconnected:
            connected = false
        }
        publish()
    }

    /// History "Copy": back on the Mac clipboard, not re-sent (spec §8.2).
    public func copyToMac(id: String) async {
        guard let item = history.item(id: id), let content = history.content(of: item) else { return }
        ownChanges.insert(await apply(content))
    }

    /// History "Send": to the phone again.
    public func sendToPhone(id: String) async {
        guard connected, phoneSupports, let item = history.item(id: id) else { return setNote("Your phone isn't connected") }
        switch history.content(of: item) {
        case .text(let text):
            try? await send(.clipboardSet(ClipboardSet(itemId: Self.newID(), text: text)))
        case .image:
            if let url = history.outgoingCopy(of: item) { _ = await sendImage(url) }
        case nil:
            break
        }
    }

    public func setSendToPhone(_ on: Bool) { sendOn = on; publish() }
    public func setReceiveFromPhone(_ on: Bool) { receiveOn = on; publish() }
    public func setCapacity(_ value: Int) { history.setCapacity(value); publish() }
    public func clearHistory() { history.clear(); publish() }

    private func applyFromPhone(_ content: ClipboardContent) async {
        ownChanges.insert(await apply(content))
        let item = history.add(content, direction: .fromPhone, id: Self.newID(), date: now())
        publish()
        eventBroadcast.yield(.receivedFromPhone(item))
    }

    private func setNote(_ text: String) {
        note = text
        publish()
    }

    private func publish() {
        state = Self.makeState(history, sendOn, receiveOn, connected, phoneSupports, note)
        stateBroadcast.yield(state)
    }

    private static func makeState(_ history: ClipboardHistory, _ send: Bool, _ receive: Bool, _ connected: Bool, _ supports: Bool, _ note: String?) -> ClipboardState {
        ClipboardState(items: history.items, capacity: history.capacity, sendToPhone: send, receiveFromPhone: receive,
                       connected: connected, phoneSupportsClipboard: supports, note: note)
    }

    static func newID() -> String { UUID().uuidString.lowercased() }
}
