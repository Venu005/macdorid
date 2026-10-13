import MacDroidCore
import SwiftUI
import UniformTypeIdentifiers

/// What the drop overlay shows while files are dragged over the popover.
struct DropPreview: Equatable {
    var isTargeted = false
    var count = 0
}

/// The whole popover is a drop target (spec §3.2 design A). While hovering it only counts the files
/// (the sandbox grants access on drop); on drop it reads them from the drag pasteboard.
struct PopoverDropDelegate: DropDelegate {
    let model: AppModel
    @Binding var preview: DropPreview

    func validateDrop(info: DropInfo) -> Bool { model.canSendFiles && info.hasItemsConforming(to: [.fileURL]) }

    func dropEntered(info: DropInfo) {
        guard validateDrop(info: info) else { return }
        preview = DropPreview(isTargeted: true, count: info.itemProviders(for: [.fileURL]).count)
    }

    func dropExited(info: DropInfo) { preview = DropPreview() }

    func performDrop(info: DropInfo) -> Bool {
        preview = DropPreview()
        guard validateDrop(info: info) else { return false }
        model.sendFiles(DroppedFiles.urls())
        return true
    }
}
