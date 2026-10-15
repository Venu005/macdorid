import MacDroidCore
import SwiftUI

/// What the call panel shows (spec §3.2): ringing, on a call, or a short notice.
struct CallPanelView: View {
    let model: AppModel

    var body: some View {
        Group {
            switch model.calls.panel {
            case .ringing(let call):
                HStack(spacing: 12) {
                    Image(systemName: "phone.fill").font(.title2).foregroundStyle(.green)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Incoming call").font(.caption).foregroundStyle(.secondary)
                        Text(CallPresentation.title(call)).font(.headline).lineLimit(1)
                        if let detail = CallPresentation.detail(call) {
                            Text(detail).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 8)
                    Button("Decline") { model.declineCall(call.callId) }.tint(.red)
                    Button("Answer") { model.answerCall(call.callId) }.tint(.green)
                }
                .buttonStyle(.borderedProminent)
            case .active(let call):
                HStack(spacing: 12) {
                    Image(systemName: "phone.fill").font(.title2).foregroundStyle(.green)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("On a call with \(CallPresentation.title(call))").font(.headline).lineLimit(1)
                        if let since = call.since {
                            TimelineView(.periodic(from: .now, by: 1)) { context in
                                Text(CallPresentation.duration(from: since, to: context.date)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                            }
                        }
                    }
                    Spacer(minLength: 8)
                    Button("Hang up") { model.hangUpCall(call.callId) }.buttonStyle(.borderedProminent).tint(.red)
                }
            case .notice(let text):
                Label(text, systemImage: "exclamationmark.triangle.fill").font(.callout)
            case .none:
                EmptyView()
            }
        }
        .padding(14)
        .frame(width: 320, height: 104, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
    }
}
