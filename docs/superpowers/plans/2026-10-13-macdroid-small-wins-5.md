# MacDroid Small Wins — Plan 5: Ring, Links, Media Controls

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ring the phone from the Mac, open links on the other device, and control the phone's media from a Now Playing card in the Mac popover.

**Architecture:**
- **Shared pieces:**
  - New `device.ring*`, `link.open` and `media.*` messages in both codecs, plus three features (`ring`, `links`, `media`).
  - Pure, mirrored link rules (Swift `LinkRules`, Kotlin `LinkExtractor`).
- **Android:**
  - Pure core logic: `RingSession` (state and timeout) and `MediaTracker` (which session, when to send, art once).
  - Thin platform glue in the app: a `Ringer` (alarm tone, torch, volume-key catcher, notification), an `OpenOnMacActivity` share target, a link notification, and a `MediaWatcher` (media sessions through the existing notification listener).
- **Mac:** a `PhoneExtras` actor and `MediaPresentation` in the core. In the app:
  - a Services provider ("Open on Phone")
  - a ring button in the popover and on the Phone page
  - a `NowPlayingCard`

**Tech Stack:** Swift 6 / SwiftUI / AppKit Services; Kotlin 2.4.21 / Compose; Android `MediaSessionManager`, `Ringtone`, `CameraManager.setTorchMode`, `MediaSession` + `VolumeProvider`.

**Spec:** `docs/superpowers/specs/2026-10-13-macdroid-small-wins-design.md`.

## Global Constraints

- **Branch:** `feat/small-wins` from `main`. Don't merge: that waits for the owner's check.
- **Handoff rule:** every task's commit updates `docs/handoff/`.
  - Task 1 adds a CHANGELOG entry at the top, `## <commit date> — Plan 5: small wins (in progress on feat/small-wins)`.
  - Each later task adds a bullet under it.
- **Features after Task 1:** Swift `ProtocolInfo.features` and Kotlin `Features.SUPPORTED` are `files, notifications, clipboard, ring, links, media`, in that order.
- **Links:**
  - only `http`/`https` with a host, ≤ 8,192 UTF-8 bytes
  - the first match of `(?i)\bhttps?://[^\s<>"']+`, with trailing `.,;:!?)]}'"` characters trimmed
  - the receiver ignores anything else
- **Ring:**
  - full alarm-stream volume, restored afterwards
  - alarm tone looping on `USAGE_ALARM`
  - torch flashing 250 ms on / 250 ms off
  - auto-stop after 30,000 ms
  - stops on Stop, a volume key, `device.ring{stop}`, the timeout or a disconnect
  - notification channel id `ring`, "Ring", `IMPORTANCE_HIGH`; text "Ringing — requested by your Mac", action "Stop"
  - no `USE_FULL_SCREEN_INTENT`
- **Media:**
  - the first playing session, else the most recently active paused one if active within 300,000 ms, else idle
  - resend only on a track, playing, controls or `artHash` change, or a position jump of more than 2,000 ms against the projection
  - art: JPEG quality 80, ≤ 300 px on the longest side, hash = the first 16 lowercase hex characters of its SHA-256, sent once per connection, before the state
  - the Mac projects the position from **its own receive time** (`positionMs + (now − receivedAt)`, capped at the duration); see the Task 4 ruling
- **Copy:**
  - Android toasts: "Opening on your Mac", "No link to open", "Not connected to your Mac", "Update MacDroid on your Mac to use this"
  - Android link notification: channel `links` "Links", title "Link from your Mac", text = the URL's host
  - Mac Services item: "Open on Phone"; its notifications are "No link in the selection" and "Your phone isn't connected"
  - Mac Phone page: "Ring Phone" / "Stop Ringing"; note "Media controls need notification access on your phone."
- **No new Android runtime permissions.** The torch needs no `CAMERA` permission.
- **Build gates** (as in earlier plans):
  - `cd mac/MacDroidCore && swift test`
  - the Mac app build with no warnings
  - `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`, with no warnings
  - `./scripts/cross-platform-test.sh`
- No UI test harness exists: views and platform glue are verified by the build and the owner's checklist. Don't automate anything on the owner's phone.

## Review Focus

1. **A link in shared text wrapped in punctuation**, such as `"(see https://x.com/a)."`, must send `https://x.com/a`, never a URL with `)` or `.` on the end. → Task 2 `trimsTrailingPunctuation` (both sides).
2. **A `link.open` carrying `javascript:` or `file:`** from a misbehaving peer must never be opened. → Task 2 `onlyHttpAndHttpsWithAHostAreAllowed`; Task 4 `nonHttpLinksAreIgnored`.
3. **A ring that nobody stops** must end at 30 s, and a disconnect mid-ring must stop it. → Task 3 `timesOutAfterThirtySeconds`, `stopReportsOnlyWhenRinging`.
4. **A podcast that's playing** must not send a message every second (only on a real change), but a seek must be sent. → Task 3 `normalPlaybackIsNotResent`, `aSeekIsResent`.
5. **A crafted `media.art` hash** must not escape the art cache (it reuses `IconCache` validation). → Task 4 `badArtHashIsIgnored`.

---

## File Structure

```
protocol/fixtures/device.ring.json, device.ringState.json, link.open.json, media.state.json, media.state.idle.json, media.art.json, media.command.json (new); PROTOCOL.md
mac/MacDroidCore/Sources/MacDroidCore/
  Protocol/Messages.swift, MessageCodec.swift                 (modify)
  Extras/LinkRules.swift, Extras/PhoneExtras.swift             (new)
  Presentation/MediaPresentation.swift                         (new)
  MacDroidDevServer/main.swift                                 (modify, Task 9)
mac/MacDroidCore/Tests/MacDroidCoreTests/ProtocolFixtureTests.swift (modify); ExtrasTests.swift (new)
mac/project.yml                                                (modify: NSServices)
mac/MacDroid/Extras/ServicesProvider.swift                     (new)
mac/MacDroid/AppModel.swift, MacDroidApp.swift, AppDelegate.swift (modify)
mac/MacDroid/Views/MenuBarPopover.swift (modify); Views/Popover/NowPlayingCard.swift (new); Views/Phone/PairedDeviceView.swift (modify)
android/core/src/main/kotlin/dev/macdroid/core/protocol/Messages.kt, MessageCodec.kt (modify)
android/core/src/main/kotlin/dev/macdroid/core/extras/LinkExtractor.kt, RingSession.kt, MediaTracker.kt (new)
android/core/src/test/kotlin/dev/macdroid/core/extras/ExtrasTest.kt (new); protocol/MessageCodecTest.kt, CrossPlatformTest.kt (modify)
android/app/src/main/java/dev/macdroid/app/platform/extras/Ringer.kt, LinkNotifications.kt, MediaWatcher.kt (new)
android/app/src/main/java/dev/macdroid/app/ui/share/OpenOnMacActivity.kt (new)
android/app/src/main/java/dev/macdroid/app/ui/presentation/OpenOnMacDecision.kt (new) + test
android/app/src/main/java/dev/macdroid/app/service/ConnectionService.kt, MacDroidApp.kt, AndroidManifest.xml (modify)
scripts/cross-platform-test.sh (modify)
```

---

### Task 1: Protocol — ring, links and media messages

**Files:**
- Create: the seven fixtures listed above
- Modify: `Messages.swift`, `MessageCodec.swift`, `Messages.kt`, `MessageCodec.kt` and `PROTOCOL.md`
- Test: `ProtocolFixtureTests.swift`, `MessageCodecTest.kt`
- Handoff: CHANGELOG entry; ARCHITECTURE message types

**Interfaces (produces):**
- **Swift:**
  - `RingAction` (`.start`, `.stop`), `DeviceRing(action:)` and `RingState(ringing:)`
  - `LinkOpen(url:)`
  - `MediaState`: `active: Bool` plus optional `packageName` (JSON `package`), `appName`, `title`, `artist`, `durationMs`, `positionMs` and `positionAt` (`Int64?`), `playing`, `canPrevious`, `canNext`, `canSeek` (`Bool?`) and `artHash`; plus the static `MediaState.idle`
  - `MediaArt(hash:jpeg:)`
  - `MediaCommandKind` (`.play`, `.pause`, `.next`, `.previous`, `.seek`) and `MediaCommand(command:positionMs:)`
  - `Message` cases `.deviceRing`, `.ringState`, `.linkOpen`, `.mediaState`, `.mediaArt`, `.mediaCommand`
  - `Feature.ring`, `Feature.links`, `Feature.media`
- **Kotlin:**
  - `RingAction { Start, Stop }`, `DeviceRingBody(action)` and `RingStateBody(ringing)`
  - `LinkOpenBody(url)`
  - `MediaStateBody(active, packageName?, appName?, title?, artist?, durationMs?, positionMs?, positionAt?, playing?, canPrevious?, canNext?, canSeek?, artHash?)`, with all optional fields defaulting to `null`
  - `MediaArtBody(hash, jpeg)`
  - `MediaCommandKind { Play, Pause, Next, Previous, Seek }` and `MediaCommandBody(command, positionMs: Long? = null)`
  - `Message.DeviceRing`, `RingState`, `LinkOpen`, `MediaState`, `MediaArt`, `MediaCommand`
  - `Features.RING`, `LINKS`, `MEDIA`

- [ ] **Step 1: Branch and fixtures**

```bash
git checkout main && git checkout -b feat/small-wins
```

Write these fixtures (ids `6f1c2a0e-0000-4000-8000-0000000000NN`, NN = 30–36):

