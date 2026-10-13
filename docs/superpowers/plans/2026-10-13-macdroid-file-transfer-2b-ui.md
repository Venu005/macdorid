# MacDroid File Transfer — Plan 2b: UI and Platform Glue

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make file transfer usable on both devices, on top of Plan 2a's engine:
- **Mac:** a Files page, the design-A popover, Settings → Received files, and notifications.
- **Android:** MediaStore, SAF and ask-each-time saving; a Files tab, a Home Send button, the share target, settings, a setup step, and notifications.

**Architecture:**
- **Pure logic and tests stay in the cores:** `MacDroidCore` gets `TransferPresentation`; the Android app's `ui/presentation` and `platform` packages hold pure helpers with JVM tests.
- **Views stay thin.**
- **Two small engine changes come first.** Both engines must show pending incoming offers as `offered`/`Offered` records, and both must cancel a pending "where to save" question when the other side cancels.
- **Platform code** implements the seams that 2a defined:
  - Mac: `ReceiveLocationProvider`, `SourceAccess`.
  - Android: `SaveTarget`, `PartFile`, `SourceOpener`.

**Tech Stack:**
- **Mac:** Swift 6.4, SwiftUI (macOS 14+), AppKit panels, UserNotifications, XcodeGen.
- **Android:** Kotlin 2.4.21, Compose Material 3 (BOM 2026.09.00), Activity Result APIs (Photo Picker, OpenMultipleDocuments, OpenDocumentTree, CreateDocument), MediaStore, DocumentsContract.

**Spec:** `docs/superpowers/specs/2026-10-13-macdroid-file-transfer-design.md` (§3.2 Mac app, §4.2 Android app, §5 errors). Plan 2a's engine (`docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2a-engine.md`) is done on the same branch.

## Global Constraints

- **Branch:** `feat/file-transfer` (continue on it; 2a is already there). Don't merge: merging needs the owner's hands-on check.
- **Handoff rule (from `/CLAUDE.md`):** every task's commit updates `docs/handoff/`.
  - Task 1 creates a CHANGELOG entry at the top headed `## 2026-10-13 — Plan 2b: file transfer UI (in progress on feat/file-transfer)`.
  - Each later task adds one bullet under it, plus the doc edits its "Handoff" step names.
- **Versions:** Mac `CFBundleShortVersionString` "0.3.0", `CFBundleVersion` "3". Android `versionName` "0.3.0", `versionCode` 3.
- **Mac entitlements:** add `com.apple.security.files.user-selected.read-write`, `com.apple.security.files.downloads.read-write` and `com.apple.security.files.bookmarks.app-scope` (all `true`), alongside the existing sandbox and network keys.
- **Mac default save folder:** `~/Downloads/MacDroid`. A chosen folder is stored as a security-scoped bookmark. If it can't be resolved, show the banner "MacDroid can't reach the folder you chose for received files. Choose it again in Settings." and ask where to save each file until it's fixed.
- **Android default save folder:** `Download/MacDroid` via MediaStore Downloads. A chosen folder is a SAF tree with a persisted read/write grant. If the grant is gone, ask where to save each file and show the banner "Can't save to your chosen folder — choose it again in Settings." No `MANAGE_EXTERNAL_STORAGE`, and no storage permission.
- **Share target:** `ACTION_SEND` and `ACTION_SEND_MULTIPLE` for `*/*`.
  - **Connected:** hand the URIs to `ConnectionService` with a read grant and toast "Sending N files to your Mac" ("Sending 1 file to your Mac" for one). No UI.
  - **Not paired or not connected:** toast "Connect to your Mac first" and open the app.
  - Copy to cache only when no persistable grant is offered **and** the file is at most 100 MB.
- **Copy (Mac):**
  - Files page drop zone: "Drop files here to send to <phone>"; when offline, "Phone not connected".
  - Buttons: "Send Files…", "Show in Finder", "Open", "Retry".
  - Notification: "Received <name>" with the body "From <phone>", and the actions "Show in Finder" and "Open".
  - Popover tiles: "Send Files" with "or drop here", and "Open MacDroid" with "⌘O".
  - Drop overlay: "Drop to send to <phone>" plus "N files · size".
- **Copy (Android):**
  - Card: "Send to Mac" with the buttons "Photos" and "Files".
  - Section headers: "In progress", "Recent".
  - Notifications: "Received <name> from Mac" with an "Open" action; the progress notification has a "Cancel" action.
  - Setup step: "Where should files from your Mac go?", with the buttons "Use Download/MacDroid" and "Choose a folder".
- **Status text** is the same on both sides, with "phone"/"Mac" swapped (`TransferPresentation`):
  - "Waiting for your phone|Mac", "Waiting for you to choose where to save", "Paused — waiting for connection", "Checking…".
  - "Sent · <size>", "Received · <size>".
  - "Cancelled on your Mac|phone".
  - Failures: "Transfer failed — file was corrupted in transit", "The original file is no longer available", "Expired — not finished within 24 hours".
- **Build gates** (all pass with no warnings before each commit that touches that side):
  - `cd mac/MacDroidCore && swift test`
  - `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build`
  - `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
- **Swift paths:** `url.path(percentEncoded: false)`, never `url.path`.
- **No automated tests drive the apps' UI.** The app targets have no UI test harness. Every rule that can be tested lives in a pure type with unit tests, and the views are verified by building plus the owner's hands-on checklist (Task 12).

## Review Focus

1. **The phone cancels while the Mac's save panel is open.** The panel must close and the row must read "Cancelled on your phone"; no question is left hanging. → Task 1 `peerCancelWhileDecidingCancelsTheQuestion`.
2. **The share sheet is used while the phone isn't connected.** The user must be told "Connect to your Mac first" and taken to the app; the share must never be silently dropped. → Task 7 `ShareDecisionTest.notConnectedAsksToConnectFirst`.
3. **The chosen Android folder's permission was revoked** (or the folder deleted). Transfers must still work by asking where to save, and the banner must appear. → Task 7 `SaveModeTest.revokedFolderFallsBackToAskWithProblem`.
4. **A large shared video without persistable access** must be read directly, not copied (copying would double the storage used). Small files are copied. → Task 7 `ShareSourceTest`.
5. **A relaunch while an incoming offer was still waiting for a decision** must not loop or strand the transfer. The undecided offer is forgotten, and the sender's re-offer starts fresh. → Task 1 `relaunchForgetsUndecidedOffers`; Task 3 `restartForgetsUndecidedOffers`.

---

## File Structure

```
mac/
  project.yml                                                (modify: entitlements, version 0.3.0/3)
  MacDroidCore/Sources/MacDroidCore/Transfer/TransferManager.swift      (modify: IncomingOffer, visible + cancellable decisions)
  MacDroidCore/Sources/MacDroidCore/Presentation/TransferPresentation.swift   (new, pure)
  MacDroidCore/Sources/MacDroidDevServer/main.swift          (modify: new provider signature)
  MacDroidCore/Tests/MacDroidCoreTests/TestSupport.swift      (modify: FixedLocation signature, GateLocation)
  MacDroidCore/Tests/MacDroidCoreTests/TransferReceiveTests.swift       (modify: decision tests)
  MacDroidCore/Tests/MacDroidCoreTests/TransferPresentationTests.swift  (new)
  MacDroid/
    AppModel.swift                     (modify: TransferManager wiring + file actions)
    Files/FilesSettings.swift          (new: folder bookmark, ask-each-time, save panel; MacReceiveLocation)
    Files/SecurityScopedSourceAccess.swift  (new)
    Files/ReceivedNotifier.swift       (new: UserNotifications)
    SidebarSection.swift               (modify: Files is live)
    Views/MainWindow.swift             (modify: FilesView, GeneralSettingsView(files:))
    Views/Files/FilesView.swift        (new: drop zone, In progress, Recent)
    Views/Files/TransferRow.swift      (new)
    Views/Settings/GeneralSettingsView.swift   (modify: Received files section)
    Views/MenuBarPopover.swift         (rewrite: design A)
    Views/Popover/BatteryRing.swift  PopoverTile.swift  PopoverDropDelegate.swift   (new)
android/
  core/src/main/kotlin/dev/macdroid/core/transfer/TransferEngine.kt     (modify: visible decisions, IO createPart, periodic resume, rate fix)
  core/src/test/kotlin/dev/macdroid/core/transfer/TransferEngineDecisionTest.kt   (new)
  app/build.gradle.kts                 (modify: version 0.3.0/3)
  app/src/main/AndroidManifest.xml     (modify: ShareActivity, SaveAsActivity)
  app/src/main/java/dev/macdroid/app/
    MacDroidApp.kt                     (modify: AppGraph transfer wiring)
    platform/FilesPrefs.kt  SaveMode.kt  ShareSource.kt  SaveTargets.kt  MediaStoreSaveTarget.kt  TreeSaveTarget.kt
    platform/AskSaveTarget.kt  SavePrompter.kt  ContentSourceOpener.kt  SendFiles.kt             (new)
    service/TransferNotifications.kt   (new)  service/ConnectionService.kt (modify)
    ui/presentation/TransferPresentation.kt  FolderLabel.kt  ShareDecision.kt                  (new, pure)
    ui/files/FilesViewModel.kt  FilesScreen.kt  TransferRow.kt  SendPickers.kt  SaveAsActivity.kt  (new)
    ui/share/ShareActivity.kt          (new)
    ui/main/MainScreen.kt  HomeScreen.kt  SettingsScreen.kt  (modify)
    ui/main/FilesSettingsViewModel.kt  (new)
    ui/setup/SetupFlow.kt  SetupViewModel.kt  SetupScreen.kt   (modify: Files step)
  app/src/test/java/dev/macdroid/app/
    TransferPresentationTest.kt  SaveModeTest.kt  ShareSourceTest.kt  ShareDecisionTest.kt  FolderLabelTest.kt  (new)
    SetupFlowTest.kt                   (modify)
