# MacDroid Mirroring — Plan 7: See the Phone's Screen (and Hear It) on the Mac

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Mac window shows the phone's screen live (50–80 ms on home Wi-Fi), with the phone's audio, started from the Mac and stopped from either side.

**Architecture:**
- **Start:** the Mac sends `mirror.*` messages on the existing control WebSocket. The phone gets the user's capture consent.
- **Media:** the phone opens a second mutual-TLS WebSocket, `/v1/mirror`, gated by the Mac's session id. It streams 12-byte-header binary frames: H.264 config, H.264 access units and PCM audio.
- **Display:** the Mac decodes H.264 in hardware into an `AVSampleBufferDisplayLayer` and plays PCM through `AVAudioEngine`.
- **Latency:** stale video is dropped to the next key frame on both sides, and a clock-offset ping drives a latency readout.
- **Tested logic:** frames, H.264 packaging, backlog and send policies, audio jitter, the clock offset and the session state machines live in `MacDroidCore` / `android/core`. Capture, encoding, display and windows are thin glue.

**Tech Stack:**
- Mac: Swift 6, Hummingbird WebSocket, CoreMedia/VideoToolbox (`CMVideoFormatDescriptionCreateFromH264ParameterSets`), `AVSampleBufferDisplayLayer`, `AVAudioEngine`, AppKit window.
- Android: Kotlin, `MediaProjection`, `MediaCodec` (Surface input), `VirtualDisplay`, `AudioPlaybackCaptureConfiguration`, OkHttp WebSocket, a foreground service of type `mediaProjection`.

**Spec:** `docs/superpowers/specs/2026-10-14-macdroid-mirroring-control-design.md`. Plan 7 covers §2, §3.1–3.4, §3.6, §4, §5 (mirror messages, frames, trust), §6.1–6.4, §6.6, §7 (Mac Mirroring settings), §8 and §9 for mirroring. Plan 8 (remote control) follows on the same branch.

## Global Constraints

- **Branch:** `feat/mirroring` from `main`. Plan 8 continues on the same branch. Don't merge.
- **Handoff:** every task's commit updates `docs/handoff/`. Task 1 adds a CHANGELOG entry at the top, `## <date> — Plan 7: mirroring (in progress on feat/mirroring)`, and each later task adds a bullet under it.
- **Features** after Task 2: `files, notifications, clipboard, ring, links, media, photos, mirror`, in that order, on both sides. (`control` comes in Plan 8.)
- **Latency targets (spec §2):** screen → Mac 50–80 ms p50, under 150 ms p95; audio under 100 ms and never more than 100 ms from video.
- **Video:**
  - H.264, no B-frames
  - longest side ≤ `maxSize`: 1920 "Sharp" or 1280 "Balanced"; width and height multiples of 16
  - `fps` 60 or 30
  - CBR 8 Mbps at 1920, 4 Mbps at 1280
  - key frame every 2 s and on `mirror.keyframe`
  - repeat the previous frame after 100 ms
- **Audio:** PCM s16le, stereo, 48 kHz, 10 ms chunks (1,920 bytes).
- **Media frames:** 12-byte big-endian header.
  - byte 0: type (1 config, 2 frame, 3 audio)
  - byte 1: flags (bit 0 key frame)
  - bytes 2–3: zero
  - bytes 4–11: capture time in µs on the phone's monotonic clock (`System.nanoTime / 1000`, which is MediaCodec's surface timestamp base)

  A frame over 4 MiB, or of an unknown type, closes the stream.
- **Session gate:** the media WebSocket sends header `X-MacDroid-Session: <sessionId>`. The Mac upgrades only for the paired phone's certificate and only for the current session while it is `starting` or `streaming`.
- **Drop policies:**
  - Phone sender: socket queue over 300 ms of the stream's bitrate (300,000 bytes at 8 Mbps) → drop video until a key frame fits and request one; audio chunks are dropped when the queue holds over 200 ms (200,000 bytes at 8 Mbps). Ruling: the spec's "1 MB" is a full second of lag at 8 Mbps.
  - Mac: more than 3 video frames waiting → drop to the next key frame and send `mirror.keyframe`.
  - Mac audio: start at 40 ms buffered; drop chunks when more than 100 ms is buffered.
- **Timers:**
  - consent hint at 20 s and timeout at 60 s (Mac)
  - ping every 2 s while streaming
  - clock offset = median of the last 5 `((t1 − t0) + (t2 − t3)) / 2` (phone − Mac, µs)
  - latency = average over the last 30 frames
- **Ruling, consent without the accessibility service (Plan 7):**
  - Android blocks activity starts from the background, so on `mirror.start` the phone posts a high-priority notification, "Mirror to <Mac>? Tap to start", and also tries to open the consent screen directly (this works when MacDroid is on screen).
  - Plan 8's accessibility service exempts MacDroid from the background-start block and adds auto-Start (spec §3.1 step 4).
  - Cost if wrong: in Plan 7 alone you take the phone out to tap.
- **Ruling, entire screen only:** on API 34+, the capture intent uses `MediaProjectionConfig.createConfigForDefaultDisplay()`, so Android offers only "Entire screen" (no "single app" choice to press).
- **Ruling, latency measurement:** the spec's on-device item 5 (measured latency) is measured with the built readout in the owner checklist (Task 11), not in Task 1's probe, because the probe has no Mac display path.
- **Copy (Mac):**
  - buttons "Mirror" and "Stop"
  - window states: "Starting…", "Tap Start on your phone to begin mirroring.", "Mirroring was declined on the phone.", "Mirroring didn't start", "Mirroring stopped", "Phone not connected"
  - "Update MacDroid on your phone to use this."
  - "Phone audio is off: allow the microphone permission for MacDroid on your phone"
  - Settings: section "Mirroring"; "Quality" (Sharp / Balanced); "Frame rate" (60 fps / 30 fps); "Phone audio"; "Show latency"
- **Copy (Android):**
  - consent notification "Mirror to <Mac name>?" / "Tap to start"
  - service notification "Mirroring to <Mac name>" with action "Stop"
  - channel names "Mirroring requests" and "Mirroring"
- **Permissions (Android manifest):** `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `RECORD_AUDIO`.
- **Gates:**
  - `swift test`
  - the Mac app build with no warnings
  - `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` with no warnings
  - `./scripts/cross-platform-test.sh`
- **The owner's phone:** Task 1 is done **with the owner present**. Don't automate taps on the owner's phone or grant permissions from adb.

## Review Focus

1. **Session gate:** a media stream with a session id the Mac didn't start (or an old one) must be refused before any frame is read → Task 6 `routeRejectsUnknownSessions`.
2. **Slow network:** when the network is slower than the stream, latency must not grow without bound; both sides drop to a key frame → Task 5 `backlogDropsToTheNextKeyFrame`, Task 7 `senderDropsVideoUntilAKeyFrameWhenTheQueueIsFull`.
3. **Rotation mid-session:** a new size and new SPS/PPS must replace the old format; the window reshapes → Task 4 `aNewConfigReplacesTheFormat`, Task 6 `rotationUpdatesTheSize`.
4. **Consent never given:** the Mac must not sit in "Starting…" forever → Task 6 `consentHintThenTimeout`.
5. **Control connection drops mid-session:** both sides must stop; the Mac must not show a frozen picture as if live → Task 6 `disconnectStopsTheSession`, Task 7 `phoneSessionEndsWhenTheMacGoes`.

---

## File Structure

```
protocol/fixtures/mirror.start.json, mirror.state.json, mirror.state.stopped.json, mirror.stop.json,
  mirror.keyframe.json, mirror.ping.json, mirror.pong.json, mirror-frames.json, h264/sample.h264 (new)    (T2, T3, T4)
protocol/PROTOCOL.md                                                                                     (T2, T3)
scripts/make-h264-fixture.sh (new)                                                                        (T4)
mac/MacDroidCore/Sources/MacDroidCore/
  Protocol/Messages.swift, MessageCodec.swift                                                            (T2)
  Mirror/MirrorFrame.swift, Mirror/H264.swift, Mirror/MirrorTiming.swift (ClockOffset, LatencyMeter,
    MonotonicClock), Mirror/VideoBacklog.swift, Mirror/AudioJitter.swift, Mirror/MirrorSession.swift (new) (T3–T6)
  Server/MacDroidServer.swift (the /v1/mirror upgrade)                                                     (T6)
  MacDroidDevServer/main.swift                                                                            (T10)
mac/MacDroidCore/Tests/MacDroidCoreTests/ProtocolFixtureTests.swift; MirrorCoreTests.swift, H264Tests.swift,
  MirrorSessionTests.swift (new); ServerTests.swift                                                       (T2–T6)
mac/MacDroid/Mirror/MirrorRenderer.swift, MirrorAudioPlayer.swift, MirrorWindowController.swift (new)    (T9)
mac/MacDroid/Views/Mirror/MirrorContentView.swift (new)                                                    (T9)
mac/MacDroid/AppModel.swift, Views/Phone/PairedDeviceView.swift, Views/MenuBarPopover.swift,
  Views/Settings/GeneralSettingsView.swift                                                                (T9)
android/core/src/main/kotlin/dev/macdroid/core/protocol/Messages.kt, MessageCodec.kt                      (T2)
android/core/src/main/kotlin/dev/macdroid/core/mirror/MirrorFrame.kt, MirrorRules.kt (new)                 (T3, T7)
android/core/src/test/kotlin/dev/macdroid/core/mirror/MirrorFrameTest.kt, MirrorRulesTest.kt (new)         (T3, T7)
android/app/src/debug/java/dev/macdroid/app/debug/MirrorProbe*.kt, src/debug/AndroidManifest.xml (new)     (T1)
android/app/src/main/java/dev/macdroid/app/platform/mirror/MirrorBridge.kt, MirrorService.kt,
  MirrorConsentActivity.kt, ScreenEncoder.kt, AudioCapturer.kt, MediaStreamClient.kt (new)                 (T8)
android/app/src/main/AndroidManifest.xml, MacDroidApp.kt                                                   (T8)
android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt; scripts/cross-platform-test.sh        (T10)
docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md (new)                                     (T1, T11)
```

---

### Task 1: On-device probe (with the owner)

**Purpose:** answer spec §9.1 questions 1–4 on the owner's Nothing Phone (3) before the real code commits to them. The probe lives only in the **debug** source set (`src/debug/`), so it never reaches a release build. It is deleted in Task 11.

**Files:**
- Create: `android/app/src/debug/AndroidManifest.xml`
- Create: `android/app/src/debug/java/dev/macdroid/app/debug/MirrorProbeActivity.kt`, `MirrorProbeService.kt`, `MirrorProbeAccessibility.kt`
- Create: `android/app/src/debug/res/xml/mirror_probe_accessibility.xml`
- Create: `docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md` (probe results)

**What the probe does (all output goes to `Log.w("MirrorProbe", …)`, because Nothing OS filters `Log.d`):**
- **Q1 background start and auto-Start:**
  - `adb shell am broadcast -a dev.macdroid.app.debug.PROBE_START -p dev.macdroid.app`, with the app in the background and the screen off and locked, tries `startActivity` for the probe activity from a receiver.
  - Logs whether the activity appeared, once with the probe accessibility service off and once with it on.
  - With the service on, the activity requests capture with `createConfigForDefaultDisplay()`. The service logs the System UI window's node tree (text and view ids) and tries to click a node whose text matches `Start|Start now|Allow` (case-insensitive).
- **Q2 lock screen visible:** the capture service writes a 360 px JPEG every 2 s to `getExternalFilesDir("probe")` for 30 s while the owner locks the phone and wakes it to the PIN pad. Pull them with `adb pull` and look.
- **Q3 capture survives lock:** the service logs `onStop` and frame callbacks per second through the lock.
- **Q4 audio:**
  - The service records 10 s of playback capture and logs the RMS per second while the owner plays music.
  - Then it sets `STREAM_MUSIC` to 0 for 5 s and logs the RMS, to see whether capture survives the phone's speaker being muted.
  - Then it restores the volume.

- [ ] **Step 1: Branch, debug manifest**

```bash
git checkout main && git checkout -b feat/mirroring
```

`android/app/src/debug/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <application>
        <activity
            android:name=".debug.MirrorProbeActivity"
            android:exported="false"
            android:showWhenLocked="true"
            android:turnScreenOn="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
        <service
            android:name=".debug.MirrorProbeService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
        <receiver android:name=".debug.MirrorProbeActivity$Trigger" android:exported="true">
            <intent-filter><action android:name="dev.macdroid.app.debug.PROBE_START" /></intent-filter>
        </receiver>
        <service
            android:name=".debug.MirrorProbeAccessibility"
            android:exported="false"
            android:label="MacDroid probe"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>
            <meta-data android:name="android.accessibilityservice" android:resource="@xml/mirror_probe_accessibility" />
        </service>
    </application>
</manifest>
```

`android/app/src/debug/res/xml/mirror_probe_accessibility.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:notificationTimeout="50" />
```

- [ ] **Step 2: Probe code**

`MirrorProbeActivity.kt`:

```kotlin
package dev.macdroid.app.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/** Debug-only probe for the mirroring spec §9.1 (deleted in Plan 7 Task 11). */
class MirrorProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.w(TAG, "activity shown (from background: ${intent.getBooleanExtra("bg", false)})")
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        } else {
            askCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        askCapture()
    }

    private fun askCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        startActivityForResult(intent, 2)
    }

    @Deprecated("probe")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.w(TAG, "consent result=$resultCode")
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, MirrorProbeService::class.java).putExtra("code", resultCode).putExtra("data", data))
        }
        finish()
    }

    /** `adb shell am broadcast -a dev.macdroid.app.debug.PROBE_START -p dev.macdroid.app` */
    class Trigger : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.w(TAG, "trigger received; starting activity from the background")
            runCatching {
                context.startActivity(Intent(context, MirrorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("bg", true))
            }.onFailure { Log.w(TAG, "startActivity failed: $it") }
        }
    }

    companion object { const val TAG = "MirrorProbe" }
}
```

`MirrorProbeService.kt`:

```kotlin
package dev.macdroid.app.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Debug-only: 30 s of capture → JPEGs every 2 s, frame rate per second, onStop, and 15 s of audio RMS. */
class MirrorProbeService : Service() {
    private val tag = MirrorProbeActivity.TAG
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("probe", "Probe", NotificationManager.IMPORTANCE_LOW))
        startForeground(77, Notification.Builder(this, "probe").setContentTitle("Mirror probe").setSmallIcon(android.R.drawable.ic_menu_camera).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")!!
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("code", 0), data)
        projection = mp
        val thread = HandlerThread("probe").apply { start() }
        val handler = Handler(thread.looper)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.w(tag, "projection onStop") }
        }, handler)
        val metrics = resources.displayMetrics
        val scale = 360f / maxOf(metrics.widthPixels, metrics.heightPixels)
        val w = (metrics.widthPixels * scale).toInt()
        val h = (metrics.heightPixels * scale).toInt()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var frames = 0
        var saved = 0L
        val dir = File(getExternalFilesDir("probe"), "").apply { mkdirs() }
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            frames++
            val now = System.currentTimeMillis()
            if (now - saved >= 2_000) {
                saved = now
                val plane = image.planes[0]
                val bitmap = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                File(dir, "frame-$now.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                Log.w(tag, "saved frame-$now.jpg")
            }
            image.close()
        }, handler)
        mp.createVirtualDisplay("probe", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
        thread {
            repeat(30) {
                Thread.sleep(1_000)
                Log.w(tag, "fps=$frames")
                frames = 0
            }
        }
        thread { probeAudio(mp) }
        handler.postDelayed({ Log.w(tag, "probe done"); mp.stop(); stopSelf() }, 32_000)
        return START_NOT_STICKY
    }

    private fun probeAudio(mp: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val record = runCatching { AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config).build() }
            .getOrElse { Log.w(tag, "audio capture unavailable: $it"); return }
        val audio = getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val buffer = ShortArray(48_000 * 2)
        record.startRecording()
        for (second in 1..15) {
            if (second == 11) audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            val n = record.read(buffer, 0, buffer.size)
            var sum = 0.0
            for (i in 0 until n) sum += buffer[i].toDouble() * buffer[i]
            Log.w(tag, "audio second=$second muted=${second >= 11} rms=${if (n > 0) sqrt(sum / n).toInt() else -1}")
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        record.stop()
        record.release()
    }
}
```

`MirrorProbeAccessibility.kt`:

```kotlin
package dev.macdroid.app.debug

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Debug-only: logs System UI windows and presses a Start/Start now/Allow button (spec §9.1 Q1). */
class MirrorProbeAccessibility : AccessibilityService() {
    private val tag = MirrorProbeActivity.TAG
    private val start = Regex("^(start|start now|allow)$", RegexOption.IGNORE_CASE)

    override fun onServiceConnected() { Log.w(tag, "probe accessibility connected") }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != "com.android.systemui") return
        val root = rootInActiveWindow ?: return
        dump(root, 0)
        find(root)?.let { Log.w(tag, "clicking '${it.text}' id=${it.viewIdResourceName} ok=${it.performAction(AccessibilityNodeInfo.ACTION_CLICK)}") }
    }

    private fun dump(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 12) return
        if (!node.text.isNullOrEmpty() || node.viewIdResourceName != null) Log.w(tag, "${"  ".repeat(depth)}${node.className} '${node.text}' id=${node.viewIdResourceName} click=${node.isClickable}")
        for (i in 0 until node.childCount) node.getChild(i)?.let { dump(it, depth + 1) }
    }

    private fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.text?.toString()?.trim()?.let(start::matches) == true) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child)?.let { return it } }
        return null
    }

    override fun onInterrupt() {}
}
```

Run `cd android && ./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL. The `@Deprecated`/`@Suppress` annotations keep the build warning-free. If the compiler still warns in debug sources, add `@Suppress("DEPRECATION")` at the reported line.

- [ ] **Step 3: Run the probe with the owner**

Install (`ANDROID_SERIAL=<serial> ./gradlew :app:installDebug`), then **with the owner holding the phone**:
1. **Q1a:** owner locks the phone. Run `adb shell am broadcast -a dev.macdroid.app.debug.PROBE_START -p dev.macdroid.app` and watch `adb logcat -s MirrorProbe:W`. Record whether "activity shown" appears (background start without accessibility).
2. **Owner:** Settings → Accessibility → "MacDroid probe" → on.
3. **Q1b:** owner locks the phone; run the broadcast again. Record: activity shown? Screen turned on? The consent dialog's node dump; whether "clicking … ok=true" appeared and capture started without a tap.
4. **Q2/Q3:** during the 30 s capture, the owner locks the phone, waits 5 s, wakes it to the PIN pad and unlocks. Record the fps lines and any `onStop`, then `adb pull /sdcard/Android/data/dev.macdroid.app/files/probe` and look at the JPEGs (lock screen? PIN pad?).
5. **Q4:** the owner plays music while the probe runs. Record the RMS for seconds 1–10 and for the muted seconds 11–15.
6. **Owner:** turns "MacDroid probe" off again.

