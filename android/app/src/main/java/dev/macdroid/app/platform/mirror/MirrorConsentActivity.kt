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
