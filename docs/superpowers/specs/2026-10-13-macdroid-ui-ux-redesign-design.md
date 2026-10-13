# MacDroid — UI/UX Redesign (Round 1.5)

**Date:** 2026-10-13
**Status:** Draft for review
**Builds on:** Plan 1 (protocol & connection core, merged to `main` @ 04a48a4) and the round-1 spec `2026-10-12-macdroid-round1-design.md`.

## 1. Goal

The connection works end to end, but both apps' UI is a bare scaffold. This round redesigns the look, feel and flow of both apps so they feel like first-party platform apps, and lays out the navigation shell that notifications, clipboard and file transfer (Plans 2–4) will slot into.

**Visual direction: native on each platform.**
- Mac: looks and behaves like a first-party macOS app (system materials, SF Symbols, standard controls, `Form`/grouped styles).
- Android: Material 3 with dynamic colour (Material You) from the wallpaper.

**In scope:** layout, navigation, onboarding/pairing flow, status presentation, settings, empty states, error presentation, plus the UI-level follow-ups listed in §5.
**Out of scope:** new features (notifications, clipboard, files), protocol or connection-logic changes. `MacDroidCore` and `android/core` are not modified, except where §5 says so.

## 2. Mac app — Hybrid (menu-bar popover + main window)

### 2.1 App behaviour
- The app keeps running in the menu bar at all times (`MenuBarExtra`, window style).
- A main window (`Window` scene, id `main`) is opened:
  - on first launch;
  - from the popover ("Open MacDroid", ⌘O);
  - **whenever the app is launched again while it is already running** (`applicationShouldHandleReopen` → open the window). This fixes the case where the menu-bar icon is hidden behind the notch.
- Dock presence follows the window:
  - activation policy `.regular` while the main window is open (Dock icon + ⌘-Tab);
  - `.accessory` once it closes (menu-bar only).
- Settings live **inside the main window's sidebar**. ⌘, opens the main window on the Settings → General section, so there is no separate Settings scene.

### 2.2 Menu-bar icon
SF Symbol reflecting state:

| State | Symbol |
|---|---|
| Not paired | `iphone.slash` |
| Paired, disconnected or reconnecting | `iphone` (dimmed) |
| Connected | `iphone.radiowaves.left.and.right` |

### 2.3 Menu-bar popover (~280 pt wide)
- **Header:** device symbol, phone name, and a status line: "Connected · 82% · charging" / "Not connected" / "Not paired".
- **Quick-action grid (2 tiles):** "Send clipboard", "Send files…". They are hidden until their feature round ships; the grid is omitted this round.
- **Not paired:** the header and grid are replaced by a single prominent "Pair a Phone…" button that opens the window on the Phone section.
- **Footer menu rows:** "Open MacDroid ⌘O", "Settings… ⌘,", "Quit MacDroid".

### 2.4 Main window
- `NavigationSplitView`.
- **Sidebar (fixed order):** Phone, Notifications, Clipboard, Files, then a "MacDroid" group with Settings (General, Security, About).
  - Notifications, Clipboard and Files are shown in secondary style and open a `ComingSoonView`: SF Symbol, title "Coming in a later update", one sentence describing the feature.
- Default size 720×480, minimum 600×420. The selected section is remembered with `@SceneStorage`.

**Phone section — not paired (pairing is the hero):**
- Title "Pair your Android phone"; subtitle "Open MacDroid on your phone and scan this code".
- Large QR code (240 pt) generated when the section appears. The pairing window is opened automatically and a fresh code is generated while this view is visible.
- Countdown "Expires in 1:52" plus a "New code" button. On expiry the QR is replaced by a "Code expired" state with a "New code" button.
- "Can't scan?" disclosure showing the address, the grouped code and the fingerprint (`Fingerprint.display`), monospaced and selectable.
- When pairing completes, the view switches to the paired state.

**Phone section — paired:**
- **Header:** device symbol (64 pt), phone name (title), and a status line with a coloured dot:
  - green: Connected over Wi-Fi
  - orange: Reconnecting…
  - grey: Not connected
- **Grouped details:**
  - Battery: "82% · charging", or "—" while disconnected
  - Model
  - Paired date
- **Actions:**
  - "Pair a Different Phone…" goes to the pairing state; the existing pairing stays until the new one completes.
  - "Forget This Phone…" is destructive and asks for confirmation with an alert.

