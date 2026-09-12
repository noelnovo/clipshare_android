package win.downops.clipshare.state

import android.content.Context
import android.content.Intent
import win.downops.clipshare.history.HistoryEntry
import win.downops.clipshare.history.HistoryManager
import win.downops.clipshare.logs.Log
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val source: String,
    val tls: Boolean = false,
)

/** App-wide state shared between the UI, service and tile. */
object AppState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    private val _appMode = MutableStateFlow(Prefs.APP_MODE_CLIENT)
    val appMode: StateFlow<String> = _appMode.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _status = MutableStateFlow("Stopped")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _serverName = MutableStateFlow<String?>(null)
    val serverName: StateFlow<String?> = _serverName.asStateFlow()

    private val _connectedIp = MutableStateFlow<String?>(null)
    val connectedIp: StateFlow<String?> = _connectedIp.asStateFlow()

    private val _transport = MutableStateFlow<String?>(null)
    val transport: StateFlow<String?> = _transport.asStateFlow()

    private val _serverClientCount = MutableStateFlow(0)
    val serverClientCount: StateFlow<Int> = _serverClientCount.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discovered: StateFlow<List<DiscoveredDevice>> = _discovered.asStateFlow()

    @Volatile
    var service: SyncService? = null
        private set

    /** True while the main activity is resumed. When foreground, ClipboardPusher
     * handles clipboard capture, so the accessibility service skips pushing to
     * avoid duplicate sends. */
    @Volatile
    var appInForeground = false

    private val _accessibilityConnected = MutableStateFlow(false)

    /** True while the accessibility service reports itself connected. This can
     * differ from the system setting (an app update silently drops the binding),
     * so the UI can warn when capture is expected but not actually running. */
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected.asStateFlow()

    fun onAccessibilityConnected() {
        _accessibilityConnected.value = true
    }

    fun onAccessibilityDisconnected() {
        _accessibilityConnected.value = false
    }

    fun startSync(ctx: Context) {
        if (_starting.value || _running.value || _stopping.value) return
        _starting.value = true
        _running.value = true
        _status.value = "Starting..."
        Log.i("AppState", "startSync")
        ctx.startForegroundService(Intent(ctx, SyncService::class.java))
    }

    fun stopSync(ctx: Context) {
        if (!_running.value && !_starting.value) return
        _stopping.value = true
        _starting.value = false
        _running.value = false
        _status.value = "Stopping..."
        _connected.value = false
        _serverName.value = null
        _connectedIp.value = null
        _transport.value = null
        _serverClientCount.value = 0
        Log.i("AppState", "stopSync")
        ctx.stopService(Intent(ctx, SyncService::class.java))
    }

    fun setAppMode(mode: String) {
        _appMode.value = mode
    }

    fun onServiceStarted(s: SyncService) {
        service = s
        _starting.value = false
        _running.value = true
        Log.i("AppState", "service started")
    }

    fun onServiceStopped() {
        service = null
        _starting.value = false
        _stopping.value = false
        _running.value = false
        _connected.value = false
        _serverName.value = null
        _connectedIp.value = null
        _transport.value = null
        _serverClientCount.value = 0
        _status.value = "Stopped"
        Log.i("AppState", "service stopped")
    }

    fun onConnecting() {
        _connected.value = false
        _status.value = "Connecting..."
        Log.i("AppState", "connecting")
    }

    fun onSearching() {
        _connected.value = false
        _status.value = "Searching for servers..."
        Log.i("AppState", "searching")
    }

    fun onConnected(name: String, host: String, transport: String? = null) {
        _connected.value = true
        _serverName.value = name
        _connectedIp.value = host
        _transport.value = transport
        _status.value = "Connected to $name"
        _lastError.value = null
        Log.i("AppState", "connected to $name at $host")
    }

    fun onDisconnected(reason: String?) {
        _connected.value = false
        _serverName.value = null
        _transport.value = null
        _status.value = if (reason.isNullOrBlank()) "Disconnected" else "Disconnected: $reason"
        Log.i("AppState", "disconnected: ${reason ?: "unknown"}")
    }

    fun onReconnecting(attempt: Int) {
        _connected.value = false
        _status.value = "Reconnecting (attempt $attempt)..."
        Log.i("AppState", "reconnecting attempt $attempt")
    }

    fun onError(msg: String?) {
        _lastError.value = msg
        if (msg != null && !_connected.value) {
            _status.value = "Error: $msg"
        }
        if (msg != null) Log.e("AppState", "error: $msg")
    }

    fun onServerStarted(port: Int) {
        _connected.value = true
        _status.value = "Server running on :$port"
        _lastError.value = null
        Log.i("AppState", "server started on port $port")
    }

    fun onServerClientCountChanged(count: Int) {
        _serverClientCount.value = count
    }

    fun onReceived(ctx: Context, text: String, from: String) {
        val entry = HistoryManager.addText(
            ctx = ctx,
            text = text,
            from = from,
            incoming = true,
        )
        _history.value = listOf(entry) + _history.value
    }

    fun onReceivedImage(ctx: Context, bytes: ByteArray, mime: String, from: String): HistoryEntry? {
        val entry = HistoryManager.addImage(
            ctx = ctx,
            bytes = bytes,
            mime = mime,
            from = from,
            incoming = true,
        )
        if (entry != null) {
            _history.value = listOf(entry) + _history.value
        }
        return entry
    }

    fun onSent(ctx: Context, text: String) {
        val entry = HistoryManager.addText(
            ctx = ctx,
            text = text,
            from = Prefs.deviceName(ctx),
            incoming = false,
        )
        _history.value = listOf(entry) + _history.value
    }

    fun onSentImage(ctx: Context, bytes: ByteArray, mime: String) {
        val entry = HistoryManager.addImage(
            ctx = ctx,
            bytes = bytes,
            mime = mime,
            from = Prefs.deviceName(ctx),
            incoming = false,
        )
        if (entry != null) {
            _history.value = listOf(entry) + _history.value
        }
    }

    fun loadHistory(ctx: Context) {
        _history.value = HistoryManager.load(ctx)
    }

    fun clearHistory(ctx: Context) {
        _history.value = emptyList()
        HistoryManager.clear(ctx)
    }

    fun setDiscovered(devices: List<DiscoveredDevice>) {
        _discovered.value = devices
    }

    internal fun resetForTesting() {
        _running.value = false
        _starting.value = false
        _stopping.value = false
        _appMode.value = Prefs.APP_MODE_CLIENT
        _connected.value = false
        _status.value = "Stopped"
        _serverName.value = null
        _connectedIp.value = null
        _transport.value = null
        _serverClientCount.value = 0
        _history.value = emptyList()
        _lastError.value = null
        _discovered.value = emptyList()
        _accessibilityConnected.value = false
        service = null
        appInForeground = false
    }

    internal fun setRunningForTesting(running: Boolean) {
        _running.value = running
    }
}
