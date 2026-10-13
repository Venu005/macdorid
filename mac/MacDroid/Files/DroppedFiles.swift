import AppKit

/// Reads dropped file URLs the way the sandbox expects: from the drag pasteboard, at drop time.
/// macOS grants a sandboxed app access to dragged files only when they're dropped; asking for them while
/// hovering (or through SwiftUI's URL Transferable) fails with "Sandbox extension data required immediately".
enum DroppedFiles {
    @MainActor
    static func urls() -> [URL] {
        let objects = NSPasteboard(name: .drag).readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true])
        return (objects as? [URL]) ?? []
    }
}
