import MacDroidCore
import SwiftUI

/// One mirrored notification as a card: icon, title, time, text, then the app's buttons and Reply.
/// Reply opens an inline composer (Return sends, Esc cancels); ✕ dismisses on both devices.
struct NotificationCard: View {
    let entry: NotificationEntry
    let iconURL: URL?
    let model: AppModel
    @State private var hovering = false
    @State private var replying = false
    @State private var replyText = ""
    @State private var sentReply = false
    @FocusState private var replyFocused: Bool

    var body: some View {
        let posted = entry.posted
        HStack(alignment: .top, spacing: 12) {
            AppIconView(url: iconURL, size: 32)
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(NotificationPresentation.bannerTitle(posted))
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    TimelineView(.periodic(from: .now, by: 60)) { context in
                        Text(NotificationPresentation.relativeTime(entry.receivedAt, now: context.date))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                if !posted.text.isEmpty {
                    Text(posted.text)
                        .foregroundStyle(.secondary)
                        .lineLimit(5)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if posted.canReply || !posted.actions.isEmpty {
                    actions(posted)
                }
                if replying { composer }
                if sentReply {
                    Label("Reply sent", systemImage: "checkmark.circle.fill")
                        .font(.caption)
                        .foregroundStyle(.green)
                        .transition(.opacity)
                }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(.background.secondary))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(.separator.opacity(hovering ? 1 : 0.5)))
        .overlay(alignment: .topLeading) {
            Button { model.dismissNotification(posted.key) } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 16))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(.secondary)
                    .background(Circle().fill(.background))
            }
            .buttonStyle(.plain)
            .help("Dismiss on both devices")
            .offset(x: -7, y: -7)
            .opacity(hovering ? 1 : 0)
        }
        .onHover { hovering = $0 }
        .animation(.easeInOut(duration: 0.15), value: replying)
        .animation(.easeInOut(duration: 0.15), value: sentReply)
    }

    private func actions(_ posted: NotificationPosted) -> some View {
        HStack(spacing: 8) {
            if posted.canReply && !replying {
                Button {
                    replying = true
                    replyFocused = true
                } label: {
                    Label("Reply", systemImage: "arrowshape.turn.up.left")
                }
            }
            ForEach(posted.actions.prefix(3), id: \.index) { action in
                Button(action.title) { model.performNotificationAction(posted.key, index: action.index) }
            }
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
    }

    private var composer: some View {
        HStack(spacing: 8) {
            TextField("Reply to \(entry.posted.appName)…", text: $replyText, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(1...4)
                .focused($replyFocused)
                .onSubmit(sendReply)
                .onExitCommand(perform: cancelReply)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: 8).fill(.background))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(.separator))
            Button(action: sendReply) {
                Image(systemName: "arrow.up.circle.fill").font(.system(size: 22))
            }
            .buttonStyle(.plain)
            .foregroundStyle(canSend ? Color.accentColor : Color.secondary)
            .disabled(!canSend)
            .help("Send (Return)")
            Button(action: cancelReply) {
                Image(systemName: "xmark").font(.caption.weight(.semibold))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .help("Cancel (Esc)")
        }
    }

    private var canSend: Bool { !replyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private func sendReply() {
        guard canSend else { return }
        model.replyToNotification(entry.posted.key, text: replyText.trimmingCharacters(in: .whitespacesAndNewlines))
        replyText = ""
        replying = false
        sentReply = true
        Task {
            try? await Task.sleep(for: .seconds(2.5))
            sentReply = false
        }
    }

    private func cancelReply() {
        replying = false
        replyText = ""
    }
}
