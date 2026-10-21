package win.downops.clipshare.sync

import android.content.Context
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
            connectToPeer(
                WifiDirectPeer(
                    name = lastName.ifBlank { lastAddress },
                    address = lastAddress,
                    status = 0,
                    isGroupOwner = false,
                ),
            )
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
            connectToPeer(peer)
        }
    }

    /** Join the selected peer. The peer is the group owner for this session. */
    fun connectToPeer(peer: WifiDirectPeer) {
        if (stopped) return
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
        startHost()
    }

    override fun stop() {
        stopped = true
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
