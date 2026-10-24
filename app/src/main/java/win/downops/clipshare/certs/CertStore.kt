package win.downops.clipshare.certs

import android.content.Context
import android.net.Uri
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.util.Constants
import win.downops.clipshare.util.JsonList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.GZIPInputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Pair of the client [SSLContext] and the trust manager it was built with. */
data class ClientTls(
    val sslContext: SSLContext,
    val trustManager: X509TrustManager,
)

/** Metadata for an imported client PKCS#12 certificate. */
data class ClientCertInfo(
    val id: String,
    val caSubject: String,
    val fingerprint: String,
    /** User-visible name, e.g. "Windows desktop" or "Pixel 9". Empty for old imports. */
    val label: String = "",
)

/** Metadata for a trusted CA certificate. */
data class TrustedCaInfo(
    val id: String,
    val subject: String,
    val fingerprint: String,
)

/**
 * Manages client certificates (PKCS#12) and trusted CA certificates used for
 * TLS connections to desktop daemons and Android servers.
 *
 * Supports multiple client certificates. The correct one is selected by
 * matching the issuer CA of the client cert to the CA that signed the server's
 * certificate.
 */
object CertStore {

    private const val CLIENT_CERTS_DIR = "certs/clients"
    private const val TRUSTED_CA_DIR = "certs/trusted"

    // ------------------------------------------------------------------
    // Client certificates
    // ------------------------------------------------------------------

    fun clientCerts(context: Context): List<ClientCertInfo> {
        return loadClientCertsRaw(context)
    }

    private fun loadClientCertsRaw(context: Context): List<ClientCertInfo> {
        return parseClientCerts(Prefs.clientCertsJson(context))
    }

