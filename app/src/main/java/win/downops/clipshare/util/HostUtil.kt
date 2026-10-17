package win.downops.clipshare.util

import win.downops.clipshare.settings.Prefs

/**
 * Utilities for normalizing host addresses discovered from the network so they
 * can safely be stored in preferences and used in WebSocket URLs.
 *
 * Android can report IPv6 link-local addresses with a scope/zone ID suffix such
 * as `fe80::1%wlan0`. OkHttp URLs do not accept `%`, and raw IPv6 addresses are
 * ambiguous when a port is appended, so discovery must sanitize and bracket
 * them.
 */
object HostUtil {

    /**
     * Returns a host string safe for use in a URL:
     *  - strips any interface/zone ID (`%wlan0`)
     *  - wraps bare IPv6 addresses in brackets
     *  - returns IPv4 addresses and hostnames unchanged
     */
    fun normalize(host: String): String {
        val h = host.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore("%")
            .trim()
        if (h.isBlank()) return h
        return if (isIPv6(h)) "[$h]" else h
    }

    /**
     * Returns a display-friendly host string without brackets, so settings and
     * the status card show `fe80::1` rather than `[fe80::1]`.
     */
    fun display(host: String): String {
        return host.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore("%")
            .trim()
    }

    /**
     * True when [host] is a bare IPv6 address (contains a colon and is not a
     * hostname with a port).
     */
    fun isIPv6(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        return h.contains(":") && h.all { it.isDigit() || it in ":abcdefABCDEF" }
    }

    /**
     * True when [host] is an IPv4 address.
     */
    fun isIPv4(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.contains(":") || h.contains("%")) return false
        val parts = h.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
    }

    /** Common Android/Windows Wi-Fi Direct group-owner subnets. */
    fun isWifiDirectAddress(host: String): Boolean {
        val parts = display(host).split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        return octets[0] == 192 && octets[1] == 168 && octets[2] in setOf(49, 137)
    }

    /**
     * Returns true when [host] matches the requested [ipVersion] preference.
     * Hostnames always pass; the preference only filters numeric IPv4/IPv6
     * addresses returned by discovery.
     */
    fun matchesIpVersion(host: String, ipVersion: String): Boolean {
        if (ipVersion == Prefs.IP_VERSION_ANY) return true
        val h = host.trim().removePrefix("[").removeSuffix("]")
        // Hostnames are not filtered.
        if (!isIPv4(h) && !isIPv6(h)) return true
        return when (ipVersion) {
            Prefs.IP_VERSION_IPV4 -> isIPv4(h)
            Prefs.IP_VERSION_IPV6 -> isIPv6(h)
            else -> true
        }
    }
}
