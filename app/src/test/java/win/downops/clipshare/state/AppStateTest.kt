package win.downops.clipshare.state

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import win.downops.clipshare.history.ClipItem
import win.downops.clipshare.history.HistoryEntry
import win.downops.clipshare.history.HistoryStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppStateTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        AppState.resetForTesting()
    }

    @Test
    fun initialState() {
        assertTrue(!AppState.running.value)
        assertTrue(!AppState.connected.value)
        assertEquals("Stopped", AppState.status.value)
        assertNull(AppState.serverName.value)
        assertNull(AppState.connectedIp.value)
        assertNull(AppState.lastError.value)
        assertEquals(emptyList<HistoryEntry>(), AppState.history.value)
        assertEquals(emptyList<DiscoveredDevice>(), AppState.discovered.value)
    }

    @Test
    fun onConnectedSetsConnectionState() {
        AppState.onError("previous error")
        AppState.onConnected("desktop-pc", "192.168.1.5")

        assertTrue(AppState.connected.value)
        assertEquals("desktop-pc", AppState.serverName.value)
        assertEquals("192.168.1.5", AppState.connectedIp.value)
        assertEquals("Connected to desktop-pc", AppState.status.value)
        assertNull(AppState.lastError.value)
    }

    @Test
    fun onDisconnectedClearsConnectionState() {
        AppState.onConnected("desktop-pc", "192.168.1.5")
        AppState.onDisconnected("peer closed")

        assertTrue(!AppState.connected.value)
        assertNull(AppState.serverName.value)
        assertEquals("Disconnected: peer closed", AppState.status.value)
    }

    @Test
    fun onDisconnectedWithNullReasonUsesGenericStatus() {
        AppState.onDisconnected(null)
        assertEquals("Disconnected", AppState.status.value)
    }

    @Test
    fun onReconnectingMarksAttempt() {
        AppState.onReconnecting(3)

        assertTrue(!AppState.connected.value)
        assertTrue(AppState.status.value.contains("3"))
    }

    @Test
    fun onErrorSetsErrorWhenNotConnected() {
        AppState.onError("bad token")

        assertEquals("bad token", AppState.lastError.value)
        assertEquals("Error: bad token", AppState.status.value)
    }

    @Test
    fun onErrorDoesNotOverrideStatusWhenConnected() {
        AppState.onConnected("pc", "1.2.3.4")
        AppState.onError("transient")

        assertEquals("transient", AppState.lastError.value)
        assertEquals("Connected to pc", AppState.status.value)
    }

    @Test
    fun onServerStartedMarksConnected() {
        AppState.onServerStarted(40403)

        assertTrue(AppState.connected.value)
        assertEquals("Server running on :40403", AppState.status.value)
    }

    @Test
    fun onServerClientCountChangedTracksClients() {
        AppState.onServerClientCountChanged(2)
        assertEquals(2, AppState.serverClientCount.value)

        AppState.onServerClientCountChanged(0)
        assertEquals(0, AppState.serverClientCount.value)
    }

    @Test
    fun onServiceStoppedResetsState() {
        AppState.onConnected("pc", "1.2.3.4")
        AppState.onServerClientCountChanged(3)

        AppState.onServiceStopped()

        assertTrue(!AppState.running.value)
        assertTrue(!AppState.connected.value)
        assertNull(AppState.serverName.value)
        assertNull(AppState.connectedIp.value)
        assertEquals(0, AppState.serverClientCount.value)
        assertEquals("Stopped", AppState.status.value)
    }

    @Test
    fun wifiDirectConnectingStateTracksPeerUntilConnected() {
        AppState.onWifiDirectConnecting("backend")

        assertEquals("backend", AppState.wifiDirectConnectingPeer.value)
        assertEquals("Connecting to backend...", AppState.status.value)

        AppState.onConnected("backend", "192.168.49.1", "wifi_direct")
        assertNull(AppState.wifiDirectConnectingPeer.value)
    }

    @Test
    fun certificateRequestSurvivesHostWaitingAndClearsExplicitly() {
        val peer = WifiDirectPeer("backend", "4c:d5:77:2e:26:11", 0, isGroupOwner = false)

        AppState.onWifiDirectWaiting()
        AppState.onCertificateRequired(peer)

        assertEquals(peer, AppState.pendingCertPeer.value)
        assertEquals("Choose a certificate for backend", AppState.status.value)

        // Starting the host after the prompt must not hide the prompt.
        AppState.onWifiDirectWaiting()
        assertEquals(peer, AppState.pendingCertPeer.value)
        assertEquals("Choose a certificate for backend", AppState.status.value)

        AppState.clearPendingCert()
        assertNull(AppState.pendingCertPeer.value)
        assertEquals("Waiting for nearby devices", AppState.status.value)
    }

    @Test
    fun onReceivedPrependsIncomingHistory() {
        AppState.onReceived(ctx, "hello from desktop", "desktop")

        val history = AppState.history.value
        assertEquals(1, history.size)
        assertEquals("hello from desktop", (history[0].clip as ClipItem.Text).text)
        assertEquals("desktop", history[0].from)
        assertTrue(history[0].incoming)
        assertTrue(!history[0].isImage)
    }

    @Test
    fun onReceivedImagePrependsImageEntry() {
        val bytes = pngBytes()
        AppState.onReceivedImage(ctx, bytes, "image/png", "desktop")

        val history = AppState.history.value
        assertEquals(1, history.size)
        assertTrue(history[0].isImage)
        val clip = history[0].clip as ClipItem.Image
        assertEquals("image/png", clip.mime)
    }

    private fun pngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLUE)
        return java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }.also { bmp.recycle() }
    }

    @Test
    fun onSentPrependsOutgoingEntry() {
        AppState.onSent(ctx, "local note")

        val history = AppState.history.value
        assertEquals(1, history.size)
        assertEquals("local note", (history[0].clip as ClipItem.Text).text)
        assertTrue(!history[0].incoming)
        assertTrue(history[0].from.isNotBlank())
    }

    @Test
    fun historyKeepsNewestFirst() {
        AppState.onReceived(ctx, "one", "a")
        AppState.onReceived(ctx, "two", "b")

        assertEquals(listOf("two", "one"), AppState.history.value.map { (it.clip as ClipItem.Text).text })
    }

    @Test
    fun clearHistoryClearsFlowAndStore() {
        AppState.onReceived(ctx, "one", "a")
        AppState.onReceived(ctx, "two", "b")

        AppState.clearHistory(ctx)

        assertEquals(emptyList<HistoryEntry>(), AppState.history.value)
        assertEquals(emptyList<HistoryEntry>(), HistoryStore.load(ctx))
    }

    @Test
    fun setAppModeAndDiscoveredUpdateFlows() {
        AppState.setAppMode("server")
        assertEquals("server", AppState.appMode.value)

        AppState.setDiscovered(listOf(DiscoveredDevice("pc", "1.2.3.4", 40403, "beacon")))
        assertEquals(1, AppState.discovered.value.size)
        assertEquals("pc", AppState.discovered.value[0].name)
    }

    @Test
    fun rapidTogglesStayConsistent() {
        repeat(20) {
            AppState.startSync(ctx)
            AppState.stopSync(ctx)
        }

        // Simulate the service finishing its async shutdown.
        AppState.onServiceStopped()

        assertTrue(!AppState.starting.value)
        assertTrue(!AppState.stopping.value)
        assertTrue(!AppState.running.value)
        assertTrue(!AppState.connected.value)
        assertEquals("Stopped", AppState.status.value)
    }

    @Test
    fun startSyncIgnoresDuplicateRequestsWhileStarting() {
        AppState.startSync(ctx)
        AppState.startSync(ctx)
        AppState.startSync(ctx)

        assertTrue(AppState.starting.value)
        assertTrue(AppState.running.value)
    }

    @Test
    fun stopSyncIgnoresRequestsWhenAlreadyStopped() {
        AppState.stopSync(ctx)
        AppState.stopSync(ctx)

        assertTrue(!AppState.starting.value)
        assertTrue(!AppState.running.value)
    }
}