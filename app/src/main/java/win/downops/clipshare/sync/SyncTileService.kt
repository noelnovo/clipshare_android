package win.downops.clipshare.sync

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import win.downops.clipshare.MainActivity
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.state.AppState
import win.downops.clipshare.util.Constants

/** Quick Settings tile that toggles the sync foreground service on/off. */
class SyncTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartListening() {
        super.onStartListening()
        observeJob?.cancel()
        observeJob = scope.launch {
            // Keep the tile in sync while the panel is open. Previously the
            // tile only refreshed on click/onStartListening, so "Stopping..."
            // stayed active until the panel was closed and reopened.
            combine(
                AppState.running,
                AppState.starting,
                AppState.stopping,
                AppState.connected,
                AppState.wifiDirectWaiting,
            ) { _, _, _, _, _ -> Unit }
                .combine(AppState.pendingCertPeer) { _, _ -> Unit }
                .combine(AppState.wifiDirectConnectingPeer) { _, _ -> Unit }
                .collect { updateTile() }
        }
        updateTile()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStopListening() {
        observeJob?.cancel()
        observeJob = null
        super.onStopListening()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onClick() {
        super.onClick()

        // A peer is waiting for a certificate choice: bring the app forward
        // instead of stopping the service.
        if (AppState.pendingCertPeer.value != null) {
            openApp()
            return
        }
        // Ignore rapid repeat taps while the service is transitioning. The
        // old toggle could stop and restart the service fast enough for the
        // new WsServer to race the old one for port 40403.
        if (AppState.starting.value || AppState.stopping.value) return

        if (AppState.running.value) {
            AppState.stopSync(applicationContext)
        } else {
            AppState.startSync(applicationContext)
        }
        updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            AppState.pendingCertPeer.value?.let { peer ->
                putExtra(Constants.Extras.CERT_PEER_NAME, peer.name)
                putExtra(Constants.Extras.CERT_PEER_ADDRESS, peer.address)
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this,
                Constants.Notification.CERT_ID,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = when {
            AppState.running.value -> Tile.STATE_ACTIVE
            AppState.starting.value -> Tile.STATE_ACTIVE
            AppState.stopping.value -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        val pending = AppState.pendingCertPeer.value
        val connecting = AppState.wifiDirectConnectingPeer.value
        tile.subtitle = when {
            pending != null -> "Select certificate"
            connecting != null -> "Connecting..."
            AppState.starting.value -> "Starting..."
            AppState.stopping.value -> "Stopping..."
            AppState.connected.value -> "Connected"
            AppState.running.value &&
                Prefs.appMode(this) == Prefs.APP_MODE_WIFI_DIRECT -> "Waiting"
            AppState.running.value -> "Idle"
            else -> "Off"
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
