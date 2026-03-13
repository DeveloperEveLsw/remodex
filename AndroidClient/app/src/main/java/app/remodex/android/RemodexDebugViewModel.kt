package app.remodex.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.remodex.android.core.model.CodexAccessMode
import app.remodex.android.core.model.CodexCollaborationModeKind
import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageDeliveryState
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexModelOption
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadSyncState
import app.remodex.android.core.model.GitRepoSyncResult
import app.remodex.android.core.pairing.RemodexPairingParser
import app.remodex.android.core.pairing.RemodexPairingPayload
import app.remodex.android.core.transport.RemodexHandshakeResult
import app.remodex.android.core.transport.RemodexTransportClient
import app.remodex.android.core.transport.RemodexTransportDiagnostics
import app.remodex.android.core.transport.RemodexTransportException
import app.remodex.android.core.transport.RemodexTransportFailureKind
import app.remodex.android.core.transport.RemodexThreadReadResult
import app.remodex.android.core.transport.RemodexThreadStartResult
import app.remodex.android.core.transport.RemodexTransportState
import app.remodex.android.core.transport.RemodexTurnStartResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class RemodexDebugUiState(
    val qrPayload: String = "",
    val parsedPairing: RemodexPairingPayload? = null,
    val hasSavedRelaySession: Boolean = false,
    val shouldAutoReconnectOnForeground: Boolean = false,
    val isAttemptingAutoReconnect: Boolean = false,
    val connectionState: RemodexTransportState = RemodexTransportState.Disconnected,
    val lastNotificationMethod: String? = null,
    val lastServerRequestMethod: String? = null,
    val hostInfo: CodexHostInfo? = null,
    val supportsPlanCollaborationMode: Boolean = false,
    val sessionUrl: String? = null,
    val diagnostics: RemodexTransportDiagnostics = RemodexTransportDiagnostics(),
    val availableModels: List<CodexModelOption> = emptyList(),
    val selectedModelId: String? = null,
    val selectedReasoningEffort: String? = null,
    val selectedAccessMode: CodexAccessMode = CodexAccessMode.OnRequest,
    val availableCollaborationModes: List<CodexCollaborationModeKind> = listOf(CodexCollaborationModeKind.Default),
    val selectedCollaborationMode: CodexCollaborationModeKind = CodexCollaborationModeKind.Default,
    val currentGitBranch: String = "",
    val gitDefaultBranch: String = "",
    val selectedGitBaseBranch: String = "",
    val availableGitBranchTargets: List<String> = emptyList(),
    val isLoadingGitBranchTargets: Boolean = false,
    val isSwitchingGitBranch: Boolean = false,
    val gitRepoSync: GitRepoSyncResult? = null,
    val threads: List<CodexThread> = emptyList(),
    val conversation: RemodexConversationState = RemodexConversationState(),
    val draftTurnInput: String = "",
    val isStartingThread: Boolean = false,
    val isStartingTurn: Boolean = false,
    val lastStartedTurnId: String? = null,
    val lastTurnStartSummary: String? = null,
    val isLoadingThreads: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
) {
    val activeThreadId: String?
        get() = conversation.activeThreadId

    val selectedModelOption: CodexModelOption?
        get() = availableModels.firstOrNull { it.id == selectedModelId || it.model == selectedModelId }

    val selectedModelLabel: String
        get() = selectedModelOption?.displayTitle() ?: "Select model"

    val selectedReasoningLabel: String
        get() = selectedReasoningEffort?.let(::reasoningTitle) ?: "Select reasoning"

    val availableReasoningEffortsForSelectedModel: List<String>
        get() = selectedModelOption?.supportedReasoningEfforts
            ?.map { it.reasoningEffort.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    val currentBranchLabel: String?
        get() = currentGitBranch.trim().takeIf(String::isNotEmpty)
            ?: gitDefaultBranch.trim().takeIf(String::isNotEmpty)

    val effectiveGitBaseBranch: String
        get() = selectedGitBaseBranch.trim().takeIf(String::isNotEmpty)
            ?: gitDefaultBranch.trim().takeIf(String::isNotEmpty)
            ?: currentGitBranch.trim()
}

class RemodexDebugViewModel(
    private val transport: RemodexTransportClient = RemodexTransportClient(appVersion = APP_VERSION),
    private val relaySessionStore: RemodexRelaySessionStore = InMemoryRemodexRelaySessionStore(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemodexDebugUiState())
    val uiState: StateFlow<RemodexDebugUiState> = _uiState.asStateFlow()
    private var gitStatusRefreshJob: Job? = null
    private var gitBranchRefreshThreadId: String? = null
    private var hasAttemptedInitialAutoConnect = false
    private var isAppInForeground = true
    private var isRunningAutoReconnect = false
    private var savedRelayPairing: RemodexPairingPayload? = null

    init {
        restoreSavedRelayPairing()

        viewModelScope.launch {
            transport.state.collect { state ->
                val previousState = _uiState.value.connectionState
                _uiState.update { current ->
                    val connectedState = state as? RemodexTransportState.Connected
                    current.copy(
                        connectionState = state,
                        hostInfo = connectedState?.hostInfo,
                        supportsPlanCollaborationMode = connectedState?.supportsPlanCollaborationMode ?: false,
                    )
                }

                handleTransportStateTransition(
                    previousState = previousState,
                    state = state,
                )

                val isNewInitializedConnection = state is RemodexTransportState.Connected &&
                    state.isInitialized &&
                    (previousState !is RemodexTransportState.Connected || !previousState.isInitialized)
                if (isNewInitializedConnection) {
                    val activeThreadId = _uiState.value.activeThreadId
                    refreshRuntimeOptions()
                    refreshThreads()
                    if (!activeThreadId.isNullOrBlank()) {
                        prepareThreadForDisplay(activeThreadId, forceHydration = true)
                    }
                }
            }
        }

        viewModelScope.launch {
            transport.diagnostics.collect { diagnostics ->
                _uiState.update { current ->
                    current.copy(diagnostics = diagnostics)
                }
            }
        }

        viewModelScope.launch {
            transport.notifications.collect { message ->
                var refreshGitBranchesForActiveThread = false
                var scheduleGitStatusRefreshForActiveThread = false
                var activeThreadIdForRefresh: String? = null
                _uiState.update { current ->
                    val activeThreadId = current.activeThreadId
                    val previousRepoRefreshSignal = repoRefreshSignal(
                        conversation = current.conversation,
                        threadId = activeThreadId,
                    )
                    val wasRunningActiveThread = current.conversation.threadHasActiveOrRunningTurn(activeThreadId)
                    val updatedConversation = RemodexConversationReducer.reduce(
                        conversation = current.conversation,
                        message = message,
                        knownThreadIds = current.threads.mapTo(linkedSetOf(), CodexThread::id),
                    )
                    val updatedRepoRefreshSignal = repoRefreshSignal(
                        conversation = updatedConversation,
                        threadId = activeThreadId,
                    )
                    val isRunningActiveThread = updatedConversation.threadHasActiveOrRunningTurn(activeThreadId)
                    refreshGitBranchesForActiveThread = wasRunningActiveThread && !isRunningActiveThread
                    scheduleGitStatusRefreshForActiveThread = previousRepoRefreshSignal != updatedRepoRefreshSignal &&
                        updatedRepoRefreshSignal != null
                    activeThreadIdForRefresh = activeThreadId

                    current.copy(
                        lastNotificationMethod = message.method,
                        conversation = updatedConversation,
                    )
                }
                if (refreshGitBranchesForActiveThread) {
                    refreshGitBranchTargets(activeThreadIdForRefresh)
                }
                if (scheduleGitStatusRefreshForActiveThread) {
                    scheduleGitStatusRefresh(activeThreadIdForRefresh)
                }
            }
        }

        viewModelScope.launch {
            transport.serverRequests.collect { message ->
                _uiState.update { current ->
                    current.copy(lastServerRequestMethod = message.method)
                }
            }
        }
    }

    fun attemptAutoConnectOnLaunchIfNeeded() {
        if (hasAttemptedInitialAutoConnect) {
            return
        }
        hasAttemptedInitialAutoConnect = true

        if (savedRelayPairing == null || isTransportConnectedOrConnecting()) {
            return
        }

        viewModelScope.launch {
            runCatching {
                connectWithAutoRecovery(
                    pairing = savedRelayPairing ?: return@launch,
                    performAutoRetry = true,
                )
            }
        }
    }

    fun setForegroundState(isForeground: Boolean) {
        if (isAppInForeground == isForeground) {
            return
        }

        isAppInForeground = isForeground
        if (isForeground) {
            attemptAutoReconnectOnForegroundIfNeeded()
        }
    }

    fun updateQrPayload(value: String) {
        _uiState.update { current ->
            current.copy(
                qrPayload = value,
                errorMessage = null,
            )
        }
    }

    fun updateDraftTurnInput(value: String) {
        _uiState.update { current ->
            current.copy(
                draftTurnInput = value,
                errorMessage = null,
            )
        }
    }

    fun selectRuntimeModel(modelId: String?) {
        _uiState.update { current ->
            normalizeRuntimeSelection(
                current.copy(
                    selectedModelId = modelId?.trim()?.takeIf(String::isNotEmpty),
                    errorMessage = null,
                ),
            )
        }
    }

    fun selectRuntimeReasoningEffort(reasoningEffort: String?) {
        _uiState.update { current ->
            normalizeRuntimeSelection(
                current.copy(
                    selectedReasoningEffort = reasoningEffort?.trim()?.takeIf(String::isNotEmpty),
                    errorMessage = null,
                ),
            )
        }
    }

    fun selectAccessMode(accessMode: CodexAccessMode) {
        _uiState.update { current ->
            current.copy(
                selectedAccessMode = accessMode,
                errorMessage = null,
            )
        }
    }

    fun selectCollaborationMode(mode: CodexCollaborationModeKind) {
        _uiState.update { current ->
            current.copy(
                selectedCollaborationMode = if (current.availableCollaborationModes.contains(mode)) {
                    mode
                } else {
                    CodexCollaborationModeKind.Default
                },
                errorMessage = null,
            )
        }
    }

    fun selectGitBaseBranch(branch: String) {
        val normalizedBranch = branch.trim()
        if (normalizedBranch.isEmpty()) {
            return
        }

        _uiState.update { current ->
            current.copy(
                selectedGitBaseBranch = normalizedBranch,
                errorMessage = null,
            )
        }
    }

    fun parsePairingPayload() {
        runCatching {
            resolvePairingForManualConnect()
        }.onSuccess { payload ->
            _uiState.update { current ->
                current.copy(
                    parsedPairing = payload,
                    sessionUrl = payload.relaySessionUrl(),
                    errorMessage = null,
                )
            }
        }.onFailure { throwable ->
            _uiState.update { current ->
                current.copy(
                    parsedPairing = null,
                    sessionUrl = null,
                    errorMessage = throwable.message,
                )
            }
        }
    }

    fun connect() {
        viewModelScope.launch {
            val pairing = runCatching {
                resolvePairingForManualConnect()
            }.getOrElse { throwable ->
                _uiState.update { current ->
                    current.copy(errorMessage = throwable.message)
                }
                return@launch
            }

            persistRelayPairing(pairing)
            _uiState.update { current ->
                current.copy(
                    parsedPairing = pairing,
                    sessionUrl = pairing.relaySessionUrl(),
                    isBusy = true,
                    errorMessage = null,
                )
            }

            runCatching {
                connectWithAutoRecovery(
                    pairing = pairing,
                    performAutoRetry = true,
                )
            }.onSuccess { handshake ->
                applyHandshake(handshake)
            }.onFailure { throwable ->
                _uiState.update { current ->
                    current.copy(
                        isBusy = false,
                        errorMessage = userFacingConnectFailureMessage(throwable),
                    )
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            gitStatusRefreshJob?.cancel()
            gitStatusRefreshJob = null
            gitBranchRefreshThreadId = null
            transport.disconnect()
            clearSavedRelayPairing()
            _uiState.update { current ->
                current.copy(
                    isBusy = false,
                    shouldAutoReconnectOnForeground = false,
                    isAttemptingAutoReconnect = false,
                    isStartingThread = false,
                    isStartingTurn = false,
                    currentGitBranch = "",
                    gitDefaultBranch = "",
                    selectedGitBaseBranch = "",
                    availableGitBranchTargets = emptyList(),
                    isLoadingGitBranchTargets = false,
                    isSwitchingGitBranch = false,
                    gitRepoSync = null,
                    threads = emptyList(),
                    conversation = RemodexConversationState(),
                    draftTurnInput = "",
                    lastStartedTurnId = null,
                    lastTurnStartSummary = null,
                    errorMessage = null,
                )
            }
        }
    }

    private fun restoreSavedRelayPairing() {
        savedRelayPairing = relaySessionStore.read()
        _uiState.update { current ->
            current.copy(
                hasSavedRelaySession = savedRelayPairing != null,
                sessionUrl = savedRelayPairing?.relaySessionUrl() ?: current.sessionUrl,
            )
        }
    }

    private fun persistRelayPairing(pairing: RemodexPairingPayload) {
        relaySessionStore.write(pairing)
        savedRelayPairing = pairing
        _uiState.update { current ->
            current.copy(
                hasSavedRelaySession = true,
                sessionUrl = pairing.relaySessionUrl(),
            )
        }
    }

    private fun clearSavedRelayPairing() {
        relaySessionStore.clear()
        savedRelayPairing = null
        _uiState.update { current ->
            current.copy(
                hasSavedRelaySession = false,
                shouldAutoReconnectOnForeground = false,
                isAttemptingAutoReconnect = false,
            )
        }
    }

    private fun resolvePairingForManualConnect(): RemodexPairingPayload {
        val rawPayload = _uiState.value.qrPayload.trim()
        if (rawPayload.isNotEmpty()) {
            return RemodexPairingParser.parse(rawPayload)
        }

        return savedRelayPairing ?: throw IllegalStateException(
            "Paste or scan a pairing payload before connecting.",
        )
    }

    private fun handleTransportStateTransition(
        previousState: RemodexTransportState,
        state: RemodexTransportState,
    ) {
        when (state) {
            RemodexTransportState.Disconnected -> {
                _uiState.update { current ->
                    current.copy(
                        isBusy = false,
                        isAttemptingAutoReconnect = false,
                        shouldAutoReconnectOnForeground = false,
                    )
                }
            }

            is RemodexTransportState.Connected -> {
                if (state.isInitialized) {
                    _uiState.update { current ->
                        current.copy(
                            isBusy = false,
                            shouldAutoReconnectOnForeground = false,
                            isAttemptingAutoReconnect = false,
                            errorMessage = null,
                        )
                    }
                }
            }

            is RemodexTransportState.Failed -> {
                handleTransportFailure(state)
            }

            is RemodexTransportState.Connecting,
            is RemodexTransportState.Retrying -> Unit
        }

        if (previousState is RemodexTransportState.Connected &&
            previousState.isInitialized &&
            state is RemodexTransportState.Failed &&
            !state.isPermanent &&
            isAppInForeground
        ) {
            attemptAutoReconnectOnForegroundIfNeeded()
        }
    }

    private fun handleTransportFailure(state: RemodexTransportState.Failed) {
        if (state.isPermanent) {
            clearSavedRelayPairing()
            _uiState.update { current ->
                current.copy(
                    isBusy = false,
                    errorMessage = state.message,
                )
            }
            return
        }

        val hasSavedPairing = savedRelayPairing != null
        _uiState.update { current ->
            current.copy(
                isBusy = false,
                shouldAutoReconnectOnForeground = hasSavedPairing,
                isAttemptingAutoReconnect = false,
                errorMessage = if (hasSavedPairing) {
                    null
                } else {
                    state.message
                },
            )
        }
    }

    private fun attemptAutoReconnectOnForegroundIfNeeded() {
        if (!_uiState.value.shouldAutoReconnectOnForeground || isRunningAutoReconnect) {
            return
        }

        viewModelScope.launch {
            val pairing = savedRelayPairing
            if (pairing == null) {
                _uiState.update { current ->
                    current.copy(
                        shouldAutoReconnectOnForeground = false,
                        isAttemptingAutoReconnect = false,
                    )
                }
                return@launch
            }

            isRunningAutoReconnect = true
            _uiState.update { current ->
                current.copy(
                    isAttemptingAutoReconnect = true,
                    errorMessage = null,
                )
            }

            try {
                var attempt = 0
                while (_uiState.value.shouldAutoReconnectOnForeground && attempt < MAX_FOREGROUND_RECONNECT_ATTEMPTS) {
                    if (isTransportConnectedAndInitialized()) {
                        _uiState.update { current ->
                            current.copy(
                                shouldAutoReconnectOnForeground = false,
                                isAttemptingAutoReconnect = false,
                                errorMessage = null,
                            )
                        }
                        return@launch
                    }

                    if (isTransportConnecting()) {
                        delay(CONNECTING_POLL_DELAY_MILLIS)
                        continue
                    }

                    runCatching {
                        transport.connect(pairing = pairing)
                    }.onSuccess {
                        _uiState.update { current ->
                            current.copy(
                                shouldAutoReconnectOnForeground = false,
                                isAttemptingAutoReconnect = false,
                                errorMessage = null,
                            )
                        }
                        return@launch
                    }.onFailure { throwable ->
                        if (isPermanentFailure(throwable)) {
                            clearSavedRelayPairing()
                            _uiState.update { current ->
                                current.copy(
                                    errorMessage = userFacingConnectFailureMessage(throwable),
                                )
                            }
                            return@launch
                        }

                        if (!transport.isRecoverableTransientFailure(throwable)) {
                            _uiState.update { current ->
                                current.copy(
                                    shouldAutoReconnectOnForeground = false,
                                    isAttemptingAutoReconnect = false,
                                    errorMessage = userFacingConnectFailureMessage(throwable),
                                )
                            }
                            return@launch
                        }

                        attempt += 1
                        delay(
                            AUTO_RECONNECT_BACKOFF_MILLIS[
                                minOf(attempt - 1, AUTO_RECONNECT_BACKOFF_MILLIS.lastIndex)
                            ],
                        )
                    }
                }

                _uiState.update { current ->
                    current.copy(
                        shouldAutoReconnectOnForeground = false,
                        isAttemptingAutoReconnect = false,
                        errorMessage = "Could not reconnect. Tap Reconnect to try again.",
                    )
                }
            } finally {
                isRunningAutoReconnect = false
            }
        }
    }

    private suspend fun connectWithAutoRecovery(
        pairing: RemodexPairingPayload,
        performAutoRetry: Boolean,
    ): RemodexHandshakeResult {
        val attemptLimit = if (performAutoRetry) {
            AUTO_RECONNECT_BACKOFF_MILLIS.size
        } else {
            0
        }

        var attemptIndex = 0
        var lastError: Throwable? = null
        while (attemptIndex <= attemptLimit) {
            if (attemptIndex > 0) {
                _uiState.update { current ->
                    current.copy(
                        isAttemptingAutoReconnect = true,
                        errorMessage = null,
                    )
                }
            }

            runCatching {
                transport.connect(pairing = pairing)
            }.onSuccess { handshake ->
                _uiState.update { current ->
                    current.copy(
                        isBusy = false,
                        isAttemptingAutoReconnect = false,
                        shouldAutoReconnectOnForeground = false,
                        errorMessage = null,
                    )
                }
                return handshake
            }.onFailure { throwable ->
                lastError = throwable
                if (isPermanentFailure(throwable)) {
                    clearSavedRelayPairing()
                    throw throwable
                }

                if (!performAutoRetry ||
                    !transport.isRecoverableTransientFailure(throwable) ||
                    attemptIndex >= AUTO_RECONNECT_BACKOFF_MILLIS.size
                ) {
                    _uiState.update { current ->
                        current.copy(
                            isBusy = false,
                            isAttemptingAutoReconnect = false,
                            shouldAutoReconnectOnForeground = false,
                        )
                    }
                    throw throwable
                }

                delay(AUTO_RECONNECT_BACKOFF_MILLIS[attemptIndex])
            }

            attemptIndex += 1
        }

        throw lastError ?: IllegalStateException("Connection failed.")
    }

    private fun isTransportConnectedOrConnecting(): Boolean {
        return when (transport.state.value) {
            is RemodexTransportState.Connected,
            is RemodexTransportState.Connecting,
            is RemodexTransportState.Retrying -> true
            RemodexTransportState.Disconnected,
            is RemodexTransportState.Failed -> false
        }
    }

    private fun isTransportConnecting(): Boolean {
        return transport.state.value is RemodexTransportState.Connecting
    }

    private fun isTransportConnectedAndInitialized(): Boolean {
        return (transport.state.value as? RemodexTransportState.Connected)?.isInitialized == true
    }

    private fun isPermanentFailure(throwable: Throwable): Boolean {
        val transportError = throwable as? RemodexTransportException
        return transportError?.isPermanent == true ||
            transportError?.kind == RemodexTransportFailureKind.PermanentRelayClosure
    }

    private fun userFacingConnectFailureMessage(throwable: Throwable): String {
        val transportError = throwable as? RemodexTransportException
        return when {
            transportError == null -> throwable.message ?: "Unexpected transport failure."
            transportError.isPermanent -> transportError.message
            transportError.kind == RemodexTransportFailureKind.Timeout -> "Connection timed out. Check server/network."
            transportError.kind == RemodexTransportFailureKind.Disconnected -> "Connection was interrupted. Tap Reconnect to try again."
            else -> transportError.message
        }
    }

    fun refreshThreads() {
        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    isLoadingThreads = true,
                    errorMessage = null,
                )
            }

            runCatching {
                transport.listThreads()
            }.onSuccess { threads ->
                _uiState.update { current ->
                    val mergedThreads = mergeThreadsFromServer(
                        localThreads = current.threads,
                        serverThreads = threads,
                    )
                    current.copy(
                        isLoadingThreads = false,
                        threads = mergedThreads,
                        conversation = current.conversation.pruneToThreads(mergedThreads.mapTo(linkedSetOf(), CodexThread::id)),
                    )
                }
            }.onFailure { throwable ->
                _uiState.update { current ->
                    current.copy(
                        isLoadingThreads = false,
                        errorMessage = throwable.message,
                    )
                }
            }
        }
    }

    fun refreshRuntimeOptions() {
        viewModelScope.launch {
            val modelsResult = runCatching { transport.listModels() }.getOrDefault(emptyList())
            val collaborationModesResult = runCatching { transport.listCollaborationModes() }
                .getOrDefault(listOf(CodexCollaborationModeKind.Default))
                .ifEmpty { listOf(CodexCollaborationModeKind.Default) }

            _uiState.update { current ->
                normalizeRuntimeSelection(
                    current.copy(
                        availableModels = modelsResult,
                        availableCollaborationModes = collaborationModesResult.distinct(),
                        selectedCollaborationMode = current.selectedCollaborationMode
                            .takeIf(collaborationModesResult::contains)
                            ?: CodexCollaborationModeKind.Default,
                    ),
                )
            }
        }
    }

    fun refreshGitBranchTargets(threadId: String? = _uiState.value.activeThreadId) {
        viewModelScope.launch {
            val targetThreadId = threadId?.trim()?.takeIf(String::isNotEmpty)
            val workingDirectory = selectedThreadWorkingDirectory(targetThreadId)
            if (targetThreadId == null || workingDirectory == null) {
                _uiState.update { current ->
                    if (current.activeThreadId == targetThreadId) {
                        current.withClearedGitBranchState()
                    } else {
                        current
                    }
                }
                return@launch
            }
            if (gitBranchRefreshThreadId == targetThreadId ||
                (_uiState.value.activeThreadId == targetThreadId && _uiState.value.isLoadingGitBranchTargets)
            ) {
                return@launch
            }
            gitBranchRefreshThreadId = targetThreadId

            _uiState.update { current ->
                if (current.activeThreadId == targetThreadId) {
                    current.copy(
                        isLoadingGitBranchTargets = true,
                    )
                } else {
                    current
                }
            }

            runCatching {
                transport.gitBranchesWithStatus(workingDirectory = workingDirectory)
            }.onSuccess { result ->
                if (gitBranchRefreshThreadId == targetThreadId) {
                    gitBranchRefreshThreadId = null
                }
                _uiState.update { current ->
                    if (current.activeThreadId != targetThreadId) {
                        return@update current
                    }

                    current.copy(
                        currentGitBranch = result.currentBranch?.trim().orEmpty(),
                        gitDefaultBranch = result.defaultBranch?.trim().orEmpty(),
                        selectedGitBaseBranch = current.selectedGitBaseBranch
                            .takeIf { selection ->
                                selection.isNotBlank() &&
                                    result.branches.any { it.trim() == selection }
                            }
                            ?: result.defaultBranch?.trim().orEmpty(),
                        availableGitBranchTargets = result.branches
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .distinct(),
                        isLoadingGitBranchTargets = false,
                    ).applyObservedGitRepoSync(
                        status = result.status,
                        threadId = targetThreadId,
                        workingDirectory = workingDirectory,
                    )
                }
            }.onFailure {
                if (gitBranchRefreshThreadId == targetThreadId) {
                    gitBranchRefreshThreadId = null
                }
                _uiState.update { current ->
                    if (current.activeThreadId != targetThreadId) {
                        return@update current
                    }

                    current.copy(
                        isLoadingGitBranchTargets = false,
                    )
                }
            }
        }
    }

    private fun scheduleGitStatusRefresh(threadId: String? = _uiState.value.activeThreadId) {
        val targetThreadId = threadId?.trim()?.takeIf(String::isNotEmpty) ?: return
        gitStatusRefreshJob?.cancel()
        gitStatusRefreshJob = viewModelScope.launch {
            delay(GIT_STATUS_REFRESH_DEBOUNCE_MILLIS)
            refreshGitStatus(targetThreadId)
        }
    }

    fun refreshGitStatus(threadId: String? = _uiState.value.activeThreadId) {
        viewModelScope.launch {
            val targetThreadId = threadId?.trim()?.takeIf(String::isNotEmpty) ?: return@launch
            val workingDirectory = selectedThreadWorkingDirectory(targetThreadId) ?: return@launch

            runCatching {
                transport.gitStatus(workingDirectory = workingDirectory)
            }.onSuccess { status ->
                _uiState.update { current ->
                    if (current.activeThreadId != targetThreadId) {
                        return@update current
                    }

                    current.copy(
                        currentGitBranch = status.currentBranch?.trim().orEmpty()
                            .ifEmpty { current.currentGitBranch },
                    ).applyObservedGitRepoSync(
                        status = status,
                        threadId = targetThreadId,
                        workingDirectory = workingDirectory,
                    )
                }
            }
        }
    }

    fun switchGitBranch(branch: String) {
        viewModelScope.launch {
            val normalizedBranch = branch.trim()
            if (normalizedBranch.isEmpty()) {
                return@launch
            }

            val currentState = _uiState.value
            val activeThreadId = currentState.activeThreadId
            if (activeThreadId.isNullOrBlank()) {
                _uiState.update { current ->
                    current.copy(errorMessage = "Select a thread before switching branches.")
                }
                return@launch
            }

            if (currentState.conversation.threadHasActiveOrRunningTurn(activeThreadId)) {
                _uiState.update { current ->
                    current.copy(errorMessage = "Wait for the active turn to finish before switching branches.")
                }
                return@launch
            }

            val workingDirectory = selectedThreadWorkingDirectory(activeThreadId)
            if (workingDirectory == null) {
                _uiState.update { current ->
                    current.copy(errorMessage = "The selected local folder is not available for git actions.")
                }
                return@launch
            }

            _uiState.update { current ->
                current.copy(
                    isSwitchingGitBranch = true,
                    errorMessage = null,
                )
            }

            runCatching {
                transport.gitCheckout(
                    workingDirectory = workingDirectory,
                    branch = normalizedBranch,
                )
            }.onSuccess { result ->
                _uiState.update { current ->
                    if (current.activeThreadId != activeThreadId) {
                        return@update current
                    }

                    current.copy(
                        currentGitBranch = result.currentBranch.trim(),
                        gitRepoSync = result.status,
                        isSwitchingGitBranch = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { throwable ->
                _uiState.update { current ->
                    current.copy(
                        isSwitchingGitBranch = false,
                        errorMessage = throwable.message,
                    )
                }
            }
        }
    }

    fun startThread() {
        viewModelScope.launch {
            val preferredProjectPath = _uiState.value.threads
                .firstOrNull { it.id == _uiState.value.activeThreadId }
                ?.cwd

            _uiState.update { current ->
                current.copy(
                    isStartingThread = true,
                    errorMessage = null,
                )
            }

            runCatching {
                transport.startThread(
                    preferredProjectPath = preferredProjectPath,
                    accessMode = _uiState.value.selectedAccessMode,
                )
            }.onSuccess { result ->
                applyThreadStarted(result)
                refreshGitBranchTargets(result.thread.id)
            }.onFailure { throwable ->
                _uiState.update { current ->
                    current.copy(
                        isStartingThread = false,
                        errorMessage = throwable.message,
                    )
                }
            }
        }
    }

    fun selectThread(threadId: String) {
        viewModelScope.launch {
            prepareThreadForDisplay(threadId)
        }
    }

    fun startTurn() {
        viewModelScope.launch {
            val currentState = _uiState.value
            val selectedThreadId = currentState.activeThreadId
            if (selectedThreadId.isNullOrBlank()) {
                _uiState.update { current ->
                    current.copy(errorMessage = "Select a thread before sending a turn.")
                }
                return@launch
            }

            val trimmedInput = currentState.draftTurnInput.trim()
            if (trimmedInput.isEmpty()) {
                _uiState.update { current ->
                    current.copy(errorMessage = "Enter a prompt before sending a turn.")
                }
                return@launch
            }

            val pendingMessageId = UUID.randomUUID().toString()

            _uiState.update { current ->
                current.copy(
                    conversation = current.conversation
                        .withActiveThread(selectedThreadId)
                        .appendUserMessage(
                            threadId = selectedThreadId,
                            text = trimmedInput,
                            messageId = pendingMessageId,
                        ),
                    isStartingTurn = true,
                    errorMessage = null,
                )
            }

            runCatching {
                transport.startTurn(
                    threadId = selectedThreadId,
                    userInput = trimmedInput,
                    accessMode = currentState.selectedAccessMode,
                    collaborationMode = currentState.selectedCollaborationMode.takeUnless {
                        it == CodexCollaborationModeKind.Default
                    },
                    preferredProjectPath = currentState.threads
                        .firstOrNull { it.id == selectedThreadId }
                        ?.cwd,
                    modelIdentifier = currentState.selectedModelOption?.model,
                    reasoningEffort = currentState.selectedReasoningEffort,
                )
            }.onSuccess { result ->
                applyTurnStarted(
                    result = result,
                    pendingMessageId = pendingMessageId,
                    requestedThreadId = selectedThreadId,
                )
            }.onFailure { throwable ->
                _uiState.update { current ->
                    current.copy(
                        conversation = current.conversation.markMessageDeliveryState(
                            threadId = selectedThreadId,
                            messageId = pendingMessageId,
                            deliveryState = CodexMessageDeliveryState.Failed,
                        ),
                        isStartingTurn = false,
                        errorMessage = throwable.message,
                    )
                }
            }
        }
    }

    fun interruptTurn() {
        viewModelScope.launch {
            val threadId = _uiState.value.activeThreadId
            if (threadId.isNullOrBlank()) {
                _uiState.update { current ->
                    current.copy(errorMessage = "Select a running thread before stopping a turn.")
                }
                return@launch
            }

            val resolvedTurnId = resolveInterruptibleTurnId(threadId = threadId)
            if (resolvedTurnId == null) {
                _uiState.update { current ->
                    current.copy(errorMessage = "No active turn is available to interrupt.")
                }
                return@launch
            }

            runCatching {
                transport.interruptTurn(
                    turnId = resolvedTurnId,
                    threadId = threadId,
                )
            }.onSuccess {
                _uiState.update { current ->
                    current.copy(errorMessage = null)
                }
            }.onFailure { throwable ->
                if (shouldRetryInterruptWithRefreshedTurnId(throwable)) {
                    val refreshedTurnId = resolveInterruptibleTurnId(threadId = threadId, forceRefresh = true)
                    if (refreshedTurnId != null && refreshedTurnId != resolvedTurnId) {
                        runCatching {
                            transport.interruptTurn(
                                turnId = refreshedTurnId,
                                threadId = threadId,
                            )
                        }.onSuccess {
                            _uiState.update { current ->
                                current.copy(errorMessage = null)
                            }
                        }.onFailure { refreshThrowable ->
                            _uiState.update { current ->
                                current.copy(errorMessage = refreshThrowable.message)
                            }
                        }
                        return@launch
                    }
                }

                _uiState.update { current ->
                    current.copy(errorMessage = throwable.message)
                }
            }
        }
    }

    private fun applyHandshake(handshake: RemodexHandshakeResult) {
        _uiState.update { current ->
            current.copy(
                isBusy = false,
                isAttemptingAutoReconnect = false,
                shouldAutoReconnectOnForeground = false,
                sessionUrl = handshake.sessionUrl,
                hostInfo = handshake.hostInfo,
                supportsPlanCollaborationMode = handshake.supportsPlanCollaborationMode,
                errorMessage = null,
            )
        }
    }

    companion object {
        private const val APP_VERSION = "0.1.0"
        private const val GIT_STATUS_REFRESH_DEBOUNCE_MILLIS = 350L
        private const val CONNECTING_POLL_DELAY_MILLIS = 300L
        private const val MAX_FOREGROUND_RECONNECT_ATTEMPTS = 20
        private val AUTO_RECONNECT_BACKOFF_MILLIS = listOf(1_000L, 3_000L)
    }

    private fun applyThreadStarted(result: RemodexThreadStartResult) {
        _uiState.update { current ->
            current.copy(
                threads = upsertThread(current.threads, result.thread),
                conversation = current.conversation
                    .withActiveThread(result.thread.id)
                    .withThreadMessages(result.thread.id, emptyList()),
                draftTurnInput = "",
                isStartingThread = false,
                lastStartedTurnId = null,
                lastTurnStartSummary = "New live thread ready: ${result.thread.id.take(8)}",
                errorMessage = null,
            )
        }
    }

    private fun applyTurnStarted(
        result: RemodexTurnStartResult,
        pendingMessageId: String? = null,
        requestedThreadId: String = result.requestedThreadId,
    ) {
        _uiState.update { current ->
            var updatedThreads = current.threads
            result.archivedThreadId?.let { archivedThreadId ->
                updatedThreads = markThreadArchived(updatedThreads, archivedThreadId)
            }
            result.activeThread?.let { activeThread ->
                updatedThreads = upsertThread(updatedThreads, activeThread)
            }

            var updatedConversation = current.conversation
            val resolvedPendingThreadId = if (pendingMessageId != null && requestedThreadId != result.threadId) {
                updatedConversation = updatedConversation.moveMessageToThread(
                    sourceThreadId = requestedThreadId,
                    targetThreadId = result.threadId,
                    messageId = pendingMessageId,
                )
                result.threadId
            } else {
                requestedThreadId
            }
            val resolvedTurnId = result.turnId ?: updatedConversation.activeTurnIdByThread[result.threadId]
            if (pendingMessageId != null) {
                updatedConversation = updatedConversation.markMessageDeliveryState(
                    threadId = resolvedPendingThreadId,
                    messageId = pendingMessageId,
                    deliveryState = if (resolvedTurnId == null) {
                        CodexMessageDeliveryState.Pending
                    } else {
                        CodexMessageDeliveryState.Confirmed
                    },
                    turnId = resolvedTurnId,
                )
            }

            current.copy(
                threads = updatedThreads,
                conversation = updatedConversation
                    .withActiveThread(result.threadId)
                    .withTurnStarted(result.threadId, result.turnId),
                draftTurnInput = "",
                isStartingTurn = false,
                lastStartedTurnId = result.turnId,
                lastTurnStartSummary = buildTurnStartSummary(result),
                errorMessage = null,
            )
        }
    }

    private fun applyThreadRead(threadResult: RemodexThreadReadResult) {
        _uiState.update { current ->
            current.copy(
                conversation = current.conversation
                    .withActiveThread(threadResult.thread.id)
                    .markThreadAsViewed(threadResult.thread.id)
                    .withRefreshedInFlightTurnState(threadResult.thread.id, threadResult.turnStateSnapshot)
                    .mergeHydratedThreadMessages(threadResult.thread.id, threadResult.messages)
                    .withThreadLoading(threadResult.thread.id, isLoading = false),
                threads = upsertThread(current.threads, threadResult.thread),
                errorMessage = null,
            )
        }
    }

    private suspend fun resolveInterruptibleTurnId(
        threadId: String,
        forceRefresh: Boolean = false,
    ): String? {
        val localTurnId = _uiState.value.conversation.activeTurnIdByThread[threadId]
        if (!forceRefresh && !localTurnId.isNullOrBlank()) {
            return localTurnId
        }

        val threadResult = runCatching {
            transport.readThread(threadId = threadId, includeTurns = true)
        }.getOrElse { throwable ->
            _uiState.update { current ->
                current.copy(errorMessage = throwable.message)
            }
            return null
        }

        applyThreadRead(threadResult)
        return threadResult.turnStateSnapshot.interruptibleTurnId ?: threadResult.turnStateSnapshot.latestTurnId
    }

    private suspend fun prepareThreadForDisplay(
        threadId: String,
        forceHydration: Boolean = false,
    ) {
        _uiState.update { current ->
            current.copy(
                conversation = current.conversation
                    .withActiveThread(threadId)
                    .markThreadAsViewed(threadId)
                    .withThreadLoading(threadId, isLoading = shouldLoadThreadHistory(current, threadId, forceHydration)),
                currentGitBranch = "",
                gitDefaultBranch = "",
                selectedGitBaseBranch = "",
                availableGitBranchTargets = emptyList(),
                gitRepoSync = null,
                isLoadingGitBranchTargets = selectedThreadWorkingDirectory(threadId, current.threads) != null,
                errorMessage = null,
            )
        }

        val isConnected = (_uiState.value.connectionState as? RemodexTransportState.Connected)?.isInitialized == true
        if (!isConnected) {
            _uiState.update { current ->
                current.copy(
                    conversation = current.conversation.withThreadLoading(threadId, isLoading = false),
                    isLoadingGitBranchTargets = false,
                )
            }
            return
        }

        runCatching {
            transport.resumeThread(
                threadId = threadId,
                accessMode = _uiState.value.selectedAccessMode,
                modelIdentifier = _uiState.value.selectedModelOption?.model,
            )
        }.onSuccess { resumeResult ->
            resumeResult.thread?.let { resumedThread ->
                _uiState.update { current ->
                    current.copy(threads = upsertThread(current.threads, resumedThread))
                }
            }
        }.onFailure { throwable ->
            _uiState.update { current ->
                current.copy(
                    conversation = current.conversation.withThreadLoading(threadId, isLoading = false),
                    isLoadingGitBranchTargets = false,
                    errorMessage = throwable.message,
                )
            }
            return
        }

        if (!shouldLoadThreadHistory(_uiState.value, threadId, forceHydration)) {
            refreshGitBranchTargets(threadId)
            _uiState.update { current ->
                current.copy(
                    conversation = current.conversation.withThreadLoading(threadId, isLoading = false),
                )
            }
            return
        }

        runCatching {
            transport.readThread(threadId = threadId, includeTurns = true)
        }.onSuccess { threadResult ->
            applyThreadRead(threadResult)
            refreshGitBranchTargets(threadId)
        }.onFailure { throwable ->
            _uiState.update { current ->
                current.copy(
                    conversation = current.conversation.withThreadLoading(threadId, isLoading = false),
                    isLoadingGitBranchTargets = false,
                    errorMessage = throwable.message,
                )
            }
        }
    }

    private fun shouldLoadThreadHistory(
        state: RemodexDebugUiState,
        threadId: String,
        forceHydration: Boolean,
    ): Boolean {
        return forceHydration || !state.conversation.isHydratedThread(threadId)
    }

    private fun selectedThreadWorkingDirectory(threadId: String?): String? {
        return selectedThreadWorkingDirectory(
            threadId = threadId,
            threads = _uiState.value.threads,
        )
    }

    private fun selectedThreadWorkingDirectory(
        threadId: String?,
        threads: List<CodexThread>,
    ): String? {
        val normalizedThreadId = threadId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return threads.firstOrNull { it.id == normalizedThreadId }
            ?.cwd
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun normalizeRuntimeSelection(state: RemodexDebugUiState): RemodexDebugUiState {
        val resolvedModel = selectResolvedModel(
            availableModels = state.availableModels,
            selectedModelId = state.selectedModelId,
        )
        val resolvedModelId = resolvedModel?.id?.takeIf(String::isNotBlank)
            ?: resolvedModel?.model?.takeIf(String::isNotBlank)
        val supportedReasoning = resolvedModel?.supportedReasoningEfforts
            ?.map { it.reasoningEffort.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val resolvedReasoning = when {
            supportedReasoning.isEmpty() -> null
            state.selectedReasoningEffort != null && supportedReasoning.contains(state.selectedReasoningEffort) -> state.selectedReasoningEffort
            !resolvedModel?.defaultReasoningEffort.isNullOrBlank() &&
                supportedReasoning.contains(resolvedModel?.defaultReasoningEffort) -> resolvedModel?.defaultReasoningEffort
            supportedReasoning.contains("medium") -> "medium"
            else -> supportedReasoning.firstOrNull()
        }
        val availableCollaborationModes = state.availableCollaborationModes
            .ifEmpty { listOf(CodexCollaborationModeKind.Default) }
            .distinct()
        val selectedCollaborationMode = state.selectedCollaborationMode
            .takeIf(availableCollaborationModes::contains)
            ?: CodexCollaborationModeKind.Default

        return state.copy(
            selectedModelId = resolvedModelId,
            selectedReasoningEffort = resolvedReasoning,
            availableCollaborationModes = availableCollaborationModes,
            selectedCollaborationMode = selectedCollaborationMode,
        )
    }

    private fun shouldRetryInterruptWithRefreshedTurnId(throwable: Throwable): Boolean {
        val classified = throwable as? RemodexTransportException ?: return false
        val message = classified.message.lowercase()
        val hints = listOf(
            "turn not found",
            "no active turn",
            "not in progress",
            "not interruptible",
            "already completed",
            "already stopped",
        )
        return hints.any(message::contains)
    }

    private fun selectResolvedModel(
        availableModels: List<CodexModelOption>,
        selectedModelId: String?,
    ): CodexModelOption? {
        if (availableModels.isEmpty()) {
            return null
        }

        val normalizedSelectedId = selectedModelId?.trim()?.takeIf(String::isNotEmpty)
        if (normalizedSelectedId != null) {
            availableModels.firstOrNull { it.id == normalizedSelectedId || it.model == normalizedSelectedId }?.let {
                return it
            }
        }

        return availableModels.firstOrNull(CodexModelOption::isDefault) ?: availableModels.first()
    }

    private fun buildTurnStartSummary(result: RemodexTurnStartResult): String {
        result.continuationSummary?.let { summary ->
            return summary
        }
        val threadLabel = result.threadId.take(8)
        val turnLabel = result.turnId?.take(8)
        return if (turnLabel != null) {
            "Live turn started on $threadLabel · $turnLabel"
        } else {
            "Live turn started on $threadLabel"
        }
    }

    private fun upsertThread(
        threads: List<CodexThread>,
        thread: CodexThread,
    ): List<CodexThread> {
        val updatedThreads = threads.toMutableList()
        val existingIndex = updatedThreads.indexOfFirst { it.id == thread.id }
        if (existingIndex >= 0) {
            updatedThreads.removeAt(existingIndex)
        }
        updatedThreads.add(0, thread)
        return updatedThreads
    }

    private fun markThreadArchived(
        threads: List<CodexThread>,
        threadId: String,
    ): List<CodexThread> {
        val existingIndex = threads.indexOfFirst { it.id == threadId }
        if (existingIndex < 0) {
            return listOf(
                CodexThread(
                    id = threadId,
                    title = "Conversation",
                    syncState = CodexThreadSyncState.ArchivedLocal,
                ),
            ) + threads
        }

        return threads.toMutableList().apply {
            this[existingIndex] = this[existingIndex].copy(syncState = CodexThreadSyncState.ArchivedLocal)
        }
    }

    private fun mergeThreadsFromServer(
        localThreads: List<CodexThread>,
        serverThreads: List<CodexThread>,
    ): List<CodexThread> {
        val localById = localThreads.associateBy { it.id }
        val merged = mutableListOf<CodexThread>()

        for (serverThread in serverThreads) {
            val localThread = localById[serverThread.id]
            merged += if (localThread?.syncState == CodexThreadSyncState.ArchivedLocal) {
                serverThread.copy(syncState = CodexThreadSyncState.ArchivedLocal)
            } else {
                serverThread
            }
        }

        for (localThread in localThreads) {
            if (merged.none { it.id == localThread.id }) {
                merged += localThread
            }
        }

        return merged
    }

    private fun repoRefreshSignal(
        conversation: RemodexConversationState,
        threadId: String?,
    ): String? {
        val latestRepoMessage = conversation.messagesFor(threadId)
            .lastOrNull { message ->
                message.role == CodexMessageRole.System &&
                    (message.kind == CodexMessageKind.FileChange || message.kind == CodexMessageKind.CommandExecution)
            } ?: return null

        return "${latestRepoMessage.id}|${latestRepoMessage.text.length}|${latestRepoMessage.isStreaming}"
    }

    private fun RemodexDebugUiState.applyObservedGitRepoSync(
        status: GitRepoSyncResult?,
        threadId: String,
        workingDirectory: String?,
    ): RemodexDebugUiState {
        if (status == null) {
            return copy(gitRepoSync = null)
        }

        var updatedConversation = conversation
        val previousSync = gitRepoSync
        val branchStayedStable = previousSync?.currentBranch == status.currentBranch
        val didClearAheadQueue = (previousSync?.aheadCount ?: 0) > 0 && status.aheadCount == 0
        if (branchStayedStable && didClearAheadQueue) {
            updatedConversation = appendHiddenPushResetMarkers(
                conversation = updatedConversation,
                threadId = threadId,
                workingDirectory = workingDirectory,
                branch = status.currentBranch.orEmpty(),
                remote = trackingRemoteName(status.trackingBranch),
            )
        }

        return copy(
            conversation = updatedConversation,
            gitRepoSync = status,
        )
    }

    private fun appendHiddenPushResetMarkers(
        conversation: RemodexConversationState,
        threadId: String,
        workingDirectory: String?,
        branch: String,
        remote: String?,
    ): RemodexConversationState {
        val normalizedThreadId = threadId.trim().takeIf(String::isNotEmpty) ?: return conversation
        val normalizedWorkingDirectory = normalizeWorkingDirectoryForPushReset(workingDirectory)
        val relatedThreadIds = if (normalizedWorkingDirectory != null) {
            _uiState.value.threads
                .mapNotNull { thread ->
                    val candidateWorkingDirectory = normalizeWorkingDirectoryForPushReset(thread.cwd)
                    thread.id.takeIf { candidateWorkingDirectory == normalizedWorkingDirectory }
                }
        } else {
            emptyList()
        }

        return (relatedThreadIds + normalizedThreadId)
            .toSet()
            .fold(conversation) { currentConversation, targetThreadId ->
                currentConversation.appendSystemMessage(
                    threadId = targetThreadId,
                    kind = CodexMessageKind.FileChange,
                    text = RemodexGitTimelineSupport.pushResetText(branch = branch, remote = remote),
                    itemId = RemodexGitTimelineSupport.PushResetItemId,
                )
            }
    }

    private fun normalizeWorkingDirectoryForPushReset(rawValue: String?): String? {
        val trimmed = rawValue?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        if (trimmed == "/") {
            return trimmed
        }

        var normalized = trimmed
        while (normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }
        return normalized.ifEmpty { "/" }
    }

    private fun trackingRemoteName(trackingBranch: String?): String? {
        val trimmed = trackingBranch?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        return trimmed.split("/", limit = 2).firstOrNull()?.takeIf(String::isNotEmpty)
    }
}

private fun CodexModelOption.displayTitle(): String {
    val normalizedModel = model.trim().lowercase()
    return when (normalizedModel) {
        "gpt-5.3-codex" -> "GPT-5.3-Codex"
        "gpt-5.2-codex" -> "GPT-5.2-Codex"
        "gpt-5.1-codex-max" -> "GPT-5.1-Codex-Max"
        "gpt-5.4" -> "GPT-5.4"
        "gpt-5.2" -> "GPT-5.2"
        "gpt-5.1-codex-mini" -> "GPT-5.1-Codex-Mini"
        else -> displayName.ifBlank { model.ifBlank { id } }
    }
}

private fun RemodexDebugUiState.withClearedGitBranchState(): RemodexDebugUiState {
    return copy(
        currentGitBranch = "",
        gitDefaultBranch = "",
        selectedGitBaseBranch = "",
        availableGitBranchTargets = emptyList(),
        isLoadingGitBranchTargets = false,
        isSwitchingGitBranch = false,
        gitRepoSync = null,
    )
}

fun reasoningTitle(effort: String): String {
    return when (effort.trim().lowercase()) {
        "minimal", "low" -> "Low"
        "medium" -> "Medium"
        "high" -> "High"
        "xhigh", "extra_high", "extra-high", "very_high", "very-high" -> "Extra High"
        else -> effort.trim()
            .split("_", "-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            .ifBlank { effort }
    }
}