```json
{ "v": 1, "type": "device.ring", "id": "6f1c2a0e-0000-4000-8000-000000000030", "body": { "action": "start" } }
```
```json
{ "v": 1, "type": "device.ringState", "id": "6f1c2a0e-0000-4000-8000-000000000031", "body": { "ringing": true } }
```
```json
{ "v": 1, "type": "link.open", "id": "6f1c2a0e-0000-4000-8000-000000000032", "body": { "url": "https://github.com/Venu005/macdroid" } }
```
`media.state.json`:
```json
{ "v": 1, "type": "media.state", "id": "6f1c2a0e-0000-4000-8000-000000000033", "body": { "active": true, "package": "com.spotify.music", "appName": "Spotify", "title": "Song", "artist": "Artist", "durationMs": 215000, "positionMs": 61000, "positionAt": 1791892800000, "playing": true, "canPrevious": true, "canNext": true, "canSeek": true, "artHash": "0123456789abcdef" } }
```
`media.state.idle.json`:
```json
{ "v": 1, "type": "media.state", "id": "6f1c2a0e-0000-4000-8000-000000000034", "body": { "active": false } }
```
```json
{ "v": 1, "type": "media.art", "id": "6f1c2a0e-0000-4000-8000-000000000035", "body": { "hash": "0123456789abcdef", "jpeg": "/9j/4AAQSkZJRg==" } }
```
```json
{ "v": 1, "type": "media.command", "id": "6f1c2a0e-0000-4000-8000-000000000036", "body": { "command": "seek", "positionMs": 93000 } }
```

- [ ] **Step 2: Failing Swift tests**

In `ProtocolFixtureTests.swift`, add the seven fixture names to `messageFixtures` (the round-trip proves that `media.state.idle` encodes without the nil fields). Update the features test:

```swift
    @Test func supportedFeaturesIncludeTheSmallWins() {
        #expect(ProtocolInfo.features == [Feature.files, Feature.notifications, Feature.clipboard, Feature.ring, Feature.links, Feature.media])
    }
```

(Rename/replace the existing `supportedFeaturesIncludeClipboard`.) Add:

```swift
    @Test func smallWinsFixturesDecode() throws {
        #expect(try MessageCodec.decode(Fixtures.text("device.ring.json")) == .deviceRing(DeviceRing(action: .start)))
        #expect(try MessageCodec.decode(Fixtures.text("device.ringState.json")) == .ringState(RingState(ringing: true)))
        #expect(try MessageCodec.decode(Fixtures.text("link.open.json")) == .linkOpen(LinkOpen(url: "https://github.com/Venu005/macdroid")))
        #expect(try MessageCodec.decode(Fixtures.text("media.state.json")) == .mediaState(MediaState(
            active: true, packageName: "com.spotify.music", appName: "Spotify", title: "Song", artist: "Artist",
            durationMs: 215_000, positionMs: 61_000, positionAt: 1_791_892_800_000, playing: true,
            canPrevious: true, canNext: true, canSeek: true, artHash: "0123456789abcdef"
        )))
        #expect(try MessageCodec.decode(Fixtures.text("media.state.idle.json")) == .mediaState(.idle))
        #expect(try MessageCodec.decode(Fixtures.text("media.art.json")) == .mediaArt(MediaArt(hash: "0123456789abcdef", jpeg: "/9j/4AAQSkZJRg==")))
        #expect(try MessageCodec.decode(Fixtures.text("media.command.json")) == .mediaCommand(MediaCommand(command: .seek, positionMs: 93_000)))
    }
```

Run `cd mac/MacDroidCore && swift test --filter ProtocolFixtureTests`. Expected: compile errors.

- [ ] **Step 3: Implement (Swift)**

In `Messages.swift`:
- Add to `Feature`: `public static let ring = "ring"`, `links = "links"` and `media = "media"`.
- Set `ProtocolInfo.features = [Feature.files, Feature.notifications, Feature.clipboard, Feature.ring, Feature.links, Feature.media]`.
- Add, before `public enum Message`:

```swift
// MARK: Small wins (Plan 5)

public enum RingAction: String, Codable, Sendable { case start, stop }

public struct DeviceRing: Codable, Equatable, Sendable {
    public var action: RingAction
    public init(action: RingAction) { self.action = action }
}

public struct RingState: Codable, Equatable, Sendable {
    public var ringing: Bool
    public init(ringing: Bool) { self.ringing = ringing }
}

public struct LinkOpen: Codable, Equatable, Sendable {
    public var url: String
    public init(url: String) { self.url = url }
}

/// What's playing on the phone. `active == false` means nothing; the other fields are then nil (omitted on the wire).
public struct MediaState: Codable, Equatable, Sendable {
    public var active: Bool
    public var packageName: String?
    public var appName: String?
    public var title: String?
    public var artist: String?
    public var durationMs: Int64?
    public var positionMs: Int64?
    /// Phone epoch milliseconds when `positionMs` was read.
    public var positionAt: Int64?
    public var playing: Bool?
    public var canPrevious: Bool?
    public var canNext: Bool?
    public var canSeek: Bool?
    public var artHash: String?

    enum CodingKeys: String, CodingKey {
        case active, packageName = "package", appName, title, artist, durationMs, positionMs, positionAt, playing, canPrevious, canNext, canSeek, artHash
    }

    public init(
        active: Bool, packageName: String? = nil, appName: String? = nil, title: String? = nil, artist: String? = nil,
        durationMs: Int64? = nil, positionMs: Int64? = nil, positionAt: Int64? = nil, playing: Bool? = nil,
        canPrevious: Bool? = nil, canNext: Bool? = nil, canSeek: Bool? = nil, artHash: String? = nil
    ) {
        self.active = active
        self.packageName = packageName
        self.appName = appName
        self.title = title
        self.artist = artist
        self.durationMs = durationMs
        self.positionMs = positionMs
        self.positionAt = positionAt
        self.playing = playing
        self.canPrevious = canPrevious
        self.canNext = canNext
        self.canSeek = canSeek
        self.artHash = artHash
    }

    public static let idle = MediaState(active: false)
}

public struct MediaArt: Codable, Equatable, Sendable {
    public var hash: String
    /// Base64 JPEG, at most 300 px on the longest side.
    public var jpeg: String
    public init(hash: String, jpeg: String) {
        self.hash = hash
        self.jpeg = jpeg
    }
}

public enum MediaCommandKind: String, Codable, Sendable { case play, pause, next, previous, seek }

public struct MediaCommand: Codable, Equatable, Sendable {
    public var command: MediaCommandKind
    /// Seek only.
    public var positionMs: Int64?
    public init(command: MediaCommandKind, positionMs: Int64? = nil) {
        self.command = command
        self.positionMs = positionMs
    }
}
```

Add the `Message` cases `deviceRing(DeviceRing)`, `ringState(RingState)`, `linkOpen(LinkOpen)`, `mediaState(MediaState)`, `mediaArt(MediaArt)` and `mediaCommand(MediaCommand)`. Add the codec branches with the type strings `"device.ring"`, `"device.ringState"`, `"link.open"`, `"media.state"`, `"media.art"` and `"media.command"`, following the existing pattern.

Run `swift test`. Expected: PASS. Fix any other test that asserts the old feature list: `ConnectionHubTests` and `ServerTests` use `ProtocolInfo.features`, so they need no change.

- [ ] **Step 4: Failing Kotlin tests**

In `MessageCodecTest.kt`, add the seven names to `messageFixtures`. Replace the features test with:

```kotlin
    @Test fun supportedFeaturesIncludeTheSmallWins() {
        assertEquals(listOf("files", "notifications", "clipboard", "ring", "links", "media"), Features.SUPPORTED)
    }
```

Add:

```kotlin
    @Test fun smallWinsFixturesDecode() {
        assertEquals(Message.DeviceRing(DeviceRingBody(RingAction.Start)), MessageCodec.decode(Fixtures.text("device.ring.json")))
        assertEquals(Message.RingState(RingStateBody(true)), MessageCodec.decode(Fixtures.text("device.ringState.json")))
        assertEquals(Message.LinkOpen(LinkOpenBody("https://github.com/Venu005/macdroid")), MessageCodec.decode(Fixtures.text("link.open.json")))
        assertEquals(
            Message.MediaState(MediaStateBody(active = true, packageName = "com.spotify.music", appName = "Spotify", title = "Song", artist = "Artist",
                durationMs = 215_000, positionMs = 61_000, positionAt = 1_791_892_800_000, playing = true,
                canPrevious = true, canNext = true, canSeek = true, artHash = "0123456789abcdef")),
            MessageCodec.decode(Fixtures.text("media.state.json")),
        )
        assertEquals(Message.MediaState(MediaStateBody(active = false)), MessageCodec.decode(Fixtures.text("media.state.idle.json")))
        assertEquals(Message.MediaArt(MediaArtBody("0123456789abcdef", "/9j/4AAQSkZJRg==")), MessageCodec.decode(Fixtures.text("media.art.json")))
        assertEquals(Message.MediaCommand(MediaCommandBody(MediaCommandKind.Seek, 93_000)), MessageCodec.decode(Fixtures.text("media.command.json")))
    }
```

Run `cd android && ./gradlew :core:test --tests 'dev.macdroid.core.protocol.MessageCodecTest'`. Expected: compilation fails.

- [ ] **Step 5: Implement (Kotlin)**

In `Messages.kt`:
- Add to `Features`: `const val RING = "ring"`, `const val LINKS = "links"` and `const val MEDIA = "media"`.
- Set `SUPPORTED = listOf(FILES, NOTIFICATIONS, CLIPBOARD, RING, LINKS, MEDIA)`.
- Add:

