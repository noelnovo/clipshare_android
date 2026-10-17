package win.downops.clipshare.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import win.downops.clipshare.settings.Prefs

class HostUtilTest {

    @Test
    fun normalizeStripsZoneId() {
        assertEquals("192.168.1.5", HostUtil.normalize("192.168.1.5"))
        assertEquals("[fe80::1]", HostUtil.normalize("fe80::1%wlan0"))
        assertEquals("[fe80::1]", HostUtil.normalize("fe80::1%eth0"))
        assertEquals("[::1]", HostUtil.normalize("::1"))
        assertEquals("[::1]", HostUtil.normalize("[::1]"))
    }

    @Test
    fun normalizeTrimsAndRemovesBrackets() {
        assertEquals("[fe80::1]", HostUtil.normalize("  [fe80::1]  "))
        assertEquals("desktop.local", HostUtil.normalize("desktop.local"))
    }

    @Test
    fun displayRemovesBracketsAndZoneId() {
        assertEquals("fe80::1", HostUtil.display("[fe80::1]"))
        assertEquals("fe80::1", HostUtil.display("fe80::1%wlan0"))
        assertEquals("192.168.1.5", HostUtil.display("192.168.1.5"))
    }

    @Test
    fun isIPv6DetectsBareAndBracketedAddresses() {
        assertTrue(HostUtil.isIPv6("fe80::1"))
        assertTrue(HostUtil.isIPv6("[fe80::1]"))
        assertTrue(HostUtil.isIPv6("::1"))
        assertFalse(HostUtil.isIPv6("192.168.1.5"))
        assertFalse(HostUtil.isIPv6("desktop.local"))
    }

    @Test
    fun isIPv4DetectsIpv4Addresses() {
        assertTrue(HostUtil.isIPv4("192.168.1.5"))
        assertTrue(HostUtil.isIPv4("10.0.0.1"))
        assertFalse(HostUtil.isIPv4("fe80::1"))
        assertFalse(HostUtil.isIPv4("desktop.local"))
    }

    @Test
    fun detectsCommonWifiDirectSubnets() {
        assertTrue(HostUtil.isWifiDirectAddress("192.168.49.1"))
        assertTrue(HostUtil.isWifiDirectAddress("192.168.137.1"))
        assertFalse(HostUtil.isWifiDirectAddress("192.168.1.10"))
        assertFalse(HostUtil.isWifiDirectAddress("desktop.local"))
    }

    @Test
    fun matchesIpVersionFiltersNumericAddresses() {
        assertTrue(HostUtil.matchesIpVersion("192.168.1.5", Prefs.IP_VERSION_IPV4))
        assertFalse(HostUtil.matchesIpVersion("[fe80::1]", Prefs.IP_VERSION_IPV4))

        assertTrue(HostUtil.matchesIpVersion("[fe80::1]", Prefs.IP_VERSION_IPV6))
        assertFalse(HostUtil.matchesIpVersion("192.168.1.5", Prefs.IP_VERSION_IPV6))

        assertTrue(HostUtil.matchesIpVersion("192.168.1.5", Prefs.IP_VERSION_ANY))
        assertTrue(HostUtil.matchesIpVersion("[fe80::1]", Prefs.IP_VERSION_ANY))

        // Hostnames are never filtered.
        assertTrue(HostUtil.matchesIpVersion("desktop.local", Prefs.IP_VERSION_IPV4))
    }
}
