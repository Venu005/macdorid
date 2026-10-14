# MacDroid Protocol (v1)

The Mac is the server; the phone is always the client. Everything runs over TLS 1.3 with mutual
authentication. Each side pins the other's certificate by SHA-256 fingerprint (lowercase hex of
the DER-encoded leaf certificate). Fixtures in `fixtures/` are canonical: both apps' tests decode
and re-encode them.

## Discovery
Bonjour/DNS-SD service `_macdroid._tcp`. TXT: `v=1`, `fp=<first 16 hex chars of Mac fingerprint>`.

## Pairing QR
`macdroid://pair?host=<ipv4>&port=<port>&fp=<64 hex>&code=<base32>&name=<Mac name>`
Values are percent-encoded; everything outside `A-Z a-z 0-9 - . _ ~` is encoded (so `+` is `%2B`).
See `fixtures/pairing-uri.txt`.

## HTTP endpoints
| Method | Path | Who | Body |
|---|---|---|---|
| POST | `/v1/pair` | any client cert, only while the pairing window is open | `pair.request.json` → 200 `pair.response.json`, 403 if code wrong/expired |
| GET | `/v1/info` | paired phone only | `{"macName": "...", "protocol": 1}` |
| GET (upgrade) | `/v1/ws` | paired phone only | WebSocket control channel |
| HEAD | `/v1/files/{transferId}` | paired phone only | Phone → Mac upload: 200 with header `Upload-Offset: N` (bytes already on the Mac's disk); 404 for an unknown id or a Mac → phone transfer |
| PUT | `/v1/files/{transferId}` | paired phone only | Raw file bytes. No `Content-Range` means offset 0; a resume sends `Content-Range: bytes N-(size-1)/size`. 200 + `Upload-Offset`; 409 + `Upload-Offset` if N isn't the Mac's offset; 507 if the Mac's disk is full |
| GET | `/v1/files/{transferId}` | paired phone only | Mac → phone download. `Range: bytes=N-` only when N > 0 → 206 (+ `Content-Range` when N < size; empty body when N == size). 404 for an unknown id or a phone → Mac transfer; 410 if the Mac's file is gone |

## Control channel
Text frames: `{"v":1,"type":"<type>","id":"<uuid>","body":{...}}`. Unknown types are ignored;
malformed frames are dropped without closing the connection. Both sides send `hello` first.

| Type | Direction | Body |
|---|---|---|
| `hello` | both | `protocol`, `app`, `device`, `features[]` (may be missing → none) |
| `device.status` | phone → Mac | `battery` (0–100), `charging` |
| `transfer.offer` | sender → receiver | `transferId` (lowercase UUID), `name`, `size` (bytes), `mime`, `purpose` (optional: absent = file; `clipboard` = clipboard image, PNG ≤ 10 MiB; `photo` = a photo the Mac asked for with `photos.fetch`) |
| `transfer.accept` | receiver → sender | `transferId` |
| `transfer.reject` | receiver → sender | `transferId`, `reason` (`no_space` \| `declined` \| `invalid`) |
| `transfer.finish` | sender → receiver | `transferId`, `sha256` (lowercase hex of the whole file) |
| `transfer.result` | receiver → sender | `transferId`, `ok`, `error` (omitted when ok; `checksum_mismatch` \| `no_space` \| `io`) |
| `transfer.cancel` | either | `transferId` |
| `notification.posted` | phone → Mac | `key`, `package`, `appName`, `title` (≤ 200), `text` (≤ 2000), `postedAt` (epoch ms), `actions` [`{index, title}`] (≤ 3), `canReply`, `iconHash` (16 lowercase hex or empty), `isUpdate` |
| `notification.removed` | phone → Mac | `key` |
| `notification.sync` | phone → Mac | `keys` — forwarded notifications still showing on the phone; sent after every `hello` |
| `notification.icon` | phone → Mac | `hash`, `png` (base64, ≤ 96×96) — once per hash per connection, before it's referenced |
| `notification.apps` | phone → Mac | `forwarding`, `access`, `apps` [`{package, appName, iconHash, muted}`] |
| `notification.reply` | Mac → phone | `key`, `text` |
| `notification.action` | Mac → phone | `key`, `actionIndex` (index in the Android notification's actions) |
| `notification.dismiss` | Mac → phone | `key` |
| `notification.mute` | Mac → phone | `package`, `muted` |
| `notification.error` | phone → Mac | `key`, `reason` (`gone` \| `failed`) |
| `device.ring` | Mac → phone | `action` (`start` \| `stop`) |
| `device.ringState` | phone → Mac | `ringing` |
| `link.open` | both | `url` — http/https with a host, ≤ 8,192 bytes; anything else is ignored |
| `media.state` | phone → Mac | `active`; when true: `package`, `appName`, `title`, `artist`, `durationMs`, `positionMs`, `positionAt` (phone epoch ms), `playing`, `canPrevious`, `canNext`, `canSeek`, `artHash` |
| `media.art` | phone → Mac | `hash` (16 lowercase hex), `jpeg` (base64, ≤ 300 px) — once per hash per connection, before it's referenced |
| `media.command` | Mac → phone | `command` (`play` \| `pause` \| `next` \| `previous` \| `seek`), `positionMs` (seek only) |
| `clipboard.set` | both | `itemId` (UUID), `kind` (`text`), `text` — the encoded frame must be ≤ 1 MiB |
| `photos.albums` | Mac → phone | `requestId` |
| `photos.albumList` | phone → Mac | `requestId`, `access` (`full` \| `partial` \| `none`), `albums` [`{id, name, count, coverId?}`], `all` first |
| `photos.query` | Mac → phone | `requestId`, `albumId` (`all` or a bucket id), `kind` (`all` \| `photos` \| `videos`), `cursor?`, `limit` (≤ 200) |
| `photos.page` | phone → Mac | `requestId`, `access`, `items` [`{id, kind (photo \| video), name, mime, takenAt (ms), size, width, height, durationMs?}`], `nextCursor?` |
| `photos.thumbs` | Mac → phone | `ids` (≤ 60) |
| `photos.thumb` | phone → Mac | `id`, `jpeg` (base64, longest side 256 px, quality 80) |
| `photos.fetch` | Mac → phone | `id`, `transferId` (lowercase UUID chosen by the Mac) |
| `photos.fetchFailed` | phone → Mac | `transferId`, `reason` (`gone` \| `denied`) |
| `photos.changed` | phone → Mac | empty body: the library or the access level changed |
| `mirror.start` | Mac → phone | `sessionId` (lowercase UUID), `maxSize` (720–1920), `fps` (30 \| 60), `audio` (bool) |
| `mirror.state` | phone → Mac | `sessionId`, `state` (`starting` \| `streaming` \| `stopped`), `width?`, `height?`, `rotation?` (0/90/180/270), `reason?` (`user` \| `mac` \| `denied` \| `consentTimeout` \| `noPermission` \| `ended` \| `error`), `audio?`, `control?` |
| `mirror.stop` | both | `sessionId` |
| `mirror.keyframe` | Mac → phone | `sessionId` |
| `mirror.ping` | Mac → phone | `t0` (Mac monotonic µs) |
| `mirror.pong` | phone → Mac | `t0`, `t1` (phone µs when received), `t2` (phone µs when sent) |

### Transfer rules
- The phone always makes the HTTP request: `PUT` for phone → Mac, `GET` for Mac → phone. Bytes go over HTTPS, never the WebSocket.
- **Resume after a reconnect:** the sender re-sends `transfer.offer` with the same id. The receiver accepts a known id again, treats an unknown id as a new offer (starting from byte 0), and replays the outcome for an id in its history (`transfer.result`, or `transfer.cancel` if it was cancelled).
- **Uploads:** `HEAD` first; when `Upload-Offset` equals `size` there's no `PUT`, and the phone sends `finish`.
- **Downloads:** the phone always sends `GET`. After every `GET` that reaches the end of the file, the Mac sends `finish`. The phone verifies once it has every byte and the `finish`, in either order.
- Partial files are `<name>.macdroid-part` until verified. At most 3 outgoing transfers per side are active; the rest wait in a FIFO queue. Photos the Mac fetched (`purpose: photo`) have their own 3 slots on the phone, so files being sent never delay them.
- File names from the other side are sanitised; see `fixtures/file-naming.json`.

### Features
`hello.features` lists what each side implements: `files`, `notifications`, `clipboard`, `ring`, `links`, `media`, `photos` and `mirror`. A feature is used only when both sides list it. A missing `features` means none.

### Notification rules
- The phone owns the mute list. `notification.mute` is a request; the phone answers with a fresh `notification.apps`.
- The phone sends `notification.apps` and then `notification.sync` after every `hello`, and sends `notification.apps` again whenever mutes, the "Show on your Mac" switch or notification access change.
- A repost with unchanged title, text, buttons and reply ability isn't sent again. A changed one is sent with `isUpdate: true`.
- Nothing is queued while disconnected, and nothing is replayed on reconnect.
- `iconHash` is exactly 16 lowercase hex characters. The Mac ignores any other value, because it becomes a file name.

### Clipboard rules
- Mac → phone is automatic; phone → Mac is on demand. Nothing is queued while disconnected.
- Images go over the transfer channel with `purpose: clipboard`. The receiver never asks where to save, keeps the file in app-private storage, puts it on the clipboard, and keeps it out of Files history. Offers over 10 MiB are rejected with `invalid`.
- The Mac never sends items marked `org.nspasteboard.ConcealedType`, `TransientType` or `AutoGeneratedType`, nor file URLs.
- Echo prevention: the Mac never re-sends a pasteboard change it made itself.

### Small wins rules
- **Ring:** the phone sends `device.ringState` on every start and stop, including automatic stops (30 s, a volume key, a disconnect).
- **Media updates:** `media.state` is sent only when the track, playing state, controls or artwork change, or the position jumps by more than 2 s; plus once after every `hello`. The receiver projects the position while playing.
- **Links:** a `link.open` that isn't http/https with a host, or is over 8,192 bytes, is ignored.

### Photos rules
- **Ids** are MediaStore `_ID` decimal strings (1–20 digits). Anything else is ignored, and never becomes a file path.
- **Order and paging:** newest first by `date_added` (seconds), then `_id`, both descending; `takenAt = date_added × 1000`. The cursor is `"<dateAddedSeconds>:<id>"`, and the next page is strictly older than it (`date_added < a OR (date_added = a AND _id < id)`), so new photos never shift pages. (`DATE_TAKEN` can be null and MediaProvider rejects SQL functions, so it isn't used.)
- **Limits:** pages ≤ 200 items, thumbnail requests ≤ 60 ids. Thumbnails that are gone are skipped silently.
- **Originals:** the phone answers `photos.fetch` by offering the file through the transfer channel with the Mac's `transferId` and `purpose: photo`, or with `photos.fetchFailed`. The Mac accepts a `photo` offer only for a transfer id it asked for; anything else is rejected with `invalid`. The Mac asks for at most 3 originals at a time (the phone's upload limit) and gives up after 30 s without an offer.
- **No access:** the phone answers `photos.albums` and `photos.query` with `access: none` and empty lists, ignores `photos.thumbs`, and answers `photos.fetch` with `denied`.
- **Changes:** the phone sends `photos.changed` (debounced 1 s) when MediaStore changes or the access level changes; the Mac reloads its albums and first page.
