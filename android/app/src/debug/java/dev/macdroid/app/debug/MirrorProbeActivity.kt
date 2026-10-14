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