- [ ] **Step 4: Record results and rulings**

Write `docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md` with a "Probe results (date)" section: one line per question with the observed answer and the decision.
- **Q1a** decides whether the notification fallback is needed in Plan 7. It is planned regardless.
- **Q1b** gives Plan 8 its auto-Start matching rules (the text and ids from the dump).
- **Q2** decides "unlock by clicking the PIN" vs "type the PIN blind".
- **Q3** decides whether a lock ends the session (if `onStop` fired, Task 8 ends with reason `ended` and the copy "Mirroring stopped: the phone locked").
- **Q4** decides whether Task 8 mutes `STREAM_MUSIC` while mirroring with audio. Mute only if the RMS stayed non-zero while muted.

Add the CHANGELOG entry (`## <date> — Plan 7: mirroring (in progress on feat/mirroring)`) with "Task 1: on-device probe; results in the follow-ups file."

```bash
git add android/app/src/debug docs
git commit -m "chore(mirroring): on-device probe and its results

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Protocol — `mirror.*` messages

**Files:**
- Create: `protocol/fixtures/mirror.start.json`, `mirror.state.json`, `mirror.state.stopped.json`, `mirror.stop.json`, `mirror.keyframe.json`, `mirror.ping.json`, `mirror.pong.json`
- Modify: `Messages.swift`, `MessageCodec.swift`, `Messages.kt`, `MessageCodec.kt`, `protocol/PROTOCOL.md`
- Test: `ProtocolFixtureTests.swift`, `MessageCodecTest.kt`

**Interfaces (produces):**
- **Swift:**
  - `Feature.mirror`
  - `MirrorStart(sessionId:maxSize:fps:audio:)`
  - `MirrorPhaseName` (`.starting`, `.streaming`, `.stopped`)
  - `MirrorStopReason` (`.user`, `.mac`, `.denied`, `.consentTimeout`, `.noPermission`, `.ended`, `.error`)
  - `MirrorStateUpdate(sessionId:state:width:height:rotation:reason:audio:control:)`, with every field after `state` optional
  - `MirrorSessionRef(sessionId:)`
  - `MirrorPing(t0:)` and `MirrorPong(t0:t1:t2:)`, all `Int64`
  - `Message` cases `.mirrorStart`, `.mirrorState`, `.mirrorStop`, `.mirrorKeyframe`, `.mirrorPing`, `.mirrorPong`
- **Kotlin:**
  - `Features.MIRROR`
  - `MirrorStartBody(sessionId, maxSize, fps, audio)`
  - `MirrorPhaseName { Starting, Streaming, Stopped }`
  - `MirrorStopReason { User, Mac, Denied, ConsentTimeout, NoPermission, Ended, Error }`
  - `MirrorStateBody(sessionId, state, width: Int? = null, height: Int? = null, rotation: Int? = null, reason: MirrorStopReason? = null, audio: Boolean? = null, control: Boolean? = null)`
  - `MirrorSessionBody(sessionId)`
  - `MirrorPingBody(t0: Long)` and `MirrorPongBody(t0: Long, t1: Long, t2: Long)`
  - `Message.MirrorStart`, `MirrorState`, `MirrorStop`, `MirrorKeyframe`, `MirrorPing`, `MirrorPong`

- [ ] **Step 1: Fixtures** (ids `6f1c2a0e-0000-4000-8000-0000000000NN`, NN = 50–56; session `5e55a0b1-1111-4222-8333-444455556666`)

```json
{ "v": 1, "type": "mirror.start", "id": "6f1c2a0e-0000-4000-8000-000000000050", "body": { "sessionId": "5e55a0b1-1111-4222-8333-444455556666", "maxSize": 1920, "fps": 60, "audio": true } }
```
```json
{ "v": 1, "type": "mirror.state", "id": "6f1c2a0e-0000-4000-8000-000000000051", "body": { "sessionId": "5e55a0b1-1111-4222-8333-444455556666", "state": "streaming", "width": 864, "height": 1920, "rotation": 0, "audio": true, "control": false } }
```
```json
{ "v": 1, "type": "mirror.state", "id": "6f1c2a0e-0000-4000-8000-000000000052", "body": { "sessionId": "5e55a0b1-1111-4222-8333-444455556666", "state": "stopped", "reason": "consentTimeout" } }
```
```json
{ "v": 1, "type": "mirror.stop", "id": "6f1c2a0e-0000-4000-8000-000000000053", "body": { "sessionId": "5e55a0b1-1111-4222-8333-444455556666" } }
```
```json
{ "v": 1, "type": "mirror.keyframe", "id": "6f1c2a0e-0000-4000-8000-000000000054", "body": { "sessionId": "5e55a0b1-1111-4222-8333-444455556666" } }
```
```json
{ "v": 1, "type": "mirror.ping", "id": "6f1c2a0e-0000-4000-8000-000000000055", "body": { "t0": 1000000 } }
```
```json
{ "v": 1, "type": "mirror.pong", "id": "6f1c2a0e-0000-4000-8000-000000000056", "body": { "t0": 1000000, "t1": 9000500, "t2": 9000600 } }
```

- [ ] **Step 2: Failing Swift tests**

- Add the 7 names to `messageFixtures`.
- Replace `supportedFeaturesIncludePhotos` with `supportedFeaturesIncludeMirror`, expecting `[Feature.files, Feature.notifications, Feature.clipboard, Feature.ring, Feature.links, Feature.media, Feature.photos, Feature.mirror]`.
- Add:

```swift
    @Test func mirrorFixturesDecode() throws {
        let s = "5e55a0b1-1111-4222-8333-444455556666"
        #expect(try MessageCodec.decode(Fixtures.text("mirror.start.json")) == .mirrorStart(MirrorStart(sessionId: s, maxSize: 1920, fps: 60, audio: true)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.state.json")) == .mirrorState(MirrorStateUpdate(
            sessionId: s, state: .streaming, width: 864, height: 1920, rotation: 0, audio: true, control: false)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.state.stopped.json")) == .mirrorState(MirrorStateUpdate(sessionId: s, state: .stopped, reason: .consentTimeout)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.stop.json")) == .mirrorStop(MirrorSessionRef(sessionId: s)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.keyframe.json")) == .mirrorKeyframe(MirrorSessionRef(sessionId: s)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.ping.json")) == .mirrorPing(MirrorPing(t0: 1_000_000)))
        #expect(try MessageCodec.decode(Fixtures.text("mirror.pong.json")) == .mirrorPong(MirrorPong(t0: 1_000_000, t1: 9_000_500, t2: 9_000_600)))
    }
```

Run `cd mac/MacDroidCore && swift build --build-tests`. Expected: compile errors.

- [ ] **Step 3: Implement Swift**

In `Messages.swift`:
- Add `Feature.mirror = "mirror"` and append it to `ProtocolInfo.features`.
- Before `public enum Message`:

```swift
// MARK: Mirroring (Plan 7)

public struct MirrorStart: Codable, Equatable, Sendable {
    public var sessionId: String
    public var maxSize: Int
    public var fps: Int
    public var audio: Bool
    public init(sessionId: String, maxSize: Int, fps: Int, audio: Bool) {
        self.sessionId = sessionId
        self.maxSize = maxSize
        self.fps = fps
        self.audio = audio
    }
}

public enum MirrorPhaseName: String, Codable, Sendable { case starting, streaming, stopped }

public enum MirrorStopReason: String, Codable, Sendable { case user, mac, denied, consentTimeout, noPermission, ended, error }

public struct MirrorStateUpdate: Codable, Equatable, Sendable {
    public var sessionId: String
    public var state: MirrorPhaseName
    public var width: Int?
    public var height: Int?
    /// 0, 90, 180 or 270.
    public var rotation: Int?
    public var reason: MirrorStopReason?
    /// Audio is actually being captured.
    public var audio: Bool?
    /// The phone's control service is on (Plan 8).
    public var control: Bool?
    public init(sessionId: String, state: MirrorPhaseName, width: Int? = nil, height: Int? = nil, rotation: Int? = nil,
                reason: MirrorStopReason? = nil, audio: Bool? = nil, control: Bool? = nil) {
        self.sessionId = sessionId
        self.state = state
        self.width = width
        self.height = height
        self.rotation = rotation
        self.reason = reason
        self.audio = audio
        self.control = control
    }
}

public struct MirrorSessionRef: Codable, Equatable, Sendable {
    public var sessionId: String
    public init(sessionId: String) { self.sessionId = sessionId }
}

/// Clock offset probe; all times are monotonic microseconds (spec §6.6).
public struct MirrorPing: Codable, Equatable, Sendable {
    public var t0: Int64
    public init(t0: Int64) { self.t0 = t0 }
}

public struct MirrorPong: Codable, Equatable, Sendable {
    public var t0: Int64
    public var t1: Int64
    public var t2: Int64
    public init(t0: Int64, t1: Int64, t2: Int64) {
        self.t0 = t0
        self.t1 = t1
        self.t2 = t2
    }
}
```

- `Message` cases:

```swift
    case mirrorStart(MirrorStart)
    case mirrorState(MirrorStateUpdate)
    case mirrorStop(MirrorSessionRef)
    case mirrorKeyframe(MirrorSessionRef)
    case mirrorPing(MirrorPing)
    case mirrorPong(MirrorPong)
```

In `MessageCodec.swift`:
- encode: `case .mirrorStart(let body): return try encode(type: "mirror.start", id: id, body: body)`, and the same for `mirror.state`, `mirror.stop`, `mirror.keyframe`, `mirror.ping` and `mirror.pong`.
- decode: `case "mirror.start": return .mirrorStart(try decoder.decode(Incoming<MirrorStart>.self, from: data).body)`, and the same for the other five, using `MirrorStateUpdate`, `MirrorSessionRef`, `MirrorSessionRef`, `MirrorPing` and `MirrorPong`.

Run `swift test`. Expected: PASS.

- [ ] **Step 4: Kotlin, test first**

In `MessageCodecTest.kt`:
- Add the 7 fixture names.
- Rename and extend the features test to `supportedFeaturesIncludeMirror`, expecting `listOf("files", "notifications", "clipboard", "ring", "links", "media", "photos", "mirror")`.
- Add:

```kotlin
    @Test fun mirrorFixturesDecode() {
        val s = "5e55a0b1-1111-4222-8333-444455556666"
        assertEquals(Message.MirrorStart(MirrorStartBody(s, 1920, 60, true)), MessageCodec.decode(Fixtures.text("mirror.start.json")))
        assertEquals(Message.MirrorState(MirrorStateBody(s, MirrorPhaseName.Streaming, width = 864, height = 1920, rotation = 0, audio = true, control = false)),
            MessageCodec.decode(Fixtures.text("mirror.state.json")))
        assertEquals(Message.MirrorState(MirrorStateBody(s, MirrorPhaseName.Stopped, reason = MirrorStopReason.ConsentTimeout)),
            MessageCodec.decode(Fixtures.text("mirror.state.stopped.json")))
        assertEquals(Message.MirrorStop(MirrorSessionBody(s)), MessageCodec.decode(Fixtures.text("mirror.stop.json")))
        assertEquals(Message.MirrorKeyframe(MirrorSessionBody(s)), MessageCodec.decode(Fixtures.text("mirror.keyframe.json")))
        assertEquals(Message.MirrorPing(MirrorPingBody(1_000_000)), MessageCodec.decode(Fixtures.text("mirror.ping.json")))
        assertEquals(Message.MirrorPong(MirrorPongBody(1_000_000, 9_000_500, 9_000_600)), MessageCodec.decode(Fixtures.text("mirror.pong.json")))
    }
```

Run `./gradlew -q :core:compileTestKotlin`. Expected: unresolved references.

Then in `Messages.kt`: add `const val MIRROR = "mirror"`, appended to `SUPPORTED`, and:

```kotlin
// Mirroring (Plan 7)

@Serializable data class MirrorStartBody(val sessionId: String, val maxSize: Int, val fps: Int, val audio: Boolean)

@Serializable enum class MirrorPhaseName { @SerialName("starting") Starting, @SerialName("streaming") Streaming, @SerialName("stopped") Stopped }

@Serializable enum class MirrorStopReason {
    @SerialName("user") User, @SerialName("mac") Mac, @SerialName("denied") Denied, @SerialName("consentTimeout") ConsentTimeout,
    @SerialName("noPermission") NoPermission, @SerialName("ended") Ended, @SerialName("error") Error,
}

@Serializable data class MirrorStateBody(
    val sessionId: String,
    val state: MirrorPhaseName,
    val width: Int? = null,
    val height: Int? = null,
    val rotation: Int? = null,
    val reason: MirrorStopReason? = null,
    val audio: Boolean? = null,
    val control: Boolean? = null,
)

@Serializable data class MirrorSessionBody(val sessionId: String)
@Serializable data class MirrorPingBody(val t0: Long)
@Serializable data class MirrorPongBody(val t0: Long, val t1: Long, val t2: Long)
```

`Message` subclasses:

```kotlin
    data class MirrorStart(val body: MirrorStartBody) : Message
    data class MirrorState(val body: MirrorStateBody) : Message
    data class MirrorStop(val body: MirrorSessionBody) : Message
    data class MirrorKeyframe(val body: MirrorSessionBody) : Message
    data class MirrorPing(val body: MirrorPingBody) : Message
    data class MirrorPong(val body: MirrorPongBody) : Message
```

`MessageCodec.kt`:
- encode: `is Message.MirrorStart -> "mirror.start" to json.encodeToJsonElement(MirrorStartBody.serializer(), message.body)`, and the same for the other five.
- decode: `"mirror.start" -> Message.MirrorStart(json.decodeFromJsonElement(MirrorStartBody.serializer(), body))`, and the same for the other five.

Run `./gradlew :core:test`. Expected: PASS.

- [ ] **Step 5: PROTOCOL.md, builds, handoff, commit**

- PROTOCOL.md: add the 6 rows (spec §5.1) and `mirror` in the features sentence.
- Run the Mac app build and `./gradlew :app:assembleDebug`. Expected: both succeed.
- CHANGELOG bullet: "Task 2: `mirror.*` messages in both codecs, 7 fixtures, `mirror` feature."

```bash
git add protocol mac/MacDroidCore android/core docs/handoff
git commit -m "feat(protocol): mirroring control messages

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Media frame header on both sides

**Files:**
- Create: `protocol/fixtures/mirror-frames.json`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Mirror/MirrorFrame.swift`, `android/core/src/main/kotlin/dev/macdroid/core/mirror/MirrorFrame.kt`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/MirrorCoreTests.swift`, `android/core/src/test/kotlin/dev/macdroid/core/mirror/MirrorFrameTest.kt`

**Interfaces (produces):**
- **Swift:**
  - `MirrorFrameType` (`.config = 1`, `.video = 2`, `.audio = 3`)
  - `MirrorFrame(type:keyFrame:captureMicros:payload:)`
  - `MirrorFrame.headerSize = 12`, `MirrorFrame.maxSize = 4 * 1024 * 1024`
  - `func encoded() -> Data`
  - `static func decode(_ data: Data) throws -> MirrorFrame`, throwing `MirrorFrameError.tooShort`, `.tooLarge` or `.unknownType(UInt8)`
- **Kotlin:**
  - `object MirrorFrame`, with `const val HEADER_SIZE = 12`, `MAX_SIZE = 4 * 1024 * 1024`, `TYPE_CONFIG = 1`, `TYPE_VIDEO = 2`, `TYPE_AUDIO = 3` and `FLAG_KEY = 1`
  - `fun header(type: Int, keyFrame: Boolean, captureMicros: Long): ByteArray`
  - `fun encode(type: Int, keyFrame: Boolean, captureMicros: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray`

- [ ] **Step 1: Shared fixture** `protocol/fixtures/mirror-frames.json`

```json
{
  "frames": [
    { "type": 1, "key": false, "captureMicros": 1000, "payload": "0000000167", "hex": "0100000000000000000003e80000000167" },
    { "type": 2, "key": true, "captureMicros": 123456789012, "payload": "0000000165aa", "hex": "020100000000001cbe991a140000000165aa" },
    { "type": 3, "key": false, "captureMicros": 5, "payload": "0102", "hex": "0300000000000000000000050102" }
  ]
}
```

Before writing the file, compute every `hex` value with a quick Python check, and fix any mismatch in the fixture, not in the code:

```bash
python3 -c "import struct;print((struct.pack('>BBHq',2,1,0,123456789012)+bytes.fromhex('0000000165aa')).hex())"
```

Expected output: `020100000000001cbe991a140000000165aa`. Do the same for the other two (`>BBHq` with `1,0,0,1000` and `3,0,0,5`).

- [ ] **Step 2: Failing tests**

Swift (`MirrorCoreTests.swift`):

```swift
import Foundation
import Testing
@testable import MacDroidCore

private struct FrameFixture: Decodable { let type: UInt8; let key: Bool; let captureMicros: Int64; let payload: String; let hex: String }
private struct FrameFixtures: Decodable { let frames: [FrameFixture] }

extension Data {
    init(hexString: String) {
        self.init()
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            append(UInt8(hexString[index..<next], radix: 16)!)
            index = next
        }
    }
}

@Suite struct MirrorFrameTests {
    @Test func framesMatchTheSharedFixture() throws {
        let fixtures = try JSONDecoder().decode(FrameFixtures.self, from: Data(Fixtures.text("mirror-frames.json").utf8))
        for f in fixtures.frames {
            let frame = MirrorFrame(type: try #require(MirrorFrameType(rawValue: f.type)), keyFrame: f.key, captureMicros: f.captureMicros, payload: Data(hexString: f.payload))
            #expect(frame.encoded() == Data(hexString: f.hex))
            #expect(try MirrorFrame.decode(Data(hexString: f.hex)) == frame)
        }
    }

    @Test func badFramesAreRejected() {
        #expect(throws: MirrorFrameError.tooShort) { try MirrorFrame.decode(Data([1, 0, 0])) }
        #expect(throws: MirrorFrameError.unknownType(9)) { try MirrorFrame.decode(Data([9] + Array(repeating: 0, count: 11))) }
        #expect(throws: MirrorFrameError.tooLarge) { try MirrorFrame.decode(Data(count: MirrorFrame.maxSize + 1)) }
    }
}
```

