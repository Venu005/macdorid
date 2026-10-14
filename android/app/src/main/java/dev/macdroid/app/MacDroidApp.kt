package dev.macdroid.app

import android.app.Application
import android.content.Context
import dev.macdroid.app.platform.ContentSourceOpener
import dev.macdroid.app.platform.DeviceInfo
import dev.macdroid.app.platform.FilesPrefs
import dev.macdroid.app.platform.KeystoreIdentity
import dev.macdroid.app.platform.NsdMacLocator
import dev.macdroid.app.platform.PrefsPairingStore
import dev.macdroid.app.platform.SavePrompter
import dev.macdroid.app.platform.SaveTargets
import dev.macdroid.app.platform.clipboard.ClipboardBridge
import dev.macdroid.app.platform.clipboard.ClipboardImages
import dev.macdroid.app.platform.clipboard.ClipboardPrefs
import dev.macdroid.app.platform.extras.MediaWatcher
import dev.macdroid.app.platform.notifications.NotificationBridge
import dev.macdroid.app.platform.mirror.MirrorBridge
import dev.macdroid.app.platform.photos.PhotosBridge
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.pairing.PairingClient
import dev.macdroid.core.pairing.PairingStore
import dev.macdroid.core.protocol.Features
import dev.macdroid.core.protocol.HelloBody
import dev.macdroid.core.tls.ClientIdentity
import dev.macdroid.core.transfer.FileEndpoint
import dev.macdroid.core.transfer.FolderSaveTarget
import dev.macdroid.core.transfer.JsonFileTransferStore
import dev.macdroid.core.transfer.TransferClient
import dev.macdroid.core.transfer.TransferEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

class MacDroidApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as MacDroidApp).graph

/** Process-wide singletons. */
class AppGraph(private val context: Context) {
    val httpClient = OkHttpClient()
    val pairingStore: PairingStore = PrefsPairingStore(context)
    val identity: ClientIdentity by lazy { KeystoreIdentity.getOrCreate() }
    val uiPrefs = UiPrefs(context)
    val connectionManager = ConnectionManager(
        store = pairingStore,
        identity = { identity },
        locator = NsdMacLocator(context),
        hello = { HelloBody(app = BuildConfig.VERSION_NAME, device = DeviceInfo.name(context), features = Features.SUPPORTED) },
        baseClient = httpClient,
    )

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val filesPrefs = FilesPrefs(context)
    val savePrompter = SavePrompter(context)
    val saveTargets = SaveTargets(context, filesPrefs, savePrompter)

    /** Runs for the whole process; transfers move while ConnectionService keeps the connection up (spec §4.2 "Background"). */
    val transferEngine = TransferEngine(
        scope = appScope,
        store = JsonFileTransferStore(File(context.filesDir, "transfers.json")),
        files = TransferClient(FileEndpoint(pairingStore, { identity }, httpClient)::current),
        sources = ContentSourceOpener(context.contentResolver),
        saveTarget = saveTargets::current,
        clipboardTarget = { FolderSaveTarget(ClipboardImages.receivedDir(context)) },
        parts = { handle -> saveTargets.reopen(handle) ?: FolderSaveTarget.reopen(handle) },
        send = connectionManager::send,
    ).also { it.start(connectionManager.incoming, connectionManager.state.map { state -> state is ConnectionState.Connected }) }

    /** Mirrors notifications to the Mac (spec §3.1); runs for the whole process like the transfer engine. */
    val notificationBridge = NotificationBridge(context, connectionManager, appScope).also { it.start() }

    val clipboardPrefs = ClipboardPrefs(context)
    /** Clipboard sync (spec §4.2–4.3); runs for the whole process. */
    val clipboardBridge = ClipboardBridge(context, connectionManager, transferEngine, clipboardPrefs, appScope).also { it.start() }

    /** Media controls for the Mac's Now Playing card (spec §3.3). */
    val mediaWatcher = MediaWatcher(context, connectionManager, appScope).also { it.start() }

    /** Photo browsing for the Mac (spec §4); runs for the whole process. */
    val photosBridge = PhotosBridge(context, connectionManager, transferEngine, appScope).also { it.start() }

    /** Screen mirroring for the Mac (Plan 7); runs for the whole process. */
    val mirrorBridge = MirrorBridge(context, connectionManager, pairingStore, appScope).also { it.start() }

    fun pairingClient() = PairingClient(identity, httpClient)
}

class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    var setupDone: Boolean
        get() = prefs.getBoolean("setupDone", false)
        set(value) { prefs.edit().putBoolean("setupDone", value).apply() }

    /** Off after the user taps Disconnect or turns off "Keep connected"; the app then shows a Connect button. */
    private val _keepConnected = MutableStateFlow(prefs.getBoolean("keepConnected", true))

    /** Observable view of [keepConnected], so screens never act on a stale snapshot. */
    val keepConnectedFlow: StateFlow<Boolean> = _keepConnected.asStateFlow()

    var keepConnected: Boolean
        get() = _keepConnected.value
        set(value) {
            prefs.edit().putBoolean("keepConnected", value).apply()
            _keepConnected.value = value
        }

    var notificationsSkipped: Boolean
        get() = prefs.getBoolean("notificationsSkipped", false)
        set(value) { prefs.edit().putBoolean("notificationsSkipped", value).apply() }

    var photosStepDone: Boolean
        get() = prefs.getBoolean("photosStepDone", false)
        set(value) { prefs.edit().putBoolean("photosStepDone", value).apply() }
}