```kotlin
@Serializable
enum class RingAction { @SerialName("start") Start, @SerialName("stop") Stop }

@Serializable
data class DeviceRingBody(val action: RingAction)

@Serializable
data class RingStateBody(val ringing: Boolean)

@Serializable
data class LinkOpenBody(val url: String)

/** What's playing; `active = false` means nothing (other fields null, so they're left off the wire). */
@Serializable
data class MediaStateBody(
    val active: Boolean,
    @SerialName("package") val packageName: String? = null,
    val appName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val positionAt: Long? = null,
    val playing: Boolean? = null,
    val canPrevious: Boolean? = null,
    val canNext: Boolean? = null,
    val canSeek: Boolean? = null,
    val artHash: String? = null,
)

@Serializable
data class MediaArtBody(val hash: String, val jpeg: String)

@Serializable
enum class MediaCommandKind {
    @SerialName("play") Play,
    @SerialName("pause") Pause,
    @SerialName("next") Next,
    @SerialName("previous") Previous,
    @SerialName("seek") Seek,
}

@Serializable
data class MediaCommandBody(val command: MediaCommandKind, val positionMs: Long? = null)
```

Add the `Message` subclasses `DeviceRing(DeviceRingBody)`, `RingState(RingStateBody)`, `LinkOpen(LinkOpenBody)`, `MediaState(MediaStateBody)`, `MediaArt(MediaArtBody)` and `MediaCommand(MediaCommandBody)`, and the codec lines using the same type strings as Swift.

Run `./gradlew :core:test`. Expected: PASS.

- [ ] **Step 6: PROTOCOL.md, handoff, commit**

- PROTOCOL.md: add the six rows from spec §2, a "Small wins rules" section with the spec §2 rules, and the three features.
- CHANGELOG: add the Plan 5 entry with "Task 1: ring/link/media messages and features in both codecs."
- ARCHITECTURE: extend the message-types line.

```bash
git add protocol mac/MacDroidCore android/core docs/handoff
git commit -m "feat(protocol): ring, link and media messages

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Link rules on both sides

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Extras/LinkRules.swift`, `android/core/src/main/kotlin/dev/macdroid/core/extras/LinkExtractor.kt`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/ExtrasTests.swift` (new), `android/core/src/test/kotlin/dev/macdroid/core/extras/ExtrasTest.kt` (new)

**Interfaces (produces):**
- Swift `LinkRules`:
  - `maxBytes = 8_192`
  - `firstLink(in text: String) -> String?`
  - `allowedURL(_ string: String) -> URL?`
- Kotlin `LinkExtractor`:
  - `MAX_BYTES = 8_192`
  - `firstLink(text: String?): String?`
  - `isAllowed(url: String): Boolean`

- [ ] **Step 1: Failing tests (both)**

`ExtrasTests.swift`:

```swift
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

    @Test func oversizedLinksAreNotFound() {
        #expect(LinkRules.firstLink(in: "https://x.com/" + String(repeating: "a", count: 8_200)) == nil)
    }
}
```

`ExtrasTest.kt`:

```kotlin
package dev.macdroid.core.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkExtractorTest {
    @Test fun findsTheFirstHttpLink() {
        assertEquals("http://y.com/p", LinkExtractor.firstLink("ftp://x and then http://y.com/p and https://z.com"))
        assertNull(LinkExtractor.firstLink("no link here"))
        assertNull(LinkExtractor.firstLink(null))
        assertEquals("HTTPS://Example.com/A", LinkExtractor.firstLink("HTTPS://Example.com/A"))
    }

    // Review Focus #1
    @Test fun trimsTrailingPunctuation() {
        assertEquals("https://x.com/a", LinkExtractor.firstLink("(see https://x.com/a)."))
        assertEquals("https://x.com/a?b=c", LinkExtractor.firstLink("go to https://x.com/a?b=c!"))
        assertEquals("https://x.com/q", LinkExtractor.firstLink("\"https://x.com/q\""))
    }

    // Review Focus #2
    @Test fun onlyHttpAndHttpsWithAHostAreAllowed() {
        assertTrue(LinkExtractor.isAllowed("https://ok.com/x"))
        assertFalse(LinkExtractor.isAllowed("javascript:alert(1)"))
        assertFalse(LinkExtractor.isAllowed("file:///etc/passwd"))
        assertFalse(LinkExtractor.isAllowed("http://"))
        assertFalse(LinkExtractor.isAllowed("https://x.com/" + "a".repeat(8_200)))
    }

    @Test fun oversizedLinksAreNotFound() {
        assertNull(LinkExtractor.firstLink("https://x.com/" + "a".repeat(8_200)))
    }
}
```

Run both. Expected: compile errors.

- [ ] **Step 2: Implement**

`LinkRules.swift`:

```swift
import Foundation

/// Which links travel between the devices (spec §2): the first http(s) URL in a text, trailing punctuation
/// trimmed, at most 8 KB. Mirrors Kotlin `LinkExtractor` exactly.
public enum LinkRules {
    public static let maxBytes = 8_192
    private static let pattern = try! NSRegularExpression(pattern: #"(?i)\bhttps?://[^\s<>"']+"#)
    private static let trailing: Set<Character> = [".", ",", ";", ":", "!", "?", ")", "]", "}", "'", "\""]

    public static func firstLink(in text: String) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = pattern.firstMatch(in: text, range: range), let found = Range(match.range, in: text) else { return nil }
        var link = String(text[found])
        while let last = link.last, trailing.contains(last) { link.removeLast() }
        return allowedURL(link) == nil ? nil : link
    }

    /// The URL if it's http/https with a host and within the size limit; nil otherwise.
    public static func allowedURL(_ string: String) -> URL? {
        guard string.utf8.count <= maxBytes, let url = URL(string: string),
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = url.host(), !host.isEmpty else { return nil }
        return url
    }
}
```

`LinkExtractor.kt`:

```kotlin
package dev.macdroid.core.extras

import java.net.URI

/** Which links travel between the devices (spec §2). Mirrors Swift `LinkRules` exactly. */
object LinkExtractor {
    const val MAX_BYTES = 8_192
    private val PATTERN = Regex("""(?i)\bhttps?://[^\s<>"']+""")
    private const val TRAILING = ".,;:!?)]}'\""

    fun firstLink(text: String?): String? {
        val found = PATTERN.find(text ?: return null)?.value ?: return null
        val link = found.trimEnd { it in TRAILING }
        return link.takeIf(::isAllowed)
    }

    fun isAllowed(url: String): Boolean {
        if (url.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
    }
}
```

Note: `URI("https://x.com/a?b=c")` parses. Links with characters `URI` rejects (spaces can't occur; unescaped `|` or `^` can) make `isAllowed` false, so they aren't sent. That is acceptable: those are rare.

Run both suites. Expected: PASS.

- [ ] **Step 3: Handoff and commit**

CHANGELOG bullet: "Task 2: mirrored link rules (`LinkRules` / `LinkExtractor`)."

```bash
git add mac/MacDroidCore android/core docs/handoff
git commit -m "feat: link rules on both sides

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 3: Android core — `RingSession` and `MediaTracker`

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/extras/RingSession.kt`, `MediaTracker.kt`
- Test: append to `android/core/src/test/kotlin/dev/macdroid/core/extras/ExtrasTest.kt`

**Interfaces (produces), all in `dev.macdroid.core.extras`:**
- `enum class StopReason { Tapped, VolumeKey, Mac, Timeout, Disconnected }`.
- `class RingSession(timeoutMs: Long = 30_000)`, with:
  - `ringing: Boolean`
  - `start(now: Long): Boolean`: true only when ringing starts
  - `stop(): Boolean`: true only if it was ringing
  - `timedOut(now: Long): Boolean`
- `data class MediaArtwork(hash: String, jpegBase64: String)`.
- `data class MediaSnapshot(packageName, appName, title, artist, durationMs: Long, positionMs: Long, positionAt: Long, playing: Boolean, canPrevious: Boolean, canNext: Boolean, canSeek: Boolean, art: MediaArtwork?, lastActiveAt: Long)`.
- `data class MediaControl(packageName: String, command: MediaCommandKind, positionMs: Long?)`.
- `class MediaTracker(send: (Message) -> Boolean, clock: () -> Long = System::currentTimeMillis)`, with:
  - `connected()` and `disconnected()`
  - `update(sessions: List<MediaSnapshot>)`
  - `handle(message: Message): MediaControl?`
  - the constants `STALE_MS = 300_000`, `JUMP_MS = 2_000`

- [ ] **Step 1: Failing tests**

Append to `ExtrasTest.kt` (with imports for `dev.macdroid.core.protocol.*`):

```kotlin
class RingSessionTest {
    @Test fun startsOnceAndStopsOnce() {
        val ring = RingSession()
        assertTrue(ring.start(0))
        assertFalse(ring.start(10)) // already ringing
        assertTrue(ring.stop())
        assertFalse(ring.stop())
    }

    // Review Focus #3
    @Test fun timesOutAfterThirtySeconds() {
        val ring = RingSession()
        ring.start(1_000)
        assertFalse(ring.timedOut(30_999))
        assertTrue(ring.timedOut(31_000))
    }

    // Review Focus #3
    @Test fun stopReportsOnlyWhenRinging() {
        val ring = RingSession()
        assertFalse(ring.stop()) // e.g. a disconnect while idle sends nothing
        assertFalse(ring.timedOut(999_999))
    }
}

class MediaTrackerTest {
    private val sent = mutableListOf<Message>()
    private var now = 1_000_000L
    private val tracker = MediaTracker({ sent.add(it); true }, clock = { now })
    private val art = MediaArtwork("0123456789abcdef", "/9j/4AAQ")

    private fun song(
        pkg: String = "com.spotify.music", title: String = "Song", playing: Boolean = true, positionMs: Long = 10_000,
        positionAt: Long = now, lastActiveAt: Long = now, artwork: MediaArtwork? = art,
    ) = MediaSnapshot(pkg, "Spotify", title, "Artist", 200_000, positionMs, positionAt, playing,
        canPrevious = true, canNext = true, canSeek = true, art = artwork, lastActiveAt = lastActiveAt)