Kotlin (`MirrorFrameTest.kt`):

```kotlin
package dev.macdroid.core.mirror

import dev.macdroid.core.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorFrameTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test fun framesMatchTheSharedFixture() {
        val frames = Json.parseToJsonElement(Fixtures.text("mirror-frames.json")).jsonObject["frames"]!!.jsonArray
        for (f in frames.map { it.jsonObject }) {
            val payload = hex(f["payload"]!!.jsonPrimitive.content)
            val encoded = MirrorFrame.encode(f["type"]!!.jsonPrimitive.int, f["key"]!!.jsonPrimitive.boolean, f["captureMicros"]!!.jsonPrimitive.long, payload)
            assertEquals(f["hex"]!!.jsonPrimitive.content, encoded.toHex())
        }
    }

    @Test fun encodesASliceOfABuffer() {
        val buffer = byteArrayOf(9, 9, 1, 2, 9)
        assertEquals("0300000000000000000000050102", MirrorFrame.encode(MirrorFrame.TYPE_AUDIO, false, 5, buffer, offset = 2, length = 2).toHex())
    }
}
```

Run both. Expected: compile errors.

- [ ] **Step 3: Implement**

`MirrorFrame.swift`:

```swift
import Foundation

public enum MirrorFrameType: UInt8, Sendable { case config = 1, video = 2, audio = 3 }

public enum MirrorFrameError: Error, Equatable { case tooShort, tooLarge, unknownType(UInt8) }

/// One binary message on the media WebSocket (spec §5.2): a 12-byte big-endian header, then the payload.
public struct MirrorFrame: Equatable, Sendable {
    public static let headerSize = 12
    public static let maxSize = 4 * 1024 * 1024

    public var type: MirrorFrameType
    public var keyFrame: Bool
    /// Phone monotonic clock, µs.
    public var captureMicros: Int64
    public var payload: Data

    public init(type: MirrorFrameType, keyFrame: Bool, captureMicros: Int64, payload: Data) {
        self.type = type
        self.keyFrame = keyFrame
        self.captureMicros = captureMicros
        self.payload = payload
    }

    public func encoded() -> Data {
        var data = Data(capacity: Self.headerSize + payload.count)
        data.append(type.rawValue)
        data.append(keyFrame ? 1 : 0)
        data.append(contentsOf: [0, 0])
        withUnsafeBytes(of: captureMicros.bigEndian) { data.append(contentsOf: $0) }
        data.append(payload)
        return data
    }

    public static func decode(_ data: Data) throws -> MirrorFrame {
        guard data.count <= maxSize else { throw MirrorFrameError.tooLarge }
        guard data.count >= headerSize else { throw MirrorFrameError.tooShort }
        let bytes = [UInt8](data.prefix(headerSize))
        guard let type = MirrorFrameType(rawValue: bytes[0]) else { throw MirrorFrameError.unknownType(bytes[0]) }
        var micros: Int64 = 0
        for byte in bytes[4..<12] { micros = micros << 8 | Int64(byte) }
        return MirrorFrame(type: type, keyFrame: bytes[1] & 1 == 1, captureMicros: micros, payload: Data(data.dropFirst(headerSize)))
    }
}
```

`MirrorFrame.kt`:

```kotlin
package dev.macdroid.core.mirror

import java.nio.ByteBuffer

/** One binary message on the media WebSocket (spec §5.2): a 12-byte big-endian header, then the payload. */
object MirrorFrame {
    const val HEADER_SIZE = 12
    const val MAX_SIZE = 4 * 1024 * 1024
    const val TYPE_CONFIG = 1
    const val TYPE_VIDEO = 2
    const val TYPE_AUDIO = 3
    const val FLAG_KEY = 1

    fun header(type: Int, keyFrame: Boolean, captureMicros: Long): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).put(type.toByte()).put((if (keyFrame) FLAG_KEY else 0).toByte()).putShort(0).putLong(captureMicros).array()

    fun encode(type: Int, keyFrame: Boolean, captureMicros: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        val out = ByteArray(HEADER_SIZE + length)
        header(type, keyFrame, captureMicros).copyInto(out)
        payload.copyInto(out, HEADER_SIZE, offset, offset + length)
        return out
    }
}
```

Run both suites. Expected: PASS.

- [ ] **Step 4: PROTOCOL.md, handoff, commit**

PROTOCOL.md: add a "Mirroring media stream" section with the header table (spec §5.2) and the session header rule (`X-MacDroid-Session`). CHANGELOG bullet: "Task 3: media frame header on both sides with a shared fixture."

```bash
git add protocol mac/MacDroidCore android/core docs/handoff
git commit -m "feat(mirroring): media frame header

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Mac core — H.264 packaging

**Files:**
- Create: `scripts/make-h264-fixture.sh`, `protocol/fixtures/h264/sample.h264`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Mirror/H264.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/H264Tests.swift`

**Interfaces (produces):**
- `enum H264`, with:
  - `static func nalUnits(in data: Data) -> [Data]` (Annex-B → NAL payloads, start codes removed)
  - `static func type(of nal: Data) -> UInt8` (`nal[0] & 0x1F`)
  - `static func parameterSets(in config: Data) -> (sps: Data, pps: Data)?`
  - `static func formatDescription(sps: Data, pps: Data) throws -> CMVideoFormatDescription`
  - `static func dimensions(of format: CMVideoFormatDescription) -> (width: Int, height: Int)`
  - `static func sampleBuffer(accessUnit: Data, format: CMVideoFormatDescription) throws -> CMSampleBuffer` (AVCC length-prefixed, `DisplayImmediately` attached; SPS, PPS, SEI and AUD NALs left out)
  - `static func isKeyFrame(_ accessUnit: Data) -> Bool` (contains NAL type 5)
- `H264Error.badParameterSets(OSStatus)`, `.noSlices` and `.coreMedia(OSStatus)`.

- [ ] **Step 1: Fixture**

`scripts/make-h264-fixture.sh`:

```bash
#!/usr/bin/env bash
# Writes protocol/fixtures/h264/sample.h264: 10 frames, 320x240 then 240x320 (a "rotation"), no B-frames, one slice per frame,
# IDR at frames 0 and 5 of each half. Used by Swift and Kotlin tests and the cross-platform test.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/protocol/fixtures/h264"
mkdir -p "$OUT"
TMP="$(mktemp -d)"
for size in 320x240 240x320; do
  ffmpeg -loglevel error -y -f lavfi -i "testsrc=size=$size:rate=30" -frames:v 10 -pix_fmt yuv420p \
    -c:v libx264 -profile:v high -bf 0 -g 5 -keyint_min 5 -sc_threshold 0 -x264-params "slices=1:aud=0" \
    -f h264 "$TMP/$size.h264"
done
cat "$TMP/320x240.h264" "$TMP/240x320.h264" > "$OUT/sample.h264"
rm -rf "$TMP"
echo "wrote $OUT/sample.h264 ($(wc -c < "$OUT/sample.h264") bytes)"
```

Run `chmod +x scripts/make-h264-fixture.sh && ./scripts/make-h264-fixture.sh`. Expected: "wrote …sample.h264 (N bytes)", N roughly 20–80 KB. If libx264 is missing, run `ffmpeg -encoders | grep 264` and use the listed H.264 encoder (e.g. `h264_videotoolbox` with `-realtime 1 -allow_sw 0 -profile:v high -bf 0`), then record a ruling.

- [ ] **Step 2: Failing tests** (`H264Tests.swift`)

```swift
import CoreMedia
import Foundation
import Testing
@testable import MacDroidCore

/// Groups the fixture's NALs the way the phone sends them: (config = SPS+PPS Annex-B) and one access unit per slice.
func h264FixtureFrames() throws -> [(config: Data?, frame: Data?)] {
    let data = try Data(contentsOf: Fixtures.directory.appending(path: "h264/sample.h264"))
    let start = Data([0, 0, 0, 1])
    var out: [(config: Data?, frame: Data?)] = []
    var params = Data()
    for nal in H264.nalUnits(in: data) {
        switch H264.type(of: nal) {
        case 7, 8: params.append(start + nal)
        case 1, 5:
            if !params.isEmpty {
                out.append((params, nil))
                params = Data()
            }
            out.append((nil, start + nal))
        default: break // SEI and others aren't sent
        }
    }
    return out
}

@Suite struct H264Tests {
    @Test func splitsAnnexBWithThreeAndFourByteStartCodes() {
        let data = Data([0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5])
        #expect(H264.nalUnits(in: data) == [Data([0x67, 1, 2]), Data([0x68, 3]), Data([0x65, 4, 5])])
        #expect(H264.type(of: Data([0x65])) == 5)
        #expect(H264.isKeyFrame(data))
        #expect(!H264.isKeyFrame(Data([0, 0, 1, 0x41, 9])))
    }

    @Test func fixtureDecodesIntoFormatsAndSampleBuffers() throws {
        let frames = try h264FixtureFrames()
        #expect(frames.filter { $0.frame != nil }.count == 20)
        #expect(frames.filter { $0.config != nil }.count == 2)
        var format: CMVideoFormatDescription?
        var keys = 0
        for f in frames {
            if let config = f.config {
                let sets = try #require(H264.parameterSets(in: config))
                format = try H264.formatDescription(sps: sets.sps, pps: sets.pps)
            }
            if let frame = f.frame {
                let buffer = try H264.sampleBuffer(accessUnit: frame, format: try #require(format))
                #expect(CMSampleBufferGetNumSamples(buffer) == 1)
                let attachments = try #require(CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: false) as? [[CFString: Any]])
                #expect(attachments.first?[kCMSampleAttachmentKey_DisplayImmediately] as? Bool == true)
                if H264.isKeyFrame(frame) { keys += 1 }
            }
        }
        #expect(keys == 4)
    }

    // Review Focus #3
    @Test func aNewConfigReplacesTheFormat() throws {
        let configs = try h264FixtureFrames().compactMap(\.config)
        let first = try H264.formatDescription(sps: try #require(H264.parameterSets(in: configs[0])).sps, pps: try #require(H264.parameterSets(in: configs[0])).pps)
        let second = try H264.formatDescription(sps: try #require(H264.parameterSets(in: configs[1])).sps, pps: try #require(H264.parameterSets(in: configs[1])).pps)
        #expect(H264.dimensions(of: first) == (320, 240))
        #expect(H264.dimensions(of: second) == (240, 320))
    }

    @Test func badInputThrows() {
        #expect(throws: (any Error).self) { try H264.formatDescription(sps: Data([0x67]), pps: Data([0x68])) }
        #expect(H264.parameterSets(in: Data([0, 0, 0, 1, 0x67, 1])) == nil)
    }
}
```

`(Int, Int) == (Int, Int)` works with `#expect` through tuple `==`. If the macro rejects it, compare `.width` and `.height` separately.

Run `swift test --filter H264Tests`. Expected: compile errors.

- [ ] **Step 3: Implement** `H264.swift`

```swift
import CoreMedia
import Foundation

public enum H264Error: Error, Equatable { case badParameterSets(OSStatus), noSlices, coreMedia(OSStatus) }

/// Annex-B H.264 from the phone → CoreMedia sample buffers for `AVSampleBufferDisplayLayer` (spec §6.1).
public enum H264 {
    public static func nalUnits(in data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var starts: [(index: Int, length: Int)] = []
        var i = 0
        while i + 2 < bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0 {
                if bytes[i + 2] == 1 {
                    starts.append((i, 3)); i += 3; continue
                }
                if i + 3 < bytes.count, bytes[i + 2] == 0, bytes[i + 3] == 1 {
                    starts.append((i, 4)); i += 4; continue
                }
            }
            i += 1
        }
        var units: [Data] = []
        for (n, start) in starts.enumerated() {
            let from = start.index + start.length
            let to = n + 1 < starts.count ? starts[n + 1].index : bytes.count
            if from < to { units.append(Data(bytes[from..<to])) }
        }
        return units
    }

    public static func type(of nal: Data) -> UInt8 { (nal.first ?? 0) & 0x1F }

    public static func isKeyFrame(_ accessUnit: Data) -> Bool { nalUnits(in: accessUnit).contains { type(of: $0) == 5 } }

    public static func parameterSets(in config: Data) -> (sps: Data, pps: Data)? {
        let units = nalUnits(in: config)
        guard let sps = units.first(where: { type(of: $0) == 7 }), let pps = units.first(where: { type(of: $0) == 8 }) else { return nil }
        return (sps, pps)
    }

    public static func formatDescription(sps: Data, pps: Data) throws -> CMVideoFormatDescription {
        var format: CMFormatDescription?
        let status = sps.withUnsafeBytes { spsBytes in
            pps.withUnsafeBytes { ppsBytes in
                let pointers = [spsBytes.bindMemory(to: UInt8.self).baseAddress!, ppsBytes.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: pointers,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &format)
            }
        }
        guard status == noErr, let format else { throw H264Error.badParameterSets(status) }
        return format
    }

    public static func dimensions(of format: CMVideoFormatDescription) -> (width: Int, height: Int) {
        let d = CMVideoFormatDescriptionGetDimensions(format)
        return (Int(d.width), Int(d.height))
    }

    public static func sampleBuffer(accessUnit: Data, format: CMVideoFormatDescription) throws -> CMSampleBuffer {
        var avcc = Data()
        for nal in nalUnits(in: accessUnit) where [1, 5].contains(type(of: nal)) {
            var length = UInt32(nal.count).bigEndian
            withUnsafeBytes(of: &length) { avcc.append(contentsOf: $0) }
            avcc.append(nal)
        }
        guard !avcc.isEmpty else { throw H264Error.noSlices }
        var block: CMBlockBuffer?
        var status = CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count,
                                                        blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
                                                        dataLength: avcc.count, flags: 0, blockBufferOut: &block)
        guard status == noErr, let block else { throw H264Error.coreMedia(status) }
        status = avcc.withUnsafeBytes { CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count) }
        guard status == noErr else { throw H264Error.coreMedia(status) }
        var sample: CMSampleBuffer?
        var size = avcc.count
        status = CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: 1,
                                           sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1, sampleSizeArray: &size,
                                           sampleBufferOut: &sample)
        guard status == noErr, let sample else { throw H264Error.coreMedia(status) }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }
}
```

Run `swift test --filter H264Tests`. Expected: PASS. If the fixture counts differ (`20` frames, `2` configs, `4` key frames), print them, check the script's flags, regenerate, and fix the fixture or script rather than the expectation, unless the encoder legitimately differs (then rule).

- [ ] **Step 4: Handoff, commit**

CHANGELOG bullet: "Task 4: Mac core `H264` (Annex-B split, SPS/PPS → format, AVCC sample buffers with DisplayImmediately) and the shared `h264/sample.h264` fixture (with a size change)."

```bash
git add scripts/make-h264-fixture.sh protocol/fixtures/h264 mac/MacDroidCore docs/handoff
git commit -m "feat(mac-core): H.264 packaging for the mirror

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 5: Mac core — timing, backlog and audio jitter

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Mirror/MirrorTiming.swift`, `Mirror/VideoBacklog.swift`, `Mirror/AudioJitter.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/MirrorCoreTests.swift` (append)

**Interfaces (produces):**
- `enum MonotonicClock`, with `static func micros() -> Int64` (`DispatchTime.now().uptimeNanoseconds / 1000`).
- `struct ClockOffset`, with:
  - `mutating func add(t0: Int64, t1: Int64, t2: Int64, t3: Int64)`
  - `var micros: Int64?` (phone − Mac: the median of the last 5 samples)
- `final class LatencyMeter: @unchecked Sendable` (lock-protected), with:
  - `setOffset(_ micros: Int64?)`
  - `record(captureMicros: Int64, displayedMicros: Int64)`
  - `var averageMillis: Int?` (the last 30; nil without an offset)
  - `reset()`
- `enum VideoDecision` (`.show`, `.drop`, `.dropAndRequestKeyFrame`).
- `struct VideoBacklog`, with:
  - `static let maxWaiting = 3`
  - `mutating func admit(keyFrame: Bool, waiting: Int) -> VideoDecision`
  - `mutating func resync()` (drop until the next key frame, e.g. after a decoder failure)
- `enum AudioDecision` (`.schedule(startPlayback: Bool)`, `.drop`).
- `struct AudioJitter`, with:
  - `static let targetMillis = 40.0`, `static let maxMillis = 100.0`
  - `private(set) var playing: Bool`
  - `mutating func decide(bufferedMillis: Double, chunkMillis: Double) -> AudioDecision`

- [ ] **Step 1: Failing tests** (append to `MirrorCoreTests.swift`)

```swift
@Suite struct MirrorTimingTests {
    @Test func clockOffsetIsTheMedianOfTheLastFive() {
        var offset = ClockOffset()
        #expect(offset.micros == nil)
        // phone = mac + 5_000; one-way delay 300 µs each way.
        offset.add(t0: 1_000, t1: 6_300, t2: 6_400, t3: 1_700)
        #expect(offset.micros == 5_000)
        offset.add(t0: 2_000, t1: 9_000, t2: 9_000, t3: 2_000) // an outlier: 7_000
        offset.add(t0: 3_000, t1: 8_300, t2: 8_400, t3: 3_700)
        #expect(offset.micros == 5_000)
        for i in 0..<5 { offset.add(t0: Int64(i), t1: Int64(i) + 6_000, t2: Int64(i) + 6_000, t3: Int64(i)) }
        #expect(offset.micros == 6_000) // only the last five count
    }

    @Test func latencyUsesTheOffsetAndAverages() {
        let meter = LatencyMeter()
        meter.record(captureMicros: 10_000, displayedMicros: 20_000)
        #expect(meter.averageMillis == nil) // no offset yet
        meter.setOffset(5_000) // phone = mac + 5 ms
        meter.record(captureMicros: 105_000, displayedMicros: 150_000) // captured at mac 100 ms, shown at 150 → 50 ms
        meter.record(captureMicros: 205_000, displayedMicros: 270_000) // 70 ms
        #expect(meter.averageMillis == 60)
        meter.reset()
        #expect(meter.averageMillis == nil)
    }
}

@Suite struct VideoBacklogTests {
    // Review Focus #2 (Mac side)
    @Test func backlogDropsToTheNextKeyFrame() {
        var backlog = VideoBacklog()
        #expect(backlog.admit(keyFrame: false, waiting: 3) == .show)
        #expect(backlog.admit(keyFrame: false, waiting: 4) == .dropAndRequestKeyFrame)
        #expect(backlog.admit(keyFrame: false, waiting: 0) == .drop) // still waiting for a key frame
        #expect(backlog.admit(keyFrame: true, waiting: 0) == .show)
        #expect(backlog.admit(keyFrame: false, waiting: 1) == .show)
    }

    @Test func aKeyFrameIsShownEvenWithABacklog() {
        var backlog = VideoBacklog()
        #expect(backlog.admit(keyFrame: true, waiting: 10) == .show)
    }

    @Test func resyncWaitsForAKeyFrame() {
        var backlog = VideoBacklog()
        backlog.resync()
        #expect(backlog.admit(keyFrame: false, waiting: 0) == .drop)
        #expect(backlog.admit(keyFrame: true, waiting: 0) == .show)
    }
}

@Suite struct AudioJitterTests {
    @Test func startsOnceFortyMillisecondsAreQueuedAndSkipsAheadOverAHundred() {
        var jitter = AudioJitter()
        #expect(jitter.decide(bufferedMillis: 0, chunkMillis: 10) == .schedule(startPlayback: false))
        #expect(jitter.decide(bufferedMillis: 20, chunkMillis: 10) == .schedule(startPlayback: false))
        #expect(jitter.decide(bufferedMillis: 30, chunkMillis: 10) == .schedule(startPlayback: true))
        #expect(jitter.playing)
        #expect(jitter.decide(bufferedMillis: 50, chunkMillis: 10) == .schedule(startPlayback: false))
        #expect(jitter.decide(bufferedMillis: 101, chunkMillis: 10) == .drop)
    }

    @Test func runningDryBuildsTheCushionAgain() {
        var jitter = AudioJitter()
        _ = jitter.decide(bufferedMillis: 40, chunkMillis: 10)
        #expect(jitter.playing)
        #expect(jitter.decide(bufferedMillis: 0, chunkMillis: 10) == .schedule(startPlayback: false))
        #expect(!jitter.playing)
    }
}
```

