# Photos (Plan 6): follow-ups

Branch `feat/photos`. Built 2026-10-14; all gates and the cross-platform test pass.

## Owner hands-on checklist

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

## Deferred minors

From the final review (2026-10-14). The 5 Important findings were fixed (see CHANGELOG). **All 9 minors below were fixed on `fix/photos-minors` (2026-10-14), one commit each; see the CHANGELOG entry "Photos follow-ups".**
1. **No progress timeout after the offer:** once an offer has arrived, nothing times out; a stalled transfer spins until disconnect. Also, `finish` forgets after an await, so a late offer can slip into the cache unobserved.
2. **Duplicate cache fetches:** concurrent cache fetches of the same photo (double-click plus Space, drag during preview) aren't coalesced.
3. **Observer scope:** `PhotosBridge` observes all MediaStore files; downloads and received files also trigger `photos.changed`. Watch the Images and Video URIs only.
4. **Stale selection:** a partial-access user re-picking a different set stays Partial→Partial, so no `photos.changed` is sent and the Mac shows the old set.
5. **Settings fallback:** Settings → Photos opens app settings after the first "Don't allow", not only once Android stops asking; gate on `shouldShowRequestPermissionRationale`.
6. **Forget leftovers:** Forget doesn't delete `Caches/PhotoCache` originals or clear `TransferManager.photoRequests` (emptied at the next launch).
7. **KDoc:** `TransferEngine.enqueueWithId` took `enqueueClipboard`'s KDoc.
8. **Busy phone:** when the phone is already sending 3 files, photo fetches can time out ("Your phone didn't respond").
9. **Unrequested thumbnails:** the Mac accepts thumbnails it never asked for (bounded by trims; the peer is the trusted paired phone).
