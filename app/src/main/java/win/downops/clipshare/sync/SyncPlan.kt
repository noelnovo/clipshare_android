package win.downops.clipshare.sync

import android.content.Context
import win.downops.clipshare.settings.Prefs

/**
 * Decides which sync stacks run from the three independent settings: app mode,
 * Wi-Fi Direct enabled, and Wi-Fi Direct role. Pure so it can be unit tested.
 *
 * - Wi-Fi Direct *host* hosts a group, so the server must run (it is the group's
 *   WebSocket endpoint). This is the only way Wi-Fi Direct starts server mode.
 * - Wi-Fi Direct *connect* joins a Windows group owner as a client, so it runs
 *   on the client stack. Combined with server app mode it cannot run, and that
 *   conflict is reported instead of silently changing the mode.
 */
data class SyncPlan(
    val runServer: Boolean,
    val wifiDirectHost: Boolean,
    val wifiDirectConnect: Boolean,
    val wifiDirectConnectBlocked: Boolean,
) {
    companion object {
        /** The plan for the settings currently stored on this device. */
        fun forContext(ctx: Context): SyncPlan =
            resolve(Prefs.appMode(ctx), Prefs.wifiDirectEnabled(ctx), Prefs.wifiDirectRole(ctx))

        fun resolve(appMode: String, wifiDirectEnabled: Boolean, wifiDirectRole: String): SyncPlan {
            val hostsGroup = wifiDirectEnabled && wifiDirectRole == Prefs.WIFI_DIRECT_ROLE_HOST
            val connectsGroup = wifiDirectEnabled && wifiDirectRole == Prefs.WIFI_DIRECT_ROLE_CONNECT
            val runServer = appMode == Prefs.APP_MODE_SERVER || hostsGroup
            return SyncPlan(
                runServer = runServer,
                wifiDirectHost = hostsGroup,
                wifiDirectConnect = connectsGroup && !runServer,
                wifiDirectConnectBlocked = connectsGroup && runServer,
            )
        }
    }
}
