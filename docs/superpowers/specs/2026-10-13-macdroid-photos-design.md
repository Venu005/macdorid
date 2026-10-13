# MacDroid — Photos (Plan 6): Browse and Transfer the Phone's Photos

**Date:** 2026-10-13
**Status:** approved in brainstorming on 2026-10-13; awaiting the owner's review of this written spec.

**Builds on:**
- the connection core
- file transfer (Plan 2: resume, checksums, `purpose`)
- the Mac window and sidebar
- the Android settings and setup wizard

**Delivery:** one spec and one plan, on branch `feat/photos`.

## 1. Goal and scope

Browse the phone's photos and videos from the Mac, and get them onto the Mac:
- a **Photos** page with an album column and a day-grouped grid
- Quick Look preview
- **Save to Mac**
- dragging into Finder or other apps

**Success:** the owner finds and pulls photos from the Mac without touching the phone. It stays Play-publishable and needs no debugging mode.

**Out of scope**
- Deleting, editing or uploading photos to the phone.
- Automatic photo import or sync.
- Showing the Mac's photos on the phone.
- Searching by content.

## 2. Access (spec decision C)

Android grants either all photos or only selected ones, and the Mac shows whatever was granted.

**Permissions by Android version**
- **Android 14+ (API 34):** `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` and `READ_MEDIA_VISUAL_USER_SELECTED`. The user picks "Allow all" or "Allow limited access".
- **Android 13 (API 33):** `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO`, all or nothing.
- **Android 10–12 (API 29–32):** `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"`.

**Access level** reported to the Mac:
- `full`: all granted.
- `partial`: only `READ_MEDIA_VISUAL_USER_SELECTED` granted.
- `none`.

**Phone UI**
- **Optional setup step** "See your photos on your Mac", with "Choose access" (requests the permissions) and "Not now". It sits after Files, and setup grows to 7 steps on a first run.
- **Settings → Photos:**
  - Shows "All photos", "Selected photos" or "Off".
  - "Change access" requests the permissions again. In partial mode, Android's selector lets the user add photos.

**Play note:** broad photo access needs a Play declaration (core functionality). This round records it as a pre-publishing item.

## 3. Protocol

Feature: `photos`, added to both apps' feature lists.

**Messages**
- **`photos.albums`, Mac → phone:** a request with `requestId`.
- **`photos.albumList`, phone → Mac:** `requestId`, `access`, and `albums` [`{id, name, count, coverId}`].
  - `id` is the MediaStore bucket id as a string.
  - The list always starts with `{id: "all", name: "All Photos"}`.
- **`photos.query`, Mac → phone:**
  - `requestId`
  - `albumId` (`all` or a bucket id)
  - `kind` (`all` \| `photos` \| `videos`)
  - `cursor` (omitted for the first page)
  - `limit` (≤ 200)
- **`photos.page`, phone → Mac:**
  - `requestId`, `access`, `items`, and `nextCursor` (omitted at the end)
  - Each item: `id`, `kind` (`photo` \| `video`), `name`, `mime`, `takenAt` (epoch ms), `size`, `width`, `height`, `durationMs` (videos only)
- **`photos.thumbs`, Mac → phone:** `ids` (≤ 60).
- **`photos.thumb`, phone → Mac:** `id`, `jpeg` (base64, longest side 256 px, quality 80). Ids that are gone are skipped silently.
- **`photos.fetch`, Mac → phone:** `id` and `transferId` (a lowercase UUID the Mac chooses).
- **`photos.fetchFailed`, phone → Mac:** `transferId` and `reason` (`gone` \| `denied`).
- **`photos.changed`, phone → Mac:** an empty body.

**Rules**
- **Cursor:** an opaque string encoding (`takenAt`, `id`) of the last item. The next page is "strictly older than the cursor", ordered by `takenAt DESC, id DESC`. New photos therefore never shift pages, and nothing is duplicated.
- **`takenAt`:** `DATE_TAKEN`, falling back to `DATE_MODIFIED × 1000`.
- **Fetching:** the phone answers `photos.fetch` by offering the item through the existing transfer engine, with `transferId` as given and `purpose: "photo"` (a new `TransferPurpose`). This is phone → Mac: the phone uploads, as with any file it sends. The source is the item's MediaStore content URI, read directly.
- **Frame size:** all `photos.*` frames stay within the 1 MiB WebSocket limit. A 256 px JPEG is about 15–40 KB.
- **No-access answers:** with no access, the phone answers `photos.albums` and `photos.query` with `access: none` and empty lists.

Every message gets a fixture, and `PROTOCOL.md` is updated.

## 4. Phone

- **`PhotoLibrary` (app platform):**
  - MediaStore queries over `MediaStore.Files` (images and videos), with the album and kind filters and cursor paging.
  - Albums are grouped by `BUCKET_ID`, with counts and the newest item as cover.
  - Thumbnails via `ContentResolver.loadThumbnail(uri, Size(256, 256))`, compressed to JPEG off the main thread.
- **Pure core (`android/core`):**
  - `PhotoCursor`: encode and decode.
  - `PhotoRequests`: maps messages to queries, caps `limit` (≤ 200) and `ids` (≤ 60), and works out the access level.
- **`PhotosBridge`:**
  - Handles incoming `photos.*` on a single worker.
  - Sends replies, and runs fetches through `TransferEngine.enqueueWithId(transferId, file, purpose = Photo)`.
  - Answers `photos.fetchFailed{gone}` when the item no longer exists, and `photos.fetchFailed{denied}` when access was removed.
