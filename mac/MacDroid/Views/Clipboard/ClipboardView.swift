import AppKit
import MacDroidCore
import SwiftUI

/// The Clipboard page (spec §4.4): searchable history, both directions, Copy and Send on hover.
struct ClipboardView: View {
    let model: AppModel
    @State private var query = ""

    var body: some View {
        let state = model.clipboard
        let items = ClipboardPresentation.filter(state.items, query: query)
        VStack(spacing: 0) {
            if let notice = ClipboardPresentation.pageNotice(state) { BannerView(kind: .warning, message: notice) }
            if let note = state.note { BannerView(kind: .warning, message: note) }
            if state.items.isEmpty {
                ContentUnavailableView("Nothing yet", systemImage: "doc.on.clipboard", description: Text("Things you copy appear here."))
            } else if items.isEmpty {
                ContentUnavailableView.search(text: query)
            } else {
                List(items) { ClipboardRow(item: $0, model: model) }.listStyle(.inset)
            }
        }
        .navigationTitle("Clipboard")
        .searchable(text: $query, placement: .toolbar, prompt: "Search clipboard history")
        .toolbar {
            ToolbarItem { Text("Sync to phone: \(state.sendToPhone ? "On" : "Off")").foregroundStyle(.secondary) }
        }
    }
}

private struct ClipboardRow: View {
    let item: ClipboardItem
    let model: AppModel
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 10) {
            icon
            VStack(alignment: .leading, spacing: 2) {
                Text(ClipboardPresentation.preview(item)).lineLimit(1).truncationMode(.tail)
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    Text("\(ClipboardPresentation.directionLabel(item.direction)) · \(NotificationPresentation.relativeTime(item.date, now: context.date))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 4)
            HStack(spacing: 6) {
                Button("Copy") { model.copyClipboardItem(item.id) }
                Button("Send") { model.sendClipboardItem(item.id) }.disabled(!model.clipboard.connected)
            }
            .controlSize(.small)
            .opacity(hovering ? 1 : 0)
        }
        .padding(.vertical, 3)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
    }

    @ViewBuilder private var icon: some View {
        switch item.kind {
        case .image:
            if let image = NSImage(contentsOf: model.clipboardImageURL(item)) {
                Image(nsImage: image).resizable().scaledToFill().frame(width: 28, height: 28).clipShape(RoundedRectangle(cornerRadius: 6))
            } else {
                Image(systemName: "photo").frame(width: 28, height: 28)
            }
        case .link:
            Image(systemName: "link").frame(width: 28, height: 28).background(.quaternary, in: RoundedRectangle(cornerRadius: 6))
        case .text:
            Image(systemName: "textformat").frame(width: 28, height: 28).background(.quaternary, in: RoundedRectangle(cornerRadius: 6))
        }
    }
}
