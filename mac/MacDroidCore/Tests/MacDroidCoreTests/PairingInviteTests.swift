import Foundation
import Testing
@testable import MacDroidCore

@Suite struct PairingInviteTests {
    let invite = PairingInvite(
        host: "192.168.1.20",
        port: 47999,
        fingerprint: "8a3ba983c43884812d5198df10a6891c193f3fb67d1e5b44c6902e9fde37b45a",
        code: "MZXW6YTBOI2DGNBVGY3TQOJQGE",
        name: "Sam's Mac+Pro ✨"
    )

    @Test func uriMatchesSharedFixture() throws {
        let expected = try Fixtures.text("pairing-uri.txt").trimmingCharacters(in: .whitespacesAndNewlines)
        #expect(invite.uri == expected)
    }

    @Test func displayCodeIsGroupedInFours() {
        #expect(invite.displayCode == "MZXW 6YTB OI2D GNBV GY3T QOJQ GE")
    }
}