**Settings → General:**
- "Launch at login" toggle. If `SMAppService` registration fails, the toggle reverts to the real state and an inline error is shown.
- "Show MacDroid in the Dock while its window is open" toggle, default on.

**Settings → Security:**
- This Mac's fingerprint (grouped display), with a copy button.
- Explanatory text: "Your phone checks this fingerprint every time it connects."

**Settings → About:** app name, version and build, plus a one-line description.

### 2.5 Errors and warnings
- Errors appear as an inline banner at the top of the relevant section (yellow warning or red error, with an SF Symbol), not as floating red text.
- **Persistent warnings** are a separate state from transient errors and are never cleared by pairing actions:
  - keychain unavailable (temporary identity in use);
  - server stopped.
  These show in the window banner and as a warning row in the popover.
- While the identity is temporary (keychain failure), pairing is disabled with an explanation. This is a Plan 1 final-review item.

## 3. Android app — Setup wizard + bottom navigation

### 3.1 Navigation
- Navigation Compose (`androidx.navigation:navigation-compose`) with two graphs:
  - **Setup** — the wizard, §3.3.
  - **Main** — the bottom bar, §3.4.
- **Start destination:** Main if paired, all required permissions are held and setup is done; otherwise Setup at the first unmet step.
- **Permission revoked later:** returning to the app with a required permission missing routes to just that setup step, with a "Back to MacDroid" exit once it is granted.

### 3.2 Theme
- `MaterialTheme` with `dynamicLightColorScheme`/`dynamicDarkColorScheme` on API 31+, and a static fallback palette on API 29–30.
- Dark mode follows the system.
- Edge-to-edge with correct insets.
- Typography is the Material 3 defaults.

### 3.3 Setup wizard (pairing-centred)
- A linear progress indicator at the top shows the current step out of the total.
- Each permission step is a short interstitial: icon in a tonal circle, title, a one-sentence reason, a primary button, and a secondary text action where it applies.

| Step | Shown when | Primary action | Notes |
|---|---|---|---|
| Welcome | first run only | "Get started" | One line on what MacDroid does; "No account, nothing leaves your Wi-Fi". |
| Local network | API 37+ and not granted | "Allow" | If permanently denied: "Open settings". |
| **Pair with your Mac** | not paired | (camera inline) | Hero step. Camera preview fills the card, with the instruction "On your Mac: MacDroid → Pair a Phone" above it and a text button "Enter code manually" below. Camera permission is requested in place; if denied, a message plus "Open settings", and manual entry stays available. Manual entry opens a bottom sheet (address, port, code), then the fingerprint confirmation dialog. |
| Notifications | API 33+ and not granted | "Allow" | Secondary "Skip" (optional). |
| Run in background | always on first setup | "Open battery settings" | Copy includes the Nothing OS "Unrestricted" hint. Secondary "Done". |

- **Pairing outcomes:**
  - **Working:** full-screen progress state "Pairing with *Mac name*…".
  - **Success:** "Paired with *Mac name*" with a check, then auto-advance after 1 s.
  - **Errors:** shown inline on the pair step (not as a dialog), with the specific message from Plan 1 (expired code → "rescan"; fingerprint mismatch → security wording; unreachable → same-Wi-Fi hint) and a "Try again" button.
- After the last step, go to Main/Home and start the connection service.

### 3.4 Main — bottom navigation (5 tabs)
Home · Notifications · Clipboard · Files · Settings (Material 3 `NavigationBar`, icons + labels).

**Home:**
- **Status card** (large, tonal container, rounded 28 dp): laptop icon, Mac name, status line, and chips "Wi-Fi" and "Battery shared".
  - **Connected:** primary container colour.
  - **Connecting / Waiting:** surface-variant colour, with "Looking for your Mac… retrying in N s" (live countdown) and a "Retry now" button that calls `ConnectionManager.nudge()`.
  - **TrustFailure:** error container colour, title "Pairing problem", text "Your Mac no longer recognises this phone.", button "Pair again". The button forgets the pairing after confirmation and goes to the Setup pair step.
  - **Stopped / Disconnected by user:** "Disconnected" with a "Connect" button that starts the service.
- **Recent activity card:** "Clipboard, files and notifications will show here" (empty state this round).
- **Send FAB:** hidden this round; added by Plans 3/4.

**Notifications / Clipboard / Files:** empty state with a large icon, "Coming in the next update", and one sentence describing the feature.

