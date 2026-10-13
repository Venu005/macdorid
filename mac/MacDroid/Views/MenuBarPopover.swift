import AppKit
import MacDroidCore
import SwiftUI

struct MenuBarPopover: View {
    let model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if model.pairedDevice == nil {
                notPaired
            } else {
                header
            }
            ForEach(model.warnings, id: \.self) { warning in
                Label(warning, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(Color.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Divider()
            VStack(spacing: 0) {
                MenuRow(title: "Open MacDroid", shortcut: "⌘O") { model.showWindow(.phone) }
                    .keyboardShortcut("o")
                MenuRow(title: "Settings…", shortcut: "⌘,") { model.showWindow(.general) }
                MenuRow(title: "Quit MacDroid", shortcut: "⌘Q") { NSApp.terminate(nil) }
                    .keyboardShortcut("q")
            }
        }
        .padding(12)
        .frame(width: 280)
    }

    private var notPaired: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("No phone paired").font(.headline)
            Text("Pair your Android phone to get started.").font(.callout).foregroundStyle(.secondary)
            Button {
                model.showWindow(.phone)
            } label: {
                Text("Pair a Phone…").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    private var header: some View {
        let status = model.phoneStatus
        return HStack(spacing: 10) {
            Image(systemName: "iphone.gen3")
                .font(.system(size: 20))
                .frame(width: 40, height: 40)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
                Text(status.title).font(.headline)
                HStack(spacing: 5) {
                    StatusDot(tone: status.tone)
                    Text(status.subtitle).font(.callout).foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// A full-width menu-style row with a hover highlight and a right-aligned shortcut hint.
private struct MenuRow: View {
    let title: String
    let shortcut: String
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title)
                Spacer()
                Text(shortcut).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
            .background(RoundedRectangle(cornerRadius: 6).fill(hovering ? Color.accentColor.opacity(0.18) : Color.clear))
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}
