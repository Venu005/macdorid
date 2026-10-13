import MacDroidCore
import SwiftUI

/// The phone's media in the popover (spec §4): artwork, title, artist · app, a seek bar and transport buttons.
struct NowPlayingCard: View {
    let model: AppModel
    /// Passed as a value (not read from `model` here), so every new state redraws the card. Reading it through the
    /// shared model alone left the play/pause button stale in the menu-bar popover.
    let extras: PhoneExtrasState
    @State private var scrubbing: Double?

    var body: some View {
        let media = extras.media
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                artwork(model.mediaArtURL(media.artHash))
                VStack(alignment: .leading, spacing: 2) {
                    Text(media.title ?? "").font(.callout.weight(.semibold)).lineLimit(1)
                    Text(MediaPresentation.subtitle(media)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let duration = Double(media.durationMs ?? 0)
                let position = Double(MediaPresentation.position(media, receivedAt: extras.mediaReceivedAt ?? context.date, now: context.date))
                VStack(spacing: 2) {
                    Slider(
                        value: Binding(get: { scrubbing ?? position }, set: { scrubbing = $0 }),
                        in: 0...max(duration, 1),
                        onEditingChanged: { editing in
                            guard !editing, let target = scrubbing else { return }
                            model.mediaCommand(.seek, positionMs: Int64(target))
                            scrubbing = nil
                        }
                    )
                    .controlSize(.mini)
                    .disabled(!(media.canSeek ?? false) || duration <= 0)
                    HStack {
                        Text(MediaPresentation.timeLabel(Int64(scrubbing ?? position)))
                        Spacer()
                        Text(duration > 0 ? MediaPresentation.timeLabel(Int64(duration)) : "")
                    }
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 26) {
                Spacer()
                control("backward.fill", enabled: media.canPrevious ?? false) { model.mediaCommand(.previous) }
                control(media.playing == true ? "pause.fill" : "play.fill", enabled: true) {
                    model.mediaCommand(media.playing == true ? .pause : .play)
                }
                control("forward.fill", enabled: media.canNext ?? false) { model.mediaCommand(.next) }
                Spacer()
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.secondary.opacity(0.1)))
    }

    private func artwork(_ url: URL?) -> some View {
        Group {
            if let url, let image = NSImage(contentsOf: url) {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "music.note").font(.system(size: 18)).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).background(.quaternary)
            }
        }
        .frame(width: 44, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func control(_ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).font(.system(size: 16)) }
            .buttonStyle(.borderless)
            .disabled(!enabled)
    }
}
