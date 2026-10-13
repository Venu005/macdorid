import MacDroidCore
import SwiftUI
import UniformTypeIdentifiers

/// What the drop overlay shows while files are dragged over the popover.
struct DropPreview: Equatable {
    var isTargeted = false
    var count = 0
    var totalBytes: Int64 = 0
}

/// The whole popover is a drop target (spec §3.2 design A). It counts the dragged files and adds up their sizes for the overlay.
struct PopoverDropDelegate: DropDelegate {
    let model: AppModel
    @Binding var preview: DropPreview

    func validateDrop(info: DropInfo) -> Bool { model.canSendFiles && info.hasItemsConforming(to: [.fileURL]) }

    func dropEntered(info: DropInfo) {
        guard validateDrop(info: info) else { return }
        let providers = info.itemProviders(for: [.fileURL])
        preview = DropPreview(isTargeted: true, count: providers.count, totalBytes: 0)
        Task { @MainActor in
            let urls = await Self.urls(from: providers)
            let total = urls.reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0) }
            if preview.isTargeted { preview.totalBytes = total }
        }
    }

    func dropExited(info: DropInfo) { preview = DropPreview() }

    func performDrop(info: DropInfo) -> Bool {
        preview = DropPreview()
        guard validateDrop(info: info) else { return false }
        let providers = info.itemProviders(for: [.fileURL])
        Task { @MainActor in model.sendFiles(await Self.urls(from: providers)) }
        return true
    }

    @MainActor
    static func urls(from providers: [NSItemProvider]) async -> [URL] {
        var urls: [URL] = []
        for provider in providers {
            let url: URL? = await withCheckedContinuation { continuation in
                _ = provider.loadObject(ofClass: URL.self) { url, _ in continuation.resume(returning: url) }
            }
            if let url { urls.append(url) }
        }
        return urls
    }
}
