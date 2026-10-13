import MacDroidCore
import SwiftUI

/// One checkbox per app the phone has seen (spec §3.3). The phone owns the list; changes are requests.
struct MutedAppsSheet: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Muted Apps").font(.title2.weight(.semibold))
            Text("Notifications from checked apps stay on your phone. This list syncs with MacDroid on your phone.")
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if model.notifications.apps.isEmpty {
                Text("Apps appear here after their first notification.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 160)
            } else {
                List(model.notifications.apps) { app in
                    Toggle(isOn: Binding(get: { app.muted }, set: { model.setAppMuted(app.packageName, muted: $0) })) {
                        HStack(spacing: 8) {
                            AppIconView(url: model.iconURL(app.iconHash))
                            Text(app.appName)
                        }
                    }
                    .toggleStyle(.checkbox)
                }
                .frame(minHeight: 240)
            }
            if let problem = model.notificationProblem {
                Text(problem).font(.callout).foregroundStyle(.red)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 420)
    }
}
