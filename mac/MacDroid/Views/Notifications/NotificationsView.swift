import AppKit
import MacDroidCore
import SwiftUI

/// The Notifications page (spec §3.3, layout A): one collapsible group per app, newest first.
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
                List {
                    ForEach(state.groups) { group in
                        Section {
                            if !collapsed.contains(group.packageName) {
                                ForEach(group.entries) { NotificationCard(entry: $0, model: model) }
                            }
                        } header: {
                            groupHeader(group)
                        }
                    }
                }
                .listStyle(.inset)
            }
        }
        .navigationTitle("Notifications")
        .toolbar {
            ToolbarItemGroup {
                Button("Muted apps…") { showingMutedApps = true }
                Button("Clear all") { model.clearAllNotifications() }.disabled(state.count == 0)
            }
        }
        .sheet(isPresented: $showingMutedApps) { MutedAppsSheet(model: model) }
        .task { await model.refreshNotificationPermission() }
        .onDisappear { model.dismissNotificationProblem() }
    }

    private func groupHeader(_ group: NotificationGroup) -> some View {
        Button {
            if collapsed.contains(group.packageName) { collapsed.remove(group.packageName) } else { collapsed.insert(group.packageName) }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: collapsed.contains(group.packageName) ? "chevron.right" : "chevron.down")
                    .font(.caption.weight(.semibold))
                    .frame(width: 10)
                AppIconView(url: model.iconURL(group.iconHash))
                Text(group.appName).font(.headline)
                Text("\(group.entries.count)").foregroundStyle(.secondary)
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
