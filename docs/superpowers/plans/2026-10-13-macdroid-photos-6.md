# MacDroid Photos — Plan 6: Browse and Transfer the Phone's Photos

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Mac Photos page (album column, day-grouped grid) that browses the phone's photos and videos, previews them with Quick Look, saves them, and lets you drag one into Finder.

**Architecture:**
- **Messages:** `photos.*` messages carry albums, pages and 256 px thumbnails over the WebSocket. Originals come over the existing transfer engine as `purpose: photo` offers, using a transfer id the Mac picked (`photos.fetch`), so the Mac knows where each file goes and rejects unasked offers.
- **Pure, tested logic:**
  - Android core: cursor, request limits, the access level, the query SQL, transfer-engine support, and `PhotoService` (answers the Mac from a `PhotoSource`).
  - Mac core: `ThumbnailCache`, `PhotosPresentation`, the `PhotosClient` actor, and `TransferManager` photo destinations.
- **Thin glue:**
  - Android: `PhotoLibrary` (MediaStore), `PhotosBridge`, the permission UI.
  - Mac: the `PhotosView` grid, Quick Look, the drag provider.

**Tech Stack:** Swift 6 / SwiftUI (`LazyVGrid`, `.quickLookPreview`, `NSItemProvider.registerFileRepresentation`); Kotlin / MediaStore (`ContentResolver.loadThumbnail`, `ContentObserver`) / Compose.

**Spec:** `docs/superpowers/specs/2026-10-13-macdroid-photos-design.md` (§9 wins).

## Global Constraints

- **Branch:** `feat/photos` from `main`. Don't merge: that waits for the owner's check.
- **Handoff:** every task's commit updates `docs/handoff/`.
  - Task 1 adds a CHANGELOG entry at the top, `## <date> — Plan 6: photos (in progress on feat/photos)`.
  - Each later task adds a bullet under it.
- **Features** after Task 1: `files, notifications, clipboard, ring, links, media, photos`, in that order, on both sides.
- **Limits:**
  - a page is at most 200 items
  - a thumbnail request is at most 60 ids
  - thumbnails are 256 px on the longest side, JPEG quality 80
  - the Mac thumbnail cache is capped at 200,000,000 bytes, evicting the least recently used
  - photo ids are MediaStore `_ID` decimal strings (digits only, 1–20 characters); anything else is ignored
- **Ordering ruling (applies to every task):** order, page and group by `MediaStore.MediaColumns.DATE_ADDED` (seconds, never null) and then `_ID`, both descending. `takenAt = DATE_ADDED × 1000`.
  - **Why:** spec §3 named `DATE_TAKEN`, but it can be null, and newer MediaProviders reject SQL functions such as `COALESCE` in selections and sort orders.
  - **Cost if wrong:** photos downloaded long after they were taken sort by the date they arrived on the phone.
- **The cursor** is `"<dateAddedSeconds>:<id>"`. The next page is `date_added < ? OR (date_added = ? AND _id < ?)`.
- **Fetch destinations:**
  - `save`: the Mac's received-files folder, never asking
  - `cache`: `Caches/PhotoCache/` (hidden from Files, emptied at launch)
