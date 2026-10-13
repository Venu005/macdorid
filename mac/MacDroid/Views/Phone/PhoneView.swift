import SwiftUI

struct PhoneView: View {
    let model: AppModel

    var body: some View {
        if model.showsPairing {
            PairingView(model: model)
        } else {
            PairedDeviceView(model: model)
        }
    }
}
