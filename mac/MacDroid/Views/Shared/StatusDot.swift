import MacDroidCore
import SwiftUI

struct StatusDot: View {
    let tone: StatusTone

    var body: some View {
        Circle()
            .fill(tone == .connected ? Color.green : Color.secondary)
            .frame(width: 8, height: 8)
    }
}
