# MacDroid — File Transfer (Plan 2)

**Date:** 2026-10-13
**Status:** Approved in brainstorming; review points 1–8 resolved on 2026-10-13 (see §8); ready for an implementation plan.
**Builds on:**
- Round-1 spec §5.3 (`2026-10-12-macdroid-round1-design.md`), whose transfer mechanics this spec refines.
- The UI redesign spec (`2026-10-13-macdroid-ui-ux-redesign-design.md`).

**Order change:** file transfer moves ahead of notifications and clipboard.
- It depends only on the connection core.
- Clipboard image sync will reuse its transfer channel.

## 1. Goal and scope

Send files both ways between the paired Android phone and the Mac, over the existing mutual-TLS connection.

**Use cases (owner):** all equally — photos and videos phone→Mac, documents both ways, and multi-GB files. So resume and speed matter.

**In scope**
- **Sending from the Mac:** drag and drop onto the window's Files page or the popover; a "Send Files…" picker.
- **Sending from Android:** the system share sheet (one or many files), the Files tab and the Home Send button. Both open the system Photo Picker (photos and videos) or the file picker (anything).
- **Receiving:** automatic save to a default folder, with a notification offering Open / Show in Finder. Optional "Ask where to save each time" on both sides, and a changeable default folder on both sides.
- **Transfer behaviour:** resumable, checksum-verified, up to 3 at once with a queue.
- **History:** progress, speed, time left, cancel, and history (last 100) on both sides.

**Out of scope (later)**
- The Finder Share extension ("Share → MacDroid" in Finder), which will be its own small round.
- Sending whole folders.
- An offline queue: sending is disabled while the phone isn't connected.
- Photo-gallery browsing.

## 2. Protocol

### 2.1 Control messages (WebSocket, existing envelope)
| Type | Direction | Body |
|---|---|---|
| `transfer.offer` | sender → receiver | `transferId` (UUID), `name`, `size` (bytes), `mime` |
| `transfer.accept` | receiver → sender | `transferId` |
| `transfer.reject` | receiver → sender | `transferId`, `reason` (`no_space` \| `declined` \| `invalid`) |
| `transfer.finish` | sender → receiver | `transferId`, `sha256` (lowercase hex of the whole file) |
| `transfer.result` | receiver → sender | `transferId`, `ok` (bool), `error` (optional: `checksum_mismatch` \| `no_space` \| `io`) |
| `transfer.cancel` | either | `transferId` |

- **Offers don't time out.** While the receiver is asking where to save, the sender shows "Waiting for your Mac/phone". Either side can cancel. A disconnect doesn't cancel the offer; it's re-sent on reconnect.

Each message gets a fixture in `protocol/fixtures/`, decoded and round-tripped by both codecs. `PROTOCOL.md` is updated to match.

### 2.2 File data (HTTPS; the phone always makes the request)
- **Phone → Mac:** after `accept`, the phone sends `PUT /v1/files/{transferId}` with the raw file body.
- **Mac → phone:** after `accept`, the phone sends `GET /v1/files/{transferId}`, and the Mac streams the file.
- **Streaming:** ~1 MB buffers, no per-chunk acknowledgements; TCP flow control paces the transfer.
- **Resume (download):** `GET` with `Range: bytes=N-`. The Mac answers `206 Partial Content`.
- **Resume (upload):**
  1. `HEAD /v1/files/{id}` returns the header `Upload-Offset: N` (bytes safely on disk).
  2. The phone then sends `PUT` with `Content-Range: bytes N-(size-1)/size`.
  3. The Mac rejects an offset that doesn't match its own with `409`.
  - `Upload-Offset` reports only bytes already flushed to disk.
  - A `PUT` with no `Content-Range` means offset 0.
  - When `HEAD` returns an offset equal to `size`, there's no `PUT`; the phone goes straight to `finish`. This includes zero-byte files.
