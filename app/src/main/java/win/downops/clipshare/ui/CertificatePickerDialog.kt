package win.downops.clipshare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import win.downops.clipshare.certs.ClientCertInfo
import win.downops.clipshare.state.WifiDirectPeer

/**
 * Asks which client certificate to present to a Wi-Fi Direct peer. The choice
 * is persisted per peer MAC by the caller, so the next connection can skip this
 * dialog. It is shown from MainActivity so it also works when the app was
 * opened from the quick-settings tile or a notification.
 */
@Composable
fun CertificatePickerDialog(
    peer: WifiDirectPeer,
    certs: List<ClientCertInfo>,
    selectedId: String?,
    onSelect: (ClientCertInfo) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var picked by remember(peer.address, certs, selectedId) {
        mutableStateOf(
            selectedId?.takeIf { id -> certs.any { it.id == id } }
                ?: certs.firstOrNull()?.id,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Client certificate") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (certs.isEmpty()) {
                    Text(
                        "No client certificate is imported. Import one in Settings → " +
                            "Client certificates, then connect again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "Choose the certificate to present to ${peer.name}. " +
                            "It will be remembered for ${peer.address.ifBlank { peer.name }}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    certs.forEach { cert ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picked = cert.id }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = picked == cert.id,
                                onClick = { picked = cert.id },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    cert.label.ifBlank {
                                        cert.caSubject.ifBlank { "Certificate" }
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (cert.caSubject.isNotBlank()) {
                                    Text(
                                        cert.caSubject,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (cert.fingerprint.isNotBlank()) {
                                    Text(
                                        shortFingerprint(cert.fingerprint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (certs.isEmpty()) {
                TextButton(onClick = onOpenSettings) { Text("Open settings") }
            } else {
                TextButton(
                    enabled = picked != null,
                    onClick = {
                        certs.firstOrNull { it.id == picked }?.let(onSelect)
                    },
                ) { Text("Use certificate") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun shortFingerprint(fingerprint: String): String {
    val parts = fingerprint.split(":").filter { it.isNotBlank() }
    if (parts.size < 8) return fingerprint
    return parts.take(4).joinToString(":") + ":…:" + parts.takeLast(2).joinToString(":")
}
