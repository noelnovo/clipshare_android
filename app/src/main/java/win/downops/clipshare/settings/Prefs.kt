package win.downops.clipshare.settings

import android.content.Context
import android.os.Build
import org.json.JSONObject
import win.downops.clipshare.util.Constants
import win.downops.clipshare.util.JsonList

/** One allowed device in whitelist mode. Matching is on name OR ip. */
data class WhitelistEntry(
    val name: String,
    val ip: String,
)

/** SharedPreferences-backed settings. */
object Prefs {
    const val MODE_DISCOVER = "discover"
    const val MODE_WHITELIST = "whitelist"

    const val APP_MODE_CLIENT = "client"
    const val APP_MODE_SERVER = "server"
    const val APP_MODE_WIFI_DIRECT = "wifi_direct"

    /** Wi-Fi Direct role: join a Windows group owner as a P2P client. */
    const val WIFI_DIRECT_ROLE_CONNECT = "connect"
    /** Wi-Fi Direct role: create a P2P group and host the server on it. */
    const val WIFI_DIRECT_ROLE_HOST = "host"

    const val IP_VERSION_ANY = "any"
    const val IP_VERSION_IPV4 = "ipv4"
    const val IP_VERSION_IPV6 = "ipv6"

    private const val FILE = "clipshare_prefs"

    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_APP_MODE = "app_mode"
    private const val KEY_SERVER_HOST = "server_host"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_SERVER_TLS_ENABLED = "server_tls_enabled"
    private const val KEY_SERVER_BIND_IP_VERSION = "server_bind_ip_version"
    private const val KEY_TOKEN = "token"
    private const val KEY_SERVER_TOKEN = "server_token"
    private const val KEY_SERVER_MODE_STARTED_AT = "server_mode_started_at"
    private const val KEY_DISCOVERY = "discovery_enabled"
    private const val KEY_DISCOVERY_BEACON_PORT = "discovery_beacon_port"
    private const val KEY_TLS_ENABLED = "tls_enabled"
    private const val KEY_CONNECTION_MODE = "connection_mode"
    private const val KEY_IP_VERSION = "ip_version"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_MAX_IMAGE_PAYLOAD_KB = "max_image_payload_kb"
    private const val KEY_MAX_LOG_FILE_KB = "max_log_file_kb"
    private const val KEY_MAX_HISTORY_ENTRIES = "max_history_entries"
    private const val KEY_CLIPBOARD_POLL_MS = "clipboard_poll_ms"
    private const val KEY_CLIENT_CERTS = "client_certs"
    private const val KEY_TRUSTED_CAS = "trusted_cas"
    private const val KEY_VERIFY_HOSTNAME = "verify_hostname"
    private const val KEY_LOGS_ENABLED = "logs_enabled"
    private const val KEY_WIFI_DIRECT = "wifi_direct_enabled"
    private const val KEY_WIFI_DIRECT_PEER_NAME = "wifi_direct_peer_name"
    private const val KEY_WIFI_DIRECT_ROLE = "wifi_direct_role"
    private const val KEY_WIFI_DIRECT_AUTO_CONNECT = "wifi_direct_auto_connect"
    private const val KEY_WIFI_DIRECT_LAST_PEER_NAME = "wifi_direct_last_peer_name"
    private const val KEY_WIFI_DIRECT_LAST_PEER_ADDRESS = "wifi_direct_last_peer_address"
    private const val KEY_WIFI_DIRECT_CERT_MAP = "wifi_direct_cert_map"


    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun deviceName(ctx: Context): String =
        prefs(ctx).getString(KEY_DEVICE_NAME, Build.MODEL) ?: Build.MODEL

    fun appMode(ctx: Context): String {
        val value = prefs(ctx).getString(KEY_APP_MODE, APP_MODE_CLIENT) ?: APP_MODE_CLIENT
        return if (value == APP_MODE_SERVER || value == APP_MODE_WIFI_DIRECT) value else APP_MODE_CLIENT
    }

    fun serverHost(ctx: Context): String =
        prefs(ctx).getString(KEY_SERVER_HOST, "") ?: ""

    fun serverPort(ctx: Context): Int = prefs(ctx).getInt(KEY_SERVER_PORT, Constants.Discovery.DEFAULT_SERVER_PORT)

