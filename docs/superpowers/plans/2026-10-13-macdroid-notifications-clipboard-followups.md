# Notifications + clipboard (Plans 3 and 4) — follow-ups

## Owner's hands-on checklist — notifications (spec §6)
Install Android 0.4.0 and build the Mac app (DEV-GUIDE). Grant notification access in the setup step (or Notifications tab → Turn on).
- [ ] A WhatsApp message arrives on the Mac as a banner: "WhatsApp · <phone>", with the title, the text and the app icon.
- [ ] Reply from the banner → the reply is sent from the phone.
- [ ] Reply from the Notifications page (hover → Reply) → sent.
- [ ] Press a notification button (e.g. "Mark as read") from the Mac → it happens on the phone.
- [ ] Dismiss on the Mac (✕ or close the banner) → gone on the phone. Dismiss on the phone → gone on the Mac.
- [ ] Mute an app in Muted apps… on the Mac → its switch turns off in the phone's Notifications tab; and the reverse.
- [ ] Turn off "Show on your Mac" on the phone → nothing arrives; the Mac page and the Phone page say so.
- [ ] Revoke notification access → the phone tab shows "Notification access is off — Turn on"; the Mac says so.
- [ ] Disconnect (Wi-Fi off), clear notifications on the phone, reconnect → the Mac's list tidies up.
- [ ] A busy group chat → after 10 in 5 s, no more banners, but the page keeps filling.
- [ ] Click a banner → MacDroid opens on the Notifications page.
- [ ] A login code (OTP) → shows without the code (expected, Android 15+).

## Owner's hands-on checklist — clipboard (spec §6)
- [ ] Copy text on the Mac → paste on the phone (within about a second).
- [ ] Take a screenshot to the clipboard on the Mac (⌃⇧⌘4) → paste the image on the phone (e.g. into WhatsApp).
- [ ] Copy a password from 1Password or the Passwords app → it does **not** reach the phone.
- [ ] Quick Settings → add "Send clipboard" → copy on the phone, tap the tile → "Sent to Mac"; ⌘V on the Mac works, with a "Clipboard from <phone>" notification.
- [ ] The "Send clipboard" button on MacDroid's notification → same.
- [ ] Clipboard tab → Send now → same.
- [ ] Share text from Chrome → "Copy to Mac" → on the Mac clipboard (not in Downloads). Share a photo → "Copy to Mac" → pastes as an image on the Mac.
- [ ] Clipboard image transfers never appear in Files on either side.
- [ ] Mac Clipboard page: search, Copy (does not bounce to the phone), Send; Settings → Keep history 10 trims the list; Clear History empties it.
- [ ] Turn off "Send to phone" on the Mac and "Get Mac clipboard" on the phone → nothing syncs in that direction.
- [ ] Send while disconnected → "Not connected to your Mac".

## Deferred / rulings
(Execution rulings and deferred review findings go here.)
