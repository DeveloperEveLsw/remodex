package app.remodex.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.remodex.android.core.model.CodexAccessMode
import app.remodex.android.core.model.CodexCollaborationModeKind
import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.model.CodexModelOption
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadSyncState
import app.remodex.android.core.pairing.RemodexPairingParser
import app.remodex.android.core.pairing.RemodexPairingPayload
import app.remodex.android.core.transport.RemodexHandshakeResult
import app.remodex.android.core.transport.RemodexTransportClient
import app.remodex.android.core.transport.RemodexTransportDiagnostics
import app.remodex.android.core.transport.RemodexTransportException
import app.remodex.android.core.transport.RemodexThreadReadResult
import app.remodex.android.core.transport.RemodexThreadStartResult
import app.remodex.android.core.transport.RemodexTransportState
import app.remodex.android.core.transport.RemodexTurnStartResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemodexDebugUiState(
    val qrPayload: String = "",
    val parsedPairing: RemodexPairingPayload? = null,
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
}

class RemodexDebugViewModel(
    private val transport: RemodexTransportClient = RemodexTransportClient(appVersion = APP_VERSION),
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemodexDebugUiState())
    val uiState: StateFlow<RemodexDebugUiState> = _uiState.asStateFlow()

    init {
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

                val isNewInitializedConnection = state is RemodexTransportState.Connected &&
                    state.isInitialized &&
                    (previousState !is RemodexTransportState.Connected || !previousState.isInitialized)
                if (isNewInitializedConnection) {
                    val activeThreadId = _uiState.value.activeThreadId
                    refreshRuntimeOptions()
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
                _uiState.update { current ->
                    current.copy(
                        lastNotificationMethod = message.method,
                        conversation = RemodexConversationReducer.reduce(
                            conversation = current.conversation,
                            message = message,
                            knownThreadIds = current.threads.mapTo(linkedSetOf(), CodexThread::id),
                        ),
                    )
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

    fun parsePairingPayload() {
        runCatching {
            RemodexPairingParser.parse(_uiState.value.qrPayload)
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
                RemodexPairingParser.parse(_uiState.value.qrPayload)
            }.getOrElse { throwable ->
                _uiState.update { current ->
                    current.copy(errorMessage = throwable.message)
                }
                return@launch
            }

            _uiState.update { current ->
                current.copy(
                    parsedPairing = pairing,
                    sessionUrl = pairing.relaySessionUrl(),
                    isBusy = true,
                    errorMessage = null,
                )
            }

            runCatching {
                transport.connectWithRecovery(pairing = pairing)
            }.onSuccess { handshake ->
                applyHandshake(handshake)
                refreshRuntimeOptions()
                refreshThreads()
            }.onFailure { throwable ->
                _uiState.update { current ->
                    current.copy(
                        isBusy = false,
                        errorMessage = throwable.message,
                    )
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            transport.disconnect()
            _uiState.update { current ->
                current.copy(
                    isBusy = false,
                    isStartingThread = false,
                    isStartingTurn = false,
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

            _uiState.update { current ->
                current.copy(
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
                applyTurnStarted(result)
            }.onFailure { throwable ->
                _uiState.update { current ->
                    current.copy(
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
                sessionUrl = handshake.sessionUrl,
                hostInfo = handshake.hostInfo,
                supportsPlanCollaborationMode = handshake.supportsPlanCollaborationMode,
                errorMessage = null,
            )
        }
    }

    companion object {
        private const val APP_VERSION = "0.1.0"
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

    private fun applyTurnStarted(result: RemodexTurnStartResult) {
        _uiState.update { current ->
            var updatedThreads = current.threads
            result.archivedThreadId?.let { archivedThreadId ->
                updatedThreads = markThreadArchived(updatedThreads, archivedThreadId)
            }
            result.activeThread?.let { activeThread ->
                updatedThreads = upsertThread(updatedThreads, activeThread)
            }

            current.copy(
                threads = updatedThreads,
                conversation = current.conversation
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
                errorMessage = null,
            )
        }

        val isConnected = (_uiState.value.connectionState as? RemodexTransportState.Connected)?.isInitialized == true
        if (!isConnected) {
            _uiState.update { current ->
                current.copy(
                    conversation = current.conversation.withThreadLoading(threadId, isLoading = false),
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
                    errorMessage = throwable.message,
                )
            }
            return
        }

        if (!shouldLoadThreadHistory(_uiState.value, threadId, forceHydration)) {
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
        }.onFailure { throwable ->
            _uiState.update { current ->
                current.copy(
                    conversation = current.conversation.withThreadLoading(threadId, isLoading = false),
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

private fun reasoningTitle(effort: String): String {
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
