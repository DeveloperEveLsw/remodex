package app.remodex.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadSyncState
import app.remodex.android.core.protocol.intValue
import app.remodex.android.core.protocol.objectValue
import app.remodex.android.core.protocol.stringValue
import app.remodex.android.core.transport.RemodexTransportDiagnostics
import app.remodex.android.core.transport.RemodexTransportState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private val RemodexColorScheme = lightColorScheme(
    background = Color(0xFFF4F3F0),
    surface = Color(0xFFFFFEFC),
    surfaceVariant = Color(0xFFF5F3EF),
    onBackground = Color(0xFF141414),
    onSurface = Color(0xFF161616),
    onSurfaceVariant = Color(0xFF8C887F),
    primary = Color(0xFF57ACFF),
    onPrimary = Color(0xFFFFFFFF),
    outline = Color(0xFFE6E2DB),
    error = Color(0xFFD86358),
    onError = Color(0xFFFFFFFF),
)

private const val SETTINGS_PANEL = "settings"
private const val ACTION_MENU_PANEL = "action-menu"
private const val BRANCH_PICKER_PANEL = "branch-picker"

@Composable
fun RemodexAndroidRoot() {
    val viewModel: RemodexDebugViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed,
    )
    val scope = rememberCoroutineScope()
    var showDeveloperPanels by rememberSaveable { mutableStateOf(false) }
    var activePanel by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedThread = uiState.threads.firstOrNull { it.id == uiState.activeThreadId }
    val gitChrome = remember(selectedThread) { selectedThread?.gitChrome() }
    val isConnected = uiState.connectionState is RemodexTransportState.Connected

    MaterialTheme(colorScheme = RemodexColorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFFF8F7F4), Color(0xFFEDEAE4)),
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
                        onOpenSettings = { activePanel = SETTINGS_PANEL },
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
                if (isConnected) {
                    MainConversationPane(
                        uiState = uiState,
                        showDeveloperPanels = showDeveloperPanels,
                        onToggleDrawer = {
                            scope.launch {
                                if (drawerState.isOpen) drawerState.close() else drawerState.open()
                            }
                        },
                        onOpenSettings = { activePanel = SETTINGS_PANEL },
                        onOpenActionMenu = { activePanel = ACTION_MENU_PANEL },
                        onOpenBranchPicker = { activePanel = BRANCH_PICKER_PANEL },
                        onPromptChange = viewModel::updateDraftTurnInput,
                        onSendPrompt = viewModel::startTurn,
                    )
                } else {
                    OnboardingState(
                        onOpenSetup = {
                            scope.launch { drawerState.open() }
                        },
                    )
                }
            }

            when (activePanel) {
                SETTINGS_PANEL -> SettingsSheet(
                    uiState = uiState,
                    onDismiss = { activePanel = null },
                    onDisconnect = viewModel::disconnect,
                )

                ACTION_MENU_PANEL -> ActionMenuSheet(
                    showDeveloperPanels = showDeveloperPanels,
                    branchLabel = gitChrome?.branch,
                    onDismiss = { activePanel = null },
                    onOpenSettings = { activePanel = SETTINGS_PANEL },
                    onRefreshThreads = {
                        activePanel = null
                        viewModel.refreshThreads()
                    },
                    onStartThread = {
                        activePanel = null
                        viewModel.startThread()
                    },
                    onToggleDeveloperPanels = {
                        showDeveloperPanels = !showDeveloperPanels
                        activePanel = null
                    },
                )

                BRANCH_PICKER_PANEL -> BranchPickerSheet(
                    branchLabel = gitChrome?.branch,
                    selectedThread = selectedThread,
                    onDismiss = { activePanel = null },
                )
            }
        }
    }
}

