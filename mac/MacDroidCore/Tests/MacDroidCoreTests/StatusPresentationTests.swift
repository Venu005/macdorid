import Foundation
import Testing
@testable import MacDroidCore

@Suite struct StatusPresentationTests {
    let device = PairedDevice(fingerprint: "aa", name: "Nothing Phone (3)", model: "Nothing A024", pairedAt: Date(timeIntervalSince1970: 0))

    @Test func notPaired() {
        let status = StatusPresentation.phoneStatus(paired: nil, connection: .disconnected)
        #expect(status == PhoneStatus(symbol: "iphone.slash", title: "No phone paired", subtitle: "Not paired", tone: .notPaired))
    }

    @Test func pairedButDisconnected() {
        let status = StatusPresentation.phoneStatus(paired: device, connection: .disconnected)
        #expect(status == PhoneStatus(symbol: "iphone", title: "Nothing Phone (3)", subtitle: "Not connected", tone: .waiting))
    }

    @Test func connectedWithBatteryAndCharging() {
        let peer = PeerInfo(device: "Nothing Phone (3)", app: "0.2.0", features: [], battery: 82, charging: true)
        let status = StatusPresentation.phoneStatus(paired: device, connection: .connected(peer))
        #expect(status == PhoneStatus(symbol: "iphone.radiowaves.left.and.right", title: "Nothing Phone (3)", subtitle: "Connected · 82% · charging", tone: .connected))
    }

    @Test func connectedBeforeFirstBatteryReport() {
        let peer = PeerInfo(device: "Pixel", app: "0.2.0", features: [])
        #expect(StatusPresentation.phoneStatus(paired: device, connection: .connected(peer)).subtitle == "Connected")
    }

    @Test func batteryText() {
        #expect(StatusPresentation.batteryText(.disconnected) == "—")
        #expect(StatusPresentation.batteryText(.connected(PeerInfo(device: "P", app: "1", features: []))) == "—")
        #expect(StatusPresentation.batteryText(.connected(PeerInfo(device: "P", app: "1", features: [], battery: 40, charging: false))) == "40%")
        #expect(StatusPresentation.batteryText(.connected(PeerInfo(device: "P", app: "1", features: [], battery: 99, charging: true))) == "99% · charging")
    }

    @Test func countdownFormatsMinutesAndSeconds() {
        #expect(StatusPresentation.countdown(remaining: 112) == "1:52")
        #expect(StatusPresentation.countdown(remaining: 120) == "2:00")
        #expect(StatusPresentation.countdown(remaining: 9.2) == "0:10")
    }

    // Review Focus #3
    @Test func countdownClampsAtZero() {
        #expect(StatusPresentation.countdown(remaining: 0) == "0:00")
        #expect(StatusPresentation.countdown(remaining: -5) == "0:00")
    }
}
