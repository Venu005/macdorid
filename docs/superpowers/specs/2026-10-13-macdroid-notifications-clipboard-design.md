# MacDroid — Notifications and Clipboard (Plans 3 and 4)

**Date:** 2026-10-13
**Status:** Approved in brainstorming on 2026-10-13; awaiting the owner's review of this written spec.
**Builds on:**
- Round-1 spec §4.4, §5.1, §5.2 (`2026-10-12-macdroid-round1-design.md`). This spec refines them and, where it differs, wins.
- The UI redesign spec (`2026-10-13-macdroid-ui-ux-redesign-design.md`).
- The file transfer spec (`2026-10-13-macdroid-file-transfer-design.md`), whose transfer channel carries clipboard images.

**Delivery:** one spec, two plans, one branch `feat/notifications-clipboard`:
- **Plan 3:** groundwork + notifications.
- **Plan 4:** clipboard.

There is one owner hands-on check and one merge at the end.

## 1. Goal and scope

**Notifications:** Android notifications appear on the Mac as real macOS notifications. You can reply, press their buttons and dismiss them from the Mac. Dismissal syncs both ways.

**Clipboard:** copying on the Mac puts the item on the phone automatically. Phone → Mac is on demand, because Android forbids background clipboard reads. The Mac keeps a history.

**Success:** the owner uses both daily without thinking about them, and both stay publishable (Play policy, Mac App Store sandbox).

**Out of scope**
- Notification history on the phone (the phone has its own shade).
- Replaying notifications posted while disconnected.
- Syncing copied Finder *files* through the clipboard (use Send Files).
- Rich text/HTML clipboard formats: plain text only.
- Forcing a fixed Android colour scheme: the app keeps Material You (wallpaper colours).

## 2. Protocol

### 2.1 Groundwork
- **`hello.features`:**
  - The phone and the Mac both send `["files","notifications","clipboard"]`.
  - A feature is active only when **both** sides list it.
  - Swift `Hello` decodes a missing `features` as `[]` (Kotlin already does).
- **Exposing the peer's features:**
  - Android: `ConnectionState.Connected` gains `features: Set<String>`.
  - Mac: the peer's features are already in `ConnectionHub`'s peer info and are exposed to the app.
- **Missing feature on the other side:** the corresponding page or tab shows "Update MacDroid on your phone/Mac to use this".

### 2.2 Notification messages
| Type | Direction | Body |
|---|---|---|
| `notification.posted` | phone → Mac | `key`, `package`, `appName`, `title`, `text`, `postedAt` (epoch ms), `actions` [`{index, title}`], `canReply` (bool), `iconHash` (16 hex), `isUpdate` (bool) |
| `notification.removed` | phone → Mac | `key` |
| `notification.sync` | phone → Mac | `keys` [string] — every forwarded notification still showing on the phone; sent right after `hello` |
| `notification.icon` | phone → Mac | `hash`, `png` (base64, ≤ 96×96) |
| `notification.apps` | phone → Mac | `forwarding` (bool), `access` (bool), `apps` [`{package, appName, iconHash, muted}`] |
| `notification.reply` | Mac → phone | `key`, `text` |
| `notification.action` | Mac → phone | `key`, `actionIndex` |
| `notification.dismiss` | Mac → phone | `key` |
| `notification.mute` | Mac → phone | `package`, `muted` (bool) |
| `notification.error` | phone → Mac | `key`, `reason` (`gone` \| `failed`) |

**Messages**
- **Keys:** `key` is Android's `StatusBarNotification.key`, opaque to the Mac.
- **Icons:** the phone sends `notification.icon` once per hash per connection, before the first `posted` that references it. The Mac caches icons on disk by hash.
- **`notification.posted`:**
  - `isUpdate = true` when a notification with the same key is updated in place.
  - `title` is capped at 200 characters and `text` at 2,000, truncated on the phone.

**Mute list**
- **The phone owns the mute list**, because filtering happens on the phone. A Mac mute is a request: `notification.mute` → the phone updates and sends a fresh `notification.apps`.
- `notification.apps` is sent after `hello`, and on any change to mutes, the "Show on your Mac" switch or notification access.
- This replaces round-1's `notification.filter` (a full list from the Mac), which could overwrite phone-side changes.

