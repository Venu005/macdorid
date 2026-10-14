# Mirroring (Plans 7–8): follow-ups

## Probe results (2026-10-14, Nothing Phone (3), Android 17 / Nothing OS, with the owner)

| # | Question | Observed | Decision |
|---|---|---|---|
| Q1a | Can MacDroid open the consent screen from the background **without** an accessibility service? | No: `Background activity launch blocked` (BAL) | Plan 7 starts from the "Mirror to <Mac>? Tap to start" notification (as planned). |
| Q1b | With an accessibility service on? | Yes: the activity opens from the background, even while locked. But Android's own dialogs (microphone permission, the capture consent) **do not appear over the lock screen**; they show after unlocking. | Plan 8 auto-Start works only on an unlocked phone. |
| Q1b | Can the service press the consent button? | Yes. The dialog is System UI's `MediaProjectionPermissionActivity`: title "Share your screen with MacDroid?", "Share entire screen" preselected (our entire-screen-only config works), buttons Cancel (`android:id/button1` is **"Share screen"**, `android:id/button2` Cancel). Container id `com.android.systemui:id/screen_share_permission_dialog`. The service only sees it with `isAccessibilityTool="true"` + `flagRetrieveInteractiveWindows` and by walking `windows` (not `rootInActiveWindow`). `performAction(CLICK)` returned true and capture started. | Plan 8 matches the dialog by that container id and presses `android:id/button1`; fallback to text "Share screen"/"Start". |
| Q2 | Is the lock screen / PIN pad visible in the capture? | No frame was ever captured from the lock screen (see Q3). | Unlocking from the Mac is not possible; the phone must be unlocked on the phone. |
| Q3 | Does capture survive the phone locking? | **No.** `MediaProjection.onStop` fired the moment the phone locked (Android ends capture on lock). | Plan 7 Task 8: hold a screen wake lock while mirroring so the phone doesn't auto-lock mid-session; a lock (power button) ends the session with reason `ended` → "Mirroring stopped". |
| Q4 | Playback capture with the phone's media volume at 0? | Works: RMS 1395/1731 with `STREAM_MUSIC` = 0 while a YouTube video played (YouTube is not capture-blocked). | `MUTE_PHONE_SPEAKER = true`: the phone stays quiet while its sound plays on the Mac; volume restored at the end. |

Also seen: up to ~110 fps captured while video played; ~1–3 fps on a still screen.

**What this means for the spec's goal "reach the phone while asleep or locked":** with a PIN/pattern lock, Android 17 shows neither the consent dialog nor the screen while locked, and stops capture on lock. Mirroring therefore starts on an unlocked phone and keeps it awake while it runs. The owner can make "pick up and go" easier with Android's own unlock helpers, which MacDroid doesn't control.
