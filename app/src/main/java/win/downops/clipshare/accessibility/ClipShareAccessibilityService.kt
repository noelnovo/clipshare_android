package win.downops.clipshare.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import win.downops.clipshare.capture.ClipboardReadActivity
import win.downops.clipshare.clipboard.ClipboardSender
import win.downops.clipshare.logs.Log
import win.downops.clipshare.state.AppState

/**
 * Background clipboard capture.
 *
 * Android 10+ hides the clipboard from background apps, so instead this service
 * watches for the *copy* action itself:
 *
 *  - **Text copied after selecting**: the text is tracked via accessibility
 *    selection events and pushed directly — no screen flash.
 *  - **Everything else** (images, or text copied without highlighting): the
 *    system's "copied to clipboard" pill (localized labels such as
 *    "Text kopiert"/"Copy saved", matched case-insensitively) signals the copy.
 *    Since the clipboard can only be read by the focused app,
 *    [ClipboardReadActivity] briefly takes focus, reads the clipboard and sends
 *    its content.
 *
 * Merely selecting text never sends anything — a copy must be detected. The
 * service only pushes when the sync service is connected and the app is not in
 * the foreground. Enable it from Settings -> Accessibility -> ClipShare.
 */
@SuppressLint("AccessibilityPolicy")
class ClipShareAccessibilityService : AccessibilityService() {

    /** Text captured from selection events, sent when a copy is detected. */
    @Volatile
    private var pendingSelection: String? = null

    @Volatile
    private var lastSelectionAt: Long = 0L

    /** Debounces the multiple pill events that fire for a single copy. */
    @Volatile
    private var lastCopyAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.init(this)
        Log.i("Accessibility", "service connected")
        AppState.onAccessibilityConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val text = e.text.lastOrNull()?.toString()
                val from = e.fromIndex
                val to = e.toIndex
                if (text != null && from >= 0 && to >= 0 && to > from && to <= text.length) {
                    pendingSelection = text.substring(from, to)
                    lastSelectionAt = System.currentTimeMillis()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (e.packageName == "com.android.systemui" &&
                    isCopySignal(e.source?.contentDescription?.toString())
                ) {
                    onCopySignal()
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        AppState.onAccessibilityDisconnected()
        super.onDestroy()
    }

    /** Returns the selected text if it is fresh enough to pair with a copy. */
    private fun takePendingSelection(): String? {
        val text = pendingSelection
        val age = System.currentTimeMillis() - lastSelectionAt
        pendingSelection = null
        lastSelectionAt = 0L
        if (text == null || age > SELECTION_STALE_MS) return null
        return text
    }

    /** True when a systemui event is the "copied to clipboard" confirmation pill. */
    private fun isCopySignal(desc: String?): Boolean {
        if (desc == null) return false
        val d = desc.lowercase()
        return d.contains("kopier") || d.contains("copi") || d.contains("clipboard") ||
            d.contains("zwischenablage")
    }

    @Synchronized
    private fun onCopySignal() {
        if (AppState.appInForeground) return
        val now = System.currentTimeMillis()
        if (now - lastCopyAt < COPY_COOLDOWN_MS) return
        lastCopyAt = now

        val selection = takePendingSelection()
        if (!selection.isNullOrBlank()) {
            ClipboardSender.pushText(this, selection)
            return
        }

        // Image, or text copied without highlighting: read the clipboard from a
        // transparent activity that briefly takes focus.
        launchCaptureActivity()
    }

    private fun launchCaptureActivity() {
        if (ClipboardReadActivity.active.get()) {
            Log.d("Capture", "capture already running; skipping duplicate launch")
            return
        }
        try {
            val intent = Intent(this, ClipboardReadActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i("Capture", "launched capture activity")
        } catch (e: Exception) {
            Log.w("Capture", "capture activity launch failed: ${e.message}")
        }
    }

    private companion object {
        const val SELECTION_STALE_MS = 60_000L
        const val COPY_COOLDOWN_MS = 2_500L
    }
}