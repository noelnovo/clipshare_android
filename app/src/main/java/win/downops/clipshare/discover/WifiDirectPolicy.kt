package win.downops.clipshare.discover

import win.downops.clipshare.util.HostUtil

/**
 * Pure Wi-Fi Direct mTLS policy shared by client and server modes.
 *
 * Wi-Fi Direct must be mTLS-only: the endpoint has to advertise TLS and the
 * client must hold a client certificate issued by that server's CA. These
 * helpers have no Android dependencies so they are unit-testable.
 */
object WifiDirectPolicy {
    const val TRANSPORT_WIFI_DIRECT = "wifi_direct"

    const val MESSAGE_PLAINTEXT_REFUSED = "Refusing plaintext Wi-Fi Direct; enable mTLS on both devices"
    const val MESSAGE_CLIENT_TLS_REQUIRED = "Wi-Fi Direct requires Client TLS (mTLS)"
    const val MESSAGE_CLIENT_CERT_REQUIRED = "Wi-Fi Direct requires a client certificate issued by this server"
    const val MESSAGE_SERVER_TLS_REQUIRED = "Wi-Fi Direct server mode requires Server TLS (mTLS)"

    fun isWifiDirectEndpoint(host: String, transport: String): Boolean {
        return transport == TRANSPORT_WIFI_DIRECT || HostUtil.isWifiDirectAddress(host)
    }

    /**
     * Returns the refusal message when a Wi-Fi Direct endpoint must not be
     * used, or null when it may proceed. `hasClientCert` reports whether the
     * app currently stores any client certificate.
     */
    fun refusalReason(tls: Boolean, hasClientCert: Boolean): String? {
        if (!tls) return MESSAGE_PLAINTEXT_REFUSED
        if (!hasClientCert) return MESSAGE_CLIENT_CERT_REQUIRED
        return null
    }

    /**
     * Interprets the DNS-SD `tls` TXT value of a Wi-Fi Direct peer. Wi-Fi Direct
     * is mTLS-only, so a missing or unrecognized value counts as TLS; only an
     * explicit `false` marks the endpoint as plaintext.
     */
    fun txtTlsFlag(value: String?): Boolean = value?.trim()?.lowercase() != "false"

    fun canStartClient(tlsEnabled: Boolean, hasClientCert: Boolean): String? {
        if (!tlsEnabled) return MESSAGE_CLIENT_TLS_REQUIRED
        if (!hasClientCert) return MESSAGE_CLIENT_CERT_REQUIRED
        return null
    }

    fun canStartServer(serverTlsEnabled: Boolean): String? {
        if (!serverTlsEnabled) return MESSAGE_SERVER_TLS_REQUIRED
        return null
    }
}