- **Change watching:** a `ContentObserver` on `MediaStore` images and video, debounced to 1 s, sends `photos.changed`. It also sends one when the access level changes, checked on app resume.
- **Files UI:** photo transfers (`purpose = photo`) are hidden from the phone's Files UI and notifications, just like clipboard transfers.

## 5. Mac

### 5.1 Core
- **`PhotosClient` actor:**
  - Sends requests and matches replies by `requestId`.
  - Holds albums, the current album and kind, the loaded items and the next cursor.
  - Exposes `state` (albums, sections by day, access, loading, error) and `events`.
- **`ThumbnailCache`:** on disk in `Application Support/PhotoThumbs/<id>.jpg`. The size is capped at 200 MB, evicting the oldest-accessed first. Ids are validated: only digits, which are MediaStore ids.
- **`PhotoFetches`:** remembers `transferId → destination`:
  - `save`: the receive folder from Settings, without asking even if "Ask where to save" is on, since saving is an explicit action
  - `drag(url)`: the drop location's temporary file
  - `preview`: `Caches/PhotoPreview/`
- **Transfer integration:**
  - `TransferManager` accepts `purpose: photo` offers only for a `transferId` that `PhotoFetches` knows. Unknown ones are rejected with `invalid`, so a phone can't push files unasked.
  - Saves appear in Files history as received files. Drag and preview transfers are hidden and dropped from history, like clipboard transfers.
- **`PhotosPresentation`:** day headers ("Today", "Yesterday", "12 Oct", and "12 Oct 2025" for older years), video durations, selection summaries ("3 selected · 14.2 MB") and the access texts.

### 5.2 UI (layout B)
- **Sidebar:** a new **Photos** item between Files and the settings.
- **Album column** (about 170 pt): album cover, name and count, with "All Photos" first.
- **Toolbar:** the All/Photos/Videos segmented control, and a "Save to Mac" icon button that's enabled when something is selected.
- **Grid:**
  - `LazyVGrid`, adaptive (min 96 pt), with day section headers.
  - Thumbnails are requested in batches of up to 60 for the cells that appear, and the next page loads within 2 rows of the end.
- **Selection:**
  - click, ⌘-click and ⇧-click; ⌘A selects all loaded items
  - a floating bar shows "N selected · size" with **Preview** and **Save to Mac**
- **Preview:** Space or double-click fetches into the preview cache, showing the progress, and opens Quick Look (`QLPreviewPanel`).
- **Drag out:** dragging into Finder or other apps uses a file promise (`NSFilePromiseProvider`) per item. The file is written at the drop location as soon as it arrives, so dropping doesn't wait.
- **Phone page:** "Photos: All / Selected / Off on phone".

## 6. Errors

| Situation | Behaviour |
|---|---|
| Not connected | The grid shows "Phone not connected"; cached thumbnails stay visible, dimmed; actions are disabled |
| Access `none` | "Photos are off on your phone. Open MacDroid on your phone → Settings → Photos." |
| Access `partial` | Banner: "MacDroid can see N selected photos. To see all, open MacDroid on your phone → Settings → Photos." |
| Item gone at fetch | "This photo is no longer on your phone"; it's removed from the grid |
| Transfer failure | As for file transfer (resume; the error shows in Files for saves, inline for preview and drag) |
| Phone lacks `photos` | "Update MacDroid on your phone to use this." |
| Unrequested `photo` offer | Rejected with `invalid` |

## 7. Testing

- **Fixtures and codecs** for every message on both sides, plus `transfer.offer` with `purpose: photo`.
- **Android core:**
  - `PhotoCursor` round trips and ordering
  - `PhotoRequests` caps
  - the access level for every permission combination
- **Mac core:**
  - `PhotosClient`: request matching, paging that appends, album and kind switches resetting, `photos.changed` refreshing
  - `ThumbnailCache`: the cap, oldest-accessed eviction, id validation
  - `PhotoFetches` and transfer integration: an unknown `photo` offer is rejected; save, drag and preview each go to their destination and history
  - `PhotosPresentation`: day headers and summaries
- **Cross-platform:** the real Mac server and Kotlin client exchange `photos.albums` → `albumList`, a `photos.query` → `page`, a `photos.thumbs` → `thumb`, and a `photos.fetch` → a transfer with `purpose: photo` saved on the Mac. On the JVM side a fake library stands in for the photos.
- **Owner checklist:**
  - full and limited access
  - albums
  - All, Photos and Videos
  - scrolling a big library
  - Quick Look on a photo and a video
  - Save to Mac
  - dragging several items to the Desktop
  - a new photo appearing within seconds
  - access turned off

## 8. Decisions

| Decision | Why | Rejected |
|---|---|---|
| Support full and limited access; show what's granted | Works for personal use, and gives the cleanest Play story | Full only; selected only |
| Albums plus a combined timeline, in an album column (layout B) | Mirrors Photos.app; quick album jumps | A toolbar menu; albums only |
| Thumbnails over the WebSocket; originals over the transfer engine with `purpose: photo` | The phone can't serve HTTP; originals get resume and checksums | A new HTTP route on the phone |
| The Mac chooses the `transferId`; unknown `photo` offers are rejected | The Mac knows where each file goes; the phone can't push unasked | A separate request-id field |
| Save ignores "Ask where to save" | Save is an explicit action from the Mac | Prompting for every saved photo |
| Cursor = (`takenAt`, `id`), strictly older | New photos don't shift pages | Offset paging |