Run `swift test --filter 'MirrorTimingTests|VideoBacklogTests|AudioJitterTests'`. Expected: compile errors.

- [ ] **Step 2: Implement**

`MirrorTiming.swift`:

```swift
import Foundation

/// The Mac's monotonic clock for mirroring latency, in µs (spec §6.6).
public enum MonotonicClock {
    public static func micros() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }
}

/// Phone clock − Mac clock, from `mirror.ping`/`pong` round trips: the median of the last 5 (spec §6.6).
public struct ClockOffset: Sendable {
    private var samples: [Int64] = []
    public init() {}

    public mutating func add(t0: Int64, t1: Int64, t2: Int64, t3: Int64) {
        samples.append(((t1 - t0) + (t2 - t3)) / 2)
        if samples.count > 5 { samples.removeFirst(samples.count - 5) }
    }

    public var micros: Int64? {
        guard !samples.isEmpty else { return nil }
        let sorted = samples.sorted()
        return sorted[sorted.count / 2]
    }
}

/// Capture-to-display latency over the last 30 frames. Written from the video queue, read by the UI.
public final class LatencyMeter: @unchecked Sendable {
    private let lock = NSLock()
    private var offset: Int64?
    private var samples: [Int64] = []

    public init() {}

    public func setOffset(_ micros: Int64?) { lock.withLock { offset = micros } }

    public func record(captureMicros: Int64, displayedMicros: Int64) {
        lock.withLock {
            guard let offset else { return }
            samples.append(displayedMicros - (captureMicros - offset))
            if samples.count > 30 { samples.removeFirst(samples.count - 30) }
        }
    }

    public var averageMillis: Int? {
        lock.withLock {
            guard offset != nil, !samples.isEmpty else { return nil }
            return Int(samples.reduce(0, +) / Int64(samples.count) / 1000)
        }
    }

    public func reset() {
        lock.withLock {
            offset = nil
            samples = []
        }
    }
}
```

`VideoBacklog.swift`:

```swift
/// What to do with an arriving video frame (spec §6.2: never queue stale video).
public enum VideoDecision: Equatable, Sendable { case show, drop, dropAndRequestKeyFrame }

/// Frames depend on earlier ones, so the Mac never drops one frame alone: with more than 3 waiting to be shown,
/// it drops everything up to the next key frame and asks the phone for one.
public struct VideoBacklog: Sendable {
    public static let maxWaiting = 3
    private var dropping = false

    public init() {}

    public mutating func admit(keyFrame: Bool, waiting: Int) -> VideoDecision {
        if keyFrame {
            dropping = false
            return .show
        }
        if dropping { return .drop }
        if waiting > Self.maxWaiting {
            dropping = true
            return .dropAndRequestKeyFrame
        }
        return .show
    }

    /// After a decoder failure or a new format: show nothing until the next key frame.
    public mutating func resync() { dropping = true }
}
```

`AudioJitter.swift`:

```swift
public enum AudioDecision: Equatable, Sendable { case schedule(startPlayback: Bool), drop }

/// PCM scheduling (spec §6.4): start once 40 ms are queued, skip chunks while more than 100 ms are queued
/// (sound stays in sync instead of drifting), and rebuild the cushion after running dry.
public struct AudioJitter: Sendable {
    public static let targetMillis = 40.0
    public static let maxMillis = 100.0
    public private(set) var playing = false

    public init() {}

    public mutating func decide(bufferedMillis: Double, chunkMillis: Double) -> AudioDecision {
        if playing && bufferedMillis <= 0 { playing = false }
        if bufferedMillis > Self.maxMillis { return .drop }
        if !playing && bufferedMillis + chunkMillis >= Self.targetMillis {
            playing = true
            return .schedule(startPlayback: true)
        }
        return .schedule(startPlayback: false)
    }
}
```

Run the filter. Expected: PASS.

- [ ] **Step 3: Handoff, commit**

CHANGELOG bullet: "Task 5: Mac core mirroring timing (monotonic clock, clock offset median, latency meter), the video backlog (drop to key frame) and audio jitter (40 ms start, skip over 100 ms)."

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac-core): mirroring timing, video backlog and audio jitter

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Mac core — `MirrorSession` and the `/v1/mirror` route

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Mirror/MirrorSession.swift`
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Server/MacDroidServer.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/MirrorSessionTests.swift` (new), `ServerTests.swift` (harness gains `mirror:`, plus 2 tests)

**Interfaces:**
- **Consumes:** Task 2 messages, Task 3 `MirrorFrame`, Task 5 `ClockOffset` / `LatencyMeter` / `MonotonicClock`.
- **Produces:**
  - `protocol MirrorFrameSink: Sendable`, with `func deliver(_ frame: MirrorFrame)`.
  - `MirrorOptions(maxSize:fps:audio:)` (defaults 1920 / 60 / true), with `static let sharp = 1920`, `static let balanced = 1280`.
  - `MirrorEndReason` (`.user`, `.mac`, `.declined`, `.consentTimeout`, `.noPermission`, `.ended`, `.error`, `.disconnected`).
  - `MirrorPhase` (`.idle`, `.starting(waitingForTap: Bool)`, `.streaming(width:height:rotation:)`, `.stopped(MirrorEndReason)`).
  - `MirrorState`, with `phase`, `sessionId`, `audio`, `control`, `connected`, `phoneFeatures`, `supported` and `isActive`.
  - `actor MirrorSession`, with:
    - init `(now: @escaping @Sendable () -> Int64 = MonotonicClock.micros, makeId:, consentHint: Duration = .seconds(20), consentTimeout: Duration = .seconds(60), pingEvery: Duration = .seconds(2), send:)`
    - `nonisolated let meter: LatencyMeter`
    - `states`, `state`, `attach(to:)`, `setSink(_:)`
    - `start(_ options: MirrorOptions) async -> Bool`, `stop() async`, `requestKeyFrame() async`
    - `accepts(session:) -> Bool`
    - internal: `streamOpened(session:) -> Int?`, `streamClosed(token:)`, `currentSink()`, `handle(_:)`, `connectionChanged(_:)`
  - `MacDroidServer.init(…, transfers:, mirror: MirrorSession? = nil)`.
  - `MacDroidServer.sessionHeader = HTTPField.Name("X-MacDroid-Session")!`.

- [ ] **Step 1: Failing session tests** (`MirrorSessionTests.swift`)

```swift
import Foundation
import NIOConcurrencyHelpers
import Testing
@testable import MacDroidCore

@Suite struct MirrorSessionTests {
    final class Harness: Sendable {
        let session: MirrorSession
        let sent: Collector<Message>

        init(hint: Duration = .seconds(20), timeout: Duration = .seconds(60), ping: Duration = .seconds(2)) {
            let sent = Collector<Message>()
            let counter = NIOLockedValueBox(0)
            session = MirrorSession(
                now: { 2_000 },
                makeId: { counter.withLockedValue { $0 += 1; return "s\($0)" } },
                consentHint: hint, consentTimeout: timeout, pingEvery: ping,
                send: { await sent.append($0) }
            )
            self.sent = sent
        }

        func connect(features: [String] = ProtocolInfo.features) async {
            await session.connectionChanged(.connected(PeerInfo(device: "Phone", app: "0.7.0", features: features)))
        }

        func streaming(_ id: String = "s1", width: Int = 864, height: Int = 1920, rotation: Int = 0) async {
            await session.handle(.mirrorState(MirrorStateUpdate(sessionId: id, state: .streaming, width: width, height: height, rotation: rotation, audio: true, control: false)))
        }
    }

    @Test func startSendsTheRequestAndGatesTheStream() async {
        let h = Harness()
        await h.connect()
        #expect(await h.session.start(MirrorOptions()))
        #expect(await h.sent.items == [.mirrorStart(MirrorStart(sessionId: "s1", maxSize: 1920, fps: 60, audio: true))])
        #expect(await h.session.state.phase == .starting(waitingForTap: false))
        #expect(await h.session.accepts(session: "s1"))
        #expect(await !h.session.accepts(session: "s0"))
        let token = await h.session.streamOpened(session: "s1")
        #expect(token != nil)
        #expect(await h.session.streamOpened(session: "nope") == nil)
    }

    @Test func aPhoneWithoutMirrorIsUnsupported() async {
        let h = Harness()
        await h.connect(features: ["files"])
        #expect(await !h.session.start(MirrorOptions()))
        #expect(await h.sent.items.isEmpty)
    }

    // Review Focus #4
    @Test func consentHintThenTimeout() async throws {
        let h = Harness(hint: .milliseconds(50), timeout: .milliseconds(150))
        await h.connect()
        _ = await h.session.start(MirrorOptions(maxSize: 1280, fps: 30, audio: false))
        try await eventually { await h.session.state.phase == .starting(waitingForTap: true) }
        try await eventually { await h.session.state.phase == .stopped(.consentTimeout) }
        #expect(await h.sent.items.last == .mirrorStop(MirrorSessionRef(sessionId: "s1")))
        #expect(await !h.session.accepts(session: "s1"))
    }

    @Test func streamingCancelsTheTimeoutAndPings() async throws {
        let h = Harness(hint: .milliseconds(50), timeout: .milliseconds(150), ping: .milliseconds(40))
        await h.connect()
        _ = await h.session.start(MirrorOptions())
        await h.streaming()
        #expect(await h.session.state.phase == .streaming(width: 864, height: 1920, rotation: 0))
        #expect(await h.session.state.audio == true)
        try await eventually { await h.sent.items.contains(.mirrorPing(MirrorPing(t0: 2_000))) }
        try await Task.sleep(for: .milliseconds(200))
        #expect(await h.session.state.phase == .streaming(width: 864, height: 1920, rotation: 0)) // no timeout once streaming
        // phone = mac + 5_000: t1 = 1_000 + 300 + 5_000, t2 = t1 + 100, t3 (now) = 2_000 → ((6_300 − 1_000) + (6_400 − 2_000)) / 2
        await h.session.handle(.mirrorPong(MirrorPong(t0: 1_000, t1: 6_300, t2: 6_400)))
        h.session.meter.record(captureMicros: 105_000, displayedMicros: 150_000)
        #expect(h.session.meter.averageMillis == 50 || h.session.meter.averageMillis == 49) // offset 4_850 µs
    }

    // Review Focus #3
    @Test func rotationUpdatesTheSize() async {
        let h = Harness()
        await h.connect()
        _ = await h.session.start(MirrorOptions())
        await h.streaming()
        await h.streaming(width: 1920, height: 864, rotation: 90)
        #expect(await h.session.state.phase == .streaming(width: 1920, height: 864, rotation: 90))
    }

    @Test func stopsCarryTheirReason() async {
        let h = Harness()
        await h.connect()
        _ = await h.session.start(MirrorOptions())
        await h.session.handle(.mirrorState(MirrorStateUpdate(sessionId: "s1", state: .stopped, reason: .denied)))
        #expect(await h.session.state.phase == .stopped(.declined))
        _ = await h.session.start(MirrorOptions())
        await h.streaming("s2")
        await h.session.handle(.mirrorStop(MirrorSessionRef(sessionId: "s2")))
        #expect(await h.session.state.phase == .stopped(.user))
        _ = await h.session.start(MirrorOptions())
        await h.session.stop()
        #expect(await h.session.state.phase == .stopped(.mac))
        #expect(await h.sent.items.last == .mirrorStop(MirrorSessionRef(sessionId: "s3")))
    }

    @Test func messagesForAnOldSessionAreIgnored() async {
        let h = Harness()
        await h.connect()
        _ = await h.session.start(MirrorOptions())
        _ = await h.session.start(MirrorOptions()) // replaces s1 (which is stopped first)
        #expect(await h.sent.items.contains(.mirrorStop(MirrorSessionRef(sessionId: "s1"))))
        await h.streaming("s1")
        #expect(await h.session.state.phase == .starting(waitingForTap: false))
        #expect(await h.session.state.sessionId == "s2")
    }

    // Review Focus #5
    @Test func disconnectStopsTheSession() async {
        let h = Harness()
        await h.connect()
        _ = await h.session.start(MirrorOptions())
        await h.streaming()
        await h.session.connectionChanged(.disconnected)
        #expect(await h.session.state.phase == .stopped(.disconnected))
        #expect(await !h.session.accepts(session: "s1"))
        #expect(await !h.session.state.supported)
    }
}
```

Run `swift test --filter MirrorSessionTests`. Expected: compile errors.

- [ ] **Step 2: Implement** `MirrorSession.swift`

```swift
import Foundation

/// Where media frames from `/v1/mirror` go (the app's renderer). Called off the main thread, without awaiting.
public protocol MirrorFrameSink: Sendable {
    func deliver(_ frame: MirrorFrame)
}

public struct MirrorOptions: Equatable, Sendable {
    public static let sharp = 1920
    public static let balanced = 1280
    public var maxSize: Int
    public var fps: Int
    public var audio: Bool
    public init(maxSize: Int = MirrorOptions.sharp, fps: Int = 60, audio: Bool = true) {
        self.maxSize = maxSize
        self.fps = fps
        self.audio = audio
    }
}

public enum MirrorEndReason: Equatable, Sendable { case user, mac, declined, consentTimeout, noPermission, ended, error, disconnected }

public enum MirrorPhase: Equatable, Sendable {
    case idle
    case starting(waitingForTap: Bool)
    case streaming(width: Int, height: Int, rotation: Int)
    case stopped(MirrorEndReason)
}

public struct MirrorState: Equatable, Sendable {
    public var phase: MirrorPhase = .idle
    public var sessionId: String?
    /// The phone is actually capturing audio.
    public var audio: Bool?
    /// The phone's control service is on (Plan 8).
    public var control: Bool?
    public var connected = false
    public var phoneFeatures: Set<String> = []

    public var supported: Bool { connected && phoneFeatures.contains(Feature.mirror) }
    public var isActive: Bool {
        switch phase {
        case .starting, .streaming: true
        default: false
        }
    }
    public init() {}
}

/// One mirroring session at a time (spec §6.1): start/stop over the control WebSocket, the consent hint and timeout,
/// the `/v1/mirror` gate, and the ping/pong clock offset for the latency readout.
public actor MirrorSession {
    public nonisolated var states: AsyncStream<MirrorState> { broadcast.stream() }
    public nonisolated let meter = LatencyMeter()
    public private(set) var state = MirrorState()

    private let broadcast = Broadcast<MirrorState>(replayLatest: true, initial: MirrorState())
    private let now: @Sendable () -> Int64
    private let makeId: @Sendable () -> String
    private let consentHint: Duration
    private let consentTimeout: Duration
    private let pingEvery: Duration
    private let send: @Sendable (Message) async throws -> Void
    private var sink: (any MirrorFrameSink)?
    private var timers: [Task<Void, Never>] = []
    private var tasks: [Task<Void, Never>] = []
    private var clock = ClockOffset()
    private var nextToken = 0
    private var openToken: Int?

    public init(
        now: @escaping @Sendable () -> Int64 = MonotonicClock.micros,
        makeId: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() },
        consentHint: Duration = .seconds(20),
        consentTimeout: Duration = .seconds(60),
        pingEvery: Duration = .seconds(2),
        send: @escaping @Sendable (Message) async throws -> Void
    ) {
        self.now = now
        self.makeId = makeId
        self.consentHint = consentHint
        self.consentTimeout = consentTimeout
        self.pingEvery = pingEvery
        self.send = send
    }

    public func attach(to hub: ConnectionHub) {
        guard tasks.isEmpty else { return }
        let messages = hub.messages
        let statuses = hub.statusUpdates
        tasks.append(Task { [weak self] in
            for await message in messages { await self?.handle(message) }
        })
        tasks.append(Task { [weak self] in
            for await status in statuses { await self?.connectionChanged(status) }
        })
    }

    public func setSink(_ sink: (any MirrorFrameSink)?) { self.sink = sink }
    func currentSink() -> (any MirrorFrameSink)? { sink }

    // MARK: Control

    public func start(_ options: MirrorOptions) async -> Bool {
        guard state.supported else { return false }
        if state.isActive { await stop() }
        let id = makeId()
        clock = ClockOffset()
        meter.reset()
        update {
            $0.sessionId = id
            $0.phase = .starting(waitingForTap: false)
            $0.audio = nil
            $0.control = nil
        }
        do {
            try await send(.mirrorStart(MirrorStart(sessionId: id, maxSize: options.maxSize, fps: options.fps, audio: options.audio)))
        } catch {
            finish(.disconnected)
            return false
        }
        let hint = consentHint, timeout = consentTimeout
        timers.append(Task { [weak self] in
            try? await Task.sleep(for: hint)
            await self?.consentHintDue(id)
        })
        timers.append(Task { [weak self] in
            try? await Task.sleep(for: timeout)
            await self?.consentTimedOut(id)
        })
        return true
    }

    public func stop() async {
        guard let id = state.sessionId, state.isActive else { return }
        finish(.mac)
        try? await send(.mirrorStop(MirrorSessionRef(sessionId: id)))
    }

    public func requestKeyFrame() async {
        guard let id = state.sessionId, case .streaming = state.phase else { return }
        try? await send(.mirrorKeyframe(MirrorSessionRef(sessionId: id)))
    }

    // MARK: Media stream gate (spec §5.3)

    public func accepts(session: String) -> Bool { state.isActive && session == state.sessionId }

    func streamOpened(session: String) -> Int? {
        guard accepts(session: session) else { return nil }
        nextToken += 1
        openToken = nextToken
        return nextToken
    }

    func streamClosed(token: Int) {
        if openToken == token { openToken = nil }
    }

    // MARK: Incoming

    func handle(_ message: Message) async {
        switch message {
        case .mirrorState(let body):
            guard body.sessionId == state.sessionId, state.isActive else { return }
            switch body.state {
            case .starting:
                break
            case .streaming:
                let first: Bool
                if case .streaming = state.phase { first = false } else { first = true }
                update {
                    $0.phase = .streaming(width: body.width ?? 0, height: body.height ?? 0, rotation: body.rotation ?? 0)
                    $0.audio = body.audio
                    $0.control = body.control
                }
                if first {
                    cancelTimers()
                    startPinging(body.sessionId)
                }
            case .stopped:
                finish(Self.reason(body.reason))
            }
        case .mirrorStop(let body):
            if body.sessionId == state.sessionId, state.isActive { finish(.user) }
        case .mirrorPong(let body):
            clock.add(t0: body.t0, t1: body.t1, t2: body.t2, t3: now())
            meter.setOffset(clock.micros)
        default:
            break
        }
    }

    func connectionChanged(_ status: ConnectionStatus) {
        switch status {
        case .connected(let peer):
            update {
                $0.connected = true
                $0.phoneFeatures = Set(peer.features)
            }
        case .disconnected:
            if state.isActive { finish(.disconnected) }
            update {
                $0.connected = false
                $0.phoneFeatures = []
            }
        }
    }

    // MARK: Helpers

    private func consentHintDue(_ id: String) {
        guard state.sessionId == id, case .starting = state.phase else { return }
        update { $0.phase = .starting(waitingForTap: true) }
    }

    private func consentTimedOut(_ id: String) async {
        guard state.sessionId == id, case .starting = state.phase else { return }
        finish(.consentTimeout)
        try? await send(.mirrorStop(MirrorSessionRef(sessionId: id)))
    }

    private func startPinging(_ id: String) {
        let every = pingEvery
        timers.append(Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: every)
                guard let self, await self.isStreaming(id) else { return }
                await self.sendPing()
            }
        })
    }

    private func isStreaming(_ id: String) -> Bool {
        guard state.sessionId == id, case .streaming = state.phase else { return false }
        return true
    }

    private func sendPing() async {
        try? await send(.mirrorPing(MirrorPing(t0: now())))
    }

    private func finish(_ reason: MirrorEndReason) {
        cancelTimers()
        openToken = nil
        update { $0.phase = .stopped(reason) }
    }

    private func cancelTimers() {
        for timer in timers { timer.cancel() }
        timers = []
    }

    static func reason(_ reason: MirrorStopReason?) -> MirrorEndReason {
        switch reason {
        case .user: .user
        case .mac: .mac
        case .denied: .declined
        case .consentTimeout: .consentTimeout
        case .noPermission: .noPermission
        case .error: .error
        case .ended, nil: .ended
        }
    }

    private func update(_ change: (inout MirrorState) -> Void) {
        var next = state
        change(&next)
        guard next != state else { return }
        state = next
        broadcast.yield(state)
    }
}
```

