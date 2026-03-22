package app.remodex.android.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.remodex.android.RemodexDebugUiState
import app.remodex.android.core.transport.RemodexTransportState

@Composable
internal fun RemodexSidebarConnectionPanel(
    uiState: RemodexDebugUiState,
    showDeveloperPanels: Boolean,
    connectionStateLabel: String,
    onOpenSettings: () -> Unit,
    onToggleDeveloperPanels: () -> Unit,
    onRefreshThreads: () -> Unit,
    onParsePairingPayload: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenScanner: () -> Unit,
    onUpdateQrPayload: (String) -> Unit,
) {
    var isManualPairingExpanded by rememberSaveable { mutableStateOf(false) }
    val isConnected = uiState.connectionState is RemodexTransportState.Connected
    val showPairingControls = isManualPairingExpanded ||
        showDeveloperPanels ||
        !isConnected ||
        uiState.qrPayload.isNotBlank()
    val connectLabel = when {
        uiState.isBusy || uiState.isAttemptingAutoReconnect -> "Connecting..."
        uiState.hasSavedRelaySession && uiState.qrPayload.isBlank() -> "Reconnect"
        else -> "Connect"
    }

    LaunchedEffect(isConnected, uiState.qrPayload) {
        if (isConnected && uiState.qrPayload.isBlank()) {
            isManualPairingExpanded = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFFF5F1EA),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Bridge",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = uiState.hostInfo?.displayName ?: "Local bridge session",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (isConnected) {
                        TextButton(onClick = { isManualPairingExpanded = !isManualPairingExpanded }) {
                            Text(if (showPairingControls) "Hide Pairing" else "Pair")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings",
                        )
                    }
                }
            }

            ConnectionSummaryRow(
                connectionStateLabel = connectionStateLabel,
                planSupported = uiState.supportsPlanCollaborationMode,
            )

            uiState.sessionUrl?.let { sessionUrl ->
                Text(
                    text = sessionUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (showPairingControls) {
                OutlinedTextField(
                    value = uiState.qrPayload,
                    onValueChange = onUpdateQrPayload,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    placeholder = {
                        Text("""{"relay":"ws://host:3000","sessionId":"..."}""")
                    },
                    shape = RoundedCornerShape(18.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onOpenScanner,
                    ) {
                        Text("Scan QR")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onParsePairingPayload,
                    ) {
                        Text("Parse")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onConnect,
                        enabled = !uiState.isBusy && !uiState.isAttemptingAutoReconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(connectLabel)
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDisconnect,
                        enabled = isConnected,
                    ) {
                        Text("Stop")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onRefreshThreads,
                        enabled = !uiState.isLoadingThreads && isConnected,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (uiState.isLoadingThreads) "Loading..." else "Refresh",
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDisconnect,
                        enabled = isConnected,
                    ) {
                        Text("Disconnect")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!showPairingControls && !isConnected && uiState.hasSavedRelaySession) {
                    TextButton(onClick = onConnect) {
                        Text(connectLabel)
                    }
                } else {
                    Text(
                        text = if (isConnected) "Connection ready" else "Waiting for pairing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TextButton(onClick = onToggleDeveloperPanels) {
                    Text(if (showDeveloperPanels) "Hide Debug" else "Show Debug")
                }
            }
        }
    }
}

@Composable
private fun ConnectionSummaryRow(
    connectionStateLabel: String,
    planSupported: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(10.dp),
            shape = CircleShape,
            color = when {
                connectionStateLabel.startsWith("Connected") -> Color(0xFF2F8C4C)
                connectionStateLabel.startsWith("Connecting") || connectionStateLabel.startsWith("Retrying") ->
                    Color(0xFFC98935)

                else -> Color(0xFFC65446)
            },
        ) {}

        Text(
            text = connectionStateLabel,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )

        Surface(
            shape = RoundedCornerShape(999.dp),
            color = if (planSupported) Color(0xFFE7F2EA) else Color(0xFFEDE8E1),
        ) {
            Text(
                text = if (planSupported) "Plan ready" else "Plan pending",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (planSupported) Color(0xFF2F8C4C) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