**Settings** (grouped list, Material 3 list items):
- **Connection:**
  - Paired Mac (name + `Fingerprint.display` short form). Tapping it shows details: address, port, full fingerprint.
  - "Keep connected in background", a switch that starts or stops `ConnectionService`.
- **Setup:**
  - Permissions — "All set ✓", or which permission is missing; tapping it opens the relevant setup step.
  - Battery — the current optimisation status, read via `PowerManager.isIgnoringBatteryOptimizations`; tapping it opens battery settings.
- **About:** version.
- **Forget this Mac:** error-coloured row with a confirmation dialog. It stops the service, clears the pairing and routes to Setup.

### 3.5 Foreground notification
Unchanged behaviour. The title uses the same status text function as Home (one source of truth) and keeps the "Disconnect" action.

## 4. Code structure

### Mac (`mac/MacDroid/`, app target only)
```
MacDroidApp.swift           scenes: MenuBarExtra + Window("main"); reopen handling via NSApplicationDelegateAdaptor
AppDelegate.swift           applicationShouldHandleReopen, activation-policy switching
AppModel.swift              existing state + persistentWarning, transientError, pairing countdown/expiry fix
Navigation/SidebarSection.swift   enum of sections
Views/MenuBarPopover.swift
Views/MainWindow.swift      NavigationSplitView + sidebar
Views/Phone/PhoneView.swift, PairingView.swift, PairedDeviceView.swift
Views/Settings/GeneralSettingsView.swift, SecuritySettingsView.swift, AboutView.swift
Views/Shared/ComingSoonView.swift, BannerView.swift, StatusDot.swift, QRCode.swift
Presentation/StatusPresentation.swift   pure: (pairedDevice, ConnectionStatus) → symbol, title, subtitle, colour
```

### Android (`android/app/src/main/java/dev/macdroid/app/ui/`)
```
theme/Theme.kt                       dynamic colour + fallback
navigation/AppNavHost.kt             setup + main graphs, start-destination logic
setup/SetupStep.kt                   pure: which steps are needed, given permissions/pairing/setupDone
setup/SetupViewModel.kt, SetupScreens.kt, PairStep.kt, ManualPairSheet.kt
main/HomeScreen.kt, HomeViewModel.kt, StatusCard.kt
main/SettingsScreen.kt, SettingsViewModel.kt
main/ComingSoonScreen.kt
presentation/StatusPresentation.kt   pure: ConnectionState (+ now) → title, subtitle, tone, action
```
- `QrScanner.kt` and the existing `PairingViewModel` are reused, folded into `setup/`.
- `StatusText` in `ServiceSupport.kt` delegates to `StatusPresentation` so the notification and Home stay consistent.

**New dependency:** `androidx.navigation:navigation-compose:2.10.2`.

## 5. Plan 1 follow-ups folded into this round (UI-level only)
- Mac: separate persistent warning state (keychain / server stopped), not cleared by pairing.
- Mac: the pairing-expiry sleeper loops until the window is actually closed (no early wake).
- Mac: the launch-at-login toggle reverts on registration failure.
- Mac: pairing disabled while using a temporary identity.
- Android: "Forget this Mac" asks for confirmation.
- Android: camera permission denied shows a Settings link and is re-checked on resume.
- Android: on rotation, error state is preserved (no reset on configuration change).
- Android: manual entry rejects an empty or obviously invalid code before probing.

## 6. Testing
- **Unit tests (pure logic):**
  - Mac `StatusPresentation`.
  - Android `StatusPresentation` (incl. countdown text), `SetupStep` ordering and the missing-permission step, plus the existing `StatusTextTest` updated.
- **Build gates:**
  - `swift test` (core unchanged).
  - `xcodebuild` Debug with no warnings.
  - `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` with no warnings.
- **Screenshot review:** implementers capture each new screen and attach the image paths to their reports, so reviewers can judge the visual result.
  - Android: install via `adb`, drive with `adb shell am start`/deep links where possible, capture with `adb exec-out screencap`.
  - Mac: `screencapture -l <windowid>` of the main window.
  - Screens captured: Mac (popover, Phone unpaired, Phone paired, Settings ×3, Coming soon); Android (each wizard step, Home in each state, Settings, Coming soon), in light and dark mode.
- **Manual checklist (human):** first-run on a freshly cleared app; pair; reconnect states; forget and re-pair; reopen the Mac app while it's running (window appears); Dock icon appears and disappears with the window; dark mode on both.