`stop()` calls `finish` before awaiting `send`, so a reentrant `start` can't see the old session as active. The test `stopsCarryTheirReason` expects the last sent message to be the stop.

Run `swift test --filter MirrorSessionTests`. Expected: PASS. In `streamingCancelsTheTimeoutAndPings`, the offset maths gives `((6300 − 1000) + (6400 − 2000)) / 2 = 4850`, so latency = `150_000 − (105_000 − 4_850)` = 49,850 µs → `49`. If the exact value differs, keep the assertion `== 49` once you've confirmed the maths, and drop the `|| 50`.

- [ ] **Step 3: Failing route tests** (`ServerTests.swift`)

Extend `ServerHarness.run` with a `mirror: MirrorSession? = nil` parameter and pass it to `MacDroidServer(…, transfers: transfers, mirror: mirror)`. Add a sink and two tests:

```swift
final class CollectingSink: MirrorFrameSink {
    let frames = NIOLockedValueBox<[MirrorFrame]>([])
    func deliver(_ frame: MirrorFrame) { frames.withLockedValue { $0.append(frame) } }
}

@Suite struct MirrorRouteTests {
    func activeSession() async -> MirrorSession {
        let session = MirrorSession(makeId: { "s1" }, send: { _ in })
        await session.connectionChanged(.connected(PeerInfo(device: "Phone", app: "0.7.0", features: ProtocolInfo.features)))
        _ = await session.start(MirrorOptions())
        return session
    }

    func connect(_ phone: Identity, port: Int, session: String, _ body: @escaping @Sendable (WebSocketOutboundWriter) async throws -> Void) async throws {
        var headers = HTTPFields()
        headers[MacDroidServer.sessionHeader] = session
        try await WebSocketClient.connect(
            url: "wss://localhost:\(port)/v1/mirror",
            configuration: .init(maxFrameSize: MirrorFrame.maxSize, additionalHeaders: headers),
            tlsConfiguration: try phone.clientTLS(),
            logger: Logger(label: "test")
        ) { _, outbound, _ in try await body(outbound) }
    }

    // Review Focus #1
    @Test func routeRejectsUnknownSessions() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let mirror = await activeSession()
        try await ServerHarness.run(pairedPhone: phone, mirror: mirror) { harness in
            await #expect(throws: (any Error).self) {
                try await connect(phone, port: harness.port, session: "s0") { _ in Issue.record("a wrong session must not upgrade") }
            }
            await mirror.stop()
            await #expect(throws: (any Error).self) {
                try await connect(phone, port: harness.port, session: "s1") { _ in Issue.record("a stopped session must not upgrade") }
            }
        }
    }

    @Test func routeDeliversFramesToTheSink() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let mirror = await activeSession()
        let sink = CollectingSink()
        await mirror.setSink(sink)
        try await ServerHarness.run(pairedPhone: phone, mirror: mirror) { harness in
            try await connect(phone, port: harness.port, session: "s1") { outbound in
                for frame in [MirrorFrame(type: .config, keyFrame: false, captureMicros: 1, payload: Data([0, 0, 0, 1, 0x67])),
                              MirrorFrame(type: .video, keyFrame: true, captureMicros: 2, payload: Data(count: 300_000))] {
                    try await outbound.write(.binary(ByteBuffer(bytes: frame.encoded())))
                }
                try await eventually { sink.frames.withLockedValue { $0.count } == 2 }
                try await outbound.close(.normalClosure, reason: nil)
            }
        }
        #expect(sink.frames.withLockedValue { $0.map(\.type) } == [.config, .video])
        #expect(sink.frames.withLockedValue { $0.last?.payload.count } == 300_000)
    }
}
```

Add `import HTTPTypes` and `import NIOConcurrencyHelpers` to `ServerTests.swift` if missing. Run `swift test --filter MirrorRouteTests`. Expected: compile errors (`mirror:`, `sessionHeader`).

- [ ] **Step 4: Implement the route** in `MacDroidServer.swift`

- Add `private let mirror: MirrorSession?` and the init parameter `mirror: MirrorSession? = nil` (after `transfers`).
- Add `public static let sessionHeader = HTTPField.Name("X-MacDroid-Session")!`.
- The WebSocket configuration's `maxFrameSize` becomes `MirrorFrame.maxSize`. Control messages stay limited to `maxMessageSize` by `messages(maxSize:)`.
- Replace the upgrade closure body:

```swift
                ) { request, channel, _ in
                    guard let certificate = try await channel.nioSSL_peerCertificate().get() else { return .dontUpgrade }
                    let fingerprint = try Fingerprint.of(certificate)
                    guard await pairing.isTrusted(fingerprint: fingerprint) else { return .dontUpgrade }
                    switch request.path {
                    case "/v1/ws":
                        return .upgrade([:]) { inbound, outbound, _ in
                            let (stream, continuation) = AsyncThrowingStream.makeStream(of: String.self)
                            let session = Task {
                                await hub.run(transport: WebSocketTransport(outbound: outbound), incoming: stream, peerFingerprint: fingerprint)
                            }
                            do {
                                for try await message in inbound.messages(maxSize: Self.maxMessageSize) {
                                    if case .text(let text) = message { continuation.yield(text) }
                                }
                                continuation.finish()
                            } catch {
                                continuation.finish(throwing: error)
                            }
                            await session.value
                        }
                    case "/v1/mirror":
                        // Only the session the Mac started, while it is starting or streaming (spec §5.3).
                        guard let mirror, let session = request.headers[Self.sessionHeader], await mirror.accepts(session: session) else {
                            return .dontUpgrade
                        }
                        return .upgrade([:]) { inbound, _, _ in
                            await Self.receiveMirror(inbound.messages(maxSize: MirrorFrame.maxSize), session: session, mirror: mirror)
                        }
                    default:
                        return .dontUpgrade
                    }
                },
```

with `let mirror = self.mirror` captured next to `pairing` and `hub`, and:

```swift
    /// Media frames go straight to the sink without awaiting the UI; an invalid frame ends the stream (spec §5.2).
    static func receiveMirror(_ messages: WebSocketInboundMessageStream, session: String, mirror: MirrorSession) async {
        guard let token = await mirror.streamOpened(session: session) else { return }
        let sink = await mirror.currentSink()
        do {
            for try await message in messages {
                guard case .binary(let buffer) = message else { continue }
                sink?.deliver(try MirrorFrame.decode(Data(buffer.readableBytesView)))
            }
        } catch {
            // closed, or a bad frame: the phone reconnects once or reports the error (spec §8)
        }
        await mirror.streamClosed(token: token)
    }
```

Run `swift test`. Expected: all pass. Run the Mac app build. Expected: no warnings. The app doesn't pass `mirror:` yet (Task 9).

- [ ] **Step 5: Handoff, commit**

CHANGELOG bullet: "Task 6: Mac core `MirrorSession` (start/stop, consent hint 20 s and timeout 60 s, ping/pong offset, reasons, disconnect) and the `/v1/mirror` route gated by `X-MacDroid-Session`; the WebSocket max frame is 4 MiB with control messages still limited to 1 MiB."

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac-core): mirror session and the media route

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Android core — size, send policy and the phone session

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/mirror/MirrorRules.kt`
- Test: `android/core/src/test/kotlin/dev/macdroid/core/mirror/MirrorRulesTest.kt`

**Interfaces:**
- **Consumes:** Task 2 messages.
- **Produces:**
  - `object MirrorSize`, with `fun fit(width: Int, height: Int, maxSize: Int): Pair<Int, Int>` (longest side ≤ maxSize, both multiples of 16, ≥ 16) and `fun bitrate(maxSize: Int): Int` (8_000_000 at ≥ 1920, otherwise 4_000_000).
  - `enum class VideoAdmit { Send, Drop, DropAndRequestKey }`.
  - `class SendPolicy(bitrateBps: Int)`, with:
    - `val videoLimitBytes: Long` (300 ms of the bitrate)
    - `val audioLimitBytes: Long` (200 ms)
    - `fun video(keyFrame: Boolean, queuedBytes: Long): VideoAdmit`
    - `fun audio(queuedBytes: Long): Boolean`
  - `class MirrorPhoneSession(send: (Message) -> Boolean, clockMs: () -> Long, onEnd: (MirrorStopReason) -> Unit)`, with:
    - `val current: String?`, `val options: MirrorStartBody?`, `val isStreaming: Boolean`
    - `fun start(body: MirrorStartBody): Boolean` (true → ask for consent)
    - `fun consentDenied()`
    - `fun streaming(width: Int, height: Int, rotation: Int, audio: Boolean, control: Boolean)`
    - `fun ended(reason: MirrorStopReason)` (the capture side ended it: sends `stopped`, no callback)
    - `fun macStopped(sessionId: String)`
    - `fun checkTimeout()` (60 s without consent → `ConsentTimeout`)
    - `fun connectionLost()`

- [ ] **Step 1: Failing tests** (`MirrorRulesTest.kt`)

```kotlin
package dev.macdroid.core.mirror

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.MirrorPhaseName
import dev.macdroid.core.protocol.MirrorStartBody
import dev.macdroid.core.protocol.MirrorStateBody
import dev.macdroid.core.protocol.MirrorStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorRulesTest {
    @Test fun sizesFitAndAreMultiplesOfSixteen() {
        assertEquals(864 to 1920, MirrorSize.fit(1080, 2400, 1920))
        assertEquals(576 to 1280, MirrorSize.fit(1080, 2400, 1280))
        assertEquals(1920 to 864, MirrorSize.fit(2400, 1080, 1920))
        assertEquals(720 to 1280, MirrorSize.fit(720, 1280, 1920)) // never upscaled
        assertEquals(8_000_000, MirrorSize.bitrate(1920))
        assertEquals(4_000_000, MirrorSize.bitrate(1280))
    }

    // Review Focus #2 (phone side)
    @Test fun senderDropsVideoUntilAKeyFrameWhenTheQueueIsFull() {
        val policy = SendPolicy(8_000_000)
        assertEquals(300_000L, policy.videoLimitBytes)
        assertEquals(200_000L, policy.audioLimitBytes)
        assertEquals(VideoAdmit.Send, policy.video(keyFrame = false, queuedBytes = 300_000))
        assertEquals(VideoAdmit.DropAndRequestKey, policy.video(keyFrame = false, queuedBytes = 300_001))
        assertEquals(VideoAdmit.Drop, policy.video(keyFrame = false, queuedBytes = 0))
        assertEquals(VideoAdmit.Drop, policy.video(keyFrame = true, queuedBytes = 400_000)) // still backed up
        assertEquals(VideoAdmit.Send, policy.video(keyFrame = true, queuedBytes = 10))
        assertEquals(VideoAdmit.Send, policy.video(keyFrame = false, queuedBytes = 10))
        assertTrue(policy.audio(200_000))
        assertFalse(policy.audio(200_001))
    }

    private val sent = mutableListOf<Message>()
    private val ends = mutableListOf<MirrorStopReason>()
    private var now = 0L
    private fun session() = MirrorPhoneSession({ sent += it; true }, { now }, { ends += it })
    private val start = MirrorStartBody("s1", 1920, 60, true)

    @Test fun aSessionStartsStreamsAndEnds() {
        val s = session()
        assertTrue(s.start(start))
        assertEquals(Message.MirrorState(MirrorStateBody("s1", MirrorPhaseName.Starting)), sent.last())
        s.streaming(864, 1920, 0, audio = true, control = false)
        assertTrue(s.isStreaming)
        assertEquals(Message.MirrorState(MirrorStateBody("s1", MirrorPhaseName.Streaming, 864, 1920, 0, audio = true, control = false)), sent.last())
        s.ended(MirrorStopReason.User)
        assertEquals(Message.MirrorState(MirrorStateBody("s1", MirrorPhaseName.Stopped, reason = MirrorStopReason.User)), sent.last())
        assertNull(s.current)
        assertTrue(ends.isEmpty()) // the capture side ended it itself
    }

    @Test fun anewStartReplacesTheOldSession() {
        val s = session()
        s.start(start)
        s.streaming(864, 1920, 0, audio = true, control = false)
        assertTrue(s.start(start.copy(sessionId = "s2")))
        assertEquals(listOf(MirrorStopReason.Mac), ends)
        assertEquals("s2", s.current)
    }

    @Test fun consentTimesOutAfterSixtySeconds() {
        val s = session()
        s.start(start)
        now = 59_999
        s.checkTimeout()
        assertEquals("s1", s.current)
        now = 60_000
        s.checkTimeout()
        assertNull(s.current)
        assertEquals(Message.MirrorState(MirrorStateBody("s1", MirrorPhaseName.Stopped, reason = MirrorStopReason.ConsentTimeout)), sent.last())
        assertEquals(listOf(MirrorStopReason.ConsentTimeout), ends)
    }

    @Test fun deniedAndMacStops() {
        val s = session()
        s.start(start)
        s.consentDenied()
        assertEquals(MirrorStopReason.Denied, (sent.last() as Message.MirrorState).body.reason)
        s.start(start.copy(sessionId = "s2"))
        s.macStopped("other")
        assertEquals("s2", s.current)
        s.macStopped("s2")
        assertNull(s.current)
        assertEquals(MirrorStopReason.Mac, ends.last())
    }

    // Review Focus #5
    @Test fun phoneSessionEndsWhenTheMacGoes() {
        val s = session()
        s.start(start)
        s.streaming(864, 1920, 0, audio = false, control = false)
        val before = sent.size
        s.connectionLost()
        assertNull(s.current)
        assertEquals(listOf(MirrorStopReason.Mac), ends)
        assertEquals(before, sent.size) // nothing to send to: the Mac is gone
    }
}
```

Run `./gradlew -q :core:compileTestKotlin`. Expected: unresolved references.

- [ ] **Step 2: Implement** `MirrorRules.kt`

```kotlin
package dev.macdroid.core.mirror

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.MirrorPhaseName
import dev.macdroid.core.protocol.MirrorStartBody
import dev.macdroid.core.protocol.MirrorStateBody
import dev.macdroid.core.protocol.MirrorStopReason

/** Encoder size and bitrate (spec §3.2). */
object MirrorSize {
    fun fit(width: Int, height: Int, maxSize: Int): Pair<Int, Int> {
        val scale = minOf(1.0, maxSize.toDouble() / maxOf(width, height))
        fun round(v: Int) = maxOf(16, ((v * scale).toInt() / 16) * 16)
        return round(width) to round(height)
    }

    fun bitrate(maxSize: Int): Int = if (maxSize >= 1920) 8_000_000 else 4_000_000
}

enum class VideoAdmit { Send, Drop, DropAndRequestKey }

/**
 * Never let latency build up behind a slow network (spec §3.4). Thresholds are time at the stream's bitrate:
 * video over 300 ms queued → drop until a key frame fits; audio over 200 ms queued → drop the chunk.
 */
class SendPolicy(bitrateBps: Int) {
    val videoLimitBytes: Long = bitrateBps.toLong() / 8 * 300 / 1000
    val audioLimitBytes: Long = bitrateBps.toLong() / 8 * 200 / 1000
    private var dropping = false

