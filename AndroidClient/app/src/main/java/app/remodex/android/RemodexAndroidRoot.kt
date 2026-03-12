package app.remodex.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadSyncState
import app.remodex.android.core.transport.RemodexTransportDiagnostics
import app.remodex.android.core.transport.RemodexTransportState
import kotlinx.coroutines.launch

private val RemodexColorScheme = lightColorScheme(
    background = Color(0xFFF1EFEB),
    surface = Color(0xFFF6F4F0),
    surfaceVariant = Color(0xFFE5E0D8),
    onBackground = Color(0xFF151515),
    onSurface = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF6E685E),
    primary = Color(0xFF6961D8),
    onPrimary = Color(0xFFFFFFFF),
    outline = Color(0xFFD7D0C7),
    error = Color(0xFFC94D3F),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun RemodexAndroidRoot() {
    val viewModel: RemodexDebugViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Open,
    )
    val scope = rememberCoroutineScope()
    var showDeveloperPanels by rememberSaveable { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = RemodexColorScheme,
        typography = MaterialTheme.typography.copy(
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            titleLarge = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Monospace,
            ),
            titleMedium = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace,
            ),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
            bodySmall = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            labelLarge = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.Monospace,
            ),
            labelMedium = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFFF2F0EC), Color(0xFFE9E4DB)),
                    ),
                ),
        ) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = true,
                scrimColor = Color(0x19000000),
                drawerContent = {
                    SidebarDrawer(
                        uiState = uiState,
                        showDeveloperPanels = showDeveloperPanels,
                        onToggleDeveloperPanels = { showDeveloperPanels = !showDeveloperPanels },
                        onNewChat = {
                            viewModel.startThread()
                            scope.launch { drawerState.close() }
                        },
                        onRefreshThreads = viewModel::refreshThreads,
                        onSelectThread = { threadId ->
                            viewModel.selectThread(threadId)
                            scope.launch { drawerState.close() }
                        },
                        onParsePairingPayload = viewModel::parsePairingPayload,
                        onConnect = viewModel::connect,
                        onDisconnect = viewModel::disconnect,
                        onUpdateQrPayload = viewModel::updateQrPayload,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                MainConversationPane(
                    uiState = uiState,
                    showDeveloperPanels = showDeveloperPanels,
                    onToggleDrawer = {
                        scope.launch {
                            if (drawerState.isOpen) drawerState.close() else drawerState.open()
                        }
                    },
                    onToggleDeveloperPanels = { showDeveloperPanels = !showDeveloperPanels },
                    onPromptChange = viewModel::updateDraftTurnInput,
                    onSendPrompt = viewModel::startTurn,
                )
            }
        }
    }
}

@Composable
private fun SidebarDrawer(
    uiState: RemodexDebugUiState,
    showDeveloperPanels: Boolean,
    onToggleDeveloperPanels: () -> Unit,
    onNewChat: () -> Unit,
    onRefreshThreads: () -> Unit,
    onSelectThread: (String) -> Unit,
    onParsePairingPayload: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onUpdateQrPayload: (String) -> Unit,
) {
    val groupedThreads = remember(uiState.threads) {
        uiState.threads.groupBy { it.projectDisplayName }
            .toSortedMap(compareBy<String> { it.equals("No Project", ignoreCase = true) }.thenBy { it.lowercase() })
    }
    val canStartNewChat = !uiState.isStartingThread &&
        uiState.connectionState is RemodexTransportState.Connected

    Surface(
        modifier = Modifier
            .width(336.dp)
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
        shape = RoundedCornerShape(32.dp),
        color = Color(0xF5FCFBF8),
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SidebarHeader()
            SearchStrip()
            NavigationDrawerItem(
                label = {
                    Text(
                        text = if (uiState.isStartingThread) "Creating..." else "New Chat",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.alpha(if (canStartNewChat) 1f else 0.5f),
                    )
                },
                selected = false,
                onClick = {
                    if (canStartNewChat) {
                        onNewChat()
                    }
                },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.alpha(if (canStartNewChat) 1f else 0.5f),
                    )
                },
                colors = NavigationDrawerItemDefaults.colors(
                    unselectedContainerColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                groupedThreads.forEach { (projectName, threads) ->
                    item(key = "header-$projectName") {
                        SidebarSectionHeader(title = projectName)
                    }
                    items(items = threads.take(8), key = { it.id }) { thread ->
                        ThreadDrawerRow(
                            thread = thread,
                            isSelected = thread.id == uiState.selectedThreadId,
                            onClick = { onSelectThread(thread.id) },
                        )
                    }
                }
            }

            Divider(color = Color(0xFFDCD6CD))
            SidebarConnectionPanel(
                uiState = uiState,
                showDeveloperPanels = showDeveloperPanels,
                onToggleDeveloperPanels = onToggleDeveloperPanels,
                onRefreshThreads = onRefreshThreads,
                onParsePairingPayload = onParsePairingPayload,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onUpdateQrPayload = onUpdateQrPayload,
            )
        }
    }
}

