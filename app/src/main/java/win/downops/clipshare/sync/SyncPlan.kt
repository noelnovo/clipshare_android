package win.downops.clipshare.sync

import android.content.Context
import win.downops.clipshare.settings.Prefs

/**
 * Decides which sync stack runs from the single app-mode setting.
 *
 * - Client: LAN client (discovery/manual/whitelist + WebSocket client).
 * - Server: LAN server (WebSocket server + LAN advertisement).
 * - Wi-Fi Direct: managed by [WifiDirectSyncMode], which starts in host/wait
 *   state and switches to a P2P client only when the user selects a device.
 *
 * Kept as a small pure value object so the mode selection is unit-testable.
 */
data class SyncPlan(
    val runServer: Boolean,
    val runClient: Boolean,
    val wifiDirect: Boolean,
) {
    companion object {
        /** The plan for the settings currently stored on this device. */
        fun forContext(ctx: Context): SyncPlan = resolve(Prefs.appMode(ctx))

        fun resolve(appMode: String): SyncPlan = when (appMode) {
            Prefs.APP_MODE_SERVER -> SyncPlan(runServer = true, runClient = false, wifiDirect = false)
            Prefs.APP_MODE_WIFI_DIRECT -> SyncPlan(runServer = false, runClient = false, wifiDirect = true)
            else -> SyncPlan(runServer = false, runClient = true, wifiDirect = false)
        }
    }
}
