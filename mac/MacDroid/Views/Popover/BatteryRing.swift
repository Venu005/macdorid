import SwiftUI

/// The phone's battery as a ring around a phone glyph, with the percentage inside.
struct BatteryRing: View {
    let level: Int?
    let charging: Bool

    var body: some View {
        ZStack {
            Circle().stroke(Color.secondary.opacity(0.25), lineWidth: 4)
            if let level {
                Circle()
                    .trim(from: 0, to: CGFloat(max(0, min(level, 100))) / 100)
                    .stroke(tint(level), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            VStack(spacing: 0) {
                Image(systemName: charging ? "bolt.fill" : "iphone.gen3").font(.system(size: 12))
                Text(level.map { "\($0)%" } ?? "–").font(.system(size: 9, weight: .semibold)).monospacedDigit()
            }
        }
        .frame(width: 46, height: 46)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(level.map { "Battery \($0) percent\(charging ? ", charging" : "")" } ?? "Battery unknown")
    }

    private func tint(_ level: Int) -> Color {
        if charging { return .green }
        return level <= 20 ? .red : .accentColor
    }
}
