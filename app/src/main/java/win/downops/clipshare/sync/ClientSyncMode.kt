package win.downops.clipshare.sync

import android.content.Context
import android.net.Network
import win.downops.clipshare.certs.CertStore
import win.downops.clipshare.discover.DiscoveryManager
import win.downops.clipshare.discover.WifiDirectManager
import win.downops.clipshare.discover.WifiDirectPolicy
import win.downops.clipshare.logs.Log
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.state.AppState
import win.downops.clipshare.util.Constants
import win.downops.clipshare.util.HostUtil
import win.downops.clipshare.ws.Protocol
import win.downops.clipshare.ws.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Client-mode strategy: discovers desktop/Android servers and maintains a
 * WebSocket connection to the selected one (manual, discovered or whitelist).
 */
class ClientSyncMode(
    private val context: Context,
    private val events: SyncEvents,
) : SyncMode {

    private var ws: WsClient? = null
    private var discovery: DiscoveryManager? = null
    private var wifiDirect: WifiDirectManager? = null
    private val lock = Object()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    /** The daemon we are currently (or last) targeting. */
    @Volatile
    private var currentHost: String? = null

    @Volatile
    private var currentPort: Int = 0

    @Volatile
    private var currentTls: Boolean = false

    @Volatile
    private var currentTransport: String = "lan"

    @Volatile
    private var whitelistCandidates: List<Pair<String, Int>> = emptyList()

    @Volatile
    private var whitelistIndex = 0

    /** Normalized manual host from "Server (IP or hostname)" when set. */
    @Volatile
    private var manualTarget: String? = null

    /**
     * True while the manual host is the intended target (attempting to connect
     * or already connected). Discovery announcements are ignored until the
     * manual host is unreachable, so a filled host field always wins.
     */
    @Volatile
    private var manualActive = false

    override fun start() {
        running = true
        startDiscovery()
        connect()
    }

    override fun stop() {
        running = false
        scope.cancel()
        discovery?.stop()
        discovery = null
        wifiDirect?.stop()
        wifiDirect = null
        stopWs()
    }

    override fun onStartCommand() {
        val shouldConnect = synchronized(lock) { ws == null }
        if (shouldConnect) connect()
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    private fun startDiscovery() {
        if (SyncPlan.forContext(context).wifiDirectConnect) {
            val hasClientCert = CertStore.clientTlsWithCert(context) != null
            val refusal = WifiDirectPolicy.canStartClient(Prefs.tlsEnabled(context), hasClientCert)
            if (refusal != null) {
                Log.w("SyncService", "$refusal; P2P discovery skipped")
                AppState.onError(refusal)
                events.updateNotification(refusal)
            } else {
                val p2p = WifiDirectManager(
                    context = context,
                    onEndpoint = { name, host, port, tls, network ->
                        onDeviceFound(name, host, port, tls, WifiDirectPolicy.TRANSPORT_WIFI_DIRECT, network)
                    },
                    peerName = Prefs.wifiDirectPeerName(context),
                    fallbackPort = Prefs.serverPort(context),
                    fallbackTls = true,
                )
                wifiDirect = p2p
                p2p.startClient()
            }
        }
        if (Prefs.connectionMode(context) == Prefs.MODE_WHITELIST) {
            Log.i("SyncService", "discovery skipped: whitelist mode")
            return
        }
        if (!Prefs.discoveryEnabled(context)) {
            Log.i("SyncService", "discovery skipped: disabled")
            return
        }
        Log.i("SyncService", "starting discovery")
        val beaconPort = Prefs.discoveryBeaconPort(context)
        val ipVersion = Prefs.ipVersion(context)
        val d = DiscoveryManager(context, beaconPort, ipVersion) { name, host, port, tls, _ ->
            Log.i("SyncService", "discovered $name at $host:$port (tls=$tls)")
            onDeviceFound(name, host, port, tls, "lan")
        }
        discovery = d
        d.start()
    }

    /** Called from the discovery threads whenever a daemon announces itself. */
    private fun onDeviceFound(
        name: String,
        host: String,
        port: Int,
        tls: Boolean,
        source: String,
        network: Network? = null,
    ) {
        if (!running) return
        val isWifiDirect = WifiDirectPolicy.isWifiDirectEndpoint(host, source)
        if (isWifiDirect) {
            val refusal = WifiDirectPolicy.refusalReason(tls, CertStore.clientTlsWithCert(context) != null)
            if (refusal != null) {
                Log.e("SyncService", "$refusal ($host:$port)")
                AppState.onError(refusal)
                events.updateNotification(refusal)
                return
            }
        }
        // A filled manual host wins: only fall back to discovery once it is
        // unreachable or not configured.
        if (manualActive) return
        connectTo(
            host,
            port,
            tls,
            verifyName = false,
            persist = false,
            transport = if (isWifiDirect) "wifi_direct" else source,
            network = network,
        )
    }

    override fun switchTo(host: String, port: Int, tls: Boolean) {
        // Device taps are ephemeral and never rewrite the manual host field.
        manualActive = false
        connectTo(host, port, tls, verifyName = false, persist = false, transport = "manual")
    }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    private fun connect() {
        if (Prefs.connectionMode(context) == Prefs.MODE_WHITELIST) {
            startWhitelistConnections()
            return
        }
        val manual = Prefs.serverHost(context).takeIf { it.isNotBlank() }
        if (manual != null) {
            val normalized = HostUtil.normalize(manual)
            manualTarget = normalized
            manualActive = true
            Log.i("SyncService", "connecting to manual host $manual (tls=${Prefs.tlsEnabled(context)})")
            connectTo(normalized, Prefs.serverPort(context), Prefs.tlsEnabled(context), verifyName = false, persist = false)
        } else {
            manualTarget = null
            manualActive = false
            AppState.onSearching()
            events.updateNotification("Searching for servers...")
            Log.i("SyncService", "no host configured, searching via discovery")
        }
    }

    private fun connectTo(
        host: String,
        port: Int,
        tls: Boolean,
        verifyName: Boolean,
        persist: Boolean,
        transport: String = "lan",
        network: Network? = null,
    ) {
        val isWifiDirect = WifiDirectPolicy.isWifiDirectEndpoint(host, transport)
        if (isWifiDirect) {
            val refusal = WifiDirectPolicy.refusalReason(tls, CertStore.clientTlsWithCert(context) != null)
            if (refusal != null) {
                Log.e("SyncService", "$refusal ($host:$port)")
                AppState.onError(refusal)
                events.updateNotification(refusal)
                return
            }
        }
        val normalizedHost = HostUtil.normalize(host)
        synchronized(lock) {
            val sameEndpoint = currentHost == normalizedHost && currentPort == port && currentTls == tls
            if (sameEndpoint && ws != null) {
                // LAN mDNS becomes visible after a P2P group forms and can
                // report the same endpoint a few seconds before the P2P info
                // callback. Do not cancel an in-flight socket for a duplicate
                // discovery result. If Android has a bound P2P Network, replace
                // a LAN socket with one explicitly routed over that network.
                if (transport == "wifi_direct" && currentTransport != "wifi_direct" && network != null) {
                    Log.i("SyncService", "rebinding $host:$port WebSocket to the Wi-Fi Direct network")
                } else {
                    if (transport == "wifi_direct") currentTransport = "wifi_direct"
                    Log.d("SyncService", "already connected/connecting to $host:$port ($transport)")
                    return
                }
            }
            currentHost = normalizedHost
            currentPort = port
            currentTls = tls
            currentTransport = transport
            ws?.stop()
            val socket = buildSocket(normalizedHost, port, tls, verifyName, network)
            ws = socket
            if (socket != null) {
                if (persist) {
                    Prefs.setServerHost(context, normalizedHost)
                    Prefs.setServerPort(context, port)
                }
                AppState.onConnecting()
                events.updateNotification("Connecting to $host...")
                socket.start()
            } else {
                // A TLS failure must not block the discovery fallback.
                manualActive = false
            }
        }
    }

    private fun buildSocket(
        host: String,
        port: Int,
        tls: Boolean,
        verifyName: Boolean,
        network: Network? = null,
    ): WsClient? {
        val token = Prefs.token(context)
        val scheme = if (tls) Constants.Protocol.Scheme.WSS else Constants.Protocol.Scheme.WS
        var url = "$scheme://$host:$port${Constants.Protocol.WS_PATH}"
        if (token.isNotBlank()) {
            url += "?token=" + token
        }
        val hello = Protocol.hello(Prefs.deviceName(context), Constants.Protocol.PLATFORM_ANDROID, Constants.App.VERSION)
        Log.i("SyncService", "opening $url")

        val tlsConfig = if (tls) {
            val clientTls = CertStore.clientTlsWithCert(context) ?: CertStore.trustOnlyTls(context)
            if (clientTls == null) {
                val msg = "TLS on, but no client certificate or trusted CA imported"
                Log.e("SyncService", msg)
                AppState.onError(msg)
                events.updateNotification("TLS on, but no certificate or CA imported")
                onConnectFailed()
                return null
            }
            clientTls
        } else null

        lateinit var socket: WsClient
        socket = WsClient(
            url = url,
            hello = hello,
            tls = tlsConfig,
            onConnected = conn@{ name, _ ->
                synchronized(lock) { if (ws !== socket) return@conn }
                Log.i("SyncService", "connected to $name at $host")
                manualActive = currentHost == manualTarget
                if (verifyName && name.isNotBlank() && !whitelistNameMatches(name)) {
                    Log.w("SyncService", "name $name rejected by whitelist")
                    rejectCurrent()
                } else {
                    AppState.onConnected(name, host, currentTransport)
                    events.updateNotification("Connected to $host")
                }
            },
            onClipboard = clip@{ clip ->
                synchronized(lock) { if (ws !== socket) return@clip }
                events.receive(clip)
            },
            onDisconnected = disc@{ reason ->
                synchronized(lock) {
                    if (ws !== socket) return@disc
                }
                Log.i("SyncService", "disconnected: ${reason ?: "unknown"}")
                manualActive = false
                AppState.onDisconnected(reason)
                events.updateNotification("Disconnected")
            },
            onReconnecting = recon@{ attempt ->
                synchronized(lock) { if (ws !== socket) return@recon }
                Log.i("SyncService", "reconnecting attempt $attempt")
                manualActive = currentHost == manualTarget
                AppState.onReconnecting(attempt)
                events.updateNotification("Reconnecting ($host)...")
            },
            onConnectFailed = failed@{
                synchronized(lock) { if (ws !== socket) return@failed }
                Log.w("SyncService", "connect failed")
                manualActive = false
                onConnectFailed()
            },
            onError = err@{ msg ->
                synchronized(lock) { if (ws !== socket) return@err }
                Log.e("SyncService", "error: $msg")
                AppState.onError(msg)
            },
            verifyHostname = Prefs.verifyHostname(context),
            network = network,
        )
        return socket
    }

    /** A whitelisted daemon presented the wrong identity: drop and move on. */
    private fun rejectCurrent() {
        stopWs()
        onConnectFailed()
    }

    // ------------------------------------------------------------------
    // Whitelist mode
    // ------------------------------------------------------------------

    /** Whitelist mode: connect to each whitelisted IP in turn. */
    private fun startWhitelistConnections() {
        whitelistCandidates = Prefs.whitelist(context).mapNotNull {
            it.ip.trim().takeIf { ip -> ip.isNotBlank() }?.let { ip -> HostUtil.normalize(ip) to Prefs.serverPort(context) }
        }
        whitelistIndex = 0
        if (whitelistCandidates.isEmpty()) {
            AppState.onSearching()
            events.updateNotification("No whitelist IPs configured")
            return
        }
        connectToCandidate()
    }

    private fun connectToCandidate() {
        val candidates = whitelistCandidates
        if (candidates.isEmpty() || !running) return
        val idx = whitelistIndex % candidates.size
        whitelistIndex++
        val (host, port) = candidates[idx]
        connectTo(host, port, Prefs.tlsEnabled(context), verifyName = true, persist = false)
    }

    /** Advances to the next whitelist candidate after a failed attempt. */
    private fun onConnectFailed() {
        if (Prefs.connectionMode(context) != Prefs.MODE_WHITELIST) return
        if (whitelistCandidates.isEmpty()) return
        stopWs()
        scope.launch {
            delay(Constants.Whitelist.RETRY_DELAY_MS)
            if (running) connectToCandidate()
        }
    }

    private fun whitelistNameMatches(name: String): Boolean {
        val host = currentHost ?: return false
        val entry = Prefs.whitelist(context).firstOrNull { HostUtil.normalize(it.ip) == host }
            ?: return false
        return entry.name.isBlank() || entry.name == name
    }

    private fun stopWs() {
        synchronized(lock) {
            ws?.stop()
            ws = null
        }
    }

    // ------------------------------------------------------------------
    // Send
    // ------------------------------------------------------------------

    override fun send(text: String): Boolean {
        val ok = ws?.send(text, Prefs.deviceName(context)) == true
        SyncSend.text(context, text, ok)
        return ok
    }

    override fun sendImage(bytes: ByteArray, mime: String): Boolean {
        val socket = synchronized(lock) { ws }
        if (socket == null) {
            Log.w("SyncService", "sendImage: no active socket")
            return false
        }
        val ok = socket.sendImage(bytes, mime, Prefs.deviceName(context))
        SyncSend.image(context, mime, bytes, ok)
        return ok
    }
}