    private inline fun <reified T : Message> sentOf() = sent.filterIsInstance<T>()

    @Test fun sendsArtThenStateForThePlayingSession() {
        tracker.connected()
        tracker.update(listOf(song(pkg = "a", playing = false), song(pkg = "b")))
        assertTrue(sent.indexOfFirst { it is Message.MediaArt } < sent.indexOfLast { it is Message.MediaState })
        assertEquals("b", sentOf<Message.MediaState>().last().body.packageName)
    }

    @Test fun recentlyPausedIsShownStaleIsNot() {
        tracker.connected()
        tracker.update(listOf(song(playing = false, lastActiveAt = now - 60_000)))
        assertEquals(true, sentOf<Message.MediaState>().last().body.active)
        tracker.update(listOf(song(playing = false, lastActiveAt = now - 400_000)))
        assertEquals(false, sentOf<Message.MediaState>().last().body.active)
    }

    // Review Focus #4
    @Test fun normalPlaybackIsNotResent() {
        tracker.connected()
        tracker.update(listOf(song(positionMs = 10_000, positionAt = now)))
        val count = sentOf<Message.MediaState>().size
        now += 5_000
        tracker.update(listOf(song(positionMs = 15_000, positionAt = now)))
        assertEquals(count, sentOf<Message.MediaState>().size)
    }

    // Review Focus #4
    @Test fun aSeekIsResent() {
        tracker.connected()
        tracker.update(listOf(song(positionMs = 10_000, positionAt = now)))
        val count = sentOf<Message.MediaState>().size
        tracker.update(listOf(song(positionMs = 90_000, positionAt = now)))
        assertEquals(count + 1, sentOf<Message.MediaState>().size)
    }

    @Test fun pauseAndTrackChangesAreResent() {
        tracker.connected()
        tracker.update(listOf(song()))
        tracker.update(listOf(song(playing = false)))
        tracker.update(listOf(song(playing = false, title = "Next")))
        assertEquals(listOf(true, false, false), sentOf<Message.MediaState>().filter { it.body.active }.map { it.body.playing })
        assertEquals("Next", sentOf<Message.MediaState>().last().body.title)
    }

    @Test fun artIsSentOncePerConnection() {
        tracker.connected()
        tracker.update(listOf(song()))
        tracker.update(listOf(song(playing = false)))
        assertEquals(1, sentOf<Message.MediaArt>().size)
        tracker.disconnected()
        tracker.connected() // resends the current state, and its art
        assertEquals(2, sentOf<Message.MediaArt>().size)
        assertEquals(false, sentOf<Message.MediaState>().last().body.playing)
    }

    @Test fun nothingIsSentWhileDisconnectedAndIdleIsSentOnce() {
        tracker.update(listOf(song()))
        assertTrue(sent.isEmpty())
        tracker.connected()
        tracker.update(emptyList())
        tracker.update(emptyList())
        assertEquals(1, sentOf<Message.MediaState>().count { !it.body.active })
    }

    @Test fun commandsTargetTheShownSession() {
        tracker.connected()
        tracker.update(listOf(song(pkg = "com.spotify.music")))
        assertEquals(MediaControl("com.spotify.music", MediaCommandKind.Seek, 60_000),
            tracker.handle(Message.MediaCommand(MediaCommandBody(MediaCommandKind.Seek, 60_000))))
        tracker.update(emptyList())
        assertNull(tracker.handle(Message.MediaCommand(MediaCommandBody(MediaCommandKind.Play))))
    }
}
```

Run `./gradlew :core:test --tests 'dev.macdroid.core.extras.*'`. Expected: compilation fails.

- [ ] **Step 2: Implement**

`RingSession.kt`:

```kotlin
package dev.macdroid.core.extras

enum class StopReason { Tapped, VolumeKey, Mac, Timeout, Disconnected }

/** Ring-my-phone state (spec §3.1): one ring at a time, auto-stop after [timeoutMs]. Not thread-safe; use from one thread. */
class RingSession(private val timeoutMs: Long = 30_000) {
    var ringing: Boolean = false
        private set
    private var startedAt = 0L

    fun start(now: Long): Boolean {
        if (ringing) return false
        ringing = true
        startedAt = now
        return true
    }

    fun stop(): Boolean {
        if (!ringing) return false
        ringing = false
        return true
    }

    fun timedOut(now: Long): Boolean = ringing && now - startedAt >= timeoutMs
}
```

`MediaTracker.kt`:

```kotlin
package dev.macdroid.core.extras

import dev.macdroid.core.protocol.MediaArtBody
import dev.macdroid.core.protocol.MediaCommandKind
import dev.macdroid.core.protocol.MediaStateBody
import dev.macdroid.core.protocol.Message

data class MediaArtwork(val hash: String, val jpegBase64: String)

/** One media session as the app read it. [lastActiveAt] is when it was last playing (epoch ms). */
data class MediaSnapshot(
    val packageName: String,
    val appName: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val positionMs: Long,
    val positionAt: Long,
    val playing: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canSeek: Boolean,
    val art: MediaArtwork?,
    val lastActiveAt: Long,
)

/** A command for the app to apply to [packageName]'s media controller. */
data class MediaControl(val packageName: String, val command: MediaCommandKind, val positionMs: Long?)

/**
 * Chooses which phone media session the Mac shows and sends `media.state` only when something meaningful changes
 * (spec §2, §3.3). Thread-safe.
 */
class MediaTracker(private val send: (Message) -> Boolean, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val STALE_MS = 300_000L
        const val JUMP_MS = 2_000L
    }

    private val lock = Any()
    private var connected = false
    private var latest: List<MediaSnapshot> = emptyList()
    private var shown: MediaSnapshot? = null
    private var lastSent: MediaStateBody? = null
    private val artSent = HashSet<String>()

    fun connected() {
        synchronized(lock) {
            connected = true
            artSent.clear()
            lastSent = null
            publish(force = true)
        }
    }

    fun disconnected() {
        synchronized(lock) { connected = false }
    }

    fun update(sessions: List<MediaSnapshot>) {
        synchronized(lock) {
            latest = sessions
            publish(force = false)
        }
    }

    fun handle(message: Message): MediaControl? {
        if (message !is Message.MediaCommand) return null
        val target = synchronized(lock) { shown } ?: return null
        return MediaControl(target.packageName, message.body.command, message.body.positionMs)
    }

    private fun choose(now: Long): MediaSnapshot? =
        latest.firstOrNull { it.playing }
            ?: latest.filter { now - it.lastActiveAt <= STALE_MS }.maxByOrNull { it.lastActiveAt }

    private fun publish(force: Boolean) {
        val now = clock()
        shown = choose(now)
        if (!connected) return
        val current = shown
        val body = current?.toBody() ?: MediaStateBody(active = false)
        val previous = lastSent
        if (!force && previous != null && !meaningfullyDifferent(previous, body)) return
        if (current?.art != null && artSent.add(current.art.hash)) {
            send(Message.MediaArt(MediaArtBody(current.art.hash, current.art.jpegBase64)))
        }
        if (send(Message.MediaState(body))) lastSent = body
    }

    private fun meaningfullyDifferent(old: MediaStateBody, new: MediaStateBody): Boolean {
        if (old.active != new.active) return true
        if (!new.active) return false
        if (old.packageName != new.packageName || old.title != new.title || old.artist != new.artist ||
            old.durationMs != new.durationMs || old.playing != new.playing || old.artHash != new.artHash ||
            old.canPrevious != new.canPrevious || old.canNext != new.canNext || old.canSeek != new.canSeek
        ) return true
        val projected = (old.positionMs ?: 0) + if (old.playing == true) (new.positionAt ?: 0) - (old.positionAt ?: 0) else 0
        return kotlin.math.abs((new.positionMs ?: 0) - projected) > JUMP_MS
    }

    private fun MediaSnapshot.toBody() = MediaStateBody(
        active = true, packageName = packageName, appName = appName, title = title, artist = artist,
        durationMs = durationMs, positionMs = positionMs, positionAt = positionAt, playing = playing,
        canPrevious = canPrevious, canNext = canNext, canSeek = canSeek, artHash = art?.hash.orEmpty(),
    )
}
```

Run `./gradlew :core:test`. Expected: PASS.

- [ ] **Step 3: Handoff and commit**

- CHANGELOG bullet: "Task 3: Android core `RingSession` and `MediaTracker`."
- ARCHITECTURE: add an Android core row `extras | LinkExtractor.kt, RingSession.kt, MediaTracker.kt`.

```bash
git add android/core docs/handoff
git commit -m "feat(android-core): ring session and media tracker

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Mac core — `PhoneExtras` and `MediaPresentation`

**Ruling (carried into the code):** the Mac projects the playback position from **its own receive time**, not the phone's `positionAt`. The two clocks can differ by seconds, and the receive time is exact for a message that arrives within milliseconds. `positionAt` stays on the wire for the phone's change detection.

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Extras/PhoneExtras.swift`, `mac/MacDroidCore/Sources/MacDroidCore/Presentation/MediaPresentation.swift`
- Test: append to `mac/MacDroidCore/Tests/MacDroidCoreTests/ExtrasTests.swift`

**Interfaces (produces):**
- `PhoneExtrasState(ringing:media:mediaReceivedAt:connected:phoneFeatures:)`, with the computed `supportsRing`, `supportsLinks` and `supportsMedia`, plus the static `.empty`.
- `PhoneExtrasEvent.openLink(URL)`.
- `LinkSendResult`: `.sent`, `.noLink`, `.notConnected`, `.unsupported`.
- `actor PhoneExtras(art: IconCache, now:send:)`, with:
  - `states`, `events`, `state`
  - `attach(to:)`, plus the internal `handle(_:)` and `connectionChanged(_:)`
  - `ring() async`, `stopRing() async`
  - `command(_ kind: MediaCommandKind, positionMs: Int64? = nil) async`
  - `sendLink(in text: String) async -> LinkSendResult`
- `MediaPresentation`:
  - `position(_ state: MediaState, receivedAt: Date, now: Date) -> Int64`
  - `timeLabel(_ ms: Int64) -> String`
  - `subtitle(_ state: MediaState) -> String`

- [ ] **Step 1: Failing tests**

Append to `ExtrasTests.swift`:

```swift
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
```

Run `swift test --filter 'PhoneExtrasTests|MediaPresentationTests'`. Expected: compile errors.

- [ ] **Step 2: Implement**

`PhoneExtras.swift`:

```swift
import Foundation