@Composable
private fun SidebarDrawer(
    uiState: RemodexDebugUiState,
    showDeveloperPanels: Boolean,
    onOpenSettings: () -> Unit,
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
            SidebarHeader(onOpenSettings = onOpenSettings)
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
                            isSelected = thread.id == uiState.activeThreadId,
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
private fun SidebarHeader(onOpenSettings: () -> Unit) {
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
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onOpenSettings) {
            Text("Settings")
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
    onOpenSettings: () -> Unit,
    onOpenActionMenu: () -> Unit,
    onOpenBranchPicker: () -> Unit,
    onPromptChange: (String) -> Unit,
    onSendPrompt: () -> Unit,
) {
    val selectedThread = uiState.threads.firstOrNull { it.id == uiState.activeThreadId }
    val selectedThreadRevision = uiState.conversation.messageRevisionFor(selectedThread?.id)
    val selectedMessages = remember(selectedThread?.id, selectedThreadRevision) {
        uiState.conversation.messagesFor(selectedThread?.id)
            .sortedBy(CodexMessage::orderIndex)
    }
    val isLoadingSelectedThread = uiState.conversation.isLoadingThread(selectedThread?.id)
    val gitChrome = remember(selectedThread) { selectedThread?.gitChrome() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(30.dp),
            color = Color(0xFCFFFEFC),
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                ConversationTopBar(
                    selectedThread = selectedThread,
                    connectionState = uiState.connectionState,
                    gitChrome = gitChrome,
                    onToggleDrawer = onToggleDrawer,
                    onOpenActionMenu = onOpenActionMenu,
                )
                Divider(color = Color(0xFFE7E3DD))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        ConversationStatusStrip(
                            hostInfo = uiState.hostInfo,
                            errorMessage = uiState.errorMessage,
                        )
                        ConversationTimeline(
                            selectedThread = selectedThread,
                            messages = selectedMessages,
                            isLoadingThread = isLoadingSelectedThread,
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
                            selectedThreadId = uiState.activeThreadId,
                            branchLabel = gitChrome?.branch,
                            onOpenSettings = onOpenSettings,
                            onOpenBranchPicker = onOpenBranchPicker,
                            onOpenActionMenu = onOpenActionMenu,
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
    gitChrome: ThreadGitChrome?,
    onToggleDrawer: () -> Unit,
    onOpenActionMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFFF4F2EE),
            border = BorderStroke(1.dp, Color(0xFFE7E3DC)),
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
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = selectedThread?.cwd ?: "Choose a conversation from the sidebar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        gitChrome?.let { chrome ->
            ConversationDiffStats(chrome = chrome)
        }
        Surface(
            shape = CircleShape,
            color = Color(0xFFF4F2EE),
            border = BorderStroke(1.dp, Color(0xFFE7E3DC)),
        ) {
            IconButton(onClick = onOpenActionMenu) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "Open action menu",
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
private fun ConversationDiffStats(chrome: ThreadGitChrome) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (chrome.additions != null || chrome.deletions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chrome.additions?.let { additions ->
                    Text(
                        text = "+$additions",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF49AF63),
                    )
                }
                chrome.deletions?.let { deletions ->
                    Text(
                        text = "-$deletions",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFD35D5D),
                    )
                }
            }
        }
        if (chrome.aheadCount > 0 || chrome.behindCount > 0) {
            Text(
                text = "↑${chrome.aheadCount} ↓${chrome.behindCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConversationStatusStrip(
    hostInfo: CodexHostInfo?,
    errorMessage: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        hostInfo?.let {
            InlineInfoBadge(
                icon = Icons.Outlined.Circle,
                label = "${it.displayName} linked",
                iconTint = Color(0xFF5BA86A),
            )
        }
        if (errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFFF1EF),
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
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
                title = "Open or create a conversation",
                subtitle = "Use the sidebar to jump between local threads, or create a new chat to start streaming into this timeline.",
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
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                TranscriptMessage(message = message)
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
                modifier = Modifier.padding(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
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
private fun OnboardingState(onOpenSetup: () -> Unit) {
    val steps = listOf(
        "Install the package" to "npm install -g remodex",
        "Start Remodex on your Mac" to "remodex up",
        "Scan the QR code" to "Open connection setup and pair with your local bridge.",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            OnboardingHero()
            Surface(
                shape = CircleShape,
                color = Color(0xFF111111),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ">_",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Remodex",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Control your local Codex session from Android.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                steps.forEachIndexed { index, (title, command) ->
                    OnboardingStepCard(
                        number = index + 1,
                        title = title,
                        detail = command,
                    )
                }
            }
            Button(
                onClick = onOpenSetup,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF111111),
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("Scan QR Code")
            }
            InlineInfoBadge(
                icon = Icons.Outlined.SettingsEthernet,
                label = "End-to-end encrypted",
            )
        }
    }
}

