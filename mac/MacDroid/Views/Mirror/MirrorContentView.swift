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
                MirrorInputView(model: model)
                if let status = MirrorPresentation.status(model.mirror, stalled: model.mirrorStalled) {
                    Text(status)
                        .font(.callout)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white)
                        .padding(12)
                        .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 8))
                        .padding()
                        .allowsHitTesting(false)
                }
                VStack {
                    Spacer()
                    if let notice = MirrorPresentation.controlNotice(model.mirror) ?? (model.mirror.textFieldMissing ? MirrorPresentation.textFieldNotice : nil) {
                        Text(notice)
                            .font(.caption)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(.black.opacity(0.65), in: Capsule())
                            .padding(.bottom, 10)
                    }
                }
                .allowsHitTesting(false)
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
            Group {
                bar("chevron.backward", "Back") { model.sendMirrorKey(.back) }
                bar("circle", "Home") { model.sendMirrorKey(.home) }
                bar("square.on.square", "Recents") { model.sendMirrorKey(.recents) }
                bar("speaker.minus", "Volume down") { model.sendMirrorKey(.volumeDown) }
                bar("speaker.plus", "Volume up") { model.sendMirrorKey(.volumeUp) }
                bar("lock", "Lock phone") { model.sendMirrorKey(.power) }
            }
            .disabled(!model.mirror.controlAvailable)
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

    private func bar(_ symbol: String, _ help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol) }.help(help)
    }

    private func color(_ tone: MirrorPresentation.LatencyTone) -> Color {
        switch tone {
        case .good: .green
        case .fair: .orange
        case .poor: .red
        }
    }
}
