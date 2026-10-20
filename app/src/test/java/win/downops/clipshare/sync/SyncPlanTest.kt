package win.downops.clipshare.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import win.downops.clipshare.settings.Prefs

class SyncPlanTest {

    @Test
    fun clientModeRunsLanClientOnly() {
        val plan = SyncPlan.resolve(Prefs.APP_MODE_CLIENT)
        assertFalse(plan.runServer)
        assertTrue(plan.runClient)
        assertFalse(plan.wifiDirect)
    }

    @Test
    fun serverModeRunsLanServerOnly() {
        val plan = SyncPlan.resolve(Prefs.APP_MODE_SERVER)
        assertTrue(plan.runServer)
        assertFalse(plan.runClient)
        assertFalse(plan.wifiDirect)
    }

    @Test
    fun wifiDirectModeUsesItsOwnStack() {
        val plan = SyncPlan.resolve(Prefs.APP_MODE_WIFI_DIRECT)
        assertFalse(plan.runServer)
        assertFalse(plan.runClient)
        assertTrue(plan.wifiDirect)
    }

    @Test
    fun unknownModeFallsBackToClient() {
        val plan = SyncPlan.resolve("something-else")
        assertTrue(plan.runClient)
        assertFalse(plan.runServer)
        assertFalse(plan.wifiDirect)
    }
}