public struct PhoneExtrasState: Equatable, Sendable {
    public var ringing: Bool
    public var media: MediaState
    /// When the Mac received `media` (the playback position is projected from this, see Task 4 ruling).
    public var mediaReceivedAt: Date?
    public var connected: Bool
    public var phoneFeatures: Set<String>

    public var supportsRing: Bool { connected && phoneFeatures.contains(Feature.ring) }
    public var supportsLinks: Bool { connected && phoneFeatures.contains(Feature.links) }
    public var supportsMedia: Bool { connected && phoneFeatures.contains(Feature.media) }

    public init(ringing: Bool = false, media: MediaState = .idle, mediaReceivedAt: Date? = nil, connected: Bool = false, phoneFeatures: Set<String> = []) {
        self.ringing = ringing
        self.media = media
        self.mediaReceivedAt = mediaReceivedAt
        self.connected = connected
        self.phoneFeatures = phoneFeatures
    }

    public static let empty = PhoneExtrasState()
}

public enum PhoneExtrasEvent: Equatable, Sendable {
    /// The phone asked the Mac to open this (already validated http/https) link.
    case openLink(URL)
}

public enum LinkSendResult: Equatable, Sendable {
    case sent, noLink, notConnected, unsupported
}

/// Ring, links and media controls on the Mac side (spec §4).
public actor PhoneExtras {
    public nonisolated var states: AsyncStream<PhoneExtrasState> { stateBroadcast.stream() }
    public nonisolated var events: AsyncStream<PhoneExtrasEvent> { eventBroadcast.stream() }
    public private(set) var state = PhoneExtrasState.empty

    private let stateBroadcast = Broadcast<PhoneExtrasState>(replayLatest: true, initial: .empty)
    private let eventBroadcast = Broadcast<PhoneExtrasEvent>(replayLatest: false)
    private let art: IconCache
    private let now: @Sendable () -> Date
    private let send: @Sendable (Message) async throws -> Void
    private var tasks: [Task<Void, Never>] = []

    public init(art: IconCache, now: @escaping @Sendable () -> Date = { Date() }, send: @escaping @Sendable (Message) async throws -> Void) {
        self.art = art
        self.now = now
        self.send = send
    }

    public func attach(to hub: ConnectionHub) {
        guard tasks.isEmpty else { return }
        let messages = hub.messages
        let statuses = hub.statusUpdates
        tasks.append(Task { [weak self] in
            for await message in messages { await self?.handle(message) }
        })
        tasks.append(Task { [weak self] in
            for await status in statuses { await self?.connectionChanged(status) }
        })
    }

    func handle(_ message: Message) {
        switch message {
        case .ringState(let body):
            update { $0.ringing = body.ringing }
        case .mediaState(let body):
            let at = now()
            update {
                $0.media = body
                $0.mediaReceivedAt = at
            }
        case .mediaArt(let body):
            art.save(hash: body.hash, pngBase64: body.jpeg) // validated like app icons; the image format doesn't matter
        case .linkOpen(let body):
            if let url = LinkRules.allowedURL(body.url) { eventBroadcast.yield(.openLink(url)) }
        default:
            break
        }
    }

    func connectionChanged(_ status: ConnectionStatus) {
        switch status {
        case .connected(let peer):
            update {
                $0.connected = true
                $0.phoneFeatures = Set(peer.features)
            }
        case .disconnected:
            update {
                $0.connected = false
                $0.ringing = false
                $0.media = .idle
                $0.mediaReceivedAt = nil
            }
        }
    }

    public func ring() async {
        try? await send(.deviceRing(DeviceRing(action: .start)))
    }

    public func stopRing() async {
        try? await send(.deviceRing(DeviceRing(action: .stop)))
    }

    public func command(_ kind: MediaCommandKind, positionMs: Int64? = nil) async {
        try? await send(.mediaCommand(MediaCommand(command: kind, positionMs: positionMs)))
    }

    /// "Open on Phone" (spec §4): the first http(s) link in `text`.
    public func sendLink(in text: String) async -> LinkSendResult {
        guard state.connected else { return .notConnected }
        guard state.phoneFeatures.contains(Feature.links) else { return .unsupported }
        guard let link = LinkRules.firstLink(in: text) else { return .noLink }
        do {
            try await send(.linkOpen(LinkOpen(url: link)))
            return .sent
        } catch {
            return .notConnected
        }
    }

    private func update(_ change: (inout PhoneExtrasState) -> Void) {
        var next = state
        change(&next)
        guard next != state else { return }
        state = next
        stateBroadcast.yield(state)
    }
}
```

`MediaPresentation.swift`:

```swift
import Foundation

/// Text and position for the Now Playing card (spec §4).
public enum MediaPresentation {
    /// The playback position now, projected from when the Mac received the state; capped at the duration.
    public static func position(_ state: MediaState, receivedAt: Date, now: Date) -> Int64 {
        let base = state.positionMs ?? 0
        let elapsed = state.playing == true ? Int64(max(0, now.timeIntervalSince(receivedAt)) * 1_000) : 0
        let duration = state.durationMs ?? 0
        let projected = base + elapsed
        return duration > 0 ? min(projected, duration) : projected
    }

    public static func timeLabel(_ ms: Int64) -> String {
        let total = max(0, ms / 1_000)
        let hours = total / 3_600, minutes = (total % 3_600) / 60, seconds = total % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
            : String(format: "%d:%02d", minutes, seconds)
    }

    public static func subtitle(_ state: MediaState) -> String {
        let artist = (state.artist ?? "").trimmingCharacters(in: .whitespaces)
        let app = state.appName ?? ""
        return artist.isEmpty ? app : "\(artist) · \(app)"
    }
}
```

Run `swift test`. Expected: PASS, with no warnings.

- [ ] **Step 3: Handoff and commit**

- CHANGELOG bullet: "Task 4: Mac core `PhoneExtras` (ring state, media state + art cache, incoming links validated, Open on Phone results) and `MediaPresentation`."
- ARCHITECTURE: add a Mac core row `Extras | Extras/LinkRules.swift, Extras/PhoneExtras.swift (actor)`.
- Add a DECISIONS row: "The Mac projects the media position from its own receive time | Phone and Mac clocks can differ by seconds | Using the phone's positionAt".

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac-core): PhoneExtras and media presentation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 5: Android — the ringer

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/platform/extras/Ringer.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/service/ConnectionService.kt`
- Handoff: CHANGELOG bullet; ARCHITECTURE (Android app `platform/extras/`); DEV-GUIDE (testing the ring on silent)

**Interfaces:**
- Consumes: Task 1 (`Message.DeviceRing`, `RingStateBody`, `RingAction`) and Task 3 (`RingSession`, `StopReason`).
- Produces:
  - `Ringer(context, scope, send)`, with `handle(message)`, `start()` and `stop(reason)`, main thread only
  - `ConnectionService.ACTION_STOP_RING`

This is platform glue with no unit-test harness. The logic it relies on is tested in Task 3, and the build verifies it.

- [ ] **Step 1: `Ringer.kt`**

```kotlin
package dev.macdroid.app.platform.extras

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.app.NotificationCompat
import dev.macdroid.app.R
import dev.macdroid.app.service.ConnectionService
import dev.macdroid.core.extras.RingSession
import dev.macdroid.core.extras.StopReason
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.RingAction
import dev.macdroid.core.protocol.RingStateBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ring my phone (spec §3.1): full alarm volume, looping alarm tone, flashing torch and a Stop notification.
 * Stops on Stop, a volume key (caught by a temporary MediaSession), the Mac, 30 s, or a disconnect.
 * Main thread only.
 */
class Ringer(private val context: Context, private val scope: CoroutineScope, private val send: (Message) -> Boolean) {
    private val session = RingSession()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private var ringtone: Ringtone? = null
    private var savedVolume: Int? = null
    private var torchId: String? = null
    private var volumeKeys: MediaSession? = null
    private var jobs: List<Job> = emptyList()

    fun handle(message: Message) {
        if (message !is Message.DeviceRing) return
        when (message.body.action) {
            RingAction.Start -> start()
            RingAction.Stop -> stop(StopReason.Mac)
        }
    }

    fun start() {
        if (!session.start(System.currentTimeMillis())) return
        savedVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrNull()
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = tone?.let { RingtoneManager.getRingtone(context, it) }?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
        volumeKeys = MediaSession(context, "MacDroidRing").apply {
            setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 1, 0) {
                override fun onAdjustVolume(direction: Int) = stop(StopReason.VolumeKey)
            })
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            isActive = true
        }
        torchId = backTorch()
        jobs = listOf(
            scope.launch { flashTorch() },
            scope.launch {
                delay(TIMEOUT_MS)
                stop(StopReason.Timeout)
            },
        )
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        send(Message.RingState(RingStateBody(true)))
    }

    fun stop(reason: StopReason) {
        if (!session.stop()) return
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        ringtone?.stop()
        ringtone = null
        volumeKeys?.run {
            isActive = false
            release()
        }
        volumeKeys = null
        torchId?.let { id -> runCatching { camera.setTorchMode(id, false) } }
        torchId = null
        savedVolume?.let { volume -> runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) } }
        savedVolume = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        send(Message.RingState(RingStateBody(false)))
    }

    private suspend fun flashTorch() {
        val id = torchId ?: return
        var on = true
        while (scope.isActive) {
            try {
                camera.setTorchMode(id, on)
            } catch (e: CameraAccessException) {
                return // camera busy: ring without the torch
            } catch (e: IllegalArgumentException) {
                return
            }
            on = !on
            delay(FLASH_MS)
        }
    }

    private fun backTorch(): String? = runCatching {
        camera.cameraIdList.firstOrNull { id ->
            val chars = camera.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    private fun notification(): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ring", NotificationManager.IMPORTANCE_HIGH))
        val stop = PendingIntent.getService(
            context, 3,
            Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_STOP_RING),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Ringing — requested by your Mac")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .setContentIntent(stop)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "ring"
        const val NOTIFICATION_ID = 40
        const val TIMEOUT_MS = 30_000L
        const val FLASH_MS = 250L
    }
}
```