### 2.3 Clipboard messages
| Type | Direction | Body |
|---|---|---|
| `clipboard.set` | both | `itemId` (UUID), `kind` = `text`, `text` (≤ 1 MB UTF-8) |

**Images** go over the file-transfer channel:
- `transfer.offer` gains an optional `purpose`: `"file"` (default when absent) or `"clipboard"`. `mime` is `image/png`, and the size is ≤ 10 MB.
- **A receiver of a `clipboard` offer:**
  - never asks where to save and never shows it in Files/transfer history or notifications
  - stores the image in app-private storage
  - on success, puts it on the clipboard
  - rejects offers over 10 MB with `invalid`
- **Retries:** resume and checksums work as for files. A clipboard transfer that fails is dropped silently and not retried from history.

Every new message, and `transfer.offer` with `purpose`, gets a fixture in `protocol/fixtures/` that both codecs decode and round-trip. `PROTOCOL.md` is updated.

## 3. Notifications

### 3.1 Phone
- **Listener:** a `NotificationListenerService` (the system "Notification access" permission) in the app module. The filter logic lives in `android/core` and is pure and tested.
- **Forwarded notifications:** those posted while connected, unless any of these applies:
  - "Show on your Mac" is off
  - the package is muted
  - `FLAG_ONGOING_EVENT` or `FLAG_FOREGROUND_SERVICE` is set
  - `FLAG_GROUP_SUMMARY` is set
  - importance is `IMPORTANCE_MIN` or lower
  - the package is MacDroid's own
  - title and text are both empty
- **Apps seen:** every package that posts a forwardable notification is recorded (label, icon hash, muted flag), persisted in prefs. This is the Notifications tab's app list.
- **Removal:** `onNotificationRemoved` for a forwarded key → `notification.removed`.
- **Reply:**
  - The phone finds the action with a free-form `RemoteInput`, fills it via `RemoteInput.addResultsToIntent` and sends its `PendingIntent`.
  - `canReply` is true when such an action exists.
  - `actions` lists the other actions, up to 3, by their index in `Notification.actions`.
- **Action:** sends that action's `PendingIntent`.
- **Errors:** if the key is no longer active → `notification.error{gone}`. If sending throws (`CanceledException` or a security error) → `notification.error{failed}`.
- **Dismiss:** `cancelNotification(key)`.
- **Background launch limit:** an action that opens an Activity may be blocked by Android's background-launch rules while the phone is locked. That is accepted; actions are usually broadcasts.
- **One-time codes:** Android 15+ redacts OTP notifications for listener apps. They arrive without the code. This is documented in the UI copy and not worked around.

### 3.2 Mac
- **Posting:**
  - `UNUserNotificationCenter`, identifier = `key`, so an update replaces in place.
  - `threadIdentifier` = package.
  - title = notification title; subtitle = "‹appName› · ‹phone name›"; body = text.
  - The cached app icon is attached as the thumbnail. The left icon is always MacDroid's, which macOS doesn't allow changing.
- **Categories:**
  - One per distinct (canReply, action titles) combination, registered on demand.
  - The set is kept and re-registered as a whole, because `setNotificationCategories` replaces it.
  - Reply → `UNTextInputNotificationAction`; the others → `UNNotificationAction`.
- **Response handling:**
  - **Default (click):** opens the main window on the Notifications page.
  - **Reply / action:** sends the matching message.
  - **Dismiss (`UNNotificationDismissActionIdentifier`, via `.customDismissAction`):** sends `notification.dismiss`.
- **Foreground:** banners are still shown when the app is in the foreground (`willPresent` → `.banner, .list, .sound`).
- **Removed notifications:** `notification.removed` → remove the delivered notification and the page entry.
- **`notification.sync`:** removes delivered notifications and page entries whose key is not listed.
- **Flood protection:** more than 10 `posted` from one package within 5 s → no further banners for that package until it's quiet for 5 s. Entries still go to the page.
- **Settings → Notifications:**
  - "Show phone notifications" (Mac-side, default on). When off, the Mac ignores `posted` and posts nothing.
  - The muted-apps list (from `notification.apps`).
- **Permission:** MacDroid already requests notification permission for received files. If denied, the Notifications page shows a banner with "Open System Settings".

