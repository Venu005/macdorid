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

## Control channel
Text frames: `{"v":1,"type":"<type>","id":"<uuid>","body":{...}}`. Unknown types are ignored;
malformed frames are dropped without closing the connection. Both sides send `hello` first.

| Type | Direction | Body |
|---|---|---|
| `hello` | both | `protocol`, `app`, `device`, `features[]` |
| `device.status` | phone → Mac | `battery` (0–100), `charging` |

Notification, clipboard and transfer messages are added by later plans (see the round-1 spec §4.4).