- [ ] **Step 2: Wire it into `ConnectionService`**

- Add `const val ACTION_STOP_RING = "dev.macdroid.app.STOP_RING"` to the companion and `private var ringer: Ringer? = null` to the class.
- At the end of the `running` path in `onCreate`:

```kotlin
        val ringer = Ringer(this, scope) { manager.send(it) }.also { this.ringer = it }
        scope.launch { manager.incoming.collect { ringer.handle(it) } }
        scope.launch {
            manager.state.collect { if (it !is ConnectionState.Connected) ringer.stop(StopReason.Disconnected) }
        }
```

- In `onStartCommand`'s `when`: `ACTION_STOP_RING -> ringer?.stop(StopReason.Tapped)`.
- In `onDestroy`, before `scope.cancel()`: `ringer?.stop(StopReason.Disconnected)`.
- Imports: `dev.macdroid.app.platform.extras.Ringer`, `dev.macdroid.core.extras.StopReason`.

(`scope` uses `Dispatchers.Main.immediate`, so `Ringer` runs on the main thread as required.)

- [ ] **Step 3: Gates, handoff, commit**

Run `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`. Expected: PASS, with no warnings.

- CHANGELOG bullet: "Task 5: Android ringer (alarm tone at full alarm volume, torch flash, volume-key MediaSession, Stop notification, 30 s, stops on disconnect)."
- ARCHITECTURE: `platform/extras/Ringer`.
- DEV-GUIDE: "To try the ring: Mac popover bell. It plays even on silent if Do Not Disturb allows alarms (the default)."

```bash
git add android docs/handoff
git commit -m "feat(android): ring my phone

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Android — "Open on Mac" and incoming links

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/ui/presentation/OpenOnMacDecision.kt` (+ test `android/app/src/test/java/dev/macdroid/app/OpenOnMacDecisionTest.kt`)
- Create: `android/app/src/main/java/dev/macdroid/app/ui/share/OpenOnMacActivity.kt`
- Create: `android/app/src/main/java/dev/macdroid/app/platform/extras/LinkNotifications.kt`
- Modify: `AndroidManifest.xml`, `ConnectionService.kt`
- Handoff: CHANGELOG bullet; ARCHITECTURE

**Interfaces (produces):** `OpenOnMacDecision.decide(text: String?, state: ConnectionState): Decision(url: String?, toast: String)` and `LinkNotifications.show(context, url)`.

- [ ] **Step 1: Failing test**

```kotlin
package dev.macdroid.app

import dev.macdroid.app.ui.presentation.OpenOnMacDecision
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.protocol.Features
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenOnMacDecisionTest {
    private val connected = ConnectionState.Connected("Mac", Features.SUPPORTED.toSet())

    @Test fun sendsTheFirstLink() {
        assertEquals(OpenOnMacDecision.Decision("https://x.com/a", "Opening on your Mac"),
            OpenOnMacDecision.decide("look (https://x.com/a).", connected))
    }

    @Test fun explainsWhyNot() {
        assertEquals(OpenOnMacDecision.Decision(null, "No link to open"), OpenOnMacDecision.decide("just words", connected))
        assertEquals(OpenOnMacDecision.Decision(null, "Not connected to your Mac"), OpenOnMacDecision.decide("https://x.com", ConnectionState.Connecting))
        assertEquals(OpenOnMacDecision.Decision(null, "Update MacDroid on your Mac to use this"),
            OpenOnMacDecision.decide("https://x.com", ConnectionState.Connected("Mac", setOf("files"))))
    }
}
```

Run `./gradlew :app:testDebugUnitTest --tests 'dev.macdroid.app.OpenOnMacDecisionTest'`. Expected: compilation fails.

- [ ] **Step 2: Implement the decision, the activity and the notifications**

```kotlin
package dev.macdroid.app.ui.presentation

import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.extras.LinkExtractor
import dev.macdroid.core.protocol.Features

/** "Share → Open on Mac" (spec §3.2): what to send, and what to tell the user. */
object OpenOnMacDecision {
    data class Decision(val url: String?, val toast: String)

    fun decide(text: String?, state: ConnectionState): Decision {
        val link = LinkExtractor.firstLink(text) ?: return Decision(null, "No link to open")
        return when {
            state !is ConnectionState.Connected -> Decision(null, "Not connected to your Mac")
            !state.macSupports(Features.LINKS) -> Decision(null, "Update MacDroid on your Mac to use this")
            else -> Decision(link, "Opening on your Mac")
        }
    }
}
```

```kotlin
package dev.macdroid.app.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.macdroid.app.graph
import dev.macdroid.app.ui.presentation.OpenOnMacDecision
import dev.macdroid.core.protocol.LinkOpenBody
import dev.macdroid.core.protocol.Message

/** "Share → Open on Mac": sends the first link in the shared text; no UI of its own. */
class OpenOnMacActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val decision = OpenOnMacDecision.decide(text, graph.connectionManager.state.value)
        val toast = when {
            decision.url == null -> decision.toast
            graph.connectionManager.send(Message.LinkOpen(LinkOpenBody(decision.url))) -> decision.toast
            else -> "Not connected to your Mac"
        }
        Toast.makeText(applicationContext, toast, Toast.LENGTH_SHORT).show()
        finish()
    }
}
```

```kotlin
package dev.macdroid.app.platform.extras

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.macdroid.app.R

/** A link from the Mac becomes a tap-to-open notification: Android forbids opening it from the background (spec §3.2). */
object LinkNotifications {
    private const val CHANNEL_ID = "links"

    fun show(context: Context, url: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Links", NotificationManager.IMPORTANCE_HIGH))
        val uri = Uri.parse(url)
        val id = url.hashCode()
        val open = PendingIntent.getActivity(
            context, id, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Link from your Mac")
            .setContentText(uri.host ?: url)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }
}
```

In `ConnectionService.onCreate`, next to the ringer's collector:

```kotlin
        scope.launch {
            manager.incoming.collect { message ->
                if (message is Message.LinkOpen && LinkExtractor.isAllowed(message.body.url)) {
                    LinkNotifications.show(this@ConnectionService, message.body.url)
                }
            }
        }
```

Manifest, inside `<application>`:

```xml
        <activity
            android:name=".ui.share.OpenOnMacActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:taskAffinity=""
            android:label="Open on Mac"
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>
```

- [ ] **Step 3: Gates, handoff, commit**

Run the Android gates. Expected: PASS.

CHANGELOG bullet: "Task 6: Android 'Open on Mac' share target and tap-to-open link notifications."

```bash
git add android docs/handoff
git commit -m "feat(android): open links on the Mac and from the Mac

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Android — media sessions to `MediaTracker`

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/platform/extras/MediaWatcher.kt`
- Modify: `MacDroidApp.kt` (`mediaWatcher`) and `platform/notifications/MacDroidNotificationListener.kt` (refresh on connect)
- Handoff: CHANGELOG bullet; ARCHITECTURE

**Interfaces:**
- Consumes: Task 3 (`MediaTracker`, `MediaSnapshot`, `MediaArtwork`, `MediaControl`).
- Produces: `AppGraph.mediaWatcher: MediaWatcher`, with `start()` and `refresh()`.

- [ ] **Step 1: `MediaWatcher.kt`**

