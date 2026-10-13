# File transfer (Plan 2) — follow-ups

## Owner's hands-on checklist (spec §6)
Owner-verified on 2026-10-13: everything except the 2 GB+ file.
- [ ] A 2 GB or larger video, phone → Mac and Mac → phone. *(owner will check later)*
- [x] Toggle Wi-Fi mid-transfer: the row shows "Paused — waiting for connection", then resumes and finishes.
- [x] Drop several files on the Mac popover: the overlay shows the count and size, and all arrive on the phone.
- [x] Android share sheet → "Send to Mac" from Photos and from Files: the toast appears, with no UI otherwise.
- [x] Share while disconnected: "Connect to your Mac first", and the app opens.
- [x] Receive on the phone with the screen locked: the progress notification shows, then "Received <name> from Mac" → Open.
- [x] Change the save folder on both sides; revoke the Android folder (clear app storage access): the banner shows and the next file asks where to save.
- [x] "Ask where to save each time" on both sides; cancel the transfer from the other side while the save panel or prompt is open.
- [x] Storage-full behaviour, if practical.
- [x] Mac "Ask where to save each time" → choose the Desktop (not Downloads): the file arrives there.
- [x] Android ask mode: swipe the "Save … from your Mac?" notification away (if allowed), then answer from the Files tab's "Choose where to save".
- [x] Delete the chosen Android folder in the Files app, then receive: the banner shows and it asks where to save.
- [x] Share a small file, cancel it on the Mac, then Retry on the phone: it sends.

## Deferred minors (from the Plan 2b final review)
- `MediaStorePart.finish` ignores the `update()` row count, so a still-pending item would be reported as Done.
- A part can leak if a cancel races `createPart`; it needs `NonCancellable` cleanup.
- Stale "Save … from your Mac?" notifications can create an empty orphan document after the transfer is gone.
- Home's Send button silently does nothing while disconnected; it should say "Connect to your Mac first".
- Android checks whether a file exists on the main thread when you tap a row.
- The Mac checks file existence for every history row on every update; it should cache the result per id.
- The Mac notification's Open/Show doesn't check the file, and notification permission is cached for the session.
- The SAF copy fallback creates the target with its real MIME type, so a provider may append an extension.

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
