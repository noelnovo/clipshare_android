package win.downops.clipshare.discover

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import win.downops.clipshare.logs.Log
import win.downops.clipshare.util.Constants

/**
 * Owns the Android Wi-Fi P2P lifecycle used by ClipShare. P2P only provides an
 * endpoint; callers still use the normal ClipShare WebSocket protocol.
 */
class WifiDirectManager(
    private val context: Context,
    private val onEndpoint: (name: String, host: String, port: Int, tls: Boolean) -> Unit,
    private val onState: (String) -> Unit = {},
) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var request: WifiP2pDnsSdServiceRequest? = null
    private var running = false
    private var serverMode = false
    private var serviceName = ""
    private var servicePort = Constants.Discovery.DEFAULT_SERVER_PORT
    private var serviceTls = false
    private var target: WifiP2pDevice? = null
    private var targetName = ""
    private var targetPort = Constants.Discovery.DEFAULT_SERVER_PORT
    private var targetTls = false

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
        manager.addLocalService(ch, info, action("P2P service advertised"))
        manager.createGroup(ch, action("P2P group ready", "P2P group creation failed"))
    }

    fun startClient() {
        if (!hasPermission()) {
            onState("Wi-Fi Direct permission required")
            return
        }
        serverMode = false
        startCommon()
        val ch = channel ?: return
        manager.setDnsSdResponseListeners(
            ch,
            WifiP2pManager.DnsSdServiceResponseListener { _, registrationType, device ->
                if (registrationType == Constants.Discovery.SERVICE_TYPE) {
                    target = device
                    onState("Wi-Fi Direct device found")
                }
            },
            WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
                if (device != null && record["platform"] != null) {
                    target = device
                    targetName = record["name"].orEmpty().ifBlank { device.deviceName }
                    targetPort = record["port"]?.toIntOrNull() ?: Constants.Discovery.DEFAULT_SERVER_PORT
                    targetTls = record["tls"] == "true"
                    connectToTarget()
                }
            },
        )
        request = WifiP2pDnsSdServiceRequest.newInstance()
        manager.addServiceRequest(ch, request, action("P2P service search started"))
        manager.discoverServices(ch, action("Searching over Wi-Fi Direct", "P2P service search failed"))
    }

    fun stop() {
        running = false
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        val ch = channel
        if (ch != null && hasPermission()) {
            request?.let { manager.removeServiceRequest(ch, it, null) }
            manager.clearServiceRequests(ch, null)
            manager.clearLocalServices(ch, null)
            if (serverMode) manager.removeGroup(ch, null)
        }
        request = null
        channel?.close()
        channel = null
        target = null
    }

    private fun startCommon() {
        if (running) return
        running = true
        channel = manager.initialize(context, Looper.getMainLooper(), null)
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!running) return
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> onState("Wi-Fi Direct state changed")
                }
            }
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun connectToTarget() {
        val ch = channel ?: return
        val device = target ?: return
        if (!hasPermission()) return
        // WifiP2pConfig.Builder is API 29+. The app supports API 26, so use the
        // legacy configuration object to keep P2P available on older devices.
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        onState("Connecting over Wi-Fi Direct")
        manager.connect(ch, config, action("P2P connection requested", "P2P connection failed"))
    }

    private fun requestConnectionInfo() {
        val ch = channel ?: return
        if (!hasPermission()) return
        manager.requestConnectionInfo(ch) { info: WifiP2pInfo ->
            if (running && info.groupFormed) {
                val host = info.groupOwnerAddress?.hostAddress
                if (host != null) {
                    val name = if (serverMode) serviceName else targetName
                    val port = if (serverMode) servicePort else targetPort
                    val tls = if (serverMode) serviceTls else targetTls
                    onState("Wi-Fi Direct connected")
                    onEndpoint(name, host, port, tls)
                }
            }
        }
    }

    private fun action(success: String, failure: String = success): WifiP2pManager.ActionListener =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onState(success)
            }

            override fun onFailure(reason: Int) {
                Log.w("WifiDirectManager", "$failure: $reason")
                onState(failure)
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
}
