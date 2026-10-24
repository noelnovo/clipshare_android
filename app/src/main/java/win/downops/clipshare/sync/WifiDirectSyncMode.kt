package win.downops.clipshare.sync

import android.content.Context
import win.downops.clipshare.certs.CertStore
import win.downops.clipshare.logs.Log
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.state.AppState
import win.downops.clipshare.state.WifiDirectPeer

/**
 * Wi-Fi Direct mode with no visible host/client role.
 *
 * The device starts as a group owner and advertises itself (host/wait state).
 * The main screen lists nearby P2P peers. If the user taps one, this mode
 * stops hosting and joins that peer as a client. When the client side gives up,
 * or the user chooses "Wait for devices", it returns to the host state.
 *
 * "Auto-connect" reconnects only to the last successfully used peer; it does
 * not pick an arbitrary nearby device.
 */
class WifiDirectSyncMode(
    private val context: Context,
    private val events: SyncEvents,
) : SyncMode {

    private var host: ServerSyncMode? = null
    private var client: ClientSyncMode? = null
    private var stopped = false
    private var autoConnectArmed = Prefs.wifiDirectAutoConnect(context)
    private var lastPeers: List<WifiDirectPeer> = emptyList()

    override fun start() {
        // Reconnect straight to the remembered peer when possible. The old
        // host-first flow created a group and tore it down a few seconds
        // later, which often left Android's P2P interface in a bad state.
        val lastAddress = Prefs.wifiDirectLastPeerAddress(context)
        val lastName = Prefs.wifiDirectLastPeerName(context)
        if (Prefs.wifiDirectAutoConnect(context) && lastAddress.isNotBlank()) {
            autoConnectArmed = false
            Log.i("SyncService", "wifi direct: auto-connecting to last peer $lastName")
            val peer = WifiDirectPeer(
                name = lastName.ifBlank { lastAddress },
                address = lastAddress,
                status = 0,
                isGroupOwner = false,
            )
            // If a certificate choice is needed, keep the service alive in
            // host/wait state and let the dialog/notification ask for it.
            if (!connectOrPrompt(peer)) startHost()
        } else {
            startHost()
        }
    }

    private fun startHost() {
        if (stopped) return
        stopClient()
        stopHost()
        AppState.onWifiDirectWaiting()
        if (lastPeers.isNotEmpty()) AppState.setWifiDirectPeers(lastPeers)
        val h = ServerSyncMode(
            context = context,
            events = events,
            p2pOnly = true,
            onPeers = { peers -> onPeers(peers) },
        )
        host = h
        h.start()
        Log.i("SyncService", "wifi direct: waiting for nearby devices")
    }

    private fun onPeers(peers: List<WifiDirectPeer>) {
        if (peers.isNotEmpty()) lastPeers = peers
        AppState.setWifiDirectPeers(peers)
        if (!autoConnectArmed || !Prefs.wifiDirectAutoConnect(context)) return
        if (AppState.serverClientCount.value > 0) return

        val lastAddress = Prefs.wifiDirectLastPeerAddress(context)
        val lastName = Prefs.wifiDirectLastPeerName(context)
        val peer = peers.firstOrNull {
            lastAddress.isNotBlank() && it.address.equals(lastAddress, ignoreCase = true)
        } ?: peers.firstOrNull {
            lastName.isNotBlank() && it.name.equals(lastName, ignoreCase = true)
        }
        if (peer != null) {
            autoConnectArmed = false
            Log.i("SyncService", "wifi direct: auto-connecting to last peer ${peer.name}")
            connectOrPrompt(peer)
        }
    }

    /**
     * Makes sure a client certificate is pinned for this peer before starting
     * the P2P client. If the user has several certificates and none is bound
     * to this MAC, it asks the UI (foreground) or posts a notification (tile).
     */
    private fun ensureCertSelected(peer: WifiDirectPeer): Boolean {
        val certs = CertStore.clientCerts(context)
        if (certs.isEmpty()) {
            val msg = "Wi-Fi Direct requires a client certificate. Import one in Settings."
            Log.w("SyncService", "$msg (${peer.name})")
            AppState.onWifiDirectFailed(msg)
            events.updateNotification(msg)
            return false
        }

        val selected = Prefs.wifiDirectCertId(context, peer.address, peer.name)
        if (selected != null && certs.any { it.id == selected }) return true
        if (selected != null) {
            // Certificate was deleted; forget the stale binding.
            Prefs.setWifiDirectCertId(context, peer.address, peer.name, null)
        }

        if (certs.size == 1) {
            Prefs.setWifiDirectCertId(context, peer.address, peer.name, certs[0].id)
            return true
        }

        Log.i("SyncService", "wifi direct: certificate selection needed for ${peer.name}")
        AppState.onCertificateRequired(peer)
        events.updateNotification("Choose a certificate for ${peer.name}")
        if (!AppState.appInForeground) events.certificateRequired(peer)
        return false
    }

    /** Returns true when a client connection was actually started. */
    private fun connectOrPrompt(peer: WifiDirectPeer): Boolean {
        if (!ensureCertSelected(peer)) return false
        startClient(peer)
        return true
    }

    /** Join the selected peer. The peer is the group owner for this session. */
    fun connectToPeer(peer: WifiDirectPeer) {
        if (stopped) return
        // Taps can arrive repeatedly while the P2P group is forming. Restarting
        // the client here removes the group that is about to complete, so keep
        // the first request and ignore duplicates.
        if (client != null && AppState.wifiDirectConnectingPeer.value != null) {
            Log.i(
                "SyncService",
                "wifi direct: connect already in progress to ${AppState.wifiDirectConnectingPeer.value}",
            )
            return
        }
        connectOrPrompt(peer)
    }

    private fun startClient(peer: WifiDirectPeer) {
        autoConnectArmed = false
        val keepPeers = AppState.wifiDirectPeers.value.ifEmpty { lastPeers }
        stopClient()
        stopHost()
        AppState.onWifiDirectConnecting(peer.name)
        // Do not make the list vanish while the client negotiates the group.
        AppState.setWifiDirectPeers(keepPeers)
        val c = ClientSyncMode(
            context = context,
            events = events,
            wifiDirectOnly = true,
            targetPeer = peer,
            onP2PFailed = { waitForPeers() },
        )
        client = c
        c.start()
    }

    /** Leave the selected peer and go back to advertising/waiting. */
    fun waitForPeers() {
        if (stopped) return
        autoConnectArmed = false
        AppState.clearPendingCert()
        startHost()
    }

    override fun stop() {
        stopped = true
        AppState.clearPendingCert()
        stopClient()
        stopHost()
        AppState.setWifiDirectPeers(emptyList())
    }

    override fun onStartCommand() {
        host?.onStartCommand()
        client?.onStartCommand()
    }

    override fun send(text: String): Boolean =
        host?.send(text) ?: client?.send(text) ?: false

    override fun sendImage(bytes: ByteArray, mime: String): Boolean =
        host?.sendImage(bytes, mime) ?: client?.sendImage(bytes, mime) ?: false

    private fun stopHost() {
        host?.stop()
        host = null
    }

    private fun stopClient() {
        client?.stop()
        client = null
    }
}
