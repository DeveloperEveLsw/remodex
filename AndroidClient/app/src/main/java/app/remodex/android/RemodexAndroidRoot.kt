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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.remodex.android.core.model.CodexAccessMode
import app.remodex.android.core.model.CodexCollaborationModeKind
import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexThreadRunBadgeState
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadSyncState
import app.remodex.android.core.model.GitRepoSyncResult
import app.remodex.android.core.transport.RemodexTransportDiagnostics
import app.remodex.android.core.transport.RemodexTransportState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

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
private const val MODEL_PICKER_PANEL = "model-picker"
private const val REASONING_PICKER_PANEL = "reasoning-picker"
private const val ACCESS_PICKER_PANEL = "access-picker"

@Composable
fun RemodexAndroidRoot() {
    val context = LocalContext.current
    val relaySessionStore = remember(context) {
        SharedPreferencesRemodexRelaySessionStore(context)
    }
    val viewModelFactory = remember(relaySessionStore) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return RemodexDebugViewModel(
                    relaySessionStore = relaySessionStore,
                ) as T
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                return RemodexDebugViewModel(
                    relaySessionStore = relaySessionStore,
                ) as T
            }
        }
    }
    val viewModel: RemodexDebugViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val drawerState = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed,
    )
    val scope = rememberCoroutineScope()
    var showDeveloperPanels by rememberSaveable { mutableStateOf(false) }
    var activePanel by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedThread = uiState.threads.firstOrNull { it.id == uiState.activeThreadId }
    val isConnected = uiState.connectionState is RemodexTransportState.Connected
    val shouldShowConnectionShell = isConnected || uiState.isAttemptingAutoReconnect || uiState.hasSavedRelaySession
    val canOpenBranchMenu = selectedThread?.cwd?.isNotBlank() == true &&
        !uiState.conversation.threadHasActiveOrRunningTurn(selectedThread?.id) &&
        !uiState.isLoadingGitBranchTargets &&
        !uiState.isSwitchingGitBranch

    LaunchedEffect(Unit) {
        viewModel.attemptAutoConnectOnLaunchIfNeeded()
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForegroundState(true)
                Lifecycle.Event.ON_STOP -> viewModel.setForegroundState(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    MaterialTheme(colorScheme = RemodexColorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
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
                if (shouldShowConnectionShell) {
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
                        onOpenBranchPicker = {
                            if (canOpenBranchMenu) {
                                activePanel = BRANCH_PICKER_PANEL
                            }
                        },
                        onDismissBranchPicker = { activePanel = null },
                        onOpenModelPicker = { activePanel = MODEL_PICKER_PANEL },
                        onOpenReasoningPicker = { activePanel = REASONING_PICKER_PANEL },
                        onOpenAccessPicker = { activePanel = ACCESS_PICKER_PANEL },
                        onPromptChange = viewModel::updateDraftTurnInput,
                        onSendPrompt = viewModel::startTurn,
                        onStopTurn = viewModel::interruptTurn,
                        onRefreshGitBranches = { viewModel.refreshGitBranchTargets() },
                        onSelectGitBaseBranch = { branch ->
                            viewModel.selectGitBaseBranch(branch)
                            activePanel = null
                        },
                        onSelectBranch = { branch ->
                            viewModel.switchGitBranch(branch)
                            activePanel = null
                        },
                        isBranchMenuExpanded = activePanel == BRANCH_PICKER_PANEL,
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
                    onOpenModelPicker = { activePanel = MODEL_PICKER_PANEL },
                    onOpenReasoningPicker = { activePanel = REASONING_PICKER_PANEL },
                    onOpenAccessPicker = { activePanel = ACCESS_PICKER_PANEL },
                )

                ACTION_MENU_PANEL -> ActionMenuSheet(
                    uiState = uiState,
                    showDeveloperPanels = showDeveloperPanels,
                    branchLabel = uiState.currentBranchLabel,
                    branchChoices = uiState.availableGitBranchTargets,
                    onDismiss = { activePanel = null },
                    onOpenSettings = { activePanel = SETTINGS_PANEL },
                    onOpenBranchPicker = {
                        if (canOpenBranchMenu) {
                            activePanel = BRANCH_PICKER_PANEL
                        }
                    },
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
                    onInterruptTurn = {
                        activePanel = null
                        viewModel.interruptTurn()
                    },
                )

                MODEL_PICKER_PANEL -> RuntimeOptionPickerSheet(
                    title = "Model",
                    options = uiState.availableModels.map {
                        RuntimeOptionRow(
                            id = it.id.ifBlank { it.model },
                            label = it.displayName.ifBlank { it.model.ifBlank { it.id } },
                        )
                    },
                    selectedId = uiState.selectedModelOption?.id ?: uiState.selectedModelOption?.model,
                    emptyStateMessage = "No models reported by the current local runtime.",
                    onDismiss = { activePanel = null },
                    onSelect = { optionId ->
                        viewModel.selectRuntimeModel(optionId)
                        activePanel = null
                    },
                )

                REASONING_PICKER_PANEL -> RuntimeOptionPickerSheet(
                    title = "Reasoning",
                    options = uiState.availableReasoningEffortsForSelectedModel.map { RuntimeOptionRow(it, reasoningTitle(it)) },
                    selectedId = uiState.selectedReasoningEffort,
                    emptyStateMessage = "No reasoning options are available for the selected model.",
                    onDismiss = { activePanel = null },
                    onSelect = { optionId ->
                        viewModel.selectRuntimeReasoningEffort(optionId)
                        activePanel = null
                    },
                )

                ACCESS_PICKER_PANEL -> AccessModePickerSheet(
                    selectedMode = uiState.selectedAccessMode,
                    onDismiss = { activePanel = null },
                    onSelect = { mode ->
                        viewModel.selectAccessMode(mode)
                        activePanel = null
                    },
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
            .width(320.dp)
            .fillMaxSize(),
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        color = Color(0xFFFDFBF7),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
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
                            badgeState = if (thread.id == uiState.activeThreadId) {
                                null
                            } else {
                                uiState.conversation.threadRunBadgeState(thread.id)
                            },
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
    badgeState: CodexThreadRunBadgeState?,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = thread.displayTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    badgeState?.let { state ->
                        ThreadRunBadge(state = state)
                    }
                }
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
private fun ThreadRunBadge(state: CodexThreadRunBadgeState) {
    val (label, background, foreground) = when (state) {
        CodexThreadRunBadgeState.Running -> Triple("Running", Color(0xFFE9F2FF), Color(0xFF2D6FD2))
        CodexThreadRunBadgeState.Ready -> Triple("Ready", Color(0xFFEAF6EC), Color(0xFF2F8C4C))
        CodexThreadRunBadgeState.Failed -> Triple("Failed", Color(0xFFFFECE8), Color(0xFFC65446))
    }

    Surface(
        shape = CircleShape,
        color = background,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            fontWeight = FontWeight.SemiBold,
        )
    }
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
    val connectLabel = when {
        uiState.isBusy || uiState.isAttemptingAutoReconnect -> "Connecting..."
        uiState.hasSavedRelaySession && uiState.qrPayload.isBlank() -> "Reconnect"
        else -> "Connect"
    }

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
                enabled = !uiState.isBusy && !uiState.isAttemptingAutoReconnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(connectLabel)
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
    onDismissBranchPicker: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenReasoningPicker: () -> Unit,
    onOpenAccessPicker: () -> Unit,
    onPromptChange: (String) -> Unit,
    onSendPrompt: () -> Unit,
    onStopTurn: () -> Unit,
    onRefreshGitBranches: () -> Unit,
    onSelectGitBaseBranch: (String) -> Unit,
    onSelectBranch: (String) -> Unit,
    isBranchMenuExpanded: Boolean,
) {
    val selectedThread = uiState.threads.firstOrNull { it.id == uiState.activeThreadId }
    val selectedThreadRevision = uiState.conversation.messageRevisionFor(selectedThread?.id)
    val selectedActiveTurnId = selectedThread?.id?.let(uiState.conversation.activeTurnIdByThread::get)
    val projectedTimeline = remember(selectedThread?.id, selectedThreadRevision) {
        RemodexTimelineProjector.project(
            uiState.conversation.visibleMessagesFor(selectedThread?.id)
                .sortedBy(CodexMessage::orderIndex),
        )
    }
    val isLoadingSelectedThread = uiState.conversation.isLoadingThread(selectedThread?.id)
    val isRunningSelectedThread = uiState.conversation.threadHasActiveOrRunningTurn(selectedThread?.id)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(Color(0xFFFFFEFC)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            ConversationTopBar(
                selectedThread = selectedThread,
                connectionState = uiState.connectionState,
                gitRepoSync = uiState.gitRepoSync,
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
                        messages = projectedTimeline.messages,
                        isLoadingThread = isLoadingSelectedThread,
                        isThreadRunning = isRunningSelectedThread,
                        activeTurnId = selectedActiveTurnId,
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
                        isRunningSelectedThread = isRunningSelectedThread,
                        runtimeLabel = if (uiState.selectedCollaborationMode == CodexCollaborationModeKind.Plan) {
                            "Local Plan"
                        } else {
                            "Local"
                        },
                        modelLabel = uiState.selectedModelLabel,
                        reasoningLabel = uiState.selectedReasoningLabel,
                        accessLabel = uiState.selectedAccessMode.displayName,
                        branchLabel = uiState.currentBranchLabel,
                        gitBaseBranch = uiState.effectiveGitBaseBranch,
                        defaultBranch = uiState.gitDefaultBranch,
                        onOpenSettings = onOpenSettings,
                        onOpenBranchPicker = onOpenBranchPicker,
                        onDismissBranchPicker = onDismissBranchPicker,
                        onOpenActionMenu = onOpenActionMenu,
                        onOpenModelPicker = onOpenModelPicker,
                        onOpenReasoningPicker = onOpenReasoningPicker,
                        onOpenAccessPicker = onOpenAccessPicker,
                        onPromptChange = onPromptChange,
                        onSendPrompt = onSendPrompt,
                        onStopTurn = onStopTurn,
                        onRefreshGitBranches = onRefreshGitBranches,
                        branchChoices = uiState.availableGitBranchTargets,
                        isLoadingBranches = uiState.isLoadingGitBranchTargets,
                        isSwitchingBranches = uiState.isSwitchingGitBranch,
                        isBranchMenuExpanded = isBranchMenuExpanded,
                        isBranchSelectionEnabled = selectedThread?.cwd?.isNotBlank() == true && !isRunningSelectedThread,
                        onSelectGitBaseBranch = onSelectGitBaseBranch,
                        onSelectBranch = onSelectBranch,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationTopBar(
    selectedThread: CodexThread?,
    connectionState: RemodexTransportState,
    gitRepoSync: GitRepoSyncResult?,
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
        gitRepoSync?.let { status ->
            ConversationDiffStats(status = status)
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
private fun ConversationDiffStats(status: GitRepoSyncResult) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val diffTotals = status.repoDiffTotals
        if (diffTotals != null && diffTotals.hasChanges) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "+${diffTotals.additions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF49AF63),
                )
                Text(
                    text = "-${diffTotals.deletions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFD35D5D),
                )
            }
        }
        if (status.aheadCount > 0 || status.behindCount > 0) {
            Text(
                text = "↑${status.aheadCount} ↓${status.behindCount}",
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
    isThreadRunning: Boolean,
    activeTurnId: String?,
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

        val pageSize = 40
        var visibleTailCount by remember(selectedThread.id) { mutableStateOf(pageSize) }
        val listState = rememberLazyListState()
        val timelineScope = rememberCoroutineScope()
        val visibleMessages = remember(messages, visibleTailCount) {
            messages.takeLast(visibleTailCount)
        }
        val hasEarlierMessages = visibleTailCount < messages.size
        val isScrolledToBottom by remember(listState, visibleMessages) {
            derivedStateOf {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisibleIndex >= visibleMessages.lastIndex - 1
            }
        }
        val assistantAnchorId = remember(visibleMessages, activeTurnId) {
            RemodexTimelineProjector.assistantResponseAnchorMessageId(
                messages = visibleMessages,
                activeTurnId = activeTurnId,
            )
        }

        LaunchedEffect(selectedThread.id) {
            visibleTailCount = pageSize
            if (visibleMessages.isNotEmpty()) {
                listState.scrollToItem(visibleMessages.lastIndex)
            }
        }

        LaunchedEffect(visibleMessages.size, isThreadRunning, assistantAnchorId) {
            if (visibleMessages.isEmpty()) {
                return@LaunchedEffect
            }
            if (isThreadRunning && assistantAnchorId != null) {
                val anchorIndex = visibleMessages.indexOfFirst { it.id == assistantAnchorId }
                if (anchorIndex >= 0) {
                    listState.animateScrollToItem(anchorIndex)
                    return@LaunchedEffect
                }
            }
            if (isScrolledToBottom) {
                listState.animateScrollToItem(visibleMessages.lastIndex)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (hasEarlierMessages) {
                item(key = "load-earlier") {
                    TextButton(
                        onClick = {
                            visibleTailCount = minOf(visibleTailCount + pageSize, messages.size)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Load earlier messages")
                    }
                }
            }
            items(visibleMessages, key = { it.id }) { message ->
                TranscriptMessage(message = message)
            }
        }
        AnimatedVisibility(
            visible = visibleMessages.size > 1 && !isScrolledToBottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFFFCFAF6),
                border = BorderStroke(1.dp, Color(0xFFE6E1D9)),
            ) {
                IconButton(
                    onClick = {
                        if (visibleMessages.isNotEmpty()) {
                            timelineScope.launch {
                                listState.animateScrollToItem(visibleMessages.lastIndex)
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Scroll to latest message",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
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
        HeroPreviewCard(
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
        HeroPreviewCard(
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
        HeroPreviewCard(
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
private fun HeroPreviewCard(
    modifier: Modifier = Modifier,
    rotation: Float,
    title: String,
    lines: List<String>,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFFFCFAF6),
        border = BorderStroke(1.dp, Color(0xFFE7E2DA)),
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
    onOpenModelPicker: () -> Unit,
    onOpenReasoningPicker: () -> Unit,
    onOpenAccessPicker: () -> Unit,
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
                    GroupedRow(
                        label = "Model",
                        value = uiState.selectedModelLabel,
                        supporting = if (uiState.availableModels.isEmpty()) {
                            "No models reported by the local runtime yet."
                        } else {
                            "${uiState.availableModels.size} model option(s) available."
                        },
                        onClick = onOpenModelPicker,
                    )
                    GroupedDivider()
                    GroupedRow(
                        label = "Reasoning",
                        value = uiState.selectedReasoningLabel,
                        supporting = if (uiState.availableReasoningEffortsForSelectedModel.isEmpty()) {
                            "The selected model does not expose reasoning controls."
                        } else {
                            "${uiState.availableReasoningEffortsForSelectedModel.size} reasoning level(s) available."
                        },
                        onClick = onOpenReasoningPicker,
                    )
                    GroupedDivider()
                    GroupedRow(
                        label = "Access",
                        value = uiState.selectedAccessMode.displayName,
                        supporting = "Approval policy is applied to new turn and resume requests.",
                        onClick = onOpenAccessPicker,
                    )
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
    uiState: RemodexDebugUiState,
    showDeveloperPanels: Boolean,
    branchLabel: String?,
    branchChoices: List<String>,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBranchPicker: () -> Unit,
    onRefreshThreads: () -> Unit,
    onStartThread: () -> Unit,
    onToggleDeveloperPanels: () -> Unit,
    onInterruptTurn: () -> Unit,
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
                    MenuRowModel(
                        title = if (uiState.conversation.threadHasActiveOrRunningTurn(uiState.activeThreadId)) {
                            "Stop Current Turn"
                        } else {
                            "No Running Turn"
                        },
                        subtitle = if (uiState.conversation.threadHasActiveOrRunningTurn(uiState.activeThreadId)) {
                            "Interrupt the active turn using the current thread context"
                        } else {
                            "Stop becomes available when the selected thread is running."
                        },
                        onClick = if (uiState.conversation.threadHasActiveOrRunningTurn(uiState.activeThreadId)) onInterruptTurn else null,
                    ),
                    MenuRowModel("Settings", "Open runtime defaults and connection info", onOpenSettings),
                ),
            )
            GroupedMenuSection(
                title = "Workspace",
                rows = listOf(
                    MenuRowModel(
                        title = when {
                            uiState.isSwitchingGitBranch -> "Switching..."
                            uiState.isLoadingGitBranchTargets -> "Reloading branches..."
                            !branchLabel.isNullOrBlank() -> branchLabel
                            else -> "Branch"
                        },
                        subtitle = when {
                            uiState.isLoadingGitBranchTargets -> "Refreshing local branch state from the paired host"
                            branchChoices.isEmpty() -> "Inspect local branch state and reload branch targets"
                            else -> "Browse ${branchChoices.size} known branch target(s)"
                        },
                        onClick = onOpenBranchPicker,
                    ),
                ),
            )
            GroupedMenuSection(
                title = "Runtime",
                rows = listOf(
                    MenuRowModel(
                        title = uiState.selectedModelLabel,
                        subtitle = "Model",
                        onClick = null,
                    ),
                    MenuRowModel(
                        title = uiState.selectedReasoningLabel,
                        subtitle = "Reasoning",
                        onClick = null,
                    ),
                    MenuRowModel(
                        title = uiState.selectedAccessMode.displayName,
                        subtitle = "Access",
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

private const val BRANCH_INLINE_LIMIT = 12

private enum class BranchBrowseMode(
    val id: String,
    val sectionTitle: String,
    val sheetTitle: String,
) {
    CurrentBranch(
        id = "current-branch",
        sectionTitle = "Current branch",
        sheetTitle = "Current Branch",
    ),
    PullRequestTarget(
        id = "pull-request-target",
        sectionTitle = "PR target",
        sheetTitle = "PR Target",
    );

    companion object {
        fun fromId(id: String?): BranchBrowseMode? = entries.firstOrNull { it.id == id }
    }
}

@Composable
private fun BranchRuntimeControl(
    modifier: Modifier = Modifier,
    branchLabel: String?,
    gitBaseBranch: String,
    defaultBranch: String,
    branchChoices: List<String>,
    isLoadingBranches: Boolean,
    isSwitchingBranches: Boolean,
    isBranchSelectionEnabled: Boolean,
    isMenuExpanded: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onRefreshBranches: () -> Unit,
    onSelectGitBaseBranch: (String) -> Unit,
    onSelectBranch: (String) -> Unit,
) {
    val normalizedDefaultBranch = defaultBranch.trim().takeIf(String::isNotEmpty)
    val normalizedCurrentBranch = branchLabel?.trim()?.takeIf(String::isNotEmpty).orEmpty()
    val effectiveGitBaseBranch = gitBaseBranch.trim().takeIf(String::isNotEmpty)
        ?: normalizedDefaultBranch
        ?: normalizedCurrentBranch
    val visibleBranchLabel = normalizedCurrentBranch.ifEmpty {
        normalizedDefaultBranch ?: "Branch"
    }
    val branchControlsDisabled = !isBranchSelectionEnabled || isLoadingBranches || isSwitchingBranches
    val nonDefaultBranches = remember(normalizedDefaultBranch, branchChoices) {
        branchChoices
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .filter { branch -> branch != normalizedDefaultBranch }
    }
    var browseModeId by rememberSaveable { mutableStateOf<String?>(null) }
    val browseMode = BranchBrowseMode.fromId(browseModeId)

    Box(modifier = modifier) {
        BranchRuntimePill(
            modifier = Modifier.fillMaxWidth(),
            label = visibleBranchLabel,
            enabled = !branchControlsDisabled,
            onClick = onOpenMenu,
        )
        DropdownMenu(
            expanded = isMenuExpanded && !branchControlsDisabled,
            onDismissRequest = onDismissMenu,
            modifier = Modifier.widthIn(min = 280.dp, max = 320.dp),
        ) {
            BranchDropdownSectionLabel(BranchBrowseMode.CurrentBranch.sectionTitle)
            BranchDropdownSection(
                mode = BranchBrowseMode.CurrentBranch,
                selectedBranch = normalizedCurrentBranch,
                defaultBranch = normalizedDefaultBranch,
                currentBranch = normalizedCurrentBranch,
                nonDefaultBranches = nonDefaultBranches,
                branchControlsDisabled = branchControlsDisabled,
                onSelectBranch = { branch ->
                    onDismissMenu()
                    onSelectBranch(branch)
                },
                onBrowseAll = { mode ->
                    browseModeId = mode.id
                    onDismissMenu()
                },
            )
            Divider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = Color(0xFFE6E1DA),
            )
            BranchDropdownSectionLabel(BranchBrowseMode.PullRequestTarget.sectionTitle)
            BranchDropdownSection(
                mode = BranchBrowseMode.PullRequestTarget,
                selectedBranch = effectiveGitBaseBranch,
                defaultBranch = normalizedDefaultBranch,
                currentBranch = normalizedCurrentBranch,
                nonDefaultBranches = nonDefaultBranches,
                branchControlsDisabled = branchControlsDisabled,
                onSelectBranch = { branch ->
                    onDismissMenu()
                    onSelectGitBaseBranch(branch)
                },
                onBrowseAll = { mode ->
                    browseModeId = mode.id
                    onDismissMenu()
                },
            )
            Divider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = Color(0xFFE6E1DA),
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = when {
                            isSwitchingBranches -> "Switching..."
                            isLoadingBranches -> "Reloading..."
                            else -> "Reload branch list"
                        },
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    onDismissMenu()
                    onRefreshBranches()
                },
                enabled = !branchControlsDisabled,
            )
        }
    }

    browseMode?.let { mode ->
        BranchBrowseSheet(
            mode = mode,
            branches = nonDefaultBranches,
            selectedBranch = when (mode) {
                BranchBrowseMode.CurrentBranch -> normalizedCurrentBranch
                BranchBrowseMode.PullRequestTarget -> effectiveGitBaseBranch
            },
            defaultBranch = normalizedDefaultBranch,
            currentBranch = normalizedCurrentBranch,
            isLoadingBranches = isLoadingBranches,
            isSwitchingBranches = isSwitchingBranches,
            onDismiss = { browseModeId = null },
            onRefreshBranches = onRefreshBranches,
            onSelectBranch = { branch ->
                browseModeId = null
                when (mode) {
                    BranchBrowseMode.CurrentBranch -> onSelectBranch(branch)
                    BranchBrowseMode.PullRequestTarget -> onSelectGitBaseBranch(branch)
                }
            },
        )
    }
}

@Composable
private fun BranchRuntimePill(
    modifier: Modifier = Modifier,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.alpha(if (enabled) 1f else 0.55f),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF4F2EE),
        border = BorderStroke(1.dp, Color(0xFFE6E2DB)),
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.DataObject,
                contentDescription = null,
                modifier = Modifier.width(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
private fun BranchDropdownSectionLabel(label: String) {
    Text(
        text = label,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun BranchDropdownSection(
    mode: BranchBrowseMode,
    selectedBranch: String,
    defaultBranch: String?,
    currentBranch: String,
    nonDefaultBranches: List<String>,
    branchControlsDisabled: Boolean,
    onSelectBranch: (String) -> Unit,
    onBrowseAll: (BranchBrowseMode) -> Unit,
) {
    if (defaultBranch != null) {
        val defaultBranchEnabled = !branchControlsDisabled &&
            (mode == BranchBrowseMode.CurrentBranch || defaultBranch != currentBranch)
        DropdownMenuItem(
            text = {
                Text(text = "$defaultBranch (default)")
            },
            trailingIcon = {
                if (selectedBranch == defaultBranch) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = { onSelectBranch(defaultBranch) },
            enabled = defaultBranchEnabled,
        )
    }

    prioritizedNonDefaultBranches(
        selectedBranch = selectedBranch,
        defaultBranch = defaultBranch,
        branches = nonDefaultBranches,
    )
        .take(BRANCH_INLINE_LIMIT)
        .forEach { branch ->
            val branchEnabled = !branchControlsDisabled &&
                (mode == BranchBrowseMode.CurrentBranch || branch != currentBranch)
            DropdownMenuItem(
                text = {
                    Text(text = branch)
                },
                trailingIcon = {
                    if (selectedBranch == branch) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = { onSelectBranch(branch) },
                enabled = branchEnabled,
            )
        }

    if (nonDefaultBranches.size > BRANCH_INLINE_LIMIT) {
        DropdownMenuItem(
            text = {
                Text(text = "Browse all branches (${nonDefaultBranches.size})...")
            },
            onClick = { onBrowseAll(mode) },
            enabled = !branchControlsDisabled,
        )
    }
}

@Composable
private fun BranchBrowseSheet(
    mode: BranchBrowseMode,
    branches: List<String>,
    selectedBranch: String,
    defaultBranch: String?,
    currentBranch: String,
    isLoadingBranches: Boolean,
    isSwitchingBranches: Boolean,
    onDismiss: () -> Unit,
    onRefreshBranches: () -> Unit,
    onSelectBranch: (String) -> Unit,
) {
    var query by rememberSaveable(mode.id) { mutableStateOf("") }
    val filteredBranches = remember(query, branches) {
        val normalizedQuery = query.trim().lowercase(Locale.US)
        if (normalizedQuery.isEmpty()) {
            branches
        } else {
            branches.filter { branch -> branch.lowercase(Locale.US).contains(normalizedQuery) }
        }
    }
    val branchControlsDisabled = isLoadingBranches || isSwitchingBranches

    SheetDialog(
        onDismiss = onDismiss,
        topPadding = 104.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
                Text(
                    text = mode.sheetTitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = onRefreshBranches,
                    enabled = !branchControlsDisabled,
                ) {
                    Text(
                        when {
                            isSwitchingBranches -> "Switching..."
                            isLoadingBranches -> "Refreshing..."
                            else -> "Refresh"
                        },
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                    )
                },
                placeholder = {
                    Text("Search branches")
                },
            )

            if (defaultBranch == null && filteredBranches.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFFF2F0EC),
                ) {
                    Text(
                        text = "No branches found",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                GroupedRowsCard {
                    defaultBranch?.let { branch ->
                        BranchBrowseRow(
                            branch = branch,
                            selectedBranch = selectedBranch,
                            currentBranch = currentBranch,
                            allowsSelectingCurrentBranch = mode == BranchBrowseMode.CurrentBranch,
                            isDefaultBranch = true,
                            branchControlsDisabled = branchControlsDisabled,
                            onSelect = onSelectBranch,
                        )
                        if (filteredBranches.isNotEmpty()) {
                            GroupedDivider()
                        }
                    }

                    filteredBranches.forEachIndexed { index, branch ->
                        BranchBrowseRow(
                            branch = branch,
                            selectedBranch = selectedBranch,
                            currentBranch = currentBranch,
                            allowsSelectingCurrentBranch = mode == BranchBrowseMode.CurrentBranch,
                            isDefaultBranch = false,
                            branchControlsDisabled = branchControlsDisabled,
                            onSelect = onSelectBranch,
                        )
                        if (index < filteredBranches.lastIndex) {
                            GroupedDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BranchBrowseRow(
    branch: String,
    selectedBranch: String,
    currentBranch: String,
    allowsSelectingCurrentBranch: Boolean,
    isDefaultBranch: Boolean,
    branchControlsDisabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val rowEnabled = !branchControlsDisabled &&
        (allowsSelectingCurrentBranch || branch != currentBranch)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (rowEnabled) Modifier.clickable { onSelect(branch) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (isDefaultBranch) "$branch (default)" else branch,
            style = MaterialTheme.typography.bodyLarge,
            color = if (rowEnabled || selectedBranch == branch) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (selectedBranch == branch) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun prioritizedNonDefaultBranches(
    selectedBranch: String?,
    defaultBranch: String?,
    branches: List<String>,
): List<String> {
    val normalizedSelected = selectedBranch?.trim()?.takeIf(String::isNotEmpty)
    val normalizedDefault = defaultBranch?.trim()?.takeIf(String::isNotEmpty)
    val prioritized = branches
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filter { it != normalizedDefault }
        .distinct()
        .toMutableList()

    if (normalizedSelected != null && normalizedSelected != normalizedDefault) {
        val selectedIndex = prioritized.indexOf(normalizedSelected)
        if (selectedIndex > 0) {
            val selected = prioritized.removeAt(selectedIndex)
            prioritized.add(0, selected)
        }
    }

    return prioritized
}

private data class RuntimeOptionRow(
    val id: String,
    val label: String,
)

@Composable
private fun RuntimeOptionPickerSheet(
    title: String,
    options: List<RuntimeOptionRow>,
    selectedId: String?,
    emptyStateMessage: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
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
                title = title,
                actionLabel = "Done",
                onAction = onDismiss,
            )
            if (options.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFFF2F0EC),
                ) {
                    Text(
                        text = emptyStateMessage,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                GroupedRowsCard {
                    options.forEachIndexed { index, option ->
                        if (index > 0) {
                            GroupedDivider()
                        }
                        GroupedRow(
                            label = option.label,
                            value = if (option.id == selectedId) "Selected" else null,
                            onClick = { onSelect(option.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessModePickerSheet(
    selectedMode: CodexAccessMode,
    onDismiss: () -> Unit,
    onSelect: (CodexAccessMode) -> Unit,
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
                title = "Access",
                actionLabel = "Done",
                onAction = onDismiss,
            )
            GroupedRowsCard {
                CodexAccessMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) {
                        GroupedDivider()
                    }
                    GroupedRow(
                        label = mode.displayName,
                        value = if (mode == selectedMode) "Selected" else null,
                        supporting = when (mode) {
                            CodexAccessMode.OnRequest -> "Prompts can request approval before privileged actions run."
                            CodexAccessMode.FullAccess -> "Turns run without approval prompts and use danger-full-access sandboxing."
                        },
                        onClick = { onSelect(mode) },
                    )
                }
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
                .padding(top = topPadding),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                color = Color(0xFFFBFAF7),
                shadowElevation = 4.dp,
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
                .padding(horizontal = 16.dp, vertical = 32.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFFBFAF7),
                shadowElevation = 8.dp,
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
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
    when (message.role) {
        CodexMessageRole.User -> UserTranscriptBubble(message = message)
        CodexMessageRole.Assistant -> AssistantTranscriptBlock(message = message)
        CodexMessageRole.System -> SystemTranscriptBlock(message = message)
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
    isRunningSelectedThread: Boolean,
    runtimeLabel: String,
    modelLabel: String,
    reasoningLabel: String,
    accessLabel: String,
    branchLabel: String?,
    gitBaseBranch: String,
    defaultBranch: String,
    onOpenSettings: () -> Unit,
    onOpenBranchPicker: () -> Unit,
    onDismissBranchPicker: () -> Unit,
    onOpenActionMenu: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenReasoningPicker: () -> Unit,
    onOpenAccessPicker: () -> Unit,
    onPromptChange: (String) -> Unit,
    onSendPrompt: () -> Unit,
    onStopTurn: () -> Unit,
    onRefreshGitBranches: () -> Unit,
    branchChoices: List<String>,
    isLoadingBranches: Boolean,
    isSwitchingBranches: Boolean,
    isBranchMenuExpanded: Boolean,
    isBranchSelectionEnabled: Boolean,
    onSelectGitBaseBranch: (String) -> Unit,
    onSelectBranch: (String) -> Unit,
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
                        label = modelLabel,
                        onClick = onOpenModelPicker,
                    )
                    MinimalControlChip(
                        label = reasoningLabel,
                        onClick = onOpenReasoningPicker,
                    )
                    MinimalIconChip(
                        icon = Icons.Outlined.Code,
                        contentDescription = "Quick tools",
                        onClick = onOpenActionMenu,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (isRunningSelectedThread) {
                        StopActionButton(onClick = onStopTurn)
                    } else {
                        SendActionButton(
                            enabled = selectedThreadId != null && !isSending,
                            onClick = onSendPrompt,
                        )
                    }
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
                label = runtimeLabel,
                onClick = onOpenSettings,
            )
            RuntimePill(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.SettingsEthernet,
                label = accessLabel,
                onClick = onOpenAccessPicker,
            )
            BranchRuntimeControl(
                modifier = Modifier.weight(1f),
                branchLabel = branchLabel,
                gitBaseBranch = gitBaseBranch,
                defaultBranch = defaultBranch,
                branchChoices = branchChoices,
                isLoadingBranches = isLoadingBranches,
                isSwitchingBranches = isSwitchingBranches,
                isBranchSelectionEnabled = isBranchSelectionEnabled,
                isMenuExpanded = isBranchMenuExpanded,
                onOpenMenu = onOpenBranchPicker,
                onDismissMenu = onDismissBranchPicker,
                onRefreshBranches = onRefreshGitBranches,
                onSelectGitBaseBranch = onSelectGitBaseBranch,
                onSelectBranch = onSelectBranch,
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
private fun StopActionButton(
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = Color(0xFFFFF1EF),
        border = BorderStroke(1.dp, Color(0xFFF0C9C1)),
    ) {
        Box(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Stop",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFC65446),
                fontWeight = FontWeight.SemiBold,
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (message.attachments.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                message.attachments.forEachIndexed { index, _ ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFF5F3EF),
                        border = BorderStroke(1.dp, Color(0xFFE6E1D9)),
                    ) {
                        Text(
                            text = "Image ${index + 1}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFFF1F1F1),
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        UserDeliveryMetaRow(message = message)
    }
}

@Composable
private fun AssistantTranscriptBlock(message: CodexMessage) {
    val clipboard = LocalClipboardManager.current
    val segments = remember(message.text) {
        RemodexMarkdownRenderer.parseMarkdownSegments(message.text.trim())
    }

    Column(
        modifier = Modifier.widthIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (message.text.isNotBlank()) {
            segments.forEach { segment ->
                when (segment) {
                    is MarkdownSegment.Prose -> MarkdownProseBlock(text = segment.text)
                    is MarkdownSegment.CodeBlock -> AssistantCodeBlock(
                        language = segment.language,
                        code = segment.code,
                    )
                }
            }
        }
        if (message.isStreaming) {
            Text(
                text = "Working on it...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        )
        {
            Surface(
                shape = CircleShape,
                color = Color(0xFFF5F3EF),
                border = BorderStroke(1.dp, Color(0xFFE7E2DB)),
                modifier = Modifier.clickable(enabled = message.text.isNotBlank()) {
                    clipboard.setText(AnnotatedString(message.text))
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy assistant message",
                        modifier = Modifier.width(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            MessageMetaRow(message = message, leadingIcon = null)
        }
    }
}

@Composable
private fun SystemTranscriptBlock(message: CodexMessage) {
    Column(
        modifier = Modifier.widthIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (message.kind) {
            CodexMessageKind.Thinking -> ThinkingSystemBlock(message = message)
            CodexMessageKind.FileChange -> FileChangeSystemBlock(message = message)
            CodexMessageKind.CommandExecution -> CommandExecutionSystemBlock(message = message)
            CodexMessageKind.Plan -> PlanSystemBlock(message = message)
            CodexMessageKind.UserInputPrompt -> StructuredUserInputPromptBlock(message = message)
            CodexMessageKind.Chat -> DefaultSystemBlock(message = message)
        }
    }
}

@Composable
private fun ThinkingSystemBlock(message: CodexMessage) {
    val content = remember(message.text) {
        ThinkingDisclosureParser.parse(message.text)
    }
    val normalized = content.fallbackText
    if (!message.isStreaming && normalized.isBlank()) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (normalized.isNotBlank()) {
            ThinkingDisclosureBlock(
                messageId = message.id,
                content = content,
            )
        }
        MessageMetaRow(message = message, leadingIcon = null)
    }
}

@Composable
private fun FileChangeSystemBlock(message: CodexMessage) {
    val renderState = remember(message.id, message.text) {
        RemodexFileChangeRenderState.fromSourceText(message.text)
    }
    val actionEntries = renderState.actionEntries
    val allEntries = if (actionEntries.isNotEmpty()) {
        actionEntries
    } else {
        renderState.summary?.entries.orEmpty()
    }
    val groupedEntries = remember(allEntries) {
        RemodexFileChangeGrouping.grouped(allEntries)
    }
    val diffChunks = remember(message.id, renderState.bodyText, allEntries) {
        RemodexPerFileDiffParser.parse(renderState.bodyText, allEntries)
    }
    var showDiffDialog by rememberSaveable(message.id) { mutableStateOf(false) }

    SystemCardContainer(
        message = message,
        title = "Workspace",
        tone = assistantKindTone(CodexMessageKind.FileChange),
        isStreaming = message.isStreaming,
    ) {
        if (groupedEntries.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                groupedEntries.forEach { group ->
                    FileChangeEntryGroup(group = group)
                }
            }
        } else if (message.text.isNotBlank()) {
            MarkdownProseBlock(text = message.text)
        }

        if (!message.isStreaming && allEntries.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { showDiffDialog = true },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = buildString {
                            append("Diff")
                            val additions = allEntries.sumOf { it.additions }
                            val deletions = allEntries.sumOf { it.deletions }
                            if (additions > 0) append(" +$additions")
                            if (deletions > 0) append(" -$deletions")
                        },
                    )
                }
            }
        }

        if (message.isStreaming) {
            Text(
                text = "Applying changes...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDiffDialog) {
        FileChangeDiffDialog(
            chunks = diffChunks,
            onDismiss = { showDiffDialog = false },
        )
    }
}

@Composable
private fun CommandExecutionSystemBlock(message: CodexMessage) {
    val status = parseCommandExecutionStatus(message.text)
    SystemCardContainer(
        message = message,
        title = "Command",
        tone = assistantKindTone(CodexMessageKind.CommandExecution),
        isStreaming = message.isStreaming,
    ) {
        if (status != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(34.dp)
                        .background(status.accent, RoundedCornerShape(99.dp)),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = status.command,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = status.statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = status.accent,
                    )
                }
            }
        } else if (message.text.isNotBlank()) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PlanSystemBlock(message: CodexMessage) {
    val bodyText = message.text.trim().takeUnless { it.isBlank() || it == "Planning..." }
    val explanationText = message.planState?.explanation?.trim()
        ?.takeUnless { it.isNullOrBlank() || it == bodyText }

    SystemCardContainer(
        message = message,
        title = "Plan",
        tone = assistantKindTone(CodexMessageKind.Plan),
        isStreaming = message.isStreaming,
    ) {
        bodyText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (bodyText == null && explanationText != null) {
            Text(
                text = explanationText,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (explanationText != null && bodyText != null) {
            Text(
                text = explanationText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val steps = message.planState?.steps.orEmpty()
        if (steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                steps.forEach { step ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = when (step.status.name) {
                                "Completed" -> "●"
                                "InProgress" -> "◐"
                                else -> "○"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (step.status.name) {
                                "Completed" -> Color(0xFF4DA468)
                                "InProgress" -> Color(0xFFC98935)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = step.step,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = when (step.status.name) {
                                    "Completed" -> "Completed"
                                    "InProgress" -> "In progress"
                                    else -> "Pending"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StructuredUserInputPromptBlock(message: CodexMessage) {
    val request = message.structuredUserInputRequest
    SystemCardContainer(
        message = message,
        title = "Question",
        tone = assistantKindTone(CodexMessageKind.UserInputPrompt),
        isStreaming = message.isStreaming,
    ) {
        if (request == null) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                request.questions.forEachIndexed { index, question ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (question.header.isNotBlank()) {
                            Text(
                                text = question.header.uppercase(Locale.US),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = question.question,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        question.options.forEach { option ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFFFFFEFC),
                                border = BorderStroke(1.dp, Color(0xFFE7E2DB)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (option.description.isNotBlank()) {
                                        Text(
                                            text = option.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (index < request.questions.lastIndex) {
                            Divider(color = Color(0xFFE9E3DB))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultSystemBlock(message: CodexMessage) {
    Text(
        text = message.text,
        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SystemCardContainer(
    message: CodexMessage,
    title: String,
    tone: Color,
    isStreaming: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = tone.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, Color(0xFFE7E2DB)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isStreaming) {
                    Text(
                        text = "Streaming",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            content()
        }
    }
    MessageMetaRow(message = message, leadingIcon = null)
}

private data class CommandExecutionStatus(
    val command: String,
    val statusLabel: String,
    val accent: Color,
)

private fun parseCommandExecutionStatus(text: String): CommandExecutionStatus? {
    val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val first = words.firstOrNull()?.lowercase() ?: return null
    val command = words.drop(1).joinToString(" ").trim().ifEmpty { "command" }
    return when (first) {
        "running" -> CommandExecutionStatus(command, "running", Color(0xFFC98935))
        "completed" -> CommandExecutionStatus(command, "completed", Color(0xFF4DA468))
        "failed", "stopped" -> CommandExecutionStatus(command, first, Color(0xFFD35D5D))
        else -> null
    }
}

@Composable
private fun MarkdownProseBlock(text: String) {
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AssistantCodeBlock(
    language: String?,
    code: String,
) {
    val clipboard = LocalClipboardManager.current
    val isDiffBlock = remember(code) {
        RemodexDiffLineKind.detectVerifiedPatch(code)
    }

    if (isDiffBlock) {
        DiffCodeBlock(code = code)
        return
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF7F5F1),
        border = BorderStroke(1.dp, Color(0xFFE6E2DB)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF1EDE7))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language?.takeIf(String::isNotBlank) ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = code,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 20.sp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffCodeBlock(code: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF7F5F1),
        border = BorderStroke(1.dp, Color(0xFFE6E2DB)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            code.split('\n').forEach { line ->
                val kind = RemodexDiffLineKind.classify(line)
                if (kind != RemodexDiffLineKind.Meta) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(diffBackgroundColor(kind)),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(if (kind == RemodexDiffLineKind.Addition || kind == RemodexDiffLineKind.Deletion) 2.dp else 0.dp)
                                .heightIn(min = 22.dp)
                                .background(diffIndicatorColor(kind)),
                        )
                        SelectionContainer {
                            Text(
                                text = line,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
                                color = diffTextColor(kind),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 20.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanDiffCodeBlock(code: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        code.split('\n').forEach { line ->
            when (val kind = RemodexDiffLineKind.classify(line)) {
                RemodexDiffLineKind.Meta -> Unit
                RemodexDiffLineKind.Hunk -> Divider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = Color(0xFFE4DFD6),
                )
                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(diffBackgroundColor(kind)),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(if (kind == RemodexDiffLineKind.Addition || kind == RemodexDiffLineKind.Deletion) 2.dp else 0.dp)
                                .heightIn(min = 22.dp)
                                .background(diffIndicatorColor(kind)),
                        )
                        SelectionContainer {
                            Text(
                                text = strippedDiffLine(line, kind),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
                                color = diffTextColor(kind),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 20.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileChangeEntryGroup(group: RemodexFileChangeGroup) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = group.key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        group.entries.forEach { entry ->
            FileChangeEntryRow(entry = entry)
        }
    }
}

@Composable
private fun FileChangeEntryRow(entry: RemodexFileChangeSummaryEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.compactPath,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = Color(0xFF2D6AB5),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (entry.additions > 0) {
                Text(
                    text = "+${entry.additions}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFF208C49),
                )
            }
            if (entry.deletions > 0) {
                Text(
                    text = "-${entry.deletions}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFFC74848),
                )
            }
        }
        entry.fullDirectoryPath?.let { directory ->
            Text(
                text = directory,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FileChangeDiffDialog(
    chunks: List<RemodexPerFileDiffChunk>,
    onDismiss: () -> Unit,
) {
    FloatingSheetDialog(onDismiss = onDismiss) {
        SheetHeader(
            title = "Changes",
            actionLabel = "Done",
            onAction = onDismiss,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "${chunks.size} file${if (chunks.size == 1) "" else "s"} changed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            chunks.forEach { chunk ->
                FileChangeDiffCard(chunk = chunk)
            }
        }
    }
}

@Composable
private fun FileChangeDiffCard(chunk: RemodexPerFileDiffChunk) {
    var isExpanded by rememberSaveable(chunk.id) { mutableStateOf(true) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF3F0EA),
        border = BorderStroke(1.dp, Color(0xFFE4DFD6)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer {
                        rotationZ = if (isExpanded) 0f else -90f
                    },
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = chunk.compactPath,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FileChangeActionPill(action = chunk.action)
                    }
                    chunk.fullDirectoryPath?.let { directory ->
                        Text(
                            text = directory,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (chunk.additions > 0) {
                        Text(
                            text = "+${chunk.additions}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = Color(0xFF208C49),
                        )
                    }
                    if (chunk.deletions > 0) {
                        Text(
                            text = "-${chunk.deletions}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = Color(0xFFC74848),
                        )
                    }
                }
            }

            if (isExpanded && chunk.diffCode.isNotBlank()) {
                Divider(color = Color(0xFFE4DFD6))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                ) {
                    CleanDiffCodeBlock(code = chunk.diffCode)
                }
            }
        }
    }
}

@Composable
private fun FileChangeActionPill(action: RemodexFileChangeAction) {
    val color = when (action) {
        RemodexFileChangeAction.Edited -> Color(0xFFC98935)
        RemodexFileChangeAction.Added -> Color(0xFF208C49)
        RemodexFileChangeAction.Deleted -> Color(0xFFC74848)
        RemodexFileChangeAction.Renamed -> Color(0xFF2D6AB5)
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = action.label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
        )
    }
}

@Composable
private fun ThinkingDisclosureBlock(
    messageId: String,
    content: ThinkingDisclosureContent,
) {
    var expandedSectionIds by remember(messageId) { mutableStateOf(emptySet<String>()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (content.showsDisclosure) {
            content.sections.forEach { section ->
                val isExpanded = expandedSectionIds.contains(section.id)
                val hasDetail = section.detail.isNotBlank()

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = hasDetail) {
                                expandedSectionIds = if (isExpanded) {
                                    expandedSectionIds - section.id
                                } else {
                                    expandedSectionIds + section.id
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null,
                            tint = if (hasDetail) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            },
                            modifier = Modifier
                                .width(14.dp)
                                .graphicsLayer {
                                    rotationZ = if (isExpanded) 0f else -90f
                                },
                        )
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (isExpanded && hasDetail) {
                        SelectionContainer {
                            Text(
                                text = section.detail,
                                modifier = Modifier.padding(start = 22.dp),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    lineHeight = 20.sp,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else if (content.fallbackText.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = content.fallbackText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun diffTextColor(kind: RemodexDiffLineKind): Color {
    return when (kind) {
        RemodexDiffLineKind.Addition -> Color(0xFF208C49)
        RemodexDiffLineKind.Deletion -> Color(0xFFC74848)
        RemodexDiffLineKind.Hunk -> Color(0xFF6679A6)
        RemodexDiffLineKind.Meta -> Color(0xFF8C887F)
        RemodexDiffLineKind.Neutral -> Color(0xFF161616)
    }
}

private fun diffBackgroundColor(kind: RemodexDiffLineKind): Color {
    return when (kind) {
        RemodexDiffLineKind.Addition -> Color(0x1F208C49)
        RemodexDiffLineKind.Deletion -> Color(0x1FC74848)
        else -> Color.Transparent
    }
}

private fun diffIndicatorColor(kind: RemodexDiffLineKind): Color {
    return when (kind) {
        RemodexDiffLineKind.Addition -> Color(0xFF208C49)
        RemodexDiffLineKind.Deletion -> Color(0xFFC74848)
        else -> Color.Transparent
    }
}

private fun strippedDiffLine(
    line: String,
    kind: RemodexDiffLineKind,
): String {
    return when (kind) {
        RemodexDiffLineKind.Addition, RemodexDiffLineKind.Deletion -> line.drop(1)
        RemodexDiffLineKind.Neutral -> if (line.startsWith(" ")) line.drop(1) else line
        RemodexDiffLineKind.Hunk,
        RemodexDiffLineKind.Meta,
        -> line
    }
}

@Composable
private fun UserDeliveryMetaRow(message: CodexMessage) {
    val deliveryText = when (message.deliveryState.name) {
        "Pending" -> "sending..."
        "Failed" -> "send failed"
        else -> formatMessageTimestamp(message.createdAt)
    }
    if (deliveryText == null) {
        return
    }
    Text(
        text = deliveryText,
        style = MaterialTheme.typography.bodySmall,
        color = if (message.deliveryState.name == "Failed") {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
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

private val MESSAGE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.US)