    fun video(keyFrame: Boolean, queuedBytes: Long): VideoAdmit {
        if (queuedBytes > videoLimitBytes) {
            if (dropping) return VideoAdmit.Drop
            dropping = true
            return if (keyFrame) VideoAdmit.Drop else VideoAdmit.DropAndRequestKey
        }
        if (dropping) {
            if (!keyFrame) return VideoAdmit.Drop
            dropping = false
        }
        return VideoAdmit.Send
    }

    fun audio(queuedBytes: Long): Boolean = queuedBytes <= audioLimitBytes
}

/**
 * The phone's side of one mirroring session (spec §3.1, §3.6): reports state to the Mac and decides when it ends.
 * [onEnd] runs when something other than the capture itself ended the session (the Mac, a timeout, a lost connection),
 * so the bridge can stop the capture service. Not thread-safe: call from one thread.
 */
class MirrorPhoneSession(
    private val send: (Message) -> Boolean,
    private val clockMs: () -> Long,
    private val onEnd: (MirrorStopReason) -> Unit,
) {
    var current: String? = null
        private set
    var options: MirrorStartBody? = null
        private set
    var isStreaming = false
        private set
    private var startedAt = 0L

    fun start(body: MirrorStartBody): Boolean {
        if (current != null) endHere(MirrorStopReason.Mac, report = true)
        current = body.sessionId
        options = body
        isStreaming = false
        startedAt = clockMs()
        send(Message.MirrorState(MirrorStateBody(body.sessionId, MirrorPhaseName.Starting)))
        return true
    }

    fun consentDenied() {
        val id = current ?: return
        send(Message.MirrorState(MirrorStateBody(id, MirrorPhaseName.Stopped, reason = MirrorStopReason.Denied)))
        clear()
    }

    fun streaming(width: Int, height: Int, rotation: Int, audio: Boolean, control: Boolean) {
        val id = current ?: return
        isStreaming = true
        send(Message.MirrorState(MirrorStateBody(id, MirrorPhaseName.Streaming, width, height, rotation, audio = audio, control = control)))
    }

    fun ended(reason: MirrorStopReason) {
        val id = current ?: return
        send(Message.MirrorState(MirrorStateBody(id, MirrorPhaseName.Stopped, reason = reason)))
        clear()
    }

    fun macStopped(sessionId: String) {
        if (sessionId == current) endHere(MirrorStopReason.Mac, report = false)
    }

    fun checkTimeout() {
        val id = current ?: return
        if (isStreaming || clockMs() - startedAt < CONSENT_TIMEOUT_MS) return
        send(Message.MirrorState(MirrorStateBody(id, MirrorPhaseName.Stopped, reason = MirrorStopReason.ConsentTimeout)))
        clear()
        onEnd(MirrorStopReason.ConsentTimeout)
    }

    fun connectionLost() {
        if (current != null) endHere(MirrorStopReason.Mac, report = false)
    }

    private fun endHere(reason: MirrorStopReason, report: Boolean) {
        val id = current ?: return
        if (report) send(Message.MirrorState(MirrorStateBody(id, MirrorPhaseName.Stopped, reason = reason)))
        clear()
        onEnd(reason)
    }

    private fun clear() {
        current = null
        options = null
        isStreaming = false
    }

    companion object {
        const val CONSENT_TIMEOUT_MS = 60_000L
    }
}
```

Run `./gradlew :core:test`. Expected: PASS.

- [ ] **Step 3: Handoff, commit**

CHANGELOG bullet: "Task 7: Android core `MirrorSize`, `SendPolicy` (drop video to a key frame past 300 ms queued, audio past 200 ms) and `MirrorPhoneSession` (states, replace, 60 s consent timeout, Mac stop, lost connection)."

Global-constraints ruling (record it in the CHANGELOG bullet too):
- Ruling: the sender's thresholds are 300 ms of video and 200 ms of audio at the stream's bitrate, not the spec's "1 MB", because 1 MB at 8 Mbps is a full second of lag.
- Cost if wrong: more key-frame requests on a marginal network.

```bash
git add android/core docs/handoff
git commit -m "feat(android-core): mirroring size, send policy and phone session

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 8: Android — consent, capture service, encoder, audio and the media stream

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/platform/mirror/MirrorBridge.kt`, `MirrorNotifications.kt`, `MirrorConsentActivity.kt`, `MirrorService.kt`, `ScreenEncoder.kt`, `AudioCapturer.kt`, `MediaStreamClient.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/dev/macdroid/app/MacDroidApp.kt`

**Interfaces:**
- **Consumes:**
  - Task 2 messages
  - Task 3 `MirrorFrame`
  - Task 7 `MirrorSize`, `SendPolicy`, `VideoAdmit` and `MirrorPhoneSession`
  - `TlsClientFactory.pinned`, `ConnectionManager` and `PairingStore`
- **Produces:**
  - `AppGraph.mirrorBridge`
  - `MirrorBridge`, with `session: MirrorPhoneSession` (main thread only), `start()` and `consentResult(sessionId: String, resultCode: Int, data: Intent?)`
  - `MirrorService.start(context, options, resultCode, data)`, `MirrorService.stop(context)` and `MirrorService.requestKeyFrame()`

This is Android glue with no JVM unit tests. The logic is in Task 7's tested core, and it is verified by the build, the Task 10 cross-platform test (the stream path) and the owner checklist (capture).

- [ ] **Step 1: Manifest**

Add to `android/app/src/main/AndroidManifest.xml`:

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
```

Inside `<application>`:

```xml
        <activity
            android:name=".platform.mirror.MirrorConsentActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:taskAffinity=""
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
        <service
            android:name=".platform.mirror.MirrorService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
```

- [ ] **Step 2: Notifications and consent**

`MirrorNotifications.kt`:

```kotlin
package dev.macdroid.app.platform.mirror

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.macdroid.app.R
import dev.macdroid.core.protocol.MirrorStartBody

/** "Mirror to <Mac>? Tap to start" and the ongoing "Mirroring to <Mac> · Stop" (spec §3.1). */
object MirrorNotifications {
    private const val REQUEST_CHANNEL = "mirror_requests"
    const val CHANNEL = "mirror"
    private const val REQUEST_ID = 4201
    const val SERVICE_ID = 4202

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(REQUEST_CHANNEL, "Mirroring requests", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mirroring", NotificationManager.IMPORTANCE_LOW))
    }

    fun showRequest(context: Context, body: MirrorStartBody, macName: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val tap = PendingIntent.getActivity(context, 0, MirrorConsentActivity.intent(context, body), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, REQUEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Mirror to $macName?")
            .setContentText("Tap to start")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(REQUEST_ID, notification)
    }

    fun cancelRequest(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(REQUEST_ID)

    fun service(context: Context, macName: String, stop: Intent): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle("Mirroring to $macName")
            .setOngoing(true)
            .addAction(0, "Stop", PendingIntent.getService(context, 1, stop, PendingIntent.FLAG_IMMUTABLE))
            .build()
}
```

`MirrorConsentActivity.kt`:

```kotlin
package dev.macdroid.app.platform.mirror

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.macdroid.app.graph
import dev.macdroid.core.protocol.MirrorStartBody

/** Wakes the screen, asks for the microphone if audio is wanted, then shows Android's capture consent (spec §3.1). */
class MirrorConsentActivity : ComponentActivity() {
    private val sessionId by lazy { intent.getStringExtra(EXTRA_SESSION).orEmpty() }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askCapture() }
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        graph.mirrorBridge.consentResult(sessionId, result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (savedInstanceState != null) return
        val wantsAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        if (wantsAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            askCapture()
        }
    }

    private fun askCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // API 34+: offer only "Entire screen" (plan ruling).
        capture.launch(if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent())
    }

    companion object {
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_AUDIO = "audio"

        fun intent(context: Context, body: MirrorStartBody): Intent =
            Intent(context, MirrorConsentActivity::class.java)
                .putExtra(EXTRA_SESSION, body.sessionId)
                .putExtra(EXTRA_AUDIO, body.audio)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
```

- [ ] **Step 3: Bridge**

`MirrorBridge.kt`:

```kotlin
package dev.macdroid.app.platform.mirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.mirror.MirrorPhoneSession
import dev.macdroid.core.pairing.PairingStore
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.MirrorPongBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The phone's end of mirroring (spec §3.1, §3.6): answers the Mac's `mirror.*`, asks for consent, and runs the capture service.
 * [session] is touched only on the main thread.
 */
class MirrorBridge(private val context: Context, private val connection: ConnectionManager, private val store: PairingStore, private val scope: CoroutineScope) {
    private val main = Handler(Looper.getMainLooper())
    val session = MirrorPhoneSession(connection::send, SystemClock::elapsedRealtime) {
        MirrorNotifications.cancelRequest(context)
        MirrorService.stop()
    }

    fun start() {
        MirrorNotifications.createChannels(context)
        scope.launch {
            connection.incoming.collect { message ->
                // Pings are answered right here so the measured offset doesn't include a thread hop (spec §6.6).
                if (message is Message.MirrorPing) {
                    val received = System.nanoTime() / 1000
                    connection.send(Message.MirrorPong(MirrorPongBody(message.body.t0, received, System.nanoTime() / 1000)))
                } else {
                    main.post { handle(message) }
                }
            }
        }
        scope.launch {
            connection.state.map { it is ConnectionState.Connected }.distinctUntilChanged().collect { up ->
                if (!up) main.post { session.connectionLost() }
            }
        }
        scope.launch {
            while (true) {
                delay(1_000)
                main.post { session.checkTimeout() }
            }
        }
    }

    private fun handle(message: Message) {
        when (message) {
            is Message.MirrorStart -> {
                session.start(message.body)
                MirrorNotifications.showRequest(context, message.body, store.paired.value?.name ?: "your Mac")
                // Works when MacDroid is on screen; otherwise the notification is the way in (plan ruling; Plan 8 lifts this).
                runCatching { context.startActivity(MirrorConsentActivity.intent(context, message.body)) }
            }
            is Message.MirrorStop -> session.macStopped(message.body.sessionId)
            is Message.MirrorKeyframe -> if (message.body.sessionId == session.current) MirrorService.requestKeyFrame()
            else -> Unit
        }
    }

    /** From [MirrorConsentActivity], on the main thread. */
    fun consentResult(sessionId: String, resultCode: Int, data: Intent?) {
        MirrorNotifications.cancelRequest(context)
        val options = session.options
        if (sessionId != session.current || options == null) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            session.consentDenied()
            return
        }
        MirrorService.start(context, options, resultCode, data)
    }
}
```

`AppGraph` (in `MacDroidApp.kt`), after `photosBridge`:

```kotlin
    /** Screen mirroring for the Mac (Plan 7); runs for the whole process. */
    val mirrorBridge = MirrorBridge(context, connectionManager, pairingStore, appScope).also { it.start() }
```

Add the import `dev.macdroid.app.platform.mirror.MirrorBridge`.

- [ ] **Step 4: Encoder, audio, stream**

`ScreenEncoder.kt`:

```kotlin
package dev.macdroid.app.platform.mirror

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.view.Surface
import kotlin.concurrent.thread

/**
 * Screen → VirtualDisplay → the hardware H.264 encoder's input surface, with no copies (spec §3.2).
 * [output] runs on the encoder thread: (codec config?, key frame?, capture µs, bytes).
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val dpi: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val output: (config: Boolean, keyFrame: Boolean, micros: Long, data: ByteArray) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var drain: Thread? = null
    @Volatile private var running = false

    fun start(width: Int, height: Int) {
        val (codec, surface) = newCodec(width, height)
        display = projection.createVirtualDisplay("MacDroid mirror", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        begin(codec)
    }

    /** Rotation or a display change: a new encoder at the new size (spec §3.2). */
    fun resize(width: Int, height: Int) {
        stopCodec()
        val (codec, surface) = newCodec(width, height)
        display?.resize(width, height, dpi)
        display?.surface = surface
        begin(codec)
    }

    fun requestKeyFrame() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        stopCodec()
        display?.release()
        display = null
    }

    private fun newCodec(width: Int, height: Int): Pair<MediaCodec, Surface> {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format(width, height, high = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            codec.reset() // High profile not offered: let the encoder choose
            codec.configure(format(width, height, high = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return codec to codec.createInputSurface()
    }

    private fun format(width: Int, height: Int, high: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
        setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 2f)
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000) // a still screen sends almost nothing
        setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
        if (high) {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
        }
    }

    private fun begin(codec: MediaCodec) {
        this.codec = codec
        codec.start()
        running = true
        drain = thread(name = "mirror-encoder", priority = Thread.MAX_PRIORITY) { drainLoop(codec) }
    }

    private fun drainLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: IllegalStateException) { break }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                output(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, info.presentationTimeUs, bytes)
            }
            runCatching { codec.releaseOutputBuffer(index, false) }
        }
    }

    private fun stopCodec() {
        running = false
        drain?.join(500)
        drain = null
        codec?.let {
            runCatching { it.stop() }
            it.release()
        }
        codec = null
    }
}
```

`AudioCapturer.kt`:

```kotlin
package dev.macdroid.app.platform.mirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlin.concurrent.thread

/** What apps play, as 48 kHz stereo PCM in 10 ms chunks (spec §3.3). [output] runs on the audio thread. */
class AudioCapturer(private val projection: MediaProjection, private val output: (micros: Long, data: ByteArray, length: Int) -> Unit) {
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    @Volatile private var running = false

    /** The caller has checked RECORD_AUDIO. False when capture isn't available. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val record = runCatching {
            AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config).setBufferSizeInBytes(CHUNK * 8).build()
        }.getOrNull() ?: return false
        this.record = record
        record.startRecording()
        running = true
        reader = thread(name = "mirror-audio", priority = Thread.MAX_PRIORITY) {
            val buffer = ByteArray(CHUNK)
            while (running) {
                val n = record.read(buffer, 0, CHUNK, AudioRecord.READ_BLOCKING)
                if (n > 0) output(System.nanoTime() / 1000, buffer, n)
            }
        }
        return true
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        reader?.join(200)
        record?.release()
        record = null
    }

    companion object {
        /** 10 ms of 48 kHz stereo 16-bit. */
        const val CHUNK = 1_920
    }
}
```

`MediaStreamClient.kt`:

```kotlin
package dev.macdroid.app.platform.mirror

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * The second mutual-TLS WebSocket, `/v1/mirror`, gated by the session id (spec §3.4, §5.3).
 * One automatic reconnect; a second drop calls [onGone].
 */
class MediaStreamClient(
    private val client: OkHttpClient,
    private val host: String,
    private val port: Int,
    private val sessionId: String,
    private val onOpen: () -> Unit,
    private val onGone: () -> Unit,
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private var reconnected = false

    /** Bytes waiting to be sent; drives [dev.macdroid.core.mirror.SendPolicy]. */
    val queuedBytes: Long get() = socket?.queueSize() ?: Long.MAX_VALUE

    fun open() {
        val url = HttpUrl.Builder().scheme("https").host(host).port(port).addPathSegments("v1/mirror").build()
        val request = Request.Builder().url(url).header(SESSION_HEADER, sessionId).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped()
        })
    }

    fun send(frame: ByteArray): Boolean = socket?.send(frame.toByteString()) ?: false

    fun close() {
        closed = true
        socket?.close(1000, null)
        socket = null
    }

    @Synchronized private fun dropped() {
        if (closed) return
        socket = null
        if (!reconnected) {
            reconnected = true
            open()
        } else {
            onGone()
        }
    }

    companion object {
        const val SESSION_HEADER = "X-MacDroid-Session"
    }
}
```

- [ ] **Step 5: The capture service**

`MirrorService.kt`:

