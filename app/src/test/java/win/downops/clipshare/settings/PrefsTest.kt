package win.downops.clipshare.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrefsTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("clipshare_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsAreAppliedWhenNothingSet() {
        assertTrue(Prefs.deviceName(ctx).isNotBlank())
        assertEquals(Prefs.APP_MODE_CLIENT, Prefs.appMode(ctx))
        assertEquals("", Prefs.serverHost(ctx))
        assertEquals(40403, Prefs.serverPort(ctx))
        assertFalse(Prefs.serverTlsEnabled(ctx))
        assertEquals("", Prefs.token(ctx))
        assertTrue(Prefs.discoveryEnabled(ctx))
        assertEquals(40404, Prefs.discoveryBeaconPort(ctx))
        assertFalse(Prefs.tlsEnabled(ctx))
        assertEquals(Prefs.MODE_DISCOVER, Prefs.connectionMode(ctx))
        assertEquals(10240, Prefs.maxImagePayloadKb(ctx))
        assertEquals(256, Prefs.maxLogFileKb(ctx))
        assertEquals(50, Prefs.maxHistoryEntries(ctx))
        assertEquals(700L, Prefs.clipboardPollMs(ctx))
        assertEquals(0L, Prefs.serverModeStartedAt(ctx))
        assertFalse("logs should be disabled by default", Prefs.logsEnabled(ctx))
        assertFalse("Wi-Fi Direct should be disabled by default", Prefs.wifiDirectEnabled(ctx))
        assertEquals("", Prefs.wifiDirectPeerName(ctx))
        assertEquals("[]", Prefs.clientCertsJson(ctx))
        assertEquals("[]", Prefs.trustedCasJson(ctx))
        assertFalse("hostname verification should be off by default", Prefs.verifyHostname(ctx))
        assertEquals(emptyList<WhitelistEntry>(), Prefs.whitelist(ctx))
    }

    @Test
    fun settersRoundTripValues() {
        Prefs.setDeviceName(ctx, "Pixel 9")
        Prefs.setAppMode(ctx, Prefs.APP_MODE_SERVER)
        Prefs.setServerHost(ctx, " 192.168.1.10 ")
        Prefs.setServerPort(ctx, 5000)
        Prefs.setWifiDirectEnabled(ctx, true)
        assertTrue(Prefs.wifiDirectEnabled(ctx))
        Prefs.setWifiDirectPeerName(ctx, "  DESKTOP  ")
        assertEquals("DESKTOP", Prefs.wifiDirectPeerName(ctx))
        Prefs.setServerTlsEnabled(ctx, true)
        Prefs.setToken(ctx, "  abc123 ")
        Prefs.setDiscoveryEnabled(ctx, false)
        Prefs.setDiscoveryBeaconPort(ctx, 9090)
        Prefs.setTlsEnabled(ctx, true)
        Prefs.setConnectionMode(ctx, Prefs.MODE_WHITELIST)
        Prefs.setMaxImagePayloadKb(ctx, 256)
        Prefs.setMaxLogFileKb(ctx, 512)
        Prefs.setMaxHistoryEntries(ctx, 100)
        Prefs.setClipboardPollMs(ctx, 1200)
        Prefs.setClientCertsJson(ctx, """[{"alias":"c"}]""")
        Prefs.setTrustedCasJson(ctx, """[{"name":"ca"}]""")
        Prefs.setVerifyHostname(ctx, false)
        Prefs.setServerModeStartedAt(ctx, 123456789L)
        Prefs.setLogsEnabled(ctx, false)

        assertEquals("Pixel 9", Prefs.deviceName(ctx))
        assertEquals(Prefs.APP_MODE_SERVER, Prefs.appMode(ctx))
        assertEquals("192.168.1.10", Prefs.serverHost(ctx))
        assertEquals(5000, Prefs.serverPort(ctx))
        assertTrue(Prefs.serverTlsEnabled(ctx))
        assertEquals("abc123", Prefs.token(ctx))
        assertFalse(Prefs.discoveryEnabled(ctx))
        assertEquals(9090, Prefs.discoveryBeaconPort(ctx))
        assertTrue(Prefs.tlsEnabled(ctx))
        assertEquals(Prefs.MODE_WHITELIST, Prefs.connectionMode(ctx))
        assertEquals(256, Prefs.maxImagePayloadKb(ctx))
        assertEquals(512, Prefs.maxLogFileKb(ctx))
        assertEquals(100, Prefs.maxHistoryEntries(ctx))
        assertEquals(1200L, Prefs.clipboardPollMs(ctx))
        assertEquals("""[{"alias":"c"}]""", Prefs.clientCertsJson(ctx))
        assertEquals("""[{"name":"ca"}]""", Prefs.trustedCasJson(ctx))
        assertFalse("hostname verification should be off after setter", Prefs.verifyHostname(ctx))
        assertEquals(123456789L, Prefs.serverModeStartedAt(ctx))
        assertFalse(Prefs.logsEnabled(ctx))
    }

    @Test
    fun maxImagePayloadKbIsClamped() {
        Prefs.setMaxImagePayloadKb(ctx, 0)
        assertEquals(8, Prefs.maxImagePayloadKb(ctx))

        Prefs.setMaxImagePayloadKb(ctx, 99999)
        assertEquals(2048, Prefs.maxImagePayloadKb(ctx))
    }

    @Test
    fun maxLogFileKbIsClamped() {
        Prefs.setMaxLogFileKb(ctx, 1)
        assertEquals(16, Prefs.maxLogFileKb(ctx))

        Prefs.setMaxLogFileKb(ctx, 999999)
        assertEquals(4096, Prefs.maxLogFileKb(ctx))
    }

    @Test
    fun maxHistoryEntriesIsClamped() {
        Prefs.setMaxHistoryEntries(ctx, 1)
        assertEquals(5, Prefs.maxHistoryEntries(ctx))

        Prefs.setMaxHistoryEntries(ctx, 99999)
        assertEquals(1000, Prefs.maxHistoryEntries(ctx))
    }

    @Test
    fun clipboardPollMsIsClamped() {
        Prefs.setClipboardPollMs(ctx, 10)
        assertEquals(200L, Prefs.clipboardPollMs(ctx))

        Prefs.setClipboardPollMs(ctx, 99999)
        assertEquals(10_000L, Prefs.clipboardPollMs(ctx))
    }

    @Test
    fun discoveryBeaconPortIsAtLeastOne() {
        Prefs.setDiscoveryBeaconPort(ctx, 0)
        assertEquals(1, Prefs.discoveryBeaconPort(ctx))
    }

    @Test
    fun whitelistRoundTrips() {
        Prefs.setWhitelist(ctx, listOf(WhitelistEntry("laptop", "10.0.0.2"), WhitelistEntry("", "10.0.0.3")))

        val entries = Prefs.whitelist(ctx)
        assertEquals(2, entries.size)
        assertEquals("laptop", entries[0].name)
        assertEquals("10.0.0.2", entries[0].ip)
        assertEquals("", entries[1].name)
        assertEquals("10.0.0.3", entries[1].ip)
    }

    @Test
    fun whitelistIgnoresCorruptJson() {
        Prefs.setWhitelist(ctx, listOf(WhitelistEntry("a", "b")))
        ctx.getSharedPreferences("clipshare_prefs", Context.MODE_PRIVATE)
            .edit().putString("whitelist", "not-json").commit()

        assertEquals(emptyList<WhitelistEntry>(), Prefs.whitelist(ctx))
    }
}
