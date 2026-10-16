package dev.macdroid.app.ui.files

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.macdroid.app.graph

/** An invisible activity that shows the system "save as" screen for one incoming file and hands the answer back. */
class SaveAsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME) ?: "file"
        val mime = intent.getStringExtra(EXTRA_MIME) ?: "application/octet-stream"
        if (savedInstanceState == null && !graph.savePrompter.isWaiting(id)) {
            // A stale notification: creating a document now would leave an empty file nobody fills (Plan 11).
            Toast.makeText(this, "That transfer is no longer waiting", Toast.LENGTH_LONG).show()
            return finish()
        }
        val launcher = registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }
            graph.savePrompter.answer(id, uri)
            finish()
        }
        if (savedInstanceState == null) launcher.launch(name)
    }

    companion object {
        private const val EXTRA_ID = "transferId"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MIME = "mime"

        fun intent(context: Context, transferId: String, name: String, mime: String): Intent =
            Intent(context, SaveAsActivity::class.java)
                .putExtra(EXTRA_ID, transferId).putExtra(EXTRA_NAME, name).putExtra(EXTRA_MIME, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
