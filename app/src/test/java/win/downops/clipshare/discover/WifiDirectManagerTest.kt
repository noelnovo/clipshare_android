package win.downops.clipshare.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDirectManagerTest {

    @Test
    fun automaticallySelectsOnlyPeerWhenNoNameIsConfigured() {
        assertEquals("DESKTOP-CVANPLA", WifiDirectManager.selectPeerName(listOf("DESKTOP-CVANPLA"), ""))
    }

    @Test
    fun doesNotGuessWhenMultiplePeersAreVisible() {
        assertNull(WifiDirectManager.selectPeerName(listOf("DESKTOP-CVANPLA", "Living Room TV"), ""))
    }

    @Test
    fun configuredPeerNameSelectsCaseInsensitively() {
        assertEquals("DESKTOP-CVANPLA", WifiDirectManager.selectPeerName(listOf("DESKTOP-CVANPLA"), "desktop-cvanpla"))
    }

    @Test
    fun configuredPeerNameMustMatchVisiblePeer() {
        assertNull(WifiDirectManager.selectPeerName(listOf("DESKTOP-CVANPLA"), "another-pc"))
    }
}
