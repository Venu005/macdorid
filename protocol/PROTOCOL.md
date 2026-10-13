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
| `transfer.offer` | sender → receiver | `transferId` (lowercase UUID), `name`, `size` (bytes), `mime` |
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

### Transfer rules
- The phone always makes the HTTP request: `PUT` for phone → Mac, `GET` for Mac → phone. Bytes go over HTTPS, never the WebSocket.
- **Resume after a reconnect:** the sender re-sends `transfer.offer` with the same id. The receiver accepts a known id again, treats an unknown id as a new offer (starting from byte 0), and replays the outcome for an id in its history (`transfer.result`, or `transfer.cancel` if it was cancelled).
- **Uploads:** `HEAD` first; when `Upload-Offset` equals `size` there's no `PUT`, and the phone sends `finish`.
- **Downloads:** the phone always sends `GET`. After every `GET` that reaches the end of the file, the Mac sends `finish`. The phone verifies once it has every byte and the `finish`, in either order.
- Partial files are `<name>.macdroid-part` until verified. At most 3 outgoing transfers per side are active; the rest wait in a FIFO queue.
- File names from the other side are sanitised; see `fixtures/file-naming.json`.

### Features
`hello.features` lists what each side implements: `files` and `notifications` (and `clipboard` once Plan 4 lands). A feature is used only when both sides list it. A missing `features` means none.

### Notification rules
- The phone owns the mute list. `notification.mute` is a request; the phone answers with a fresh `notification.apps`.
- The phone sends `notification.apps` and then `notification.sync` after every `hello`, and sends `notification.apps` again whenever mutes, the "Show on your Mac" switch or notification access change.
- A repost with unchanged title, text, buttons and reply ability isn't sent again. A changed one is sent with `isUpdate: true`.
- Nothing is queued while disconnected, and nothing is replayed on reconnect.
- `iconHash` is exactly 16 lowercase hex characters. The Mac ignores any other value, because it becomes a file name.
