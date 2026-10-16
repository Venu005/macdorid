import Foundation
import Testing
@testable import MacDroidCore

@Suite struct LinkRulesTests {
    @Test func findsTheFirstHttpLink() {
        #expect(LinkRules.firstLink(in: "ftp://x and then http://y.com/p and https://z.com") == "http://y.com/p")
        #expect(LinkRules.firstLink(in: "no link here") == nil)
        #expect(LinkRules.firstLink(in: "HTTPS://Example.com/A") == "HTTPS://Example.com/A")
    }

    // Review Focus #1
    @Test func trimsTrailingPunctuation() {
        #expect(LinkRules.firstLink(in: "(see https://x.com/a).") == "https://x.com/a")
        #expect(LinkRules.firstLink(in: "go to https://x.com/a?b=c!") == "https://x.com/a?b=c")
        #expect(LinkRules.firstLink(in: "\"https://x.com/q\"") == "https://x.com/q")
    }

    // Review Focus #2
    @Test func onlyHttpAndHttpsWithAHostAreAllowed() {
        #expect(LinkRules.allowedURL("https://ok.com/x") != nil)
        #expect(LinkRules.allowedURL("javascript:alert(1)") == nil)
        #expect(LinkRules.allowedURL("file:///etc/passwd") == nil)
        #expect(LinkRules.allowedURL("http://") == nil)
        #expect(LinkRules.allowedURL("https://x.com/" + String(repeating: "a", count: 8_200)) == nil)
    }

    // Final review I-3: a closing bracket that belongs to the link stays.
    @Test func balancedBracketsStay() {
        #expect(LinkRules.firstLink(in: "https://en.m.wikipedia.org/wiki/Mercury_(planet)") == "https://en.m.wikipedia.org/wiki/Mercury_(planet)")
        #expect(LinkRules.firstLink(in: "(https://en.m.wikipedia.org/wiki/Mercury_(planet))") == "https://en.m.wikipedia.org/wiki/Mercury_(planet)")
        #expect(LinkRules.firstLink(in: "(see https://x.com/a).") == "https://x.com/a")
    }

    @Test func oversizedLinksAreNotFound() {
        #expect(LinkRules.firstLink(in: "https://x.com/" + String(repeating: "a", count: 8_200)) == nil)
    }
}
@Suite struct PhoneExtrasTests {
    final class Harness: Sendable {
        let extras: PhoneExtras
        let sent: Collector<Message>
        let events: Collector<PhoneExtrasEvent>
        let art: IconCache
        let listener: Task<Void, Never>

        init(failSends: Bool = false) throws {
            let sent = Collector<Message>()
            let events = Collector<PhoneExtrasEvent>()
            let art = IconCache(directory: try makeTemporaryDirectory())
            let extras = PhoneExtras(art: art, now: { Date(timeIntervalSince1970: 1_000_000) }, send: { message in
                if failSends { throw HubError.notConnected }
                await sent.append(message)
            })
            let stream = extras.events
            self.extras = extras
            self.sent = sent
            self.events = events
            self.art = art
            self.listener = Task { for await event in stream { await events.append(event) } }
        }

        func connect(features: [String] = ProtocolInfo.features) async {
            await extras.connectionChanged(.connected(PeerInfo(device: "Phone", app: "0.5.0", features: features)))
        }
    }

    @Test func ringSendsStartAndStateFollowsThePhone() async throws {
        let h = try Harness()
        await h.connect()
        await h.extras.ring()
        #expect(await h.sent.items == [.deviceRing(DeviceRing(action: .start))])
        await h.extras.handle(.ringState(RingState(ringing: true)))
        #expect(await h.extras.state.ringing)
        await h.extras.stopRing()
        #expect(await h.sent.items.last == .deviceRing(DeviceRing(action: .stop)))
    }

    @Test func disconnectClearsRingingAndMedia() async throws {
        let h = try Harness()
        await h.connect()
        await h.extras.handle(.ringState(RingState(ringing: true)))
        await h.extras.handle(.mediaState(MediaState(active: true, title: "Song")))
        await h.extras.connectionChanged(.disconnected)
        let state = await h.extras.state
        #expect(!state.ringing)
        #expect(state.media == .idle)
    }

    // Review Focus #2
    @Test func nonHttpLinksAreIgnored() async throws {
        let h = try Harness()
        await h.extras.handle(.linkOpen(LinkOpen(url: "javascript:alert(1)")))
        await h.extras.handle(.linkOpen(LinkOpen(url: "https://example.com/x")))
        try await eventually { await h.events.items.count == 1 }
        #expect(await h.events.items == [.openLink(URL(string: "https://example.com/x")!)])
        h.listener.cancel()
    }

    @Test func sendingALinkReportsWhyNot() async throws {
        let h = try Harness()
        #expect(await h.extras.sendLink(in: "https://x.com") == .notConnected)
        await h.connect(features: [Feature.files])
        #expect(await h.extras.sendLink(in: "https://x.com") == .unsupported)
        await h.connect()
        #expect(await h.extras.sendLink(in: "no link") == .noLink)
        #expect(await h.extras.sendLink(in: "read (https://x.com/a).") == .sent)
        #expect(await h.sent.items == [.linkOpen(LinkOpen(url: "https://x.com/a"))])
    }