docs/handoff/*                         (modify: every task)
```

---
### Task 1: Mac core — pending offers are visible, cancellable and carry their details

Today `TransferManager` asks `ReceiveLocationProvider.saveLocation(forName:size:)` without a transfer id, the question can't be cancelled, and nothing shows in the snapshot until the answer arrives. The app's save panel needs all three (spec §3.2 "Ask where to save each time", §5 "Cancel on either side").

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Transfer/TransferManager.swift`
- Modify: `mac/MacDroidCore/Sources/MacDroidDevServer/main.swift` (`FolderLocation`)
- Modify: `mac/MacDroidCore/Tests/MacDroidCoreTests/TestSupport.swift` (`FixedLocation`, new `GateLocation`)
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/TransferReceiveTests.swift`
- Modify: `docs/handoff/CHANGELOG.md`, `ARCHITECTURE.md`

**Interfaces:**
- Produces:
  - `public struct IncomingOffer: Equatable, Sendable { id, name, size, mime }`. `name` is already sanitised.
  - `ReceiveLocationProvider.saveLocation(for offer: IncomingOffer) async -> SaveLocation?`, which replaces `saveLocation(forName:size:)`. It is called inside a Task that is **cancelled** when the phone cancels the offer.
  - An incoming offer appears in `snapshot.active` right away, with phase `.offered`, direction `.fromPhone` and `folder == nil`, until it is decided.
  - `cancel(id:)` on an undecided offer cancels the question and sends `transfer.cancel`.

- [ ] **Step 1: Update the test helpers** (`TestSupport.swift`)

Replace `FixedLocation` with:

```swift
struct FixedLocation: ReceiveLocationProvider {
    let location: SaveLocation?
    init(_ location: SaveLocation?) { self.location = location }
    func saveLocation(for offer: IncomingOffer) async -> SaveLocation? { location }
}

/// A provider that waits until the test answers, like a save panel. Records the offers it was asked about and which were cancelled.
actor GateLocation: ReceiveLocationProvider {
    private(set) var asked: [IncomingOffer] = []
    private(set) var cancelled: [String] = []
    private var waiting: [String: CheckedContinuation<SaveLocation?, Never>] = [:]

    func saveLocation(for offer: IncomingOffer) async -> SaveLocation? {
        asked.append(offer)
        let id = offer.id
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                if cancelled.contains(id) { continuation.resume(returning: nil) } else { waiting[id] = continuation }
            }
        } onCancel: {
            Task { await self.cancelQuestion(id) }
        }
    }

    func answer(_ id: String, with location: SaveLocation?) { waiting.removeValue(forKey: id)?.resume(returning: location) }

    private func cancelQuestion(_ id: String) {
        cancelled.append(id)
        waiting.removeValue(forKey: id)?.resume(returning: nil)
    }
}
```

- [ ] **Step 2: Write the failing tests** (append inside `TransferReceiveTests`)

```swift
    @Test func undecidedOfferIsVisibleAndTheProviderGetsItsDetails() async throws {
        let f = try TransferFixture()
        let gate = GateLocation()
        let manager = f.manager(location: gate)
        await manager.handle(makeOffer("t1", name: "../IMG.jpg", size: 42, mime: "image/jpeg"))
        try await eventually { await gate.asked.count == 1 }
        #expect(await gate.asked == [IncomingOffer(id: "t1", name: "IMG.jpg", size: 42, mime: "image/jpeg")])
        let pending = try #require(await manager.snapshot.active.first)
        #expect(pending.phase == .offered && pending.direction == .fromPhone && pending.folder == nil && pending.name == "IMG.jpg")
        await gate.answer("t1", with: .folder(f.downloads))
        try await eventually { await manager.snapshot.active.first?.phase == .active }
        #expect(await f.phone.sent.contains { $0.acceptedID == "t1" })
        #expect(await manager.snapshot.active.count == 1) // the pending record became the transfer, not a second row
    }

    // Review Focus #1
    @Test func peerCancelWhileDecidingCancelsTheQuestion() async throws {
        let f = try TransferFixture()
        let gate = GateLocation()
        let manager = f.manager(location: gate)
        await manager.handle(makeOffer("t1", size: 1))
        try await eventually { await gate.asked.count == 1 }
        await manager.handle(.transferCancel(TransferID(transferId: "t1")))
        try await eventually { await gate.cancelled == ["t1"] }
        #expect(await manager.snapshot.history.first?.phase == .cancelled(byPeer: true))
        #expect(await manager.snapshot.active.isEmpty)
        #expect(await !f.phone.sent.contains { $0.acceptedID == "t1" || $0.rejection != nil })
    }

    @Test func localCancelWhileDecidingTellsThePhone() async throws {
        let f = try TransferFixture()
        let gate = GateLocation()
        let manager = f.manager(location: gate)
        await manager.handle(makeOffer("t1", size: 1))
        try await eventually { await gate.asked.count == 1 }
        await manager.cancel(id: "t1")
        try await eventually { await gate.cancelled == ["t1"] }
        #expect(await f.phone.sent.contains { $0.cancelledID == "t1" })
        #expect(await manager.snapshot.history.first?.phase == .cancelled(byPeer: false))
    }

    @Test func reofferWhileDecidingIsIgnored() async throws {
        let f = try TransferFixture()
        let gate = GateLocation()
        let manager = f.manager(location: gate)
        await manager.handle(makeOffer("t1", size: 1))
        try await eventually { await gate.asked.count == 1 }
        await manager.handle(makeOffer("t1", size: 1))
        #expect(await gate.asked.count == 1)
        #expect(await f.phone.sent.isEmpty)
    }

    // Review Focus #5
    @Test func relaunchForgetsUndecidedOffers() async throws {
        let f = try TransferFixture()
        do {
            let first = f.manager(location: GateLocation())
            await first.handle(makeOffer("t1", size: 1))
            try await eventually { await first.snapshot.active.count == 1 }
        }
        let second = f.manager()
        #expect(await second.snapshot.active.isEmpty)
        await second.handle(makeOffer("t1", size: 1)) // the phone re-offers after reconnecting: a fresh decision
        try await eventually { await f.phone.sent.contains { $0.acceptedID == "t1" } }
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter TransferReceiveTests`
Expected: compile errors (`IncomingOffer` is not defined, and `FixedLocation` doesn't conform to the protocol).

- [ ] **Step 4: Change the provider API** (`TransferManager.swift`, top of the file)

Replace the `ReceiveLocationProvider` protocol with:

```swift
/// An offer from the phone that needs a place to be saved.
public struct IncomingOffer: Equatable, Sendable {
    public let id: String
    /// Already sanitised (spec §2.3).
    public let name: String
    public let size: Int64
    public let mime: String

    public init(id: String, name: String, size: Int64, mime: String) {
        self.id = id
        self.name = name
        self.size = size
        self.mime = mime
    }
}

/// Decides where incoming files are saved; nil declines the file.
/// Called inside a Task that is cancelled if the offer is cancelled, so a save panel should close on cancellation.
public protocol ReceiveLocationProvider: Sendable {
    func saveLocation(for offer: IncomingOffer) async -> SaveLocation?
}
```

- [ ] **Step 5: Make decisions visible and cancellable** (`TransferManager.swift`)

1. Replace the stored properties `pendingOffers` and `cancelledOffers` with:

```swift
    /// Undecided incoming offers: the task asking where to save, cancelled if the offer is cancelled.
    private var decisions: [String: Task<Void, Never>] = [:]
```

2. In `restore(active:history:now:)`, drop undecided offers. As the first statement inside the `for var record in stored` loop, add:

```swift
            // An offer still waiting for "where to save" when MacDroid quit was never accepted; the phone's re-offer starts fresh.
            if record.direction == .fromPhone && record.folder == nil { continue }
```

3. In `receiveOffer(_:)`, replace the known-id branch's first line, `guard record.direction == .fromPhone else { return }`, with:

```swift
            guard record.direction == .fromPhone, record.folder != nil else { return } // undecided: the question is still open
```

   Replace the tail of `receiveOffer` (from `guard !pendingOffers.contains(id) else { return }` to the end of the function) with:

```swift
        let name = FileNaming.sanitize(offer.name)
        active.append(TransferRecord(
            id: id, direction: .fromPhone, name: name, size: offer.size, mime: offer.mime, phase: .offered,
            createdAt: now(), updatedAt: now()
        ))
        persist()
        publish(force: true)
        // Deciding may wait on a save panel, so it runs on its own and must not hold up other control messages.
        decisions[id] = Task { await self.decide(IncomingOffer(id: id, name: name, size: offer.size, mime: offer.mime)) }
```

4. Replace `decide(_:)` entirely with:

```swift
    private func decide(_ offer: IncomingOffer) async {
        let id = offer.id
        let decision = await location.saveLocation(for: offer)
        decisions[id] = nil
        // Cancelled meanwhile (by the phone or locally): the record is already gone.
        guard !Task.isCancelled, active.contains(where: { $0.id == id && $0.phase == .offered }) else { return }
        guard let decision else {
            await reject(offer, name: offer.name, reason: .declined)
            return
        }
        let folder: URL
        let finalName: String
        let replaces: Bool
        switch decision {
        case .folder(let url):
            folder = url
            finalName = FileNaming.unique(offer.name) { isTaken($0, in: url) }
            replaces = false
        case .file(let url):
            folder = url.deletingLastPathComponent()
            if isClaimed(url.lastPathComponent, in: folder) {
                // Two save panels picked the same file: never let two transfers share one part file.
                finalName = FileNaming.unique(url.lastPathComponent) { isTaken($0, in: folder) }
                replaces = false
            } else {
                finalName = url.lastPathComponent
                replaces = true
            }
        }
        if let free = freeSpace(folder), free < offer.size {
            await reject(offer, name: finalName, reason: .noSpace)
            return
        }
        // Claim the name before any await, so a concurrent decision picks another.
        update(id) {
            $0.name = finalName
            $0.folder = folder
            $0.replacesExisting = replaces
            $0.phase = .active
        }
        guard let writer = incomingWriter(id) else { return }
        do {
            try await writer.open()
        } catch {
            logger.warning("can't create part file in \(folder.path(percentEncoded: false)): \(error)")
            writers[id] = nil
            await reject(offer, name: finalName, reason: .declined)
            return
        }
        await sendOrDrop(.transferAccept(TransferID(transferId: id)))
    }
```

5. Change `reject(_:name:reason:)` to take an `IncomingOffer`. Replace its signature and archive call:

```swift
    private func reject(_ offer: IncomingOffer, name: String, reason: TransferRejectReason) async {
        archive(TransferRecord(
            id: offer.id, direction: .fromPhone, name: name, size: offer.size, mime: offer.mime,
            phase: .failed(TransferFailure(reason)), createdAt: now(), updatedAt: now()
        ))
        await sendOrDrop(.transferReject(TransferReject(transferId: offer.id, reason: reason)))
    }
```

   `archive` replaces the pending record, since it removes by id.

6. Replace `peerCancelled(_:)` with:

```swift
    private func peerCancelled(_ id: String) async {
        decisions.removeValue(forKey: id)?.cancel() // closes a save panel that's still open
        guard let record = active.first(where: { $0.id == id }) else { return }
        await discard(record, as: .cancelled(byPeer: true))
        await pump()
    }
```

7. In `cancel(id:)`, add `decisions.removeValue(forKey: id)?.cancel()` as its first line.

8. `isTaken`/`isClaimed` already ignore records whose `folder` is nil, so they don't change.

- [ ] **Step 6: Update the dev server** (`MacDroidDevServer/main.swift`)

```swift
struct FolderLocation: ReceiveLocationProvider {
    let folder: URL
    func saveLocation(for offer: IncomingOffer) async -> SaveLocation? { .folder(folder) }
}
```

- [ ] **Step 7: Run all Swift tests**

Run: `cd mac/MacDroidCore && swift test`
Expected: all pass (129 earlier + 5 new), no warnings.

- [ ] **Step 8: Handoff and commit**
  - **CHANGELOG.md:** add at the top:
    ```markdown
    ## 2026-10-13 — Plan 2b: file transfer UI (in progress on feat/file-transfer)
    - **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2b-ui.md`.
    - Task 1: Mac `TransferManager` shows undecided incoming offers as `.offered` rows, passes `IncomingOffer` (id, name, size, mime) to the save-location provider, and cancels the question when either side cancels; undecided offers are forgotten at relaunch. Verified: `swift test`.
    ```
  - **ARCHITECTURE.md:** in the Mac "Transfer" row, add "`ReceiveLocationProvider.saveLocation(for: IncomingOffer)` runs in a cancellable task (the app shows a save panel there)".

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac): visible, cancellable save-location decisions"
```

---

### Task 2: Mac `TransferPresentation` (pure row text, popover items, drop summary)

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Presentation/TransferPresentation.swift`
- Test: create `mac/MacDroidCore/Tests/MacDroidCoreTests/TransferPresentationTests.swift`

**Interfaces:**
- Consumes: `TransferRecord`, `TransferSnapshot`, `TransferText`.
- Produces:
  - `public struct TransferRowModel: Equatable, Sendable { title, directionLabel, detail, progress: Double?, isError, canCancel, canRetry, canOpen }`
  - `public enum TransferPresentation`:
    - `row(_ record: TransferRecord, rate: Double?) -> TransferRowModel`
    - `popoverItems(_ snapshot: TransferSnapshot, limit: Int = 2) -> [TransferRecord]`
    - `dropSummary(count: Int, totalBytes: Int64) -> String`

- [ ] **Step 1: Write the failing tests** (`TransferPresentationTests.swift`)

```swift
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct TransferPresentationTests {
    func record(_ direction: TransferDirection, _ phase: TransferPhase, size: Int64 = 2_100_000_000, done: Int64 = 0,
                bookmark: Data? = nil, saved: URL? = nil) -> TransferRecord {
        TransferRecord(id: "t", direction: direction, name: "clip.mov", size: size, mime: "video/quicktime", phase: phase,
                       bytesDone: done, sourceBookmark: bookmark, savedURL: saved,
                       createdAt: Date(timeIntervalSince1970: 0), updatedAt: Date(timeIntervalSince1970: 0))
    }

    @Test func activeRowShowsProgressSpeedAndTimeLeft() {
        let row = TransferPresentation.row(record(.toPhone, .active, done: 1_300_000_000), rate: 48_000_000)
        #expect(row.title == "clip.mov")
        #expect(row.directionLabel == "→ phone")
        #expect(row.detail == "1.3 of 2.1 GB · 48 MB/s · 17 s left")
        #expect(abs((row.progress ?? 0) - 1_300_000_000.0 / 2_100_000_000.0) < 0.0001)
        #expect(row.canCancel && !row.canRetry && !row.isError)
    }

    @Test func waitingStates() {
        #expect(TransferPresentation.row(record(.toPhone, .queued), rate: nil).detail == "Waiting to send")
        #expect(TransferPresentation.row(record(.toPhone, .offered), rate: nil).detail == "Waiting for your phone")
        #expect(TransferPresentation.row(record(.fromPhone, .offered), rate: nil).detail == "Waiting for you to choose where to save")
        #expect(TransferPresentation.row(record(.fromPhone, .paused, done: 1_050_000_000), rate: nil).detail == "Paused — waiting for connection")
        #expect(TransferPresentation.row(record(.fromPhone, .paused, done: 1_050_000_000), rate: nil).progress == 0.5)
        #expect(TransferPresentation.row(record(.fromPhone, .verifying), rate: nil).detail == "Checking…")
        #expect(TransferPresentation.row(record(.fromPhone, .active), rate: nil).directionLabel == "← phone")
    }

    @Test func finishedRows() {
        let received = TransferPresentation.row(record(.fromPhone, .done, size: 3_145_728, saved: URL(fileURLWithPath: "/x/clip.mov")), rate: nil)
        #expect(received.detail == "Received · 3.1 MB")
        #expect(received.canOpen && !received.canCancel && !received.canRetry)
        #expect(TransferPresentation.row(record(.toPhone, .done, size: 512), rate: nil).detail == "Sent · 512 bytes")
        #expect(TransferPresentation.row(record(.toPhone, .cancelled(byPeer: true)), rate: nil).detail == "Cancelled on your phone")
        #expect(TransferPresentation.row(record(.toPhone, .cancelled(byPeer: false)), rate: nil).detail == "Cancelled on your Mac")
    }

    @Test func failuresAreErrorsAndOutgoingOnesCanBeRetried() {
        let corrupted = TransferPresentation.row(record(.toPhone, .failed(.checksumMismatch), bookmark: Data([1])), rate: nil)
        #expect(corrupted.detail == "Transfer failed — file was corrupted in transit")
        #expect(corrupted.isError && corrupted.canRetry)
        #expect(!TransferPresentation.row(record(.fromPhone, .failed(.io)), rate: nil).canRetry) // only the sender has the file
        #expect(TransferPresentation.row(record(.toPhone, .failed(.noSpace)), rate: nil).detail == "Not enough space on your phone")
        #expect(TransferPresentation.row(record(.fromPhone, .failed(.noSpace)), rate: nil).detail == "Not enough space on this Mac")
        #expect(TransferPresentation.row(record(.toPhone, .failed(.declined)), rate: nil).detail == "Declined on your phone")
        #expect(TransferPresentation.row(record(.fromPhone, .failed(.declined)), rate: nil).detail == "Not saved")
        #expect(TransferPresentation.row(record(.toPhone, .failed(.sourceUnavailable)), rate: nil).detail == "The original file is no longer available")
        #expect(TransferPresentation.row(record(.toPhone, .failed(.expired)), rate: nil).detail == "Expired — not finished within 24 hours")
        #expect(TransferPresentation.row(record(.toPhone, .failed(.io)), rate: nil).detail == "Transfer failed")
    }

    @Test func popoverShowsActiveTransfersElseTheLastReceivedFile() {
        let a = record(.toPhone, .active), b = record(.fromPhone, .active), c = record(.toPhone, .queued)
        #expect(TransferPresentation.popoverItems(TransferSnapshot(active: [a, b, c], history: [], rates: [:])).count == 2)
        let sent = record(.toPhone, .done), received = record(.fromPhone, .done)
        #expect(TransferPresentation.popoverItems(TransferSnapshot(active: [], history: [sent, received], rates: [:])) == [received])
        #expect(TransferPresentation.popoverItems(.empty).isEmpty)
    }

    @Test func dropSummary() {
        #expect(TransferPresentation.dropSummary(count: 3, totalBytes: 12_400_000) == "3 files · 12.4 MB")
        #expect(TransferPresentation.dropSummary(count: 1, totalBytes: 512) == "1 file · 512 bytes")
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter TransferPresentationTests`
Expected: compile error (`TransferPresentation` is not defined).

- [ ] **Step 3: Implement `TransferPresentation.swift`**

```swift
import Foundation

/// What one transfer row shows on the Files page and in the popover.
public struct TransferRowModel: Equatable, Sendable {
    public var title: String
    public var directionLabel: String
    public var detail: String
    /// 0…1 while bytes are moving or paused; nil when there's no bar to show.
    public var progress: Double?
    public var isError: Bool
    public var canCancel: Bool
    public var canRetry: Bool
    public var canOpen: Bool
}

/// Pure transfer → text mapping for the Mac UI. The Android app mirrors it with "phone"/"Mac" swapped.
public enum TransferPresentation {
    public static func row(_ record: TransferRecord, rate: Double?) -> TransferRowModel {
        let toPhone = record.direction == .toPhone
        let fraction = record.size > 0 ? min(1, Double(record.bytesDone) / Double(record.size)) : nil
        let canRetry = toPhone && record.sourceBookmark != nil
        var row = TransferRowModel(
            title: record.name, directionLabel: toPhone ? "→ phone" : "← phone", detail: "", progress: nil,
            isError: false, canCancel: !record.phase.isFinished, canRetry: false, canOpen: false
        )
        switch record.phase {
        case .queued:
            row.detail = "Waiting to send"
        case .offered:
            row.detail = toPhone ? "Waiting for your phone" : "Waiting for you to choose where to save"
        case .active:
            row.detail = TransferText.line(done: record.bytesDone, total: record.size, bytesPerSecond: rate)
            row.progress = fraction
        case .paused:
            row.detail = "Paused — waiting for connection"
            row.progress = fraction
        case .verifying:
            row.detail = "Checking…"
            row.progress = 1
        case .done:
            row.detail = "\(toPhone ? "Sent" : "Received") · \(TransferText.bytes(record.size))"
            row.canOpen = !toPhone && record.savedURL != nil
        case .failed(let failure):
            row.detail = failureText(failure, toPhone: toPhone)
            row.isError = true
            row.canRetry = canRetry
        case .cancelled(let byPeer):
            row.detail = byPeer ? "Cancelled on your phone" : "Cancelled on your Mac"
            row.canRetry = canRetry
        }
        return row
    }

    /// The popover shows up to `limit` running transfers, otherwise the last received file.
    public static func popoverItems(_ snapshot: TransferSnapshot, limit: Int = 2) -> [TransferRecord] {
        if !snapshot.active.isEmpty { return Array(snapshot.active.prefix(limit)) }
        return Array(snapshot.history.filter { $0.direction == .fromPhone && $0.phase == .done }.prefix(1))
    }

    /// "3 files · 12.4 MB", shown while files are dragged over the popover.
    public static func dropSummary(count: Int, totalBytes: Int64) -> String {
        "\(count) \(count == 1 ? "file" : "files") · \(TransferText.bytes(totalBytes))"
    }

    static func failureText(_ failure: TransferFailure, toPhone: Bool) -> String {
        switch failure {
        case .checksumMismatch: "Transfer failed — file was corrupted in transit"
        case .noSpace: toPhone ? "Not enough space on your phone" : "Not enough space on this Mac"
        case .declined: toPhone ? "Declined on your phone" : "Not saved"
        case .sourceUnavailable: "The original file is no longer available"
        case .expired: "Expired — not finished within 24 hours"
        case .io, .invalid: "Transfer failed"
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test` → all pass, no warnings.

- [ ] **Step 5: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 2: `TransferPresentation` (row text, popover items, drop summary) in `MacDroidCore`. Verified: `TransferPresentationTests`."
  - **ARCHITECTURE.md:** add `Presentation/TransferPresentation.swift` to the Presentation row.

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac): pure transfer presentation"
```

---
### Task 3: Android core — visible and cancellable decisions, part creation off the control thread, periodic resume

These are the Android counterparts of Task 1. There's one more: a transfer that ran out of retries sat "Paused" until the next reconnect (2a's final-review minor), so while connected, paused transfers are now retried every 30 s.

**Files:**
- Modify: `android/core/src/main/kotlin/dev/macdroid/core/transfer/TransferEngine.kt`
- Modify: `android/core/src/test/kotlin/dev/macdroid/core/transfer/TransferEngineReceiveTest.kt` (`EngineHarness` gets `resumeIntervalMs`)
- Test: create `android/core/src/test/kotlin/dev/macdroid/core/transfer/TransferEngineDecisionTest.kt`

**Interfaces:**
- Produces:
  - **Undecided offers:** an incoming offer appears in `snapshot.active` right away as `Phase.Offered`, `Direction.FromMac`, `part == null`, until the `SaveTarget` answers.
  - **Cancellation:** `cancel(id)` or a `transfer.cancel` from the Mac while undecided cancels the `createPart` coroutine. An "ask where to save" prompt sees `CancellationException`.
  - **IO dispatch:** `SaveTarget.createPart` now runs on `Dispatchers.IO`.
  - A new constructor parameter `resumeIntervalMs: Long = 30_000`.

- [ ] **Step 1: Let the harness set the resume interval** (`TransferEngineReceiveTest.kt`)

Add `resumeIntervalMs: Long = 60_000` as the last `EngineHarness` constructor parameter, and pass `resumeIntervalMs = resumeIntervalMs` to `TransferEngine(…)`.

- [ ] **Step 2: Write the failing tests** (`TransferEngineDecisionTest.kt`)

```kotlin
package dev.macdroid.core.transfer

import dev.macdroid.core.eventually
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.TransferIdBody
import dev.macdroid.core.protocol.TransferOfferBody
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A save target that waits for the test, like an "Ask where to save" prompt. */
class GateTarget(private val folder: File) : SaveTarget {
    val asked = CopyOnWriteArrayList<String>()
    val answer = CompletableDeferred<Boolean>() // true: save into the folder; false: decline
    @Volatile var cancelled = false

