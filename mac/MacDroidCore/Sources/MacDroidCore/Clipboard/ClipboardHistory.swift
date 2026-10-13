import Foundation

public struct ClipboardItem: Codable, Equatable, Sendable, Identifiable {
    public enum Kind: String, Codable, Sendable { case text, link, image }
    public enum Direction: String, Codable, Sendable { case toPhone, fromPhone }

    public var id: String
    public var kind: Kind
    /// Text and links; nil for images (stored as files).
    public var text: String?
    public var direction: Direction
    public var date: Date

    public init(id: String, kind: Kind, text: String?, direction: Direction, date: Date) {
        self.id = id
        self.kind = kind
        self.text = text
        self.direction = direction
        self.date = date
    }
}

/// The Mac's clipboard history (spec §4.4): newest first, 10/20/50 items, saved in Application Support,
/// images as PNG files beside the JSON. Trimmed and cleared items take their image files with them.
public struct ClipboardHistory: Sendable {
    public static let capacities = [10, 20, 50]
    public static let defaultCapacity = 20

    public let directory: URL
    public let imagesDirectory: URL
    public private(set) var items: [ClipboardItem]
    public private(set) var capacity: Int
    private let store: JSONFileStore<[ClipboardItem]>

    public init(directory: URL, capacity: Int = ClipboardHistory.defaultCapacity) {
        self.directory = directory
        imagesDirectory = directory.appending(path: "images", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: imagesDirectory, withIntermediateDirectories: true)
        store = JSONFileStore(url: directory.appending(path: "history.json"))
        self.capacity = Self.capacities.contains(capacity) ? capacity : Self.defaultCapacity
        items = store.load() ?? []
        trim()
    }

    public static func imageURL(imagesDirectory: URL, id: String) -> URL { imagesDirectory.appending(path: id + ".png") }

    @discardableResult
    public mutating func add(_ content: ClipboardContent, direction: ClipboardItem.Direction, id: String, date: Date) -> ClipboardItem {
        let item: ClipboardItem
        switch content {
        case .text(let text):
            item = ClipboardItem(id: id, kind: ClipboardRules.kind(ofText: text), text: text, direction: direction, date: date)
        case .image(let png):
            try? png.write(to: Self.imageURL(imagesDirectory: imagesDirectory, id: id), options: .atomic)
            item = ClipboardItem(id: id, kind: .image, text: nil, direction: direction, date: date)
        }
        items.insert(item, at: 0)
        trim()
        save()
        return item
    }

    public mutating func setCapacity(_ value: Int) {
        guard Self.capacities.contains(value) else { return }
        capacity = value
        trim()
        save()
    }

    public mutating func clear() {
        items.forEach(removeFiles)
        items.removeAll()
        save()
    }

    public func item(id: String) -> ClipboardItem? { items.first { $0.id == id } }

    public func content(of item: ClipboardItem) -> ClipboardContent? {
        switch item.kind {
        case .text, .link: item.text.map(ClipboardContent.text)
        case .image: (try? Data(contentsOf: Self.imageURL(imagesDirectory: imagesDirectory, id: item.id))).map(ClipboardContent.image)
        }
    }

    public func imageURL(_ item: ClipboardItem) -> URL? {
        guard item.kind == .image else { return nil }
        let url = Self.imageURL(imagesDirectory: imagesDirectory, id: item.id)
        return FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) ? url : nil
    }

    /// A copy of `item`'s image to send, so trimming or clearing the history can't pull the file from under a
    /// transfer. Only the newest outgoing copy is kept: a newer clipboard item cancels the older transfer anyway.
    public func outgoingCopy(of item: ClipboardItem) -> URL? {
        guard let source = imageURL(item) else { return nil }
        let folder = directory.appending(path: "outgoing", directoryHint: .isDirectory)
        let fm = FileManager.default
        try? fm.removeItem(at: folder)
        try? fm.createDirectory(at: folder, withIntermediateDirectories: true)
        let copy = folder.appending(path: UUID().uuidString.lowercased() + ".png")
        return (try? fm.copyItem(at: source, to: copy)) == nil ? nil : copy
    }

    private mutating func trim() {
        while items.count > capacity { removeFiles(items.removeLast()) }
    }

    private func removeFiles(_ item: ClipboardItem) {
        guard item.kind == .image else { return }
        try? FileManager.default.removeItem(at: Self.imageURL(imagesDirectory: imagesDirectory, id: item.id))
    }

    private func save() { try? store.save(items) }
}