- **Downloads always use a `GET`:**
  - The phone sends `Range` only when N > 0.
  - When N equals `size` (all bytes already present, or a zero-byte file), the Mac answers `206` with an empty body.
  - After every `GET` that reaches the end of the file, the Mac sends `finish`. The receiver verifies once it has every byte and the `finish`, in whichever order they arrive.
- **Resume after a reconnect:**
  - The sender re-sends `transfer.offer` with the **same** `transferId`.
  - If the receiver knows the id, it answers `accept` again (idempotent), and the phone resumes with `HEAD`+`PUT` or a `GET` with `Range`.
  - If the receiver doesn't know the id (for example after the 24 h cleanup or an "Ask where to save" transfer interrupted by a relaunch), it treats the offer as new. The transfer starts over from byte 0 under the same id. (A receiver can't tell a re-offer from a new offer.)
  - If the id is in the receiver's history, it replays the outcome instead: `transfer.result` for a finished or failed transfer, `transfer.cancel` for a cancelled one.
  - `reject` with `invalid` is only for malformed offers (for example a negative `size`).
  - A `transfer.cancel` that can't be sent because the connection is down is queued and sent after reconnecting.
- **Partial files:** saved as `<name>.macdroid-part` in the destination folder until verified.
  - On resume, each side re-hashes the bytes already present to restore the running SHA-256.
  - After a successful `result`, the receiver renames the part file to the final name.
- **Transfer state** (id, direction, name, size, offset, destination) is persisted on both sides and kept for 24 h. Anything older, with its part file, is cleaned up at launch.

### 2.3 Concurrency and naming
- At most **3 active outgoing transfers per side**; further offers wait in a FIFO queue on the sender. No coordination is needed between sides, so up to 6 transfers can run at once.
- **Name clashes:** the receiver appends ` (1)`, ` (2)`, … before the extension.
- **Name sanitising:** the receiver keeps only the last path component; strips `/`, `\`, NUL, control characters, `:` and `* ? " < > |` (unsafe on the Mac and on Android SAF/FAT providers), and leading dots that would hide the file; maps empty names to `file`; and caps names at 200 UTF-8 bytes (keeping the extension).

### 2.4 Security
- **First task of the plan:** make `TrustedPeerMiddleware` global, so every route except `POST /v1/pair` requires the paired phone. This matters because TLS 1.3 session resumption skips the TLS verify callback.
- `/v1/files/{id}` returns 404 for an unknown id or one in the wrong direction, so ids can't be probed across directions.
- Transfer ids are random UUIDs, generated by the sender.

## 3. Mac

### 3.1 Logic (`MacDroidCore`, unit-tested)
- **`TransferManager` (actor):**
  - owns the outgoing queue and limit, the incoming offers, persistence, and progress, speed and ETA
  - handles control messages via the hub's new incoming-message hook
  - publishes a multi-subscriber stream of transfer snapshots
- **`ConnectionHub` additions (required groundwork):**
  - an incoming-message handler for non-hello messages
  - a multi-subscriber status stream, replacing the single-consumer `AsyncStream`
- **Server routes:** `PUT`/`HEAD`/`GET /v1/files/{id}`, with streaming request and response bodies.
- **`FileNaming`:** sanitising and clash naming.
- **`TransferHistoryStore`:** JSON in Application Support, last 100 entries.
- **Outgoing sources:** the persisted transfer state stores a **security-scoped bookmark** for each file being sent, so a Mac→phone resume still works after a relaunch. If the bookmark can't be resolved, the transfer fails with **Retry**.

### 3.2 App
- **Files page** (sidebar "Files" is now live):
  - a full-width drop zone at the top ("Drop files here to send to <phone>" plus **Send Files…**); disabled with "Phone not connected" when offline
  - **In progress:** rows with direction (→ phone / ← phone), a progress bar, "1.3 of 2.1 GB · 48 MB/s · 17 s left", and cancel ✕
  - **Recent:** history rows; received files get **Show in Finder** / **Open**, failed ones **Retry**