    override fun freeBytes(): Long? = null

    override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? {
        asked += transferId
        return try {
            if (answer.await()) FolderSaveTarget(folder).createPart(name, mime, transferId) else null
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        }
    }
}

class TransferEngineDecisionTest {
    private val dir: File = Files.createTempDirectory("decide").toFile()
    private val gate = GateTarget(File(dir, "Download/MacDroid"))
    private val h = EngineHarness(dir = dir, target = gate)

    @After fun tearDown() = h.close()

    private fun offer(id: String = "t1") = h.deliver(Message.TransferOffer(TransferOfferBody(id, "../IMG.jpg", 10, "image/jpeg")))

    @Test fun undecidedOfferIsVisibleAsOffered() {
        h.start()
        h.mac.served["t1"] = testBytes(10)
        offer()
        eventually { gate.asked.size == 1 }
        val pending = h.active().single()
        assertEquals(Phase.Offered, pending.phase)
        assertEquals("IMG.jpg", pending.name)
        assertNull(pending.part)
        gate.answer.complete(true)
        eventually { h.sentOf<Message.TransferAccept>().size == 1 }
        eventually { h.active().singleOrNull()?.part != null }
    }

    @Test fun peerCancelWhileDecidingCancelsTheQuestion() {
        h.start()
        offer()
        eventually { gate.asked.size == 1 }
        h.deliver(Message.TransferCancel(TransferIdBody("t1")))
        eventually { gate.cancelled }
        eventually { h.history().firstOrNull()?.phase == Phase.Cancelled }
        assertTrue(h.history().first().cancelledByPeer)
        assertTrue(h.active().isEmpty())
        assertTrue(h.sentOf<Message.TransferAccept>().isEmpty() && h.sentOf<Message.TransferReject>().isEmpty())
    }

    @Test fun localCancelWhileDecidingTellsTheMac() {
        h.start()
        offer()
        eventually { gate.asked.size == 1 }
        h.engine.cancel("t1")
        eventually { gate.cancelled }
        eventually { h.sentOf<Message.TransferCancel>().any { it.body.transferId == "t1" } }
    }

    @Test fun reofferWhileDecidingIsIgnored() {
        h.start()
        offer()
        eventually { gate.asked.size == 1 }
        offer()
        Thread.sleep(100)
        assertEquals(1, gate.asked.size)
        assertTrue(h.sent.isEmpty())
    }

    @Test fun disconnectLeavesAnUndecidedOfferAlone() {
        h.start()
        offer()
        eventually { gate.asked.size == 1 }
        h.connected.value = false
        Thread.sleep(100)
        assertEquals(Phase.Offered, h.active().single().phase)
        assertTrue(!gate.cancelled)
    }