```kotlin
package dev.macdroid.app.platform.mirror

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Surface
import dev.macdroid.app.graph
import dev.macdroid.core.mirror.MirrorFrame
import dev.macdroid.core.mirror.MirrorSize
import dev.macdroid.core.mirror.SendPolicy
import dev.macdroid.core.mirror.VideoAdmit
import dev.macdroid.core.protocol.MirrorStartBody
import dev.macdroid.core.protocol.MirrorStopReason
import dev.macdroid.core.tls.TlsClientFactory
import java.util.concurrent.TimeUnit

/** One mirroring session's capture: foreground service of type mediaProjection (spec §3.1–3.4, §3.6). Main thread except the encoder/audio callbacks. */
class MirrorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var audio: AudioCapturer? = null
    private var stream: MediaStreamClient? = null
    private var policy: SendPolicy? = null
    @Volatile private var lastConfig: ByteArray? = null
    private var options: MirrorStartBody? = null
    private var size: Triple<Int, Int, Int>? = null
    private var audioOn = false
    private var savedVolume: Int? = null
    private var finished = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) resizeIfNeeded() }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish(MirrorStopReason.User)
            return START_NOT_STICKY
        }
        val body = intent?.let { readOptions(it) }
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val macName = graph.pairingStore.paired.value?.name ?: "your Mac"
        startForeground(MirrorNotifications.SERVICE_ID,
            MirrorNotifications.service(this, macName, Intent(this, MirrorService::class.java).setAction(ACTION_STOP)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (body == null || data == null) {
            finish(MirrorStopReason.Error)
            return START_NOT_STICKY
        }
        begin(body, intent.getIntExtra(EXTRA_CODE, 0), data)
        return START_NOT_STICKY
    }

    private fun begin(body: MirrorStartBody, code: Int, data: Intent) {
        val mac = graph.pairingStore.paired.value ?: return finish(MirrorStopReason.Error)
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) ?: return finish(MirrorStopReason.Error)
        options = body
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { finish(MirrorStopReason.Ended) } }
        }, handler)
        val bitrate = MirrorSize.bitrate(body.maxSize)
        policy = SendPolicy(bitrate)
        val http = TlsClientFactory.pinned(graph.identity, mac.fingerprint, graph.httpClient).newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .build()
        stream = MediaStreamClient(http, mac.host, mac.port, body.sessionId,
            onOpen = {
                lastConfig?.let { stream?.send(it) }
                encoder?.requestKeyFrame()
            },
            onGone = { handler.post { finish(MirrorStopReason.Error) } },
        ).also { it.open() }
        val (w, h, rotation) = screenSize(body.maxSize).also { size = it }
        encoder = ScreenEncoder(mp, resources.displayMetrics.densityDpi, body.fps, bitrate, ::onVideo).also { it.start(w, h) }
        audioOn = body.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            AudioCapturer(mp, ::onAudio).let { capturer -> capturer.start().also { if (it) audio = capturer } }
        if (audioOn && MUTE_PHONE_SPEAKER) {
            val am = getSystemService(AudioManager::class.java)
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
        displays.registerDisplayListener(displayListener, handler)
        running = this
        graph.mirrorBridge.session.streaming(w, h, rotation, audio = audioOn, control = false)
    }

    // Encoder thread.
    private fun onVideo(config: Boolean, keyFrame: Boolean, micros: Long, data: ByteArray) {
        val stream = stream ?: return
        val policy = policy ?: return
        if (config) {
            val frame = MirrorFrame.encode(MirrorFrame.TYPE_CONFIG, false, micros, data)
            lastConfig = frame
            stream.send(frame)
            return
        }
        when (synchronized(policy) { policy.video(keyFrame, stream.queuedBytes) }) {
            VideoAdmit.Send -> stream.send(MirrorFrame.encode(MirrorFrame.TYPE_VIDEO, keyFrame, micros, data))
            VideoAdmit.DropAndRequestKey -> encoder?.requestKeyFrame()
            VideoAdmit.Drop -> Unit
        }
    }

    // Audio thread.
    private fun onAudio(micros: Long, data: ByteArray, length: Int) {
        val stream = stream ?: return
        val policy = policy ?: return
        if (synchronized(policy) { policy.audio(stream.queuedBytes) }) {
            stream.send(MirrorFrame.encode(MirrorFrame.TYPE_AUDIO, false, micros, data, 0, length))
        }
    }

    /** The display's size as currently shown (natural size swapped when rotated), fitted to maxSize (spec §3.2). */
    private fun screenSize(maxSize: Int): Triple<Int, Int, Int> {
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        val rotation = when (display.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val natural = minOf(mode.physicalWidth, mode.physicalHeight) to maxOf(mode.physicalWidth, mode.physicalHeight)
        val (w, h) = if (rotation == 90 || rotation == 270) natural.second to natural.first else natural
        val (fw, fh) = MirrorSize.fit(w, h, maxSize)
        return Triple(fw, fh, rotation)
    }

    private fun resizeIfNeeded() {
        val body = options ?: return
        val next = screenSize(body.maxSize)
        if (next == size) return
        size = next
        encoder?.resize(next.first, next.second)
        graph.mirrorBridge.session.streaming(next.first, next.second, next.third, audio = audioOn, control = false)
    }

    /** [reason] null: the session already ended elsewhere (the Mac, a lost connection); nothing to report. */
    private fun finish(reason: MirrorStopReason?) {
        if (finished) return
        finished = true
        running = null
        runCatching { displays.unregisterDisplayListener(displayListener) }
        audio?.stop()
        encoder?.release()
        stream?.close()
        projection?.stop()
        savedVolume?.let { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        if (reason != null) graph.mirrorBridge.session.ended(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun readOptions(intent: Intent): MirrorStartBody? {
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return null
        return MirrorStartBody(session, intent.getIntExtra(EXTRA_MAX, 1920), intent.getIntExtra(EXTRA_FPS, 60), intent.getBooleanExtra(EXTRA_AUDIO, false))
    }

    companion object {
        private const val ACTION_STOP = "dev.macdroid.app.mirror.STOP"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_MAX = "maxSize"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        /** From the Task 1 probe, question 4: mute the phone's speaker while mirroring audio only if capture keeps working. */
        private const val MUTE_PHONE_SPEAKER = false
        @Volatile private var running: MirrorService? = null

        fun start(context: Context, options: MirrorStartBody, resultCode: Int, data: Intent) {
            context.startForegroundService(Intent(context, MirrorService::class.java)
                .putExtra(EXTRA_SESSION, options.sessionId).putExtra(EXTRA_MAX, options.maxSize).putExtra(EXTRA_FPS, options.fps)
                .putExtra(EXTRA_AUDIO, options.audio).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data))
        }

        /** The session ended elsewhere; stop capturing without reporting again. */
        fun stop() {
            running?.let { service -> service.handler.post { service.finish(null) } }
        }

        fun requestKeyFrame() {
            running?.encoder?.requestKeyFrame()
        }
    }
}
```

Set `MUTE_PHONE_SPEAKER` from the Task 1 probe's Q4 result: true only if the RMS stayed non-zero while `STREAM_MUSIC` was 0. If the probe showed the session ends on lock (Q3), nothing changes here: `onStop` already ends it with `Ended`.

`graph.identity` and `graph.httpClient` already exist on `AppGraph`. If either is private, make it `val` (both are public today).

Run `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --warning-mode all`. Expected: BUILD SUCCESSFUL, no `w:` lines. If a deprecation warning remains (e.g. `getParcelableExtra`), use the API 33 typed overload behind a `Build.VERSION.SDK_INT >= 33` check instead of suppressing.

- [ ] **Step 6: Handoff, commit**

CHANGELOG bullet: "Task 8: Android mirroring: the consent notification and activity (wakes the screen, microphone permission, entire screen only), the `mediaProjection` foreground service, the H.264 surface encoder (CBR, no B-frames, key frame on request, rebuilt on rotation), playback-capture audio and the pinned `/v1/mirror` stream with one reconnect and the send policy."

```bash
git add android docs/handoff
git commit -m "feat(android): screen and audio capture streamed to the Mac

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Mac app — the mirror window, video, audio and settings

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Presentation/MirrorPresentation.swift` (+ tests in `MirrorCoreTests.swift`)
- Create: `mac/MacDroid/Mirror/MirrorRenderer.swift`, `MirrorAudioPlayer.swift`, `MirrorWindowController.swift`; `mac/MacDroid/Views/Mirror/MirrorContentView.swift`
- Modify:
  - `mac/MacDroid/AppModel.swift`
  - `Views/Phone/PairedDeviceView.swift`
  - `Views/MenuBarPopover.swift`
  - `Views/Settings/GeneralSettingsView.swift`

**Interfaces:**
- **Consumes:** Tasks 4–6.
- **Produces:**
  - `MirrorPresentation`, with:
    - `status(_ state: MirrorState) -> String?`
    - `audioNotice(_ state: MirrorState, requested: Bool) -> String?`
    - `LatencyTone` (`.good`, `.fair`, `.poor`) and `latencyTone(_ ms: Int) -> LatencyTone`
  - App:
    - `AppModel.mirror: MirrorState`, `mirrorLatency: Int?`, `mirrorKeepOnTop`
    - `startMirroring()`, `stopMirroring()`, `mirrorWindowClosed()`
    - settings `mirrorQuality`, `mirrorFPS`, `mirrorAudio`, `mirrorShowLatency`

- [ ] **Step 1: Failing presentation tests** (append to `MirrorCoreTests.swift`)

```swift
@Suite struct MirrorPresentationTests {
    func state(_ phase: MirrorPhase, audio: Bool? = nil) -> MirrorState {
        var s = MirrorState()
        s.phase = phase
        s.audio = audio
        return s
    }

    @Test func statusLines() {
        #expect(MirrorPresentation.status(state(.idle)) == nil)
        #expect(MirrorPresentation.status(state(.starting(waitingForTap: false))) == "Starting…")
        #expect(MirrorPresentation.status(state(.starting(waitingForTap: true))) == "Tap Start on your phone to begin mirroring.")
        #expect(MirrorPresentation.status(state(.streaming(width: 1, height: 1, rotation: 0))) == nil)
        #expect(MirrorPresentation.status(state(.stopped(.declined))) == "Mirroring was declined on the phone.")
        #expect(MirrorPresentation.status(state(.stopped(.consentTimeout))) == "Mirroring didn't start")
        #expect(MirrorPresentation.status(state(.stopped(.noPermission))) == "Mirroring didn't start")
        #expect(MirrorPresentation.status(state(.stopped(.disconnected))) == "Phone not connected")
        #expect(MirrorPresentation.status(state(.stopped(.error))) == "Mirroring stopped")
        #expect(MirrorPresentation.status(state(.stopped(.user))) == "Mirroring stopped")
    }

    @Test func audioNoticeOnlyWhenAskedForButMissing() {
        let streaming = MirrorPhase.streaming(width: 1, height: 1, rotation: 0)
        #expect(MirrorPresentation.audioNotice(state(streaming, audio: false), requested: true) == "Phone audio is off: allow the microphone permission for MacDroid on your phone")
        #expect(MirrorPresentation.audioNotice(state(streaming, audio: true), requested: true) == nil)
        #expect(MirrorPresentation.audioNotice(state(streaming, audio: false), requested: false) == nil)
    }

    @Test func latencyTones() {
        #expect(MirrorPresentation.latencyTone(60) == .good)
        #expect(MirrorPresentation.latencyTone(120) == .fair)
        #expect(MirrorPresentation.latencyTone(150) == .poor)
    }
}
```

Run the filter. Expected: compile errors.

- [ ] **Step 2: Implement** `MirrorPresentation.swift`

```swift
/// Text for the mirror window (spec §6.3, §8).
public enum MirrorPresentation {
    public enum LatencyTone: Equatable, Sendable { case good, fair, poor }

    public static func status(_ state: MirrorState) -> String? {
        switch state.phase {
        case .idle, .streaming: nil
        case .starting(let waitingForTap): waitingForTap ? "Tap Start on your phone to begin mirroring." : "Starting…"
        case .stopped(.declined): "Mirroring was declined on the phone."
        case .stopped(.consentTimeout), .stopped(.noPermission): "Mirroring didn't start"
        case .stopped(.disconnected): "Phone not connected"
        case .stopped: "Mirroring stopped"
        }
    }

    public static func audioNotice(_ state: MirrorState, requested: Bool) -> String? {
        guard requested, case .streaming = state.phase, state.audio == false else { return nil }
        return "Phone audio is off: allow the microphone permission for MacDroid on your phone"
    }

    /// Green under 100 ms, orange under 150, red above (spec §6.3).
    public static func latencyTone(_ ms: Int) -> LatencyTone { ms < 100 ? .good : ms < 150 ? .fair : .poor }
}
```

Run the filter. Expected: PASS.

- [ ] **Step 3: Renderer and audio player (app)**

`mac/MacDroid/Mirror/MirrorRenderer.swift`:

```swift
import AVFoundation
import MacDroidCore

/// Shows the phone's video as it arrives (spec §6.1–6.2): hardware decode, display immediately, and drop to the next key frame
/// instead of queueing. `deliver` is called on the server's thread; decoding work runs on one serial queue.
final class MirrorRenderer: MirrorFrameSink, @unchecked Sendable {
    let layer = AVSampleBufferDisplayLayer()
    private let renderer: AVSampleBufferVideoRenderer
    private let queue = DispatchQueue(label: "dev.macdroid.mirror.video", qos: .userInteractive)
    private let lock = NSLock()
    private var waiting = 0 // lock
    private var format: CMVideoFormatDescription? // queue
    private var backlog = VideoBacklog() // queue
    private let meter: LatencyMeter
    private let audio: MirrorAudioPlayer
    private let requestKeyFrame: @Sendable () -> Void

    init(meter: LatencyMeter, audio: MirrorAudioPlayer, requestKeyFrame: @escaping @Sendable () -> Void) {
        self.meter = meter
        self.audio = audio
        self.requestKeyFrame = requestKeyFrame
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = CGColor.black
        renderer = layer.sampleBufferRenderer
    }

    func deliver(_ frame: MirrorFrame) {
        switch frame.type {
        case .audio:
            audio.play(frame.payload)
        case .config, .video:
            lock.withLock { waiting += 1 }
            queue.async { [self] in
                let behind = lock.withLock {
                    waiting -= 1
                    return waiting
                }
                show(frame, waiting: behind)
            }
        }
    }

    /// A new session: forget the old format and picture.
    func reset() {
        queue.async { [self] in
            format = nil
            backlog = VideoBacklog()
            renderer.flush(removingDisplayedImage: true, completionHandler: nil)
        }
    }

    private func show(_ frame: MirrorFrame, waiting: Int) {
        if frame.type == .config {
            guard let sets = H264.parameterSets(in: frame.payload), let next = try? H264.formatDescription(sps: sets.sps, pps: sets.pps) else { return }
            format = next
            backlog.resync() // the new format starts at its key frame (Review Focus #3)
            return
        }
        guard let format else { return }
        switch backlog.admit(keyFrame: frame.keyFrame, waiting: waiting) {
        case .drop:
            return
        case .dropAndRequestKeyFrame:
            requestKeyFrame()
            return
        case .show:
            break
        }
        if renderer.status == .failed {
            renderer.flush()
            backlog.resync()
            requestKeyFrame()
            return
        }
        guard let sample = try? H264.sampleBuffer(accessUnit: frame.payload, format: format) else { return }
        renderer.enqueue(sample)
        meter.record(captureMicros: frame.captureMicros, displayedMicros: MonotonicClock.micros())
    }
}
```

`mac/MacDroid/Mirror/MirrorAudioPlayer.swift`:

```swift
import AVFoundation
import MacDroidCore

/// Plays the phone's PCM (48 kHz stereo s16le) with a 40 ms cushion and skip-ahead (spec §6.4). All work on one queue.
final class MirrorAudioPlayer: @unchecked Sendable {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false)!
    private let queue = DispatchQueue(label: "dev.macdroid.mirror.audio", qos: .userInteractive)
    private var jitter = AudioJitter() // queue
    private var bufferedFrames = 0 // queue
    private var muted = false // queue

    init() {
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
    }

    func start(muted: Bool) {
        queue.async { [self] in
            self.muted = muted
            jitter = AudioJitter()
            bufferedFrames = 0
            if !muted { try? engine.start() }
        }
    }

    func stop() {
        queue.async { [self] in
            node.stop()
            engine.stop()
            jitter = AudioJitter()
            bufferedFrames = 0
        }
    }

    func play(_ pcm: Data) {
        queue.async { [self] in
            guard !muted, engine.isRunning else { return }
            let frames = pcm.count / 4
            guard frames > 0 else { return }
            let decision = jitter.decide(bufferedMillis: Double(bufferedFrames) / 48, chunkMillis: Double(frames) / 48)
            guard case .schedule(let startPlayback) = decision else { return }
            if !jitter.playing && node.isPlaying { node.pause() } // ran dry: rebuild the cushion
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)) else { return }
            buffer.frameLength = AVAudioFrameCount(frames)
            pcm.withUnsafeBytes { raw in
                let samples = raw.bindMemory(to: Int16.self)
                let left = buffer.floatChannelData![0], right = buffer.floatChannelData![1]
                for i in 0..<frames {
                    left[i] = Float(Int16(littleEndian: samples[2 * i])) / 32_768
                    right[i] = Float(Int16(littleEndian: samples[2 * i + 1])) / 32_768
                }
            }
            bufferedFrames += frames
            node.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                guard let self else { return }
                queue.async { self.bufferedFrames -= frames }
            }
            if startPlayback { node.play() }
        }
    }
}
```

- [ ] **Step 4: Window and content**

`mac/MacDroid/Mirror/MirrorWindowController.swift`:

```swift
import AppKit
import MacDroidCore
import SwiftUI

/// The mirror's own window (spec §6.3): phone-shaped, resizable, remembers its frame, optional "Keep on top".
@MainActor
final class MirrorWindowController: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private var aspect = CGSize(width: 9, height: 20)
    private let onClose: () -> Void

    init(onClose: @escaping () -> Void) {
        self.onClose = onClose
    }

    func show(content: some View, title: String) {
        if let window {
            window.makeKeyAndOrderFront(nil)
            NSApp.activate()
            return
        }
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 360, height: 800 + MirrorContentView.barHeight),
                              styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                              backing: .buffered, defer: false)
        window.title = title
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.isReleasedWhenClosed = false
        window.contentMinSize = NSSize(width: 200, height: 300)
        window.contentView = NSHostingView(rootView: content)
        window.delegate = self
        if !window.setFrameUsingName("MacDroidMirror") { window.center() }
        window.setFrameAutosaveName("MacDroidMirror")
        self.window = window
        window.makeKeyAndOrderFront(nil)
        NSApp.activate()
    }

    /// The phone's picture size (e.g. after rotation): keep the width, fit the height to the new shape.
    func setAspect(width: Int, height: Int) {
        guard width > 0, height > 0 else { return }
        aspect = CGSize(width: width, height: height)
        guard let window else { return }
        let content = window.contentRect(forFrameRect: window.frame).size
        window.setContentSize(fitted(content))
    }

    func setKeepOnTop(_ on: Bool) { window?.level = on ? .floating : .normal }

    func close() { window?.close() }

    func windowWillResize(_ sender: NSWindow, to frameSize: NSSize) -> NSSize {
        let content = sender.contentRect(forFrameRect: NSRect(origin: .zero, size: frameSize)).size
        return sender.frameRect(forContentRect: NSRect(origin: .zero, size: fitted(content))).size
    }

    func windowWillClose(_ notification: Notification) {
        window = nil
        onClose()
    }

    private func fitted(_ content: NSSize) -> NSSize {
        NSSize(width: content.width, height: (content.width * aspect.height / aspect.width).rounded() + MirrorContentView.barHeight)
    }
}
```

`mac/MacDroid/Views/Mirror/MirrorContentView.swift`:

```swift
import AVFoundation
import MacDroidCore
import SwiftUI

/// The picture, a status overlay, and the bar (spec §6.3). Plan 8 adds Back/Home/Recents/volume/lock and input.
struct MirrorContentView: View {
    static let barHeight: CGFloat = 40
    let model: AppModel
    let renderer: MirrorRenderer

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                MirrorVideoView(layer: renderer.layer)
                if let status = MirrorPresentation.status(model.mirror) {
                    Text(status)
                        .font(.callout)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white)
                        .padding(12)
                        .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 8))
                        .padding()
                }
            }
            MirrorBar(model: model).frame(height: Self.barHeight)
        }
        .background(Color.black)
        .ignoresSafeArea()
    }
}

private struct MirrorVideoView: NSViewRepresentable {
    let layer: AVSampleBufferDisplayLayer

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.wantsLayer = true
        view.layer = CALayer()
        view.layer?.backgroundColor = CGColor.black
        layer.frame = view.bounds
        layer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        view.layer?.addSublayer(layer)
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {}
}

private struct MirrorBar: View {
    @Bindable var model: AppModel

    var body: some View {
        HStack(spacing: 14) {
            if let notice = MirrorPresentation.audioNotice(model.mirror, requested: model.mirrorAudio) {
                Image(systemName: "speaker.slash").help(notice)
            }
            Spacer()
            if model.mirrorShowLatency, let ms = model.mirrorLatency {
                Text("\(ms) ms")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(color(MirrorPresentation.latencyTone(ms)))
                    .help("Time from the phone's screen to this window")
            }
            Toggle(isOn: $model.mirrorKeepOnTop) { Image(systemName: "pin") }
                .toggleStyle(.button)
                .help("Keep on top")
            Button { model.stopMirroring() } label: { Image(systemName: "stop.fill") }
                .help("Stop")
        }
        .buttonStyle(.borderless)
        .foregroundStyle(.white)
        .padding(.horizontal, 12)
        .background(Color(white: 0.12))
    }

    private func color(_ tone: MirrorPresentation.LatencyTone) -> Color {
        switch tone {
        case .good: .green
        case .fair: .orange
        case .poor: .red
        }
    }
}
```

