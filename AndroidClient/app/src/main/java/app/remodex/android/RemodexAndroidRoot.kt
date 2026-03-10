package app.remodex.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.transport.RemodexTransportState

@Composable
fun RemodexAndroidRoot() {
    val viewModel: RemodexDebugViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Remodex Android",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = "Pairing parser + transport debug shell",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    DebugCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        CardHeader(title = "Pairing Payload")
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = uiState.qrPayload,
                            onValueChange = viewModel::updateQrPayload,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 5,
                            placeholder = {
                                Text("""{"relay":"ws://host:3000","sessionId":"..."}""")
                            },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = viewModel::parsePairingPayload) {
                                Text("Parse")
                            }
                            Button(onClick = viewModel::connect, enabled = !uiState.isBusy) {
                                Text(if (uiState.isBusy) "Connecting..." else "Connect")
                            }
                            OutlinedButton(onClick = viewModel::disconnect) {
                                Text("Disconnect")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Tip: Android emulator cannot reach host `localhost`. Use `10.0.2.2` in the relay URL when testing against a relay running on the same Windows machine.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    DebugCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        CardHeader(title = "Connection State")
                        Spacer(modifier = Modifier.height(12.dp))
                        KeyValueRow("State", connectionStateLabel(uiState.connectionState))
                        KeyValueRow("Session URL", uiState.sessionUrl ?: "Not parsed")
                        KeyValueRow(
                            "Plan Mode",
                            if (uiState.supportsPlanCollaborationMode) {
                                "Supported"
                            } else {
                                "Unknown / not yet reported"
                            },
                        )
                        KeyValueRow("Last Notification", uiState.lastNotificationMethod ?: "None")
                        KeyValueRow("Last Server Request", uiState.lastServerRequestMethod ?: "None")
                        if (uiState.errorMessage != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = uiState.errorMessage ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                item {
                    DebugCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        CardHeader(title = "Host Info")
                        Spacer(modifier = Modifier.height(12.dp))
                        HostInfoSection(uiState.hostInfo)
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
private fun CardHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HostInfoSection(hostInfo: CodexHostInfo?) {
    if (hostInfo == null) {
        Text(
            text = "No host info received yet. After a successful relay handshake, the bridge should send `bridge/hostInfo` or include `host` in the initialize result.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    KeyValueRow("Platform", hostInfo.displayName)
    KeyValueRow("Raw Platform", hostInfo.platform)
    KeyValueRow(
        "Desktop Refresh",
        if (hostInfo.capabilities.desktopRefreshAvailable) {
            if (hostInfo.capabilities.desktopRefreshEnabled) "Available + enabled" else "Available"
        } else {
            "Unavailable"
        },
    )
    KeyValueRow(
        "Desktop Routing",
        if (hostInfo.capabilities.desktopAppRoutingAvailable) "Available" else "Unavailable",
    )
}

private fun connectionStateLabel(state: RemodexTransportState): String {
    return when (state) {
        RemodexTransportState.Disconnected -> "Disconnected"
        is RemodexTransportState.Connecting -> "Connecting (attempt ${state.attempt})"
        is RemodexTransportState.Retrying -> "Retrying (attempt ${state.attempt})"
        is RemodexTransportState.Connected -> {
            if (state.isInitialized) "Connected + initialized" else "Connected"
        }
        is RemodexTransportState.Failed -> {
            if (state.isPermanent) "Failed permanently" else "Failed"
        }
    }
}
