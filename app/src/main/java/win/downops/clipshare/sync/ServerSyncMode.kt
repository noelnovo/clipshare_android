package win.downops.clipshare.sync

import android.content.Context
import win.downops.clipshare.certs.ServerCertManager
import win.downops.clipshare.discover.DiscoveryAdvertiser
import win.downops.clipshare.discover.WifiDirectManager
import win.downops.clipshare.discover.WifiDirectPolicy
import win.downops.clipshare.logs.Log
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.state.AppState
import win.downops.clipshare.state.WifiDirectPeer
import win.downops.clipshare.util.Constants
import win.downops.clipshare.ws.Protocol
import win.downops.clipshare.ws.WsServer

/**
 * Server-mode strategy: listens for inbound WebSocket connections from other
 * ClipShare clients and advertises itself via mDNS/UDP beacons.
 */
class ServerSyncMode(
    private val context: Context,
    private val events: SyncEvents,
    private val p2pOnly: Boolean = false,
    private val onPeers: (List<WifiDirectPeer>) -> Unit = {},
) : SyncMode {

    private var wsServer: WsServer? = null
    private var advertiser: DiscoveryAdvertiser? = null
    private var wifiDirect: WifiDirectManager? = null

    override fun start() {
        startServerMode()
    }

    override fun stop() {
        wsServer?.stop()
        wsServer = null
        advertiser?.stop()
        advertiser = null
        wifiDirect?.stop()
        wifiDirect = null
    }

    private fun startServerMode() {
        val port = Prefs.serverPort(context)
        // Wi-Fi Direct is mTLS-only. LAN server mode still honors the user's
        // server TLS switch.
        val tls = p2pOnly || Prefs.serverTlsEnabled(context)

        if (tls && !ServerCertManager.hasCerts(context)) {
            Log.i("SyncService", "generating server certificates")
            ServerCertManager.generate(context, Prefs.deviceName(context))
        }

        val keyStore = if (tls) ServerCertManager.loadKeyStore(context) else null
        if (tls && keyStore == null) {
            val msg = "Server TLS enabled but certificate failed to load"
            Log.e("SyncService", msg)
            AppState.onError(msg)
            events.updateNotification(msg)
            return
        }

        // mTLS is always required with server TLS: only client certificates
        // signed by this server's CA are accepted.
        val trustStore = if (tls) ServerCertManager.loadTrustStore(context) else null
        if (tls && trustStore == null) {
            val msg = "Server TLS enabled but CA trust store failed to load"
            Log.e("SyncService", msg)
            AppState.onError(msg)
            events.updateNotification(msg)
            return
        }

        val bindHost = if (p2pOnly) {
            // The Wi-Fi Direct group is IPv4-only.
            "0.0.0.0"
        } else {
            when (Prefs.serverBindIpVersion(context)) {
                Prefs.IP_VERSION_IPV4 -> "0.0.0.0"
                Prefs.IP_VERSION_IPV6 -> "::"
                else -> "0.0.0.0"
            }
        }
        val server = WsServer(
            port = port,
            bindHost = bindHost,
            deviceName = Prefs.deviceName(context),
            keyStore = keyStore,
            keyStorePassword = if (tls) Constants.Pkcs12.PASSWORD.toCharArray() else null,
            trustStore = trustStore,
            // The Wi-Fi Direct UI has no token field; mTLS is the identity.
            serverToken = if (p2pOnly) "" else Prefs.serverToken(context),
            onReceived = { from, clip ->
                events.receive(clip)
                broadcastReceived(clip, skipFrom = from)
            },
            onClientChange = { count ->
                AppState.onServerClientCountChanged(count)
                events.updateNotification(
                    if (p2pOnly) {
                        if (count == 0) "Waiting for nearby devices"
                        else "$count device${if (count == 1) "" else "s"} connected"
                    } else {
                        if (count == 0) "Server running on :$port (no clients)"
                        else "Server running on :$port ($count client${if (count == 1) "" else "s"})"
                    }
                )
            },
        )
        wsServer = server
        server.start()
        if (p2pOnly) {
            AppState.onWifiDirectWaiting()
            events.updateNotification("Waiting for nearby devices")
        } else {
            AppState.onServerStarted(port)
            events.updateNotification("Server running on :$port")
        }

        if (p2pOnly) {
            val refusal = WifiDirectPolicy.canStartServer(tls)
            if (refusal != null) {
                Log.w("SyncService", "$refusal; P2P group not created")
                events.updateNotification(refusal)
                AppState.onWifiDirectFailed(refusal)
            } else {
                val p2p = WifiDirectManager(
                    context = context,
                    onEndpoint = { _, _, _, _, _ -> },
                    fallbackPort = port,
                    fallbackTls = true,
                    onPeerList = onPeers,
                )
                wifiDirect = p2p
                p2p.startServer(Prefs.deviceName(context), port, true)
            }
            return
        }

        val beaconPort = Prefs.discoveryBeaconPort(context)
        val adv = DiscoveryAdvertiser(context, beaconPort)
        advertiser = adv
        adv.start(Prefs.deviceName(context), port, tls)
    }

    /** Broadcast a just-received clipboard item to every other connected client. */
    private fun broadcastReceived(clip: Protocol.Clipboard, skipFrom: String) {
        val server = wsServer ?: return
        val from = Prefs.deviceName(context)
        clip.text?.let { server.broadcast(it, from, skipFrom) }
        clip.image?.let { server.broadcastImage(it, clip.mime ?: Constants.Mime.IMAGE_PNG, from, skipFrom) }
    }

    override fun send(text: String): Boolean {
        val ok = wsServer?.broadcast(text, Prefs.deviceName(context)) == true
        SyncSend.text(context, text, ok)
        return ok
    }

    override fun sendImage(bytes: ByteArray, mime: String): Boolean {
        val ok = wsServer?.broadcastImage(bytes, mime, Prefs.deviceName(context)) == true
        SyncSend.image(context, mime, bytes, ok)
        return ok
    }
}
