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
