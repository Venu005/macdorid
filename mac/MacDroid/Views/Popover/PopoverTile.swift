import SwiftUI

/// A large Control Center-style button: symbol, title and a small hint underneath.
struct PopoverTile: View {
    let symbol: String
    let title: String
    let hint: String
    var enabled = true
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: symbol).font(.system(size: 18)).foregroundStyle(enabled ? Color.accentColor : Color.secondary)
                Text(title).font(.callout.weight(.semibold))
                Text(hint).font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.secondary.opacity(hovering && enabled ? 0.18 : 0.1)))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .onHover { hovering = $0 }
    }
}