```kotlin
package dev.macdroid.app.platform.extras

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.macdroid.app.platform.notifications.MacDroidNotificationListener
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.extras.MediaArtwork
import dev.macdroid.core.extras.MediaControl
import dev.macdroid.core.extras.MediaSnapshot
import dev.macdroid.core.extras.MediaTracker
import dev.macdroid.core.protocol.Features
import dev.macdroid.core.protocol.MediaCommandKind
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches the phone's media sessions (available through MacDroid's notification access) and feeds [MediaTracker]
 * (spec §3.3). Session callbacks run on the main thread; artwork is encoded off it.
 */
class MediaWatcher(private val context: Context, private val connection: ConnectionManager, private val scope: CoroutineScope) {
    val tracker = MediaTracker(connection::send)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MacDroidNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val controllers = LinkedHashMap<String, Pair<MediaController, MediaController.Callback>>() // by package
    private val lastActive = HashMap<String, Long>()
    private val artCache = HashMap<String, MediaArtwork?>()
    private var watching = false
    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { rebind(it.orEmpty()) }

    fun start() {
        scope.launch {
            connection.state.map { it.macSupports(Features.MEDIA) }.distinctUntilChanged().collect { ready ->
                if (ready) tracker.connected() else tracker.disconnected()
            }
        }
        scope.launch {
            connection.incoming.collect { message ->
                val control = tracker.handle(message) ?: return@collect
                withContext(Dispatchers.Main) { apply(control) }
            }
        }
        main.post { refresh() }
    }

    /** (Re)reads the sessions; call when notification access may have changed. Main thread. */
    fun refresh() {
        try {
            if (!watching) {
                sessions.addOnActiveSessionsChangedListener(changed, listener, main)
                watching = true
            }
            rebind(sessions.getActiveSessions(listener))
        } catch (e: SecurityException) {
            rebind(emptyList()) // no notification access: nothing to show (spec §3.3)
        }
    }

    private fun rebind(list: List<MediaController>) {
        val present = list.associateBy { it.packageName }
        for ((pkg, pair) in controllers.toMap()) {
            if (pkg !in present) {
                pair.first.unregisterCallback(pair.second)
                controllers.remove(pkg)
            }
        }
        for ((pkg, controller) in present) {
            if (pkg in controllers) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onSessionDestroyed() {
                    controllers.remove(pkg)?.let { it.first.unregisterCallback(it.second) }
                    publish()
                }
            }
            controller.registerCallback(callback, main)
            controllers[pkg] = controller to callback
        }
        publish()
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val raw = controllers.values.mapNotNull { (controller, _) -> read(controller, now) }
        scope.launch(Dispatchers.Default) {
            tracker.update(raw.map { (snapshot, bitmap, key) -> snapshot.copy(art = artwork(key, bitmap)) })
        }
    }

    /** Reads one controller on the main thread; artwork is encoded later, off it. */
    private fun read(controller: MediaController, now: Long): Triple<MediaSnapshot, Bitmap?, String>? {
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState ?: return null
        val playing = state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING
        val pkg = controller.packageName
        if (playing) lastActive[pkg] = now
        val elapsed = if (playing) (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed else 0f
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val snapshot = MediaSnapshot(
            packageName = pkg,
            appName = appName(pkg),
            title = title,
            artist = artist,
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            positionMs = (state.position + elapsed.toLong()).coerceAtLeast(0),
            positionAt = now,
            playing = playing,
            canPrevious = (state.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            canNext = (state.actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            canSeek = (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L,
            art = null,
            lastActiveAt = lastActive[pkg] ?: 0L,
        )
        return Triple(snapshot, bitmap, "$pkg|$title|$artist")
    }

    private fun artwork(key: String, bitmap: Bitmap?): MediaArtwork? = synchronized(artCache) {
        artCache.getOrPut(key) { bitmap?.let(::encode) }
    }

    private fun encode(bitmap: Bitmap): MediaArtwork? = runCatching {
        val scale = minOf(1f, ART_PX.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).take(8).joinToString("") { "%02x".format(it) }
        MediaArtwork(hash, Base64.getEncoder().encodeToString(jpeg))
    }.getOrNull()

    private fun apply(control: MediaControl) {
        val controls = controllers[control.packageName]?.first?.transportControls ?: return
        when (control.command) {
            MediaCommandKind.Play -> controls.play()
            MediaCommandKind.Pause -> controls.pause()
            MediaCommandKind.Next -> controls.skipToNext()
            MediaCommandKind.Previous -> controls.skipToPrevious()
            MediaCommandKind.Seek -> control.positionMs?.let(controls::seekTo)
        }
    }

    private fun appName(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        const val ART_PX = 300
    }
}
```

(The parentheses around `state.actions and …` matter: in Kotlin, `!=` binds tighter than the infix `and`.)

- [ ] **Step 2: Wire it up**

`MacDroidApp.kt` (`AppGraph`), after `clipboardBridge`:

```kotlin
    /** Media controls for the Mac's Now Playing card (spec §3.3). */
    val mediaWatcher = MediaWatcher(context, connectionManager, appScope).also { it.start() }
```

`MacDroidNotificationListener.onListenerConnected`: also call `graph.mediaWatcher.refresh()`:

```kotlin
    override fun onListenerConnected() {
        bridge.listenerConnected(this)
        graph.mediaWatcher.refresh()
    }
```

- [ ] **Step 3: Gates, handoff, commit**

Run the Android gates. Expected: PASS, with no warnings.

CHANGELOG bullet: "Task 7: Android `MediaWatcher` (media sessions via the notification listener, art encoded off the main thread, commands applied to the shown app)."

```bash
git add android docs/handoff
git commit -m "feat(android): media sessions for the Mac's Now Playing card

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Mac app — Services "Open on Phone", ring button, Now Playing card

**Files:**
- Create: `mac/MacDroid/Extras/ServicesProvider.swift`, `mac/MacDroid/Views/Popover/NowPlayingCard.swift`
- Modify: `mac/project.yml` (`NSServices`), `AppModel.swift`, `MacDroidApp.swift`, `AppDelegate.swift`, `Views/MenuBarPopover.swift`, `Views/Phone/PairedDeviceView.swift`
- Handoff: CHANGELOG bullet; ARCHITECTURE; DEV-GUIDE (Services may need `/System/Library/CoreServices/pbs -update` or a log-out)

**Interfaces:**
- Consumes: Task 4 (`PhoneExtras`, `PhoneExtrasState`, `PhoneExtrasEvent`, `LinkSendResult`, `MediaPresentation`) and Plan 3's `NotificationRouter`.
- Produces:
  - `AppModel.extras: PhoneExtrasState`
  - `ringPhone()` and `stopRingingPhone()`
  - `mediaCommand(_:positionMs:)` and `mediaArtURL(_:) -> URL?`
  - `sendLinkToPhone(_ text: String)`

- [ ] **Step 1: `NSServices` in `project.yml`**

Under `targets.MacDroid.info.properties`, add:

```yaml
        NSServices:
          - NSMenuItem:
              default: Open on Phone
            NSMessage: openOnPhone
            NSPortName: MacDroid
            NSSendTypes:
              - public.utf8-plain-text
              - public.url
            NSRequiredContext: {}
```

- [ ] **Step 2: `ServicesProvider.swift`**

```swift
import AppKit

/// Right-click → Services → "Open on Phone" (spec §4). macOS calls `openOnPhone` with the selection.
@MainActor
final class ServicesProvider: NSObject {
    private let model: AppModel

    init(model: AppModel) { self.model = model }

    @objc func openOnPhone(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let text = pasteboard.string(forType: .string) ?? pasteboard.string(forType: .URL) ?? ""
        model.sendLinkToPhone(text)
    }
}
```

In `AppDelegate`: add `static var servicesProvider: NSObject?`, and at the start of `applicationDidFinishLaunching`:

```swift
        if let provider = Self.servicesProvider {
            NSApp.servicesProvider = provider
            NSUpdateDynamicServices()
        }
```

In `MacDroidApp.init`, after `model.start()`: `AppDelegate.servicesProvider = ServicesProvider(model: model)`.

- [ ] **Step 3: `AppModel`**

Add properties:

```swift
    private(set) var extras: PhoneExtrasState = .empty
    @ObservationIgnored private let phoneExtras: PhoneExtras
    @ObservationIgnored private let mediaArt: IconCache
```

In `init()`, after `phoneNotifications` is created:

```swift
        let mediaArt = IconCache(directory: URL.applicationSupportDirectory.appending(path: "MediaArt", directoryHint: .isDirectory))
        self.mediaArt = mediaArt
        phoneExtras = PhoneExtras(art: mediaArt, send: { message in try await hub.send(message) })
```

In `start()`:

```swift
        tasks.append(Task { [phoneExtras, hub] in await phoneExtras.attach(to: hub) })
        tasks.append(Task { [weak self, phoneExtras] in
            for await state in phoneExtras.states { self?.extras = state }
        })
        tasks.append(Task { [phoneExtras] in
            for await event in phoneExtras.events {
                if case .openLink(let url) = event { await MainActor.run { _ = NSWorkspace.shared.open(url) } }
            }
        })
```

Methods:

```swift
    // MARK: Small wins (spec §4)

    func ringPhone() { Task { await phoneExtras.ring() } }
    func stopRingingPhone() { Task { await phoneExtras.stopRing() } }
    func mediaCommand(_ kind: MediaCommandKind, positionMs: Int64? = nil) { Task { await phoneExtras.command(kind, positionMs: positionMs) } }
    func mediaArtURL(_ hash: String?) -> URL? { hash.flatMap(mediaArt.url(for:)) }

    /// Services → "Open on Phone".
    func sendLinkToPhone(_ text: String) {
        Task {
            switch await phoneExtras.sendLink(in: text) {
            case .sent: break
            case .noLink: await notifyInfo("No link in the selection")
            case .notConnected: await notifyInfo("Your phone isn't connected")
            case .unsupported: await notifyInfo("Update MacDroid on your phone to use this")
            }
        }
    }

    private func notifyInfo(_ text: String) async {
        guard await router.ensureAuthorized() else { return }
        let content = UNMutableNotificationContent()
        content.title = "MacDroid"
        content.body = text
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "dev.macdroid.info", content: content, trigger: nil))
    }
```

(Add `import UserNotifications` to `AppModel.swift` if it's missing.)

- [ ] **Step 4: `NowPlayingCard.swift`**

```swift
import MacDroidCore
import SwiftUI

/// The phone's media in the popover (spec §4): artwork, title, artist · app, a seek bar and transport buttons.
struct NowPlayingCard: View {
    let model: AppModel
    @State private var scrubbing: Double?

