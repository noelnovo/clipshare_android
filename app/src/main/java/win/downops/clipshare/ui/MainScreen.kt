package win.downops.clipshare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import win.downops.clipshare.certs.CertStore
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.state.AppState
import win.downops.clipshare.state.DiscoveredDevice
import win.downops.clipshare.state.WifiDirectPeer
import win.downops.clipshare.ui.history.HistorySectionHeader
import win.downops.clipshare.ui.history.historyItems
import win.downops.clipshare.util.HostUtil

@Composable
fun MainScreen(
    onPushText: (String) -> Unit,
    onConnectTo: (DiscoveredDevice) -> Unit,
    onConnectWifiDirect: (WifiDirectPeer) -> Unit,
    onWaitForPeers: () -> Unit,
    onToggle: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val running by AppState.running.collectAsState()
    val starting by AppState.starting.collectAsState()
    val stopping by AppState.stopping.collectAsState()
    val appMode by AppState.appMode.collectAsState()
    val connected by AppState.connected.collectAsState()
    val connectedIp by AppState.connectedIp.collectAsState()
    val transport by AppState.transport.collectAsState()
    val status by AppState.status.collectAsState()
    val serverName by AppState.serverName.collectAsState()
    val serverClientCount by AppState.serverClientCount.collectAsState()
    val devices by AppState.discovered.collectAsState()
    val wifiDirectPeers by AppState.wifiDirectPeers.collectAsState()
    val wifiDirectWaiting by AppState.wifiDirectWaiting.collectAsState()
    val wifiDirectConnectingPeer by AppState.wifiDirectConnectingPeer.collectAsState()
    val history by AppState.history.collectAsState()
    val error by AppState.lastError.collectAsState()

    var text by remember { mutableStateOf("") }
    val isServer = appMode == Prefs.APP_MODE_SERVER
    val isWifiDirect = appMode == Prefs.APP_MODE_WIFI_DIRECT
    val context = LocalContext.current
    val availableCerts = CertStore.clientCerts(context)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusCard(
                running = running,
                starting = starting,
                stopping = stopping,
                connected = connected,
                status = status,
                serverName = serverName,
                serverClientCount = serverClientCount,
                transport = transport,
                isServer = isServer,
                isWifiDirect = isWifiDirect,
                wifiDirectWaiting = wifiDirectWaiting,
                wifiDirectConnectingPeer = wifiDirectConnectingPeer,
                onToggle = onToggle,
            )
        }
        item {
            if (error != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        text = "Error: $error",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        if (!isServer) {
            item {
                Text(
                    if (isWifiDirect) "Nearby devices (Wi-Fi Direct)" else "Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (isWifiDirect) {
                    if (wifiDirectPeers.isEmpty()) {
                        Text(
                            "No nearby devices yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        wifiDirectPeers.forEach { peer ->
                            val selectedId = Prefs.wifiDirectCertId(context, peer.address, peer.name)
                            val certLabel = selectedId
                                ?.let { id -> availableCerts.firstOrNull { it.id == id } }
                                ?.let { cert ->
                                    cert.label.ifBlank {
                                        cert.caSubject.ifBlank { "Certificate" }
                                    }
                                }
                                ?: availableCerts.singleOrNull()?.let { cert ->
                                    "Auto: " + cert.label.ifBlank {
                                        cert.caSubject.ifBlank { "Certificate" }
                                    }
                                }
                            WifiDirectDeviceRow(
                                peer = peer,
                                connected = connected && peer.name == serverName,
                                connecting = wifiDirectConnectingPeer == peer.name,
                                certLabel = certLabel,
                                onConnect = { onConnectWifiDirect(peer) },
                                onChangeCert = { AppState.onCertificateRequired(peer) },
                            )
                        }
                    }
                    if (connected) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = onWaitForPeers,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Disconnect and wait for devices") }
                    }
                } else if (devices.isEmpty()) {
                    Text(
                        "No devices found. Make sure a server is running and you are on the same network, or add the address in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    devices.forEach { device ->
                        DeviceRow(device, connectedIp, connected, onConnectTo)
                    }
                }
            }
        }
        item {
            Text(
                if (isServer) "Broadcast" else "Send",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        when {
                            isServer -> "Text to broadcast"
                            isWifiDirect && !connected -> "Text to broadcast to devices"
                            else -> "Text to push"
                        }
                    )
                },
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onPushText(text)
                    text = ""
                },
                enabled = text.isNotBlank() && (
                    connected ||
                        (isServer && running) ||
                        (isWifiDirect && wifiDirectWaiting && serverClientCount > 0 && running)
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        isServer -> "Broadcast to clients"
                        isWifiDirect && !connected -> "Broadcast to devices"
                        isWifiDirect -> "Send to device"
                        else -> "Send to server"
                    }
                )
            }
        }
        item {
            HistorySectionHeader(history, onClearHistory)
            if (history.isEmpty()) {
                Text(
                    "Nothing yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        historyItems(history)
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    starting: Boolean,
    stopping: Boolean,
    connected: Boolean,
    status: String,
    serverName: String?,
    serverClientCount: Int,
    transport: String?,
    isServer: Boolean,
    isWifiDirect: Boolean,
    wifiDirectWaiting: Boolean,
    wifiDirectConnectingPeer: String?,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(
                            when {
                                connected || (serverClientCount > 0 && (isServer || isWifiDirect)) ->
                                    Color(0xFF2E7D32)
                                running || starting || stopping -> Color(0xFFF9A825)
                                else -> Color(0xFF9E9E9E)
                            },
                            CircleShape
                        )
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(status, style = MaterialTheme.typography.titleMedium)
                    when {
                        isServer -> Text(
                            if (running) "$serverClientCount client${if (serverClientCount == 1) "" else "s"} connected"
                            else "Not running",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        isWifiDirect && wifiDirectConnectingPeer != null -> Text(
                            "Connecting to $wifiDirectConnectingPeer…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        isWifiDirect && connected && serverName != null -> Text(
                            "Syncing with $serverName (wifi_direct)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        isWifiDirect && running -> Text(
                            when {
                                serverClientCount > 0 ->
                                    "$serverClientCount device${if (serverClientCount == 1) "" else "s"} connected"
                                wifiDirectWaiting -> "Waiting for nearby devices"
                                else -> "Starting Wi-Fi Direct..."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        serverName != null -> Text(
                            "Syncing with $serverName${transport?.let { " ($it)" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Text(
                            if (running) "Watching clipboard while app is open" else "Not syncing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = running,
                    onCheckedChange = { onToggle() },
                    enabled = !starting && !stopping,
                    modifier = Modifier.testTag("sync_switch"),
                )
            }
            if (starting || stopping || wifiDirectConnectingPeer != null ||
                (running && !connected && !isServer && !isWifiDirect)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            starting -> "Starting service..."
                            stopping -> "Stopping service..."
                            wifiDirectConnectingPeer != null -> "Connecting to $wifiDirectConnectingPeer..."
                            else -> "Connecting..."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DiscoveredDevice,
    connectedIp: String?,
    connected: Boolean,
    onConnect: (DiscoveredDevice) -> Unit,
) {
    val isCurrent = connected && device.host == connectedIp
    Card(
        modifier = if (isCurrent) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .clickable { onConnect(device) }
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${HostUtil.display(device.host)}:${device.port}  (${device.source}${if (device.tls) " · TLS" else ""})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isCurrent) {
                Text(
                    "Connected",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text("Connect", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun WifiDirectDeviceRow(
    peer: WifiDirectPeer,
    connected: Boolean,
    connecting: Boolean,
    certLabel: String?,
    onConnect: () -> Unit,
    onChangeCert: () -> Unit,
) {
    val enabled = !connected && !connecting
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onConnect() } else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (connected || connecting)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(peer.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Wi-Fi Direct · mTLS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Connecting…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    connected -> Text(
                        "Connected",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    else -> Text(
                        "Connect",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Certificate: ${certLabel ?: "not selected"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onChangeCert, enabled = !connecting) {
                    Text("Change")
                }
            }
        }
    }
}