@Composable
private fun SidebarHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFF111111),
            tonalElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = ">...[]",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column {
            Text(
                text = "Remodex",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Remote Codex Workspace",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchStrip() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF0EDE8),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Search conversations",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SidebarSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ThreadDrawerRow(
    thread: CodexThread,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = thread.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = thread.preview ?: thread.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        selected = isSelected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = if (thread.syncState == CodexThreadSyncState.ArchivedLocal) {
                    Icons.AutoMirrored.Outlined.MenuBook
                } else {
                    Icons.Outlined.FolderOpen
                },
                contentDescription = null,
            )
        },
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = Color(0xFFE8E4DD),
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            unselectedContainerColor = Color.Transparent,
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SidebarConnectionPanel(
    uiState: RemodexDebugUiState,
    showDeveloperPanels: Boolean,
    onToggleDeveloperPanels: () -> Unit,
    onRefreshThreads: () -> Unit,
    onParsePairingPayload: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onUpdateQrPayload: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Bridge",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onToggleDeveloperPanels) {
                Text(if (showDeveloperPanels) "Hide Debug" else "Show Debug")
            }
        }

        StatusBadgeRow(
            stateLabel = connectionStateLabel(uiState.connectionState),
            hostInfo = uiState.hostInfo,
            planSupported = uiState.supportsPlanCollaborationMode,
        )

        OutlinedTextField(
            value = uiState.qrPayload,
            onValueChange = onUpdateQrPayload,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 5,
            placeholder = {
                Text("""{"relay":"ws://host:3000","sessionId":"..."}""")
            },
            shape = RoundedCornerShape(20.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onParsePairingPayload) {
                Text("Parse")
            }
            Button(
                onClick = onConnect,
                enabled = !uiState.isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(if (uiState.isBusy) "Connecting..." else "Connect")
            }
            OutlinedButton(onClick = onDisconnect) {
                Text("Stop")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onRefreshThreads,
                enabled = !uiState.isLoadingThreads &&
                    uiState.connectionState is RemodexTransportState.Connected,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.width(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (uiState.isLoadingThreads) "Loading..." else "Refresh")
            }
            uiState.sessionUrl?.let { sessionUrl ->
                Text(
                    text = sessionUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusBadgeRow(
    stateLabel: String,
    hostInfo: CodexHostInfo?,
    planSupported: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillBadge(
            icon = Icons.Outlined.Lan,
            label = stateLabel,
            tone = when {
                stateLabel.startsWith("Connected") -> Color(0xFFE6F1E2)
                stateLabel.startsWith("Connecting") || stateLabel.startsWith("Retrying") -> Color(0xFFF7EFD8)
                else -> Color(0xFFF1E2E0)
            },
        )
        PillBadge(
            icon = Icons.Outlined.Code,
            label = hostInfo?.displayName ?: "Host unknown",
        )
        PillBadge(
            icon = Icons.Outlined.SettingsEthernet,
            label = if (planSupported) "Plan ready" else "Plan pending",
        )
    }
}

@Composable
private fun PillBadge(
    icon: ImageVector,
    label: String,
    tone: Color = Color(0xFFEDE8E1),
) {
    Surface(
        shape = CircleShape,
        color = tone,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.width(14.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MainConversationPane(
    uiState: RemodexDebugUiState,
    showDeveloperPanels: Boolean,
    onToggleDrawer: () -> Unit,
    onToggleDeveloperPanels: () -> Unit,
    onPromptChange: (String) -> Unit,
    onSendPrompt: () -> Unit,
) {
    val selectedThread = uiState.threads.firstOrNull { it.id == uiState.selectedThreadId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(34.dp),
            color = Color(0xF7FFFEFB),
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                ConversationTopBar(
                    selectedThread = selectedThread,
                    connectionState = uiState.connectionState,
                    onToggleDrawer = onToggleDrawer,
                    onToggleDeveloperPanels = onToggleDeveloperPanels,
                )
                Divider(color = Color(0xFFDCD6CD))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        SelectedThreadSummary(
                            selectedThread = selectedThread,
                            hostInfo = uiState.hostInfo,
                            errorMessage = uiState.errorMessage,
                        )
                        ConversationTimeline(
                            selectedThread = selectedThread,
                            messages = uiState.selectedMessages,
                            isLoadingThread = uiState.isLoadingThread,
                            modifier = Modifier.weight(1f),
                        )
                        AnimatedVisibility(visible = showDeveloperPanels) {
                            DeveloperPanel(
                                diagnostics = uiState.diagnostics,
                                hostInfo = uiState.hostInfo,
                            )
                        }
                        ComposerArea(
                            prompt = uiState.draftTurnInput,
                            isSending = uiState.isStartingTurn || uiState.isStartingThread,
                            selectedThreadId = uiState.selectedThreadId,
                            lastStartedTurnId = uiState.lastStartedTurnId,
                            lastTurnStartSummary = uiState.lastTurnStartSummary,
                            onPromptChange = onPromptChange,
                            onSendPrompt = onSendPrompt,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationTopBar(
    selectedThread: CodexThread?,
    connectionState: RemodexTransportState,
    onToggleDrawer: () -> Unit,
    onToggleDeveloperPanels: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFFF0EDE8),
        ) {
            IconButton(onClick = onToggleDrawer) {
                Icon(
                    imageVector = Icons.Outlined.MoreHoriz,
                    contentDescription = "Toggle sidebar",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = selectedThread?.displayTitle ?: "Remodex",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = selectedThread?.projectDisplayName ?: "Choose a conversation from the sidebar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            shape = CircleShape,
            color = Color(0xFFF0EDE8),
        ) {
            IconButton(onClick = onToggleDeveloperPanels) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "Toggle diagnostics",
                    tint = when (connectionState) {
                        is RemodexTransportState.Connected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectedThreadSummary(
    selectedThread: CodexThread?,
    hostInfo: CodexHostInfo?,
    errorMessage: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (errorMessage == null) Color(0xFF5F9961) else MaterialTheme.colorScheme.error,
                modifier = Modifier.width(10.dp),
            )
            Text(
                text = hostInfo?.let { "${it.displayName} host linked" } ?: "Host not linked yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            selectedThread?.cwd?.let { cwd ->
                Text(
                    text = cwd,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFF7E9E6),
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ConversationTimeline(
    selectedThread: CodexThread?,
    messages: List<CodexMessage>,
    isLoadingThread: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        if (selectedThread == null) {
            EmptyConversationState(
                title = "Connect, then open a conversation",
                subtitle = "The Android shell now follows the original Remodex information architecture: sidebar threads, a focused timeline, and a bottom composer ready for live turns.",
            )
            return
        }

        if (isLoadingThread) {
            EmptyConversationState(
                title = "Loading conversation",
                subtitle = "Fetching thread history from `thread/read(includeTurns=true)`.",
            )
            return
        }

        if (messages.isEmpty()) {
            EmptyConversationState(
                title = selectedThread.displayTitle,
                subtitle = "This thread has no decoded timeline items yet. Use the composer below to continue the conversation.",
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(messages.takeLast(18), key = { it.id }) { message ->
                MessageBubble(message = message)
            }
        }
    }
}

@Composable
private fun EmptyConversationState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFFEDE8E1),
        ) {
            Icon(
                imageVector = Icons.Outlined.DataObject,
                contentDescription = null,
                modifier = Modifier.padding(18.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(0.9f),
        )
    }
}

@Composable
private fun MessageBubble(message: CodexMessage) {
    val isUser = message.role == CodexMessageRole.User
    val bubbleColor = when {
        isUser -> Color(0xFFE9E5DF)
        message.kind == CodexMessageKind.FileChange -> Color(0xFFF0EEE8)
        message.kind == CodexMessageKind.CommandExecution -> Color(0xFFEAE7F8)
        else -> Color(0xFFF9F7F3)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser) {
            Text(
                text = messageRoleLabel(message),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomEnd = if (isUser) 8.dp else 20.dp,
                bottomStart = if (isUser) 20.dp else 8.dp,
            ),
            color = bubbleColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (message.kind != CodexMessageKind.Chat) {
                    Text(
                        text = messageKindLabel(message.kind),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun DeveloperPanel(
    diagnostics: RemodexTransportDiagnostics,
    hostInfo: CodexHostInfo?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFFF0ECE5),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                KeyValueRow("Last Outbound", diagnostics.lastOutboundMethod ?: "None")
                KeyValueRow("RPC Error Method", diagnostics.lastRpcErrorMethod ?: "None")
                KeyValueRow("RPC Error Code", diagnostics.lastRpcErrorCode?.toString() ?: "None")
                KeyValueRow("Plan Probe", hostInfo?.displayName ?: "No host info")
                DebugBlock("Last Outbound Payload", diagnostics.lastOutboundPayload)
                DebugBlock("Last Inbound Payload", diagnostics.lastInboundPayload)
            }
        }
    }
}

@Composable
private fun ComposerArea(
    prompt: String,
    isSending: Boolean,
    selectedThreadId: String?,
    lastStartedTurnId: String?,
    lastTurnStartSummary: String?,
    onPromptChange: (String) -> Unit,
    onSendPrompt: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComposerMetaPill(
                    icon = Icons.Outlined.FolderOpen,
                    label = selectedThreadId?.take(10)?.plus("...") ?: "No thread",
                )
                ComposerMetaPill(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    label = "Local",
                )
            }
            lastStartedTurnId?.let {
                Text(
                    text = "Turn ${it.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Color(0xFFF9F7F3),
            shadowElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    placeholder = {
                        Text("Ask for follow-up changes")
                    },
                    shape = RoundedCornerShape(20.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = lastTurnStartSummary ?: "GPT-5.4 · High",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = onSendPrompt,
                        enabled = selectedThreadId != null && !isSending,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isSending) "Sending..." else "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerMetaPill(
    icon: ImageVector,
    label: String,
) {
    Surface(
        shape = CircleShape,
        color = Color(0xFFF0ECE5),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.width(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFFF0ECE5),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(118.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DebugBlock(label: String, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: "None",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun connectionStateLabel(state: RemodexTransportState): String {
    return when (state) {
        RemodexTransportState.Disconnected -> "Disconnected"
        is RemodexTransportState.Connecting -> "Connecting ${state.attempt}"
        is RemodexTransportState.Retrying -> "Retrying ${state.attempt}"
        is RemodexTransportState.Connected -> {
            if (state.isInitialized) "Connected" else "Handshaking"
        }
        is RemodexTransportState.Failed -> {
            if (state.isPermanent) "Failed" else "Retry queued"
        }
    }
}

private fun messageRoleLabel(message: CodexMessage): String {
    return when (message.role) {
        CodexMessageRole.User -> "You"
        CodexMessageRole.Assistant -> "Remodex"
        CodexMessageRole.System -> when (message.kind) {
            CodexMessageKind.Thinking -> "Reasoning"
            CodexMessageKind.FileChange -> "Workspace"
            CodexMessageKind.CommandExecution -> "Command"
            CodexMessageKind.Plan -> "Plan"
            else -> "System"
        }
    }
}

private fun messageKindLabel(kind: CodexMessageKind): String {
    return when (kind) {
        CodexMessageKind.Chat -> "Chat"
        CodexMessageKind.Thinking -> "Reasoning"
        CodexMessageKind.FileChange -> "Diff"
        CodexMessageKind.CommandExecution -> "Command"
        CodexMessageKind.Plan -> "Plan"
        CodexMessageKind.UserInputPrompt -> "Approval"
    }
}