    var body: some View {
        let extras = model.extras
        let media = extras.media
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                artwork(model.mediaArtURL(media.artHash))
                VStack(alignment: .leading, spacing: 2) {
                    Text(media.title ?? "").font(.callout.weight(.semibold)).lineLimit(1)
                    Text(MediaPresentation.subtitle(media)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let duration = Double(media.durationMs ?? 0)
                let position = Double(MediaPresentation.position(media, receivedAt: extras.mediaReceivedAt ?? context.date, now: context.date))
                VStack(spacing: 2) {
                    Slider(
                        value: Binding(get: { scrubbing ?? position }, set: { scrubbing = $0 }),
                        in: 0...max(duration, 1),
                        onEditingChanged: { editing in
                            guard !editing, let target = scrubbing else { return }
                            model.mediaCommand(.seek, positionMs: Int64(target))
                            scrubbing = nil
                        }
                    )
                    .controlSize(.mini)
                    .disabled(!(media.canSeek ?? false) || duration <= 0)
                    HStack {
                        Text(MediaPresentation.timeLabel(Int64(scrubbing ?? position)))
                        Spacer()
                        Text(duration > 0 ? MediaPresentation.timeLabel(Int64(duration)) : "")
                    }
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 26) {
                Spacer()
                control("backward.fill", enabled: media.canPrevious ?? false) { model.mediaCommand(.previous) }
                control(media.playing == true ? "pause.fill" : "play.fill", enabled: true) {
                    model.mediaCommand(media.playing == true ? .pause : .play)
                }
                control("forward.fill", enabled: media.canNext ?? false) { model.mediaCommand(.next) }
                Spacer()
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.secondary.opacity(0.1)))
    }

    private func artwork(_ url: URL?) -> some View {
        Group {
            if let url, let image = NSImage(contentsOf: url) {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "music.note").font(.system(size: 18)).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).background(.quaternary)
            }
        }
        .frame(width: 44, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func control(_ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).font(.system(size: 16)) }
            .buttonStyle(.borderless)
            .disabled(!enabled)
    }
}
```

- [ ] **Step 5: Popover and Phone page**

In `MenuBarPopover.swift`'s `deviceCard`, replace `Spacer(minLength: 0)` with:

```swift
            Spacer(minLength: 0)
            if model.extras.supportsRing {
                let ringing = model.extras.ringing
                Button { ringing ? model.stopRingingPhone() : model.ringPhone() } label: {
                    Image(systemName: ringing ? "bell.slash.fill" : "bell.and.waves.left.and.right")
                        .font(.system(size: 15))
                }
                .buttonStyle(.borderless)
                .foregroundStyle(ringing ? Color.red : Color.secondary)
                .help(ringing ? "Stop ringing" : "Ring your phone")
            }
```

In `body`, after `tiles`:

```swift
                if model.extras.supportsMedia && model.extras.media.active { NowPlayingCard(model: model) }
```

In `PairedDeviceView.swift`'s button `Section`, before "Pair a Different Phone…":

```swift
                    if model.extras.supportsRing {
                        Button(model.extras.ringing ? "Stop Ringing" : "Ring Phone") {
                            model.extras.ringing ? model.stopRingingPhone() : model.ringPhone()
                        }
                    }
```

and in the "Details" section, after the Notifications row:

```swift
                if model.notifications.phoneAccess == false {
                    Text("Media controls need notification access on your phone.").font(.callout).foregroundStyle(.secondary)
                }
```

- [ ] **Step 6: Build, handoff, commit**

Run the Mac app build. Expected: BUILD SUCCEEDED, with no warnings. If Swift 6 flags the `@objc` Services method's pointer parameter, keep the signature (AppKit requires it) and mark the method `nonisolated` with `MainActor.assumeIsolated { … }` inside.

- CHANGELOG bullet: "Task 8: Mac Services 'Open on Phone', ring button (popover + Phone page), Now Playing card with seek, incoming links open in the browser."
- DEV-GUIDE: "Services → Open on Phone: a fresh dev build may need `/System/Library/CoreServices/pbs -update`, or a log-out/in, before it appears."

```bash
git add mac docs/handoff
git commit -m "feat(mac): Open on Phone, ring button and Now Playing card

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Cross-platform — ring, links and media through the real server and client

**Files:** `mac/MacDroidCore/Sources/MacDroidDevServer/main.swift`, `android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt`, `scripts/cross-platform-test.sh`

- [ ] **Step 1: Dev server**

After the clipboard setup:

```swift
let extras = PhoneExtras(
    art: IconCache(directory: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "macdroid-devserver-art-\(UUID().uuidString)")),
    send: { try await hub.send($0) }
)
await extras.attach(to: hub)

let extrasPrinter = Task {
    var commanded = false
    var lastRinging = false
    for await state in extras.states {
        if state.ringing != lastRinging {
            lastRinging = state.ringing
            print("RING state ringing=\(state.ringing)")
        }
        if state.media.active, !commanded {
            commanded = true
            print("MEDIA state title=\(state.media.title ?? "") playing=\(state.media.playing ?? false) art=\(state.media.artHash ?? "")")
            await extras.command(.pause)
        }
    }
}

let linkPrinter = Task {
    for await event in extras.events {
        if case .openLink(let url) = event { print("LINK open \(url.absoluteString)") }
    }
}
```

In `statusPrinter`, inside the `if !sentClipboard` block's `Task`, after the clipboard `localChange`:

```swift
                    await extras.ring()
                    _ = await extras.sendLink(in: "see https://example.com/mac")
```

Cancel `extrasPrinter` and `linkPrinter` at the end.

- [ ] **Step 2: Kotlin side**

In `CrossPlatformTest`, after `exchangeClipboard(...)`, call `exchangeSmallWins(manager, received)`:

```kotlin
    /** The Mac rings and sends a link; the phone answers, sends a link back, and shares its media. */
    private suspend fun exchangeSmallWins(manager: ConnectionManager, received: List<Message>) {
        withTimeout(10_000) { while (received.none { it is Message.DeviceRing }) delay(50) }
        assertEquals(RingAction.Start, received.filterIsInstance<Message.DeviceRing>().first().body.action)
        assertTrue(manager.send(Message.RingState(RingStateBody(true))))
        withTimeout(10_000) { while (received.none { it is Message.LinkOpen }) delay(50) }
        assertEquals("https://example.com/mac", received.filterIsInstance<Message.LinkOpen>().first().body.url)
        assertTrue(manager.send(Message.LinkOpen(LinkOpenBody("https://example.com/phone"))))
        val tracker = MediaTracker(manager::send)
        tracker.connected()
        tracker.update(listOf(MediaSnapshot("com.example.music", "Music", "Song", "Artist", 200_000, 1_000, System.currentTimeMillis(),
            playing = true, canPrevious = true, canNext = true, canSeek = true,
            art = MediaArtwork("0123456789abcdef", "/9j/4AAQ"), lastActiveAt = System.currentTimeMillis())))
        withTimeout(10_000) { while (received.none { it is Message.MediaCommand }) delay(50) }
        assertEquals(MediaControl("com.example.music", MediaCommandKind.Pause, null),
            tracker.handle(received.filterIsInstance<Message.MediaCommand>().first()))
    }
```

(Imports: `dev.macdroid.core.extras.*` and `dev.macdroid.core.protocol.*` as needed.)

- [ ] **Step 3: Script assertions** (before the `cmp` lines)

```bash
grep -q 'RING state ringing=true' "$LOG" || { echo "the Mac never saw the phone ringing"; cat "$LOG"; exit 1; }
grep -q 'LINK open https://example.com/phone' "$LOG" || { echo "the Mac never got the phone's link"; cat "$LOG"; exit 1; }
grep -q 'MEDIA state title=Song playing=true art=0123456789abcdef' "$LOG" || { echo "the Mac never got the media state"; cat "$LOG"; exit 1; }
```

- [ ] **Step 4: Run, handoff, commit**

Run `./scripts/cross-platform-test.sh`. Expected: `cross-platform test passed`.

- CHANGELOG bullet: "Task 9: the cross-platform run covers ring, links both ways, and media state → command."
- DEV-GUIDE: update the script description.

```bash
git add mac/MacDroidCore android/core scripts docs/handoff
git commit -m "test: ring, links and media in the cross-platform run

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Final gates, handoff and the owner's checklist

- [ ] **Step 1:** Run every gate (`swift test`, the Mac app build, Android gates with `--rerun-tasks`, the cross-platform run). Expected: all pass, with no warnings.

- [ ] **Step 2:** Create `docs/superpowers/plans/2026-10-13-macdroid-small-wins-followups.md`:

```markdown
# Small wins (Plan 5) — follow-ups

## Owner's hands-on checklist
Install the new builds. Media controls need the notification access you already granted.
- [ ] Phone on silent: Mac popover bell → loud ring + torch flashing + "Ringing — requested by your Mac".
- [ ] Each stop works: notification Stop; a volume button; the popover's red Stop; wait 30 s. Alarm volume returns to what it was.
- [ ] Turn Wi-Fi off while ringing → it stops.
- [ ] Chrome on the phone → Share → "Open on Mac" → opens in the Mac's browser. Share plain text without a link → "No link to open".
- [ ] Mac: select a link in Notes or Safari → right-click → Services → "Open on Phone" → a "Link from your Mac" notification on the phone; tap → opens.
- [ ] Spotify/YouTube Music playing on the phone → Now Playing card in the popover: art, title, artist · app; play/pause, next, previous work; drag the seek bar.
- [ ] Pause and leave it for 5+ minutes → the card disappears.
- [ ] Phone page: "Ring Phone" / "Stop Ringing".

## Deferred / rulings
- Task 4: the Mac projects the media position from its own receive time (clock skew); `positionAt` is still sent.
```

- [ ] **Step 3: Handoff**
- CHANGELOG: mark Plan 5 as done on `feat/small-wins`.
- STATUS: Branches and In flight.
- ROADMAP: small wins done, pending the owner's check.
- README: round table, add the row `| Plan 5 | Small wins: ring, links, media controls | Done on feat/small-wins; owner check pending |`.

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "docs: Plan 5 done — owner checklist and handoff

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