    @Test func mediaStateAndArtAreKept() async throws {
        let h = try Harness()
        await h.extras.handle(.mediaArt(MediaArt(hash: "0123456789abcdef", jpeg: "/9j/4AAQ")))
        await h.extras.handle(.mediaState(MediaState(active: true, title: "Song", artHash: "0123456789abcdef")))
        #expect(await h.extras.state.media.title == "Song")
        #expect(await h.extras.state.mediaReceivedAt == Date(timeIntervalSince1970: 1_000_000))
        #expect(h.art.url(for: "0123456789abcdef") != nil)
    }

    // Review Focus #5
    @Test func badArtHashIsIgnored() async throws {
        let h = try Harness()
        await h.extras.handle(.mediaArt(MediaArt(hash: "../../escape-art", jpeg: "/9j/4AAQ")))
        #expect(h.art.url(for: "../../escape-art") == nil)
    }

    @Test func commandsAreSent() async throws {
        let h = try Harness()
        await h.connect()
        await h.extras.command(.seek, positionMs: 30_000)
        #expect(await h.sent.items == [.mediaCommand(MediaCommand(command: .seek, positionMs: 30_000))])
    }
}

@Suite struct MediaPresentationTests {
    let t0 = Date(timeIntervalSince1970: 1_000_000)

    @Test func projectsWhilePlayingAndCapsAtDuration() {
        let playing = MediaState(active: true, durationMs: 100_000, positionMs: 10_000, playing: true)
        #expect(MediaPresentation.position(playing, receivedAt: t0, now: t0.addingTimeInterval(5)) == 15_000)
        #expect(MediaPresentation.position(playing, receivedAt: t0, now: t0.addingTimeInterval(500)) == 100_000)
        let paused = MediaState(active: true, durationMs: 100_000, positionMs: 10_000, playing: false)
        #expect(MediaPresentation.position(paused, receivedAt: t0, now: t0.addingTimeInterval(5)) == 10_000)
    }

    @Test func timeLabels() {
        #expect(MediaPresentation.timeLabel(65_000) == "1:05")
        #expect(MediaPresentation.timeLabel(3_723_000) == "1:02:03")
        #expect(MediaPresentation.timeLabel(0) == "0:00")
    }

    @Test func subtitles() {
        #expect(MediaPresentation.subtitle(MediaState(active: true, appName: "Spotify", artist: "Artist")) == "Artist · Spotify")
        #expect(MediaPresentation.subtitle(MediaState(active: true, appName: "YouTube", artist: "")) == "YouTube")
    }
}

@Suite struct ExtrasPlan11Tests {
    @Test func mediaStateCarriesTheSpeed() throws {
        let message = try MessageCodec.decode(Fixtures.text("media.state.json"))
        guard case .mediaState(let state) = message else { Issue.record("not media.state"); return }
        #expect(state.speed == 1.5)
    }

    @Test func projectedPositionAdvancesAtSpeed() {
        let t0 = Date(timeIntervalSince1970: 1_000)
        let state = MediaState(active: true, durationMs: 100_000, positionMs: 10_000, playing: true, speed: 1.5)
        #expect(MediaPresentation.position(state, receivedAt: t0, now: t0.addingTimeInterval(2)) == 13_000)
        let odd = MediaState(active: true, durationMs: 100_000, positionMs: 10_000, playing: true, speed: 0)
        #expect(MediaPresentation.position(odd, receivedAt: t0, now: t0.addingTimeInterval(2)) == 12_000)
    }

    @Test func aReleasedSeekHoldsUntilThePhoneCatchesUp() {
        let t0 = Date(timeIntervalSince1970: 1_000)
        let seek = MediaPresentation.PendingSeek(targetMs: 90_000, at: t0)
        // The phone hasn't moved yet: show the target, keep waiting.
        #expect(MediaPresentation.shown(projected: 10_000, pending: seek, now: t0.addingTimeInterval(1)) == 90_000)
        #expect(MediaPresentation.pendingStillHolds(seek, projected: 10_000, now: t0.addingTimeInterval(1)))
        // The phone got there (within 2 s): let go.
        #expect(!MediaPresentation.pendingStillHolds(seek, projected: 91_000, now: t0.addingTimeInterval(1)))
        // The phone never moved: give up after 3 s.
        #expect(!MediaPresentation.pendingStillHolds(seek, projected: 10_000, now: t0.addingTimeInterval(3)))
        #expect(MediaPresentation.shown(projected: 10_000, pending: nil, now: t0) == 10_000)
    }

    @Test func linkThrottleAllowsThreeIn10Seconds() {
        var throttle = LinkThrottle()
        let t0 = Date(timeIntervalSince1970: 1_000)
        let answers = [0, 1, 2, 9, 10].map { throttle.allows(at: t0.addingTimeInterval(TimeInterval($0))) }
        #expect(answers == [true, true, true, false, true])
    }

    @Test func pruneKeepsTheNewest() throws {
        let cache = IconCache(directory: try makeTemporaryDirectory())
        let png = Data([1, 2, 3]).base64EncodedString()
        let hashes = (0..<10).map { String(format: "%016x", $0) }
        for (i, hash) in hashes.enumerated() {
            cache.save(hash: hash, pngBase64: png)
            try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: Double(1_000 + i))],
                                                  ofItemAtPath: cache.url(for: hash)!.path(percentEncoded: false))
        }
        cache.prune(keeping: 8)
        #expect(cache.url(for: hashes[0]) == nil)
        #expect(cache.url(for: hashes[1]) == nil)
        #expect(hashes[2...].allSatisfy { cache.url(for: $0) != nil })
    }
}