    // Review Focus #5
    @Test fun restartForgetsUndecidedOffers() {
        val store = InMemoryTransferStore(StoredTransfers(active = listOf(
            TransferRecord(id = "t1", direction = Direction.FromMac, name = "a", size = 1, mime = "x", phase = Phase.Offered,
                createdAt = h.now, updatedAt = h.now),
        )))
        val harness = EngineHarness(store = store, dir = dir, target = gate)
        try {
            harness.start()
            Thread.sleep(100)
            assertTrue(harness.active().isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test fun pausedUploadIsRetriedWhileConnected() {
        val harness = EngineHarness(dir = dir, resumeIntervalMs = 300)
        try {
            harness.start()
            harness.mac.headStatus = 500 // every attempt fails, until the retries run out
            val source = File(dir, "up.bin").apply { writeBytes(testBytes(1_000)) }
            val id = harness.engine.enqueue(listOf(OutgoingFile(source.path, source.name, 1_000, "x"))).single()
            eventually { harness.sentOf<Message.TransferOffer>().size == 1 }
            harness.deliver(Message.TransferAccept(TransferIdBody(id)))
            Thread.sleep(250) // 3 retries 20 ms apart have given up by now
            assertEquals(Phase.Paused, harness.active().single().phase)
            harness.mac.headStatus = null
            eventually { harness.sentOf<Message.TransferOffer>().size == 2 } // the periodic resume offers it again
            harness.deliver(Message.TransferAccept(TransferIdBody(id)))
            eventually { harness.sentOf<Message.TransferFinish>().any { it.body.transferId == id } }
        } finally {
            harness.close()
        }
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd android && ./gradlew :core:test --tests '*TransferEngineDecisionTest*'`
Expected: compile error (no `resumeIntervalMs` parameter). After Step 1 alone, the decision tests fail on their assertions: no `Offered` record, no cancellation.

- [ ] **Step 4: Change the engine** (`TransferEngine.kt`)

1. Add the constructor parameter `private val resumeIntervalMs: Long = 30_000,` after `maxRetries`.

2. In `start`, add a third child after the two collectors:

```kotlin
            launch {
                while (true) {
                    delay(resumeIntervalMs)
                    resumePaused()
                }
            }
```

3. In `load()`, add this as the first branch of the `when` inside the loop:

```kotlin
                // An offer still waiting for "where to save" when the app died was never accepted; the Mac's re-offer starts fresh.
                record.direction == Direction.FromMac && record.part == null -> Unit
```

4. In `onConnectionChanged`, change the pause condition so undecided incoming offers aren't paused:

```kotlin
                val moving = record.phase == Phase.Active || (record.phase == Phase.Offered && record.direction == Direction.ToMac)
                if (moving) update(record.id) { it.copy(phase = Phase.Paused) }
```

5. In `cancelLocally`, add `deciding.remove(id)?.cancel()` as the first line.

6. Replace `onOffer` and `decide` with:

```kotlin
    private fun onOffer(offer: TransferOfferBody) {
        val id = offer.transferId
        val known = record(id)
        if (known != null) {
            if (known.direction != Direction.FromMac || known.part == null) return // undecided: the question is still open
            // A re-offer after a reconnect: accept again and resume from the part file's length.
            update(id) { it.copy(phase = Phase.Active) }
            send(Message.TransferAccept(TransferIdBody(id)))
            startDownload(id)
            return
        }
        past(id)?.let { replay(it); return }
        if (offer.size < 0) {
            send(Message.TransferReject(TransferRejectBody(id, TransferRejectReason.Invalid)))
            return
        }
        val name = FileNaming.sanitize(offer.name)
        add(TransferRecord(id = id, direction = Direction.FromMac, name = name, size = offer.size, mime = offer.mime,
            phase = Phase.Offered, createdAt = clock(), updatedAt = clock()))
        persist()
        // Deciding may wait for the user ("Ask where to save"), so it runs on its own and can be cancelled.
        deciding[id] = scope.launch(control) {
            try {
                decide(offer, name)
            } finally {
                deciding.remove(id)
            }
        }
    }

    private suspend fun decide(offer: TransferOfferBody, name: String) {
        val id = offer.transferId
        val target = saveTarget()
        val free = withContext(Dispatchers.IO) { runCatching { target.freeBytes() }.getOrNull() }
        if (free != null && free < offer.size) return reject(offer, name, TransferRejectReason.NoSpace)
        val part = try {
            withContext(Dispatchers.IO) { target.createPart(name, offer.mime, id) } // MediaStore/SAF work, or a user prompt
        } catch (e: IOException) {
            null
        } ?: return reject(offer, name, TransferRejectReason.Declined)
        if (record(id)?.phase != Phase.Offered) { // cancelled while the part was being created
            withContext(Dispatchers.IO) { runCatching { part.delete() } }
            return
        }
        update(id) { it.copy(phase = Phase.Active, part = part.handle) }
        persist()
        send(Message.TransferAccept(TransferIdBody(id)))
        startDownload(id)
    }
```

7. Add the periodic resume, next to `pauseForRetry`:

```kotlin
    /** While connected, try paused transfers again, so nothing sits "Paused" while the Mac is reachable (spec §5). */
    private fun resumePaused() {
        if (!connected) return
        for (record in active.filter { it.phase == Phase.Paused && jobs[it.id]?.isActive != true }) {
            when (record.direction) {
                Direction.ToMac -> {
                    update(record.id) { it.copy(phase = Phase.Offered) }
                    offer(record)
                }
                Direction.FromMac -> if (record.part != null) {
                    update(record.id) { it.copy(phase = Phase.Active) }
                    startDownload(record.id)
                }
            }
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd android && ./gradlew :core:test` → BUILD SUCCESSFUL. Then run the engine tests three times with `--rerun-tasks` to check they are stable.

- [ ] **Step 6: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 3: Android `TransferEngine` shows undecided offers as `Offered`, cancels the save question on either side's cancel, creates parts on IO, forgets undecided offers on restart, and retries paused transfers every 30 s while connected. Verified: `TransferEngineDecisionTest`."

```bash
git add android/core docs/handoff
git commit -m "feat(android): visible, cancellable save decisions and periodic resume"
```

---
### Task 4: Mac app — wire up `TransferManager`, folder settings, notifications and entitlements

The app target has no unit-test target, so this task is verified by the app build and a scripted smoke check. Its logic is the core's, which is already tested.

**Files:**
- Modify: `mac/project.yml` (entitlements, version)
- Create: `mac/MacDroid/Files/FilesSettings.swift`, `mac/MacDroid/Files/SecurityScopedSourceAccess.swift`, `mac/MacDroid/Files/ReceivedNotifier.swift`
- Modify: `mac/MacDroid/AppModel.swift`
- Modify: `docs/handoff/ARCHITECTURE.md`, `DEV-GUIDE.md`, `CHANGELOG.md`

**Interfaces:**
- Consumes: `TransferManager(stateDirectory:location:sourceAccess:send:)`, `attach(to:)`, `snapshots`, `events`, `send(files:)`, `cancel(id:)`, `retry(id:)`; `IncomingOffer`; `SaveLocation`; `SourceAccess`; `AccessedFile`.
- Produces (used by Tasks 5–6):
  - `AppModel` state: `transfers: TransferSnapshot`, `files: FilesSettings`, `canSendFiles: Bool`, `phoneName: String`
  - `AppModel` actions: `sendFiles(_ urls: [URL])`, `pickFilesToSend()`, `cancelTransfer(_ id: String)`, `retryTransfer(_ id: String)`, `revealReceived(_ record: TransferRecord)`, `openReceived(_ record: TransferRecord)`
  - `FilesSettings` (`@MainActor @Observable`):
    - state: `askEachTime: Bool` (get/set), `folder: URL`, `folderProblem: Bool`, `usesDefaultFolder: Bool`, `phoneName: String`
    - actions: `chooseFolder()`, `useDefaultFolder()`
- An `AppModel.transientError` message, "Folders can't be sent yet — send the files inside instead.", shows when folders are dropped.

- [ ] **Step 1: Entitlements and version** (`mac/project.yml`)

Under `targets.MacDroid.entitlements.properties`, add:

```yaml
        com.apple.security.files.user-selected.read-write: true
        com.apple.security.files.downloads.read-write: true
        com.apple.security.files.bookmarks.app-scope: true
```

Under `info.properties`, set `CFBundleShortVersionString: "0.3.0"` and `CFBundleVersion: "3"`.

- [ ] **Step 2: Create `Files/FilesSettings.swift`**

```swift
import AppKit
import MacDroidCore
import Observation

/// Settings → Received files: where files from the phone go (a security-scoped folder bookmark) and "Ask where to save each time".
@MainActor @Observable
final class FilesSettings {
    private static let askKey = "files.askEachTime"
    private static let bookmarkKey = "files.folderBookmark"

    var askEachTime: Bool {
        didSet { UserDefaults.standard.set(askEachTime, forKey: Self.askKey) }
    }
    /// Where received files go: the chosen folder, or ~/Downloads/MacDroid.
    private(set) var folder: URL
    /// The chosen folder can't be reached any more; received files ask where to save until it's chosen again.
    private(set) var folderProblem = false
    private(set) var usesDefaultFolder = true
    /// Named in save panels ("<phone> is sending …").
    var phoneName = "Your phone"

    @ObservationIgnored private var accessedFolder: URL?
    @ObservationIgnored private var openPanels: [String: NSSavePanel] = [:]

    static var defaultFolder: URL {
        URL.downloadsDirectory.resolvingSymlinksInPath().appending(path: "MacDroid", directoryHint: .isDirectory)
    }

    init() {
        askEachTime = UserDefaults.standard.bool(forKey: Self.askKey)
        folder = Self.defaultFolder
        restoreFolder()
    }

    func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.prompt = "Choose"
        panel.message = "Choose where files from your phone are saved."
        panel.directoryURL = folder
        NSApp.activate()
        guard panel.runModal() == .OK, let url = panel.url,
              let bookmark = try? url.bookmarkData(options: .withSecurityScope) else { return }
        UserDefaults.standard.set(bookmark, forKey: Self.bookmarkKey)
        restoreFolder()
    }

    func useDefaultFolder() {
        UserDefaults.standard.removeObject(forKey: Self.bookmarkKey)
        restoreFolder()
    }

    /// Resolves the bookmark and keeps access open while MacDroid runs (received files are written there at any time).
    private func restoreFolder() {
        accessedFolder?.stopAccessingSecurityScopedResource()
        accessedFolder = nil
        folderProblem = false
        guard let bookmark = UserDefaults.standard.data(forKey: Self.bookmarkKey) else {
            usesDefaultFolder = true
            folder = Self.defaultFolder
            return
        }
        usesDefaultFolder = false
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, options: .withSecurityScope, bookmarkDataIsStale: &stale),
              url.startAccessingSecurityScopedResource() else {
            folderProblem = true
            folder = Self.defaultFolder
            return
        }
        accessedFolder = url
        folder = url
        if stale, let fresh = try? url.bookmarkData(options: .withSecurityScope) {
            UserDefaults.standard.set(fresh, forKey: Self.bookmarkKey)
        }
    }

    /// The core's question: the folder, or a save panel when asked for or when the chosen folder is unreachable.
    func saveLocation(for offer: IncomingOffer) async -> SaveLocation? {
        if !askEachTime && !folderProblem {
            do {
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                return .folder(folder)
            } catch {
                folderProblem = true
            }
        }
        return await askWhereToSave(offer)
    }

    private func askWhereToSave(_ offer: IncomingOffer) async -> SaveLocation? {
        guard !Task.isCancelled else { return nil }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = offer.name
        panel.directoryURL = folder
        panel.message = "\(phoneName) is sending “\(offer.name)” (\(TransferText.bytes(offer.size))). Choose where to save it."
        let id = offer.id
        openPanels[id] = panel
        defer { openPanels[id] = nil }
        NSApp.activate()
        let response = await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                panel.begin { continuation.resume(returning: $0) }
            }
        } onCancel: {
            // The phone cancelled: close the panel instead of leaving a question nobody needs answered.
            Task { @MainActor [weak self] in self?.openPanels[id]?.cancel(nil) }
        }
        guard response == .OK, let url = panel.url else { return nil }
        return .file(url)
    }
}

/// Bridges the core's question to `FilesSettings` on the main actor.
struct MacReceiveLocation: ReceiveLocationProvider {
    let settings: FilesSettings
    func saveLocation(for offer: IncomingOffer) async -> SaveLocation? { await settings.saveLocation(for: offer) }
}
```

- [ ] **Step 3: Create `Files/SecurityScopedSourceAccess.swift`**

```swift
import Foundation
import MacDroidCore

/// Outgoing files: security-scoped, read-only bookmarks, so a resume still works after MacDroid relaunches (spec §3.1).
struct SecurityScopedSourceAccess: SourceAccess {
    func bookmark(for url: URL) throws -> Data {
        try url.bookmarkData(options: [.withSecurityScope, .securityScopeAllowOnlyReadAccess])
    }

    func resolve(_ bookmark: Data) throws -> AccessedFile {
        var stale = false
        let url = try URL(resolvingBookmarkData: bookmark, options: .withSecurityScope, bookmarkDataIsStale: &stale)
        let started = url.startAccessingSecurityScopedResource()
        return AccessedFile(url: url, stop: { if started { url.stopAccessingSecurityScopedResource() } })
    }
}
```

- [ ] **Step 4: Create `Files/ReceivedNotifier.swift`**

```swift
import AppKit
import MacDroidCore
import UserNotifications

/// "Received <name>" notifications with Show in Finder and Open (spec §3.2). Asks for permission the first time a file arrives.
@MainActor
final class ReceivedNotifier: NSObject, UNUserNotificationCenterDelegate {
    nonisolated static let category = "dev.macdroid.received"
    nonisolated static let showAction = "show"
    nonisolated static let openAction = "open"
    nonisolated static let pathKey = "path"
    private var authorized: Bool?

    func setUp() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: Self.category,
                actions: [
                    UNNotificationAction(identifier: Self.showAction, title: "Show in Finder"),
                    UNNotificationAction(identifier: Self.openAction, title: "Open"),
                ],
                intentIdentifiers: []
            ),
        ])
    }

    func notifyReceived(_ record: TransferRecord, from phoneName: String) async {
        guard let url = record.savedURL else { return }
        let center = UNUserNotificationCenter.current()
        if authorized == nil {
            authorized = (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        }
        guard authorized == true else { return }
        let content = UNMutableNotificationContent()
        content.title = "Received \(record.name)"
        content.body = "From \(phoneName)"
        content.categoryIdentifier = Self.category
        content.userInfo = [Self.pathKey: url.path(percentEncoded: false)]
        try? await center.add(UNNotificationRequest(identifier: record.id, content: content, trigger: nil))
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let path = response.notification.request.content.userInfo[Self.pathKey] as? String else { return }
        let open = response.actionIdentifier == Self.openAction
        await MainActor.run {
            let url = URL(fileURLWithPath: path)
            if open { NSWorkspace.shared.open(url) } else { NSWorkspace.shared.activateFileViewerSelecting([url]) }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}
```

- [ ] **Step 5: Wire it into `AppModel`**

1. Add `import AppKit` at the top.

2. Add stored properties next to the others:

```swift
    private(set) var transfers: TransferSnapshot = .empty
    let files: FilesSettings
    @ObservationIgnored private let transferManager: TransferManager
    @ObservationIgnored private let notifier: ReceivedNotifier
```

3. In `init()`:
   - Replace `pairedDevice = store.load()` with:
     ```swift
             let paired = store.load()
             pairedDevice = paired
     ```
   - Replace `hub = ConnectionHub(macName: name, appVersion: …)` with:

```swift
        let hub = ConnectionHub(macName: name, appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0")
        self.hub = hub
        let files = FilesSettings()
        files.phoneName = paired?.name ?? "Your phone"
        self.files = files
        transferManager = TransferManager(
            stateDirectory: URL.applicationSupportDirectory.appending(path: "Transfers", directoryHint: .isDirectory),
            location: MacReceiveLocation(settings: files),
            sourceAccess: SecurityScopedSourceAccess(),
            send: { message in try await hub.send(message) }
        )
        notifier = ReceivedNotifier()
```

4. In `start()`, pass the manager to the server, `MacDroidServer(identity: identity, pairing: pairing, hub: hub, macName: macName, transfers: transferManager)`. Then append:

```swift
        notifier.setUp()
        tasks.append(Task { [transferManager, hub] in await transferManager.attach(to: hub) })
        tasks.append(Task { [weak self, transferManager] in
            for await snapshot in transferManager.snapshots { self?.transfers = snapshot }
        })
        tasks.append(Task { [weak self, transferManager] in
            for await event in transferManager.events { await self?.handle(event) }
        })
```

5. In `handle(_ event: PairingEvent)`, in the `.paired(let device)` case, add `files.phoneName = device.name`.

6. Add the file actions and helpers, after `forgetPhone()`:

```swift
    var canSendFiles: Bool {
        if case .connected = status { return true }
        return false
    }

    var phoneName: String { pairedDevice?.name ?? "your phone" }

    /// Queues dropped or picked files. Folders aren't supported yet (spec §1, out of scope).
    func sendFiles(_ urls: [URL]) {
        let files = urls.filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory != true }
        transientError = files.count < urls.count ? "Folders can't be sent yet — send the files inside instead." : nil
        guard !files.isEmpty else { return }
        Task { _ = await transferManager.send(files: files) }
    }

    func pickFilesToSend() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.prompt = "Send"
        panel.message = "Choose files to send to \(phoneName)."
        NSApp.activate()
        guard panel.runModal() == .OK else { return }
        sendFiles(panel.urls)
    }

    func cancelTransfer(_ id: String) { Task { await transferManager.cancel(id: id) } }
    func retryTransfer(_ id: String) { Task { await transferManager.retry(id: id) } }

    func revealReceived(_ record: TransferRecord) {
        if let url = record.savedURL { NSWorkspace.shared.activateFileViewerSelecting([url]) }
    }

    func openReceived(_ record: TransferRecord) {
        if let url = record.savedURL { NSWorkspace.shared.open(url) }
    }

    private func handle(_ event: TransferEvent) async {
        switch event {
        case .received(let record): await notifier.notifyReceived(record, from: pairedDevice?.name ?? "your phone")
        }
    }
```

- [ ] **Step 6: Build**

Run: `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build`
Expected: BUILD SUCCEEDED, with no compiler warnings (the `appintentsmetadataprocessor` log line is tool noise). Fix Swift 6 isolation errors at their source, without `@unchecked` or `nonisolated(unsafe)`, and list each fix in the commit body.

Then confirm the entitlements made it into the signed app:

```bash
codesign -d --entitlements - mac/build/Build/Products/Debug/MacDroid.app 2>/dev/null | grep -E "user-selected|downloads|bookmarks"
```

Expected: all three keys are listed.

- [ ] **Step 7: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 4: the Mac app runs `TransferManager` (security-scoped source bookmarks, Received-files settings with a folder bookmark and a cancellable save panel, notifications with Show in Finder/Open); entitlements for user-selected files, Downloads and app-scope bookmarks; version 0.3.0. Verified: app build and the entitlements check."
  - **ARCHITECTURE.md:** in the Mac app paragraph, add "`Files/`: `FilesSettings` (save folder, ask-each-time, save panel), `SecurityScopedSourceAccess`, `ReceivedNotifier`. `AppModel` owns the `TransferManager` and exposes `transfers` plus the file actions."
  - **DEV-GUIDE.md:** under Gotchas, add "**Sandbox file access:** received files go to `~/Downloads/MacDroid` (Downloads entitlement) or a folder chosen in Settings (security-scoped bookmark, kept open while the app runs). Outgoing files are remembered as read-only security-scoped bookmarks so a resume survives a relaunch."

```bash
git add mac docs/handoff
git commit -m "feat(mac): run file transfers in the app (settings, notifications, entitlements)"
```

---
### Task 5: Mac Files page and the Received-files settings section

**Files:**
- Create: `mac/MacDroid/Views/Files/FilesView.swift`, `mac/MacDroid/Views/Files/TransferRow.swift`
- Modify: `mac/MacDroid/SidebarSection.swift`, `mac/MacDroid/Views/MainWindow.swift`, `mac/MacDroid/Views/Settings/GeneralSettingsView.swift`

**Interfaces:**
- Consumes: Task 4's `AppModel` and `FilesSettings` API; Task 2's `TransferPresentation.row`.
- Produces: `TransferRow(record:rate:model:)`, which the popover reuses in Task 6.

- [ ] **Step 1: Create `Views/Files/TransferRow.swift`**

```swift
import MacDroidCore
import SwiftUI

/// One transfer: direction, name, progress or status, and its actions (cancel, Show in Finder/Open, Retry).
struct TransferRow: View {
    let record: TransferRecord
    let rate: Double?
    let model: AppModel
    var compact = false

    var body: some View {
        let row = TransferPresentation.row(record, rate: rate)
        HStack(spacing: 10) {
            Image(systemName: record.direction == .toPhone ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                .font(.system(size: compact ? 16 : 20))
                .foregroundStyle(row.isError ? Color.red : Color.accentColor)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(row.title).lineLimit(1).truncationMode(.middle)
                    if !compact { Text(row.directionLabel).foregroundStyle(.secondary) }
                }
                if let progress = row.progress { ProgressView(value: progress).controlSize(.small) }
                Text(row.detail)
                    .font(.caption)
                    .foregroundStyle(row.isError ? Color.red : Color.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if row.canOpen {
                Button(compact ? "Show" : "Show in Finder") { model.revealReceived(record) }
                if !compact { Button("Open") { model.openReceived(record) } }
            }
            if row.canRetry { Button("Retry") { model.retryTransfer(record.id) } }
            if row.canCancel {
                Button { model.cancelTransfer(record.id) } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .help("Cancel")
            }
        }
        .controlSize(.small)
        .padding(.vertical, compact ? 2 : 4)
    }
}
```

- [ ] **Step 2: Create `Views/Files/FilesView.swift`**

```swift
import MacDroidCore
import SwiftUI

/// The Files page: a drop zone to send, transfers in progress, and recent history (spec §3.2).
struct FilesView: View {
    let model: AppModel
    @State private var targeted = false

    var body: some View {
        let snapshot = model.transfers
        VStack(spacing: 0) {
            if model.files.folderProblem {
                BannerView(kind: .warning, message: "MacDroid can't reach the folder you chose for received files. Choose it again in Settings.")
            }
            if let error = model.transientError { BannerView(kind: .error, message: error) }
            List {
                Section { dropZone }
                if !snapshot.active.isEmpty {
                    Section("In progress") {
                        ForEach(snapshot.active) { TransferRow(record: $0, rate: snapshot.rates[$0.id], model: model) }
                    }
                }
                Section("Recent") {
                    if snapshot.history.isEmpty {
                        Text("Files you send or receive appear here.").foregroundStyle(.secondary)
                    } else {
                        ForEach(snapshot.history) { TransferRow(record: $0, rate: nil, model: model) }
                    }
                }
            }
            .listStyle(.inset)
        }
        .navigationTitle("Files")
    }

    private var dropZone: some View {
        let enabled = model.canSendFiles
        return VStack(spacing: 8) {
            Image(systemName: "arrow.down.doc")
                .font(.system(size: 28))
                .foregroundStyle(enabled ? Color.accentColor : Color.secondary)
            Text(enabled ? "Drop files here to send to \(model.phoneName)" : "Phone not connected").font(.headline)
            Button("Send Files…") { model.pickFilesToSend() }.disabled(!enabled)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .background(RoundedRectangle(cornerRadius: 12).fill(targeted ? Color.accentColor.opacity(0.15) : Color.secondary.opacity(0.06)))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(targeted ? Color.accentColor : Color.secondary.opacity(0.3), style: StrokeStyle(lineWidth: 1.5, dash: [6]))
        )
        .dropDestination(for: URL.self) { urls, _ in
            guard model.canSendFiles else { return false }
            model.sendFiles(urls)
            return true
        } isTargeted: { targeted = $0 && model.canSendFiles }
    }
}
```

- [ ] **Step 3: Make Files live** (`SidebarSection.swift` and `MainWindow.swift`)

In `SidebarSection.comingSoonDescription`, delete the `case .files:` line, so Files is available.

In `MainWindow.detail(for:)`:

```swift
        case .phone: PhoneView(model: model)
        case .files: FilesView(model: model)
        case .notifications, .clipboard: ComingSoonView(section: section)
        case .general: GeneralSettingsView(files: model.files)
```

- [ ] **Step 4: Add the Received-files section** (`GeneralSettingsView.swift`)

Add the property `@Bindable var files: FilesSettings`, then a second `Section` inside the `Form`, after the existing one:

```swift
            Section {
                LabeledContent("Save received files to") {
                    HStack {
                        Text(files.folder.lastPathComponent).foregroundStyle(.secondary)
                        Button("Choose…") { files.chooseFolder() }
                        if !files.usesDefaultFolder { Button("Use Downloads") { files.useDefaultFolder() } }
                    }
                }
                Toggle("Ask where to save each time", isOn: $files.askEachTime)
                if files.folderProblem {
                    Label("MacDroid can't reach the folder you chose for received files. Choose it again in Settings.",
                          systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            } header: {
                Text("Received files")
            } footer: {
                Text("Files from your phone save automatically. Partly received files end in “.macdroid-part” until they're checked.")
                    .foregroundStyle(.secondary)
            }
```

- [ ] **Step 5: Build**

Run the Mac app build gate. Expected: BUILD SUCCEEDED, no compiler warnings.

- [ ] **Step 6: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 5: Mac Files page (drop zone, Send Files…, In progress, Recent with Show in Finder/Open/Retry/cancel) and a Received-files section in Settings → General. Verified: app build."
  - **ARCHITECTURE.md:** in "Extension points → UI", note that Files is live: `MainWindow` shows `FilesView`.
  - **STATUS.md:** under "Known limitations", change the line about placeholder tabs to say that Notifications and Clipboard are placeholders, and Files is live on `feat/file-transfer`.

```bash
git add mac docs/handoff
git commit -m "feat(mac): Files page and received-files settings"
```

---

### Task 6: Mac popover — design A (Control Center style)

**Files:**
- Create: `mac/MacDroid/Views/Popover/BatteryRing.swift`, `PopoverTile.swift`, `PopoverDropDelegate.swift`
- Rewrite: `mac/MacDroid/Views/MenuBarPopover.swift`

**Interfaces:**
- Consumes: `AppModel` (`status`, `phoneStatus`, `pairedDevice`, `warnings`, `transfers`, `canSendFiles`, `phoneName`, `sendFiles`, `pickFilesToSend`, `showWindow`, `requestPairing`); `TransferRow` (Task 5, `compact: true`); `TransferPresentation.popoverItems`, `dropSummary`.
- Produces: nothing used later.

- [ ] **Step 1: Create the pieces**

`Views/Popover/BatteryRing.swift`:

```swift
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
```

`Views/Popover/PopoverTile.swift`:

```swift
import SwiftUI

/// A large Control Center-style button: symbol, title and a small hint underneath.
struct PopoverTile: View {
    let symbol: String
    let title: String
    let hint: String
    var enabled = true
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: symbol).font(.system(size: 18)).foregroundStyle(enabled ? Color.accentColor : Color.secondary)
                Text(title).font(.callout.weight(.semibold))
                Text(hint).font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.secondary.opacity(hovering && enabled ? 0.18 : 0.1)))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .onHover { hovering = $0 }
    }
}
```

`Views/Popover/PopoverDropDelegate.swift`:

```swift
import MacDroidCore
import SwiftUI
import UniformTypeIdentifiers

/// What the drop overlay shows while files are dragged over the popover.
struct DropPreview: Equatable {
    var isTargeted = false
    var count = 0
    var totalBytes: Int64 = 0
}

/// The whole popover is a drop target (spec §3.2 design A). It counts the dragged files and adds up their sizes for the overlay.
struct PopoverDropDelegate: DropDelegate {
    let model: AppModel
    @Binding var preview: DropPreview

    func validateDrop(info: DropInfo) -> Bool { model.canSendFiles && info.hasItemsConforming(to: [.fileURL]) }

    func dropEntered(info: DropInfo) {
        guard validateDrop(info: info) else { return }
        let providers = info.itemProviders(for: [.fileURL])
        preview = DropPreview(isTargeted: true, count: providers.count, totalBytes: 0)
        Task { @MainActor in
            let urls = await Self.urls(from: providers)
            let total = urls.reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0) }
            if preview.isTargeted { preview.totalBytes = total }
        }
    }

    func dropExited(info: DropInfo) { preview = DropPreview() }

    func performDrop(info: DropInfo) -> Bool {
        preview = DropPreview()
        guard validateDrop(info: info) else { return false }
        let providers = info.itemProviders(for: [.fileURL])
        Task { @MainActor in model.sendFiles(await Self.urls(from: providers)) }
        return true
    }

    @MainActor
    static func urls(from providers: [NSItemProvider]) async -> [URL] {
        var urls: [URL] = []
        for provider in providers {
            let url: URL? = await withCheckedContinuation { continuation in
                _ = provider.loadObject(ofClass: URL.self) { url, _ in continuation.resume(returning: url) }
            }
            if let url { urls.append(url) }
        }
        return urls
    }
}
```

- [ ] **Step 2: Rewrite `MenuBarPopover.swift`**

```swift
import AppKit
import MacDroidCore
import SwiftUI