@Composable
private fun OnboardingHero() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroPhoneCard(
            modifier = Modifier
                .weight(1f)
                .alpha(0.78f),
            rotation = -12f,
            title = "Diff",
            lines = listOf(
                "codex_connection.swift",
                "+ restore reconnect state",
                "+ keep stop visible",
                "- flatten placeholder rows",
            ),
        )
        HeroPhoneCard(
            modifier = Modifier.weight(1.1f),
            rotation = 0f,
            title = "Remodex",
            lines = listOf(
                "Local relay linked",
                "Analyze project thoroughly",
                "assistant stream connected",
                "timeline updates live",
            ),
        )
        HeroPhoneCard(
            modifier = Modifier
                .weight(1f)
                .alpha(0.78f),
            rotation = 12f,
            title = "Threads",
            lines = listOf(
                "New Chat",
                "Hello",
                "Workspace sync",
                "Archived Chats",
            ),
        )
    }
}

@Composable
private fun HeroPhoneCard(
    modifier: Modifier = Modifier,
    rotation: Float,
    title: String,
    lines: List<String>,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFFFFFEFC),
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            lines.forEachIndexed { index, line ->
                Text(
                    text = line,
                    style = if (index == 0) {
                        MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OnboardingStepCard(
    number: Int,
    title: String,
    detail: String,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFFF2F0EC),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF111111),
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFFEFC),
                ) {
                    Text(
                        text = detail,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSheet(
    uiState: RemodexDebugUiState,
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit,
) {
    SheetDialog(
        onDismiss = onDismiss,
        topPadding = 48.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SheetHeader(
                title = "Settings",
                actionLabel = "Close",
                onAction = onDismiss,
            )
            SettingsSection(
                title = "Archived Chats",
            ) {
                GroupedRowsCard {
                    GroupedRow(
                        label = "Archived Chats",
                        value = "Local only",
                        supporting = "Archive state is preserved per thread inside the local workspace.",
                    )
                }
            }
            SettingsSection(title = "Appearance") {
                GroupedRowsCard {
                    GroupedRow(
                        label = "Font",
                        value = "System",
                        supporting = "Keep body text native and code rows monospace, matching the iOS reference hierarchy.",
                    )
                }
            }
            SettingsSection(title = "Notifications") {
                GroupedRowsCard {
                    GroupedRow(
                        label = "Status",
                        value = if (uiState.connectionState is RemodexTransportState.Connected) "Authorized" else "Unavailable",
                        supporting = "Local alerts can be surfaced once background run completion wiring lands.",
                    )
                }
            }
            SettingsSection(title = "Runtime Defaults") {
                GroupedRowsCard {
                    GroupedRow(label = "Model", value = "GPT-5.4")
                    GroupedDivider()
                    GroupedRow(label = "Reasoning", value = "Extra High")
                    GroupedDivider()
                    GroupedRow(label = "Speed", value = "Normal")
                    GroupedDivider()
                    GroupedRow(label = "Access", value = "On-Request")
                }
            }
            SettingsSection(title = "Connection") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFFF2F0EC),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Status: ${connectionStateLabel(uiState.connectionState).lowercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Security: local relay pairing",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF63A76B),
                        )
                        uiState.hostInfo?.displayName?.let { hostName ->
                            Text(
                                text = "Trusted Host: $hostName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }
            SettingsSection(title = "About") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFFF2F0EC),
                ) {
                    Text(
                        text = "Chats stay local-first between Android and your paired host. Relay metadata and runtime defaults remain scoped to the current local session.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionMenuSheet(
    showDeveloperPanels: Boolean,
    branchLabel: String?,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshThreads: () -> Unit,
    onStartThread: () -> Unit,
    onToggleDeveloperPanels: () -> Unit,
) {
    FloatingSheetDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupedMenuSection(
                title = "Conversation",
                rows = listOf(
                    MenuRowModel("New Chat", "Create a fresh local thread", onStartThread),
                    MenuRowModel("Refresh Threads", "Reload thread metadata", onRefreshThreads),
                    MenuRowModel("Settings", "Open runtime defaults and connection info", onOpenSettings),
                ),
            )
            GroupedMenuSection(
                title = "Workspace",
                rows = listOf(
                    MenuRowModel(
                        title = branchLabel ?: "No branch",
                        subtitle = "Current branch preview",
                        onClick = null,
                    ),
                ),
            )
            GroupedMenuSection(
                title = "Developer",
                rows = listOf(
                    MenuRowModel(
                        title = if (showDeveloperPanels) "Hide Diagnostics" else "Show Diagnostics",
                        subtitle = "Toggle the local transport debug panel",
                        onClick = onToggleDeveloperPanels,
                    ),
                ),
            )
        }
    }
}

@Composable
private fun BranchPickerSheet(
    branchLabel: String?,
    selectedThread: CodexThread?,
    onDismiss: () -> Unit,
) {
    SheetDialog(
        onDismiss = onDismiss,
        topPadding = 120.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(
                title = "Branch",
                actionLabel = "Done",
                onAction = onDismiss,
            )
            SettingsSection(title = "Current Repository") {
                GroupedRowsCard {
                    GroupedRow(
                        label = branchLabel ?: "No branch detected",
                        value = selectedThread?.projectDisplayName ?: "No project",
                        supporting = selectedThread?.cwd ?: "Select a local conversation to inspect branch metadata.",
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFF2F0EC),
            ) {
                Text(
                    text = "Branch switching UI now follows the iOS grouped-sheet pattern. Actual branch mutations stay deferred until runtime actions are wired.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SheetDialog(
    onDismiss: () -> Unit,
    topPadding: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x3D000000))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topPadding),
                shape = RoundedCornerShape(30.dp),
                color = Color(0xFFFBFAF7),
                shadowElevation = 14.dp,
            ) {
                Column(content = content)
            }
        }
    }
}

