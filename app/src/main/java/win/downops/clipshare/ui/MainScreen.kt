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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import win.downops.clipshare.settings.Prefs
import win.downops.clipshare.state.AppState
import win.downops.clipshare.state.DiscoveredDevice
import win.downops.clipshare.ui.history.HistorySectionHeader
import win.downops.clipshare.ui.history.historyItems
import win.downops.clipshare.util.HostUtil

@Composable
fun MainScreen(
    onPushText: (String) -> Unit,
    onConnectTo: (DiscoveredDevice) -> Unit,
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
    val history by AppState.history.collectAsState()
    val error by AppState.lastError.collectAsState()

    var text by remember { mutableStateOf("") }
    val isServer = appMode == Prefs.APP_MODE_SERVER

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusCard(running, starting, stopping, connected, status, serverName, serverClientCount, transport, isServer, onToggle)
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
                Text("Devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (devices.isEmpty()) {
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
                label = { Text(if (isServer) "Text to broadcast" else "Text to push") },
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onPushText(text)
                    text = ""
                },
                enabled = text.isNotBlank() && (connected || (isServer && running)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (isServer) "Broadcast to clients" else "Send to server") }
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
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(
                            if (connected || (isServer && running)) Color(0xFF2E7D32)
                            else if (running) Color(0xFFF9A825)
                            else Color(0xFF9E9E9E),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(status, style = MaterialTheme.typography.titleMedium)
                    if (isServer) {
                        Text(
                            if (running) "$serverClientCount client${if (serverClientCount == 1) "" else "s"} connected"
                            else "Not running",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (serverName != null) {
                        Text(
                            "Syncing with $serverName${transport?.let { " ($it)" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
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
            if (starting || stopping || (running && !connected && !isServer)) {
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
