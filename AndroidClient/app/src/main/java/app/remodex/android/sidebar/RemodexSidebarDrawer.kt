package app.remodex.android.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.remodex.android.RemodexDebugUiState
import app.remodex.android.connectionStateLabel
import app.remodex.android.core.transport.RemodexTransportState

@Composable
internal fun RemodexSidebarDrawer(
    uiState: RemodexDebugUiState,
    showDeveloperPanels: Boolean,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleDeveloperPanels: () -> Unit,
    onNewChat: () -> Unit,
    onRefreshThreads: () -> Unit,
    onSelectThread: (String) -> Unit,
    onParsePairingPayload: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenScanner: () -> Unit,
    onUpdateQrPayload: (String) -> Unit,
) {
    val groups = remember(uiState.threads, searchText) {
        buildSidebarThreadGroups(
            threads = uiState.threads,
            searchText = searchText,
        )
    }
    val canStartNewChat = !uiState.isStartingThread &&
        uiState.connectionState is RemodexTransportState.Connected

    Surface(
        modifier = Modifier
            .width(336.dp)
            .fillMaxSize(),
        color = Color(0xFFFDFBF7),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RemodexSidebarHeader()

            RemodexSidebarSearchField(
                query = searchText,
                onQueryChange = onSearchTextChange,
            )

            RemodexSidebarNewChatButton(
                isCreatingThread = uiState.isStartingThread,
                isEnabled = canStartNewChat,
                onClick = onNewChat,
            )

            RemodexSidebarThreadList(
                groups = groups,
                isFiltering = searchText.isNotBlank(),
                isConnected = uiState.connectionState is RemodexTransportState.Connected,
                activeThreadId = uiState.activeThreadId,
                runBadgeStateForThread = { threadId ->
                    uiState.conversation.threadRunBadgeState(threadId)
                },
                onSelectThread = onSelectThread,
                modifier = Modifier.weight(1f),
            )

            HorizontalDivider(color = Color(0xFFE2DCD2))

            RemodexSidebarConnectionPanel(
                uiState = uiState,
                showDeveloperPanels = showDeveloperPanels,
                connectionStateLabel = connectionStateLabel(
                    uiState.connectionState,
                    uiState.connectionRecoveryState,
                ),
                onOpenSettings = onOpenSettings,
                onToggleDeveloperPanels = onToggleDeveloperPanels,
                onRefreshThreads = onRefreshThreads,
                onParsePairingPayload = onParsePairingPayload,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onOpenScanner = onOpenScanner,
                onUpdateQrPayload = onUpdateQrPayload,
            )
        }
    }
}
