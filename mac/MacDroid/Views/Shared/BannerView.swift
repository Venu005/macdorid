import SwiftUI

struct BannerView: View {
    enum Kind { case warning, error }
    let kind: Kind
    let message: String

    private var tint: Color { kind == .warning ? Color.orange : Color.red }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: kind == .warning ? "exclamationmark.triangle.fill" : "xmark.octagon.fill")
                .foregroundStyle(tint)
            Text(message)
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
        .padding([.horizontal, .top], 12)
    }
}
