import AppKit
import MacDroidCore
import SwiftUI

/// The Notifications page (spec §3.3, layout A): one collapsible group per app, newest first, as cards.
struct NotificationsView: View {
    let model: AppModel
    @State private var collapsed: Set<String> = []
    @State private var showingMutedApps = false

    var body: some View {
        let state = model.notifications
        VStack(spacing: 0) {
            if let notice = NotificationPresentation.pageNotice(state) { BannerView(kind: .warning, message: notice) }
            if model.macNotificationsDenied {
                HStack {
                    BannerView(kind: .warning, message: "MacDroid isn't allowed to show notifications on this Mac.")
                    Button("Open System Settings") {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
                            NSWorkspace.shared.open(url)
                        }
                    }
                    .padding(.trailing, 12)
                    .padding(.top, 12)
                }
            }
            if let problem = model.notificationProblem { BannerView(kind: .error, message: problem) }
            if state.groups.isEmpty {
                ContentUnavailableView("No notifications", systemImage: "bell", description: Text("Notifications from your phone appear here."))
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 18) {
                        ForEach(state.groups) { group in
                            VStack(alignment: .leading, spacing: 8) {
                                NotificationGroupHeader(
                                    group: group,
                                    iconURL: model.iconURL(group.iconHash),
                                    collapsed: collapsed.contains(group.packageName),
                                    toggle: { toggle(group.packageName) },
                                    clear: { model.dismissNotifications(group.entries.map(\.posted.key)) }
                                )
                                if !collapsed.contains(group.packageName) {
                                    ForEach(group.entries) { entry in
                                        NotificationCard(entry: entry, iconURL: model.iconURL(group.iconHash), model: model)
                                    }
                                }
                            }
                        }
                    }
                    .padding(20)
                }
            }
        }
        .navigationTitle("Notifications")
        .toolbar {
            ToolbarItemGroup {
                Button { showingMutedApps = true } label: { Label("Muted Apps", systemImage: "bell.slash") }
                    .help("Choose apps whose notifications stay on your phone")
                Button { model.clearAllNotifications() } label: { Label("Clear All", systemImage: "xmark.circle") }
                    .help("Dismiss every notification on both devices")
                    .disabled(state.count == 0)
            }
        }
        .sheet(isPresented: $showingMutedApps) { MutedAppsSheet(model: model) }
        .task { await model.refreshNotificationPermission() }
        .onDisappear { model.dismissNotificationProblem() }
    }

    private func toggle(_ packageName: String) {
        withAnimation(.easeInOut(duration: 0.15)) {
            if collapsed.contains(packageName) { collapsed.remove(packageName) } else { collapsed.insert(packageName) }
        }
    }
}

/// App name, count and a hover "Clear" for one app's group.
private struct NotificationGroupHeader: View {
    let group: NotificationGroup
    let iconURL: URL?
    let collapsed: Bool
    let toggle: () -> Void
    let clear: () -> Void
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 8) {
            Button(action: toggle) {
                HStack(spacing: 8) {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                        .rotationEffect(.degrees(collapsed ? 0 : 90))
                        .frame(width: 12)
                    AppIconView(url: iconURL, size: 18)
                    Text(group.appName).font(.headline)
                    Text("\(group.entries.count)")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(.quaternary, in: Capsule())
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Spacer()
            Button("Clear", action: clear)
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
                .help("Dismiss \(group.appName)'s notifications on both devices")
                .opacity(hovering ? 1 : 0)
        }
        .onHover { hovering = $0 }
    }
}