@Composable
private fun FloatingSheetDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x24000000))
                .padding(horizontal = 22.dp, vertical = 84.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFFFBFAF7),
                shadowElevation = 18.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(56.dp))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title = title)
        content()
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun GroupedRowsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFF2F0EC),
    ) {
        Column(content = content)
    }
}

@Composable
private fun GroupedDivider() {
    Divider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = Color(0xFFE6E1DA),
    )
}

@Composable
private fun GroupedRow(
    label: String,
    value: String? = null,
    supporting: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF57ACFF),
                )
            }
        }
        supporting?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class MenuRowModel(
    val title: String,
    val subtitle: String,
    val onClick: (() -> Unit)?,
)

@Composable
private fun GroupedMenuSection(
    title: String,
    rows: List<MenuRowModel>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFFF2F0EC),
        ) {
            Column {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        Divider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = Color(0xFFE6E1DA),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (row.onClick != null) {
                                    Modifier.clickable(onClick = row.onClick)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (row.onClick != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = row.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptMessage(message: CodexMessage) {
    val isUser = message.role == CodexMessageRole.User

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isUser) {
            UserTranscriptBubble(message = message)
        } else {
            AssistantTranscriptBlock(message = message)
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
    branchLabel: String?,
    onOpenSettings: () -> Unit,
    onOpenBranchPicker: () -> Unit,
    onOpenActionMenu: () -> Unit,
    onPromptChange: (String) -> Unit,
    onSendPrompt: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFFF8F7F4),
            border = BorderStroke(1.dp, Color(0xFFE8E4DE)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 22.sp,
                    ),
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (prompt.isBlank()) {
                                Text(
                                    text = "Ask Remodex anything, @ to add files, $ for skills",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MinimalIconChip(
                        icon = Icons.Outlined.Add,
                        contentDescription = "Add attachment or context",
                    )
                    MinimalControlChip(
                        label = "GPT-5.4",
                        onClick = onOpenSettings,
                    )
                    MinimalControlChip(
                        label = "Extra High",
                        onClick = onOpenSettings,
                    )
                    MinimalIconChip(
                        icon = Icons.Outlined.Code,
                        contentDescription = "Quick tools",
                        onClick = onOpenActionMenu,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    SendActionButton(
                        enabled = selectedThreadId != null && !isSending,
                        onClick = onSendPrompt,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuntimePill(
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                label = "Local",
                onClick = onOpenSettings,
            )
            RuntimePill(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.SettingsEthernet,
                label = "On-Request",
                onClick = onOpenSettings,
            )
            RuntimePill(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.FolderOpen,
                label = branchLabel ?: "No branch",
                onClick = onOpenBranchPicker,
            )
        }
    }
}

@Composable
private fun MinimalIconChip(
    icon: ImageVector,
    contentDescription: String?,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = CircleShape,
        color = Color(0xFFF1EFEB),
        border = BorderStroke(1.dp, Color(0xFFE6E1D9)),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.width(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MinimalControlChip(
    label: String,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = CircleShape,
        color = Color(0xFFF1EFEB),
        border = BorderStroke(1.dp, Color(0xFFE6E1D9)),
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.width(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SendActionButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.primary else Color(0xFFE3E0DB),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Send,
                contentDescription = "Send prompt",
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(16.dp),
            )
        }
    }
}

@Composable
private fun RuntimePill(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF4F2EE),
        border = BorderStroke(1.dp, Color(0xFFE6E2DB)),
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InlineInfoBadge(
    icon: ImageVector,
    label: String,
    iconTint: Color? = null,
) {
    val resolvedIconTint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = CircleShape,
        color = Color(0xFFF5F3EF),
        border = BorderStroke(1.dp, Color(0xFFE7E2DB)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.width(12.dp),
                tint = resolvedIconTint,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UserTranscriptBubble(message: CodexMessage) {
    Surface(
        modifier = Modifier.widthIn(max = 240.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF1F1F1),
    ) {
        Text(
            text = message.text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    MessageMetaRow(
        message = message,
        leadingIcon = null,
    )
}

@Composable
private fun AssistantTranscriptBlock(message: CodexMessage) {
    Column(
        modifier = Modifier.widthIn(max = 340.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (message.kind != CodexMessageKind.Chat) {
            Surface(
                shape = CircleShape,
                color = assistantKindTone(message.kind),
            ) {
                Text(
                    text = messageKindLabel(message.kind),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        MessageMetaRow(
            message = message,
            leadingIcon = Icons.Outlined.ContentCopy,
        )
    }
}

@Composable
private fun MessageMetaRow(
    message: CodexMessage,
    leadingIcon: ImageVector?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.width(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
            )
        }
        formatMessageTimestamp(message.createdAt)?.let { formatted ->
            Text(
                text = formatted,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (message.isStreaming) {
            Text(
                text = "Streaming",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
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

private data class ThreadGitChrome(
    val branch: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val aheadCount: Int = 0,
    val behindCount: Int = 0,
)

private fun CodexThread.gitChrome(): ThreadGitChrome? {
    val metadataObject = metadata?.let(::JsonObject) ?: return null
    val statusObject = metadataObject.findObject("status", "gitStatus", "repoStatus")
    val diffObject = statusObject?.findObject("repoDiffTotals", "diff")
        ?: metadataObject.findObject("repoDiffTotals", "diff")

    val branch = statusObject?.findString("currentBranch", "branch", "current")
        ?: metadataObject.findString("currentBranch", "branch", "current")
    val additions = diffObject?.findInt("additions")
    val deletions = diffObject?.findInt("deletions")
    val aheadCount = statusObject?.findInt("ahead", "aheadCount")
        ?: metadataObject.findInt("ahead", "aheadCount")
        ?: 0
    val behindCount = statusObject?.findInt("behind", "behindCount")
        ?: metadataObject.findInt("behind", "behindCount")
        ?: 0

    if (branch == null && additions == null && deletions == null && aheadCount == 0 && behindCount == 0) {
        return null
    }

    return ThreadGitChrome(
        branch = branch,
        additions = additions,
        deletions = deletions,
        aheadCount = aheadCount,
        behindCount = behindCount,
    )
}

private fun assistantKindTone(kind: CodexMessageKind): Color {
    return when (kind) {
        CodexMessageKind.Thinking -> Color(0xFFF1F3FA)
        CodexMessageKind.FileChange -> Color(0xFFF4F1EB)
        CodexMessageKind.CommandExecution -> Color(0xFFF1F4F8)
        CodexMessageKind.Plan -> Color(0xFFF3F2F8)
        CodexMessageKind.UserInputPrompt -> Color(0xFFFFF3EE)
        CodexMessageKind.Chat -> Color.Transparent
    }
}

private fun formatMessageTimestamp(createdAt: Instant?): String? {
    if (createdAt == null) {
        return null
    }
    return MESSAGE_TIME_FORMATTER.format(createdAt.atZone(ZoneId.systemDefault()))
}

private fun JsonObject.findString(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key -> this[key]?.stringValue?.takeIf(String::isNotBlank) }
}

private fun JsonObject.findInt(vararg keys: String): Int? {
    return keys.firstNotNullOfOrNull { key -> this[key]?.intValue }
}

private fun JsonObject.findObject(vararg keys: String): JsonObject? {
    return keys.firstNotNullOfOrNull { key -> this[key]?.objectValue }
}

private val MESSAGE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.US)