/// Design A: device card with battery ring, Send/Open tiles, up to two transfer rows, and small Settings/Quit buttons.
/// The whole popover accepts dropped files.
struct MenuBarPopover: View {
    let model: AppModel
    @State private var drop = DropPreview()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if model.pairedDevice == nil {
                notPaired
            } else {
                deviceCard
                tiles
                transfers
            }
            ForEach(model.warnings, id: \.self) { warning in
                Label(warning, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(Color.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
            footer
        }
        .padding(12)
        .frame(width: 300)
        .overlay { if drop.isTargeted { dropOverlay } }
        .onDrop(of: [.fileURL], delegate: PopoverDropDelegate(model: model, preview: $drop))
    }

    private var notPaired: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("No phone paired").font(.headline)
            Text("Pair your Android phone to get started.").font(.callout).foregroundStyle(.secondary)
            Button {
                model.requestPairing()
            } label: {
                Text("Pair a Phone…").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    private var deviceCard: some View {
        let status = model.phoneStatus
        let peer: PeerInfo? = if case .connected(let peer) = model.status { peer } else { nil }
        return HStack(spacing: 12) {
            BatteryRing(level: peer?.battery, charging: peer?.charging ?? false)
            VStack(alignment: .leading, spacing: 2) {
                Text(status.title).font(.headline)
                HStack(spacing: 5) {
                    StatusDot(tone: status.tone)
                    Text(status.subtitle).font(.callout).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.secondary.opacity(0.1)))
    }

    private var tiles: some View {
        HStack(spacing: 8) {
            PopoverTile(symbol: "arrow.up.doc", title: "Send Files", hint: model.canSendFiles ? "or drop here" : "Phone not connected",
                        enabled: model.canSendFiles) { model.pickFilesToSend() }
            PopoverTile(symbol: "macwindow", title: "Open MacDroid", hint: "⌘O") { model.showWindow(.phone) }
                .keyboardShortcut("o")
        }
    }

    @ViewBuilder
    private var transfers: some View {
        let items = TransferPresentation.popoverItems(model.transfers)
        if !items.isEmpty {
            VStack(spacing: 4) {
                ForEach(items) { TransferRow(record: $0, rate: model.transfers.rates[$0.id], model: model, compact: true) }
            }
        }
    }

    private var footer: some View {
        HStack {
            Button { model.showWindow(.general) } label: { Image(systemName: "gearshape") }
                .help("Settings")
            Spacer()
            Button { NSApp.terminate(nil) } label: { Image(systemName: "power") }
                .help("Quit MacDroid")
                .keyboardShortcut("q")
        }
        .buttonStyle(.borderless)
        .foregroundStyle(.secondary)
    }

    private var dropOverlay: some View {
        RoundedRectangle(cornerRadius: 12)
            .fill(Color.accentColor.opacity(0.9))
            .overlay {
                VStack(spacing: 6) {
                    Image(systemName: "arrow.down.doc.fill").font(.system(size: 28))
                    Text("Drop to send to \(model.phoneName)").font(.headline)
                    Text(TransferPresentation.dropSummary(count: drop.count, totalBytes: drop.totalBytes)).font(.callout)
                }
                .foregroundStyle(.white)
            }
            .padding(4)
            .allowsHitTesting(false)
    }
}
```

- [ ] **Step 3: Build**

Run the Mac app build gate. Expected: BUILD SUCCEEDED, no compiler warnings. If Swift 6 objects to sending `NSItemProvider` across isolation, keep `urls(from:)` `@MainActor` (as written) and resolve each error at its source.

- [ ] **Step 4: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 6: Mac popover in design A: device card with battery ring, Send Files and Open MacDroid tiles, up to two transfer rows, Settings/Quit icons, and the whole popover as a drop target with a 'Drop to send' overlay (count and size). Verified: app build."
  - **ARCHITECTURE.md:** in the Mac app paragraph, change the popover description to "design A popover (`Views/Popover/`), whole-popover drop target".

```bash
git add mac docs/handoff
git commit -m "feat(mac): Control Center-style popover with drop-to-send"
```

---
### Task 7: Android pure helpers — `TransferPresentation`, `SaveMode`, `ShareSource`, `ShareDecision`, `FolderLabel`

Every rule the Android UI follows, without Android types, so it runs as plain JVM unit tests.

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/ui/presentation/TransferPresentation.kt`, `FolderLabel.kt`, `ShareDecision.kt`
- Create: `android/app/src/main/java/dev/macdroid/app/platform/SaveMode.kt`, `ShareSource.kt`
- Test: create `android/app/src/test/java/dev/macdroid/app/TransferPresentationTest.kt`, `SaveModeTest.kt`, `ShareSourceTest.kt`, `ShareDecisionTest.kt`, `FolderLabelTest.kt`

**Interfaces:**
- Consumes: `dev.macdroid.core.transfer.TransferRecord`, `TransferSnapshot`, `Phase`, `Direction`, `Failure`, `TransferText`.
- Produces:
  - `data class TransferRowModel(title, directionLabel, detail, progress: Float?, isError, canCancel, canRetry, canOpen)`
  - `object TransferPresentation`:
    - `row(record, rate): TransferRowModel`
    - `recent(snapshot, limit = 3): List<TransferRecord>`
    - `progressSummary(snapshot): ProgressSummary?`, with `data class ProgressSummary(val title: String, val percent: Int)`
  - `enum class SaveMode { Default, Folder, Ask }`, `data class SaveChoice(val mode: SaveMode, val folderProblem: Boolean)`, and `SaveMode.choose(askEachTime: Boolean, treeUri: String?, treeWritable: Boolean): SaveChoice`
  - `enum class ShareSource { Keep, Copy }` with `ShareSource.plan(persisted: Boolean, size: Long, copyLimit: Long): ShareSource`
  - `enum class ShareDecision { Send, ConnectFirst, Nothing }` with `ShareDecision.decide(paired, connected, count)` and `ShareDecision.sendingText(count)`
  - `object FolderLabel` with `DEFAULT = "Download/MacDroid"` and `of(treeUri: String?): String`

- [ ] **Step 1: Write the failing tests**

`TransferPresentationTest.kt`:

```kotlin
package dev.macdroid.app

import dev.macdroid.app.ui.presentation.TransferPresentation
import dev.macdroid.core.transfer.Direction
import dev.macdroid.core.transfer.Failure
import dev.macdroid.core.transfer.Phase
import dev.macdroid.core.transfer.TransferRecord
import dev.macdroid.core.transfer.TransferSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferPresentationTest {
    private fun record(
        direction: Direction, phase: Phase, size: Long = 2_100_000_000, done: Long = 0, failure: Failure? = null,
        byPeer: Boolean = false, source: String? = null, saved: String? = null,
    ) = TransferRecord(id = "t", direction = direction, name = "clip.mp4", size = size, mime = "video/mp4", phase = phase,
        bytesDone = done, failure = failure, cancelledByPeer = byPeer, source = source, savedUri = saved, createdAt = 0, updatedAt = 0)

    @Test fun activeRowShowsProgressSpeedAndTimeLeft() {
        val row = TransferPresentation.row(record(Direction.ToMac, Phase.Active, done = 1_300_000_000), 48_000_000.0)
        assertEquals("→ Mac", row.directionLabel)
        assertEquals("1.3 of 2.1 GB · 48 MB/s · 17 s left", row.detail)
        assertEquals(1_300_000_000f / 2_100_000_000f, row.progress!!, 0.0001f)
        assertTrue(row.canCancel)
    }

    @Test fun waitingStates() {
        assertEquals("Waiting to send", TransferPresentation.row(record(Direction.ToMac, Phase.Queued), null).detail)
        assertEquals("Waiting for your Mac", TransferPresentation.row(record(Direction.ToMac, Phase.Offered), null).detail)
        assertEquals("Waiting for you to choose where to save", TransferPresentation.row(record(Direction.FromMac, Phase.Offered), null).detail)
        assertEquals("Paused — waiting for connection", TransferPresentation.row(record(Direction.FromMac, Phase.Paused), null).detail)
        assertEquals("Checking…", TransferPresentation.row(record(Direction.FromMac, Phase.Verifying), null).detail)
        assertEquals("← Mac", TransferPresentation.row(record(Direction.FromMac, Phase.Active), null).directionLabel)
    }

    @Test fun finishedRows() {
        val received = TransferPresentation.row(record(Direction.FromMac, Phase.Done, size = 3_145_728, saved = "content://x"), null)
        assertEquals("Received · 3.1 MB", received.detail)
        assertTrue(received.canOpen)
        assertFalse(received.canCancel)
        assertNull(received.progress)
        assertEquals("Sent · 512 bytes", TransferPresentation.row(record(Direction.ToMac, Phase.Done, size = 512), null).detail)
        assertEquals("Cancelled on your Mac", TransferPresentation.row(record(Direction.ToMac, Phase.Cancelled, byPeer = true), null).detail)
        assertEquals("Cancelled on your phone", TransferPresentation.row(record(Direction.ToMac, Phase.Cancelled), null).detail)
    }

    @Test fun failures() {
        val corrupted = TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.ChecksumMismatch, source = "content://a"), null)
        assertEquals("Transfer failed — file was corrupted in transit", corrupted.detail)
        assertTrue(corrupted.isError && corrupted.canRetry)
        assertFalse(TransferPresentation.row(record(Direction.FromMac, Phase.Failed, failure = Failure.Io), null).canRetry)
        assertEquals("Not enough space on your Mac", TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.NoSpace), null).detail)
        assertEquals("Not enough space on this phone", TransferPresentation.row(record(Direction.FromMac, Phase.Failed, failure = Failure.NoSpace), null).detail)
        assertEquals("Declined on your Mac", TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.Declined), null).detail)
        assertEquals("Not saved", TransferPresentation.row(record(Direction.FromMac, Phase.Failed, failure = Failure.Declined), null).detail)
        assertEquals("The original file is no longer available", TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.SourceUnavailable), null).detail)
        assertEquals("Expired — not finished within 24 hours", TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.Expired), null).detail)
        assertEquals("Transfer failed", TransferPresentation.row(record(Direction.ToMac, Phase.Failed, failure = Failure.Io), null).detail)
    }

    @Test fun progressSummary() {
        assertNull(TransferPresentation.progressSummary(TransferSnapshot()))
        val sending = TransferSnapshot(active = listOf(record(Direction.ToMac, Phase.Active, size = 100, done = 25), record(Direction.ToMac, Phase.Queued, size = 100)))
        assertEquals(TransferPresentation.ProgressSummary("Sending 2 files to your Mac", 12), TransferPresentation.progressSummary(sending))
        val receiving = TransferSnapshot(active = listOf(record(Direction.FromMac, Phase.Active, size = 10, done = 5)))
        assertEquals(TransferPresentation.ProgressSummary("Receiving 1 file from your Mac", 50), TransferPresentation.progressSummary(receiving))
        val both = TransferSnapshot(active = listOf(record(Direction.FromMac, Phase.Active, size = 0), record(Direction.ToMac, Phase.Active, size = 0)))
        assertEquals(TransferPresentation.ProgressSummary("Transferring 2 files", 0), TransferPresentation.progressSummary(both))
    }

    @Test fun recentIsTheNewestThree() {
        val history = (1..5).map { record(Direction.ToMac, Phase.Done).copy(id = "t$it") }
        assertEquals(listOf("t1", "t2", "t3"), TransferPresentation.recent(TransferSnapshot(history = history)).map { it.id })
    }
}
```

`SaveModeTest.kt`:

```kotlin
package dev.macdroid.app

import dev.macdroid.app.platform.SaveChoice
import dev.macdroid.app.platform.SaveMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveModeTest {
    @Test fun defaultIsMediaStoreDownloads() = assertEquals(SaveChoice(SaveMode.Default, false), SaveMode.choose(false, null, false))
    @Test fun askEachTimeWins() = assertEquals(SaveChoice(SaveMode.Ask, false), SaveMode.choose(true, "content://tree", true))
    @Test fun chosenFolderIsUsedWhileWritable() = assertEquals(SaveChoice(SaveMode.Folder, false), SaveMode.choose(false, "content://tree", true))

    // Review Focus #3
    @Test fun revokedFolderFallsBackToAskWithProblem() = assertEquals(SaveChoice(SaveMode.Ask, true), SaveMode.choose(false, "content://tree", false))
}
```

`ShareSourceTest.kt`:

```kotlin
package dev.macdroid.app

import dev.macdroid.app.platform.ShareSource
import org.junit.Assert.assertEquals
import org.junit.Test

// Review Focus #4
class ShareSourceTest {
    private val limit = 100L * 1024 * 1024

    @Test fun persistableUrisAreKept() = assertEquals(ShareSource.Keep, ShareSource.plan(persisted = true, size = 5, copyLimit = limit))
    @Test fun smallUnpersistableFilesAreCopied() = assertEquals(ShareSource.Copy, ShareSource.plan(persisted = false, size = limit, copyLimit = limit))
    @Test fun largeUnpersistableFilesAreReadInPlace() = assertEquals(ShareSource.Keep, ShareSource.plan(persisted = false, size = limit + 1, copyLimit = limit))
}
```

`ShareDecisionTest.kt`:

```kotlin
package dev.macdroid.app

import dev.macdroid.app.ui.presentation.ShareDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareDecisionTest {
    @Test fun connectedSends() = assertEquals(ShareDecision.Send, ShareDecision.decide(paired = true, connected = true, count = 2))

    // Review Focus #2
    @Test fun notConnectedAsksToConnectFirst() {
        assertEquals(ShareDecision.ConnectFirst, ShareDecision.decide(paired = true, connected = false, count = 1))
        assertEquals(ShareDecision.ConnectFirst, ShareDecision.decide(paired = false, connected = false, count = 1))
    }

    @Test fun nothingSharedDoesNothing() = assertEquals(ShareDecision.Nothing, ShareDecision.decide(paired = true, connected = true, count = 0))

    @Test fun toastText() {
        assertEquals("Sending 1 file to your Mac", ShareDecision.sendingText(1))
        assertEquals("Sending 3 files to your Mac", ShareDecision.sendingText(3))
    }
}
```

`FolderLabelTest.kt`:

```kotlin
package dev.macdroid.app

import dev.macdroid.app.ui.presentation.FolderLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderLabelTest {
    @Test fun defaultFolder() = assertEquals("Download/MacDroid", FolderLabel.of(null))
    @Test fun externalStorageTree() =
        assertEquals("Documents/MacDroid", FolderLabel.of("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMacDroid"))
    @Test fun storageRoot() = assertEquals("Internal storage", FolderLabel.of("content://com.android.externalstorage.documents/tree/primary%3A"))
    @Test fun otherProvider() = assertEquals("Work", FolderLabel.of("content://com.example.docs/tree/Work"))
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: compile errors (types not defined).

- [ ] **Step 3: Implement**

`ui/presentation/TransferPresentation.kt`:

```kotlin
package dev.macdroid.app.ui.presentation

import dev.macdroid.core.transfer.Direction
import dev.macdroid.core.transfer.Failure
import dev.macdroid.core.transfer.Phase
import dev.macdroid.core.transfer.TransferRecord
import dev.macdroid.core.transfer.TransferSnapshot
import dev.macdroid.core.transfer.TransferText

data class TransferRowModel(
    val title: String,
    val directionLabel: String,
    val detail: String,
    /** 0…1 while bytes are moving or paused; null when there's no bar to show. */
    val progress: Float?,
    val isError: Boolean,
    val canCancel: Boolean,
    val canRetry: Boolean,
    val canOpen: Boolean,
)

/** Transfer → text for the Files tab, Home and notifications. Mirrors the Mac's TransferPresentation with "phone"/"Mac" swapped. */
object TransferPresentation {
    data class ProgressSummary(val title: String, val percent: Int)

    fun row(record: TransferRecord, rate: Double?): TransferRowModel {
        val toMac = record.direction == Direction.ToMac
        val fraction = if (record.size > 0) (record.bytesDone.toFloat() / record.size).coerceIn(0f, 1f) else null
        val canRetry = toMac && record.source != null
        val base = TransferRowModel(
            title = record.name, directionLabel = if (toMac) "→ Mac" else "← Mac", detail = "", progress = null,
            isError = false, canCancel = !record.isFinished, canRetry = false, canOpen = false,
        )
        return when (record.phase) {
            Phase.Queued -> base.copy(detail = "Waiting to send")
            Phase.Offered -> base.copy(detail = if (toMac) "Waiting for your Mac" else "Waiting for you to choose where to save")
            Phase.Active -> base.copy(detail = TransferText.line(record.bytesDone, record.size, rate), progress = fraction)
            Phase.Paused -> base.copy(detail = "Paused — waiting for connection", progress = fraction)
            Phase.Verifying -> base.copy(detail = "Checking…", progress = 1f)
            Phase.Done -> base.copy(
                detail = "${if (toMac) "Sent" else "Received"} · ${TransferText.bytes(record.size)}",
                canOpen = !toMac && record.savedUri != null,
            )
            Phase.Failed -> base.copy(detail = failureText(record.failure, toMac), isError = true, canRetry = canRetry)
            Phase.Cancelled -> base.copy(
                detail = if (record.cancelledByPeer) "Cancelled on your Mac" else "Cancelled on your phone",
                canRetry = canRetry,
            )
        }
    }

    fun recent(snapshot: TransferSnapshot, limit: Int = 3): List<TransferRecord> = snapshot.history.take(limit)

    /** The ongoing notification: what's moving and how far along, overall. Null when nothing is active. */
    fun progressSummary(snapshot: TransferSnapshot): ProgressSummary? {
        val active = snapshot.active
        if (active.isEmpty()) return null
        val count = active.size
        val files = if (count == 1) "1 file" else "$count files"
        val title = when {
            active.all { it.direction == Direction.ToMac } -> "Sending $files to your Mac"
            active.all { it.direction == Direction.FromMac } -> "Receiving $files from your Mac"
            else -> "Transferring $files"
        }
        val total = active.sumOf { it.size }
        val percent = if (total > 0) (active.sumOf { it.bytesDone } * 100 / total).toInt() else 0
        return ProgressSummary(title, percent)
    }

    private fun failureText(failure: Failure?, toMac: Boolean): String = when (failure) {
        Failure.ChecksumMismatch -> "Transfer failed — file was corrupted in transit"
        Failure.NoSpace -> if (toMac) "Not enough space on your Mac" else "Not enough space on this phone"
        Failure.Declined -> if (toMac) "Declined on your Mac" else "Not saved"
        Failure.SourceUnavailable -> "The original file is no longer available"
        Failure.Expired -> "Expired — not finished within 24 hours"
        Failure.Io, Failure.Invalid, null -> "Transfer failed"
    }
}
```

`ui/presentation/FolderLabel.kt`:

```kotlin
package dev.macdroid.app.ui.presentation

import java.net.URLDecoder

/** A readable name for the save folder: the default, or the path inside a chosen SAF tree. */
object FolderLabel {
    const val DEFAULT = "Download/MacDroid"

    fun of(treeUri: String?): String {
        if (treeUri == null) return DEFAULT
        val encoded = treeUri.substringAfter("/tree/", missingDelimiterValue = treeUri.substringAfterLast('/'))
        val decoded = URLDecoder.decode(encoded, "UTF-8") // the Charset overload needs API 33
        val path = if (':' in decoded) decoded.substringAfter(':') else decoded
        return path.ifEmpty { "Internal storage" }
    }
}
```

`ui/presentation/ShareDecision.kt`:

```kotlin
package dev.macdroid.app.ui.presentation

/** What the share target does (spec §4.2): send silently when connected, otherwise ask to connect first. */
enum class ShareDecision {
    Send, ConnectFirst, Nothing;

    companion object {
        fun decide(paired: Boolean, connected: Boolean, count: Int): ShareDecision = when {
            count == 0 -> Nothing
            !paired || !connected -> ConnectFirst
            else -> Send
        }

        fun sendingText(count: Int) = if (count == 1) "Sending 1 file to your Mac" else "Sending $count files to your Mac"
    }
}
```

`platform/SaveMode.kt`:

```kotlin
package dev.macdroid.app.platform

/** Where files from the Mac go: MediaStore Downloads, a SAF folder the user chose, or a prompt for each file. */
enum class SaveMode {
    Default, Folder, Ask;

    companion object {
        /** A chosen folder whose grant is gone falls back to asking, and flags the problem for a banner (spec §5). */
        fun choose(askEachTime: Boolean, treeUri: String?, treeWritable: Boolean): SaveChoice = when {
            askEachTime -> SaveChoice(Ask, folderProblem = false)
            treeUri != null && treeWritable -> SaveChoice(Folder, folderProblem = false)
            treeUri != null -> SaveChoice(Ask, folderProblem = true)
            else -> SaveChoice(Default, folderProblem = false)
        }
    }
}

data class SaveChoice(val mode: SaveMode, val folderProblem: Boolean)
```

`platform/ShareSource.kt`:

```kotlin
package dev.macdroid.app.platform

/**
 * How to keep reading a file the user shared (spec §4.2, ruling 2). Keep the URI when access persists. Otherwise copy
 * small files to cache, and read large ones in place while the service holds the temporary grant
 * (copying a multi-GB video would double the storage used).
 */
enum class ShareSource {
    Keep, Copy;

    companion object {
        fun plan(persisted: Boolean, size: Long, copyLimit: Long): ShareSource =
            if (!persisted && size <= copyLimit) Copy else Keep
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL.

- [ ] **Step 5: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 7: Android pure helpers (`TransferPresentation`, `SaveMode`, `ShareSource`, `ShareDecision`, `FolderLabel`) with unit tests. Verified: `:app:testDebugUnitTest`."

```bash
git add android/app docs/handoff
git commit -m "feat(android): transfer presentation and file-handling rules"
```

---
### Task 8: Android platform — save targets, source opener and engine wiring

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/platform/FilesPrefs.kt`, `MediaStoreSaveTarget.kt`, `TreeSaveTarget.kt`, `AskSaveTarget.kt`, `SavePrompter.kt`, `SaveTargets.kt`, `ContentSourceOpener.kt`
- Create: `android/app/src/main/java/dev/macdroid/app/service/TransferNotifications.kt` (channels and the "where to save?" prompt; Task 9 adds the rest)
- Create: `android/app/src/main/java/dev/macdroid/app/ui/files/SaveAsActivity.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/MacDroidApp.kt` (`AppGraph`), `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle.kts`

**Interfaces:**
- Consumes: `SaveMode.choose` (Task 7); core `SaveTarget`, `PartFile`, `SourceOpener`, `TransferEngine`, `TransferClient`, `FileEndpoint`, `JsonFileTransferStore`, `FileNaming`.
- Produces (for Tasks 9–12):
  - `AppGraph`: `appScope`, `filesPrefs: FilesPrefs`, `savePrompter: SavePrompter`, `saveTargets: SaveTargets`, `transferEngine: TransferEngine` (already started)
  - `FilesPrefs`: `askEachTime`, `askEachTimeFlow`, `treeUri`, `treeUriFlow`, `filesStepDone`
  - `SaveTargets`: `folderProblem: StateFlow<Boolean>`, `current(): SaveTarget`, `reopen(handle: String): PartFile?`
  - `SavePrompter`: `ask(transferId, name, mime): Uri?` (suspends), `answer(transferId, uri)`
  - `TransferNotifications`: `PROGRESS_CHANNEL`, `FILES_CHANNEL`, `createChannels(context)`, `askWhereToSave(context, transferId, name, mime)`, `cancelAsk(context, transferId)`

- [ ] **Step 1: Version** (`android/app/build.gradle.kts`): set `versionCode = 3` and `versionName = "0.3.0"`.

- [ ] **Step 2: Create `platform/FilesPrefs.kt`**

```kotlin
package dev.macdroid.app.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Files: ask-each-time and the chosen SAF folder (null means Download/MacDroid). */
class FilesPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("files", Context.MODE_PRIVATE)
    private val _askEachTime = MutableStateFlow(prefs.getBoolean("askEachTime", false))
    private val _treeUri = MutableStateFlow(prefs.getString("treeUri", null))
    val askEachTimeFlow: StateFlow<Boolean> = _askEachTime.asStateFlow()
    val treeUriFlow: StateFlow<String?> = _treeUri.asStateFlow()

    var askEachTime: Boolean
        get() = _askEachTime.value
        set(value) {
            prefs.edit().putBoolean("askEachTime", value).apply()
            _askEachTime.value = value
        }

    var treeUri: String?
        get() = _treeUri.value
        set(value) {
            prefs.edit().putString("treeUri", value).apply()
            _treeUri.value = value
        }

    /** The optional setup step "Where should files from your Mac go?" was answered or skipped. */
    var filesStepDone: Boolean
        get() = prefs.getBoolean("filesStepDone", false)
        set(value) { prefs.edit().putBoolean("filesStepDone", value).apply() }
}
```

- [ ] **Step 3: Create the three save targets**

`platform/MediaStoreSaveTarget.kt`:

```kotlin
package dev.macdroid.app.platform

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import dev.macdroid.core.transfer.PartFile
import dev.macdroid.core.transfer.SaveTarget
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The default: Download/MacDroid through MediaStore, which needs no permission on API 29+ (spec §4.2).
 * The part file is a pending item (hidden from other apps) under the final name; MediaStore adds " (1)" on a clash.
 */
class MediaStoreSaveTarget(private val context: Context) : SaveTarget {
    override fun freeBytes(): Long? = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull()

    override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MacDroid")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        return MediaStorePart(context.contentResolver, uri)
    }
}

class MediaStorePart(private val resolver: ContentResolver, private val uri: Uri) : PartFile {
    companion object {
        const val PREFIX = "media:"

        fun reopen(context: Context, handle: String): PartFile? {
            val uri = Uri.parse(handle.removePrefix(PREFIX))
            return runCatching {
                requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")).close()
                MediaStorePart(context.contentResolver, uri)
            }.getOrNull()
        }
    }

    override val handle = PREFIX + uri
    override fun length(): Long = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: throw IOException("can't open $uri")
    override fun openRead(): InputStream = resolver.openInputStream(uri) ?: throw IOException("can't read $uri")
    override fun openAppend(): OutputStream = resolver.openOutputStream(uri, "wa") ?: throw IOException("can't write $uri")

    override fun finish(): String {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri.toString()
    }

    override fun delete() {
        runCatching { resolver.delete(uri, null, null) }
    }
}
```

`platform/TreeSaveTarget.kt`:

```kotlin
package dev.macdroid.app.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.macdroid.core.transfer.FileNaming
import dev.macdroid.core.transfer.PartFile
import dev.macdroid.core.transfer.SaveTarget
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A folder the user chose (SAF tree with a persisted grant). Part files are octet-stream, so the provider doesn't add an extension. */
class TreeSaveTarget(private val context: Context, private val tree: Uri) : SaveTarget {
    override fun freeBytes(): Long? = null

    override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? {
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = runCatching {
            DocumentsContract.createDocument(context.contentResolver, folder, "application/octet-stream", name + FileNaming.PART_SUFFIX)
        }.getOrNull() ?: return null
        return TreePart(context, tree, doc, name, mime)
    }
}

class TreePart(context: Context, private val tree: Uri, private val doc: Uri, private val name: String, private val mime: String) : PartFile {
    companion object {
        const val PREFIX = "tree:"

        fun reopen(context: Context, handle: String): PartFile? {
            val parts = handle.removePrefix(PREFIX).split('\n')
            if (parts.size != 4) return null
            val doc = Uri.parse(parts[1])
            val exists = runCatching {
                context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
            return if (exists) TreePart(context, Uri.parse(parts[0]), doc, parts[2], parts[3]) else null
        }
    }

    private val resolver = context.contentResolver
    override val handle = PREFIX + listOf(tree, doc, name, mime).joinToString("\n")
    override fun length(): Long = resolver.openFileDescriptor(doc, "r")?.use { it.statSize } ?: throw IOException("can't open $doc")
    override fun openRead(): InputStream = resolver.openInputStream(doc) ?: throw IOException("can't read $doc")
    override fun openAppend(): OutputStream = resolver.openOutputStream(doc, "wa") ?: throw IOException("can't write $doc")

    /** Renames the part to a free final name; providers that can't rename get a copy instead. */
    override fun finish(): String {
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val taken = childNames()
        val finalName = FileNaming.unique(name) { it in taken }
        runCatching { DocumentsContract.renameDocument(resolver, doc, finalName) }.getOrNull()?.let { return it.toString() }
        val target = DocumentsContract.createDocument(resolver, folder, mime, finalName) ?: throw IOException("can't create $finalName")
        (resolver.openInputStream(doc) ?: throw IOException("can't read $doc")).use { input ->
            (resolver.openOutputStream(target) ?: throw IOException("can't write $target")).use { input.copyTo(it, 1 shl 20) }
        }
        DocumentsContract.deleteDocument(resolver, doc)
        return target.toString()
    }

    private fun childNames(): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        } ?: emptySet()
    }

    override fun delete() {
        runCatching { DocumentsContract.deleteDocument(resolver, doc) }
    }
}
```

`platform/AskSaveTarget.kt`:

```kotlin
package dev.macdroid.app.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.macdroid.core.transfer.PartFile
import dev.macdroid.core.transfer.SaveTarget
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** "Ask where to save each time": the user picks a file with CreateDocument, and bytes are written straight into it. */
class AskSaveTarget(private val context: Context, private val prompter: SavePrompter) : SaveTarget {
    override fun freeBytes(): Long? = null

    override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? =
        prompter.ask(transferId, name, mime)?.let { DocumentPart(context, it) }
}

/** The user's chosen document is the part file; it already has its final name, so finishing changes nothing. */
class DocumentPart(context: Context, private val uri: Uri) : PartFile {
    companion object {
        const val PREFIX = "doc:"

        fun reopen(context: Context, handle: String): PartFile? {
            val uri = Uri.parse(handle.removePrefix(PREFIX))
            val writable = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            return if (writable) DocumentPart(context, uri) else null
        }
    }

    private val resolver = context.contentResolver
    override val handle = PREFIX + uri
    override fun length(): Long = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: throw IOException("can't open $uri")
    override fun openRead(): InputStream = resolver.openInputStream(uri) ?: throw IOException("can't read $uri")
    override fun openAppend(): OutputStream = resolver.openOutputStream(uri, "wa") ?: throw IOException("can't write $uri")
    override fun finish(): String = uri.toString()

    override fun delete() {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }
}
```

- [ ] **Step 4: Create the prompt plumbing**

`service/TransferNotifications.kt`:

```kotlin
package dev.macdroid.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.macdroid.app.R
import dev.macdroid.app.ui.files.SaveAsActivity

/** Notifications for file transfers: the ongoing progress, received files, and "where to save?" prompts. */
object TransferNotifications {
    const val PROGRESS_CHANNEL = "transfers"
    const val FILES_CHANNEL = "files"
    const val PROGRESS_ID = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(FILES_CHANNEL, "Received files", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** A file from the Mac needs a place ("Ask where to save each time"). Tapping opens the system save screen. */
    fun askWhereToSave(context: Context, transferId: String, name: String, mime: String) {
        createChannels(context)
        val open = PendingIntent.getActivity(
            context, transferId.hashCode(), SaveAsActivity.intent(context, transferId, name, mime),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, FILES_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Save “$name” from your Mac?")
            .setContentText("Tap to choose where to save it")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(askId(transferId), notification)
    }

    fun cancelAsk(context: Context, transferId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(askId(transferId))
    }

    private fun askId(transferId: String) = 1000 + (transferId.hashCode() and 0xffff)
}
```

`platform/SavePrompter.kt`:

```kotlin
package dev.macdroid.app.platform

import android.content.Context
import android.net.Uri
import dev.macdroid.app.service.TransferNotifications
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Lets a background transfer ask where to save. It posts a notification that opens [dev.macdroid.app.ui.files.SaveAsActivity].
 * The question waits for an answer with no timeout (ruling 6); cancelling the transfer cancels it.
 */
class SavePrompter(private val context: Context) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Uri?>>()

    suspend fun ask(transferId: String, name: String, mime: String): Uri? {
        val answer = CompletableDeferred<Uri?>()
        pending[transferId] = answer
        TransferNotifications.askWhereToSave(context, transferId, name, mime)
        try {
            return answer.await()
        } finally {
            pending.remove(transferId)
            TransferNotifications.cancelAsk(context, transferId)
        }
    }

    fun answer(transferId: String, uri: Uri?) {
        pending[transferId]?.complete(uri)
    }
}
```

`ui/files/SaveAsActivity.kt`:

```kotlin
package dev.macdroid.app.ui.files

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.macdroid.app.graph

/** An invisible activity that shows the system "save as" screen for one incoming file and hands the answer back. */
class SaveAsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME) ?: "file"
        val mime = intent.getStringExtra(EXTRA_MIME) ?: "application/octet-stream"
        val launcher = registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }
            graph.savePrompter.answer(id, uri)
            finish()
        }
        if (savedInstanceState == null) launcher.launch(name)
    }

    companion object {
        private const val EXTRA_ID = "transferId"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MIME = "mime"

        fun intent(context: Context, transferId: String, name: String, mime: String): Intent =
            Intent(context, SaveAsActivity::class.java)
                .putExtra(EXTRA_ID, transferId).putExtra(EXTRA_NAME, name).putExtra(EXTRA_MIME, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
```

- [ ] **Step 5: Create `platform/SaveTargets.kt` and `platform/ContentSourceOpener.kt`**

```kotlin
package dev.macdroid.app.platform

import android.content.Context
import android.net.Uri
import dev.macdroid.core.transfer.PartFile
import dev.macdroid.core.transfer.SaveTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Picks the save target from the Files settings, flags an unreachable chosen folder, and reopens part files by handle. */
class SaveTargets(private val context: Context, private val prefs: FilesPrefs, private val prompter: SavePrompter) {
    private val _folderProblem = MutableStateFlow(false)
    val folderProblem: StateFlow<Boolean> = _folderProblem.asStateFlow()

    fun current(): SaveTarget {
        val tree = prefs.treeUri
        val writable = tree != null && context.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isWritePermission }
        val choice = SaveMode.choose(prefs.askEachTime, tree, writable)
        _folderProblem.value = choice.folderProblem
        return when (choice.mode) {
            SaveMode.Default -> MediaStoreSaveTarget(context)
            SaveMode.Folder -> TreeSaveTarget(context, Uri.parse(tree))
            SaveMode.Ask -> AskSaveTarget(context, prompter)
        }
    }

    fun reopen(handle: String): PartFile? = when {
        handle.startsWith(MediaStorePart.PREFIX) -> MediaStorePart.reopen(context, handle)
        handle.startsWith(TreePart.PREFIX) -> TreePart.reopen(context, handle)
        handle.startsWith(DocumentPart.PREFIX) -> DocumentPart.reopen(context, handle)
        else -> null
    }
}
```

```kotlin
package dev.macdroid.app.platform

import android.content.ContentResolver
import android.net.Uri
import dev.macdroid.core.transfer.SourceOpener
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Opens files to send: content URIs from pickers and the share sheet, or paths of cache copies. */
class ContentSourceOpener(private val resolver: ContentResolver) : SourceOpener {
    override fun open(source: String): InputStream =
        if (source.startsWith("/")) File(source).inputStream()
        else resolver.openInputStream(Uri.parse(source)) ?: throw IOException("can't open $source")
}
```

- [ ] **Step 6: Wire `AppGraph`** (`MacDroidApp.kt`)

Add these properties after `connectionManager`, plus the imports they need (`dev.macdroid.app.platform.*`, `dev.macdroid.core.connection.ConnectionState`, `dev.macdroid.core.transfer.FileEndpoint`, `JsonFileTransferStore`, `TransferClient`, `TransferEngine`, `java.io.File`, `kotlinx.coroutines.CoroutineScope`, `Dispatchers`, `SupervisorJob`, `kotlinx.coroutines.flow.map`):

```kotlin
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val filesPrefs = FilesPrefs(context)
    val savePrompter = SavePrompter(context)
    val saveTargets = SaveTargets(context, filesPrefs, savePrompter)

    /** Runs for the whole process; transfers move while ConnectionService keeps the connection up (spec §4.2 "Background"). */
    val transferEngine = TransferEngine(
        scope = appScope,
        store = JsonFileTransferStore(File(context.filesDir, "transfers.json")),
        files = TransferClient(FileEndpoint(pairingStore, { identity }, httpClient)::current),
        sources = ContentSourceOpener(context.contentResolver),
        saveTarget = saveTargets::current,
        parts = saveTargets::reopen,
        send = connectionManager::send,
    ).also { it.start(connectionManager.incoming, connectionManager.state.map { state -> state is ConnectionState.Connected }) }
```

- [ ] **Step 7: Register the activity** (`AndroidManifest.xml`, inside `<application>`)

```xml
        <activity
            android:name=".ui.files.SaveAsActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
```

- [ ] **Step 8: Build and test**

Run: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, no `w:` warnings.

- [ ] **Step 9: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 8: Android save targets (MediaStore Download/MacDroid by default, a chosen SAF folder, or ask each time via a notification and CreateDocument), content-URI sources, and the `TransferEngine` running in `AppGraph`; version 0.3.0. Verified: Android gates."
  - **ARCHITECTURE.md:** in the `android/app` glue list, add "`platform/`: `SaveTargets` (MediaStore, SAF tree or ask-each-time `SaveTarget`s; reopens part files by handle prefix `media:`/`tree:`/`doc:`), `FilesPrefs`, `SavePrompter`, `ContentSourceOpener`. `AppGraph.transferEngine` starts with the process."

```bash
git add android docs/handoff
git commit -m "feat(android): save targets and engine wiring"
```

---
### Task 9: Android — sending helper, notifications and the connection service

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/platform/SendFiles.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/service/TransferNotifications.kt` (progress and received)
- Modify: `android/app/src/main/java/dev/macdroid/app/service/ConnectionService.kt`

**Interfaces:**
- Consumes: `ShareSource.plan` and `TransferPresentation.progressSummary` (Task 7); `AppGraph.transferEngine` (Task 8); core `OutgoingFile`, `TransferEvent`, `TransferRecord`, `FileNaming`, `TransferText`.
- Produces:
  - `SendFiles.enqueue(context, uris): Int` (suspend; describes, persists or copies, then queues the files)
  - `SendFiles.cleanUpCopies(context, history)`
  - `ConnectionService.sendFiles(context, uris)`, plus the actions `ACTION_SEND_FILES` and `ACTION_CANCEL_TRANSFERS`
  - `TransferNotifications.progress(context, summary)` and `TransferNotifications.received(context, record)`

- [ ] **Step 1: Create `platform/SendFiles.kt`**

```kotlin
package dev.macdroid.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dev.macdroid.app.graph
import dev.macdroid.core.transfer.FileNaming
import dev.macdroid.core.transfer.OutgoingFile
import dev.macdroid.core.transfer.TransferRecord
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns picked or shared URIs into queued transfers (spec §4.2). */
object SendFiles {
    const val COPY_LIMIT_BYTES = 100L * 1024 * 1024
    private const val COPIES_DIR = "outgoing"

    /** Returns how many files were queued (unreadable ones are skipped). */
    suspend fun enqueue(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val files = uris.mapNotNull { describe(context, it) }
        if (files.isNotEmpty()) context.graph.transferEngine.enqueue(files)
        files.size
    }

    private fun describe(context: Context, uri: Uri): OutgoingFile? {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        }
        if (size < 0) size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1
        if (size < 0) return null
        val displayName = name ?: uri.lastPathSegment ?: "file"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        // Keep access across process death where the provider allows it (Photo Picker and documents do).
        val persisted = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true }.getOrDefault(false)
        val source = when (ShareSource.plan(persisted, size, COPY_LIMIT_BYTES)) {
            ShareSource.Keep -> uri.toString()
            ShareSource.Copy -> copyToCache(context, uri, displayName) ?: return null
        }
        return OutgoingFile(source, displayName, size, mime)
    }

    private fun copyToCache(context: Context, uri: Uri, name: String): String? = runCatching {
        val dir = File(File(context.cacheDir, COPIES_DIR), UUID.randomUUID().toString()).apply { mkdirs() }
        val file = File(dir, FileNaming.sanitize(name))
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> file.outputStream().use { input.copyTo(it, 1 shl 20) } }
        file.path
    }.getOrNull()

    /** Deletes cache copies of transfers that are finished. */
    fun cleanUpCopies(context: Context, history: List<TransferRecord>) {
        val root = File(context.cacheDir, COPIES_DIR).path
        history.mapNotNull { it.source }.filter { it.startsWith(root) }.forEach { File(it).parentFile?.deleteRecursively() }
    }
}
```

- [ ] **Step 2: Add progress and received notifications** (append inside `object TransferNotifications`, with imports for `dev.macdroid.app.ui.MainActivity`, `dev.macdroid.app.ui.presentation.TransferPresentation`, `dev.macdroid.core.transfer.TransferRecord`, `dev.macdroid.core.transfer.TransferText`, `android.app.Notification`, `android.net.Uri`)

```kotlin
    /** The ongoing notification while transfers run, with Cancel (cancels them all). */
    fun progress(context: Context, summary: TransferPresentation.ProgressSummary): Notification {
        val cancel = PendingIntent.getService(
            context, 2, Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_CANCEL_TRANSFERS),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle(summary.title)
            .setProgress(100, summary.percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    /** "Received <name> from Mac", with Open. */
    fun received(context: Context, record: TransferRecord) {
        val uri = record.savedUri?.let(Uri::parse) ?: return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, record.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(context, record.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, FILES_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Received ${record.name} from Mac")
            .setContentText(TransferText.bytes(record.size))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Open", open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(record.id.hashCode(), notification)
    }
```

- [ ] **Step 3: Integrate with `ConnectionService`**

1. In `onCreate`, after `manager.start()` and the network watcher:

```kotlin
        TransferNotifications.createChannels(this)
        val engine = graph.transferEngine
        scope.launch {
            // At most one update a second; also clean up cache copies of finished transfers.
            engine.snapshot.conflate().collect { snapshot ->
                val summary = TransferPresentation.progressSummary(snapshot)
                if (summary == null) {
                    notifications.cancel(TransferNotifications.PROGRESS_ID)
                } else {
                    notifications.notify(TransferNotifications.PROGRESS_ID, TransferNotifications.progress(this@ConnectionService, summary))
                }
                withContext(Dispatchers.IO) { SendFiles.cleanUpCopies(this@ConnectionService, snapshot.history) }
                delay(1_000)
            }
        }
        scope.launch {
            engine.events.collect { event ->
                if (event is TransferEvent.Received) TransferNotifications.received(this@ConnectionService, event.record)
            }
        }
```

   (Move the `val notifications = getSystemService(NotificationManager::class.java)` line above this block if it currently comes after it.)

2. In `onStartCommand`, after the permission check and before the `ACTION_DISCONNECT` check:

```kotlin
        when (intent?.action) {
            ACTION_CANCEL_TRANSFERS -> graph.transferEngine.cancelAll()
            ACTION_SEND_FILES -> {
                // The share target's read grant came with this intent and lasts while the service runs.
                val clip = intent.clipData
                val uris = if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                scope.launch { SendFiles.enqueue(this@ConnectionService, uris) }
            }
        }
```

3. In the companion object:

```kotlin
        const val ACTION_SEND_FILES = "dev.macdroid.app.SEND_FILES"
        const val ACTION_CANCEL_TRANSFERS = "dev.macdroid.app.CANCEL_TRANSFERS"

        /** Hands shared URIs to the service with a read grant (spec §4.2, ruling 2). */
        fun sendFiles(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val clip = ClipData.newRawUri("files", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            val intent = Intent(context, ConnectionService::class.java)
                .setAction(ACTION_SEND_FILES)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = clip
            ContextCompat.startForegroundService(context, intent)
        }
```

   Imports: `android.content.ClipData`, `android.net.Uri`, `dev.macdroid.app.platform.SendFiles`, `dev.macdroid.app.ui.presentation.TransferPresentation`, `dev.macdroid.core.transfer.TransferEvent`, `kotlinx.coroutines.delay`, `kotlinx.coroutines.flow.conflate`, `kotlinx.coroutines.withContext`.

- [ ] **Step 4: Build and test**

Run: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, no `w:` warnings.

- [ ] **Step 5: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 9: Android `SendFiles` (persistable grant, or a cache copy up to 100 MB), a progress notification with Cancel, a 'Received <name> from Mac' notification with Open, and `ConnectionService` actions for sending and cancelling. Verified: Android gates."
  - **ARCHITECTURE.md:** in the `service/` glue line, add "file-transfer notifications (`TransferNotifications`) and `SEND_FILES`/`CANCEL_TRANSFERS` actions".

```bash
git add android docs/handoff
git commit -m "feat(android): transfer notifications and service actions"
```

---

### Task 10: Android — Files tab, Home Send button and recent activity

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/ui/files/FilesViewModel.kt`, `FilesScreen.kt`, `TransferRow.kt`, `SendPickers.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/main/MainScreen.kt`, `HomeScreen.kt`

**Interfaces:**
- Consumes: `TransferPresentation` (Task 7); `SendFiles.enqueue` (Task 9); `AppGraph.transferEngine`, `connectionManager`, `saveTargets` (Task 8).
- Produces:
  - `FilesViewModel` with `ui: StateFlow<FilesUi>`, `send(uris)`, `cancel(id)`, `retry(id)`
  - `@Composable TransferRow(record, rate, onCancel, onRetry, onOpen)`
  - `rememberSendPickers(onPicked): SendPickers`
  - `Context.openReceived(record)`

- [ ] **Step 1: Create the view model and pickers**

`ui/files/FilesViewModel.kt`:

```kotlin
package dev.macdroid.app.ui.files

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.platform.SendFiles
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.transfer.TransferSnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FilesUi(val snapshot: TransferSnapshot, val connected: Boolean, val folderProblem: Boolean)

class FilesViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph

    val ui: StateFlow<FilesUi> = combine(graph.transferEngine.snapshot, graph.connectionManager.state, graph.saveTargets.folderProblem) { snapshot, state, problem ->
        FilesUi(snapshot, state is ConnectionState.Connected, problem)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FilesUi(graph.transferEngine.snapshot.value, graph.connectionManager.state.value is ConnectionState.Connected, graph.saveTargets.folderProblem.value),
    )

    init {
        graph.saveTargets.current() // refresh the folder-problem flag when the screen opens
    }

    fun send(uris: List<Uri>) {
        viewModelScope.launch { SendFiles.enqueue(getApplication(), uris) }
    }

    fun cancel(id: String) = graph.transferEngine.cancel(id)

    fun retry(id: String) {
        graph.transferEngine.retry(id)
    }
}
```

`ui/files/SendPickers.kt`:

```kotlin
package dev.macdroid.app.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.macdroid.core.transfer.TransferRecord

