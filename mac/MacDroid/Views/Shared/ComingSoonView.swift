import SwiftUI

struct ComingSoonView: View {
    let section: SidebarSection

    var body: some View {
        ContentUnavailableView {
            Label(section.title, systemImage: section.symbol)
        } description: {
            Text("Coming in a later update.\n\(section.comingSoonDescription ?? "")")
        }
    }
}
