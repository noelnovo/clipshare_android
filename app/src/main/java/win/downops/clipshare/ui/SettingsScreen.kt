package win.downops.clipshare.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.journeyapps.barcodescanner.CaptureActivity
import win.downops.clipshare.certs.CertStore
import win.downops.clipshare.certs.ClientCertInfo
import win.downops.clipshare.certs.QrCodes
import win.downops.clipshare.certs.ServerCertManager
import win.downops.clipshare.logs.LogStore
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.settings.SettingsValidator
import win.downops.clipshare.settings.ValidationResult
import win.downops.clipshare.settings.WhitelistEntry
import win.downops.clipshare.state.AppState
import win.downops.clipshare.util.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(context: Context, onBack: () -> Unit, registerSave: (() -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf(Prefs.deviceName(context)) }
    var appMode by rememberSaveable { mutableStateOf(Prefs.appMode(context)) }

    // Connection settings
    var host by rememberSaveable { mutableStateOf(Prefs.serverHost(context)) }
    var port by rememberSaveable { mutableStateOf(Prefs.serverPort(context).toString()) }
    var token by rememberSaveable { mutableStateOf(Prefs.token(context)) }
    var discovery by rememberSaveable { mutableStateOf(Prefs.discoveryEnabled(context)) }
    var wifiDirect by rememberSaveable { mutableStateOf(Prefs.wifiDirectEnabled(context)) }
    var beaconPort by rememberSaveable { mutableStateOf(Prefs.discoveryBeaconPort(context).toString()) }
    var mode by rememberSaveable { mutableStateOf(Prefs.connectionMode(context)) }
    var ipVersion by rememberSaveable { mutableStateOf(Prefs.ipVersion(context)) }
    val whitelist = remember {
        mutableStateListOf<WhitelistEntry>().apply { addAll(Prefs.whitelist(context)) }
    }

    // Clipboard / history / logging
    var pollMs by rememberSaveable { mutableStateOf(Prefs.clipboardPollMs(context).toString()) }
    var maxHistoryEntries by rememberSaveable { mutableStateOf(Prefs.maxHistoryEntries(context).toString()) }
    var maxLogKb by rememberSaveable { mutableStateOf(Prefs.maxLogFileKb(context).toString()) }
    var logsEnabled by rememberSaveable { mutableStateOf(Prefs.logsEnabled(context)) }

    // TLS: client
    var tlsEnabled by rememberSaveable { mutableStateOf(Prefs.tlsEnabled(context)) }
    var verifyHostname by rememberSaveable { mutableStateOf(Prefs.verifyHostname(context)) }
    val clientCerts = remember { mutableStateListOf<ClientCertInfo>().apply { addAll(CertStore.clientCerts(context)) } }

    // TLS: server
    var serverPort by rememberSaveable { mutableStateOf(Prefs.serverPort(context).toString()) }
    var serverToken by rememberSaveable { mutableStateOf(Prefs.serverToken(context)) }
    var serverTls by rememberSaveable { mutableStateOf(Prefs.serverTlsEnabled(context)) }
    var serverBindIpVersion by rememberSaveable { mutableStateOf(Prefs.serverBindIpVersion(context)) }
    var serverCertStatus by rememberSaveable { mutableStateOf(serverCertStatusText(context)) }
    var showClientCertQr by rememberSaveable { mutableStateOf(false) }
    var showClientCertWarning by rememberSaveable { mutableStateOf(false) }
    var pendingBackAfterWarning by rememberSaveable { mutableStateOf(false) }

    var settingsTab by rememberSaveable { mutableStateOf(0) }

    val deviceNameError = SettingsValidator.validateDeviceName(name)
    val pollMsError = SettingsValidator.validateClipboardPollMs(pollMs.toLongOrNull())
    val maxHistoryEntriesError = SettingsValidator.validateMaxHistoryEntries(maxHistoryEntries.toIntOrNull())
    val maxLogKbError = SettingsValidator.validateMaxLogFileKb(maxLogKb.toIntOrNull())
    val portError = SettingsValidator.validatePort(port.toIntOrNull())
    val beaconPortError = SettingsValidator.validatePort(beaconPort.toIntOrNull())
    val serverPortError = SettingsValidator.validatePort(serverPort.toIntOrNull())

    val clientCertPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val ok = CertStore.importClientP12(context, uri)
            if (ok) {
                clientCerts.clear()
                clientCerts.addAll(CertStore.clientCerts(context))
            }
            Toast.makeText(
                context,
                if (ok) "Certificate imported" else "Import failed",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val qrScanner = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val content = result.data?.getStringExtra("SCAN_RESULT")
            if (content != null) {
                val ok = CertStore.importFromQrContent(context, content)
                if (ok) {
                    clientCerts.clear()
                    clientCerts.addAll(CertStore.clientCerts(context))
                }
                Toast.makeText(
                    context,
                    if (ok) "Certificate imported from QR" else "QR code not recognized",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun save() {
        val validationErrors = buildList {
            add(SettingsValidator.validateDeviceName(name))
            add(SettingsValidator.validateClipboardPollMs(pollMs.toLongOrNull()))
            add(SettingsValidator.validateMaxHistoryEntries(maxHistoryEntries.toIntOrNull()))
            add(SettingsValidator.validateMaxLogFileKb(maxLogKb.toIntOrNull()))
            add(SettingsValidator.validatePort(port.toIntOrNull()))
            add(SettingsValidator.validatePort(beaconPort.toIntOrNull()))
            add(SettingsValidator.validatePort(serverPort.toIntOrNull()))
        }.filterIsInstance<ValidationResult.Invalid>()

        if (validationErrors.isNotEmpty()) {
            Toast.makeText(context, validationErrors.first().message, Toast.LENGTH_LONG).show()
            return
        }

        Prefs.setDeviceName(context, name)
        Prefs.setAppMode(context, appMode)
        Prefs.setServerHost(context, host)
        Prefs.setServerPort(context, port.toIntOrNull() ?: Constants.Discovery.DEFAULT_SERVER_PORT)
        Prefs.setServerToken(context, serverToken)
        Prefs.setServerTlsEnabled(context, serverTls)
        Prefs.setServerBindIpVersion(context, serverBindIpVersion)
        Prefs.setToken(context, token)
        Prefs.setDiscoveryEnabled(context, discovery)
        Prefs.setWifiDirectEnabled(context, wifiDirect)
        Prefs.setDiscoveryBeaconPort(context, beaconPort.toIntOrNull() ?: Constants.Discovery.DEFAULT_BEACON_PORT)
        Prefs.setTlsEnabled(context, tlsEnabled)
        Prefs.setVerifyHostname(context, verifyHostname)
        Prefs.setLogsEnabled(context, logsEnabled)
        Prefs.setConnectionMode(context, mode)
        Prefs.setIpVersion(context, ipVersion)
        Prefs.setWhitelist(context, whitelist.toList())
        Prefs.setClipboardPollMs(context, pollMs.toLongOrNull() ?: Constants.Clipboard.SYNC_POLL_MS)
        Prefs.setMaxHistoryEntries(context, maxHistoryEntries.toIntOrNull() ?: Constants.History.DEFAULT_MAX_ENTRIES)
        Prefs.setMaxLogFileKb(context, maxLogKb.toIntOrNull() ?: Constants.Log.DEFAULT_MAX_FILE_KB)
        LogStore.setMaxLogFileKb(maxLogKb.toIntOrNull() ?: Constants.Log.DEFAULT_MAX_FILE_KB)

        // Client certs (private keys) are not used in server mode: record when
        // server mode started and purge them once the retention period lapses.
        if (appMode == Prefs.APP_MODE_SERVER) {
            if (Prefs.serverModeStartedAt(context) <= 0L) {
                Prefs.setServerModeStartedAt(context, System.currentTimeMillis())
            }
        } else {
            Prefs.setServerModeStartedAt(context, 0L)
        }
        val purged = CertStore.purgeClientSecretsIfServerModeExpired(context)
        if (purged) {
            clientCerts.clear()
            Toast.makeText(context, "Client certificates removed (server-mode retention)", Toast.LENGTH_LONG).show()
        }

        // Generate the server certs here (not in the sync service) the first
        // time server TLS is enabled, so the warning dialog can appear at the
        // exact moment the cert files are generated. ServerSyncMode's bootstrap
        // then becomes a no-op.
        val generatedNow = appMode == Prefs.APP_MODE_SERVER &&
            serverTls &&
            !ServerCertManager.hasCerts(context)
        if (generatedNow) {
            ServerCertManager.generate(context, Prefs.deviceName(context))
            serverCertStatus = serverCertStatusText(context)
        }

        AppState.setAppMode(appMode)
        if (AppState.running.value) {
            AppState.stopSync(context)
            AppState.startSync(context)
        }

        if (generatedNow) {
            showClientCertWarning = true
            pendingBackAfterWarning = true
        } else {
            onBack()
        }
    }

    LaunchedEffect(Unit) {
        registerSave { save() }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = settingsTab) {
            Tab(
                selected = settingsTab == 0,
                onClick = { settingsTab = 0 },
                text = { Text("General") },
                modifier = Modifier.testTag("tab_general"),
            )
            Tab(
                selected = settingsTab == 1,
                onClick = { settingsTab = 1 },
                text = { Text("Advanced") },
                modifier = Modifier.testTag("tab_advanced"),
            )
        }

        if (settingsTab == 0) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // General
                SettingsSectionTitle("General")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Device name") },
                    supportingText = if (deviceNameError is ValidationResult.Invalid) {
                        { Text(deviceNameError.message) }
                    } else {
                        null
                    },
                    isError = deviceNameError is ValidationResult.Invalid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("App mode", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Choose whether this device connects to other servers or acts as one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = appMode == Prefs.APP_MODE_CLIENT,
                        onClick = { appMode = Prefs.APP_MODE_CLIENT },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        modifier = Modifier.testTag("mode_client"),
                    ) { Text("Client") }
                    SegmentedButton(
                        selected = appMode == Prefs.APP_MODE_SERVER,
                        onClick = { appMode = Prefs.APP_MODE_SERVER },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        modifier = Modifier.testTag("mode_server"),
                    ) { Text("Server") }
                }
                // History
                SettingsSectionTitle("History")
                OutlinedTextField(
                    value = maxHistoryEntries,
                    onValueChange = { maxHistoryEntries = it.filter(Char::isDigit) },
                    label = { Text("Max history entries") },
                    supportingText = {
                        val message = (maxHistoryEntriesError as? ValidationResult.Invalid)?.message
                        Text(message ?: "Older items are dropped to stay within this limit.")
                    },
                    isError = maxHistoryEntriesError is ValidationResult.Invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Client
                SettingsSectionTitle("Client")
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Server (IP or hostname)") },
                    supportingText = { Text("Leave blank if using Discovery.") },
                    placeholder = { Text("e.g. 192.168.1.10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Server port") },
                    supportingText = if (portError is ValidationResult.Invalid) {
                        { Text(portError.message) }
                    } else {
                        null
                    },
                    isError = portError is ValidationResult.Invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("client_port"),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Shared token (optional)") },
                    supportingText = { Text("Appended to the WebSocket URL as ?token=... when set.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    title = "Discovery",
                    subtitle = "Browse for servers via mDNS and UDP beacons",
                    checked = discovery,
                    onCheckedChange = { discovery = it },
                )
                SwitchRow(
                    title = "TLS (wss)",
                    subtitle = "Requires a client certificate or trusted CA",
                    checked = tlsEnabled,
                    onCheckedChange = { tlsEnabled = it },
                )
                Text("Client certificates", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Used for mutual TLS. The certificate matching the server's CA is used automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                clientCerts.forEach { cert ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(cert.caSubject, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = {
                            CertStore.deleteClientCert(context, cert.id)
                            clientCerts.clear()
                            clientCerts.addAll(CertStore.clientCerts(context))
                        }) { Text("\u2715") }
                    }
                }
                OutlinedButton(
                    onClick = { clientCertPicker.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Select client secret") }
                OutlinedButton(
                    onClick = { qrScanner.launch(Intent(context, CaptureActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan client secret") }

                // Server
                SettingsSectionTitle("Server")
                OutlinedTextField(
                    value = serverToken,
                    onValueChange = { serverToken = it },
                    label = { Text("Server token (optional)") },
                    supportingText = { Text("Clients must include this token as a query parameter to connect.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    title = "TLS (wss)",
                    subtitle = "Generate a local CA + server certificate. Clients must present a client certificate (mutual TLS).",
                    checked = serverTls,
                    onCheckedChange = { serverTls = it },
                    testTag = "server_tls",
                )
                Text(
                    serverCertStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        ServerCertManager.generate(context, Prefs.deviceName(context))
                        serverCertStatus = serverCertStatusText(context)
                        if (appMode == Prefs.APP_MODE_SERVER) {
                            showClientCertWarning = true
                        }
                        Toast.makeText(context, "Server certificate regenerated", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Regenerate cert") }
                OutlinedButton(
                    onClick = {
                        val uri = ServerCertManager.getP12ShareUri(context)
                        if (uri != null) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/x-pkcs12"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share certificate bundle"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share client secret") }
                OutlinedButton(
                    onClick = { showClientCertQr = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Show client secret") }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // General
                SettingsSectionTitle("General")
                OutlinedTextField(
                    value = beaconPort,
                    onValueChange = { beaconPort = it.filter(Char::isDigit) },
                    label = { Text("Beacon port") },
                    supportingText = if (beaconPortError is ValidationResult.Invalid) {
                        { Text(beaconPortError.message) }
                    } else {
                        null
                    },
                    isError = beaconPortError is ValidationResult.Invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    title = "Enable logs",
                    subtitle = "Show the Logs screen and menu. Logs are still recorded in the background.",
                    checked = logsEnabled,
                    onCheckedChange = { logsEnabled = it },
                    testTag = "enable_logs",
                )
                SwitchRow(
                    title = "Enable Wi-Fi Direct",
                    subtitle = "Discover and connect to ClipShare devices without a router. Android may request Nearby devices and location access.",
                    checked = wifiDirect,
                    onCheckedChange = { wifiDirect = it },
                )
                if (logsEnabled) {
                    OutlinedTextField(
                        value = maxLogKb,
                        onValueChange = { maxLogKb = it.filter(Char::isDigit) },
                        label = { Text("Max log file size (KB)") },
                        supportingText = {
                            val message = (maxLogKbError as? ValidationResult.Invalid)?.message
                            Text(message ?: "The on-device log is trimmed to stay within this limit.")
                        },
                        isError = maxLogKbError is ValidationResult.Invalid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Client
                SettingsSectionTitle("Client")
                OutlinedTextField(
                    value = pollMs,
                    onValueChange = { pollMs = it.filter(Char::isDigit) },
                    label = { Text("Clipboard poll interval (ms)") },
                    supportingText = {
                        val message = (pollMsError as? ValidationResult.Invalid)?.message
                        Text(message ?: "How often the clipboard is re-checked when working in background mode. Lower = more responsive, higher = less battery. Clamped to 200-10000.")
                    },
                    isError = pollMsError is ValidationResult.Invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Connection mode", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == Prefs.MODE_DISCOVER,
                        onClick = { mode = Prefs.MODE_DISCOVER },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Discover") }
                    SegmentedButton(
                        selected = mode == Prefs.MODE_WHITELIST,
                        onClick = { mode = Prefs.MODE_WHITELIST },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Whitelist") }
                }

                if (mode == Prefs.MODE_WHITELIST) {
                    Text("Whitelist", style = MaterialTheme.typography.titleSmall)
                    whitelist.forEachIndexed { i, entry ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = entry.name,
                                onValueChange = { whitelist[i] = entry.copy(name = it) },
                                label = { Text("Name") },
                                singleLine = true,
                                modifier = Modifier.weight(1.2f),
                            )
                            OutlinedTextField(
                                value = entry.ip,
                                onValueChange = { whitelist[i] = entry.copy(ip = it) },
                                label = { Text("IP") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1.2f),
                            )
                            IconButton(onClick = { whitelist.removeAt(i) }) { Text("\u2715") }
                        }
                    }
                    OutlinedButton(
                        onClick = { whitelist.add(WhitelistEntry("", "")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add entry") }
                }

                Text("IP version", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Filter discovered servers by address family. Manual entries are not filtered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = ipVersion == Prefs.IP_VERSION_ANY,
                        onClick = { ipVersion = Prefs.IP_VERSION_ANY },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    ) { Text("Any") }
                    SegmentedButton(
                        selected = ipVersion == Prefs.IP_VERSION_IPV4,
                        onClick = { ipVersion = Prefs.IP_VERSION_IPV4 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    ) { Text("IPv4") }
                    SegmentedButton(
                        selected = ipVersion == Prefs.IP_VERSION_IPV6,
                        onClick = { ipVersion = Prefs.IP_VERSION_IPV6 },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    ) { Text("IPv6") }
                }
                SwitchRow(
                    title = "Verify hostname",
                    subtitle = "Turn on if the device private IP address changes frequently. Turn off if this device will always work in the same network.",
                    checked = verifyHostname,
                    onCheckedChange = { verifyHostname = it },
                )

                // Server
                SettingsSectionTitle("Server")
                Text("Bind address", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Choose whether the server listens on IPv4, IPv6, or the current default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = serverBindIpVersion == Prefs.IP_VERSION_ANY,
                        onClick = { serverBindIpVersion = Prefs.IP_VERSION_ANY },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    ) { Text("Any") }
                    SegmentedButton(
                        selected = serverBindIpVersion == Prefs.IP_VERSION_IPV4,
                        onClick = { serverBindIpVersion = Prefs.IP_VERSION_IPV4 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    ) { Text("IPv4") }
                    SegmentedButton(
                        selected = serverBindIpVersion == Prefs.IP_VERSION_IPV6,
                        onClick = { serverBindIpVersion = Prefs.IP_VERSION_IPV6 },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    ) { Text("IPv6") }
                }
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it.filter(Char::isDigit) },
                    label = { Text("Server port") },
                    supportingText = if (serverPortError is ValidationResult.Invalid) {
                        { Text(serverPortError.message) }
                    } else {
                        null
                    },
                    isError = serverPortError is ValidationResult.Invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_port"),
                )
            }
        }
    }

    if (showClientCertQr) {
        ServerClientCertQrDialog(
            context = context,
            onDismiss = { showClientCertQr = false },
        )
    }

    if (showClientCertWarning) {
        ClientCertRetentionWarningDialog(
            context = context,
            onDismiss = {
                showClientCertWarning = false
                if (pendingBackAfterWarning) {
                    pendingBackAfterWarning = false
                    onBack()
                }
            },
        )
    }
}

@Composable
private fun ServerClientCertQrDialog(context: Context, onDismiss: () -> Unit) {
    val content = remember { QrCodes.clientCertQrContent(context) }
    val bitmap = remember(content) { content?.let { QrCodes.encode(it, 1024) } }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .widthIn(max = 420.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(12.dp),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Client certificate QR code",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Scan with the device that will connect to this server. It installs " +
                            "the client certificate and trusts this server's CA in one step.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Generate the server certificate first.")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun ClientCertRetentionWarningDialog(context: Context, onDismiss: () -> Unit) {
    val startedAt = Prefs.serverModeStartedAt(context)
    val expiry = (if (startedAt > 0L) startedAt else System.currentTimeMillis()) +
        Constants.Certs.SERVER_MODE_CLIENT_CERT_RETENTION_MS
    val expiryDate = remember(expiry) {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(expiry))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Client certificates expire") },
        text = {
            Text(
                "Server certificates were generated. This device is in server " +
                    "mode, so its stored client secrets (private keys) will be " +
                    "removed on $expiryDate. Have clients scan a fresh " +
                    "certificate QR from this screen soon."
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String = title,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(testTag))
    }
}

private fun serverCertStatusText(context: Context): String {
    return if (ServerCertManager.hasCerts(context)) {
        val pem = ServerCertManager.getCaCertificatePem(context)
        if (pem != null) "Server certificate ready" else "Certificate state unknown"
    } else {
        "No server certificate generated yet"
    }
}