/** The two ways to pick files to send: the system Photo Picker (no storage permission) and the document picker. */
class SendPickers(val pickPhotos: () -> Unit, val pickFiles: () -> Unit)

@Composable
fun rememberSendPickers(onPicked: (List<Uri>) -> Unit): SendPickers {
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { if (it.isNotEmpty()) onPicked(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onPicked(it) }
    return remember(photos, files) {
        SendPickers(
            pickPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            pickFiles = { files.launch(arrayOf("*/*")) },
        )
    }
}

/** Opens a received file in whatever app handles its type. */
fun Context.openReceived(record: TransferRecord) {
    val uri = record.savedUri?.let(Uri::parse) ?: return
    runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, record.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
}
```

- [ ] **Step 2: Create `ui/files/TransferRow.kt`**

```kotlin
package dev.macdroid.app.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macdroid.app.ui.presentation.TransferPresentation
import dev.macdroid.core.transfer.Direction
import dev.macdroid.core.transfer.TransferRecord

@Composable
fun TransferRow(record: TransferRecord, rate: Double?, onCancel: () -> Unit, onRetry: () -> Unit, onOpen: () -> Unit) {
    val row = TransferPresentation.row(record, rate)
    ListItem(
        modifier = if (row.canOpen) Modifier.clickable(onClick = onOpen) else Modifier,
        leadingContent = {
            Icon(
                if (record.direction == Direction.ToMac) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                contentDescription = row.directionLabel,
                tint = if (row.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = {
            Column {
                row.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
                Text(row.detail, color = if (row.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.canRetry) TextButton(onClick = onRetry) { Text("Retry") }
                if (row.canCancel) IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = "Cancel") }
            }
        },
    )
}
```

If `TextOverflow.MiddleEllipsis` isn't available in this Compose version, use `TextOverflow.Ellipsis`.

- [ ] **Step 3: Create `ui/files/FilesScreen.kt`**

```kotlin
package dev.macdroid.app.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun FilesScreen(viewModel: FilesViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickers = rememberSendPickers(viewModel::send)
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Files", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)) }
        if (ui.folderProblem) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        "Can't save to your chosen folder — choose it again in Settings. Until then MacDroid asks where to save each file.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        item { SendCard(ui.connected, pickers) }
        if (ui.snapshot.active.isNotEmpty()) {
            item { SectionTitle("In progress") }
            items(ui.snapshot.active, key = { it.id }) { record ->
                TransferRow(record, ui.snapshot.rates[record.id], { viewModel.cancel(record.id) }, { viewModel.retry(record.id) }, { context.openReceived(record) })
            }
        }
        item { SectionTitle("Recent") }
        if (ui.snapshot.history.isEmpty()) {
            item {
                Text("Files you send or receive appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
        items(ui.snapshot.history, key = { "h-" + it.id }) { record ->
            TransferRow(record, null, { viewModel.cancel(record.id) }, { viewModel.retry(record.id) }, { context.openReceived(record) })
        }
    }
}

@Composable
fun SendCard(connected: Boolean, pickers: SendPickers) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Send to Mac", style = MaterialTheme.typography.titleMedium)
            if (!connected) Text("Connect to your Mac first", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = pickers.pickPhotos, enabled = connected) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                    Text("Photos", modifier = Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(onClick = pickers.pickFiles, enabled = connected) {
                    Icon(Icons.Rounded.Description, contentDescription = null)
                    Text("Files", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp))
}
```

- [ ] **Step 4: Show it, and add Send to Home**

In `MainScreen.kt`, replace the Files `ComingSoonScreen(...)` with `FilesScreen()`, and import `dev.macdroid.app.ui.files.FilesScreen`.

In `HomeScreen.kt`:
- Give `HomeScreen` a second parameter, `filesViewModel: FilesViewModel = viewModel()`.
- Wrap the existing `Column` in a `Box(Modifier.fillMaxSize())`.
- Replace the "Recent activity" `OutlinedCard` body.
- Add the Send FAB with a two-item menu.

```kotlin
    val files by filesViewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickers = rememberSendPickers(filesViewModel::send)
    var sendMenu by remember { mutableStateOf(false) }
```

```kotlin
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                val recent = TransferPresentation.recent(files.snapshot)
                if (recent.isEmpty()) {
                    Text(
                        "Files you send or receive show here. Clipboard and notifications come in later updates.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                recent.forEach { record ->
                    TransferRow(record, null, { filesViewModel.cancel(record.id) }, { filesViewModel.retry(record.id) }, { context.openReceived(record) })
                }
            }
        }
```

```kotlin
    // inside the Box, after the Column
    Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
        ExtendedFloatingActionButton(
            onClick = { if (files.connected) sendMenu = true },
            icon = { Icon(Icons.Rounded.Send, contentDescription = null) },
            text = { Text("Send") },
            expanded = true,
        )
        DropdownMenu(expanded = sendMenu, onDismissRequest = { sendMenu = false }) {
            DropdownMenuItem(text = { Text("Photos") }, onClick = { sendMenu = false; pickers.pickPhotos() })
            DropdownMenuItem(text = { Text("Files") }, onClick = { sendMenu = false; pickers.pickFiles() })
        }
    }
```

When not connected, the FAB stays visible. Tapping it does nothing, and the status card above it already says why. Use `Icons.AutoMirrored.Rounded.Send` if `Icons.Rounded.Send` is deprecated in this icon set (a deprecation warning fails the gate).

- [ ] **Step 5: Build and test**

Run: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, no `w:` warnings.

- [ ] **Step 6: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 10: Android Files tab (Send to Mac card with Photos/Files, In progress with cancel, Recent with open/retry, folder-problem banner) and Home's Send FAB plus Recent activity (last 3). Verified: Android gates."
  - **ARCHITECTURE.md:** update `ui/main/` to say that Notifications and Clipboard are placeholders and Files is live (`ui/files/`).

```bash
git add android docs/handoff
git commit -m "feat(android): Files tab and Home send button"
```

---
### Task 11: Android share target

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/ui/share/ShareActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ShareDecision` (Task 7); `ConnectionService.sendFiles` (Task 9); `AppGraph.pairingStore` and `connectionManager`.

- [ ] **Step 1: Create `ui/share/ShareActivity.kt`**

```kotlin
package dev.macdroid.app.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import dev.macdroid.app.graph
import dev.macdroid.app.service.ConnectionService
import dev.macdroid.app.ui.MainActivity
import dev.macdroid.app.ui.presentation.ShareDecision
import dev.macdroid.core.connection.ConnectionState

/** "Share → MacDroid": queues the files and gets out of the way (spec §4.2). It shows no UI of its own. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        val decision = ShareDecision.decide(
            paired = graph.pairingStore.paired.value != null,
            connected = graph.connectionManager.state.value is ConnectionState.Connected,
            count = uris.size,
        )
        when (decision) {
            ShareDecision.Send -> {
                // This activity holds the share's read grant; the intent passes it on to the service.
                ConnectionService.sendFiles(this, uris)
                Toast.makeText(this, ShareDecision.sendingText(uris.size), Toast.LENGTH_SHORT).show()
            }
            ShareDecision.ConnectFirst -> {
                Toast.makeText(this, "Connect to your Mac first", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ShareDecision.Nothing -> Unit
        }
        finish()
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}
```

- [ ] **Step 2: Register it** (`AndroidManifest.xml`, inside `<application>`)

```xml
        <activity
            android:name=".ui.share.ShareActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:label="Send to Mac"
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="*/*" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="*/*" />
            </intent-filter>
        </activity>
```

- [ ] **Step 3: Build and test**

Run: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, no `w:` warnings.

- [ ] **Step 4: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 11: Android share target ('Send to Mac' for one or many files of any type): queues silently with a toast when connected, otherwise 'Connect to your Mac first' and opens the app. Verified: Android gates."
  - **ARCHITECTURE.md:** add `ui/share/ShareActivity` to the `android/app` UI list.

```bash
git add android docs/handoff
git commit -m "feat(android): share target"
```

---

### Task 12: Android Settings → Files and the optional setup step

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/ui/main/FilesSettingsViewModel.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/main/SettingsScreen.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/setup/SetupFlow.kt`, `SetupViewModel.kt`, `SetupScreen.kt`
- Test: `android/app/src/test/java/dev/macdroid/app/SetupFlowTest.kt`

**Interfaces:**
- Consumes: `FilesPrefs`, `SaveTargets` (Task 8); `FolderLabel` (Task 7).
- Produces:
  - `SetupStep.Files`, shown on first run only, between Notifications and Battery
  - a new `SetupInputs.filesStepDone` field (last parameter, default `false`)
  - `SetupViewModel.completeFiles(treeUri: Uri?)`

- [ ] **Step 1: Update the setup-flow tests** (`SetupFlowTest.kt`)

1. Add `filesStepDone: Boolean = false` as the last parameter of the `inputs(...)` helper, and pass it as the last argument to `SetupInputs(...)`.
2. In `firstRunShowsEveryStepInOrder`, the expected list becomes `listOf(SetupStep.Welcome, SetupStep.LocalNetwork, SetupStep.PairMac, SetupStep.Notifications, SetupStep.Files, SetupStep.Battery)`.
3. In `firstRunSkipsPermissionsTheOsDoesNotHave`, it becomes `listOf(SetupStep.Welcome, SetupStep.PairMac, SetupStep.Files, SetupStep.Battery)`.
4. In `advancesAsEachStepCompletes`, replace the line expecting `SetupStep.Battery` after skipping notifications with:

```kotlin
        assertEquals(SetupStep.Files, SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true, paired = true, notificationsSkipped = true)))
        assertEquals(SetupStep.Battery, SetupFlow.current(inputs(welcomeDone = true, hasLocalNetwork = true, paired = true, notificationsSkipped = true, filesStepDone = true)))
```

   and add `filesStepDone = true` to the final `assertNull(...)` line's inputs.
5. In `progressPosition`, `assertEquals(5, state.total)` becomes `assertEquals(6, state.total)`.
6. Add:

```kotlin
    @Test fun filesStepIsFirstRunOnly() {
        assertNull(SetupFlow.current(inputs(setupDone = true, hasLocalNetwork = true, paired = true, filesStepDone = false)))
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*SetupFlowTest*'`
Expected: compile error (`SetupStep.Files` and the `filesStepDone` parameter don't exist).

- [ ] **Step 3: Implement the flow** (`SetupFlow.kt`)

- `enum class SetupStep { Welcome, LocalNetwork, PairMac, Notifications, Files, Battery }`
- `SetupInputs`: add `val filesStepDone: Boolean = false` as the last property.
- In the first-run branch of `steps`, add `add(SetupStep.Files)` before `add(SetupStep.Battery)`.
- In `isDone`, add `SetupStep.Files -> i.filesStepDone || i.setupDone`.

- [ ] **Step 4: Run them to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*SetupFlowTest*'` → PASS.

- [ ] **Step 5: Wire the setup step** (`SetupViewModel.kt`, `SetupScreen.kt`)

`SetupViewModel`:
- Add `val filesStepDone: Boolean` to `Local`, read from `graph.filesPrefs.filesStepDone`.
- Pass `filesStepDone = l.filesStepDone` in `inputs(...)`.
- Add:

```kotlin
    /** "Where should files from your Mac go?": null keeps Download/MacDroid; a tree URI is a folder the user chose. */
    fun completeFiles(treeUri: Uri?) {
        val app = getApplication<Application>()
        if (treeUri != null) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                graph.filesPrefs.treeUri = treeUri.toString()
            }
        }
        graph.filesPrefs.filesStepDone = true
        refresh()
    }
```

`SetupScreen`: add `SetupStep.Files -> FilesStep(onDone = viewModel::completeFiles)` to the `when`, and:

```kotlin
@Composable
private fun FilesStep(onDone: (Uri?) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) onDone(uri) }
    StepLayout(
        icon = Icons.Rounded.Folder,
        title = "Where should files from your Mac go?",
        body = "Files you send from your Mac are saved automatically. You can change this later in Settings.",
        primaryLabel = "Use Download/MacDroid",
        onPrimary = { onDone(null) },
        secondaryLabel = "Choose a folder",
        onSecondary = { launcher.launch(null) },
    )
}
```

(Imports: `androidx.compose.material.icons.rounded.Folder`.)

- [ ] **Step 6: Create the Files settings view model** (`ui/main/FilesSettingsViewModel.kt`)

```kotlin
package dev.macdroid.app.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.ui.presentation.FolderLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class FilesSettingsUi(val folderLabel: String, val customFolder: Boolean, val askEachTime: Boolean, val folderProblem: Boolean)

class FilesSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph

    val ui: StateFlow<FilesSettingsUi> = combine(graph.filesPrefs.treeUriFlow, graph.filesPrefs.askEachTimeFlow, graph.saveTargets.folderProblem) { tree, ask, problem ->
        FilesSettingsUi(FolderLabel.of(tree), tree != null, ask, problem)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesSettingsUi(FolderLabel.of(graph.filesPrefs.treeUri), graph.filesPrefs.treeUri != null, graph.filesPrefs.askEachTime, false))

    fun refresh() {
        graph.saveTargets.current()
    }

    fun chooseFolder(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        graph.filesPrefs.treeUri = uri.toString()
        refresh()
    }

    fun useDefaultFolder() {
        val resolver = getApplication<Application>().contentResolver
        graph.filesPrefs.treeUri?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        graph.filesPrefs.treeUri = null
        refresh()
    }

    fun setAskEachTime(on: Boolean) {
        graph.filesPrefs.askEachTime = on
        refresh()
    }
}
```

- [ ] **Step 7: Add the Files section** (`SettingsScreen.kt`)

Add the parameter `filesViewModel: FilesSettingsViewModel = viewModel()`. Collect its state and create the launcher at the top of `SettingsScreen`:

```kotlin
    val files by filesViewModel.ui.collectAsStateWithLifecycle()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) filesViewModel.chooseFolder(uri) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { filesViewModel.refresh() }
```

Then insert these items after the "Connection" items and before `SectionHeader("Setup")`:

```kotlin
        item { SectionHeader("Files") }
        item {
            ListItem(
                headlineContent = { Text("Save files from your Mac to") },
                supportingContent = {
                    Text(
                        if (files.folderProblem) "Can't save to your chosen folder — choose it again" else files.folderLabel,
                        color = if (files.folderProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                trailingContent = { if (files.customFolder) TextButton(onClick = filesViewModel::useDefaultFolder) { Text("Use default") } },
                modifier = Modifier.clickable { folderPicker.launch(null) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Ask where to save each time") },
                supportingContent = { Text("Choose a place for every file instead of saving automatically") },
                leadingContent = { Icon(Icons.Rounded.SaveAs, contentDescription = null) },
                trailingContent = { Switch(checked = files.askEachTime, onCheckedChange = filesViewModel::setAskEachTime) },
            )
        }
```

(Imports: `androidx.activity.compose.rememberLauncherForActivityResult`, `androidx.activity.result.contract.ActivityResultContracts`, `androidx.compose.material.icons.rounded.Folder`, `androidx.compose.material.icons.rounded.SaveAs`.)

- [ ] **Step 8: Build and test**

Run: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, no `w:` warnings.

- [ ] **Step 9: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 12: Android Settings → Files (save folder with 'Use default', ask-each-time switch, problem text) and an optional first-run setup step 'Where should files from your Mac go?'. Verified: `SetupFlowTest` and the Android gates."
  - **ARCHITECTURE.md:** in `ui/setup/`, list the step order Welcome → Local network → Pair → Notifications → Files (first run only) → Battery.

```bash
git add android docs/handoff
git commit -m "feat(android): files settings and setup step"
```

---

### Task 13: Final gates, handoff and the owner's hands-on checklist

**Files:**
- Modify: `docs/handoff/CHANGELOG.md`, `STATUS.md`, `ROADMAP.md`, `README.md`, `DEV-GUIDE.md`
- Create: `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`

- [ ] **Step 1: Run every gate**

```bash
cd mac/MacDroidCore && swift test
cd .. && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build
cd ../android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
cd .. && ./scripts/cross-platform-test.sh
```

Expected: everything passes with no warnings. Record the Swift test count.

- [ ] **Step 2: Install for the owner's check**

```bash
ANDROID_SERIAL=<serial> ./gradlew -p android :app:installDebug
open mac/build/Build/Products/Debug/MacDroid.app
```

- [ ] **Step 3: Write the follow-ups file** (`docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`)

```markdown
# File transfer (Plan 2) — follow-ups

## Owner's hands-on checklist (spec §6)
- [ ] A 2 GB or larger video, phone → Mac and Mac → phone.
- [ ] Toggle Wi-Fi mid-transfer: the row shows "Paused — waiting for connection", then resumes and finishes.
- [ ] Drop several files on the Mac popover: the overlay shows the count and size, and all arrive on the phone.
- [ ] Android share sheet → "Send to Mac" from Photos and from Files: the toast appears, with no UI otherwise.
- [ ] Share while disconnected: "Connect to your Mac first", and the app opens.
- [ ] Receive on the phone with the screen locked: the progress notification shows, then "Received <name> from Mac" → Open.
- [ ] Change the save folder on both sides; revoke the Android folder (clear app storage access): the banner shows and the next file asks where to save.
- [ ] "Ask where to save each time" on both sides; cancel the transfer from the other side while the save panel or prompt is open.
- [ ] Storage-full behaviour, if practical.

## Deferred minors (from the Plan 2a final review)
- `TransferManager.forget()` leaves an orphaned `.macdroid-part` file.
- A re-offer during the Mac's checksum verification can recreate an empty part file.
- GET's security-scoped access can leak if the phone disconnects before the response starts.
- Android: a progress callback can re-add a speed for a finished transfer (cosmetic).
- PUT doesn't check `Content-Range` end/total against the offered size (hardening).
- Stale "later plans add … transfers" comments in `Messages.swift` and `Messages.kt`.

## Later
- The Finder Share extension (its own small round).
- Sending folders.
```

- [ ] **Step 4: Update the handoff**
  - **CHANGELOG.md:** rename the Plan 2b heading to `(done on feat/file-transfer; waiting for the owner's check)`, and add a "Verified" line with the four gates and the Swift test count.
  - **STATUS.md:**
    - In flight: "File transfer (Plan 2a engine + 2b UI) is complete on `feat/file-transfer` and waiting for the owner's hands-on checklist (`docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`). Merge after it passes."
    - Under "Pending or unverified", add the checklist.
    - Update the Swift test count.
  - **ROADMAP.md:** in "Next", say that 2a and 2b are done; next is the owner's checklist, then merge, then Plan 3 (notifications).
  - **README.md:** in the round table, set Plan 2 to "**Built on `feat/file-transfer`; owner's hands-on check pending**". Update the "Picking up?" note to match.
  - **DEV-GUIDE.md:** add a Gotchas line: "**Share target and URI grants:** shared URIs are handed to `ConnectionService` in an intent with `FLAG_GRANT_READ_URI_PERMISSION`. Files without a persistable grant are copied to `cache/outgoing/` only up to 100 MB, and the copies are deleted when the transfer finishes."

- [ ] **Step 5: Commit**

```bash
git add docs
git commit -m "docs(handoff): Plan 2b done; owner checklist"
```
