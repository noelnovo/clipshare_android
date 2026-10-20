package win.downops.clipshare.discover

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.WpsInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import win.downops.clipshare.logs.Log
import win.downops.clipshare.state.WifiDirectPeer
import win.downops.clipshare.util.Constants
import java.net.InetAddress

/**
 * Owns the Android Wi-Fi P2P lifecycle used by ClipShare. P2P only provides an
 * endpoint; callers still use the normal ClipShare WebSocket protocol.
 */
class WifiDirectManager(
    private val context: Context,
    private val onEndpoint: (name: String, host: String, port: Int, tls: Boolean, network: Network?) -> Unit,
    private val peerName: String = "",
    private val fallbackPort: Int = Constants.Discovery.DEFAULT_SERVER_PORT,
    private val fallbackTls: Boolean = false,
    private val onState: (String) -> Unit = {},
    private val onPeerList: (List<WifiDirectPeer>) -> Unit = {},
    private val peerAddress: String = "",
) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var request: WifiP2pDnsSdServiceRequest? = null
    @Volatile
    private var running = false
    private var serverMode = false
    private var serviceName = ""
    private var servicePort = Constants.Discovery.DEFAULT_SERVER_PORT
    private var serviceTls = false
    private var target: WifiP2pDevice? = null
    private var targetEndpointReady = false
    private var targetName = ""
    private var targetPort = Constants.Discovery.DEFAULT_SERVER_PORT
    private var targetTls = false
    private var connecting = false
    private var peerDiscoveryStarted = false
    private var endpointReported = false
    private var resolvingEndpoint = false
    private var connectionGeneration = 0
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val peerFallback = Runnable {
        if (running && !serverMode && !connecting) beginPeerDiscovery()
    }

    fun startServer(name: String, port: Int, tls: Boolean) {
        if (!hasPermission()) {
            onState("Wi-Fi Direct permission required")
            return
        }
        serverMode = true
        serviceName = name
        servicePort = port
        serviceTls = tls
        startCommon()
        val ch = channel ?: return
        val record = mapOf(
            "name" to name,
            "port" to port.toString(),
            "tls" to tls.toString(),
            "platform" to Constants.Protocol.PLATFORM_ANDROID,
        )
        val info = WifiP2pDnsSdServiceInfo.newInstance(
            name,
            Constants.Discovery.SERVICE_TYPE,
            record,
        )
        // A previous session may have left a group behind (for example, after
        // an app update kills the process). createGroup then fails with BUSY.
        // Remove it first, then advertise and create the new group.
        manager.cancelConnect(ch, null)
        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = advertiseServiceAndCreateGroup(ch, info)
            override fun onFailure(reason: Int) = advertiseServiceAndCreateGroup(ch, info)
        })
    }

    private fun advertiseServiceAndCreateGroup(
        ch: WifiP2pManager.Channel,
        info: WifiP2pDnsSdServiceInfo,
    ) {
        manager.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onState("P2P service advertised")
                manager.createGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onState("Waiting for nearby devices")
                        startHostDiscovery()
                    }

                    override fun onFailure(reason: Int) {
                        Log.w("WifiDirectManager", "P2P group creation failed: $reason")
                        onState("P2P group creation failed")
                    }
                })
            }

            override fun onFailure(reason: Int) {
                Log.w("WifiDirectManager", "P2P service advertisement failed: $reason")
                onState("P2P service advertisement failed")
            }
        })
    }

    fun startClient() {
        if (!hasPermission()) {
            onState("Wi-Fi Direct permission required")
            return
        }
        serverMode = false
        startCommon()
        val ch = channel ?: return
        // Drop any group left over from a previous session; a stale group keeps
        // the P2P state machine busy and makes connect() fail with BUSY.
        manager.removeGroup(ch, null)
        manager.setDnsSdResponseListeners(
            ch,
            WifiP2pManager.DnsSdServiceResponseListener { _, registrationType, device ->
                if (registrationType == Constants.Discovery.SERVICE_TYPE) {
                    target = device
                    targetEndpointReady = true
                    targetName = device.deviceName
                    targetPort = fallbackPort
                    targetTls = fallbackTls
                    onState("Wi-Fi Direct device found")
                    requestPeers()
                }
            },
            WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
                if (device != null && record["platform"] != null) {
                    target = device
                    targetName = record["name"].orEmpty().ifBlank { device.deviceName }
                    targetPort = record["port"]?.toIntOrNull() ?: Constants.Discovery.DEFAULT_SERVER_PORT
                    targetTls = WifiDirectPolicy.txtTlsFlag(record["tls"])
                    targetEndpointReady = true
                    requestPeers()
                }
            },
        )
        request = WifiP2pDnsSdServiceRequest.newInstance()
        manager.addServiceRequest(ch, request, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onState("P2P service search started")
                manager.discoverServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onState("Searching for Wi-Fi Direct services")
                        handler.removeCallbacks(peerFallback)
                        handler.postDelayed(peerFallback, PEER_FALLBACK_DELAY_MS)
                    }

                    override fun onFailure(reason: Int) {
                        Log.w("WifiDirectManager", "P2P service discovery failed: $reason")
                        beginPeerDiscovery()
                    }
                })
            }

            override fun onFailure(reason: Int) {
                Log.w("WifiDirectManager", "P2P service request failed: $reason")
                beginPeerDiscovery()
            }
        })
    }

    fun stop() {
        running = false
        handler.removeCallbacks(peerFallback)
        handler.removeCallbacks(peerRefresh)
        onPeerList(emptyList())
        scope.cancel()
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        val ch = channel
        if (ch != null && hasPermission()) {
            request?.let { manager.removeServiceRequest(ch, it, null) }
            manager.clearServiceRequests(ch, null)
            manager.clearLocalServices(ch, null)
            manager.removeGroup(ch, null)
        }
        request = null
        channel?.close()
        channel = null
        target = null
        targetEndpointReady = false
        connecting = false
        peerDiscoveryStarted = false
        resolvingEndpoint = false
    }

    private fun startCommon() {
        if (running) return
        running = true
        channel = manager.initialize(context, Looper.getMainLooper(), null)
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!running) return
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> onState("Wi-Fi Direct state changed")
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                }
            }
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun connectToTarget() {
        val ch = channel ?: return
        val device = target ?: return
        if (!hasPermission() || connecting) return
        connecting = true
        // WifiP2pConfig.Builder is API 29+. The app supports API 26, so use the
        // legacy configuration object to keep P2P available on older devices.
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = CLIENT_GROUP_OWNER_INTENT
            wps.setup = WpsInfo.PBC
        }
        onState("Connecting over Wi-Fi Direct")
        // Keep the peer in WifiP2pService's peer cache through connect().
        // Android validates the config against that cache and rejects requests
        // for devices that disappeared when discovery was explicitly stopped.
        peerDiscoveryStarted = false
        // A previous negotiation that is still open makes connect() fail with
        // BUSY (reason 2). Cancel it first so the new request is accepted.
        manager.cancelConnect(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = issueConnect(ch, config)
            override fun onFailure(reason: Int) = issueConnect(ch, config)
        })
    }

    private fun issueConnect(ch: WifiP2pManager.Channel, config: WifiP2pConfig) {
        if (!running) {
            connecting = false
            return
        }
        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onState("P2P connection requested")
            }

            override fun onFailure(reason: Int) {
                connecting = false
                targetEndpointReady = false
                Log.w("WifiDirectManager", "P2P connection failed: $reason")
                onState("P2P connection failed")
                if (reason == WifiP2pManager.BUSY) {
                    manager.cancelConnect(ch, null)
                }
                handler.postDelayed(peerFallback, PEER_FALLBACK_DELAY_MS)
            }
        })
    }

    private fun beginPeerDiscovery() {
        val ch = channel ?: return
        if (!running || serverMode || connecting) return
        if (peerDiscoveryStarted) return
        handler.removeCallbacks(peerFallback)
        val expectedName = peerName.trim()
        onState(
            if (expectedName.isBlank()) "Searching for a nearby Wi-Fi Direct peer"
            else "Searching for Wi-Fi Direct peer $expectedName",
        )
        manager.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = discoverPeers(ch)
            override fun onFailure(reason: Int) = discoverPeers(ch)
        })
    }

    private fun discoverPeers(ch: WifiP2pManager.Channel) {
        if (!running || connecting) return
        peerDiscoveryStarted = true
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onState("Searching for Wi-Fi Direct peers")
                requestPeers()
            }

            override fun onFailure(reason: Int) {
                peerDiscoveryStarted = false
                Log.w("WifiDirectManager", "P2P peer discovery failed: $reason")
                onState("Wi-Fi Direct peer discovery failed")
            }
        })
    }

    private fun requestPeers() {
        val ch = channel ?: return
        if (!running || connecting) return
        if (!hasPermission()) return
        manager.requestPeers(ch) { peers: WifiP2pDeviceList ->
            if (!running || connecting) return@requestPeers
            publishPeers(peers)
            if (serverMode) return@requestPeers

            val currentTarget = target
            if (currentTarget != null) {
                if (!targetEndpointReady) return@requestPeers
                val availableTarget = peers.deviceList.firstOrNull {
                    it.deviceAddress.equals(currentTarget.deviceAddress, ignoreCase = true)
                }
                if (availableTarget != null) {
                    target = availableTarget
                    Log.i("WifiDirectManager", "P2P target is present in peer list: ${availableTarget.deviceName}")
                    connectToTarget()
                } else {
                    Log.w(
                        "WifiDirectManager",
                        "P2P service target is not yet in the peer list (${peers.deviceList.size} peer(s)); scanning peers",
                    )
                    beginPeerDiscovery()
                }
                return@requestPeers
            }

            val expected = peerName.trim()
            val selectedName = selectPeerName(peers.deviceList.map { it.deviceName }, expected)
            val device = if (peerAddress.isNotBlank()) {
                peers.deviceList.firstOrNull { it.deviceAddress.equals(peerAddress, ignoreCase = true) }
            } else {
                selectedName?.let { name -> peers.deviceList.firstOrNull { it.deviceName == name } }
            }
            if (device == null) {
                if (expected.isBlank() && peerAddress.isBlank() && peers.deviceList.size > 1) {
                    onState("Select a nearby device to connect")
                }
                return@requestPeers
            }
            target = device
            targetEndpointReady = true
            targetName = device.deviceName
            targetPort = fallbackPort
            targetTls = fallbackTls
            onState("Wi-Fi Direct peer found: ${device.deviceName}")
            connectToTarget()
        }
    }

    /** Publish the P2P peer list to the UI (host and client modes). */
    private fun publishPeers(peers: WifiP2pDeviceList) {
        val list = peers.deviceList
            // Wi-Fi Direct category 3 = printer, 7 = display/TV. Those peers
            // cannot speak the ClipShare protocol, so keep them out of the
            // nearby list.
            .filterNot { device ->
                val category = device.primaryDeviceType.substringBefore('-').toIntOrNull()
                category == 3 || category == 7
            }
            .map { device ->
                WifiDirectPeer(
                    name = device.deviceName.ifBlank { device.deviceAddress },
                    address = device.deviceAddress,
                    status = device.status,
                    isGroupOwner = device.isGroupOwner,
                )
            }
        onPeerList(list)
    }

    /** Host state: keep a P2P scan running so the user can pick a peer. */
    private fun startHostDiscovery() {
        val ch = channel ?: return
        if (!running || !serverMode) return
        handler.removeCallbacks(peerRefresh)
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onState("Searching for nearby devices")
                requestPeers()
            }

            override fun onFailure(reason: Int) {
                Log.w("WifiDirectManager", "host peer discovery failed: $reason")
                requestPeers()
            }
        })
        handler.postDelayed(peerRefresh, PEER_REFRESH_MS)
    }

    private val peerRefresh = object : Runnable {
        override fun run() {
            if (!running || !serverMode) return
            val ch = channel ?: return
            manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    requestPeers()
                }

                override fun onFailure(reason: Int) {
                    Log.d("WifiDirectManager", "host peer refresh failed: $reason")
                    requestPeers()
                }
            })
            handler.postDelayed(this, PEER_REFRESH_MS)
        }
    }

    private fun requestConnectionInfo() {
        val ch = channel ?: return
        if (!hasPermission()) return
        manager.requestConnectionInfo(ch) { info: WifiP2pInfo ->
            if (running && info.groupFormed) {
                val host = info.groupOwnerAddress?.hostAddress
                if (host != null && !serverMode) {
                    probeGroupOwner(host, fallbackPort)
                }
                if (host != null && !endpointReported && !resolvingEndpoint) {
                    resolvingEndpoint = true
                    val generation = ++connectionGeneration
                    val name = if (serverMode) serviceName else targetName
                    val port = if (serverMode) servicePort else targetPort
                    val tls = if (serverMode) serviceTls else targetTls
                    onState("Wi-Fi Direct connected")
                    scope.launch {
                        var network: Network? = null
                        for (attempt in 0 until NETWORK_LOOKUP_ATTEMPTS) {
                            network = findWiFiDirectNetwork(host)
                            if (network != null) break
                            delay(NETWORK_LOOKUP_INTERVAL_MS)
                        }
                        withContext(Dispatchers.Main) {
                            resolvingEndpoint = false
                            if (!running || generation != connectionGeneration || endpointReported) return@withContext
                            endpointReported = true
                            connecting = false
                            if (network == null) {
                                Log.w("WifiDirectManager", "P2P network not found for $host; using system route")
                            }
                            onEndpoint(name, host, port, tls, network)
                        }
                    }
                }
            } else if (running) {
                connectionGeneration++
                endpointReported = false
                connecting = false
                target = null
                targetEndpointReady = false
                peerDiscoveryStarted = false
                resolvingEndpoint = false
            }
        }
    }

    /**
     * Diagnostic: opens a plain, unbound TCP socket to the group owner and logs
     * whether the P2P route is usable from the app process.
     */
    private fun probeGroupOwner(host: String, port: Int) {
        scope.launch {
            val result = runCatching {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                    "connected local=${socket.localAddress?.hostAddress}"
                }
            }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }
            Log.i("WifiDirectManager", "P2P probe $host:$port $result")
        }
    }

    @Suppress("DEPRECATION")
    private fun findWiFiDirectNetwork(groupOwnerAddress: String): Network? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val address = runCatching { InetAddress.getByName(groupOwnerAddress) }.getOrNull() ?: return null
        return connectivity.allNetworks.firstOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
            val properties = connectivity.getLinkProperties(network) ?: return@firstOrNull false
            properties.routes.any { it.matches(address) }
        }
    }

    private fun hasPermission(): Boolean {
        val nearby = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
        val location = Build.VERSION.SDK_INT >= 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return nearby && location
    }

    companion object {
        private const val PEER_FALLBACK_DELAY_MS = 8_000L
        private const val PEER_REFRESH_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 5_000
        private const val NETWORK_LOOKUP_ATTEMPTS = 20
        private const val NETWORK_LOOKUP_INTERVAL_MS = 250L
        private const val CLIENT_GROUP_OWNER_INTENT = 0

        internal fun selectPeerName(peerNames: List<String>, configuredName: String): String? {
            val expected = configuredName.trim()
            if (expected.isNotEmpty()) {
                return peerNames.firstOrNull { it.equals(expected, ignoreCase = true) }
            }
            return peerNames.singleOrNull()
        }
    }
}
