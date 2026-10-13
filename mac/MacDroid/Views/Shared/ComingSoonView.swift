import SwiftUI

struct ComingSoonView: View {
    let section: SidebarSection

    var body: some View {
        ContentUnavailableView {
            Label("Coming in a later update", systemImage: section.symbol)
        } description: {
            Text(section.comingSoonDescription ?? "")
        }
    }
}
