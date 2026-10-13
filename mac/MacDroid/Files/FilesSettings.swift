import AppKit
import MacDroidCore
import Observation

/// Settings → Received files: where files from the phone go (a security-scoped folder bookmark) and "Ask where to save each time".
@MainActor @Observable
final class FilesSettings {
    private static let askKey = "files.askEachTime"
    private static let bookmarkKey = "files.folderBookmark"

    var askEachTime: Bool {
        didSet { UserDefaults.standard.set(askEachTime, forKey: Self.askKey) }
    }
    /// Where received files go: the chosen folder, or ~/Downloads/MacDroid.
    private(set) var folder: URL
    /// The chosen folder can't be reached any more; received files ask where to save until it's chosen again.
    private(set) var folderProblem = false
    private(set) var usesDefaultFolder = true
    /// Named in save panels ("<phone> is sending …").
    var phoneName = "Your phone"

    /// Every folder opened this session stays accessible, so files already saved in a previous folder can still be opened.
    @ObservationIgnored private var accessedFolders: [URL] = []
    @ObservationIgnored private var openPanels: [String: NSSavePanel] = [:]

    static var defaultFolder: URL {
        URL.downloadsDirectory.resolvingSymlinksInPath().appending(path: "MacDroid", directoryHint: .isDirectory)
    }

    init() {
        askEachTime = UserDefaults.standard.bool(forKey: Self.askKey)
        folder = Self.defaultFolder
        restoreFolder()
    }

    func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.prompt = "Choose"
        panel.message = "Choose where files from your phone are saved."
        panel.directoryURL = folder
        NSApp.activate()
        guard panel.runModal() == .OK, let url = panel.url,
              let bookmark = try? url.bookmarkData(options: .withSecurityScope) else { return }
        UserDefaults.standard.set(bookmark, forKey: Self.bookmarkKey)
        restoreFolder()
    }

    func useDefaultFolder() {
        UserDefaults.standard.removeObject(forKey: Self.bookmarkKey)
        restoreFolder()
    }

    /// Resolves the bookmark and keeps access open while MacDroid runs (received files are written there at any time).
    private func restoreFolder() {
        folderProblem = false
        guard let bookmark = UserDefaults.standard.data(forKey: Self.bookmarkKey) else {
            usesDefaultFolder = true
            folder = Self.defaultFolder
            return
        }
        usesDefaultFolder = false
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, options: .withSecurityScope, bookmarkDataIsStale: &stale),
              url.startAccessingSecurityScopedResource() else {
            folderProblem = true
            folder = Self.defaultFolder
            return
        }
        accessedFolders.append(url)
        folder = url
        if stale, let fresh = try? url.bookmarkData(options: .withSecurityScope) {
            UserDefaults.standard.set(fresh, forKey: Self.bookmarkKey)
        }
    }

    /// The core's question: the folder, or a save panel when asked for or when the chosen folder is unreachable.
    func saveLocation(for offer: IncomingOffer) async -> SaveLocation? {
        if !askEachTime && !folderProblem {
            do {
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                return .folder(folder)
            } catch {
                folderProblem = true
            }
        }
        return await askWhereToSave(offer)
    }

    private func askWhereToSave(_ offer: IncomingOffer) async -> SaveLocation? {
        guard !Task.isCancelled else { return nil }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = offer.name
        panel.directoryURL = folder
        panel.message = "\(phoneName) is sending “\(offer.name)” (\(TransferText.bytes(offer.size))). Choose where to save it."
        let id = offer.id
        openPanels[id] = panel
        defer { openPanels[id] = nil }
        NSApp.activate()
        let response = await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                panel.begin { continuation.resume(returning: $0) }
            }
        } onCancel: {
            // The phone cancelled: close the panel instead of leaving a question nobody needs answered.
            Task { @MainActor [weak self] in self?.openPanels[id]?.cancel(nil) }
        }
        guard response == .OK, let url = panel.url else { return nil }
        return .file(url)
    }
}

/// Bridges the core's question to `FilesSettings` on the main actor.
struct MacReceiveLocation: ReceiveLocationProvider {
    let settings: FilesSettings
    func saveLocation(for offer: IncomingOffer) async -> SaveLocation? { await settings.saveLocation(for: offer) }
}
