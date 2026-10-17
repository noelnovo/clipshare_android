package win.downops.clipshare.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import win.downops.clipshare.certs.CertStore
import win.downops.clipshare.logs.Log
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.state.AppState
import win.downops.clipshare.util.Constants

/**
 * Foreground service that runs in either client or server mode.
 *
 * The mode-specific logic lives in [ClientSyncMode] and [ServerSyncMode],
 * chosen here from the configured app mode. This class only coordinates:
 * it starts the active mode, exposes send/switchTo to the UI and tile, and
 * owns the shared clipboard-receive + notification behavior ([SyncEvents]).
 */
class SyncService : Service() {

    private var mode: SyncMode? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.init(this)
        createChannel()
        val events = SyncEvents(this)
        startForeground(Constants.Notification.ID, events.buildNotification("Starting..."))
        Log.i("SyncService", "onCreate")
        AppState.onServiceStarted(this)
        AppState.setAppMode(Prefs.appMode(this))

        // Client certs have no value in server mode: drop them once the
        // retention window has lapsed (also enforced from the settings screen).
        if (Prefs.appMode(this) == Prefs.APP_MODE_SERVER) {
            if (CertStore.purgeClientSecretsIfServerModeExpired(this)) {
                Log.i("SyncService", "purged client certificates (server-mode retention)")
            }
        }

        val plan = SyncPlan.forContext(this)
        if (plan.wifiDirectConnectBlocked) {
            val msg = "Wi-Fi Direct connect needs Client mode; switch the app mode or use Wi-Fi Direct host"
            Log.w("SyncService", msg)
            events.updateNotification(msg)
        }
        val m = if (plan.runServer) {
            ServerSyncMode(this, events)
        } else {
            ClientSyncMode(this, events)
        }
        mode = m
        scope.launch {
            try {
                m.start()
            } catch (e: Exception) {
                Log.e("SyncService", "mode start failed", e)
                AppState.onError("Service failed to start: ${e.message}")
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("SyncService", "onStartCommand")
        mode?.onStartCommand()
        return START_STICKY
    }

    // ------------------------------------------------------------------
    // Delegation to the active mode
    // ------------------------------------------------------------------

    /** Push local clipboard text to peers. Called from the app UI, the
     * accessibility service or the quick-settings tile while connected. */
    fun send(text: String): Boolean = mode?.send(text) == true

    /** Push a local image to peers. */
    fun sendImage(bytes: ByteArray, mime: String): Boolean =
        mode?.sendImage(bytes, mime) == true

    /**
     * Manually switch to a specific daemon (e.g. user taps a device in the UI).
     * No-op in server mode.
     */
    fun switchTo(host: String, port: Int, tls: Boolean) {
        mode?.switchTo(host, port, tls)
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                Constants.Notification.CHANNEL_ID, "ClipShare sync", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Clipboard sync and server"
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        val m = mode
        mode = null
        scope.launch {
            try {
                m?.stop()
            } catch (e: Exception) {
                Log.e("SyncService", "mode stop failed", e)
            }
            scope.cancel()
        }
        AppState.onServiceStopped()
        Log.i("SyncService", "onDestroy")
        super.onDestroy()
    }
}