- **Popover — design A, "Control Center style":**
  - **Device card:** phone name, status line (connected · charging) and a battery ring showing the percentage.
  - **Tiles:** two large tiles, **Send Files** ("or drop here") and **Open MacDroid** (⌘O).
  - **Transfers:** up to 2 rows (live progress, or the last received file with **Show**).
  - **Footer:** small Settings ⚙︎ and Quit ⏻ icon buttons.
  - **Drop target:** the **whole popover** is the drop target. While files are dragged over it, a blue overlay reads "Drop to send to <phone>" with the file count and total size. There is no permanent dashed box.
- **Receiving:**
  - Saves to `~/Downloads/MacDroid` by default.
  - Settings → **Files**: choose the folder (security-scoped bookmark; if it goes stale, a banner asks you to re-choose it) and a toggle **Ask where to save each time** (NSSavePanel per file).
  - The Mac asks for `UserNotifications` permission the first time a file arrives (there's no prompt yet).
  - A `UserNotifications` alert "Received <name> from <phone>" with **Show in Finder** and **Open** actions.
- **Entitlements:** add `com.apple.security.files.user-selected.read-write` and `com.apple.security.files.downloads.read-write`.

## 4. Android

### 4.1 Logic (`android/core`, unit-tested)
- **`TransferClient`:**
  - streams uploads (`PUT` from an `InputStream` provider) and downloads (`GET` to an `OutputStream` provider)
  - Range/HEAD resume, running SHA-256, cancel, progress callbacks
- **`TransferQueue`:** at most 3 active transfers; FIFO; per-transfer state (offset, direction, uri, size) behind a `TransferStore` interface so persistence can be tested.
- **Control messages:** handled via `ConnectionManager.incoming`. Outgoing messages use `send`.

### 4.2 App
- **Files tab** (live):
  - a **Send to Mac** card with **Photos** (system Photo Picker, `PickMultipleVisualMedia`, no storage permission) and **Files** (`OpenMultipleDocuments`)
  - **In progress** with live progress and cancel
  - **Recent** (last 100; tap a received file to open it via `ACTION_VIEW`)
- **Home:** the **Send** FAB appears (same Photos/Files choice). "Recent activity" shows the last 3 transfers.
- **Share target:**
  - An activity handles `ACTION_SEND`/`ACTION_SEND_MULTIPLE` for `*/*`.
  - It hands the URIs to `ConnectionService` in an intent with `ClipData` and `FLAG_GRANT_READ_URI_PERMISSION`, because the activity's own grant can end when it finishes.
  - It takes persistable read permission where offered. Otherwise it copies the file to cache, but only up to 100 MB; a larger file without persistable access can't resume after the process dies, so a failure shows **Retry**.
  - It enqueues the files, shows a toast "Sending N files to your Mac" and finishes immediately. No UI is shown if the phone is paired.
  - If the phone isn't paired or connected, it shows a toast "Connect to your Mac first" and opens the app.
- **Receiving:**
  - **Default:** `Download/MacDroid` through **MediaStore Downloads** (`RELATIVE_PATH = Download/MacDroid`). The part file is a pending item (`IS_PENDING = 1`, hidden from other apps) created under the final name; MediaStore adds ` (1)` on a clash. Verifying sets `IS_PENDING = 0`. No permission is needed on API 29+.
  - **Chosen folder:** a setup step "Where should files from your Mac go?" (optional) and Settings use `OpenDocumentTree` with persisted permission, writing through SAF.
    - Part files are created as `application/octet-stream`, so the provider doesn't append an extension.
    - The final rename uses `DocumentsContract.renameDocument` when the provider supports it (`FLAG_SUPPORTS_RENAME`); otherwise the file is copied.
  - Both are behind one `SaveTarget` interface, so the transfer code doesn't care which is used.
  - Settings adds a **Files** section with the folder and **Ask where to save each time** (`CreateDocument` per file).
  - No `MANAGE_EXTERNAL_STORAGE`.
- **Notifications:** a progress notification while transfers run (with **Cancel**), and "Received <name> from Mac" with **Open**.
- **HTTP client:** file calls use a client derived from the pinned one. The call timeout is disabled, and the read and write timeouts are 60 s per socket operation (instead of OkHttp's 10 s), so large files work while a dead connection is still detected.
- **Background:** transfers run inside `ConnectionService` (foreground, type `connectedDevice`), so they continue when the app is in the background or the phone is locked.

## 5. Errors

| Situation | Behaviour |
|---|---|
| Wi-Fi drop / Mac sleep | The transfer shows "Paused — waiting for connection" and resumes automatically after reconnect (state persisted 24 h). |
| Not enough space | The receiver checks free space against `size` on offer and rejects with `no_space`. If space runs out mid-transfer: `result` with `no_space`, the part file is deleted and **Retry** is offered. |
| Checksum mismatch | The part file is deleted; "Transfer failed — file was corrupted in transit", with **Retry**. |
| Cancel on either side | `transfer.cancel`; the part file is deleted; the row shows "Cancelled on your Mac/phone". |
| Destination inaccessible | Banner: re-choose the folder. Meanwhile, fall back to ask-each-time. |
| Not connected | Send controls are disabled ("Phone not connected" / "Connect to your Mac first"). |
| Unsafe file name | Sanitised (§2.3). |
| Unpaired requester on file routes | 403 (global middleware). |

## 6. Testing
- **Protocol fixtures:** every new message is decoded and round-tripped in Swift and Kotlin.
- **Mac unit tests:**
  - `TransferManager`: queue limit, offer/accept/reject, resume offsets, checksum continuation, cancel, cleanup older than 24 h
  - `FileNaming`: sanitising and clashes
  - routes: PUT/HEAD resume, offset mismatch 409, GET Range 206, 403 for strangers, 404 for wrong direction
  - history store
- **Android unit tests:** the `TransferClient` against MockWebServer, covering:
  - upload; upload resume after truncation
  - download with Range; download resume
  - checksum mismatch; cancel
  - `TransferQueue` limit and order
- **Cross-platform:** extend `scripts/cross-platform-test.sh`:
  1. send a few MB each way
  2. kill the connection mid-file
  3. resume
  4. compare checksums
- **Manual (owner):**
  - a 2+ GB video both ways
  - Wi-Fi toggled mid-transfer
  - several files dropped on the popover
  - sending from the Android share sheet
  - receiving with the phone locked
  - changing the save folder on both sides
  - storage-full behaviour if practical

## 7. Decisions made in brainstorming
- Files before notifications and clipboard: clipboard images reuse transfers, and files are independent.
- Auto-save on receive (AirDrop-between-own-devices feel), with "Ask where to save" optional.
- The Finder Share extension is deferred; drag-and-drop and the picker ship now.
- The full resumable design is built in this round, because multi-GB files matter.
- Mac popover design A (Control Center style, whole-popover drop target).
- Android uses the Photo Picker plus SAF (Play-friendly, no broad storage permission).

## 8. Review rulings (2026-10-13)
A review against the code raised 8 points. The owner accepted the suggested answer for each, and they're folded into the sections above:
1. The Android default folder uses MediaStore Downloads; SAF is used only for a folder the user picks (§4.2).
2. Share-target URIs are handed to the service with a grant; copying to cache is capped at 100 MB (§4.2).
3. The Mac stores security-scoped bookmarks for outgoing files (§3.1).
4. Resume works by re-offering the same id. *Refined while planning:* an unknown id is treated as a new offer and starts over from byte 0; ids in the receiver's history replay their outcome (§2.2).
5. The limit is 3 outgoing per side (§2.3).
6. Offers don't time out (§2.1).
7. Zero-byte files, a `PUT` with no `Content-Range`, and the extra unsafe name characters (§2.2, §2.3). *Refined while planning:* downloads always make a `GET` (empty when nothing is left), so the Mac always knows when to send `finish`.
8. OkHttp timeouts are lifted (no call timeout; 60 s per read/write), the Mac asks for notification permission, and `Upload-Offset` counts flushed bytes (§2.2, §3.2, §4.2).
