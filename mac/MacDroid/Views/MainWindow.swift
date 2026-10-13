import SwiftUI

struct MainWindow: View {
    static let id = "main"
    @Bindable var model: AppModel

    var body: some View {
        NavigationSplitView {
            List(selection: $model.selectedSection) {
                Section {
                    ForEach(SidebarSection.features) { row($0) }
                }
                Section("MacDroid") {
                    ForEach(SidebarSection.settings) { row($0) }
                }
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200, max: 260)
        } detail: {
            VStack(spacing: 0) {
                ForEach(model.warnings, id: \.self) { BannerView(kind: .warning, message: $0) }
                detail(for: model.selectedSection ?? .phone)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 600, minHeight: 420)
        .onAppear { DockPolicy.windowDidAppear() }
        .onDisappear { DockPolicy.windowDidDisappear() }
    }

    private func row(_ section: SidebarSection) -> some View {
        Label(section.title, systemImage: section.symbol)
            .foregroundStyle(section.isAvailable ? .primary : .secondary)
            .tag(section)
    }

    @ViewBuilder
    private func detail(for section: SidebarSection) -> some View {
        switch section {
        case .phone: PhoneView(model: model)
        case .notifications: NotificationsView(model: model)
        case .files: FilesView(model: model)
        case .clipboard: ComingSoonView(section: section)
        case .general: GeneralSettingsView(model: model)
        case .security: SecuritySettingsView(fingerprint: model.fingerprint)
        case .about: AboutView()
        }
    }
}
