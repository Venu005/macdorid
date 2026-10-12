import Foundation
import Testing
@testable import MacDroidCore

@Suite struct PairingManagerTests {
    let clock = FakeClock()
    let store = InMemoryPairedDeviceStore()
    var manager: PairingManager { PairingManager(store: store, windowDuration: 120, now: { [clock] in clock.now }) }

    @Test func base32MatchesRFC4648Vectors() {
        #expect(PairingManager.base32(Array("foo".utf8)) == "MZXW6")
        #expect(PairingManager.base32(Array("foobar".utf8)) == "MZXW6YTBOI")
        #expect(PairingManager.makeCode().count == 26)
        #expect(PairingManager.makeCode() != PairingManager.makeCode())
    }

    @Test func constantTimeEquals() {
        #expect(PairingManager.constantTimeEquals("ABC", "ABC"))
        #expect(!PairingManager.constantTimeEquals("ABC", "ABD"))
        #expect(!PairingManager.constantTimeEquals("ABC", "ABCD"))
    }

    @Test func windowExpiresAfterDuration() async {
        let manager = manager
        let window = await manager.openWindow()
        #expect(window.expiresAt == clock.now.addingTimeInterval(120))
        clock.advance(119)
        #expect(await manager.currentWindow() == window)
        clock.advance(1)
        #expect(await manager.currentWindow() == nil)
    }

    @Test func correctCodePairsAndPersists() async throws {
        let manager = manager
        let window = await manager.openWindow()
        let device = try await manager.completePairing(code: window.code, fingerprint: "aa", deviceName: "Phone", model: "Nothing")
        #expect(device.fingerprint == "aa")
        #expect(store.load() == device)
        #expect(await manager.isTrusted(fingerprint: "aa"))
        #expect(await manager.currentWindow() == nil)
    }

    @Test func wrongCodeIsRejectedAndWindowStaysOpen() async {
        let manager = manager
        _ = await manager.openWindow()
        await #expect(throws: PairingError.codeMismatch) {
            try await manager.completePairing(code: "WRONG", fingerprint: "aa", deviceName: "P", model: "M")
        }
        #expect(await manager.currentWindow() != nil)
        #expect(await manager.pairedDevice == nil)
    }

    @Test func expiredWindowRejectsPairing() async {
        let manager = manager
        let window = await manager.openWindow()
        clock.advance(121)
        await #expect(throws: PairingError.windowClosed) {
            try await manager.completePairing(code: window.code, fingerprint: "aa", deviceName: "P", model: "M")
        }
    }

    // Review Focus #2
    @Test func codeCannotBeReusedAfterPairing() async throws {
        let manager = manager
        let window = await manager.openWindow()
        _ = try await manager.completePairing(code: window.code, fingerprint: "aa", deviceName: "P", model: "M")
        await #expect(throws: PairingError.windowClosed) {
            try await manager.completePairing(code: window.code, fingerprint: "bb", deviceName: "Evil", model: "M")
        }
        #expect(await manager.isTrusted(fingerprint: "aa"))
        #expect(await !manager.isTrusted(fingerprint: "bb"))
    }

    // Review Focus #3
    @Test func repairingReplacesOldFingerprint() async throws {
        let manager = manager
        _ = try await manager.completePairing(code: await manager.openWindow().code, fingerprint: "old", deviceName: "P", model: "M")
        _ = try await manager.completePairing(code: await manager.openWindow().code, fingerprint: "new", deviceName: "P", model: "M")
        #expect(await !manager.isTrusted(fingerprint: "old"))
        #expect(await manager.isTrusted(fingerprint: "new"))
        #expect(store.load()?.fingerprint == "new")
    }

    @Test func handshakeAcceptance() async throws {
        let manager = manager
        #expect(await !manager.acceptsHandshake(fingerprint: "stranger"))
        let window = await manager.openWindow()
        #expect(await manager.acceptsHandshake(fingerprint: "stranger"))
        _ = try await manager.completePairing(code: window.code, fingerprint: "aa", deviceName: "P", model: "M")
        #expect(await manager.acceptsHandshake(fingerprint: "aa"))
        #expect(await !manager.acceptsHandshake(fingerprint: "stranger"))
    }

    @Test func forgetClearsTrust() async throws {
        let manager = manager
        _ = try await manager.completePairing(code: await manager.openWindow().code, fingerprint: "aa", deviceName: "P", model: "M")
        await manager.forget()
        #expect(await !manager.isTrusted(fingerprint: "aa"))
        #expect(store.load() == nil)
    }

    @Test func loadsPairedDeviceFromStore() async {
        let device = PairedDevice(fingerprint: "aa", name: "P", model: "M", pairedAt: clock.now)
        let manager = PairingManager(store: InMemoryPairedDeviceStore(initial: device), now: { [clock] in clock.now })
        #expect(await manager.isTrusted(fingerprint: "aa"))
    }

    @Test func emitsEvents() async throws {
        let manager = manager
        var events = manager.events.makeAsyncIterator()
        let window = await manager.openWindow()
        #expect(await events.next() == .windowOpened(window))
        let device = try await manager.completePairing(code: window.code, fingerprint: "aa", deviceName: "P", model: "M")
        #expect(await events.next() == .windowClosed)
        #expect(await events.next() == .paired(device))
    }

    @Test func userDefaultsStoreRoundTrip() throws {
        let defaults = try #require(UserDefaults(suiteName: "dev.macdroid.tests.\(UUID().uuidString)"))
        let store = UserDefaultsPairedDeviceStore(defaults: defaults)
        let device = PairedDevice(fingerprint: "aa", name: "Phone", model: "M", pairedAt: Date(timeIntervalSince1970: 5))
        store.save(device)
        #expect(UserDefaultsPairedDeviceStore(defaults: defaults).load() == device)
        store.save(nil)
        #expect(store.load() == nil)
    }
}
