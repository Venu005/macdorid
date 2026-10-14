import AVFoundation
import MacDroidCore
import SwiftUI

/// The picture, a status overlay, and the bar (spec §6.3). Plan 8 adds Back/Home/Recents/volume/lock and input.
struct MirrorContentView: View {
    static let barHeight: CGFloat = 40
    let model: AppModel
    let renderer: MirrorRenderer

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                MirrorVideoView(layer: renderer.layer)
                if let status = MirrorPresentation.status(model.mirror) {
                    Text(status)
                        .font(.callout)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white)
                        .padding(12)
                        .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 8))
                        .padding()
                }
            }
            MirrorBar(model: model).frame(height: Self.barHeight)
        }
        .background(Color.black)
        .ignoresSafeArea()
    }
}

private struct MirrorVideoView: NSViewRepresentable {
    let layer: AVSampleBufferDisplayLayer

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.wantsLayer = true
        view.layer = CALayer()
        view.layer?.backgroundColor = CGColor.black
        layer.frame = view.bounds
        layer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        view.layer?.addSublayer(layer)
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {}
}

private struct MirrorBar: View {
    @Bindable var model: AppModel

    var body: some View {
        HStack(spacing: 14) {
            if let notice = MirrorPresentation.audioNotice(model.mirror, requested: model.mirrorAudio) {
                Image(systemName: "speaker.slash").help(notice)
            }
            Spacer()
            if model.mirrorShowLatency, let ms = model.mirrorLatency {
                Text("\(ms) ms")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(color(MirrorPresentation.latencyTone(ms)))
                    .help("Time from the phone's screen to this window")
            }
            Toggle(isOn: $model.mirrorKeepOnTop) { Image(systemName: "pin") }
                .toggleStyle(.button)
                .help("Keep on top")
            Button { model.stopMirroring() } label: { Image(systemName: "stop.fill") }
                .help("Stop")
        }
        .buttonStyle(.borderless)
        .foregroundStyle(.white)
        .padding(.horizontal, 12)
        .background(Color(white: 0.12))
    }

    private func color(_ tone: MirrorPresentation.LatencyTone) -> Color {
        switch tone {
        case .good: .green
        case .fair: .orange
        case .poor: .red
        }
    }
}