    fun serverTlsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SERVER_TLS_ENABLED, false)

    fun serverBindIpVersion(ctx: Context): String {
        val value = prefs(ctx).getString(KEY_SERVER_BIND_IP_VERSION, IP_VERSION_ANY) ?: IP_VERSION_ANY
        return if (value in setOf(IP_VERSION_ANY, IP_VERSION_IPV4, IP_VERSION_IPV6)) value else IP_VERSION_ANY
    }

    fun token(ctx: Context): String = prefs(ctx).getString(KEY_TOKEN, "") ?: ""

    fun serverToken(ctx: Context): String = prefs(ctx).getString(KEY_SERVER_TOKEN, "") ?: ""

    fun serverModeStartedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_SERVER_MODE_STARTED_AT, 0L)

    fun discoveryEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DISCOVERY, true)

    fun discoveryBeaconPort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DISCOVERY_BEACON_PORT, Constants.Discovery.DEFAULT_BEACON_PORT)

    fun tlsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_TLS_ENABLED, false)

    fun connectionMode(ctx: Context): String =
        prefs(ctx).getString(KEY_CONNECTION_MODE, MODE_DISCOVER) ?: MODE_DISCOVER

    fun ipVersion(ctx: Context): String {
        val value = prefs(ctx).getString(KEY_IP_VERSION, IP_VERSION_ANY) ?: IP_VERSION_ANY
        return if (value in setOf(IP_VERSION_ANY, IP_VERSION_IPV4, IP_VERSION_IPV6)) value else IP_VERSION_ANY
    }

    fun maxImagePayloadKb(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MAX_IMAGE_PAYLOAD_KB, Constants.Image.DEFAULT_MAX_PAYLOAD_KB)

    fun maxLogFileKb(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MAX_LOG_FILE_KB, Constants.Log.DEFAULT_MAX_FILE_KB)

    fun maxHistoryEntries(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MAX_HISTORY_ENTRIES, Constants.History.DEFAULT_MAX_ENTRIES)

    fun clipboardPollMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_CLIPBOARD_POLL_MS, Constants.Clipboard.SYNC_POLL_MS)
            .coerceIn(Constants.Clipboard.MIN_POLL_MS, Constants.Clipboard.MAX_POLL_MS)

    fun clientCertsJson(ctx: Context): String =
        prefs(ctx).getString(KEY_CLIENT_CERTS, "[]") ?: "[]"

    fun trustedCasJson(ctx: Context): String =
        prefs(ctx).getString(KEY_TRUSTED_CAS, "[]") ?: "[]"

    fun verifyHostname(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VERIFY_HOSTNAME, false)

    fun logsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOGS_ENABLED, false)

    fun wifiDirectEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_WIFI_DIRECT, false)

    fun wifiDirectPeerName(ctx: Context): String =
        prefs(ctx).getString(KEY_WIFI_DIRECT_PEER_NAME, "") ?: ""

    /** Independent of app mode: connect (default) or host a Wi-Fi Direct group. */
    fun wifiDirectRole(ctx: Context): String {
        val value = prefs(ctx).getString(KEY_WIFI_DIRECT_ROLE, WIFI_DIRECT_ROLE_CONNECT) ?: WIFI_DIRECT_ROLE_CONNECT
        return if (value == WIFI_DIRECT_ROLE_HOST) WIFI_DIRECT_ROLE_HOST else WIFI_DIRECT_ROLE_CONNECT
    }

    /** Reconnect to the last successfully used Wi-Fi Direct peer automatically. */
    fun wifiDirectAutoConnect(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_WIFI_DIRECT_AUTO_CONNECT, true)

    fun wifiDirectLastPeerName(ctx: Context): String =
        prefs(ctx).getString(KEY_WIFI_DIRECT_LAST_PEER_NAME, "") ?: ""

    fun wifiDirectLastPeerAddress(ctx: Context): String =
        prefs(ctx).getString(KEY_WIFI_DIRECT_LAST_PEER_ADDRESS, "") ?: ""

    /**
     * Client certificate remembered for a Wi-Fi Direct peer. The P2P device
     * address (MAC) is the primary key; the peer name is a fallback for drivers
     * that rotate the address.
     */
    fun wifiDirectCertId(ctx: Context, address: String, name: String = ""): String? {
        val raw = prefs(ctx).getString(KEY_WIFI_DIRECT_CERT_MAP, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val direct = obj.optString(wifiDirectPeerKey(address, name), "")
            if (direct.isNotBlank()) return direct
            if (name.isNotBlank()) {
                val byName = obj.optString("name:" + name.trim().lowercase(), "")
                if (byName.isNotBlank()) return byName
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun setWifiDirectCertId(ctx: Context, address: String, name: String, certId: String?) {
        val raw = prefs(ctx).getString(KEY_WIFI_DIRECT_CERT_MAP, null)
        val obj = try {
            if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
        val keys = buildList {
            if (address.trim().isNotBlank()) add("mac:" + address.trim().lowercase())
            if (name.trim().isNotBlank()) add("name:" + name.trim().lowercase())
        }
        if (certId.isNullOrBlank()) {
            keys.forEach { obj.remove(it) }
        } else {
            keys.forEach { obj.put(it, certId) }
        }
        prefs(ctx).edit().putString(KEY_WIFI_DIRECT_CERT_MAP, obj.toString()).apply()
    }

    /** Drop every peer binding that points at a deleted client certificate. */
    fun removeWifiDirectCertMappings(ctx: Context, certId: String) {
        val raw = prefs(ctx).getString(KEY_WIFI_DIRECT_CERT_MAP, null) ?: return
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys().asSequence().filter { obj.optString(it) == certId }.toList()
            if (keys.isEmpty()) return
            keys.forEach { obj.remove(it) }
            prefs(ctx).edit().putString(KEY_WIFI_DIRECT_CERT_MAP, obj.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun wifiDirectPeerKey(address: String, name: String): String {
        val mac = address.trim().lowercase()
        if (mac.isNotBlank()) return "mac:$mac"
        return "name:" + name.trim().lowercase()
    }

    fun whitelist(ctx: Context): List<WhitelistEntry> {
        val raw = prefs(ctx).getString(KEY_WHITELIST, null) ?: return emptyList()
        return JsonList.parse(raw) {
            WhitelistEntry(it.optString("name"), it.optString("ip"))
        }
    }

    fun setDeviceName(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_DEVICE_NAME, value).apply()

    fun setAppMode(ctx: Context, value: String) {
        val mode = when (value) {
            APP_MODE_SERVER, APP_MODE_WIFI_DIRECT -> value
            else -> APP_MODE_CLIENT
        }
        prefs(ctx).edit()
            .putString(KEY_APP_MODE, mode)
            .putBoolean(KEY_WIFI_DIRECT, mode == APP_MODE_WIFI_DIRECT)
            .apply()
    }

    fun setServerHost(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_SERVER_HOST, value.trim()).apply()

    fun setServerPort(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_SERVER_PORT, value).apply()

    fun setServerTlsEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SERVER_TLS_ENABLED, value).apply()

    fun setServerBindIpVersion(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_SERVER_BIND_IP_VERSION, value).apply()

    fun setToken(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_TOKEN, value.trim()).apply()

    fun setServerToken(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_SERVER_TOKEN, value.trim()).apply()

    fun setServerModeStartedAt(ctx: Context, value: Long) =
        prefs(ctx).edit().putLong(KEY_SERVER_MODE_STARTED_AT, value).apply()

    fun setDiscoveryEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_DISCOVERY, value).apply()

    fun setDiscoveryBeaconPort(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_DISCOVERY_BEACON_PORT, value.coerceAtLeast(1)).apply()

    fun setTlsEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TLS_ENABLED, value).apply()

    fun setConnectionMode(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_CONNECTION_MODE, value).apply()

    fun setIpVersion(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_IP_VERSION, value).apply()

    fun setMaxImagePayloadKb(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_MAX_IMAGE_PAYLOAD_KB, value.coerceIn(Constants.Image.MIN_MAX_PAYLOAD_KB, Constants.Image.MAX_MAX_PAYLOAD_KB)).apply()

    fun setMaxLogFileKb(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_MAX_LOG_FILE_KB, value.coerceIn(Constants.Log.MIN_MAX_FILE_KB, Constants.Log.MAX_MAX_FILE_KB)).apply()

    fun setMaxHistoryEntries(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_MAX_HISTORY_ENTRIES, value.coerceIn(Constants.History.MIN_MAX_ENTRIES, Constants.History.MAX_MAX_ENTRIES)).apply()

    fun setClipboardPollMs(ctx: Context, value: Long) =
        prefs(ctx).edit().putLong(KEY_CLIPBOARD_POLL_MS, value.coerceIn(Constants.Clipboard.MIN_POLL_MS, Constants.Clipboard.MAX_POLL_MS)).apply()

    fun setClientCertsJson(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_CLIENT_CERTS, value).apply()

    fun setTrustedCasJson(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_TRUSTED_CAS, value).apply()

    fun setVerifyHostname(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_VERIFY_HOSTNAME, value).apply()

    fun setLogsEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_LOGS_ENABLED, value).apply()

    fun setWifiDirectEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_WIFI_DIRECT, value).apply()

    fun setWifiDirectRole(ctx: Context, value: String) =
        prefs(ctx).edit().putString(
            KEY_WIFI_DIRECT_ROLE,
            if (value == WIFI_DIRECT_ROLE_HOST) WIFI_DIRECT_ROLE_HOST else WIFI_DIRECT_ROLE_CONNECT,
        ).apply()

    fun setWifiDirectPeerName(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_WIFI_DIRECT_PEER_NAME, value.trim()).apply()

    fun setWifiDirectAutoConnect(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_WIFI_DIRECT_AUTO_CONNECT, value).apply()

    fun setWifiDirectLastPeer(ctx: Context, name: String, address: String) =
        prefs(ctx).edit()
            .putString(KEY_WIFI_DIRECT_LAST_PEER_NAME, name.trim())
            .putString(KEY_WIFI_DIRECT_LAST_PEER_ADDRESS, address.trim())
            .apply()

    fun clearWifiDirectLastPeer(ctx: Context) =
        prefs(ctx).edit()
            .remove(KEY_WIFI_DIRECT_LAST_PEER_NAME)
            .remove(KEY_WIFI_DIRECT_LAST_PEER_ADDRESS)
            .apply()

    fun setWhitelist(ctx: Context, entries: List<WhitelistEntry>) {
        prefs(ctx).edit().putString(
            KEY_WHITELIST,
            JsonList.build(entries) { obj, e -> obj.put("name", e.name).put("ip", e.ip) },
        ).apply()
    }
}
