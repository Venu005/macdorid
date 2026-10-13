import SwiftUI

/// The pairing QR code on a white tile so it scans in dark mode too.
struct QRCodeImage: View {
    let text: String

    var body: some View {
        Group {
            if let image = QRCode.image(for: text) {
                Image(nsImage: image).interpolation(.none).resizable().scaledToFit()
            } else {
                Image(systemName: "qrcode").resizable().scaledToFit().foregroundStyle(.secondary)
            }
        }
        .padding(12)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(.quaternary))
    }
}
