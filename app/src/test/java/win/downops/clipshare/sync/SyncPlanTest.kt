package win.downops.clipshare.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import win.downops.clipshare.settings.Prefs

class SyncPlanTest {

    @Test
    fun wifiDirectOffDoesNotChangeStackChoice() {
        val client = SyncPlan.resolve(Prefs.APP_MODE_CLIENT, false, Prefs.WIFI_DIRECT_ROLE_HOST)
        assertFalse(client.runServer)
        assertFalse(client.wifiDirectHost)
        assertFalse(client.wifiDirectConnect)

        val server = SyncPlan.resolve(Prefs.APP_MODE_SERVER, false, Prefs.WIFI_DIRECT_ROLE_CONNECT)
        assertTrue(server.runServer)
        assertFalse(server.wifiDirectHost)
    }

    @Test
    fun connectRoleDoesNotRequireServerMode() {
        val plan = SyncPlan.resolve(Prefs.APP_MODE_CLIENT, true, Prefs.WIFI_DIRECT_ROLE_CONNECT)
        assertFalse(plan.runServer)
        assertTrue(plan.wifiDirectConnect)
        assertFalse(plan.wifiDirectHost)
        assertFalse(plan.wifiDirectConnectBlocked)
    }

    @Test
    fun hostRoleStartsServerWithoutServerAppMode() {
        val plan = SyncPlan.resolve(Prefs.APP_MODE_CLIENT, true, Prefs.WIFI_DIRECT_ROLE_HOST)
        assertTrue(plan.runServer)
        assertTrue(plan.wifiDirectHost)
        assertFalse(plan.wifiDirectConnect)
    }

    @Test
    fun serverModeWithConnectRoleIsReportedNotHidden() {
        val plan = SyncPlan.resolve(Prefs.APP_MODE_SERVER, true, Prefs.WIFI_DIRECT_ROLE_CONNECT)
        assertTrue(plan.runServer)
        assertFalse(plan.wifiDirectConnect)
        assertTrue(plan.wifiDirectConnectBlocked)
        assertFalse(plan.wifiDirectHost)
    }

    @Test
    fun serverModeWithHostRoleHostsGroup() {
        val plan = SyncPlan.resolve(Prefs.APP_MODE_SERVER, true, Prefs.WIFI_DIRECT_ROLE_HOST)
        assertTrue(plan.runServer)
        assertTrue(plan.wifiDirectHost)
        assertFalse(plan.wifiDirectConnectBlocked)
        assertEquals(false, plan.wifiDirectConnect)
    }
}
