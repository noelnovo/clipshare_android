package win.downops.clipshare.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectPolicyTest {

    @Test
    fun detectsWifiDirectEndpoints() {
        assertTrue(WifiDirectPolicy.isWifiDirectEndpoint("192.168.137.1", "lan"))
        assertTrue(WifiDirectPolicy.isWifiDirectEndpoint("192.168.49.10", "lan"))
        assertTrue(WifiDirectPolicy.isWifiDirectEndpoint("192.168.1.50", "wifi_direct"))
        assertFalse(WifiDirectPolicy.isWifiDirectEndpoint("192.168.1.50", "lan"))
    }

    @Test
    fun refusesPlaintextAndMissingClientCert() {
        assertEquals(
            WifiDirectPolicy.MESSAGE_PLAINTEXT_REFUSED,
            WifiDirectPolicy.refusalReason(false, true),
        )
        assertEquals(
            WifiDirectPolicy.MESSAGE_CLIENT_CERT_REQUIRED,
            WifiDirectPolicy.refusalReason(true, false),
        )
        assertNull(WifiDirectPolicy.refusalReason(true, true))
    }

    @Test
    fun gatesClientStartup() {
        assertEquals(
            WifiDirectPolicy.MESSAGE_CLIENT_TLS_REQUIRED,
            WifiDirectPolicy.canStartClient(false, true),
        )
        assertEquals(
            WifiDirectPolicy.MESSAGE_CLIENT_CERT_REQUIRED,
            WifiDirectPolicy.canStartClient(true, false),
        )
        assertNull(WifiDirectPolicy.canStartClient(true, true))
    }

    @Test
    fun txtTlsFlagDefaultsToTlsUnlessExplicitlyFalse() {
        assertTrue(WifiDirectPolicy.txtTlsFlag("true"))
        assertTrue(WifiDirectPolicy.txtTlsFlag(" TRUE "))
        assertTrue(WifiDirectPolicy.txtTlsFlag(null))
        assertTrue(WifiDirectPolicy.txtTlsFlag(""))
        assertFalse(WifiDirectPolicy.txtTlsFlag("false"))
        assertFalse(WifiDirectPolicy.txtTlsFlag("False"))
    }

    @Test
    fun gatesServerStartup() {
        assertEquals(
            WifiDirectPolicy.MESSAGE_SERVER_TLS_REQUIRED,
            WifiDirectPolicy.canStartServer(false),
        )
        assertNull(WifiDirectPolicy.canStartServer(true))
    }
}
