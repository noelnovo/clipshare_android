package win.downops.clipshare.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import win.downops.clipshare.MainActivity
import win.downops.clipshare.R
import win.downops.clipshare.clipboard.ClipboardDedup
import win.downops.clipshare.clipboard.ClipboardWriter
import win.downops.clipshare.history.ClipItem
import win.downops.clipshare.history.HistoryEntry
import win.downops.clipshare.history.image.ImageHistoryStore
import win.downops.clipshare.logs.Log
import win.downops.clipshare.state.AppState
import win.downops.clipshare.state.WifiDirectPeer
import win.downops.clipshare.util.Constants
import win.downops.clipshare.ws.Protocol

/**
 * Shared side-effects a [SyncMode] can trigger on the owning [SyncService]:
 * writing an inbound clipboard item and updating the foreground notification.
 */
class SyncEvents(private val context: Context) {

    fun receive(clip: Protocol.Clipboard) {
        val image = clip.image
        if (image != null) {
            Log.i("SyncService", "received ${image.size} byte ${clip.mime ?: "image"}")
            val mime = clip.mime ?: Constants.Mime.IMAGE_PNG
            ClipboardDedup.markRemoteWritten(image)
            val entry = AppState.onReceivedImage(context, image, mime, clip.from)
            setClipboardFromHistory(entry, mime)
        } else {
            val text = clip.text
            if (text != null) {
                Log.i("SyncService", "received ${text.length} chars")
                ClipboardDedup.markRemoteWritten(text.toByteArray(Charsets.UTF_8))
                writeClipboard(text)
                AppState.onReceived(context, text, clip.from)
            }
        }
    }

    fun updateNotification(text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(Constants.Notification.ID, buildNotification(text))
    }

    /**
     * Tile/background flow: there is no Activity to show a dialog, so post a
     * tappable notification that opens MainActivity on the certificate picker.
     * When the app is in the foreground the in-app dialog handles this instead.
     */
    fun certificateRequired(peer: WifiDirectPeer) {
        if (AppState.appInForeground) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.Notification.CERT_CHANNEL_ID,
                "ClipShare certificate selection",
                NotificationManager.IMPORTANCE_HIGH,
            )
            channel.description = "Choose a client certificate for a Wi-Fi Direct device"
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Constants.Extras.CERT_PEER_NAME, peer.name)
            putExtra(Constants.Extras.CERT_PEER_ADDRESS, peer.address)
        }
        val pi = PendingIntent.getActivity(
            context,
            Constants.Notification.CERT_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Constants.Notification.CERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clipboard)
            .setContentTitle("Choose a certificate")
            .setContentText("Tap to choose the client certificate for ${peer.name}")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(Constants.Notification.CERT_ID, notification)
        Log.i("SyncService", "posted certificate selection notification for ${peer.name}")
    }

    /** Builds the foreground notification; also used by [SyncService] at startup. */
    fun buildNotification(text: String): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, Constants.Notification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clipboard)
            .setContentTitle("ClipShare")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun writeClipboard(text: String) {
        // Label the clip as app-internal so capture paths skip it and we avoid
        // echoing received content back to peers (and avoid duplicate history).
        ClipboardWriter.writeText(context, Constants.Clipboard.INTERNAL_CLIP_LABEL, text)
    }

    /** Puts a received image on the clipboard from its persisted history file. */
    private fun setClipboardFromHistory(entry: HistoryEntry?, mime: String) {
        val clip = entry?.clip as? ClipItem.Image ?: return
        val file = ImageHistoryStore.imageFile(context, clip.imageId) ?: return
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        // Label the clip as app-internal so capture paths skip it and we avoid
        // echoing received content back to peers (and avoid duplicate history).
        ClipboardWriter.writeImage(context, Constants.Clipboard.INTERNAL_CLIP_LABEL, uri, mime)
        Log.i("SyncService", "placed received image on clipboard from history: ${file.name}")
    }
}