### 3.3 UI
**Mac Notifications page (sidebar, layout A, grouped by app)**
- One collapsible group per app, newest first.
- Each card shows the title, text and relative time.
- Hovering a card reveals Reply (inline field), the app's buttons and ✕ (dismiss).
- Header: "Muted apps…" opens a sheet with a switch per app, and "Clear all" dismisses everything on both sides.
- Last 50 entries, in memory only, cleared on quit.
- Banner states:
  - notification access off on the phone
  - "Show on your Mac" off on the phone
  - macOS permission denied
  - feature missing on the phone
- The Phone page shows "Notifications off on phone" when `access` or `forwarding` is false.

**Android Notifications tab (control panel; replaces the placeholder)**
- Top card:
  - the "Show on your Mac" switch, with the subtitle "Forwarding to ‹Mac name›"
  - when notification access is off, it becomes "Notification access is off — Turn on", which opens the system screen
- "Apps" list: every app seen, each with a switch (on = shown on the Mac) and a subtitle ("12 today" / "Muted").
- Footnote: "Apps appear here after their first notification. Mutes sync with the Mac."
- Copy mentions that one-time codes are hidden by Android.

**Setup wizard:** the existing Notifications step (MacDroid's own `POST_NOTIFICATIONS`) also asks for notification access. Same step, still 6 steps. It is skippable; the tab offers it again.

**Colours:** Material You, as everywhere in the app. Black and white on the owner's phone.

## 4. Clipboard

### 4.1 Mac → phone (automatic)
- **Watching:** poll `NSPasteboard.general.changeCount` every 0.5 s.
- **Sending:**
  - Text or URL → `clipboard.set`.
  - PNG or TIFF image → converted to PNG. If ≤ 10 MB, it is sent as a `purpose=clipboard` transfer; otherwise it is skipped, with a note on the Clipboard page.
- **Never sent:**
  - Items carrying `org.nspasteboard.ConcealedType`, `org.nspasteboard.TransientType` or `org.nspasteboard.AutoGeneratedType`.
  - File URLs (Finder copies).
  - Text over 1 MB (a note on the page).
  - Anything while disconnected (not queued).
- **"Send to phone" setting** (Settings → Clipboard, default on).

### 4.2 Phone → Mac (on demand)
**Entry points**
- Quick Settings tile "Send clipboard".
- A "Send clipboard" action on the foreground-service notification.
- The Clipboard tab's "Send now".
- A new share target, **"Copy to Mac"**: text, or a single image. It sits next to the existing "Send to Mac" for files.

**Reading the clipboard**
- The tile, notification action and "Send now" open a translucent `ClipboardSendActivity`.
- It reads the clipboard once it has window focus (Android only lets the focused app read it), hands the item to `ConnectionService` and finishes.
- On Android 14+ the tile uses `startActivityAndCollapse(PendingIntent)`.

**What gets sent**
- Text → `clipboard.set`.
- An image URI → read via `ContentResolver`; if ≤ 10 MB, it is sent as a `clipboard` transfer.

**Toasts**
- "Sent to Mac"
- "Not connected to your Mac"
- "Clipboard is empty"
- "Too large to send"

### 4.3 Receiving
**Phone**
- `ClipboardManager.setPrimaryClip` (allowed in the background).
- Images:
  - saved under `cacheDir/clipboard/` and exposed through a `FileProvider` content URI
  - only files referenced by the last 10 items are kept
- "Get Mac clipboard" switch (Clipboard tab, default on). When off, incoming items are ignored.
- Android shows its own "copied" preview; that is expected.

**Mac**
- Writes to `NSPasteboard.general`.
- Posts a small notification, "Clipboard from ‹phone name›".
- Setting: "Receive from phone" (default on).

**Echo prevention:** each side remembers the `itemId` and content hash of the last item it *applied*. A local clipboard change whose content hash equals it is not sent back.

### 4.4 History and UI
**Mac Clipboard page**
- Header: "Sync to phone" state, plus a search field.
- Rows: a kind icon (text, link or image), a preview, a direction (→ phone / ← from phone) and a relative time.
- Hover: **Copy** (puts it back on the Mac clipboard without re-sending) and **Send** (sends to the phone).
- Capacity: 10/20/50 (default 20), set in Settings → Clipboard, which also has "Clear history".
- Storage: `Application Support/Clipboard/history.json`, with images as files beside it.

**Android Clipboard tab (replaces the placeholder)**
- A "Send clipboard to Mac" card with a **Send now** button.
- The "Get Mac clipboard" switch.
- "Recent": the last 10 items, in memory only. Tap one to copy it to the phone clipboard again.

## 5. Errors

| Situation | Behaviour |
|---|---|
| Notification access revoked | Phone tab card "Notification access is off — Turn on"; `notification.apps{access:false}` → the Mac Phone page and Notifications page say so |
| macOS notifications denied | Mac Notifications page banner + "Open System Settings"; the page still fills |
| Reply/action on a gone notification | `notification.error{gone}` → Mac banner "This notification is no longer on your phone"; the entry is removed |
| Reply/action failed on the phone | `notification.error{failed}` → Mac banner "Couldn't send reply to ‹app›" |
| Clipboard send while disconnected | Phone toast "Not connected to your Mac"; Mac → phone copies are skipped, not queued |
| Clipboard too large | Not sent; a note on the Mac page; phone toast "Too large to send" |
| Clipboard image transfer fails | Resume as usual; on final failure, dropped silently |
| Peer lacks the feature | The page or tab says "Update MacDroid on your ‹phone/Mac› to use this"; nothing is sent |

## 6. Testing

**Unit tests**
- **Protocol:** every new fixture is decoded and round-tripped by Swift and Kotlin, including `transfer.offer` with and without `purpose`, and `hello` without `features`.
- **Android core:**
  - the notification filter rules (each exclusion)
  - the action/reply mapping
  - the apps-seen list and mutes
  - echo prevention
  - the recent-items cap
  - clipboard size limits
- **Mac core:**
  - the notification store: last 50, sync removal, update-in-place
  - flood protection
  - category registry
  - the clipboard watcher's type rules (concealed, transient, auto-generated, file URLs, size caps)
  - echo prevention
  - the history store (capacity, persistence, search, image files)
  - presentation for both pages
- **Transfer:** a `purpose=clipboard` offer skips ask mode and history, and is rejected over 10 MB.

**Cross-platform script** (`scripts/cross-platform-test.sh`): extended so the real Swift server and the real Kotlin client exchange:
- `notification.posted` → `removed`
- a `notification.reply` arriving at the client
- `clipboard.set` both ways
- a clipboard image transfer

**Owner's hands-on checklist**
- Reply to a WhatsApp message from a Mac banner and from the page.
- Press a notification button from the Mac.
- Dismiss on the Mac → gone on the phone, and the reverse.
- Mute an app on the Mac → the switch flips on the phone, and the reverse.
- Turn off notification access → both sides say so.
- Disconnect, clear notifications on the phone, reconnect → the Mac tidies up.
- Copy text and a screenshot on the Mac → paste on the phone.
- Copy from 1Password (or the macOS Passwords app) → *not* on the phone.
- Send from the Quick Settings tile, the notification button, "Send now" and "Copy to Mac" in the share sheet → on the Mac clipboard, with a notification.
- Mac history: search, Copy, Send, capacity change, Clear history.

## 7. Decisions (this round)
| Decision | Why | Rejected |
|---|---|---|
| One spec, two plans (3: notifications, 4: clipboard) on one branch | Shared groundwork; one review and one merge | Two separate rounds; one giant plan |
| Show full notification content | The owner's own Mac; macOS hides previews when locked; per-app mute covers the rest | Respect Android's private flag; a setting |
| Mac → phone clipboard on by default, switch in Settings | Universal-Clipboard feel; concealed items are never sent | Off by default; a popover pause switch |
| Phone owns the mute list; the Mac sends mute requests | Filtering happens on the phone; avoids overwrite races | Round-1's full-list `notification.filter` from the Mac |
| `notification.sync` on reconnect | Clears stale Mac entries without replaying anything | Replay; leaving stale entries |
| The phone Notifications tab is a control panel, not a history | The phone already has a shade | Duplicate history on the phone |
| Clipboard images over the transfer channel with `purpose=clipboard` | Resume and checksums for free | Base64 in the WebSocket |
| Mac Notifications page grouped by app (layout A) | Matches Notification Center | A timeline with filter chips |
| The popover is unchanged | Notifications live in Notification Center; clipboard sync is invisible | Adding notification/clipboard sections |
| Android keeps Material You colours | The owner's choice (B&W on their phone already) | Forcing black and white |