- [ ] **Step 5: `AppModel` wiring**

Stored properties (observable unless marked):

```swift
    private(set) var mirror = MirrorState()
    private(set) var mirrorLatency: Int?
    var mirrorKeepOnTop = false { didSet { mirrorWindow?.setKeepOnTop(mirrorKeepOnTop) } }
    var mirrorQuality = UserDefaults.standard.object(forKey: "mirror.quality") as? Int ?? MirrorOptions.sharp {
        didSet { UserDefaults.standard.set(mirrorQuality, forKey: "mirror.quality") }
    }
    var mirrorFPS = UserDefaults.standard.object(forKey: "mirror.fps") as? Int ?? 60 {
        didSet { UserDefaults.standard.set(mirrorFPS, forKey: "mirror.fps") }
    }
    var mirrorAudio = UserDefaults.standard.object(forKey: "mirror.audio") as? Bool ?? true {
        didSet { UserDefaults.standard.set(mirrorAudio, forKey: "mirror.audio") }
    }
    var mirrorShowLatency = UserDefaults.standard.object(forKey: "mirror.showLatency") as? Bool ?? true {
        didSet { UserDefaults.standard.set(mirrorShowLatency, forKey: "mirror.showLatency") }
    }
    @ObservationIgnored let mirrorSession: MirrorSession
    @ObservationIgnored private let mirrorAudioPlayer = MirrorAudioPlayer()
    @ObservationIgnored private var mirrorRenderer: MirrorRenderer!
    @ObservationIgnored private var mirrorWindow: MirrorWindowController?
    @ObservationIgnored private var mirrorLatencyTask: Task<Void, Never>?
```

In `init`, after `phoneExtras`:

```swift
        let mirrorSession = MirrorSession(send: { message in try await hub.send(message) })
        self.mirrorSession = mirrorSession
```

At the end of `init` (all stored properties set):

```swift
        mirrorRenderer = MirrorRenderer(meter: mirrorSession.meter, audio: mirrorAudioPlayer,
                                        requestKeyFrame: { Task { await mirrorSession.requestKeyFrame() } })
```

In `start()`:
- Pass `mirror: mirrorSession` to `MacDroidServer(…)`.
- Add:

```swift
        tasks.append(Task { [mirrorSession, hub, mirrorRenderer] in
            await mirrorSession.attach(to: hub)
            await mirrorSession.setSink(mirrorRenderer)
        })
        tasks.append(Task { [weak self, mirrorSession] in
            for await state in mirrorSession.states { self?.mirrorChanged(state) }
        })
```

Methods:

```swift
    // MARK: Mirroring

    func startMirroring() {
        if mirrorWindow == nil {
            mirrorWindow = MirrorWindowController(onClose: { [weak self] in self?.mirrorWindowClosed() })
        }
        mirrorWindow?.show(content: MirrorContentView(model: self, renderer: mirrorRenderer), title: phoneName)
        mirrorWindow?.setKeepOnTop(mirrorKeepOnTop)
        mirrorRenderer.reset()
        mirrorAudioPlayer.start(muted: !mirrorAudio)
        let options = MirrorOptions(maxSize: mirrorQuality, fps: mirrorFPS, audio: mirrorAudio)
        Task { _ = await mirrorSession.start(options) }
    }

    func stopMirroring() {
        Task { await mirrorSession.stop() }
        mirrorAudioPlayer.stop()
        mirrorWindow?.close()
    }

    func mirrorWindowClosed() {
        mirrorWindow = nil
        Task { await mirrorSession.stop() }
        mirrorAudioPlayer.stop()
    }

    private func mirrorChanged(_ state: MirrorState) {
        mirror = state
        switch state.phase {
        case .streaming(let width, let height, _):
            mirrorWindow?.setAspect(width: width, height: height)
            if mirrorLatencyTask == nil {
                mirrorLatencyTask = Task { [weak self, mirrorSession] in
                    while !Task.isCancelled {
                        try? await Task.sleep(for: .milliseconds(500))
                        self?.mirrorLatency = mirrorSession.meter.averageMillis
                    }
                }
            }
        case .stopped(let reason):
            mirrorLatencyTask?.cancel()
            mirrorLatencyTask = nil
            mirrorLatency = nil
            mirrorAudioPlayer.stop()
            if reason == .disconnected {
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(10))
                    if case .stopped = self?.mirror.phase { self?.mirrorWindow?.close() }
                }
            }
        default:
            break
        }
    }
```

In `forgetPhone()`, add `stopMirroring()` first.

- [ ] **Step 6: Buttons and settings**

`PairedDeviceView`: in the bottom `HStack`, before "Pair a Different Phone…":

```swift
                    if model.mirror.supported {
                        Button("Mirror") { model.startMirroring() }
                    }
```

In the same view's "Details" section, for a connected phone whose app is too old (spec §8):

```swift
                if model.mirror.connected && !model.mirror.supported {
                    LabeledContent("Mirroring", value: "Update MacDroid on your phone to use this.")
                }
```

`MenuBarPopover`: next to the ring button on the phone card:

```swift
            if model.mirror.supported {
                Button { model.startMirroring() } label: { Image(systemName: "rectangle.on.rectangle") }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .help("Mirror your phone")
            }
```

Copy the ring button's exact modifiers so the two icons match.

`GeneralSettingsView`: a section after Clipboard (`model` is `@Bindable` there; if it isn't, use `Binding(get:set:)` like the clipboard toggles):

```swift
            Section {
                Picker("Quality", selection: $model.mirrorQuality) {
                    Text("Sharp").tag(MirrorOptions.sharp)
                    Text("Balanced").tag(MirrorOptions.balanced)
                }
                Picker("Frame rate", selection: $model.mirrorFPS) {
                    Text("60 fps").tag(60)
                    Text("30 fps").tag(30)
                }
                Toggle("Phone audio", isOn: $model.mirrorAudio)
                Toggle("Show latency", isOn: $model.mirrorShowLatency)
            } header: {
                Text("Mirroring")
            } footer: {
                Text("Mirroring starts from the Phone page or the menu bar. Apps that block screenshots show black.")
                    .foregroundStyle(.secondary)
            }
```

Run `swift test`, then the Mac app build. Expected: tests pass; the build succeeds with no warnings. Fix Swift 6 isolation diagnostics by making isolation explicit (e.g. `@MainActor` closures, captured `let`s); don't silence them.

- [ ] **Step 7: Handoff, commit**

CHANGELOG bullet: "Task 9: Mac mirror window (aspect-locked, remembered frame, keep on top, latency readout, Stop), `MirrorRenderer` (hardware decode, display immediately, drop to key frame), `MirrorAudioPlayer` (40 ms cushion, skip ahead), the Mirror buttons on the Phone page and popover, and Settings → Mirroring."

```bash
git add mac docs/handoff
git commit -m "feat(mac): mirror window with video, audio and latency

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Cross-platform test — a mirroring session end to end

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidDevServer/main.swift`, `android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt`, `scripts/cross-platform-test.sh`

- [ ] **Step 1: Failing script checks** (before the `cmp` lines)

```bash
grep -q 'MIRROR streaming 320x240' "$LOG" || { echo "the Mac never saw the phone streaming"; cat "$LOG"; exit 1; }
grep -q 'MIRROR video frames=20 keys=4 decoded=20' "$LOG" || { echo "the Mac didn't get and decode the video"; cat "$LOG"; exit 1; }
grep -q 'MIRROR audio chunks=3' "$LOG" || { echo "the Mac didn't get the audio"; cat "$LOG"; exit 1; }
grep -q 'MIRROR stopped user' "$LOG" || { echo "the Mac never saw the phone stop mirroring"; cat "$LOG"; exit 1; }
```

Run `./scripts/cross-platform-test.sh`. Expected: fails with "the Mac never saw the phone streaming".

- [ ] **Step 2: Dev server**

After the photos block:

```swift
/// Counts and decodes what arrives on /v1/mirror (exercises H264 on real frames).
final class DevMirrorSink: MirrorFrameSink, @unchecked Sendable {
    private let lock = NSLock()
    private var format: CMVideoFormatDescription?
    private var frames = 0, keys = 0, decoded = 0, audio = 0

    func deliver(_ frame: MirrorFrame) {
        lock.withLock {
            switch frame.type {
            case .config:
                if let sets = H264.parameterSets(in: frame.payload) { format = try? H264.formatDescription(sps: sets.sps, pps: sets.pps) }
            case .video:
                frames += 1
                if frame.keyFrame { keys += 1 }
                if let format, (try? H264.sampleBuffer(accessUnit: frame.payload, format: format)) != nil { decoded += 1 }
                if frames == 20 { print("MIRROR video frames=\(frames) keys=\(keys) decoded=\(decoded)") }
            case .audio:
                audio += 1
                if audio == 3 { print("MIRROR audio chunks=\(audio)") }
            }
        }
    }
}

let mirror = MirrorSession(consentTimeout: .seconds(120), send: { try await hub.send($0) })
await mirror.attach(to: hub)
await mirror.setSink(DevMirrorSink())

let mirrorPrinter = Task {
    var started = false
    var askedForKey = false
    for await state in mirror.states {
        if state.supported, !started {
            started = true
            _ = await mirror.start(MirrorOptions(maxSize: 1280, fps: 30, audio: true))
        }
        switch state.phase {
        case .streaming(let width, let height, _):
            print("MIRROR streaming \(width)x\(height)")
            if !askedForKey {
                askedForKey = true
                await mirror.requestKeyFrame()
            }
        case .stopped(let reason):
            if started { print("MIRROR stopped \(reason)") }
        default:
            break
        }
    }
}
```

- Add `import CoreMedia` at the top.
- Pass `mirror: mirror` to `MacDroidServer(…)`.
- Add `mirrorPrinter.cancel()` at the end.
- `MirrorEndReason.user` prints as `user`.

- [ ] **Step 3: Kotlin side**

In `CrossPlatformTest`, after `exchangePhotos(received)`, call `exchangeMirror(manager, store, phone, received)`:

```kotlin
    /** The Mac starts mirroring; a wrong session is refused; the fixture stream and audio go through; the phone stops. */
    private suspend fun exchangeMirror(manager: ConnectionManager, store: InMemoryPairingStore, phone: ClientIdentity, received: List<Message>) {
        withTimeout(20_000) { while (received.none { it is Message.MirrorStart }) delay(50) }
        val start = received.filterIsInstance<Message.MirrorStart>().first().body
        val mac = store.paired.value!!
        val http = TlsClientFactory.pinned(phone, mac.fingerprint).newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        fun open(session: String): Pair<WebSocket, CompletableDeferred<Boolean>> {
            val opened = CompletableDeferred<Boolean>()
            val url = HttpUrl.Builder().scheme("https").host(mac.host).port(mac.port).addPathSegments("v1/mirror").build()
            val socket = http.newWebSocket(Request.Builder().url(url).header("X-MacDroid-Session", session).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(true) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { opened.complete(false) }
            })
            return socket to opened
        }
        assertFalse("a wrong session must be refused", withTimeout(10_000) { open("not-the-session").second.await() })
        val (socket, opened) = open(start.sessionId)
        assertTrue(withTimeout(10_000) { opened.await() })
        manager.send(Message.MirrorState(MirrorStateBody(start.sessionId, MirrorPhaseName.Streaming, 320, 240, 0, audio = true, control = false)))
        val h264 = File(System.getProperty("fixturesDir"), "h264/sample.h264").readBytes()
        var micros = 1_000L
        for ((type, key, bytes) in h264Frames(h264)) {
            socket.send(MirrorFrame.encode(type, key, micros, bytes).toByteString())
            micros += 33_333
        }
        repeat(3) { socket.send(MirrorFrame.encode(MirrorFrame.TYPE_AUDIO, false, micros + it * 10_000, ByteArray(1_920)).toByteString()) }
        withTimeout(10_000) { while (received.none { it is Message.MirrorKeyframe }) delay(50) }
        delay(500)
        manager.send(Message.MirrorState(MirrorStateBody(start.sessionId, MirrorPhaseName.Stopped, reason = MirrorStopReason.User)))
        socket.close(1000, null)
        delay(300)
    }

    /** The fixture as the phone sends it: SPS+PPS as one config frame, then one frame per slice. */
    private fun h264Frames(data: ByteArray): List<Triple<Int, Boolean, ByteArray>> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) { starts += i; i += 4 }
            else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) { starts += i; i += 3 }
            else i++
        }
        val nals = starts.mapIndexed { n, s -> data.copyOfRange(s, if (n + 1 < starts.size) starts[n + 1] else data.size) }
        fun type(nal: ByteArray): Int { val header = if (nal[2] == 1.toByte()) nal[3] else nal[4]; return header.toInt() and 0x1F }
        val out = mutableListOf<Triple<Int, Boolean, ByteArray>>()
        var params = ByteArray(0)
        for (nal in nals) {
            when (type(nal)) {
                7, 8 -> params += nal
                1, 5 -> {
                    if (params.isNotEmpty()) { out += Triple(MirrorFrame.TYPE_CONFIG, false, params); params = ByteArray(0) }
                    out += Triple(MirrorFrame.TYPE_VIDEO, type(nal) == 5, nal)
                }
            }
        }
        return out
    }
```

`store` and `phone` are already locals in the main test. Pass them in.

- Imports: `dev.macdroid.core.mirror.MirrorFrame`, the mirror protocol types, `dev.macdroid.core.tls.TlsClientFactory`, `kotlinx.coroutines.CompletableDeferred`, `okhttp3.*` (HttpUrl, Request, Response, WebSocket, WebSocketListener), `okio.ByteString.Companion.toByteString`, `java.util.concurrent.TimeUnit`.
- The fixture directory comes from the `fixturesDir` system property that `android/core/build.gradle.kts` already sets for tests.

- [ ] **Step 4: Run**

Run `./scripts/cross-platform-test.sh`. Expected: "cross-platform test passed", twice in a row. Then run all gates. Expected: all pass with no warnings.

- [ ] **Step 5: Handoff, commit**

CHANGELOG bullet: "Task 10: cross-platform test covers mirroring: start, a wrong session refused, the H.264 fixture decoded on the Mac, audio chunks, a key-frame request and the phone's stop."

```bash
git add mac android scripts docs/handoff
git commit -m "test: cross-platform mirroring session

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Remove the probe, final gates, handoff, owner checklist

**Files:**
- Delete: `android/app/src/debug/java/dev/macdroid/app/debug/`, `android/app/src/debug/res/xml/mirror_probe_accessibility.xml`, `android/app/src/debug/AndroidManifest.xml`. Keep the follow-ups file with the probe results.
- Modify: `docs/handoff/STATUS.md`, `ARCHITECTURE.md`, `DEV-GUIDE.md`, `ROADMAP.md`, `DECISIONS.md`, `README.md`, `CHANGELOG.md`, `protocol/PROTOCOL.md` (any rules still missing)

- [ ] **Step 1: Remove the probe and run every gate**

```bash
git rm -r android/app/src/debug
cd mac/MacDroidCore && swift test 2>&1 | tail -3
cd ../ && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build 2>&1 | grep -E "^/.*(warning|error):|BUILD" | sort -u
cd ../android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --warning-mode all 2>&1 | grep -E "^e:|^w:|BUILD"
cd .. && ./scripts/cross-platform-test.sh
```

Expected: all tests pass; `BUILD SUCCEEDED`; `BUILD SUCCESSFUL` with no `w:`; "cross-platform test passed".

- [ ] **Step 2: Handoff docs**

- **ARCHITECTURE.md:**
  - Mac: `Mirror/` core (frame, H264, timing, backlog, jitter, session), the `/v1/mirror` route, `MirrorRenderer`, `MirrorAudioPlayer`, `MirrorWindowController`
  - Android: `platform/mirror/`, core `mirror/`
  - the session flow (start → consent → stream → stop) and the drop policies
- **DECISIONS.md:**
  - the sender thresholds in time, not bytes
  - the consent notification in Plan 7
  - entire screen only
  - drop to key frame on both sides
- **DEV-GUIDE.md:**
  - how to test mirroring
  - the probe results
  - where the mirror's settings live
  - "Android may stop capture on lock" (if Q3 said so)
- **STATUS.md / ROADMAP.md / README.md:** Plan 7 built on `feat/mirroring`; Plan 8 next on the same branch.
- **CHANGELOG.md:** close the Plan 7 entry with the gate results.

- [ ] **Step 3: Owner checklist** (in the follow-ups file and the final message)

1. Install both builds. On the Mac: Settings → Mirroring shows Quality, Frame rate, Phone audio, Show latency.
2. Phone page → **Mirror** (and the popover icon).
   - The window opens with "Starting…".
   - The phone shows the consent screen (if MacDroid is open) or a "Mirror to <Mac>? Tap to start" notification.
   - Tap Start; allow the microphone the first time.
3. **Picture:** sharp text at Sharp/60, and the window keeps the phone's shape when resized.
4. **Latency:** the readout shows a number. Write down typical values at Sharp/60 and Balanced/30 (target 50–80 ms).
5. **Sound:** play a video on the phone; it plays on the Mac in sync. With Phone audio off, no sound.
6. **Rotation:** rotate the phone into a landscape video; the window reshapes and keeps playing.
7. **Keep on top:** the window stays above other apps.
8. **Stopping:** Stop on the Mac ends it on the phone (the notification goes); "Stop" in the phone notification ends it on the Mac ("Mirroring stopped"); closing the window ends it.
9. **Declining:** decline the consent → "Mirroring was declined on the phone." Ignore it for 20 s → "Tap Start on your phone to begin mirroring."; at 60 s → "Mirroring didn't start".
10. **Wi-Fi:** turn it off on the phone mid-session → the window says "Phone not connected" and closes after 10 s.
11. **Protected apps:** open a banking app → black (expected).

- [ ] **Step 4: Commit**

```bash
git add -A android docs protocol
git commit -m "docs(handoff): mirroring (Plan 7) built on feat/mirroring; probe removed

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Don't merge. Plan 8 (remote control) continues on this branch.