- **Fetch timeout:** 30 s without an offer gives "Your phone didn't respond". At most 3 fetches are asked for at once (the phone's `MAX_ACTIVE_OUTGOING`), with previews and drags ahead of saves. In-flight thumbnail requests expire after 20 s.
- **Copy (Mac):**
  - the sidebar item "Photos"
  - "All Photos"
  - "Phone not connected"
  - "Photos are off on your phone. Open MacDroid on your phone → Settings → Photos."
  - "MacDroid can see N selected photos. To see all, open MacDroid on your phone → Settings → Photos."
  - "This photo is no longer on your phone"
  - "Your phone didn't respond"
  - "Update MacDroid on your phone to use this."
  - "N selected · <size>"
  - the buttons "Preview" and "Save to Mac"
  - Phone page: "Photos: All / Selected / Off on phone"
- **Copy (Android):**
  - setup step "See your photos on your Mac", with the body "Browse and save your photos and videos from your Mac. You choose all photos or just some." and the buttons "Choose access" / "Not now"
  - Settings section "Photos", with the row "Photos on your Mac" and the values "All photos" / "Selected photos" / "Off"
- **Permissions** (manifest):
  - `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`
  - `READ_EXTERNAL_STORAGE` with `android:maxSdkVersion="32"`
- **Gates:**
  - `swift test`
  - the Mac app build with no warnings
  - `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` with no warnings
  - `./scripts/cross-platform-test.sh`
- No UI test harness exists: views and MediaStore glue are verified by the build and the owner's checklist. Don't automate anything on the owner's phone.

## Review Focus

1. **A phone that pushes a `purpose: photo` offer the Mac never asked for** must be rejected, never written to disk. → Task 3 `unrequestedPhotoOffersAreRejected`.
2. **New photos taken while the Mac pages through the library** must not shift pages or duplicate items. → Task 2 `cursorSelectsStrictlyOlderItems`.
3. **A thumbnail id like `../x` or `12/../3`** must never become a file path. → Task 4 `invalidIdsAreRejected`.
4. **A photo deleted on the phone after it appeared in the grid:** fetching it must say "This photo is no longer on your phone" and remove it. → Task 4 `goneFetchRemovesTheItemAndExplains`.
5. **A fetch whose offer never arrives** must not spin forever. → Task 4 `fetchesTimeOutAfterThirtySeconds`.

---

## File Structure

```
protocol/fixtures/photos.{albums,albumList,query,page,thumbs,thumb,fetch,fetchFailed,changed}.json,
  transfer.offer.photo.json (new); protocol/PROTOCOL.md                                  (T1)
mac/MacDroidCore/Sources/MacDroidCore/
  Protocol/Messages.swift, MessageCodec.swift                                            (T1)
  Transfer/TransferModel.swift, TransferManager.swift  (hidden, photo destinations)      (T3)
  Photos/ThumbnailCache.swift, Photos/PhotosClient.swift (new)                           (T4)
  Presentation/PhotosPresentation.swift (new)                                            (T4)
mac/MacDroidCore/Sources/MacDroidDevServer/main.swift                                    (T3, T9)
mac/MacDroidCore/Tests/MacDroidCoreTests/ProtocolFixtureTests.swift                      (T1)
  TransferPhotoTests.swift (new, T3), PhotosTests.swift (new, T4)
mac/MacDroid/AppModel.swift, SidebarSection.swift, Files/FilesSettings.swift,
  Views/MainWindow.swift, Views/Phone/PairedDeviceView.swift                             (T8)
mac/MacDroid/Views/Photos/PhotosView.swift, PhotoCell.swift (new)                        (T8)
android/core/src/main/kotlin/dev/macdroid/core/
  protocol/Messages.kt, MessageCodec.kt                                                  (T1)
  photos/PhotoQueries.kt (new, T2), photos/PhotoService.kt (new, T5)
  transfer/TransferModel.kt, TransferEngine.kt  (isHidden, enqueueWithId)                (T2)
android/core/src/test/kotlin/dev/macdroid/core/
  protocol/MessageCodecTest.kt (T1); photos/PhotoQueriesTest.kt (T2), PhotoServiceTest.kt (T5);
  transfer/TransferEnginePhotoTest.kt (T2); CrossPlatformTest.kt (T9)
android/app/src/main/AndroidManifest.xml, MacDroidApp.kt (AppGraph.photosBridge, UiPrefs) (T6, T7)
android/app/src/main/java/dev/macdroid/app/platform/photos/PhotoLibrary.kt, PhotosBridge.kt (new, T6)
android/app/src/main/java/dev/macdroid/app/ui/setup/SetupFlow.kt, SetupViewModel.kt, SetupScreen.kt (T7)
android/app/src/main/java/dev/macdroid/app/ui/main/SettingsScreen.kt, SettingsViewModel.kt (T7)
android/app/src/main/java/dev/macdroid/app/ui/presentation/PhotosAccessText.kt (new, T7)
android/app/src/test/java/dev/macdroid/app/PhotosAccessTextTest.kt (new), SetupFlowTest.kt (T7)
scripts/cross-platform-test.sh                                                           (T9)
docs/superpowers/plans/2026-10-13-macdroid-photos-followups.md (new, T10)
```

---

### Task 1: Protocol — `photos.*` messages and `purpose: photo`

**Files:**
- Create: the 10 fixtures listed above
- Modify: `Messages.swift`, `MessageCodec.swift`, `Messages.kt`, `MessageCodec.kt`, `PROTOCOL.md`
- Test: `ProtocolFixtureTests.swift`, `MessageCodecTest.kt`

**Interfaces (produces):**
- **Swift:**
  - `PhotoAccess` (`.full`, `.partial`, `.none`), `PhotoKindFilter` (`.all`, `.photos`, `.videos`) and `PhotoKind` (`.photo`, `.video`)
  - `PhotoAlbum(id:name:count:coverId:)` and `PhotoItem(id:kind:name:mime:takenAt:size:width:height:durationMs:)`, both `Identifiable`
  - `PhotosAlbumsRequest(requestId:)` and `PhotosAlbumList(requestId:access:albums:)`
  - `PhotosQuery(requestId:albumId:kind:cursor:limit:)` and `PhotosPage(requestId:access:items:nextCursor:)`
  - `PhotosThumbs(ids:)` and `PhotosThumb(id:jpeg:)`
  - `PhotosFetch(id:transferId:)`, `PhotoFetchFailure` (`.gone`, `.denied`) and `PhotosFetchFailed(transferId:reason:)`
  - `Message` cases `.photosAlbums`, `.photosAlbumList`, `.photosQuery`, `.photosPage`, `.photosThumbs`, `.photosThumb`, `.photosFetch`, `.photosFetchFailed`, `.photosChanged` (no payload)
  - `TransferPurpose.photo` and `Feature.photos`
- **Kotlin:**
  - `PhotoAccess { Full, Partial, None }`, `PhotoKindFilter { All, Photos, Videos }` and `PhotoKind { Photo, Video }`
  - `PhotoAlbumBody(id, name, count, coverId: String? = null)` and `PhotoItemBody(id, kind, name, mime, takenAt, size, width, height, durationMs: Long? = null)`
  - `PhotosAlbumsBody(requestId)` and `PhotosAlbumListBody(requestId, access, albums)`
  - `PhotosQueryBody(requestId, albumId, kind, cursor: String? = null, limit)` and `PhotosPageBody(requestId, access, items, nextCursor: String? = null)`
  - `PhotosThumbsBody(ids)` and `PhotosThumbBody(id, jpeg)`
  - `PhotosFetchBody(id, transferId)`, `PhotoFetchFailure { Gone, Denied }` and `PhotosFetchFailedBody(transferId, reason)`
  - `Message.PhotosAlbums`, `PhotosAlbumList`, `PhotosQuery`, `PhotosPage`, `PhotosThumbs`, `PhotosThumb`, `PhotosFetch`, `PhotosFetchFailed`, and `data object PhotosChanged`
  - `TransferPurpose.Photo` and `Features.PHOTOS`

- [ ] **Step 1: Branch and fixtures** (ids `6f1c2a0e-0000-4000-8000-0000000000NN`, NN = 40–49)

```bash
git checkout main && git checkout -b feat/photos
```

```json
{ "v": 1, "type": "photos.albums", "id": "6f1c2a0e-0000-4000-8000-000000000040", "body": { "requestId": "r1" } }
```
```json
{ "v": 1, "type": "photos.albumList", "id": "6f1c2a0e-0000-4000-8000-000000000041", "body": { "requestId": "r1", "access": "full", "albums": [ { "id": "all", "name": "All Photos", "count": 4812, "coverId": "1001" }, { "id": "-1739773001", "name": "Camera", "count": 2301, "coverId": "1001" } ] } }
```
```json
{ "v": 1, "type": "photos.query", "id": "6f1c2a0e-0000-4000-8000-000000000042", "body": { "requestId": "r2", "albumId": "all", "kind": "all", "cursor": "1791892800:1001", "limit": 200 } }
```
```json
{ "v": 1, "type": "photos.page", "id": "6f1c2a0e-0000-4000-8000-000000000043", "body": { "requestId": "r2", "access": "partial", "items": [ { "id": "1000", "kind": "video", "name": "VID_2041.mp4", "mime": "video/mp4", "takenAt": 1791892700000, "size": 52428800, "width": 1920, "height": 1080, "durationMs": 42000 }, { "id": "999", "kind": "photo", "name": "IMG_2040.jpg", "mime": "image/jpeg", "takenAt": 1791892600000, "size": 3145728, "width": 4000, "height": 3000 } ], "nextCursor": "1791892600:999" } }
```
```json
{ "v": 1, "type": "photos.thumbs", "id": "6f1c2a0e-0000-4000-8000-000000000044", "body": { "ids": ["1000", "999"] } }
```
```json
{ "v": 1, "type": "photos.thumb", "id": "6f1c2a0e-0000-4000-8000-000000000045", "body": { "id": "1000", "jpeg": "/9j/4AAQSkZJRg==" } }
```
```json
{ "v": 1, "type": "photos.fetch", "id": "6f1c2a0e-0000-4000-8000-000000000046", "body": { "id": "1000", "transferId": "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f" } }
```
```json
{ "v": 1, "type": "photos.fetchFailed", "id": "6f1c2a0e-0000-4000-8000-000000000047", "body": { "transferId": "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", "reason": "gone" } }
```
```json
{ "v": 1, "type": "photos.changed", "id": "6f1c2a0e-0000-4000-8000-000000000048", "body": {} }
```
```json
{ "v": 1, "type": "transfer.offer", "id": "6f1c2a0e-0000-4000-8000-000000000049", "body": { "transferId": "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", "name": "VID_2041.mp4", "size": 52428800, "mime": "video/mp4", "purpose": "photo" } }
```

- [ ] **Step 2: Failing Swift tests**

Add the 10 names to `messageFixtures`. Replace the features test `supportedFeaturesIncludeTheSmallWins` with `supportedFeaturesIncludePhotos`, expecting `[Feature.files, Feature.notifications, Feature.clipboard, Feature.ring, Feature.links, Feature.media, Feature.photos]`. Add:

```swift
    @Test func photosFixturesDecode() throws {
        #expect(try MessageCodec.decode(Fixtures.text("photos.albums.json")) == .photosAlbums(PhotosAlbumsRequest(requestId: "r1")))
        #expect(try MessageCodec.decode(Fixtures.text("photos.albumList.json")) == .photosAlbumList(PhotosAlbumList(requestId: "r1", access: .full, albums: [
            PhotoAlbum(id: "all", name: "All Photos", count: 4812, coverId: "1001"),
            PhotoAlbum(id: "-1739773001", name: "Camera", count: 2301, coverId: "1001"),
        ])))
        #expect(try MessageCodec.decode(Fixtures.text("photos.query.json")) == .photosQuery(PhotosQuery(requestId: "r2", albumId: "all", kind: .all, cursor: "1791892800:1001", limit: 200)))
        #expect(try MessageCodec.decode(Fixtures.text("photos.page.json")) == .photosPage(PhotosPage(requestId: "r2", access: .partial, items: [
            PhotoItem(id: "1000", kind: .video, name: "VID_2041.mp4", mime: "video/mp4", takenAt: 1_791_892_700_000, size: 52_428_800, width: 1920, height: 1080, durationMs: 42_000),
            PhotoItem(id: "999", kind: .photo, name: "IMG_2040.jpg", mime: "image/jpeg", takenAt: 1_791_892_600_000, size: 3_145_728, width: 4000, height: 3000),
        ], nextCursor: "1791892600:999")))
        #expect(try MessageCodec.decode(Fixtures.text("photos.thumbs.json")) == .photosThumbs(PhotosThumbs(ids: ["1000", "999"])))
        #expect(try MessageCodec.decode(Fixtures.text("photos.thumb.json")) == .photosThumb(PhotosThumb(id: "1000", jpeg: "/9j/4AAQSkZJRg==")))
        #expect(try MessageCodec.decode(Fixtures.text("photos.fetch.json")) == .photosFetch(PhotosFetch(id: "1000", transferId: "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f")))
        #expect(try MessageCodec.decode(Fixtures.text("photos.fetchFailed.json")) == .photosFetchFailed(PhotosFetchFailed(transferId: "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", reason: .gone)))
        #expect(try MessageCodec.decode(Fixtures.text("photos.changed.json")) == .photosChanged)
        #expect(try MessageCodec.decode(Fixtures.text("transfer.offer.photo.json")) == .transferOffer(TransferOffer(
            transferId: "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", name: "VID_2041.mp4", size: 52_428_800, mime: "video/mp4", purpose: .photo
        )))
    }
```

Run `swift test --filter ProtocolFixtureTests`. Expected: compile errors.

- [ ] **Step 3: Implement (Swift)**

In `Messages.swift`:
- Add `Feature.photos = "photos"` and append it to `ProtocolInfo.features`.
- Add `case photo` to `TransferPurpose`.
- Add, before `public enum Message`:

```swift
// MARK: Photos (Plan 6)

public enum PhotoAccess: String, Codable, Sendable { case full, partial, none }
public enum PhotoKindFilter: String, Codable, Sendable { case all, photos, videos }
public enum PhotoKind: String, Codable, Sendable { case photo, video }

public struct PhotoAlbum: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var count: Int
    public var coverId: String?
    public init(id: String, name: String, count: Int, coverId: String? = nil) {
        self.id = id
        self.name = name
        self.count = count
        self.coverId = coverId
    }
}

public struct PhotoItem: Codable, Equatable, Sendable, Identifiable, Hashable {
    public var id: String
    public var kind: PhotoKind
    public var name: String
    public var mime: String
    /// Epoch ms (DATE_ADDED × 1000, see the plan's ordering ruling).
    public var takenAt: Int64
    public var size: Int64
    public var width: Int
    public var height: Int
    public var durationMs: Int64?
    public init(id: String, kind: PhotoKind, name: String, mime: String, takenAt: Int64, size: Int64, width: Int, height: Int, durationMs: Int64? = nil) {
        self.id = id
        self.kind = kind
        self.name = name
        self.mime = mime
        self.takenAt = takenAt
        self.size = size
        self.width = width
        self.height = height
        self.durationMs = durationMs
    }
}

public struct PhotosAlbumsRequest: Codable, Equatable, Sendable {
    public var requestId: String
    public init(requestId: String) { self.requestId = requestId }
}

public struct PhotosAlbumList: Codable, Equatable, Sendable {
    public var requestId: String
    public var access: PhotoAccess
    public var albums: [PhotoAlbum]
    public init(requestId: String, access: PhotoAccess, albums: [PhotoAlbum]) {
        self.requestId = requestId
        self.access = access
        self.albums = albums
    }
}

public struct PhotosQuery: Codable, Equatable, Sendable {
    public var requestId: String
    public var albumId: String
    public var kind: PhotoKindFilter
    public var cursor: String?
    public var limit: Int
    public init(requestId: String, albumId: String, kind: PhotoKindFilter, cursor: String? = nil, limit: Int) {
        self.requestId = requestId
        self.albumId = albumId
        self.kind = kind
        self.cursor = cursor
        self.limit = limit
    }
}

public struct PhotosPage: Codable, Equatable, Sendable {
    public var requestId: String
    public var access: PhotoAccess
    public var items: [PhotoItem]
    public var nextCursor: String?
    public init(requestId: String, access: PhotoAccess, items: [PhotoItem], nextCursor: String? = nil) {
        self.requestId = requestId
        self.access = access
        self.items = items
        self.nextCursor = nextCursor
    }
}

public struct PhotosThumbs: Codable, Equatable, Sendable {
    public var ids: [String]
    public init(ids: [String]) { self.ids = ids }
}

public struct PhotosThumb: Codable, Equatable, Sendable {
    public var id: String
    public var jpeg: String
    public init(id: String, jpeg: String) {
        self.id = id
        self.jpeg = jpeg
    }
}

public struct PhotosFetch: Codable, Equatable, Sendable {
    public var id: String
    public var transferId: String
    public init(id: String, transferId: String) {
        self.id = id
        self.transferId = transferId
    }
}

public enum PhotoFetchFailure: String, Codable, Sendable { case gone, denied }

public struct PhotosFetchFailed: Codable, Equatable, Sendable {
    public var transferId: String
    public var reason: PhotoFetchFailure
    public init(transferId: String, reason: PhotoFetchFailure) {
        self.transferId = transferId
        self.reason = reason
    }
}
```

Add the `Message` cases:

```swift
    case photosAlbums(PhotosAlbumsRequest)
    case photosAlbumList(PhotosAlbumList)
    case photosQuery(PhotosQuery)
    case photosPage(PhotosPage)
    case photosThumbs(PhotosThumbs)
    case photosThumb(PhotosThumb)
    case photosFetch(PhotosFetch)
    case photosFetchFailed(PhotosFetchFailed)
    case photosChanged
```

In `MessageCodec.swift`:
- Add `private struct Empty: Codable {}`.
- Add the encode branches, following the pattern, with `case .photosChanged: return try encode(type: "photos.changed", id: id, body: Empty())`.
- Add the decode branches, with `case "photos.changed": return .photosChanged`.
- Type strings: `photos.albums`, `photos.albumList`, `photos.query`, `photos.page`, `photos.thumbs`, `photos.thumb`, `photos.fetch`, `photos.fetchFailed`, `photos.changed`.

Run `swift test`. Expected: PASS.

- [ ] **Step 4: Failing Kotlin tests, then implement**

In `MessageCodecTest.kt`:
- Add the 10 names to `messageFixtures`.
- Replace the features test `supportedFeaturesIncludeTheSmallWins` with `supportedFeaturesIncludePhotos`, expecting `listOf("files", "notifications", "clipboard", "ring", "links", "media", "photos")`.
- Add:

```kotlin
    @Test fun photosFixturesDecode() {
        assertEquals(Message.PhotosAlbums(PhotosAlbumsBody("r1")), MessageCodec.decode(Fixtures.text("photos.albums.json")))
        assertEquals(
            Message.PhotosAlbumList(PhotosAlbumListBody("r1", PhotoAccess.Full, listOf(
                PhotoAlbumBody("all", "All Photos", 4812, "1001"), PhotoAlbumBody("-1739773001", "Camera", 2301, "1001")))),
            MessageCodec.decode(Fixtures.text("photos.albumList.json")),
        )
        assertEquals(Message.PhotosQuery(PhotosQueryBody("r2", "all", PhotoKindFilter.All, "1791892800:1001", 200)), MessageCodec.decode(Fixtures.text("photos.query.json")))
        assertEquals(
            Message.PhotosPage(PhotosPageBody("r2", PhotoAccess.Partial, listOf(
                PhotoItemBody("1000", PhotoKind.Video, "VID_2041.mp4", "video/mp4", 1_791_892_700_000, 52_428_800, 1920, 1080, 42_000),
                PhotoItemBody("999", PhotoKind.Photo, "IMG_2040.jpg", "image/jpeg", 1_791_892_600_000, 3_145_728, 4000, 3000)), "1791892600:999")),
            MessageCodec.decode(Fixtures.text("photos.page.json")),
        )
        assertEquals(Message.PhotosThumbs(PhotosThumbsBody(listOf("1000", "999"))), MessageCodec.decode(Fixtures.text("photos.thumbs.json")))
        assertEquals(Message.PhotosThumb(PhotosThumbBody("1000", "/9j/4AAQSkZJRg==")), MessageCodec.decode(Fixtures.text("photos.thumb.json")))
        assertEquals(Message.PhotosFetch(PhotosFetchBody("1000", "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f")), MessageCodec.decode(Fixtures.text("photos.fetch.json")))
        assertEquals(Message.PhotosFetchFailed(PhotosFetchFailedBody("7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", PhotoFetchFailure.Gone)), MessageCodec.decode(Fixtures.text("photos.fetchFailed.json")))
        assertEquals(Message.PhotosChanged, MessageCodec.decode(Fixtures.text("photos.changed.json")))
        assertEquals(
            Message.TransferOffer(TransferOfferBody("7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", "VID_2041.mp4", 52_428_800, "video/mp4", TransferPurpose.Photo)),
            MessageCodec.decode(Fixtures.text("transfer.offer.photo.json")),
        )
    }
```

Run it. Expected: compilation fails. Then implement in `Messages.kt`:
- `Features.PHOTOS = "photos"`, appended to `SUPPORTED`
- `@SerialName("photo") Photo` added to `TransferPurpose`
- the bodies:

```kotlin
@Serializable enum class PhotoAccess { @SerialName("full") Full, @SerialName("partial") Partial, @SerialName("none") None }
@Serializable enum class PhotoKindFilter { @SerialName("all") All, @SerialName("photos") Photos, @SerialName("videos") Videos }
@Serializable enum class PhotoKind { @SerialName("photo") Photo, @SerialName("video") Video }

@Serializable data class PhotoAlbumBody(val id: String, val name: String, val count: Int, val coverId: String? = null)

@Serializable data class PhotoItemBody(
    val id: String,
    val kind: PhotoKind,
    val name: String,
    val mime: String,
    val takenAt: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long? = null,
)

@Serializable data class PhotosAlbumsBody(val requestId: String)
@Serializable data class PhotosAlbumListBody(val requestId: String, val access: PhotoAccess, val albums: List<PhotoAlbumBody>)
@Serializable data class PhotosQueryBody(val requestId: String, val albumId: String, val kind: PhotoKindFilter, val cursor: String? = null, val limit: Int)
@Serializable data class PhotosPageBody(val requestId: String, val access: PhotoAccess, val items: List<PhotoItemBody>, val nextCursor: String? = null)
@Serializable data class PhotosThumbsBody(val ids: List<String>)
@Serializable data class PhotosThumbBody(val id: String, val jpeg: String)
@Serializable data class PhotosFetchBody(val id: String, val transferId: String)
@Serializable enum class PhotoFetchFailure { @SerialName("gone") Gone, @SerialName("denied") Denied }
@Serializable data class PhotosFetchFailedBody(val transferId: String, val reason: PhotoFetchFailure)
```

Add the `Message` subclasses (`PhotosAlbums(PhotosAlbumsBody)`, `PhotosAlbumList(…)`, `PhotosQuery(…)`, `PhotosPage(…)`, `PhotosThumbs(…)`, `PhotosThumb(…)`, `PhotosFetch(…)`, `PhotosFetchFailed(…)`, `data object PhotosChanged : Message`). Add the codec lines:
- encode `Message.PhotosChanged -> "photos.changed" to JsonObject(emptyMap())`
- decode `"photos.changed" -> Message.PhotosChanged`

Run `./gradlew :core:test`. Expected: PASS.

- [ ] **Step 5: Mac app compile check, PROTOCOL.md, handoff, commit**

Run the Mac app build. Expected: it succeeds, because no app `switch` covers `Message`.

- PROTOCOL.md: add the 9 rows, `purpose: photo`, and a "Photos rules" section covering the cursor (with the DATE_ADDED ruling), the limits, Mac-chosen transfer ids, and unrequested offers being rejected.
- CHANGELOG: add the Plan 6 entry with "Task 1: photos messages and `purpose: photo` in both codecs."

```bash
git add protocol mac/MacDroidCore android/core docs/handoff
git commit -m "feat(protocol): photos messages and photo transfer purpose

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Android core — queries, cursor, access, and photo transfers in the engine

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/photos/PhotoQueries.kt`
- Modify: `android/core/src/main/kotlin/dev/macdroid/core/transfer/TransferModel.kt`, `TransferEngine.kt`
- Test: `android/core/src/test/kotlin/dev/macdroid/core/photos/PhotoQueriesTest.kt`, `android/core/src/test/kotlin/dev/macdroid/core/transfer/TransferEnginePhotoTest.kt`

**Interfaces (produces):**
- `object PhotoQueries`, with:
  - `MAX_PAGE = 200`, `MAX_THUMBS = 60`
  - `pageLimit(requested: Int): Int`
  - `thumbIds(ids: List<String>): List<String>`
  - `isValidId(id: String): Boolean`
  - `encodeCursor(dateAddedSeconds: Long, id: Long): String` and `decodeCursor(cursor: String?): Pair<Long, Long>?`
  - `selection(albumId: String, kind: PhotoKindFilter, cursor: String?): Pair<String, Array<String>>`
  - `SORT = "date_added DESC, _id DESC"`
  - `access(api: Int, images: Boolean, video: Boolean, selectedOnly: Boolean, legacyStorage: Boolean): PhotoAccess`
- `TransferRecord.isHidden` (clipboard or photo).
- `TransferEngine.enqueueWithId(id: String, file: OutgoingFile, purpose: TransferPurpose): Boolean` (false when the id is already known).

- [ ] **Step 1: Failing tests**

`PhotoQueriesTest.kt`:

```kotlin
package dev.macdroid.core.photos

import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.PhotoKindFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoQueriesTest {
    @Test fun cursorRoundTrips() {
        assertEquals("1791892800:1001", PhotoQueries.encodeCursor(1_791_892_800, 1001))
        assertEquals(1_791_892_800L to 1001L, PhotoQueries.decodeCursor("1791892800:1001"))
        assertNull(PhotoQueries.decodeCursor("nonsense"))
        assertNull(PhotoQueries.decodeCursor(null))
        assertNull(PhotoQueries.decodeCursor("1:2:3"))
    }

    // Review Focus #2
    @Test fun cursorSelectsStrictlyOlderItems() {
        val (sql, args) = PhotoQueries.selection("all", PhotoKindFilter.All, "1791892800:1001")
        assertEquals("media_type IN (1,3) AND (date_added < ? OR (date_added = ? AND _id < ?))", sql)
        assertArrayEquals(arrayOf("1791892800", "1791892800", "1001"), args)
    }

    @Test fun albumAndKindFilters() {
        val (sql, args) = PhotoQueries.selection("-1739773001", PhotoKindFilter.Videos, null)
        assertEquals("media_type IN (3) AND bucket_id = ?", sql)
        assertArrayEquals(arrayOf("-1739773001"), args)
        assertEquals("media_type IN (1)", PhotoQueries.selection("all", PhotoKindFilter.Photos, null).first)
    }

    @Test fun limitsAndIds() {
        assertEquals(200, PhotoQueries.pageLimit(10_000))
        assertEquals(1, PhotoQueries.pageLimit(0))
        val ids = (1..80).map { it.toString() } + listOf("../x", "12/../3", "", "5")
        assertEquals((1..60).map { it.toString() }, PhotoQueries.thumbIds(ids))
        assertTrue(PhotoQueries.isValidId("1000"))
        assertFalse(PhotoQueries.isValidId("../x"))
        assertFalse(PhotoQueries.isValidId("1".repeat(21)))
    }

    @Test fun accessLevels() {
        assertEquals(PhotoAccess.Full, PhotoQueries.access(api = 36, images = true, video = true, selectedOnly = false, legacyStorage = false))
        assertEquals(PhotoAccess.Partial, PhotoQueries.access(api = 36, images = false, video = false, selectedOnly = true, legacyStorage = false))
        assertEquals(PhotoAccess.None, PhotoQueries.access(api = 36, images = false, video = false, selectedOnly = false, legacyStorage = false))
        assertEquals(PhotoAccess.Full, PhotoQueries.access(api = 31, images = false, video = false, selectedOnly = false, legacyStorage = true))
        assertEquals(PhotoAccess.None, PhotoQueries.access(api = 31, images = false, video = false, selectedOnly = false, legacyStorage = false))
    }
}
```

`TransferEnginePhotoTest.kt`:

```kotlin
package dev.macdroid.core.transfer

import dev.macdroid.core.eventually
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.TransferPurpose
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferEnginePhotoTest {
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    @Test fun enqueueWithIdUsesTheMacsIdAndPurpose() {
        h.start()
        val file = File(h.dir, "IMG.jpg").apply { writeBytes(testBytes(500)) }
        assertTrue(h.engine.enqueueWithId("7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", OutgoingFile(file.path, "IMG.jpg", 500, "image/jpeg"), TransferPurpose.Photo))
        eventually { h.sentOf<Message.TransferOffer>().any { it.body.transferId == "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f" && it.body.purpose == TransferPurpose.Photo } }
        assertTrue(h.engine.snapshot.value.files().active.isEmpty()) // hidden from the phone's Files UI
    }

    @Test fun aRepeatedIdIsIgnored() {
        h.start()
        val file = File(h.dir, "IMG.jpg").apply { writeBytes(testBytes(500)) }
        val outgoing = OutgoingFile(file.path, "IMG.jpg", 500, "image/jpeg")
        assertTrue(h.engine.enqueueWithId("dup", outgoing, TransferPurpose.Photo))
        eventually { h.engine.snapshot.value.active.any { it.id == "dup" } }
        assertFalse(h.engine.enqueueWithId("dup", outgoing, TransferPurpose.Photo))
        Thread.sleep(100)
        assertEquals(1, h.sentOf<Message.TransferOffer>().count { it.body.transferId == "dup" })
    }
}
```

Run `./gradlew :core:test --tests 'dev.macdroid.core.photos.*' --tests 'dev.macdroid.core.transfer.TransferEnginePhotoTest'`. Expected: compilation fails.

- [ ] **Step 2: Implement**

`PhotoQueries.kt`:

```kotlin
package dev.macdroid.core.photos

import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.PhotoKindFilter

/**
 * Pure rules for the phone's photo library (spec §3–4). Ordering, paging and grouping use DATE_ADDED then _ID
 * (plan ordering ruling): DATE_TAKEN can be null, and MediaProvider rejects SQL functions in selections.
 */
object PhotoQueries {
    const val MAX_PAGE = 200
    const val MAX_THUMBS = 60
    const val SORT = "date_added DESC, _id DESC"
    private const val IMAGE = 1 // MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
    private const val VIDEO = 3 // MEDIA_TYPE_VIDEO

    fun pageLimit(requested: Int): Int = requested.coerceIn(1, MAX_PAGE)

    fun isValidId(id: String): Boolean = id.length in 1..20 && id.all(Char::isDigit)

    fun thumbIds(ids: List<String>): List<String> = ids.filter(::isValidId).distinct().take(MAX_THUMBS)

    fun encodeCursor(dateAddedSeconds: Long, id: Long): String = "$dateAddedSeconds:$id"

    fun decodeCursor(cursor: String?): Pair<Long, Long>? {
        val parts = cursor?.split(':') ?: return null
        if (parts.size != 2) return null
        val added = parts[0].toLongOrNull() ?: return null
        val id = parts[1].toLongOrNull() ?: return null
        return added to id
    }

    /** SQL selection and its arguments for one page (newest first, strictly older than [cursor]). */
    fun selection(albumId: String, kind: PhotoKindFilter, cursor: String?): Pair<String, Array<String>> {
        val types = when (kind) {
            PhotoKindFilter.All -> "$IMAGE,$VIDEO"
            PhotoKindFilter.Photos -> "$IMAGE"
            PhotoKindFilter.Videos -> "$VIDEO"
        }
        val clauses = mutableListOf("media_type IN ($types)")
        val args = mutableListOf<String>()
        if (albumId != "all") {
            clauses += "bucket_id = ?"
            args += albumId
        }
        decodeCursor(cursor)?.let { (added, id) ->
            clauses += "(date_added < ? OR (date_added = ? AND _id < ?))"
            args += listOf(added.toString(), added.toString(), id.toString())
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    /** spec §2: what the user granted, by Android version. */
    fun access(api: Int, images: Boolean, video: Boolean, selectedOnly: Boolean, legacyStorage: Boolean): PhotoAccess = when {
        api >= 33 && (images || video) -> PhotoAccess.Full
        api >= 34 && selectedOnly -> PhotoAccess.Partial
        api < 33 && legacyStorage -> PhotoAccess.Full
        else -> PhotoAccess.None
    }
}
```

`TransferModel.kt`:
- Add `val isHidden: Boolean get() = isClipboard || purpose == TransferPurpose.Photo` to `TransferRecord`.
- In `TransferSnapshot.files()`, replace every `isClipboard` with `isHidden`.

`TransferEngine.kt`:
- In `archive(...)`, change `if (record.isClipboard)` to `if (record.isHidden)`.
- In `load()`, change the `record.isClipboard ->` branch to `record.isHidden ->`.
- Add:

```kotlin
    /** Sends [file] to the Mac under an id the Mac chose (spec §3 `photos.fetch`). False if that id is already known. */
    fun enqueueWithId(id: String, file: OutgoingFile, purpose: TransferPurpose): Boolean {
        if (_snapshot.value.active.any { it.id == id }) return false
        val now = clock()
        val record = TransferRecord(id = id, direction = Direction.ToMac, name = file.name, size = file.size, mime = file.mime,
            phase = Phase.Queued, source = file.source, purpose = purpose, createdAt = now, updatedAt = now)
        scope.launch(control) {
            if (record(id) != null) return@launch
            add(record)
            persist()
            pump()
        }
        return true
    }
```

(`aRepeatedIdIsIgnored` waits until the first record is active before calling again, so the check is deterministic.)

Run `./gradlew :core:test`. Expected: PASS. Existing clipboard tests still pass, because clipboard records are hidden too.

- [ ] **Step 3: Handoff, commit**

CHANGELOG bullet: "Task 2: Android core `PhotoQueries` (cursor, SQL, limits, access) and `TransferEngine.enqueueWithId`; photo transfers hidden like clipboard ones."

```bash
git add android/core docs/handoff
git commit -m "feat(android-core): photo queries and photo transfers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Mac core — photo destinations in `TransferManager`

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Transfer/TransferModel.swift`, `TransferManager.swift`
- Modify: `mac/MacDroid/AppModel.swift` and `mac/MacDroidCore/Sources/MacDroidDevServer/main.swift` (both switch exhaustively over `TransferEvent`)
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/TransferPhotoTests.swift`

**Interfaces (produces):**
- `TransferRecord.hidden: Bool?` (the last init parameter, `hidden: Bool? = nil`) and `isHidden: Bool`.
- `TransferManager.init(…, clipboardDirectory:, photoCacheDirectory: URL? = nil, photoSaveFolder: @escaping @Sendable () async -> URL? = { nil }, now:, send:)`.
- `TransferManager.expectPhoto(transferId: String, toCache: Bool)`, `forgetPhoto(transferId: String)` and `hasTransfer(_ id: String) -> Bool`.
- `TransferEvent.photoReceived(transferId: String, url: URL)` and `TransferEvent.photoFailed(transferId: String)`.

- [ ] **Step 1: Failing tests**

```swift
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct TransferPhotoTests {
    func manager(_ f: TransferFixture, cache: URL) -> TransferManager {
        let phone = f.phone, clock = f.clock, save = f.downloads
        return TransferManager(
            stateDirectory: f.state, location: GateLocation(), freeSpace: { _ in nil },
            photoCacheDirectory: cache, photoSaveFolder: { save },
            now: { clock.now }, send: { try await phone.send($0) }
        )
    }

    func photoOffer(_ id: String, size: Int64) -> Message {
        .transferOffer(TransferOffer(transferId: id, name: "IMG_1.jpg", size: size, mime: "image/jpeg", purpose: .photo))
    }

    func listen(_ m: TransferManager) -> (Collector<TransferEvent>, Task<Void, Never>) {
        let events = Collector<TransferEvent>()
        let stream = m.events
        return (events, Task { for await e in stream { await events.append(e) } })
    }

    // Review Focus #1
    @Test func unrequestedPhotoOffersAreRejected() async throws {
        let f = try TransferFixture()
        let m = manager(f, cache: try makeTemporaryDirectory())
        await m.handle(photoOffer("p1", size: 10))
        try await eventually { await f.phone.sent.contains { $0.rejection == TransferReject(transferId: "p1", reason: .invalid) } }
        #expect(await m.snapshot.active.isEmpty)
        #expect(await !m.hasTransfer("p1"))
    }

    @Test func cacheFetchesAreHiddenAndAnnounced() async throws {
        let f = try TransferFixture()
        let cache = try makeTemporaryDirectory()
        let m = manager(f, cache: cache)
        let (events, listener) = listen(m)
        await m.expectPhoto(transferId: "p1", toCache: true)
        let data = testBytes(2_000)
        await m.handle(photoOffer("p1", size: Int64(data.count)))
        try await eventually { await f.phone.sent.contains { $0.acceptedID == "p1" } }
        #expect(await m.snapshot.active.isEmpty)
        #expect(await m.hasTransfer("p1"))
        try await upload(data, to: m, id: "p1")
        await m.handle(.transferFinish(TransferFinish(transferId: "p1", sha256: Checksum.sha256Hex(data))))
        try await eventually { await events.items.count == 1 }
        guard case .photoReceived("p1", let url) = await events.items[0] else { Issue.record("wrong event"); return }
        #expect(url.deletingLastPathComponent().standardizedFileURL == cache.standardizedFileURL)
        #expect(await m.snapshot.history.isEmpty)
        listener.cancel()
    }

    @Test func savesGoToTheSaveFolderWithoutAskingAndShowInHistory() async throws {
        let f = try TransferFixture()
        let m = manager(f, cache: try makeTemporaryDirectory())
        let (events, listener) = listen(m)
        await m.expectPhoto(transferId: "p2", toCache: false)
        let data = testBytes(1_000)
        await m.handle(photoOffer("p2", size: Int64(data.count)))
        try await eventually { await f.phone.sent.contains { $0.acceptedID == "p2" } } // GateLocation never answers: nothing asked
        try await upload(data, to: m, id: "p2")
        await m.handle(.transferFinish(TransferFinish(transferId: "p2", sha256: Checksum.sha256Hex(data))))
        try await eventually { await events.items.count == 2 }
        #expect(f.exists("IMG_1.jpg"))
        #expect(await m.snapshot.history.first?.name == "IMG_1.jpg")
        listener.cancel()
    }

    @Test func failedPhotoTransfersAreAnnounced() async throws {
        let f = try TransferFixture()
        let m = manager(f, cache: try makeTemporaryDirectory())
        let (events, listener) = listen(m)
        await m.expectPhoto(transferId: "p3", toCache: true)
        let data = testBytes(500)
        await m.handle(photoOffer("p3", size: Int64(data.count)))
        try await eventually { await f.phone.sent.contains { $0.acceptedID == "p3" } }
        try await upload(data, to: m, id: "p3")
        await m.handle(.transferFinish(TransferFinish(transferId: "p3", sha256: String(repeating: "0", count: 64))))
        try await eventually { await events.items.contains(.photoFailed(transferId: "p3")) }
        #expect(await !m.hasTransfer("p3"))
        listener.cancel()
    }

    @Test func cacheFetchesAreCancelledOnDisconnect() async throws {
        let f = try TransferFixture()
        let m = manager(f, cache: try makeTemporaryDirectory())
        let (events, listener) = listen(m)
        await m.connectionChanged(true)
        await m.expectPhoto(transferId: "p4", toCache: true)
        await m.handle(photoOffer("p4", size: 100))
        try await eventually { await m.hasTransfer("p4") && f.phone.sent.contains { $0.acceptedID == "p4" } }
        await m.connectionChanged(false)
        try await eventually { await events.items.contains(.photoFailed(transferId: "p4")) }
        #expect(await !m.hasTransfer("p4"))
        listener.cancel()
    }

    @Test func photoRecordsInTheCacheAreNotRestoredAfterARelaunch() {
        let now = Date()
        let record = TransferRecord(id: "p1", direction: .fromPhone, name: "a.jpg", size: 1, mime: "image/jpeg", phase: .active,
                                    folder: URL(fileURLWithPath: "/tmp"), createdAt: now, updatedAt: now, purpose: .photo, hidden: true)
        #expect(TransferManager.restore(active: [record], history: [], now: now).0.isEmpty)
    }
}
```

Run `cd mac/MacDroidCore && swift test --filter TransferPhotoTests`. Expected: compile errors (`photoCacheDirectory`, `expectPhoto`, `hidden`, `photoReceived`…).

- [ ] **Step 2: Implement — model**

In `TransferModel.swift`:
- Add `public var hidden: Bool?` after `purpose`, and the init parameter `hidden: Bool? = nil` (last), assigned in the body.
- Add:

```swift
    /// Hidden from Files and history: clipboard images, and photos fetched into the cache (spec §9.4).
    public var isHidden: Bool { isClipboard || hidden == true }
```

- Add the `TransferEvent` cases:

```swift
    /// A photo the Mac asked for (spec §5) arrived at `url` (the photo cache, or the save folder).
    case photoReceived(transferId: String, url: URL)
    /// A photo the Mac asked for ended without a file (failed, cancelled, or the Mac disconnected).
    case photoFailed(transferId: String)
```

- [ ] **Step 3: Implement — manager**

In `TransferManager.swift`:

1. Stored properties and init. Add the init parameters `photoCacheDirectory: URL? = nil` and `photoSaveFolder: @escaping @Sendable () async -> URL? = { nil }` right after `clipboardDirectory`. Assign them, and create the cache directory when it's given, as is done for `clipboardDirectory`.

```swift
    private let photoCacheDirectory: URL?
    private let photoSaveFolder: @Sendable () async -> URL?
    /// Photo fetches the Mac asked for: transfer id → true for the hidden cache, false for the save folder.
    private var photoRequests: [String: Bool] = [:]
```

2. The API:

```swift
    /// The Mac asked the phone for a photo under this transfer id (spec §3 `photos.fetch`); only such offers are accepted.
    public func expectPhoto(transferId: String, toCache: Bool) { photoRequests[transferId] = toCache }
    public func forgetPhoto(transferId: String) { photoRequests[transferId] = nil }
    /// True while a transfer with this id is in progress (offered, active, paused or verifying).
    public func hasTransfer(_ id: String) -> Bool { active.contains { $0.id == id } }
```

3. In `receiveOffer`, directly after the `if let past = history.first…` block and before `let isClipboard = …`:

```swift
        if offer.purpose == .photo {
            // Only photos the Mac asked for are written anywhere (Review Focus #1).
            guard let toCache = photoRequests[id], offer.size >= 0, !toCache || photoCacheDirectory != nil else {
                await sendOrDrop(.transferReject(TransferReject(transferId: id, reason: .invalid)))
                return
            }
            let name = FileNaming.sanitize(offer.name)
            active.append(TransferRecord(
                id: id, direction: .fromPhone, name: name, size: offer.size, mime: offer.mime, phase: .offered,
                createdAt: now(), updatedAt: now(), purpose: .photo, hidden: toCache
            ))
            persist()
            publish(force: true)
            let incoming = IncomingOffer(id: id, name: name, size: offer.size, mime: offer.mime)
            let cache = photoCacheDirectory
            let saveFolder = photoSaveFolder
            // Saves go to the received-files folder without asking (spec §9.2). If it's unusable (nil),
            // `decide` falls back to the normal location provider.
            decisions[id] = Task {
                let folder: URL? = toCache ? cache : await saveFolder()
                await self.decide(incoming, fixed: folder.map(SaveLocation.folder))
            }
            return
        }
```

4. Hidden records. Replace the clipboard-only checks with `isHidden` in:
   - `restore`: `if record.isHidden {`, with the comment "Clipboard content and cached photos are never resumed later."
   - `archive(_:)`: `if record.isHidden {`
   - `publish(force:)`: both filters become `!$0.isHidden`

   Leave the other `isClipboard` uses alone: they are clipboard-specific (the size limit, the disconnect cancel of outgoing clipboard images, `TransferManager+Sending`).

5. Announce photo outcomes at the one place every record ends. At the top of `archive(_:)`:

```swift
        if record.purpose == .photo {
            photoRequests[record.id] = nil
            if record.phase != .done { eventBroadcast.yield(.photoFailed(transferId: record.id)) }
        }
```

6. In `receiveFinish`, replace the final event yield with:

```swift
        let savedAt = folder.appending(path: name)
        if done.purpose == .photo {
            if !done.isHidden { eventBroadcast.yield(.received(done)) }
            eventBroadcast.yield(.photoReceived(transferId: id, url: savedAt))
        } else {
            eventBroadcast.yield(done.isClipboard ? .clipboardImageReceived(savedAt) : .received(done))
        }
```

7. In `connectionChanged(_:)`, inside the disconnected branch after the clipboard loop:

```swift
            // A preview or drag can't wait for a reconnect; saves (visible) resume like files.
            for record in active where record.purpose == .photo && record.isHidden {
                await cancel(id: record.id)
            }
```

Keep the app and dev server compiling:
- `AppModel.handle(_ event: TransferEvent)`: add `case .photoReceived, .photoFailed: break // Task 8`.
- `transferPrinter` in the dev server: add `case .photoReceived, .photoFailed: break // Task 9`.

Run `swift test` and the Mac app build (`scratchpad/macbuild.sh` or the CLAUDE.md command). Expected: everything passes with no warnings. The existing clipboard tests still pass, because `isHidden` includes clipboard.

- [ ] **Step 4: Handoff, commit**

CHANGELOG bullet: "Task 3: Mac `TransferManager` photo destinations: only expected ids are accepted; the cache is hidden and cancelled on disconnect; the save folder is visible and never asks; `photoReceived` / `photoFailed` events; `hidden` generalises clipboard hiding."

```bash
git add mac docs/handoff
git commit -m "feat(mac-core): photo destinations in the transfer manager

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Mac core — `ThumbnailCache`, `PhotosPresentation`, `PhotosClient`

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Photos/ThumbnailCache.swift`, `Photos/PhotosClient.swift`, `Presentation/PhotosPresentation.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/PhotosTests.swift`

**Interfaces:**
- **Consumes:**
  - the Task 1 photo messages
  - `Feature.photos`
  - Task 3's `TransferManager.expectPhoto` / `forgetPhoto` / `hasTransfer`
- **Produces:**
  - `ThumbnailCache(directory:limit:)`, with `isValid(id:)`, `save(id:jpegBase64:) -> URL?`, `url(for:) -> URL?`, `trim()` and `clear()`.
  - `PhotoDay(id, title, items)`.
  - `PhotosPresentation`, with:
    - `days(_:now:calendar:locale:)`
    - `duration(ms:)`
    - `selection(count:bytes:)`
    - `banner(access:visibleCount:)`
    - `phoneLine(_:)`
    - `message(_ error: PhotoFetchError)`
  - `PhotoSelection.click(_ id: String, command: Bool, shift: Bool, selection: Set<String>, anchor: String?, order: [String]) -> (selection: Set<String>, anchor: String?)`.
  - `PhotoFetchError` (`.gone`, `.denied`, `.noResponse`, `.notConnected`, `.unsupported`, `.failed`).
  - `PhotosState`, with `connected`, `phoneFeatures`, `access`, `albums`, `albumId`, `kind`, `items`, `hasMore`, `loading`, and `supported`.
  - `PhotosEvent.thumbnail(id:url:)`.
  - `PhotoTransfers(expect:forget:isActive:)` and `.manager(_:)`.
  - `PhotosClient`, with:
    - init `(thumbnails:transfers:now:fetchTimeout:makeId:send:)`
    - `attach(to:)`, `states`, `events`, `state`
    - `select(album:)`, `setKind(_:)`, `loadMore()`
    - `requestThumbnails(_:)`
    - `fetchFile(_ id: String, toCache: Bool) async -> Result<URL, PhotoFetchError>`
    - `transferFinished(transferId:url:)`, `transferFailed(transferId:)`
    - `reset()`

- [ ] **Step 1: Failing tests**

`PhotosTests.swift`:

```swift
import Foundation
import NIOConcurrencyHelpers
import Testing
@testable import MacDroidCore

private let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10]).base64EncodedString()

private func item(_ id: Int, _ seconds: Int64, kind: PhotoKind = .photo, size: Int64 = 1_000) -> PhotoItem {
    PhotoItem(id: String(id), kind: kind, name: "IMG_\(id).jpg", mime: "image/jpeg", takenAt: seconds * 1000, size: size, width: 4, height: 3)
}

@Suite struct ThumbnailCacheTests {
    // Review Focus #3
    @Test func invalidIdsAreRejected() throws {
        let cache = ThumbnailCache(directory: try makeTemporaryDirectory())
        for bad in ["../x", "12/../3", "", "1a", String(repeating: "1", count: 21), "１２"] {
            #expect(!ThumbnailCache.isValid(id: bad))
            #expect(cache.save(id: bad, jpegBase64: jpeg) == nil)
            #expect(cache.url(for: bad) == nil)
        }
        #expect(cache.save(id: "1000", jpegBase64: jpeg) != nil)
        #expect(cache.url(for: "1000") != nil)
    }

    @Test func onlySmallJpegsAreKept() throws {
        let cache = ThumbnailCache(directory: try makeTemporaryDirectory())
        #expect(cache.save(id: "1", jpegBase64: Data("not a jpeg".utf8).base64EncodedString()) == nil)
        #expect(cache.save(id: "2", jpegBase64: "%%%") == nil)
        #expect(cache.save(id: "3", jpegBase64: (Data([0xFF, 0xD8]) + Data(count: ThumbnailCache.maxThumbBytes)).base64EncodedString()) == nil)
    }

    @Test func trimDeletesTheLeastRecentlyUsed() throws {
        let directory = try makeTemporaryDirectory()
        let cache = ThumbnailCache(directory: directory, limit: 2_500)
        let body = (Data([0xFF, 0xD8]) + Data(count: 998)).base64EncodedString() // 1,000 bytes each
        for (n, id) in ["1", "2", "3"].enumerated() {
            let url = try #require(cache.save(id: id, jpegBase64: body))
            try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: TimeInterval(n + 1))], ofItemAtPath: url.path(percentEncoded: false))
        }
        _ = cache.url(for: "1") // used just now: no longer the oldest
        cache.trim()
        #expect(cache.url(for: "1") != nil)
        #expect(cache.url(for: "2") == nil)
        #expect(cache.url(for: "3") != nil)
    }
}

@Suite struct PhotosPresentationTests {
    let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()
    let locale = Locale(identifier: "en_GB")
    let now = Date(timeIntervalSince1970: 1_791_892_800) // 2026-10-13 08:00 UTC

    @Test func daysAreGroupedAndTitled() {
        let items = [item(5, 1_791_892_000), item(4, 1_791_880_000), item(3, 1_791_800_000), item(2, 1_791_600_000), item(1, 1_760_000_000)]
        let days = PhotosPresentation.days(items, now: now, calendar: calendar, locale: locale)
        #expect(days.map(\.title) == ["Today", "Yesterday", "10 Oct", "9 Oct 2025"])
        #expect(days.map { $0.items.map(\.id) } == [["5", "4"], ["3"], ["2"], ["1"]])
    }

    @Test func durationsAndSelection() {
        #expect(PhotosPresentation.duration(ms: 42_000) == "0:42")
        #expect(PhotosPresentation.duration(ms: 3_723_000) == "1:02:03")
        #expect(PhotosPresentation.selection(count: 1, bytes: 3_100_000) == "1 selected · 3.1 MB")
    }

    @Test func accessCopy() {
        #expect(PhotosPresentation.banner(access: .full, visibleCount: 10) == nil)
        #expect(PhotosPresentation.banner(access: .partial, visibleCount: 24) == "MacDroid can see 24 selected photos. To see all, open MacDroid on your phone → Settings → Photos.")
        #expect(PhotosPresentation.banner(access: PhotoAccess.none, visibleCount: 0) == "Photos are off on your phone. Open MacDroid on your phone → Settings → Photos.")
        #expect(PhotosPresentation.phoneLine(.full) == "All")
        #expect(PhotosPresentation.phoneLine(.partial) == "Selected")
        #expect(PhotosPresentation.phoneLine(PhotoAccess.none) == "Off on phone")
        #expect(PhotosPresentation.phoneLine(nil) == nil)
        #expect(PhotosPresentation.message(.gone) == "This photo is no longer on your phone")
        #expect(PhotosPresentation.message(.noResponse) == "Your phone didn't respond")
        #expect(PhotosPresentation.message(.unsupported) == "Update MacDroid on your phone to use this.")
    }
}

@Suite struct PhotoSelectionTests {
    let order = ["5", "4", "3", "2", "1"]

    @Test func plainClickSelectsOnlyThatItem() {
        let r = PhotoSelection.click("3", command: false, shift: false, selection: ["5", "4"], anchor: "5", order: order)
        #expect(r.selection == ["3"])
        #expect(r.anchor == "3")
    }

    @Test func commandClickToggles() {
        var r = PhotoSelection.click("3", command: true, shift: false, selection: ["5"], anchor: "5", order: order)
        #expect(r.selection == ["5", "3"])
        r = PhotoSelection.click("5", command: true, shift: false, selection: r.selection, anchor: r.anchor, order: order)
        #expect(r.selection == ["3"])
    }

    @Test func shiftClickSelectsTheRangeFromTheAnchor() {
        let r = PhotoSelection.click("2", command: false, shift: true, selection: ["4"], anchor: "4", order: order)
        #expect(r.selection == ["4", "3", "2"])
        #expect(r.anchor == "4")
        let back = PhotoSelection.click("5", command: false, shift: true, selection: r.selection, anchor: "4", order: order)
        #expect(back.selection == ["5", "4"])
        let noAnchor = PhotoSelection.click("2", command: false, shift: true, selection: [], anchor: nil, order: order)
        #expect(noAnchor.selection == ["2"])
    }
}

@Suite struct PhotosClientTests {
    final class Harness: Sendable {
        let client: PhotosClient
        let sent: Collector<Message>
        let expected: Collector<String>
        let forgotten: Collector<String>
        let thumbs: Collector<PhotosEvent>
        let active: NIOLockedValueBox<Set<String>>
        let clock: FakeClock
        let listener: Task<Void, Never>

        init(timeout: Duration = .seconds(30)) throws {
            let sent = Collector<Message>(), expected = Collector<String>(), forgotten = Collector<String>(), thumbs = Collector<PhotosEvent>()
            let active = NIOLockedValueBox<Set<String>>([])
            let clock = FakeClock()
            let counter = NIOLockedValueBox(0)
            let client = PhotosClient(
                thumbnails: ThumbnailCache(directory: try makeTemporaryDirectory()),
                transfers: PhotoTransfers(
                    expect: { id, _ in await expected.append(id) },
                    forget: { await forgotten.append($0) },
                    isActive: { id in active.withLockedValue { $0.contains(id) } }
                ),
                now: { clock.now },
                fetchTimeout: timeout,
                makeId: { counter.withLockedValue { $0 += 1; return "id\($0)" } },
                send: { await sent.append($0) }
            )
            self.client = client
            self.sent = sent
            self.expected = expected
            self.forgotten = forgotten
            self.thumbs = thumbs
            self.active = active
            self.clock = clock
            let stream = client.events
            listener = Task { for await event in stream { await thumbs.append(event) } }
        }

        func connect(features: [String] = ProtocolInfo.features) async {
            await client.connectionChanged(.connected(PeerInfo(device: "Phone", app: "0.6.0", features: features)))
        }

        /// Connects and answers the first page (request "id2") with `items`.
        func connectWith(_ items: [PhotoItem], next: String? = nil) async {
            await connect()
            await client.handle(.photosPage(PhotosPage(requestId: "id2", access: .full, items: items, nextCursor: next)))
        }

        func fetches() async -> [PhotosFetch] {
            await sent.items.compactMap { if case .photosFetch(let body) = $0 { return body }; return nil }
        }
    }

    @Test func connectingLoadsAlbumsAndTheFirstPage() async throws {
        let h = try Harness()
        await h.connect()
        #expect(await h.sent.items == [
            .photosAlbums(PhotosAlbumsRequest(requestId: "id1")),
            .photosQuery(PhotosQuery(requestId: "id2", albumId: "all", kind: .all, cursor: nil, limit: 200)),
        ])
        await h.client.handle(.photosAlbumList(PhotosAlbumList(requestId: "id1", access: .full, albums: [PhotoAlbum(id: "all", name: "All Photos", count: 2)])))
        await h.client.handle(.photosPage(PhotosPage(requestId: "id2", access: .full, items: [item(9, 100), item(8, 90)], nextCursor: "90:8")))
        let state = await h.client.state
        #expect(state.items.map(\.id) == ["9", "8"])
        #expect(state.hasMore)
        #expect(!state.loading)
        #expect(state.access == .full)
        #expect(state.albums.map(\.id) == ["all"])
    }

    @Test func aPhoneWithoutPhotosIsUnsupported() async throws {
        let h = try Harness()
        await h.connect(features: ["files"])
        #expect(await h.sent.items.isEmpty)
        #expect(await h.client.fetchFile("9", toCache: true) == .failure(.unsupported))
    }

    @Test func staleResponsesAreIgnored() async throws {
        let h = try Harness()
        await h.connect()
        await h.client.select(album: "-17")
        await h.client.handle(.photosPage(PhotosPage(requestId: "id2", access: .full, items: [item(9, 100)])))
        #expect(await h.client.state.items.isEmpty)
        await h.client.handle(.photosPage(PhotosPage(requestId: "id3", access: .full, items: [item(5, 50)])))
        #expect(await h.client.state.items.map(\.id) == ["5"])
        #expect(await h.client.state.albumId == "-17")
    }

    @Test func loadMoreUsesTheCursorAndSkipsDuplicates() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100), item(8, 90)], next: "90:8")
        await h.client.loadMore()
        #expect(await h.sent.items.last == .photosQuery(PhotosQuery(requestId: "id3", albumId: "all", kind: .all, cursor: "90:8", limit: 200)))
        await h.client.handle(.photosPage(PhotosPage(requestId: "id3", access: .full, items: [item(8, 90), item(7, 80)])))
        #expect(await h.client.state.items.map(\.id) == ["9", "8", "7"])
        #expect(await !h.client.state.hasMore)
        let count = await h.sent.items.count
        await h.client.loadMore()
        #expect(await h.sent.items.count == count)
    }

    @Test func thumbnailsAreRequestedOnceAndExpireAfterTwentySeconds() async throws {
        let h = try Harness()
        await h.connect()
        await h.client.requestThumbnails(["1", "2", "../x", "1"])
        #expect(await h.sent.items.last == .photosThumbs(PhotosThumbs(ids: ["1", "2"])))
        let count = await h.sent.items.count
        await h.client.requestThumbnails(["1", "2"])
        #expect(await h.sent.items.count == count)
        await h.client.handle(.photosThumb(PhotosThumb(id: "1", jpeg: jpeg)))
        try await eventually { await h.thumbs.items.count == 1 }
        h.clock.advance(21)
        await h.client.requestThumbnails(["1", "2"])
        #expect(await h.sent.items.last == .photosThumbs(PhotosThumbs(ids: ["2"]))) // 1 is cached now
    }

    @Test func thumbnailRequestsAreBatchedBySixty() async throws {
        let h = try Harness()
        await h.connect()
        await h.client.requestThumbnails((1...130).map(String.init))
        let batches = await h.sent.items.compactMap { if case .photosThumbs(let b) = $0 { return b.ids.count }; return nil }
        #expect(batches == [60, 60, 10])
    }

    @Test func aFetchCompletesWithTheFileAndIsReusedFromTheCache() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: true) }
        try await eventually { await h.fetches() == [PhotosFetch(id: "9", transferId: "id3")] }
        #expect(await h.expected.items == ["id3"])
        let file = try makeTemporaryDirectory().appending(path: "IMG_9.jpg")
        try Data([1]).write(to: file)
        await client.transferFinished(transferId: "id3", url: file)
        #expect(await fetch.value == .success(file))
        #expect(await client.fetchFile("9", toCache: true) == .success(file))
        #expect(await h.fetches().count == 1)
    }

    // Review Focus #4
    @Test func goneFetchRemovesTheItemAndExplains() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100), item(8, 90)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: false) }
        try await eventually { await h.fetches().count == 1 }
        await client.handle(.photosFetchFailed(PhotosFetchFailed(transferId: "id3", reason: .gone)))
        #expect(await fetch.value == .failure(.gone))
        #expect(await client.state.items.map(\.id) == ["8"])
        #expect(await h.forgotten.items == ["id3"])
        #expect(PhotosPresentation.message(.gone) == "This photo is no longer on your phone")
    }

    @Test func aDeniedFetchTurnsAccessOff() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: true) }
        try await eventually { await h.fetches().count == 1 }
        await client.handle(.photosFetchFailed(PhotosFetchFailed(transferId: "id3", reason: .denied)))
        #expect(await fetch.value == .failure(.denied))
        #expect(await client.state.access == PhotoAccess.none)
    }

    // Review Focus #5
    @Test func fetchesTimeOutWhenNoOfferArrives() async throws {
        let h = try Harness(timeout: .milliseconds(100))
        await h.connectWith([item(9, 100)])
        #expect(await h.client.fetchFile("9", toCache: true) == .failure(.noResponse))
        #expect(await h.forgotten.items == ["id3"])
    }

    @Test func aStartedTransferDoesNotTimeOut() async throws {
        let h = try Harness(timeout: .milliseconds(100))
        await h.connectWith([item(9, 100)])
        h.active.withLockedValue { $0.insert("id3") }
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: false) }
        try await Task.sleep(for: .milliseconds(300))
        let file = try makeTemporaryDirectory().appending(path: "IMG_9.jpg")
        await client.transferFinished(transferId: "id3", url: file)
        #expect(await fetch.value == .success(file))
    }

    @Test func onlyThreeFetchesAreAskedForAtOnceAndPreviewsGoFirst() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100), item(8, 90), item(7, 80), item(6, 70), item(5, 60)])
        let client = h.client
        let saves = (6...9).map { id in Task { await client.fetchFile(String(id), toCache: false) } } // id3…id6
        try await eventually { await h.fetches().count == 3 && h.expected.items.count == 4 }
        let preview = Task { await client.fetchFile("5", toCache: true) } // id7: jumps the waiting save
        try await eventually { await h.expected.items.count == 5 }
        let first = try #require(await h.fetches().first)
        await client.transferFinished(transferId: first.transferId, url: try makeTemporaryDirectory().appending(path: "a.jpg"))
        try await eventually { await h.fetches().count == 4 }
        #expect(await h.fetches().last?.id == "5")
        for fetch in await h.fetches() { await client.transferFailed(transferId: fetch.transferId) }
        try await eventually { await h.fetches().count == 5 }
        await client.connectionChanged(.disconnected)
        _ = await preview.value
        for save in saves { _ = await save.value }
    }

    @Test func aDisconnectFailsPendingFetches() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: true) }
        try await eventually { await h.fetches().count == 1 }
        await client.connectionChanged(.disconnected)
        #expect(await fetch.value == .failure(.notConnected))
        #expect(await client.fetchFile("9", toCache: true) == .failure(.notConnected))
    }

    @Test func aFailedTransferFailsTheFetch() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: true) }
        try await eventually { await h.fetches().count == 1 }
        await client.transferFailed(transferId: "id3")
        #expect(await fetch.value == .failure(.failed))
    }

    // Review Focus #2 (Mac side): new photos at the top don't drop what's already loaded.
    @Test func changedMergesTheNewFirstPageAndKeepsOlderPages() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100), item(8, 90)], next: "90:8")
        await h.client.loadMore()
        await h.client.handle(.photosPage(PhotosPage(requestId: "id3", access: .full, items: [item(7, 80)], nextCursor: "80:7")))
        await h.client.handle(.photosChanged)
        #expect(await h.sent.items.suffix(2) == [
            .photosAlbums(PhotosAlbumsRequest(requestId: "id4")),
            .photosQuery(PhotosQuery(requestId: "id5", albumId: "all", kind: .all, cursor: nil, limit: 200)),
        ])
        await h.client.handle(.photosPage(PhotosPage(requestId: "id5", access: .full, items: [item(10, 110), item(9, 100)], nextCursor: "100:9")))
        #expect(await h.client.state.items.map(\.id) == ["10", "9", "8", "7"])
        await h.client.loadMore()
        #expect(await h.sent.items.last == .photosQuery(PhotosQuery(requestId: "id6", albumId: "all", kind: .all, cursor: "80:7", limit: 200)))
    }

    @Test func anAlbumThatDisappearedFallsBackToAllPhotos() async throws {
        let h = try Harness()
        await h.connect()
        await h.client.select(album: "-17") // id3
        await h.client.handle(.photosChanged) // id4 albums, id5 merge page
        await h.client.handle(.photosAlbumList(PhotosAlbumList(requestId: "id4", access: .full, albums: [PhotoAlbum(id: "all", name: "All Photos", count: 1)])))
        #expect(await h.client.state.albumId == "all")
        #expect(await h.sent.items.last == .photosQuery(PhotosQuery(requestId: "id6", albumId: "all", kind: .all, cursor: nil, limit: 200)))
    }
}
```

The tests call `PhotosClient.handle(_:)` and `connectionChanged(_:)` directly. They are internal (not `public`), so `@testable` reaches them.

Run `swift test --filter 'ThumbnailCacheTests|PhotosPresentationTests|PhotoSelectionTests|PhotosClientTests'`. Expected: compile errors.

- [ ] **Step 2: Implement `ThumbnailCache`**

```swift
import Foundation

/// The phone's 256 px thumbnails (spec §5), in App Support/PhotoThumbs, trimmed least-recently-used first.
/// Stateless apart from the files themselves, so it's safe to share.
public final class ThumbnailCache: Sendable {
    public static let defaultLimit: Int64 = 200_000_000
    public static let maxThumbBytes = 512 * 1024
    public let directory: URL
    private let limit: Int64

    public init(directory: URL, limit: Int64 = ThumbnailCache.defaultLimit) {
        self.directory = directory
        self.limit = limit
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    /// MediaStore ids are ASCII decimal; anything else never becomes a path (Review Focus #3).
    public static func isValid(id: String) -> Bool {
        (1...20).contains(id.utf8.count) && id.utf8.allSatisfy { (48...57).contains($0) }
    }

    @discardableResult
    public func save(id: String, jpegBase64: String) -> URL? {
        guard Self.isValid(id: id), let data = Data(base64Encoded: jpegBase64), data.count <= Self.maxThumbBytes,
              data.starts(with: [0xFF, 0xD8]) else { return nil }
        let url = file(id)
        do { try data.write(to: url, options: .atomic) } catch { return nil }
        return url
    }

    /// The cached thumbnail, marked as just used.
    public func url(for id: String) -> URL? {
        guard Self.isValid(id: id) else { return nil }
        let url = file(id)
        let path = url.path(percentEncoded: false)
        guard FileManager.default.fileExists(atPath: path) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: path)
        return url
    }

    /// Deletes the least recently used thumbnails until the folder fits the limit.
    public func trim() {
        let keys: Set<URLResourceKey> = [.contentModificationDateKey, .fileSizeKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: Array(keys)) else { return }
        var entries = files.compactMap { url -> (url: URL, used: Date, size: Int64)? in
            guard let values = try? url.resourceValues(forKeys: keys) else { return nil }
            return (url, values.contentModificationDate ?? .distantPast, Int64(values.fileSize ?? 0))
        }
        var total = entries.reduce(Int64(0)) { $0 + $1.size }
        guard total > limit else { return }
        entries.sort { $0.used < $1.used }
        for entry in entries where total > limit {
            try? FileManager.default.removeItem(at: entry.url)
            total -= entry.size
        }
    }

    /// Forget every thumbnail (another phone was paired).
    public func clear() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        for url in files { try? FileManager.default.removeItem(at: url) }
    }

    private func file(_ id: String) -> URL { directory.appending(path: id + ".jpg") }
}
```

- [ ] **Step 3: Implement `PhotosPresentation`**

```swift
import Foundation

public struct PhotoDay: Identifiable, Equatable, Sendable {
    /// yyyy-MM-dd in the calendar's time zone.
    public let id: String
    public let title: String
    public var items: [PhotoItem]
}

/// Text and grouping for the Mac Photos page (spec §5).
public enum PhotosPresentation {
    /// Groups newest-first items by day: "Today", "Yesterday", "12 Oct", or "12 Oct 2025" for another year.
    public static func days(_ items: [PhotoItem], now: Date, calendar: Calendar = .current, locale: Locale = .current) -> [PhotoDay] {
        let titles = DayTitles(now: now, calendar: calendar, locale: locale)
        var result: [PhotoDay] = []
        for item in items {
            let date = Date(timeIntervalSince1970: TimeInterval(item.takenAt) / 1000)
            let parts = calendar.dateComponents([.year, .month, .day], from: date)
            let key = String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
            if result.last?.id == key {
                result[result.count - 1].items.append(item)
            } else {
                result.append(PhotoDay(id: key, title: titles.title(for: date), items: [item]))
            }
        }
        return result
    }

    /// Two formatters per call, not one per day: the grid regroups on every update.
    private struct DayTitles {
        let now: Date
        let calendar: Calendar
        let sameYear: DateFormatter
        let otherYear: DateFormatter

        init(now: Date, calendar: Calendar, locale: Locale) {
            self.now = now
            self.calendar = calendar
            func formatter(_ template: String) -> DateFormatter {
                let f = DateFormatter()
                f.calendar = calendar
                f.timeZone = calendar.timeZone
                f.locale = locale
                f.setLocalizedDateFormatFromTemplate(template)
                return f
            }
            sameYear = formatter("dMMM")
            otherYear = formatter("dMMMyyyy")
        }

        func title(for date: Date) -> String {
            if calendar.isDate(date, inSameDayAs: now) { return "Today" }
            if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: yesterday) { return "Yesterday" }
            let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: now)
            return (sameYear ? self.sameYear : otherYear).string(from: date)
        }
    }

    /// "0:42", or "1:02:03" past an hour.
    public static func duration(ms: Int64) -> String {
        let total = max(0, ms / 1000)
        let hours = total / 3600, minutes = (total % 3600) / 60, seconds = total % 60
        return hours > 0 ? String(format: "%d:%02d:%02d", hours, minutes, seconds) : String(format: "%d:%02d", minutes, seconds)
    }

    public static func selection(count: Int, bytes: Int64) -> String {
        "\(count) selected · \(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file))"
    }

    public static func banner(access: PhotoAccess?, visibleCount: Int) -> String? {
        switch access {
        case .partial:
            "MacDroid can see \(visibleCount) selected \(visibleCount == 1 ? "photo" : "photos"). To see all, open MacDroid on your phone → Settings → Photos."
        case PhotoAccess.none:
            "Photos are off on your phone. Open MacDroid on your phone → Settings → Photos."
        default:
            nil
        }
    }

    /// The Phone page's "Photos" line.
    public static func phoneLine(_ access: PhotoAccess?) -> String? {
        switch access {
        case .full: "All"
        case .partial: "Selected"
        case PhotoAccess.none: "Off on phone"
        default: nil
        }
    }

    public static func message(_ error: PhotoFetchError) -> String {
        switch error {
        case .gone: "This photo is no longer on your phone"
        case .denied: "Photos are off on your phone. Open MacDroid on your phone → Settings → Photos."
        case .noResponse: "Your phone didn't respond"
        case .notConnected: "Phone not connected"
        case .unsupported: "Update MacDroid on your phone to use this."
        case .failed: "Couldn't get this photo from your phone"
        }
    }
}
```

`PhotoAccess.none` is always written in full. A bare `.none` on a `PhotoAccess?` means `nil`.

Add to the same file, for the grid's selection (spec §5.2):

```swift
/// Finder-style selection over the loaded items (spec §5.2): click, ⌘-click and ⇧-click.
public enum PhotoSelection {
    /// `order` is the grid's order (newest first); `anchor` is where the last plain or ⌘-click landed.
    public static func click(_ id: String, command: Bool, shift: Bool, selection: Set<String>, anchor: String?, order: [String])
        -> (selection: Set<String>, anchor: String?) {
        if shift, let anchor, let from = order.firstIndex(of: anchor), let to = order.firstIndex(of: id) {
            return (Set(order[min(from, to)...max(from, to)]), anchor)
        }
        if command {
            var next = selection
            if next.remove(id) == nil { next.insert(id) }
            return (next, id)
        }
        return ([id], id)
    }
}
```

- [ ] **Step 4: Implement `PhotosClient`**

```swift
import Foundation

public enum PhotoFetchError: Error, Equatable, Sendable { case gone, denied, noResponse, notConnected, unsupported, failed }

public struct PhotosState: Equatable, Sendable {
    public var connected = false
    public var phoneFeatures: Set<String> = []
    /// What the phone granted, from its last album list or page; nil until it answers.
    public var access: PhotoAccess?
    public var albums: [PhotoAlbum] = []
    public var albumId = "all"
    public var kind: PhotoKindFilter = .all
    /// Newest first (DATE_ADDED, then id).
    public var items: [PhotoItem] = []
    public var hasMore = false
    public var loading = false

    public var supported: Bool { connected && phoneFeatures.contains(Feature.photos) }
    public init() {}
    public static let empty = PhotosState()
}

public enum PhotosEvent: Equatable, Sendable {
    /// A thumbnail arrived and is in the cache.
    case thumbnail(id: String, url: URL)
}

/// What `PhotosClient` needs from `TransferManager`, as closures so tests can stand in.
public struct PhotoTransfers: Sendable {
    public var expect: @Sendable (_ transferId: String, _ toCache: Bool) async -> Void
    public var forget: @Sendable (_ transferId: String) async -> Void
    public var isActive: @Sendable (_ transferId: String) async -> Bool

    public init(
        expect: @escaping @Sendable (String, Bool) async -> Void,
        forget: @escaping @Sendable (String) async -> Void,
        isActive: @escaping @Sendable (String) async -> Bool
    ) {
        self.expect = expect
        self.forget = forget
        self.isActive = isActive
    }

    public static func manager(_ manager: TransferManager) -> PhotoTransfers {
        PhotoTransfers(
            expect: { await manager.expectPhoto(transferId: $0, toCache: $1) },
            forget: { await manager.forgetPhoto(transferId: $0) },
            isActive: { await manager.hasTransfer($0) }
        )
    }
}

/// Browses the phone's photos (spec §3, §5): albums, pages, thumbnails, and fetching originals over the transfer engine.
public actor PhotosClient {
    public static let pageSize = 200
    public static let thumbBatch = 60
    public static let thumbExpiry: TimeInterval = 20
    /// The phone offers at most 3 uploads at once (`TransferEngine.MAX_ACTIVE_OUTGOING`); asking for more would time out.
    public static let maxConcurrentFetches = 3
    static let trimEvery = 500

    public nonisolated var states: AsyncStream<PhotosState> { stateBroadcast.stream() }
    public nonisolated var events: AsyncStream<PhotosEvent> { eventBroadcast.stream() }
    public private(set) var state = PhotosState.empty

    private enum PageMode { case replace, append, merge }
    private struct Pending {
        let photoId: String
        let toCache: Bool
        let continuation: CheckedContinuation<Result<URL, PhotoFetchError>, Never>
    }

    private let stateBroadcast = Broadcast<PhotosState>(replayLatest: true, initial: .empty)
    private let eventBroadcast = Broadcast<PhotosEvent>(replayLatest: false)
    private let thumbnails: ThumbnailCache
    private let transfers: PhotoTransfers
    private let now: @Sendable () -> Date
    private let fetchTimeout: Duration
    private let makeId: @Sendable () -> String
    private let send: @Sendable (Message) async throws -> Void
    private var tasks: [Task<Void, Never>] = []
    private var albumRequest: String?
    private var pageRequest: (id: String, mode: PageMode)?
    private var cursor: String?
    private var thumbsInFlight: [String: Date] = [:]
    private var thumbsSinceTrim = 0
    private var pending: [String: Pending] = [:] // by transfer id
    private var waiting: [String] = [] // transfer ids not asked for yet; previews and drags first
    private var asking: Set<String> = [] // transfer ids the phone was asked for
    private var cachedOriginals: [String: URL] = [:] // photo id → file in the photo cache

    public init(
        thumbnails: ThumbnailCache,
        transfers: PhotoTransfers,
        now: @escaping @Sendable () -> Date = { Date() },
        fetchTimeout: Duration = .seconds(30),
        makeId: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() },
        send: @escaping @Sendable (Message) async throws -> Void
    ) {
        self.thumbnails = thumbnails
        self.transfers = transfers
        self.now = now
        self.fetchTimeout = fetchTimeout
        self.makeId = makeId
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

    // MARK: Browsing

    public func select(album: String) async {
        guard album != state.albumId else { return }
        update { $0.albumId = album }
        await reload()
    }

    public func setKind(_ kind: PhotoKindFilter) async {
        guard kind != state.kind else { return }
        update { $0.kind = kind }
        await reload()
    }

    public func loadMore() async {
        guard state.supported, pageRequest == nil, let cursor else { return }
        await requestPage(cursor: cursor, mode: .append)
    }

    /// Asks for thumbnails not cached and not already asked for in the last 20 s, at most 60 per message.
    public func requestThumbnails(_ ids: [String]) async {
        guard state.supported else { return }
        let at = now()
        thumbsInFlight = thumbsInFlight.filter { at.timeIntervalSince($0.value) < Self.thumbExpiry }
        var seen = Set<String>()
        let wanted = ids.filter {
            ThumbnailCache.isValid(id: $0) && thumbsInFlight[$0] == nil && seen.insert($0).inserted && thumbnails.url(for: $0) == nil
        }
        for id in wanted { thumbsInFlight[id] = at }
        for start in stride(from: 0, to: wanted.count, by: Self.thumbBatch) {
            let batch = Array(wanted[start..<min(start + Self.thumbBatch, wanted.count)])
            try? await send(.photosThumbs(PhotosThumbs(ids: batch)))
        }
    }

    /// Forgets everything about the current phone (it was unpaired).
    public func reset() {
        albumRequest = nil
        pageRequest = nil
        cursor = nil
        thumbsInFlight = [:]
        cachedOriginals = [:]
        thumbnails.clear()
        let connected = state.connected, features = state.phoneFeatures
        update {
            $0 = .empty
            $0.connected = connected
            $0.phoneFeatures = features
        }
    }

    // MARK: Originals

    /// Fetches the original into the hidden cache (preview, drag) or the save folder (Save to Mac).
    public func fetchFile(_ id: String, toCache: Bool) async -> Result<URL, PhotoFetchError> {
        guard state.connected else { return .failure(.notConnected) }
        guard state.supported else { return .failure(.unsupported) }
        if toCache, let url = cachedOriginals[id], FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) {
            return .success(url)
        }
        let transferId = makeId()
        await transfers.expect(transferId, toCache)
        return await withCheckedContinuation { continuation in
            pending[transferId] = Pending(photoId: id, toCache: toCache, continuation: continuation)
            if toCache {
                // Someone is waiting to see this one: ahead of queued saves.
                let firstSave = waiting.firstIndex { !(pending[$0]?.toCache ?? false) } ?? waiting.endIndex
                waiting.insert(transferId, at: firstSave)
            } else {
                waiting.append(transferId)
            }
            startWaiting()
        }
    }

    public func transferFinished(transferId: String, url: URL) async {
        await finish(transferId, .success(url))
    }

    public func transferFailed(transferId: String) async {
        await finish(transferId, .failure(.failed))
    }

    // MARK: Messages

    func handle(_ message: Message) async {
        switch message {
        case .photosAlbumList(let body):
            guard body.requestId == albumRequest else { return }
            albumRequest = nil
            let keep = body.albums.contains { $0.id == state.albumId }
            update {
                $0.access = body.access
                $0.albums = body.albums
            }
            if !keep {
                update { $0.albumId = "all" }
                await reload()
            }
        case .photosPage(let body):
            guard let request = pageRequest, request.id == body.requestId else { return }
            pageRequest = nil
            let items = body.items.filter { ThumbnailCache.isValid(id: $0.id) }
            var nextCursor = body.nextCursor
            update { s in
                s.access = body.access
                s.loading = false
                switch request.mode {
                case .replace:
                    s.items = items
                case .append:
                    let known = Set(s.items.map(\.id))
                    s.items += items.filter { !known.contains($0.id) }
                case .merge:
                    let merged = Self.merge(firstPage: items, firstCursor: body.nextCursor, old: s.items, oldCursor: self.cursor)
                    s.items = merged.items
                    nextCursor = merged.cursor
                }
                s.hasMore = nextCursor != nil
            }
            cursor = nextCursor
        case .photosThumb(let body):
            thumbsInFlight[body.id] = nil
            guard let url = thumbnails.save(id: body.id, jpegBase64: body.jpeg) else { return }
            eventBroadcast.yield(.thumbnail(id: body.id, url: url))
            thumbsSinceTrim += 1
            if thumbsSinceTrim >= Self.trimEvery {
                thumbsSinceTrim = 0
                thumbnails.trim()
            }
        case .photosFetchFailed(let body):
            if body.reason == .denied { update { $0.access = PhotoAccess.none } }
            await finish(body.transferId, .failure(body.reason == .gone ? .gone : .denied))
        case .photosChanged:
            guard state.supported else { return }
            await requestAlbums()
            await requestPage(cursor: nil, mode: .merge)
        default:
            break
        }
    }

    func connectionChanged(_ status: ConnectionStatus) async {
        switch status {
        case .connected(let peer):
            let wasSupported = state.supported
            update {
                $0.connected = true
                $0.phoneFeatures = Set(peer.features)
            }
            // Battery updates also arrive as status changes: only a fresh connection reloads.
            guard state.supported, !wasSupported else { return }
            await requestAlbums()
            await requestPage(cursor: nil, mode: state.items.isEmpty ? .replace : .merge)
        case .disconnected:
            albumRequest = nil
            pageRequest = nil
            thumbsInFlight = [:]
            update {
                $0.connected = false
                $0.loading = false
            }
            for transferId in Array(pending.keys) { await finish(transferId, .failure(.notConnected)) }
        }
    }

    /// New photos at the top: the fresh first page, then every loaded item older than its last one (Review Focus #2).
    static func merge(firstPage: [PhotoItem], firstCursor: String?, old: [PhotoItem], oldCursor: String?) -> (items: [PhotoItem], cursor: String?) {
        guard let last = firstPage.last, firstCursor != nil else { return (firstPage, firstCursor) }
        let older = old.filter { isOlder($0, than: last) }
        return older.isEmpty ? (firstPage, firstCursor) : (firstPage + older, oldCursor)
    }

    static func isOlder(_ a: PhotoItem, than b: PhotoItem) -> Bool {
        a.takenAt != b.takenAt ? a.takenAt < b.takenAt : (Int64(a.id) ?? 0) < (Int64(b.id) ?? 0)
    }

    // MARK: Helpers

    private func reload() async {
        cursor = nil
        update {
            $0.items = []
            $0.hasMore = false
        }
        await requestPage(cursor: nil, mode: .replace)
    }

    private func requestAlbums() async {
        let id = makeId()
        albumRequest = id
        try? await send(.photosAlbums(PhotosAlbumsRequest(requestId: id)))
    }

    private func requestPage(cursor: String?, mode: PageMode) async {
        guard state.supported else { return }
        let id = makeId()
        pageRequest = (id, mode)
        update { $0.loading = true }
        do {
            try await send(.photosQuery(PhotosQuery(requestId: id, albumId: state.albumId, kind: state.kind, cursor: cursor, limit: Self.pageSize)))
        } catch {
            if pageRequest?.id == id {
                pageRequest = nil
                update { $0.loading = false }
            }
        }
    }

    /// Asks for up to three originals at a time; each one's 30 s clock starts when the phone is asked.
    private func startWaiting() {
        while asking.count < Self.maxConcurrentFetches, !waiting.isEmpty {
            let transferId = waiting.removeFirst()
            guard let fetch = pending[transferId] else { continue }
            asking.insert(transferId)
            let timeout = fetchTimeout
            Task { await self.sendFetch(id: fetch.photoId, transferId: transferId) }
            Task {
                try? await Task.sleep(for: timeout)
                await self.checkTimeout(transferId)
            }
        }
    }

    private func sendFetch(id: String, transferId: String) async {
        do {
            try await send(.photosFetch(PhotosFetch(id: id, transferId: transferId)))
        } catch {
            await finish(transferId, .failure(.notConnected))
        }
    }

    /// 30 s after asking: an offer that never arrived means the phone didn't respond (Review Focus #5).
    private func checkTimeout(_ transferId: String) async {
        guard pending[transferId] != nil, !(await transfers.isActive(transferId)) else { return }
        await finish(transferId, .failure(.noResponse))
    }

    private func finish(_ transferId: String, _ result: Result<URL, PhotoFetchError>) async {
        guard let fetch = pending.removeValue(forKey: transferId) else { return }
        waiting.removeAll { $0 == transferId }
        asking.remove(transferId)
        defer { startWaiting() }
        switch result {
        case .success(let url):
            if fetch.toCache { cachedOriginals[fetch.photoId] = url }
        case .failure(let error):
            await transfers.forget(transferId)
            if error == .gone { update { $0.items.removeAll { $0.id == fetch.photoId } } }
        }
        fetch.continuation.resume(returning: result)
    }

    private func update(_ change: (inout PhotosState) -> Void) {
        var next = state
        change(&next)
        guard next != state else { return }
        state = next
        stateBroadcast.yield(state)
    }
}
```

- The `.merge` branch reads `self.cursor` inside `update`'s non-escaping closure. That is allowed on the actor. If the compiler objects, read it into a `let oldCursor = cursor` before calling `update`.
- `fetchFile` asks for at most three originals at a time. A fourth can't be offered until the phone frees an upload slot, so asking for it now would trip the 30 s timeout on a busy phone. Previews and drags go ahead of queued saves.
- `connectionChanged` loads only when the phone *becomes* supported. `ConnectionHub` also re-sends `.connected` for battery updates.

Run `swift test`. Expected: all pass. Run the Mac app build. Expected: no warnings.

- [ ] **Step 5: Handoff, commit**

CHANGELOG bullet: "Task 4: Mac core `ThumbnailCache` (digits-only ids, 200 MB LRU), `PhotosPresentation` (day groups, copy), `PhotoSelection` (click / ⌘ / ⇧) and the `PhotosClient` actor (paging with stale-response guard, merge on change, thumbnail de-dup with 20 s expiry, fetches three at a time, previews first, with a 30 s no-offer timeout)."

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac-core): photos client, thumbnail cache and presentation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Android core — `PhotoService` answers the Mac

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/photos/PhotoService.kt`
- Test: `android/core/src/test/kotlin/dev/macdroid/core/photos/PhotoServiceTest.kt`

**Interfaces:**
- **Consumes:**
  - Task 1 messages
  - Task 2's `PhotoQueries`, `OutgoingFile` and `TransferEngine.enqueueWithId`
- **Produces:**
  - `interface PhotoSource`, with:
    - `fun access(): PhotoAccess`
    - `fun albums(): List<PhotoAlbumBody>` ("all" first)
    - `fun page(albumId: String, kind: PhotoKindFilter, cursor: String?, limit: Int): PhotoPageResult`
    - `fun thumbnail(id: String): ByteArray?`
    - `fun original(id: String): OutgoingFile?`
  - `data class PhotoPageResult(val items: List<PhotoItemBody>, val nextCursor: String?)`.
  - `class PhotoService(source: PhotoSource, send: (Message) -> Boolean, enqueue: (transferId: String, file: OutgoingFile) -> Boolean)`, with `fun handle(message: Message)`.

- [ ] **Step 1: Failing test**

```kotlin
package dev.macdroid.core.photos

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.PhotoAlbumBody
import dev.macdroid.core.protocol.PhotoFetchFailure
import dev.macdroid.core.protocol.PhotoItemBody
import dev.macdroid.core.protocol.PhotoKind
import dev.macdroid.core.protocol.PhotoKindFilter
import dev.macdroid.core.protocol.PhotosAlbumListBody
import dev.macdroid.core.protocol.PhotosAlbumsBody
import dev.macdroid.core.protocol.PhotosFetchBody
import dev.macdroid.core.protocol.PhotosFetchFailedBody
import dev.macdroid.core.protocol.PhotosPageBody
import dev.macdroid.core.protocol.PhotosQueryBody
import dev.macdroid.core.protocol.PhotosThumbBody
import dev.macdroid.core.protocol.PhotosThumbsBody
import dev.macdroid.core.transfer.OutgoingFile
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoServiceTest {
    private class FakeSource(var access: PhotoAccess = PhotoAccess.Full) : PhotoSource {
        val queries = mutableListOf<List<Any?>>()
        override fun access() = access
        override fun albums() = listOf(PhotoAlbumBody("all", "All Photos", 2, "9"))
        override fun page(albumId: String, kind: PhotoKindFilter, cursor: String?, limit: Int): PhotoPageResult {
            queries += listOf(albumId, kind, cursor, limit)
            return PhotoPageResult(listOf(PhotoItemBody("9", PhotoKind.Photo, "a.jpg", "image/jpeg", 1000, 10, 4, 3)), "1:9")
        }
        override fun thumbnail(id: String): ByteArray? = if (id == "9") byteArrayOf(-1, -40) else null
        override fun original(id: String): OutgoingFile? = if (id == "9") OutgoingFile("content://media/external/images/media/9", "a.jpg", 10, "image/jpeg") else null
    }

    private val sent = mutableListOf<Message>()
    private val enqueued = mutableListOf<Pair<String, OutgoingFile>>()
    private fun service(source: PhotoSource) = PhotoService(source, { sent += it; true }, { id, file -> enqueued += id to file; true })

    @Test fun albumsAndPagesCarryTheAccessLevel() {
        val source = FakeSource()
        val s = service(source)
        s.handle(Message.PhotosAlbums(PhotosAlbumsBody("r1")))
        s.handle(Message.PhotosQuery(PhotosQueryBody("r2", "all", PhotoKindFilter.Videos, "5:6", 5_000)))
        assertEquals(Message.PhotosAlbumList(PhotosAlbumListBody("r1", PhotoAccess.Full, source.albums())), sent[0])
        assertEquals("r2", (sent[1] as Message.PhotosPage).body.requestId)
        assertEquals("1:9", (sent[1] as Message.PhotosPage).body.nextCursor)
        assertEquals(listOf("all", PhotoKindFilter.Videos, "5:6", 200), source.queries.single())
    }

    @Test fun withoutAccessNothingIsRead() {
        val source = FakeSource(PhotoAccess.None)
        val s = service(source)
        s.handle(Message.PhotosAlbums(PhotosAlbumsBody("r1")))
        s.handle(Message.PhotosQuery(PhotosQueryBody("r2", "all", PhotoKindFilter.All, null, 10)))
        s.handle(Message.PhotosThumbs(PhotosThumbsBody(listOf("9"))))
        s.handle(Message.PhotosFetch(PhotosFetchBody("9", "t1")))
        assertEquals(
            listOf(
                Message.PhotosAlbumList(PhotosAlbumListBody("r1", PhotoAccess.None, emptyList())),
                Message.PhotosPage(PhotosPageBody("r2", PhotoAccess.None, emptyList(), null)),
                Message.PhotosFetchFailed(PhotosFetchFailedBody("t1", PhotoFetchFailure.Denied)),
            ),
            sent,
        )
        assertEquals(emptyList<List<Any?>>(), source.queries)
        assertEquals(0, enqueued.size)
    }

    @Test fun thumbnailsSkipMissingAndInvalidIds() {
        val s = service(FakeSource())
        s.handle(Message.PhotosThumbs(PhotosThumbsBody(listOf("9", "8", "../x"))))
        assertEquals(listOf(Message.PhotosThumb(PhotosThumbBody("9", "/9g="))), sent)
    }

    @Test fun fetchEnqueuesUnderTheMacsIdOrSaysGone() {
        val s = service(FakeSource())
        s.handle(Message.PhotosFetch(PhotosFetchBody("9", "7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f")))
        s.handle(Message.PhotosFetch(PhotosFetchBody("8", "t2")))
        s.handle(Message.PhotosFetch(PhotosFetchBody("../9", "t3")))
        s.handle(Message.PhotosFetch(PhotosFetchBody("9", "../../etc")))
        assertEquals("7d4c0e1a-2b3f-4c5d-8e9f-0a1b2c3d4e5f", enqueued.single().first)
        assertEquals(
            listOf(
                Message.PhotosFetchFailed(PhotosFetchFailedBody("t2", PhotoFetchFailure.Gone)),
                Message.PhotosFetchFailed(PhotosFetchFailedBody("t3", PhotoFetchFailure.Gone)),
            ),
            sent,
        )
    }
}
```

The last fetch, with transfer id `../../etc`, is dropped silently: no reply can address it.

Run `./gradlew :core:test --tests 'dev.macdroid.core.photos.PhotoServiceTest'`. Expected: compilation fails.

- [ ] **Step 2: Implement**

Add to `PhotoQueries`:

```kotlin
    /** Transfer ids come from the Mac (UUIDs); they become record ids and URL path parts. */
    fun isValidTransferId(id: String): Boolean = id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '-' }
```

`PhotoService.kt`:

```kotlin
package dev.macdroid.core.photos

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.PhotoAlbumBody
import dev.macdroid.core.protocol.PhotoFetchFailure
import dev.macdroid.core.protocol.PhotoItemBody
import dev.macdroid.core.protocol.PhotoKindFilter
import dev.macdroid.core.protocol.PhotosAlbumListBody
import dev.macdroid.core.protocol.PhotosFetchFailedBody
import dev.macdroid.core.protocol.PhotosPageBody
import dev.macdroid.core.protocol.PhotosThumbBody
import dev.macdroid.core.transfer.OutgoingFile
import java.util.Base64

data class PhotoPageResult(val items: List<PhotoItemBody>, val nextCursor: String?)

/** The phone's library as MacDroid sees it (MediaStore on the phone, a fake in tests). */
interface PhotoSource {
    fun access(): PhotoAccess
    /** "all" first, then each bucket. */
    fun albums(): List<PhotoAlbumBody>
    fun page(albumId: String, kind: PhotoKindFilter, cursor: String?, limit: Int): PhotoPageResult
    /** A JPEG at most 256 px on its longest side, or null when the item is gone or not visible. */
    fun thumbnail(id: String): ByteArray?
    fun original(id: String): OutgoingFile?
}

/**
 * Answers the Mac's photos.* requests (spec §3–4). Not thread-safe: call [handle] from one worker,
 * since thumbnails and queries do disk I/O.
 */
class PhotoService(
    private val source: PhotoSource,
    private val send: (Message) -> Boolean,
    private val enqueue: (transferId: String, file: OutgoingFile) -> Boolean,
) {
    fun handle(message: Message) {
        when (message) {
            is Message.PhotosAlbums -> {
                val access = source.access()
                val albums = if (access == PhotoAccess.None) emptyList() else source.albums()
                send(Message.PhotosAlbumList(PhotosAlbumListBody(message.body.requestId, access, albums)))
            }
            is Message.PhotosQuery -> {
                val body = message.body
                val access = source.access()
                val page = if (access == PhotoAccess.None) PhotoPageResult(emptyList(), null)
                    else source.page(body.albumId, body.kind, body.cursor, PhotoQueries.pageLimit(body.limit))
                send(Message.PhotosPage(PhotosPageBody(body.requestId, access, page.items, page.nextCursor)))
            }
            is Message.PhotosThumbs -> {
                if (source.access() == PhotoAccess.None) return
                for (id in PhotoQueries.thumbIds(message.body.ids)) {
                    val jpeg = source.thumbnail(id) ?: continue
                    if (!send(Message.PhotosThumb(PhotosThumbBody(id, Base64.getEncoder().encodeToString(jpeg))))) return
                }
            }
            is Message.PhotosFetch -> {
                val body = message.body
                if (!PhotoQueries.isValidTransferId(body.transferId)) return
                fun fail(reason: PhotoFetchFailure) = send(Message.PhotosFetchFailed(PhotosFetchFailedBody(body.transferId, reason)))
                when {
                    !PhotoQueries.isValidId(body.id) -> fail(PhotoFetchFailure.Gone)
                    source.access() == PhotoAccess.None -> fail(PhotoFetchFailure.Denied)
                    else -> source.original(body.id)?.let { enqueue(body.transferId, it) } ?: fail(PhotoFetchFailure.Gone)
                }
            }
            else -> Unit
        }
    }
}
```

If `enqueue` returns false, the id is already being sent: that's a duplicate and is ignored.

Run `./gradlew :core:test`. Expected: PASS.

- [ ] **Step 3: Handoff, commit**

CHANGELOG bullet: "Task 5: Android core `PhotoService` answers albums, pages, thumbnails and fetches from a `PhotoSource`; nothing is read without access; invalid photo and transfer ids are refused."

```bash
git add android/core docs/handoff
git commit -m "feat(android-core): photo service answers the Mac's photo requests

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Android platform — `PhotoLibrary` (MediaStore) and `PhotosBridge`

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/platform/photos/PhotoLibrary.kt`, `PhotosBridge.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/dev/macdroid/app/MacDroidApp.kt`

**Interfaces:**
- **Consumes:**
  - `PhotoSource`, `PhotoService` and `PhotoQueries`
  - `TransferEngine.enqueueWithId`
  - `ConnectionManager.incoming` / `state` / `send`
  - `macSupports`
- **Produces:**
  - `PhotosBridge(context, connection, engine, scope)`, with `start()`, `refreshAccess()` and `val access: StateFlow<PhotoAccess>`.
  - `AppGraph.photosBridge`.

There are no unit tests here: this is MediaStore glue. The logic it calls is tested in Tasks 2 and 5, and it is verified by the build, the cross-platform test (Task 9) and the owner's checklist.

- [ ] **Step 1: Manifest**

Next to the other `<uses-permission>` lines:

```xml
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
```

- [ ] **Step 2: `PhotoLibrary`**

```kotlin
package dev.macdroid.app.platform.photos

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import dev.macdroid.app.platform.Permissions
import dev.macdroid.core.photos.PhotoPageResult
import dev.macdroid.core.photos.PhotoQueries
import dev.macdroid.core.photos.PhotoSource
import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.PhotoAlbumBody
import dev.macdroid.core.protocol.PhotoItemBody
import dev.macdroid.core.protocol.PhotoKind
import dev.macdroid.core.protocol.PhotoKindFilter
import dev.macdroid.core.transfer.OutgoingFile
import java.io.ByteArrayOutputStream

/** The phone's photos and videos through MediaStore (spec §4). Blocking: call from a worker thread. */
class PhotoLibrary(private val context: Context) : PhotoSource {
    private val resolver = context.contentResolver
    private val files: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    override fun access(): PhotoAccess {
        val api = Build.VERSION.SDK_INT
        return PhotoQueries.access(
            api = api,
            images = api >= 33 && Permissions.granted(context, Manifest.permission.READ_MEDIA_IMAGES),
            video = api >= 33 && Permissions.granted(context, Manifest.permission.READ_MEDIA_VIDEO),
            selectedOnly = api >= 34 && Permissions.granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            legacyStorage = api < 33 && Permissions.granted(context, Manifest.permission.READ_EXTERNAL_STORAGE),
        )
    }

    override fun albums(): List<PhotoAlbumBody> {
        data class Bucket(val name: String, var count: Int, val cover: String)
        val buckets = LinkedHashMap<String, Bucket>() // most recently added first
        var total = 0
        var cover: String? = null
        val (selection, args) = PhotoQueries.selection("all", PhotoKindFilter.All, null)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        query(projection, selection, args, limit = null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0).toString()
                total++
                if (cover == null) cover = id
                val bucket = c.getString(1) ?: continue
                buckets.getOrPut(bucket) { Bucket(c.getString(2) ?: "Other", 0, id) }.count++
            }
        }
        return listOf(PhotoAlbumBody("all", "All Photos", total, cover)) +
            buckets.map { (id, b) -> PhotoAlbumBody(id, b.name, b.count, b.cover) }
    }

    override fun page(albumId: String, kind: PhotoKindFilter, cursor: String?, limit: Int): PhotoPageResult {
        val (selection, args) = PhotoQueries.selection(albumId, kind, cursor)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DURATION,
        )
        val items = mutableListOf<PhotoItemBody>()
        var lastAdded = 0L
        var lastId = 0L
        query(projection, selection, args, limit)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                lastAdded = c.getLong(4)
                lastId = id
                items += PhotoItemBody(
                    id = id.toString(),
                    kind = if (video) PhotoKind.Video else PhotoKind.Photo,
                    name = c.getString(2) ?: "$id",
                    mime = c.getString(3) ?: if (video) "video/mp4" else "image/jpeg",
                    takenAt = lastAdded * 1000,
                    size = c.getLong(5),
                    width = c.getInt(6),
                    height = c.getInt(7),
                    durationMs = if (video) c.getLong(8) else null,
                )
            }
        }
        val next = if (items.size == limit) PhotoQueries.encodeCursor(lastAdded, lastId) else null
        return PhotoPageResult(items, next)
    }

    override fun thumbnail(id: String): ByteArray? {
        val item = lookup(id) ?: return null
        return runCatching {
            val bitmap = resolver.loadThumbnail(item.uri, Size(THUMB_PX, THUMB_PX), null)
            val scaled = fit(bitmap)
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                out.toByteArray()
            }
        }.getOrNull()
    }

    override fun original(id: String): OutgoingFile? {
        val item = lookup(id) ?: return null
        return OutgoingFile(item.uri.toString(), item.name, item.size, item.mime)
    }

    private data class Item(val uri: Uri, val name: String, val size: Long, val mime: String)

    /** Null when the id is gone or not visible under the current access. */
    private fun lookup(id: String): Item? {
        if (!PhotoQueries.isValidId(id)) return null
        val projection = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE)
        return query(projection, "${MediaStore.MediaColumns._ID} = ? AND media_type IN (1,3)", arrayOf(id), limit = 1)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val video = c.getInt(0) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Item(ContentUris.withAppendedId(base, id.toLong()), c.getString(1) ?: id, c.getLong(2),
                c.getString(3) ?: if (video) "video/mp4" else "image/jpeg")
        }
    }

    private fun query(projection: Array<String>, selection: String, args: Array<String>, limit: Int?) = runCatching {
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, PhotoQueries.SORT)
            if (limit != null) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        resolver.query(files, projection, bundle, null)
    }.getOrNull() // SecurityException without access: nothing to show

    private fun fit(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= THUMB_PX) return bitmap
        val scale = THUMB_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private companion object {
        const val THUMB_PX = 256
        const val THUMB_QUALITY = 80
    }
}
```

- [ ] **Step 3: `PhotosBridge`**

```kotlin
package dev.macdroid.app.platform.photos

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.photos.PhotoService
import dev.macdroid.core.protocol.Features
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.PhotoAccess
import dev.macdroid.core.protocol.TransferPurpose
import dev.macdroid.core.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Serves the Mac's photo browsing (spec §4): requests run one at a time on a worker, and library changes
 * reach the Mac as `photos.changed`, debounced by a second.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PhotosBridge(
    private val context: Context,
    private val connection: ConnectionManager,
    private val engine: TransferEngine,
    private val scope: CoroutineScope,
) {
    private val library = PhotoLibrary(context)
    private val service = PhotoService(library, connection::send) { id, file -> engine.enqueueWithId(id, file, TransferPurpose.Photo) }
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val requests = Channel<Message>(Channel.UNLIMITED)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _access = MutableStateFlow(library.access())
    val access: StateFlow<PhotoAccess> = _access.asStateFlow()

    fun start() {
        // Collect quickly and queue: a slow thumbnail batch must never hold up other messages.
        scope.launch {
            connection.incoming.collect { message ->
                if (message.isPhotoRequest()) requests.trySend(message)
            }
        }
        scope.launch(worker) {
            for (message in requests) runCatching { service.handle(message) }
        }
        scope.launch {
            changes.debounce(CHANGE_DEBOUNCE_MS).collect {
                if (connection.state.value.macSupports(Features.PHOTOS)) connection.send(Message.PhotosChanged)
            }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                changes.tryEmit(Unit)
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
    }

    /** Re-reads the permission (call on resume and after a prompt); a change reloads the Mac's view. */
    fun refreshAccess() {
        val now = library.access()
        if (_access.value != now) {
            _access.value = now
            changes.tryEmit(Unit)
        }
    }

    private fun Message.isPhotoRequest() =
        this is Message.PhotosAlbums || this is Message.PhotosQuery || this is Message.PhotosThumbs || this is Message.PhotosFetch

    private companion object {
        const val CHANGE_DEBOUNCE_MS = 1_000L
    }
}
```

- [ ] **Step 4: `AppGraph`**

After `mediaWatcher`:

```kotlin
    /** Photo browsing for the Mac (spec §4); runs for the whole process. */
    val photosBridge = PhotosBridge(context, connectionManager, transferEngine, appScope).also { it.start() }
```

Add the import `dev.macdroid.app.platform.photos.PhotosBridge`.

Run `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`. Expected: BUILD SUCCESSFUL with no warnings. If lint flags `READ_MEDIA_VISUAL_USER_SELECTED` or the selected-photos access behaviour, resolve it in this task (Task 7 requests all three together, as Android 14 expects) and record a ruling.

- [ ] **Step 5: Handoff, commit**

CHANGELOG bullet: "Task 6: Android `PhotoLibrary` (MediaStore paging by DATE_ADDED/_ID, albums by bucket, 256 px JPEG thumbnails via `loadThumbnail`) and `PhotosBridge` (single worker, `ContentObserver` debounced to `photos.changed`); media read permissions in the manifest."

```bash
git add android docs/handoff
git commit -m "feat(android): MediaStore photo library and photos bridge

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Android UI — the setup step and Settings → Photos

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/ui/presentation/PhotosAccessText.kt`, `android/app/src/test/java/dev/macdroid/app/PhotosAccessTextTest.kt`
- Modify:
  - `ui/MainActivity.kt` (re-check access on resume)
  - `ui/setup/SetupFlow.kt`, `SetupViewModel.kt`, `SetupScreen.kt`
  - `MacDroidApp.kt` (the `UiPrefs.photosStepDone` preference)
  - `ui/main/SettingsScreen.kt`, `SettingsViewModel.kt`
- Test: `android/app/src/test/java/dev/macdroid/app/SetupFlowTest.kt`

**Interfaces:**
- **Consumes:** `PhotosBridge.access` / `refreshAccess()` and `PhotoAccess`.
- **Produces:**
  - `SetupStep.Photos`.
  - `SetupInputs.photosStepDone`.
  - `PhotosAccessText`, with `permissions(api: Int): Array<String>` and `label(access: PhotoAccess): String`.

- [ ] **Step 1: Failing tests**

`PhotosAccessTextTest.kt`:

```kotlin
package dev.macdroid.app

import dev.macdroid.app.ui.presentation.PhotosAccessText
import dev.macdroid.core.protocol.PhotoAccess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotosAccessTextTest {
    @Test fun permissionsByVersion() {
        assertArrayEquals(
            arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"),
            PhotosAccessText.permissions(36),
        )
        assertArrayEquals(arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"), PhotosAccessText.permissions(33))
        assertArrayEquals(arrayOf("android.permission.READ_EXTERNAL_STORAGE"), PhotosAccessText.permissions(29))
    }

    @Test fun labels() {
        assertEquals("All photos", PhotosAccessText.label(PhotoAccess.Full))
        assertEquals("Selected photos", PhotosAccessText.label(PhotoAccess.Partial))
        assertEquals("Off", PhotosAccessText.label(PhotoAccess.None))
    }
}
```

In `SetupFlowTest.kt`, add:

```kotlin
    @Test fun firstRunAsksForPhotosAfterFiles() {
        val i = SetupInputs(setupDone = false, welcomeDone = true, needsLocalNetwork = false, hasLocalNetwork = true, paired = true,
            needsNotifications = false, hasNotifications = true, notificationsSkipped = true, batteryDone = false, filesStepDone = true)
        assertEquals(SetupStep.Photos, SetupFlow.current(i))
        assertEquals(SetupStep.Battery, SetupFlow.current(i.copy(photosStepDone = true)))
        assertEquals(listOf(SetupStep.Welcome, SetupStep.PairMac, SetupStep.Notifications, SetupStep.Files, SetupStep.Photos, SetupStep.Battery), SetupFlow.steps(i))
    }

    @Test fun existingUsersAreNotAskedAboutPhotos() {
        val i = SetupInputs(setupDone = true, welcomeDone = false, needsLocalNetwork = false, hasLocalNetwork = true, paired = true,
            needsNotifications = false, hasNotifications = true, notificationsSkipped = false, batteryDone = false)
        assertEquals(null, SetupFlow.current(i))
    }
```

Fix any existing `SetupFlowTest` that lists the first-run steps exactly, so it includes `SetupStep.Photos` after `Files`.

Run `./gradlew :app:testDebugUnitTest`. Expected: compilation fails (`PhotosAccessText`, `SetupStep.Photos`, `photosStepDone`).

- [ ] **Step 2: Implement the pure parts**

`PhotosAccessText.kt`:

```kotlin
package dev.macdroid.app.ui.presentation

import dev.macdroid.core.protocol.PhotoAccess

/** Photos permission requests and labels (spec §2). */
object PhotosAccessText {
    fun permissions(api: Int): Array<String> = when {
        api >= 34 -> arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        api >= 33 -> arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
        else -> arrayOf("android.permission.READ_EXTERNAL_STORAGE")
    }

    fun label(access: PhotoAccess): String = when (access) {
        PhotoAccess.Full -> "All photos"
        PhotoAccess.Partial -> "Selected photos"
        PhotoAccess.None -> "Off"
    }
}
```

`SetupFlow.kt`:
- `enum class SetupStep { Welcome, LocalNetwork, PairMac, Notifications, Files, Photos, Battery }`
- `SetupInputs` gets `/** The optional first-run step "See your photos on your Mac" was answered (spec §2). */ val photosStepDone: Boolean = false,`
- The first-run list adds `add(SetupStep.Photos)` after `add(SetupStep.Files)`.
- `isDone` adds `SetupStep.Photos -> i.photosStepDone || i.setupDone`. Existing users never see it (spec §9.8).

Run `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 3: Wire the UI**

`UiPrefs` (in `MacDroidApp.kt`):

```kotlin
    var photosStepDone: Boolean
        get() = prefs.getBoolean("photosStepDone", false)
        set(value) { prefs.edit().putBoolean("photosStepDone", value).apply() }
```

`SetupViewModel`:
- Add `val photosStepDone: Boolean` to `Local`, read from `graph.uiPrefs.photosStepDone`, and pass `photosStepDone = l.photosStepDone` in `inputs`.
- In `refresh()`, also call `graph.photosBridge.refreshAccess()`.
- Add:

```kotlin
    /** "See your photos on your Mac": answered either way (the permission result itself is read from the system). */
    fun completePhotos() {
        graph.uiPrefs.photosStepDone = true
        refresh()
    }
```

`SetupScreen`:
- Add the branch `SetupStep.Photos -> PhotosStep(onDone = viewModel::completePhotos)`.
- Add:

```kotlin
@Composable
private fun PhotosStep(onDone: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    StepLayout(
        icon = Icons.Rounded.PhotoLibrary,
        title = "See your photos on your Mac",
        body = "Browse and save your photos and videos from your Mac. You choose all photos or just some.",
        primaryLabel = "Choose access",
        onPrimary = { launcher.launch(PhotosAccessText.permissions(Build.VERSION.SDK_INT)) },
        secondaryLabel = "Not now",
        onSecondary = onDone,
    )
}
```

The imports are `androidx.compose.material.icons.rounded.PhotoLibrary`, `android.os.Build` and `dev.macdroid.app.ui.presentation.PhotosAccessText`. `PhotoLibrary` is in material-icons-extended, which the app already depends on.

`SettingsViewModel`:
- `SettingsUi` gets `val photosAccess: PhotoAccess = PhotoAccess.None` and `val photosSummary: String get() = PhotosAccessText.label(photosAccess)`.
- `read()` sets `photosAccess = graph.photosBridge.access.value`.
- `refresh()` first calls `graph.photosBridge.refreshAccess()`.

`MainActivity`: re-check photo access whenever the app comes back. The user may have changed it in the system settings, and the Mac should reload (spec §4):

```kotlin
    override fun onResume() {
        super.onResume()
        graph.photosBridge.refreshAccess()
    }
```

Import `dev.macdroid.app.graph` if it isn't already imported.

`SettingsScreen`: after the Files section items, add:

```kotlin
        item { SectionHeader("Photos") }
        item {
            val context = LocalContext.current
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                viewModel.refresh()
                // Android shows nothing once access was denied twice: send the user to the app's settings instead.
                if (it.values.none { granted -> granted }) context.openAppSettings()
            }
            ListItem(
                headlineContent = { Text("Photos on your Mac") },
                supportingContent = { Text(ui.photosSummary) },
                modifier = Modifier.clickable { launcher.launch(PhotosAccessText.permissions(Build.VERSION.SDK_INT)) },
            )
        }
```

`openAppSettings` is `internal` in `ui/setup/SetupScreen.kt`. Import it as `dev.macdroid.app.ui.setup.openAppSettings`, since it's in the same module. Add any missing imports (`rememberLauncherForActivityResult`, `ActivityResultContracts`, `LocalContext`, `Build`, `clickable`) as the file needs them.

On Android 14+ with "Selected photos", tapping the row asks again, and the system shows "Select more photos" / "Allow all". That is how the user changes their choice.

Run `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`. Expected: BUILD SUCCESSFUL with no warnings.

- [ ] **Step 4: Handoff, commit**

CHANGELOG bullet: "Task 7: Android setup step 'See your photos on your Mac' (first run only) and Settings → Photos (All photos / Selected photos / Off; tapping asks again or opens app settings)."

```bash
git add android docs/handoff
git commit -m "feat(android): photos setup step and settings row

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 8: Mac app — the Photos page

**Files:**
- Create: `mac/MacDroid/Views/Photos/PhotosView.swift`, `mac/MacDroid/Views/Photos/PhotoCell.swift`
- Modify:
  - `mac/MacDroid/AppModel.swift`
  - `mac/MacDroid/SidebarSection.swift`
  - `mac/MacDroid/Views/MainWindow.swift`
  - `mac/MacDroid/Views/Phone/PairedDeviceView.swift`
  - `mac/MacDroid/Files/FilesSettings.swift`

**Interfaces:**
- **Consumes:**
  - Task 3: `TransferManager(photoCacheDirectory:photoSaveFolder:)` and the `photoReceived` / `photoFailed` events
  - Task 4: `PhotosClient`, `PhotoTransfers.manager`, `ThumbnailCache` and `PhotosPresentation`
- **Produces:** the Photos sidebar item and page, plus the Phone page's "Photos" line.

No unit tests here: the logic lives in `MacDroidCore` (Tasks 3–4). This task is verified by the build with no warnings and the owner's checklist (Task 10).

- [ ] **Step 1: Sidebar and settings helper**

`SidebarSection.swift`:
- Add `case photos` after `files` in the enum.
- `features` becomes `[.phone, .notifications, .clipboard, .files, .photos]`.
- `title`: `case .photos: "Photos"`.
- `symbol`: `case .photos: "photo.on.rectangle"`.

`FilesSettings.swift`:

```swift
    /// Where "Save to Mac" puts photos (spec §9.2): the received-files folder, or nil when it can't be reached.
    var photoSaveFolder: URL? { folderProblem ? nil : folder }
```

- [ ] **Step 2: `AppModel` wiring**

Stored state (with the other observable properties):

```swift
    private(set) var photos: PhotosState = .empty
    /// Thumbnails already on disk, by photo id; cells read this, so arrivals redraw them.
    private(set) var photoThumbs: [String: URL] = [:]
    var photoSelection: Set<String> = []
    @ObservationIgnored private var photoAnchor: String?
    /// Bound to `.quickLookPreview`.
    var photoPreview: URL?
    private(set) var photoError: String?
    /// Photos being fetched for a preview or a save (a spinner on the cell).
    private(set) var photoBusy: Set<String> = []
    @ObservationIgnored let photoThumbnails: ThumbnailCache
    @ObservationIgnored private let photosClient: PhotosClient
    @ObservationIgnored private let thumbImages = NSCache<NSString, NSImage>()
    @ObservationIgnored private var pendingThumbs: Set<String> = []
    @ObservationIgnored private var thumbFlush: Task<Void, Never>?
```

In `init`, before `let manager = TransferManager(`:

```swift
        // Previews and drags from the last run (spec §9.2): the cache never outlives the app.
        let photoCache = URL.cachesDirectory.appending(path: "PhotoCache", directoryHint: .isDirectory)
        try? FileManager.default.removeItem(at: photoCache)
```

Add these arguments to `TransferManager(...)` after `clipboardDirectory:`:

```swift
            photoCacheDirectory: photoCache,
            photoSaveFolder: { await MainActor.run { files.photoSaveFolder } },
```

After `phoneExtras = …`:

```swift
        let photoThumbnails = ThumbnailCache(directory: URL.applicationSupportDirectory.appending(path: "PhotoThumbs", directoryHint: .isDirectory))
        self.photoThumbnails = photoThumbnails
        photosClient = PhotosClient(thumbnails: photoThumbnails, transfers: .manager(manager), send: { message in try await hub.send(message) })
```

In `start()`, beside the extras tasks:

```swift
        tasks.append(Task { [photosClient, hub] in await photosClient.attach(to: hub) })
        tasks.append(Task { [weak self, photosClient] in
            for await state in photosClient.states { self?.photosChanged(state) }
        })
        tasks.append(Task { [weak self, photosClient] in
            for await event in photosClient.events {
                if case .thumbnail(let id, let url) = event { self?.photoThumbs[id] = url }
            }
        })
        Task.detached(priority: .background) { [photoThumbnails] in photoThumbnails.trim() }
```

In `handle(_ event: TransferEvent)`, replace the Task 3 placeholder:

```swift
        case .photoReceived(let transferId, let url): await photosClient.transferFinished(transferId: transferId, url: url)
        case .photoFailed(let transferId): await photosClient.transferFailed(transferId: transferId)
```

In `forgetPhone()`, after `await pairing.forget()`:

```swift
        await photosClient.reset()
        photoThumbs = [:]
        photoSelection = []
        thumbImages.removeAllObjects()
```

Methods (an `// MARK: Photos` section):

```swift
    private func photosChanged(_ state: PhotosState) {
        photos = state
        if !photoSelection.isEmpty { photoSelection.formIntersection(state.items.map(\.id)) }
    }

    /// The decoded thumbnail, if it's on disk; cells call `needThumbnail` as they appear.
    func thumbnailImage(_ id: String) -> NSImage? {
        guard let url = photoThumbs[id] else { return nil }
        if let image = thumbImages.object(forKey: id as NSString) { return image }
        guard let image = NSImage(contentsOf: url) else { return nil }
        thumbImages.setObject(image, forKey: id as NSString)
        return image
    }

    /// Asks the phone for thumbnails of cells that appeared, together after 50 ms.
    func needThumbnail(_ id: String) {
        guard photoThumbs[id] == nil else { return }
        if let url = photoThumbnails.url(for: id) {
            photoThumbs[id] = url
            return
        }
        pendingThumbs.insert(id)
        guard thumbFlush == nil else { return }
        thumbFlush = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(50))
            guard let self else { return }
            let ids = Array(pendingThumbs)
            pendingThumbs = []
            thumbFlush = nil
            await photosClient.requestThumbnails(ids)
        }
    }

    func selectPhotoAlbum(_ id: String) {
        photoSelection = []
        Task { await photosClient.select(album: id) }
    }

    func setPhotoKind(_ kind: PhotoKindFilter) {
        photoSelection = []
        Task { await photosClient.setKind(kind) }
    }

    func loadMorePhotos() {
        Task { await photosClient.loadMore() }
    }

    /// Click, ⌘-click and ⇧-click (spec §5.2), read from the current event's modifier keys.
    func clickPhoto(_ id: String) {
        let flags = NSEvent.modifierFlags
        let result = PhotoSelection.click(id, command: flags.contains(.command), shift: flags.contains(.shift),
                                          selection: photoSelection, anchor: photoAnchor, order: photos.items.map(\.id))
        photoSelection = result.selection
        photoAnchor = result.anchor
    }

    /// ⌘A: every loaded item.
    func selectAllPhotos() {
        photoSelection = Set(photos.items.map(\.id))
    }

    func clearPhotoSelection() {
        photoSelection = []
        photoAnchor = nil
    }

    /// Space: preview the one selected item.
    func previewSelectedPhoto() -> Bool {
        guard photoSelection.count == 1, let item = photos.items.first(where: { photoSelection.contains($0.id) }) else { return false }
        previewPhoto(item)
        return true
    }

    /// Quick Look (spec §9.3): fetch into the cache, then preview.
    func previewPhoto(_ item: PhotoItem) {
        photoError = nil
        photoBusy.insert(item.id)
        Task {
            let result = await photosClient.fetchFile(item.id, toCache: true)
            photoBusy.remove(item.id)
            switch result {
            case .success(let url): photoPreview = url
            case .failure(let error): photoError = PhotosPresentation.message(error)
            }
        }
    }

    /// "Save to Mac": every selected item goes to the received-files folder (and Files history).
    func saveSelectedPhotos() {
        let items = photos.items.filter { photoSelection.contains($0.id) }
        photoSelection = []
        photoError = nil
        for item in items {
            photoBusy.insert(item.id)
            Task {
                let result = await photosClient.fetchFile(item.id, toCache: false)
                photoBusy.remove(item.id)
                if case .failure(let error) = result { photoError = PhotosPresentation.message(error) }
            }
        }
    }

    /// Drag-out (spec §9.1): the file is fetched into the cache when the drop happens, then the system copies it.
    func dragProvider(for item: PhotoItem) -> NSItemProvider {
        let provider = NSItemProvider()
        provider.suggestedName = item.name
        let type = UTType(mimeType: item.mime) ?? (item.kind == .video ? .movie : .image)
        let client = photosClient
        let id = item.id
        provider.registerFileRepresentation(forTypeIdentifier: type.identifier, fileOptions: [], visibility: .all) { completion in
            Task {
                switch await client.fetchFile(id, toCache: true) {
                case .success(let url):
                    completion(url, false, nil)
                case .failure(let error):
                    completion(nil, false, NSError(domain: "MacDroid", code: 1, userInfo: [NSLocalizedDescriptionKey: PhotosPresentation.message(error)]))
                }
            }
            return nil
        }
        return provider
    }
```

Add `import UniformTypeIdentifiers` to AppModel.swift if it isn't there already.

- [ ] **Step 3: Views**

`PhotoCell.swift`:

```swift
import MacDroidCore
import SwiftUI

/// A square thumbnail, or a grey placeholder until it arrives.
struct PhotoThumbnail: View {
    let model: AppModel
    let id: String?

    var body: some View {
        Rectangle()
            .fill(.quaternary)
            .overlay {
                if let id, let image = model.thumbnailImage(id) {
                    Image(nsImage: image).resizable().scaledToFill()
                }
            }
            .clipped()
            .onAppear { if let id { model.needThumbnail(id) } }
    }
}

/// One grid item: click / ⌘-click / ⇧-click select, double-click previews, drag copies the original out (spec §5, §9).
struct PhotoCell: View {
    let model: AppModel
    let item: PhotoItem

    var body: some View {
        let selected = model.photoSelection.contains(item.id)
        PhotoThumbnail(model: model, id: item.id)
            .aspectRatio(1, contentMode: .fit)
            .overlay(alignment: .bottomLeading) {
                if item.kind == .video {
                    Label(item.durationMs.map(PhotosPresentation.duration(ms:)) ?? "", systemImage: "play.fill")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.white)
                        .shadow(color: .black.opacity(0.6), radius: 2)
                        .padding(4)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, Color.accentColor)
                        .padding(4)
                }
            }
            .overlay {
                if model.photoBusy.contains(item.id) { ProgressView().controlSize(.small) }
            }
            .overlay {
                RoundedRectangle(cornerRadius: 4).strokeBorder(Color.accentColor, lineWidth: selected ? 3 : 0)
            }
            .clipShape(RoundedRectangle(cornerRadius: 4))
            .contentShape(Rectangle())
            .onTapGesture(count: 2) { model.previewPhoto(item) }
            .onTapGesture { model.clickPhoto(item.id) }
            .onDrag { model.dragProvider(for: item) }
            .contextMenu {
                Button("Preview") { model.previewPhoto(item) }
                Button("Save to Mac") {
                    model.photoSelection = [item.id]
                    model.saveSelectedPhotos()
                }
            }
            .help(item.name)
            .accessibilityLabel(item.name)
            .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
```

`PhotosView.swift`:

```swift
import MacDroidCore
import QuickLook
import SwiftUI

/// The phone's photos (spec §5, layout B): album column, day-grouped grid, floating selection bar.
struct PhotosView: View {
    @Bindable var model: AppModel

    var body: some View {
        let state = model.photos
        Group {
            if !state.connected && state.items.isEmpty {
                ContentUnavailableView("Phone not connected", systemImage: "iphone.slash", description: Text("Your phone's photos appear here when it connects."))
            } else if state.connected && !state.supported {
                ContentUnavailableView("Photos", systemImage: "photo.on.rectangle", description: Text("Update MacDroid on your phone to use this."))
            } else if state.access == PhotoAccess.none {
                ContentUnavailableView("Photos are off", systemImage: "photo", description: Text(PhotosPresentation.banner(access: PhotoAccess.none, visibleCount: 0) ?? ""))
            } else {
                // Disconnected with photos loaded: they stay visible, dimmed, and nothing can be done with them (spec §6).
                HStack(spacing: 0) {
                    AlbumList(model: model).frame(width: 180)
                    Divider()
                    PhotoGrid(model: model)
                }
                .disabled(!state.connected)
                .opacity(state.connected ? 1 : 0.55)
            }
        }
        .toolbar {
            ToolbarItemGroup {
                Picker("Show", selection: Binding(get: { model.photos.kind }, set: { model.setPhotoKind($0) })) {
                    Text("All").tag(PhotoKindFilter.all)
                    Text("Photos").tag(PhotoKindFilter.photos)
                    Text("Videos").tag(PhotoKindFilter.videos)
                }
                .pickerStyle(.segmented)
                .help("Show photos, videos or both")
                .disabled(!state.supported)
                Button { model.saveSelectedPhotos() } label: { Label("Save to Mac", systemImage: "square.and.arrow.down") }
                    .help("Save the selected items to your received-files folder")
                    .disabled(model.photoSelection.isEmpty || !state.supported)
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if !state.connected && !state.items.isEmpty { BannerView(kind: .warning, message: "Phone not connected") }
        }
        .quickLookPreview($model.photoPreview)
    }
}

private struct AlbumList: View {
    let model: AppModel

    var body: some View {
        List(selection: Binding<String?>(get: { model.photos.albumId }, set: { if let id = $0 { model.selectPhotoAlbum(id) } })) {
            ForEach(model.photos.albums) { album in
                HStack(spacing: 8) {
                    PhotoThumbnail(model: model, id: album.coverId)
                        .frame(width: 28, height: 28)
                        .clipShape(RoundedRectangle(cornerRadius: 5))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(album.name).lineLimit(1)
                        Text(album.count.formatted()).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .tag(album.id)
            }
        }
        .listStyle(.sidebar)
    }
}

private struct PhotoGrid: View {
    let model: AppModel

    var body: some View {
        let state = model.photos
        let days = PhotosPresentation.days(state.items, now: Date())
        // The next page loads once a cell in about the last two rows appears (spec §5.2).
        let nearEnd = Set(state.items.suffix(20).map(\.id))
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if let banner = PhotosPresentation.banner(access: state.access, visibleCount: state.albums.first?.count ?? state.items.count) {
                        BannerView(kind: .warning, message: banner)
                    }
                    if let error = model.photoError { BannerView(kind: .error, message: error) }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 3)], alignment: .leading, spacing: 3, pinnedViews: [.sectionHeaders]) {
                        ForEach(days) { day in
                            Section {
                                ForEach(day.items) { item in
                                    PhotoCell(model: model, item: item)
                                        .onAppear { if state.hasMore && nearEnd.contains(item.id) { model.loadMorePhotos() } }
                                }
                            } header: {
                                Text(day.title)
                                    .font(.headline)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.vertical, 6)
                                    .background(.background)
                            }
                        }
                        if state.hasMore {
                            ProgressView().controlSize(.small).frame(maxWidth: .infinity, minHeight: 96).onAppear { model.loadMorePhotos() }
                        }
                    }
                }
                .padding(12)
                .padding(.bottom, model.photoSelection.isEmpty ? 0 : 56)
            }
            .overlay {
                if state.items.isEmpty {
                    if state.loading {
                        ProgressView()
                    } else {
                        ContentUnavailableView("No photos", systemImage: "photo", description: Text("Photos and videos on your phone appear here."))
                    }
                }
            }
            if !model.photoSelection.isEmpty {
                SelectionBar(model: model).padding(12)
            }
        }
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(.space) { model.previewSelectedPhoto() ? .handled : .ignored }
        .onKeyPress(.escape) {
            model.clearPhotoSelection()
            return .handled
        }
        .onKeyPress(characters: ["a"], phases: .down) { press in
            guard press.modifiers == .command else { return .ignored }
            model.selectAllPhotos()
            return .handled
        }
    }
}

private struct SelectionBar: View {
    let model: AppModel

    var body: some View {
        let selected = model.photos.items.filter { model.photoSelection.contains($0.id) }
        HStack(spacing: 10) {
            Text(PhotosPresentation.selection(count: selected.count, bytes: selected.reduce(0) { $0 + $1.size }))
                .font(.callout.weight(.semibold))
            Spacer()
            Button("Preview") { if let first = selected.first { model.previewPhoto(first) } }
                .disabled(selected.count != 1)
            Button("Save to Mac") { model.saveSelectedPhotos() }
                .buttonStyle(.borderedProminent)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
        .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
    }
}
```

`MainWindow.detail(for:)`: add `case .photos: PhotosView(model: model)`.

Keyboard notes:
- The grid takes focus when clicked (`.focusable()`). Space previews the one selected item, Escape clears the selection, and ⌘A selects every loaded item.
- If the app's standard Edit menu catches ⌘A before the grid, the `.onKeyPress` handler never fires. In that case, put the same `model.selectAllPhotos()` behind `.onCommand(#selector(NSResponder.selectAll(_:)))` on the grid and record a ruling.

`PairedDeviceView`, in "Details" after the Notifications line:

```swift
                if model.photos.supported, let line = PhotosPresentation.phoneLine(model.photos.access) {
                    LabeledContent("Photos", value: line)
                }
```

Any other exhaustive `switch` over `SidebarSection` that the compiler flags gets a `.photos` case that matches what `.files` does there.

- [ ] **Step 4: Build**

Run `cd mac/MacDroidCore && swift test`, then the Mac app build (XcodeGen picks up `Views/Photos/` from the folder). Expected: tests pass and the build succeeds with no warnings. Fix any Swift 6 isolation warnings in the new code by making the closure's isolation explicit; don't silence them.

- [ ] **Step 5: Handoff, commit**

CHANGELOG bullet: "Task 8: Mac Photos page (album column, day-grouped 96 pt grid, click / ⌘ / ⇧ / ⌘A selection, selection bar, Space or double-click Quick Look, single-item drag-out, Save to Mac, dimmed while disconnected), the Phone page 'Photos' line, and the photo cache emptied at launch."

```bash
git add mac docs/handoff
git commit -m "feat(mac): photos page with preview, save and drag-out

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Cross-platform test — browse, thumbnail, save, gone

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidDevServer/main.swift`, `android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt`, `scripts/cross-platform-test.sh`

**Interfaces consumed:** `PhotosClient`, `PhotoTransfers.manager`, `PhotoService`, `PhotoSource` and `TransferEngine.enqueueWithId`.

- [ ] **Step 1: Failing checks in the script**

Before the `cmp` lines in `scripts/cross-platform-test.sh`:

```bash
grep -q 'PHOTOS page count=2 access=full' "$LOG" || { echo "the Mac never got a photos page"; cat "$LOG"; exit 1; }
grep -q 'PHOTOS thumb id=2' "$LOG" || { echo "the Mac never got a thumbnail"; cat "$LOG"; exit 1; }
grep -q 'PHOTOS saved IMG_2.jpg size=4000' "$LOG" || { echo "the Mac never saved the photo"; cat "$LOG"; exit 1; }
grep -q 'PHOTOS gone id=424242' "$LOG" || { echo "the Mac never heard a photo was gone"; cat "$LOG"; exit 1; }
```

Run `./scripts/cross-platform-test.sh`. Expected: it fails with "the Mac never got a photos page".

- [ ] **Step 2: Dev server**

Add these to the `TransferManager(...)` call, after `clipboardDirectory:`:

```swift
    photoCacheDirectory: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "macdroid-devserver-photos-\(UUID().uuidString)"),
    photoSaveFolder: { receiveDir },
```

After the extras block:

```swift
let photos = PhotosClient(
    thumbnails: ThumbnailCache(directory: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "macdroid-devserver-thumbs-\(UUID().uuidString)")),
    transfers: .manager(transfers),
    send: { try await hub.send($0) }
)
await photos.attach(to: hub)

// On the first page: ask for its thumbnails, save the newest item, and ask for one that doesn't exist.
let photosPrinter = Task {
    var started = false
    for await state in photos.states where !state.items.isEmpty && !started {
        started = true
        print("PHOTOS page count=\(state.items.count) access=\(state.access?.rawValue ?? "-")")
        await photos.requestThumbnails(state.items.map(\.id))
        let newest = state.items[0].id
        Task {
            switch await photos.fetchFile(newest, toCache: false) {
            case .success(let url):
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? -1
                print("PHOTOS saved \(url.lastPathComponent) size=\(size)")
            case .failure(let error):
                print("PHOTOS fetch failed \(error)")
            }
            if case .failure(.gone) = await photos.fetchFile("424242", toCache: true) { print("PHOTOS gone id=424242") }
        }
    }
}

let thumbPrinter = Task {
    for await event in photos.events {
        if case .thumbnail(let id, _) = event { print("PHOTOS thumb id=\(id)") }
    }
}
```

In `transferPrinter`, replace the Task 3 placeholder:

```swift
        case .photoReceived(let transferId, let url): await photos.transferFinished(transferId: transferId, url: url)
        case .photoFailed(let transferId): await photos.transferFailed(transferId: transferId)
```

At the end of the file, add `photosPrinter.cancel()` and `thumbPrinter.cancel()`.

- [ ] **Step 3: Kotlin side**

In `CrossPlatformTest`, add a fake source:

```kotlin
    /** Two items; "2" has a 4,000-byte original and a tiny JPEG thumbnail. */
    private class FakePhotos(private val original: File) : PhotoSource {
        override fun access() = PhotoAccess.Full
        override fun albums() = listOf(PhotoAlbumBody("all", "All Photos", 2, "2"))
        override fun page(albumId: String, kind: PhotoKindFilter, cursor: String?, limit: Int) = PhotoPageResult(
            listOf(
                PhotoItemBody("2", PhotoKind.Photo, "IMG_2.jpg", "image/jpeg", 1_791_892_700_000, 4_000, 4, 3),
                PhotoItemBody("1", PhotoKind.Photo, "IMG_1.jpg", "image/jpeg", 1_791_892_600_000, 10, 4, 3),
            ),
            null,
        )
        override fun thumbnail(id: String) = if (id == "2") byteArrayOf(-1, -40, -1, -32, 0, 16) else null
        override fun original(id: String) = if (id == "2") OutgoingFile(original.path, "IMG_2.jpg", original.length(), "image/jpeg") else null
    }
```

In the main test, right after `engine.start(...)` and before `manager.start()` (incoming has no replay), start serving photos one message at a time:

```kotlin
        val photoFile = File.createTempFile("photo", ".jpg").apply { writeBytes(ByteArray(4_000) { (it % 251).toByte() }); deleteOnExit() }
        val photoService = PhotoService(FakePhotos(photoFile), manager::send) { id, file -> engine.enqueueWithId(id, file, TransferPurpose.Photo) }
        val photoRequests = Channel<Message>(Channel.UNLIMITED)
        val photoCollector = scope.launch { manager.incoming.collect { photoRequests.trySend(it) } }
        val photoWorker = scope.launch { for (m in photoRequests) photoService.handle(m) }
```

After `exchangeSmallWins(manager, received)`, call `exchangePhotos(received)`, and cancel `photoCollector` and `photoWorker` in the `finally` block.

```kotlin
    /** The Mac browses, takes a thumbnail, saves photo 2, and asks for one that's gone. */
    private suspend fun exchangePhotos(received: List<Message>) {
        withTimeout(20_000) {
            while (received.none { it is Message.PhotosFetch && it.body.id == "424242" }) delay(50)
        }
        val save = received.filterIsInstance<Message.PhotosFetch>().first { it.body.id == "2" }
        withTimeout(20_000) {
            while (received.none { it is Message.TransferResult && it.body.transferId == save.body.transferId && it.body.ok }) delay(50)
        }
    }
```

Add the imports: `dev.macdroid.core.photos.*`, the photo protocol types, `TransferPurpose` and `kotlinx.coroutines.channels.Channel`.

- [ ] **Step 4: Run**

Run `./scripts/cross-platform-test.sh`. Expected: "cross-platform test passed". Then run every gate:
- `swift test`
- the Mac app build
- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`

Expected: all pass with no warnings.

- [ ] **Step 5: Handoff, commit**

CHANGELOG bullet: "Task 9: cross-platform test covers photos: page, thumbnail, save to the receive folder over the transfer engine, and `gone`."

```bash
git add mac android scripts docs/handoff
git commit -m "test: cross-platform photos round trip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Final gates, handoff, owner checklist

**Files:**
- Modify: `docs/handoff/STATUS.md`, `ARCHITECTURE.md`, `DEV-GUIDE.md`, `ROADMAP.md`, `DECISIONS.md`, `README.md`, `CHANGELOG.md`
- Create: `docs/superpowers/plans/2026-10-13-macdroid-photos-followups.md`

- [ ] **Step 1: Every gate, fresh**

```bash
cd mac/MacDroidCore && swift test 2>&1 | tail -3
cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build 2>&1 | grep -E "warning:|error:|BUILD" | sort -u
cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --warning-mode all 2>&1 | grep -E "^w:|warning|BUILD" | sort -u
./scripts/cross-platform-test.sh
```

Expected:
- `swift test`: all tests pass.
- Mac build: only `BUILD SUCCEEDED`.
- Gradle: only `BUILD SUCCESSFUL`.
- Cross-platform: "cross-platform test passed".

- [ ] **Step 2: Handoff docs**

- **ARCHITECTURE.md:**
  - the Photos components on both sides
  - the 9 `photos.*` messages and `purpose: photo`
  - the fetch flow: the Mac picks the transfer id, `expectPhoto`, the phone offers, then cache or save folder
  - the DATE_ADDED/_ID cursor
  - `hidden`/`isHidden`
- **DECISIONS.md:**
  - the ordering ruling (DATE_ADDED, not DATE_TAKEN), with the reason and the rejected `COALESCE`
  - Mac-chosen transfer ids, with unrequested offers rejected
  - three fetches at a time, previews first (the phone's `MAX_ACTIVE_OUTGOING`)
  - single-item drag-out via `registerFileRepresentation`
  - the cache emptied at launch
- **DEV-GUIDE.md:**
  - the photos permissions per Android version
  - testing "Selected photos" (Settings → Apps → MacDroid → Photos and videos)
  - where the Mac's thumbnails and cache live
- **STATUS.md:** photos built on `feat/photos`, pending the owner's check.
- **ROADMAP.md / README.md:** Round 6 (photos) built, pending the owner's check.
- **CHANGELOG.md:** close the Plan 6 entry with how it was verified (gate results and test counts).

- [ ] **Step 3: Owner checklist** (write it into `…-photos-followups.md` and into the final message)

1. Install both builds. On the phone, open MacDroid. Existing users see no new setup step. Go to Settings → Photos → choose "Allow all". The row reads "All photos".
2. On the Mac, open Photos in the sidebar:
   - The albums show with covers and counts, "All Photos" first.
   - The grid shows day headers (Today / Yesterday / dates).
   - Thumbnails fill in as you scroll, and videos show their length.
3. Scroll to the bottom of a large library: more pages load, with no duplicates.
4. Switch the album and the All / Photos / Videos filter: the grid reloads.
5. Double-click a photo: Quick Look opens. Double-click a video: it plays in Quick Look.
6. Click a photo, ⌘-click two more, and ⇧-click to extend a range. ⌘A selects everything loaded, and Escape clears it. Select one photo and press Space: Quick Look opens.
   Select 3 items. The bar shows "3 selected · <size>". Click "Save to Mac": the files land in the received-files folder and appear in Files.
7. Drag one photo to the Desktop: the file is copied there.
8. Take a new photo on the phone. Within a few seconds it appears at the top on the Mac, and you keep your scroll position.
9. Delete a photo on the phone that is still shown on the Mac, then preview it on the Mac: "This photo is no longer on your phone" appears, and it leaves the grid.
10. Phone Settings → Apps → MacDroid → Photos → "Allow limited access", and pick 2 photos. The Mac shows the "MacDroid can see 2 selected photos…" banner and only those 2. The Phone page says "Photos: Selected".
11. Set it to "Don't allow": the Mac shows "Photos are off on your phone…".
12. Turn Wi-Fi off on the phone: the Photos page stays, dimmed, with "Phone not connected" across the top, and nothing can be clicked.

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "docs(handoff): photos built on feat/photos, pending the owner's check

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Don't merge. The owner checks first.
