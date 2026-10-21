package win.downops.clipshare.sync

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
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.state.AppState

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
            ) { _, _, _, _, _ -> Unit }.collect { updateTile() }
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
        if (AppState.running.value) {
            AppState.stopSync(applicationContext)
        } else {
            AppState.startSync(applicationContext)
        }
        updateTile()
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
        tile.subtitle = when {
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