    /** Imports a client .p12 from a content Uri. Its CA is auto-trusted. */
    fun importClientP12(context: Context, uri: Uri): Boolean {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        } catch (_: Exception) {
            return false
        }
        return importClientP12Bytes(context, bytes)
    }

    /** Imports a client .p12 from raw bytes (file picker). */
    fun importClientP12Bytes(context: Context, bytes: ByteArray): Boolean {
        val dest = File(context.filesDir, "$CLIENT_CERTS_DIR/${newId()}.p12")
        return try {
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)

            val ks = loadP12(dest)
            if (ks.size() == 0) {
                dest.delete()
                return false
            }

            val (caSubject, fingerprint) = extractCaInfo(ks) ?: ("" to "")
            val info = ClientCertInfo(
                id = dest.nameWithoutExtension,
                caSubject = caSubject,
                fingerprint = fingerprint,
            )
            saveClientCerts(context, clientCerts(context) + info)

            // Auto-trust the CA contained in this bundle.
            val ca = extractCaCertificate(ks)
            if (ca != null && !hasTrustedCa(context, fingerprint)) {
                importTrustedCaFromPem(context, ca.toPem())
            }
            true
        } catch (_: Exception) {
            dest.delete()
            false
        }
    }

    fun deleteClientCert(context: Context, id: String) {
        File(context.filesDir, "$CLIENT_CERTS_DIR/$id.p12").delete()
        saveClientCerts(context, clientCerts(context).filter { it.id != id })
        Prefs.removeWifiDirectCertMappings(context, id)
    }

    /** Sets the user-visible name for an imported client bundle. */
    fun setClientCertLabel(context: Context, id: String, label: String) {
        saveClientCerts(
            context,
            clientCerts(context).map {
                if (it.id == id) it.copy(label = label.trim()) else it
            },
        )
    }

    /** Deletes every stored client certificate bundle (private keys). */
    fun deleteAllClientCerts(context: Context) {
        val ids = clientCerts(context).map { it.id }
        File(context.filesDir, CLIENT_CERTS_DIR).listFiles()?.forEach { it.delete() }
        saveClientCerts(context, emptyList())
        ids.forEach { Prefs.removeWifiDirectCertMappings(context, it) }
    }

    /**
     * Deletes all client certificate bundles when the device has been in server
     * mode for at least [Constants.Certs.SERVER_MODE_CLIENT_CERT_RETENTION_MS].
     * Returns true if a purge happened.
     */
    fun purgeClientSecretsIfServerModeExpired(context: Context): Boolean {
        if (Prefs.appMode(context) != Prefs.APP_MODE_SERVER) return false
        val startedAt = Prefs.serverModeStartedAt(context)
        if (startedAt <= 0L) return false
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed < Constants.Certs.SERVER_MODE_CLIENT_CERT_RETENTION_MS) return false
        deleteAllClientCerts(context)
        return true
    }

    /**
     * Builds a [ClientTls] context that presents every available client
     * certificate. The combined key store lets the TLS handshake offer all of
     * them, so the server can select the one signed by its own CA (required for
     * mutual TLS). Servers that do not require a client cert ignore them.
     */
    fun clientTlsWithCert(context: Context): ClientTls? =
        loadClientTls(context, clientCerts(context))

    /**
     * Builds a TLS context that presents only [certId]. This matters when two
     * imported CAs share the same subject DN (e.g. two ClipShare desktops both
     * called "clipshare-ca"): the TLS stack cannot tell them apart from the
     * CertificateRequest alone, so the caller must pin the certificate.
     */
    fun clientTlsForCert(context: Context, certId: String?): ClientTls? {
        if (certId.isNullOrBlank()) return clientTlsWithCert(context)
        val cert = clientCerts(context).firstOrNull { it.id == certId } ?: return null
        return loadClientTls(context, listOf(cert))
    }

    private fun loadClientTls(context: Context, certs: List<ClientCertInfo>): ClientTls? {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        var loaded = 0
        certs.forEach { cert ->
            val file = File(context.filesDir, "$CLIENT_CERTS_DIR/${cert.id}.p12")
            if (file.exists()) {
                try {
                    val src = loadP12(file)
                    val aliases = src.aliases()
                    while (aliases.hasMoreElements()) {
                        val alias = aliases.nextElement()
                        if (src.isKeyEntry(alias)) {
                            val key = src.getKey(alias, Constants.Pkcs12.PASSWORD.toCharArray())
                            val chain = src.getCertificateChain(alias)
                            ks.setKeyEntry("client-$loaded", key, Constants.Pkcs12.PASSWORD.toCharArray(), chain)
                            loaded++
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (loaded == 0) return null
        return buildClientTls(context, ks)
    }

    /**
     * Builds a [ClientTls] context with no client certificate, trusting only
     * the user-imported CAs. Used for TLS connections to Android servers that
     * do not require mutual TLS.
     */
    fun trustOnlyTls(context: Context): ClientTls? {
        val tm = trustManager(context) ?: return null
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(tm), null)
        }
        return ClientTls(sslContext, tm)
    }

    // ------------------------------------------------------------------
    // Trusted CA certificates
    // ------------------------------------------------------------------

    fun trustedCas(context: Context): List<TrustedCaInfo> {
        val json = Prefs.trustedCasJson(context)
        return parseTrustedCas(json)
    }

    fun importTrustedCaFromPem(context: Context, pem: String): Boolean {
        return try {
            val cert = parseCertificate(pem) ?: return false
            val fingerprint = sha256Fingerprint(cert) ?: return false
            val id = newId()
            val file = File(context.filesDir, "$TRUSTED_CA_DIR/$id.crt")
            file.parentFile?.mkdirs()
            file.writeText(cert.toPem())

            val info = TrustedCaInfo(
                id = id,
                subject = cert.subjectDN.name,
                fingerprint = fingerprint,
            )
            saveTrustedCas(context, trustedCas(context) + info)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun deleteTrustedCa(context: Context, id: String) {
        File(context.filesDir, "$TRUSTED_CA_DIR/$id.crt").delete()
        saveTrustedCas(context, trustedCas(context).filter { it.id != id })
    }

    fun hasTrustedCa(context: Context, fingerprint: String): Boolean {
        return trustedCas(context).any { it.fingerprint.equals(fingerprint, ignoreCase = true) }
    }

    /**
     * Imports a client certificate from a QR scan. The only accepted payload is
     * the "clipshare-p12:..." envelope produced by [QrCodes.clientCertQrContent]:
     * a gzipped key + leaf + CA bundle. The CA is auto-trusted, so one scan gives
     * both the private key for mutual TLS and the CA needed to validate the
     * server.
     */
    fun importFromQrContent(context: Context, content: String): Boolean {
        if (!content.startsWith(QrCodes.P12_PREFIX)) return false
        val bytes = decodeQrPayload(content.removePrefix(QrCodes.P12_PREFIX))
            ?: return false
        return importEnvelopeQrBytes(context, bytes)
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /**
     * Imports the envelope produced by [QrCodes.clientCertQrContent]: a gzipped
     * key + leaf + CA bundle. It is rebuilt as a PKCS#12 and passed through the
     * normal import path, so TLS uses the same store and the CA is auto-trusted.
     */
    private fun importEnvelopeQrBytes(context: Context, bytes: ByteArray): Boolean {
        if (!isGzip(bytes)) return false
        return try {
            val body = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            var off = 0
            fun u16(): Int? {
                if (off + 2 > body.size) return null
                val n = ((body[off].toInt() and 0xff) shl 8) or (body[off + 1].toInt() and 0xff)
                off += 2
                return n
            }
            fun segment(): ByteArray? {
                val len = u16() ?: return null
                if (off + len > body.size) return null
                val seg = body.copyOfRange(off, off + len)
                off += len
                return seg
            }

            val keyDer = segment() ?: return false
            val leafDer = segment() ?: return false
            val caDer = segment() ?: return false

            val p12 = buildP12Bundle(keyDer, leafDer, caDer) ?: return false
            importClientP12Bytes(context, p12)
        } catch (_: Exception) {
            false
        }
    }

    private fun buildP12Bundle(keyDer: ByteArray, leafDer: ByteArray, caDer: ByteArray): ByteArray? {
        return try {
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyDer))
            val cf = CertificateFactory.getInstance("X.509")
            val leaf = cf.generateCertificate(ByteArrayInputStream(leafDer)) as? X509Certificate ?: return null
            val ca = cf.generateCertificate(ByteArrayInputStream(caDer)) as? X509Certificate ?: return null
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry("client", key, Constants.Pkcs12.PASSWORD.toCharArray(), arrayOf(leaf, ca))
            val out = ByteArrayOutputStream()
            ks.store(out, Constants.Pkcs12.PASSWORD.toCharArray())
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeQrPayload(raw: String): ByteArray? {
        return try {
            android.util.Base64.decode(raw, android.util.Base64.DEFAULT or android.util.Base64.NO_PADDING)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Builds a trust manager that trusts the system CAs plus every imported
     * trusted CA. Used to validate server certificates in client mode.
     */
    fun trustManager(context: Context): X509TrustManager? {
        val caFiles = trustedCas(context).mapNotNull {
            File(context.filesDir, "$TRUSTED_CA_DIR/${it.id}.crt").takeIf { f -> f.exists() }
        }
        if (caFiles.isEmpty()) return null

        return try {
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            var index = 0
            caFiles.forEach { file ->
                file.inputStream().use { input ->
                    val certs = CertificateFactory.getInstance("X.509")
                        .generateCertificates(input)
                        .filterIsInstance<X509Certificate>()
                    certs.forEach { cert ->
                        trustStore.setCertificateEntry("ca-${index++}", cert)
                    }
                }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(trustStore) }
            tmf.trustManagers.firstOrNull() as? X509TrustManager
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun buildClientTls(context: Context, ks: KeyStore): ClientTls? {
        return try {
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(ks, Constants.Pkcs12.PASSWORD.toCharArray()) }
                .keyManagers

            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (ks.isKeyEntry(alias)) {
                    ks.getCertificateChain(alias)?.forEachIndexed { i, c ->
                        trustStore.setCertificateEntry("ca-$alias-$i", c)
                    }
                } else {
                    ks.getCertificate(alias)?.let { trustStore.setCertificateEntry(alias, it) }
                }
            }
            // Also trust every auto-imported CA (from other .p12 bundles and QR
            // shares) so a client cert does not block Android server CAs.
            var index = 0
            trustedCas(context).forEach { ca ->
                val file = File(context.filesDir, "$TRUSTED_CA_DIR/${ca.id}.crt")
                if (file.exists()) {
                    parseCertificate(file.readText())?.let { cert ->
                        try {
                            trustStore.setCertificateEntry("trusted-$index", cert)
                            index++
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(trustStore) }
            val tms = tmf.trustManagers.toMutableList()

            val tm = tms.firstOrNull() as? X509TrustManager ?: return null
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(keyManagers, tms.toTypedArray(), null)
            }
            ClientTls(sslContext, tm)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadP12(file: File): KeyStore {
        return KeyStore.getInstance("PKCS12").apply {
            file.inputStream().use { load(it, Constants.Pkcs12.PASSWORD.toCharArray()) }
        }
    }

    private fun extractCaInfo(ks: KeyStore): Pair<String, String>? {
        val chain = extractCertificateChain(ks) ?: return null
        val ca = chain.lastOrNull() ?: return null
        val fingerprint = sha256Fingerprint(ca) ?: return null
        return ca.subjectDN.name to fingerprint
    }

    private fun extractCaCertificate(ks: KeyStore): X509Certificate? {
        // A bundle of only key+leaf has no CA to trust; treating the leaf as a
        // CA would silently trust the wrong certificate.
        val chain = extractCertificateChain(ks) ?: return null
        if (chain.size < 2) return null
        return chain.lastOrNull()
    }

    private fun extractCertificateChain(ks: KeyStore): Array<X509Certificate>? {
        // Prefer the entry with the longest chain so a store that also carries
        // the issuer CA key (server-mode p12) still yields leaf + CA.
        var best: Array<X509Certificate>? = null
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (ks.isKeyEntry(alias)) {
                val chain = ks.getCertificateChain(alias)
                    ?.filterIsInstance<X509Certificate>()
                    ?.toTypedArray()
                if (chain != null && (best == null || chain.size > best.size)) {
                    best = chain
                }
            }
        }
        return best
    }

    private fun parseClientCerts(json: String): List<ClientCertInfo> = JsonList.parse(json) {
        ClientCertInfo(
            id = it.optString("id"),
            caSubject = it.optString("caSubject"),
            fingerprint = it.optString("fingerprint"),
            label = it.optString("label"),
        )
    }

    private fun parseTrustedCas(json: String): List<TrustedCaInfo> = JsonList.parse(json) {
        TrustedCaInfo(
            id = it.optString("id"),
            subject = it.optString("subject"),
            fingerprint = it.optString("fingerprint"),
        )
    }

    private fun saveClientCerts(context: Context, list: List<ClientCertInfo>) {
        Prefs.setClientCertsJson(context, JsonList.build(list) { obj, it ->
            obj.put("id", it.id)
                .put("caSubject", it.caSubject)
                .put("fingerprint", it.fingerprint)
                .put("label", it.label)
        })
    }

    private fun saveTrustedCas(context: Context, list: List<TrustedCaInfo>) {
        Prefs.setTrustedCasJson(context, JsonList.build(list) { obj, it ->
            obj.put("id", it.id)
                .put("subject", it.subject)
                .put("fingerprint", it.fingerprint)
        })
    }

    private fun sha256Fingerprint(cert: X509Certificate): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun newId(): String = System.currentTimeMillis().toString(36) + (0..9999).random()
}
