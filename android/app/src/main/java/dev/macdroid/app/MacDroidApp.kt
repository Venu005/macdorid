package dev.macdroid.app

import android.app.Application
import android.content.Context
import dev.macdroid.app.platform.DeviceInfo
import dev.macdroid.app.platform.KeystoreIdentity
import dev.macdroid.app.platform.NsdMacLocator
import dev.macdroid.app.platform.PrefsPairingStore
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.pairing.PairingClient
import dev.macdroid.core.pairing.PairingStore
import dev.macdroid.core.protocol.HelloBody
import dev.macdroid.core.tls.ClientIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        hello = { HelloBody(app = BuildConfig.VERSION_NAME, device = DeviceInfo.name(context)) },
        baseClient = httpClient,
    )

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
}
