# UI/UX redesign follow-ups

Carried forward from the redesign execution ledger. Pick up in Plans 2–4 or before publishing.

## Rulings made during execution
- Ruling 1: Task 2 spec ❌ "@SceneStorage for selected section" — keep selection in AppModel.selectedSection (in-memory) — window requests (popover/⌘,/reopen) must set the section from outside the scene, which SceneStorage can't do — cost if wrong: sidebar resets to Phone on relaunch
- Ruling 2: Task 2 plan-mandated Importants (login-item detection timing, stale windowClosed spinner, no-network spinner without retry, duplicate window on startRepair) — fix all four; the spec requires a usable pairing view and quiet login launches — cost if wrong: one extra fix round
- Ruling 3: a successful pairing sets UiPrefs.keepConnected = true (added to Task 4 in PairingViewModel success branch) — otherwise a user who tapped Disconnect, then forgot and re-paired, would never auto-connect (readyToConnect stays false) — cost if wrong: pairing re-enables background connection even if user had turned it off
- Ruling 4: Task 5 countdown fix may make one additive change in android/core: ConnectionState.Waiting gains `val retryAtNanos: Long = 0` (monotonic System.nanoTime deadline set by ConnectionManager), HomeViewModel computes remaining from it — the Global Constraint "android/core not modified" exists to protect connection behaviour, which this doesn't change; an app-layer timestamp can't be correct (StateFlow conflation, resubscription) — cost if wrong: one small core API addition outside the plan's stated scope
- Ruling 5: Task 5 Permissions row (spec ❌: should name missing permission and open its setup step) — accepted as implemented: a missing local-network permission already routes the whole app to that setup step on resume, so only notifications can be missing while Settings is visible, and notifications aren't a forced setup step, so the system notification settings is the relevant destination — cost if wrong: row wording/destination differs from spec text
- Ruling 6: Final I2 fixed in the app layer (option a: after forget, Mac shows a "Pair a Phone" button instead of auto-opening a code) rather than changing core (403 on untrusted upgrade) — core is frozen this round — cost if wrong: one extra click on the Mac after forgetting

## Must check by hand
- Real login-item launch: with a phone paired, MacDroid should start quietly in the menu bar (no window). If macOS restores the window at login, disable window restoration for the main scene.

## Recommended before Plans 2–4 grow the apps
- Extract the Mac AppModel pairing state machine into a testable type (currently untested in the app target).
- Compare the closed window's code in AppModel's .windowClosed handler (narrow stale-close race).
- Persist SetupViewModel's welcome/battery progress across process death.

## Deferred minors

**Plan 11 (2026-10-16)** fixes: the pairing window closes when the pairing view goes away, ⌘, on the popover's Settings row, setup progress survives process death. **Kept:** the AppModel pairing state-machine extraction (refactor, no defect) and the remaining cosmetic items.
- Task 1: minor (deferred): countdown traps on NaN/inf; phoneStatus missing battery-not-charging / charging-no-battery tests; connected title uses peer.device not paired.name
- Task 2: minor (deferred): watchExpiry tasks not stored/cancelled; pairing window stays open when PairingView disappears; popover Settings row lacks .keyboardShortcut(","); approval message styled red; login toggle stale after approval; Dock toggle may flash; no "dimmed" icon state; AppModel pairing state machine untested (in app target)
- Task 2: minor (deferred): narrow race — handler sees nil currentWindow then beginPairing sets invite B before handler clears (compare closed code instead); "New code" during in-flight open is silently dropped; real login-item launch untested (human checklist)
- Task 3: minor (deferred): fully-qualified StatusPresentation in ServiceSupport
- Task 4: minor (deferred): process death restarts first-run at Welcome (welcomeDone/batteryDone in memory); service start re-fires on rotation (idempotent); SetupViewModel logic untested; Back exits app from setup; plan-mandated spec drifts (Working text generic, in-card not full-screen, no explicit "Back to MacDroid", LocalNetwork always shows Open settings)
- Task 5: minor (deferred): keepConnected switch not refreshed if notification Disconnect fires while Settings stays resumed; ticker runs when not Waiting; openNotificationSettings lacks runCatching; keep-connected on without local network leaves switch on with service self-stopping; no tests for SettingsUi summaries
