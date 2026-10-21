package win.downops.clipshare.capture

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import win.downops.clipshare.clipboard.ClipboardSender
import win.downops.clipshare.clipboard.ClipboardWriter
import win.downops.clipshare.logs.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transparent activity used to capture background copies that have no text
 * selection — images, or text copied via a copy button. Android only grants
 * clipboard access to the currently focused app, so this activity briefly takes
 * focus, reads the clipboard and sends its content, then finishes immediately.
 */
class ClipboardReadActivity : Activity() {

    private val handled = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    private val timeout = Runnable { finishWithoutAnimation() }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active.set(true)
        // This activity only steals focus long enough to read the clipboard.
        // Suppress its transition/dim so a background copy does not visually
        // feel like a second copy.
        overridePendingTransition(0, 0)
        window?.setDimAmount(0f)
        // Safety net: never linger on screen if focus is not granted quickly.
        handler.postDelayed(timeout, 1500)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed({ readAndFinish() }, 150)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) readAndFinish()
    }

    private fun readAndFinish() {
        if (!handled.compareAndSet(false, true)) return
        handler.removeCallbacks(timeout)
        val cm = ClipboardWriter.manager(this)
        val payload = ClipboardSender.payloadOf(this, cm.primaryClip)
        if (payload != null) {
            Log.i("Capture", "captured ${payload.fingerprint}")
            payload.send(this)
        } else {
            Log.d("Capture", "clipboard empty, nothing to capture")
        }
        finishWithoutAnimation()
    }

    @Suppress("DEPRECATION")
    private fun finishWithoutAnimation() {
        overridePendingTransition(0, 0)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        active.set(false)
        super.onDestroy()
    }

    companion object {
        /** True while a capture activity is already reading the clipboard. */
        val active = AtomicBoolean(false)
    }
}