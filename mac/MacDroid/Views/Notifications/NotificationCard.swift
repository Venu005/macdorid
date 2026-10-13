import MacDroidCore
import SwiftUI

/// One mirrored notification. Hover shows Reply, the app's buttons and ✕ (dismiss on both devices).
struct NotificationCard: View {
    let entry: NotificationEntry
    let model: AppModel
    @State private var hovering = false
    @State private var replying = false
    @State private var replyText = ""

    var body: some View {
        let posted = entry.posted
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(NotificationPresentation.bannerTitle(posted)).font(.headline).lineLimit(1)
                Spacer(minLength: 4)
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    Text(NotificationPresentation.relativeTime(entry.receivedAt, now: context.date))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Button { model.dismissNotification(posted.key) } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .help("Dismiss on both devices")
                    .opacity(hovering ? 1 : 0)
            }
            if !posted.text.isEmpty {
                Text(posted.text).lineLimit(4).foregroundStyle(.secondary)
            }
            if (hovering || replying) && (posted.canReply || !posted.actions.isEmpty) {
                HStack(spacing: 8) {
                    if posted.canReply { Button("Reply") { replying = true } }
                    ForEach(posted.actions.prefix(3), id: \.index) { action in
                        Button(action.title) { model.performNotificationAction(posted.key, index: action.index) }
                    }
                }
                .controlSize(.small)
            }
            if replying {
                HStack {
                    TextField("Reply", text: $replyText)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(sendReply)
                    Button("Send", action: sendReply)
                        .disabled(replyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Button("Cancel") { replying = false; replyText = "" }
                }
                .controlSize(.small)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
    }

    private func sendReply() {
        let text = replyText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        model.replyToNotification(entry.posted.key, text: text)
        replyText = ""
        replying = false
    }
}
