package app.remodex.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.remodex.android.core.model.CodexAccessMode
import app.remodex.android.core.model.CodexApprovalRequest
import app.remodex.android.core.model.CodexCollaborationModeKind
import app.remodex.android.core.model.CodexFuzzyFileMatch
import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageDeliveryState
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexModelOption
import app.remodex.android.core.model.CodexPlanStep
import app.remodex.android.core.model.CodexPlanStepStatus
import app.remodex.android.core.model.CodexStructuredUserInputOption
import app.remodex.android.core.model.CodexStructuredUserInputQuestion
import app.remodex.android.core.model.CodexStructuredUserInputRequest
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadSyncState
import app.remodex.android.core.model.CodexSkillMetadata
import app.remodex.android.core.model.CodexTurnSkillMention
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
import app.remodex.android.core.protocol.JsonValue
import app.remodex.android.core.protocol.arrayValue
import app.remodex.android.core.protocol.boolValue
import app.remodex.android.core.protocol.objectValue
import app.remodex.android.core.protocol.stringValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    val pendingApproval: CodexApprovalRequest? = null,
    val isHandlingPendingApproval: Boolean = false,
    val submittingStructuredRequestKeys: Set<String> = emptySet(),
    val composerMentionedFiles: List<RemodexComposerMentionedFile> = emptyList(),
    val composerMentionedSkills: List<RemodexComposerMentionedSkill> = emptyList(),
    val fileAutocompleteItems: List<CodexFuzzyFileMatch> = emptyList(),
    val isFileAutocompleteVisible: Boolean = false,
    val isFileAutocompleteLoading: Boolean = false,
    val fileAutocompleteQuery: String = "",
    val skillAutocompleteItems: List<CodexSkillMetadata> = emptyList(),
    val isSkillAutocompleteVisible: Boolean = false,
    val isSkillAutocompleteLoading: Boolean = false,
    val skillAutocompleteQuery: String = "",
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

data class RemodexRealtimeSyncPolicy(
    val enabled: Boolean = true,
    val threadListForegroundIntervalMillis: Long = 8_000L,
    val threadListBackgroundIntervalMillis: Long = 75_000L,
    val activeThreadForegroundIntervalMillis: Long = 8_000L,
    val activeThreadBackgroundIdleIntervalMillis: Long = 90_000L,
    val activeThreadBackgroundRunningIntervalMillis: Long = 12_000L,
    val runningThreadWatchForegroundIntervalMillis: Long = 4_000L,
    val runningThreadWatchBackgroundIntervalMillis: Long = 15_000L,
    val runningThreadWatchTtlMillis: Long = 30_000L,
    val inactiveRunningThreadSyncLimit: Int = 3,
)

private data class RemodexRunningThreadWatch(
    val threadId: String,
    val expiresAtMillis: Long,
)

data class RemodexComposerMentionedFile(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val path: String,
)

data class RemodexComposerMentionedSkill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String? = null,
    val description: String? = null,
)

private data class RemodexTrailingFileAutocompleteToken(
    val query: String,
    val tokenRange: IntRange,
)

private data class RemodexTrailingSkillAutocompleteToken(
    val query: String,
    val tokenRange: IntRange,
)

private data class RemodexTrailingToken(
    val query: String,
    val tokenRange: IntRange,
)

private data class RemodexSkillSearchIndexEntry(
    val skill: CodexSkillMetadata,
    val searchBlob: String,
)

class RemodexDebugViewModel(
    private val transport: RemodexTransportClient = RemodexTransportClient(appVersion = APP_VERSION),
    private val relaySessionStore: RemodexRelaySessionStore = InMemoryRemodexRelaySessionStore(),
    private val realtimeSyncPolicy: RemodexRealtimeSyncPolicy = RemodexRealtimeSyncPolicy(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemodexDebugUiState())
    val uiState: StateFlow<RemodexDebugUiState> = _uiState.asStateFlow()
    private var gitStatusRefreshJob: Job? = null
    private var threadListSyncJob: Job? = null
    private var activeThreadSyncJob: Job? = null
    private var runningThreadWatchSyncJob: Job? = null
    private var fileAutocompleteDebounceJob: Job? = null
    private var skillAutocompleteDebounceJob: Job? = null
    private var gitBranchRefreshThreadId: String? = null
    private var hasAttemptedInitialAutoConnect = false
    private var isAppInForeground = true
    private var isRunningAutoReconnect = false
    private var savedRelayPairing: RemodexPairingPayload? = null
    private val runningThreadWatchById = linkedMapOf<String, RemodexRunningThreadWatch>()
    private val cachedSkillSearchIndexByRoot = mutableMapOf<String, List<RemodexSkillSearchIndexEntry>>()
    private val unsupportedSkillsAutocompleteRoots = mutableSetOf<String>()

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
                    refreshRuntimeOptions()
                    recoverThreadStateAfterInitializedConnection()
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
                val resolvedStructuredRequestKey = resolvedStructuredRequestKey(message)
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
                        pendingApproval = current.pendingApproval?.takeUnless { request ->
                            resolvedStructuredRequestKey != null &&
                                serverRequestKey(request.requestID) == resolvedStructuredRequestKey
                        },
                        isHandlingPendingApproval = if (
                            resolvedStructuredRequestKey != null &&
                            current.pendingApproval != null &&
                            serverRequestKey(current.pendingApproval.requestID) == resolvedStructuredRequestKey
                        ) {
                            false
                        } else {
                            current.isHandlingPendingApproval
                        },
                        submittingStructuredRequestKeys = resolvedStructuredRequestKey?.let { requestKey ->
                            current.submittingStructuredRequestKeys - requestKey
                        } ?: current.submittingStructuredRequestKeys,
                    )
                }
                if (refreshGitBranchesForActiveThread) {
                    refreshGitBranchTargets(activeThreadIdForRefresh)
                }
                if (scheduleGitStatusRefreshForActiveThread) {
                    scheduleGitStatusRefresh(activeThreadIdForRefresh)
                }
                sanitizeRunningThreadWatches()
            }
        }

        viewModelScope.launch {
            transport.serverRequests.collect { message ->
                _uiState.update { current ->
                    current.copy(lastServerRequestMethod = message.method)
                }
                runCatching {
                    handleServerRequest(message)
                }.onFailure { throwable ->
                    _uiState.update { current ->
                        current.copy(errorMessage = throwable.message)
                    }
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
        updateRealtimeSyncState()
        if (isForeground) {
            if (isTransportConnectedAndInitialized()) {
                recoverThreadStateAfterForegroundReturn()
            } else {
                attemptAutoReconnectOnForegroundIfNeeded()
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
        refreshComposerAutocomplete(value)
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

    fun selectFileAutocomplete(item: CodexFuzzyFileMatch) {
        val fullPath = item.path.trim().ifEmpty { item.fileName }
        val updatedInput = replacingTrailingFileAutocompleteToken(
            text = _uiState.value.draftTurnInput,
            selectedPath = item.fileName,
        ) ?: _uiState.value.draftTurnInput

        _uiState.update { current ->
            current.copy(
                draftTurnInput = updatedInput,
                composerMentionedFiles = if (current.composerMentionedFiles.any { it.path == fullPath }) {
                    current.composerMentionedFiles
                } else {
                    current.composerMentionedFiles + RemodexComposerMentionedFile(
                        fileName = item.fileName,
                        path = fullPath,
                    )
                },
                fileAutocompleteItems = emptyList(),
                isFileAutocompleteVisible = false,
                isFileAutocompleteLoading = false,
                fileAutocompleteQuery = "",
            )
        }
        resetFileAutocompleteState()
    }

    fun selectSkillAutocomplete(skill: CodexSkillMetadata) {
        val normalizedSkillName = skill.name.trim()
        if (normalizedSkillName.isEmpty()) {
            resetSkillAutocompleteState()
            return
        }

        val updatedInput = replacingTrailingSkillAutocompleteToken(
            text = _uiState.value.draftTurnInput,
            selectedSkill = normalizedSkillName,
        ) ?: _uiState.value.draftTurnInput

        _uiState.update { current ->
            current.copy(
                draftTurnInput = updatedInput,
                composerMentionedSkills = if (
                    current.composerMentionedSkills.any { it.name.equals(normalizedSkillName, ignoreCase = true) }
                ) {
                    current.composerMentionedSkills
                } else {
                    current.composerMentionedSkills + RemodexComposerMentionedSkill(
                        name = normalizedSkillName,
                        path = skill.path?.trim()?.takeIf(String::isNotEmpty),
                        description = skill.description,
                    )
                },
                skillAutocompleteItems = emptyList(),
                isSkillAutocompleteVisible = false,
                isSkillAutocompleteLoading = false,
                skillAutocompleteQuery = "",
            )
        }
        resetSkillAutocompleteState()
    }

    fun removeMentionedFile(id: String) {
        _uiState.update { current ->
            val mention = current.composerMentionedFiles.firstOrNull { it.id == id } ?: return@update current
            val ambiguousKeys = ambiguousFileNameAliasKeys(current.composerMentionedFiles)
            val collisionKey = fileNameAliasCollisionKey(mention.fileName)
            val allowFileNameAliases = collisionKey?.let { it !in ambiguousKeys } ?: true

            current.copy(
                draftTurnInput = removingFileMentionAliases(
                    mention = mention,
                    text = current.draftTurnInput,
                    allowFileNameAliases = allowFileNameAliases,
                ),
                composerMentionedFiles = current.composerMentionedFiles.filterNot { it.id == id },
            )
        }
    }

    fun removeMentionedSkill(id: String) {
        _uiState.update { current ->
            val mention = current.composerMentionedSkills.firstOrNull { it.id == id } ?: return@update current
            current.copy(
                draftTurnInput = removeBoundedToken(
                    token = "${'$'}${mention.name}",
                    text = current.draftTurnInput,
                    caseInsensitive = false,
                ),
                composerMentionedSkills = current.composerMentionedSkills.filterNot { it.id == id },
            )
        }
    }

    fun approvePendingRequest() {
        respondToPendingApproval(decision = "accept")
    }

    fun declinePendingRequest() {
        respondToPendingApproval(decision = "decline")
    }

    fun respondToStructuredUserInput(
        requestID: JsonValue,
        answersByQuestionID: Map<String, List<String>>,
    ) {
        val requestKey = serverRequestKey(requestID)
        if (requestKey.isBlank()) {
            return
        }

        val normalizedAnswers = answersByQuestionID
            .mapValues { (_, answers) ->
                answers.map(String::trim).filter(String::isNotEmpty)
            }
            .filterValues(List<String>::isNotEmpty)
        if (normalizedAnswers.isEmpty()) {
            return
        }

        _uiState.update { current ->
            if (requestKey in current.submittingStructuredRequestKeys) {
                current
            } else {
                current.copy(
                    submittingStructuredRequestKeys = current.submittingStructuredRequestKeys + requestKey,
                    errorMessage = null,
                )
            }
        }

        viewModelScope.launch {
            runCatching {
                transport.sendResponse(
                    id = requestID,
                    result = buildStructuredUserInputResponse(normalizedAnswers),
                )
            }.onFailure { throwable ->
                _uiState.update { current ->
                    current.copy(
                        submittingStructuredRequestKeys = current.submittingStructuredRequestKeys - requestKey,
                        errorMessage = throwable.message,
                    )
                }
            }
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

            connectWithPairing(pairing = pairing)
        }
    }

    fun connectWithQrPayload(rawPayload: String) {
        viewModelScope.launch {
            val trimmedPayload = rawPayload.trim()
            val pairing = runCatching {
                RemodexPairingParser.parse(trimmedPayload)
            }.getOrElse { throwable ->
                _uiState.update { current ->
                    current.copy(errorMessage = throwable.message)
                }
                return@launch
            }

            _uiState.update { current ->
                current.copy(qrPayload = trimmedPayload)
            }
            connectWithPairing(pairing = pairing)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            resetFileAutocompleteState()
            resetSkillAutocompleteState()
            gitStatusRefreshJob?.cancel()
            gitStatusRefreshJob = null
            gitBranchRefreshThreadId = null
            stopSyncLoop()
            clearRunningThreadWatches()
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
                    composerMentionedFiles = emptyList(),
                    composerMentionedSkills = emptyList(),
                    fileAutocompleteItems = emptyList(),
                    isFileAutocompleteVisible = false,
                    isFileAutocompleteLoading = false,
                    fileAutocompleteQuery = "",
                    skillAutocompleteItems = emptyList(),
                    isSkillAutocompleteVisible = false,
                    isSkillAutocompleteLoading = false,
                    skillAutocompleteQuery = "",
                    lastStartedTurnId = null,
                    lastTurnStartSummary = null,
                    pendingApproval = null,
                    isHandlingPendingApproval = false,
                    submittingStructuredRequestKeys = emptySet(),
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

    private suspend fun connectWithPairing(pairing: RemodexPairingPayload) {
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

    private fun handleTransportStateTransition(
        previousState: RemodexTransportState,
        state: RemodexTransportState,
    ) {
        when (state) {
            RemodexTransportState.Disconnected -> {
                stopSyncLoop()
                clearRunningThreadWatches()
                _uiState.update { current ->
                    current.copy(
                        isBusy = false,
                        isAttemptingAutoReconnect = false,
                        shouldAutoReconnectOnForeground = false,
                        pendingApproval = null,
                        isHandlingPendingApproval = false,
                        submittingStructuredRequestKeys = emptySet(),
                    )
                }
            }

            is RemodexTransportState.Connected -> {
                if (state.isInitialized) {
                    startSyncLoop()
                    _uiState.update { current ->
                        current.copy(
                            isBusy = false,
                            shouldAutoReconnectOnForeground = false,
                            isAttemptingAutoReconnect = false,
                            errorMessage = null,
                        )
                    }
                } else {
                    stopSyncLoop()
                }
            }

            is RemodexTransportState.Failed -> {
                stopSyncLoop()
                handleTransportFailure(state)
            }

            is RemodexTransportState.Connecting,
            is RemodexTransportState.Retrying -> Unit
        }

        if (previousState is RemodexTransportState.Connected &&
            previousState.isInitialized &&
            state is RemodexTransportState.Failed &&
            isAppInForeground &&
            shouldAttemptAutoRecovery(state)
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
                    pendingApproval = null,
                    isHandlingPendingApproval = false,
                    submittingStructuredRequestKeys = emptySet(),
                    errorMessage = state.message,
                )
            }
            return
        }

        val shouldSuppressMessage = shouldSuppressFailureMessage(state)
        val shouldAttemptAutoRecovery = shouldAttemptAutoRecovery(state)
        _uiState.update { current ->
            current.copy(
                isBusy = false,
                shouldAutoReconnectOnForeground = shouldSuppressMessage || shouldAttemptAutoRecovery,
                isAttemptingAutoReconnect = false,
                pendingApproval = null,
                isHandlingPendingApproval = false,
                submittingStructuredRequestKeys = emptySet(),
                errorMessage = if (shouldSuppressMessage || shouldAttemptAutoRecovery) {
                    null
                } else {
                    state.message
                },
            )
        }
    }

    private fun shouldAttemptAutoRecovery(state: RemodexTransportState.Failed): Boolean {
        if (state.isPermanent || savedRelayPairing == null) {
            return false
        }

        return when (state.failureKind) {
            RemodexTransportFailureKind.Timeout,
            RemodexTransportFailureKind.Network,
            RemodexTransportFailureKind.Disconnected -> true

            RemodexTransportFailureKind.PermanentRelayClosure,
            RemodexTransportFailureKind.Protocol,
            RemodexTransportFailureKind.Rpc,
            RemodexTransportFailureKind.Unknown -> false
        }
    }

    private fun shouldSuppressFailureMessage(state: RemodexTransportState.Failed): Boolean {
        return isBenignBackgroundDisconnect(state) && !isAppInForeground
    }

    private fun isBenignBackgroundDisconnect(state: RemodexTransportState.Failed): Boolean {
        return !state.isPermanent && state.failureKind == RemodexTransportFailureKind.Disconnected
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
        refreshThreadsInternal(surfaceErrors = true)
    }

    private fun refreshThreadsInternal(surfaceErrors: Boolean) {
        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    isLoadingThreads = true,
                    errorMessage = if (surfaceErrors) {
                        null
                    } else {
                        current.errorMessage
                    },
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
                        errorMessage = if (surfaceErrors) {
                            throwable.message
                        } else {
                            current.errorMessage
                        },
                    )
                }
            }
        }
    }

    private suspend fun syncThreadsListSilently() {
        val threads = runCatching {
            transport.listThreads()
        }.getOrElse {
            return
        }

        _uiState.update { current ->
            val mergedThreads = mergeThreadsFromServer(
                localThreads = current.threads,
                serverThreads = threads,
            )
            current.copy(
                threads = mergedThreads,
                conversation = current.conversation.pruneToThreads(mergedThreads.mapTo(linkedSetOf(), CodexThread::id)),
            )
        }
        sanitizeRunningThreadWatches()
    }

    private fun recoverThreadStateAfterInitializedConnection() {
        if (savedRelayPairing == null && _uiState.value.activeThreadId.isNullOrBlank()) {
            refreshThreadsInternal(surfaceErrors = false)
            return
        }
        viewModelScope.launch {
            recoverThreadState(forceHydration = true)
        }
    }

    private fun recoverThreadStateAfterForegroundReturn() {
        viewModelScope.launch {
            recoverThreadState(forceHydration = true)
        }
    }

    private suspend fun recoverThreadState(forceHydration: Boolean) {
        val activeThreadId = _uiState.value.activeThreadId
        if (!activeThreadId.isNullOrBlank()) {
            refreshThreadsInternal(surfaceErrors = false)
            prepareThreadForDisplay(
                threadId = activeThreadId,
                forceHydration = forceHydration,
                surfaceErrors = false,
            )
            return
        }

        recoverSelectedThreadFromThreadList(forceHydration = forceHydration)
    }

    private suspend fun recoverSelectedThreadFromThreadList(forceHydration: Boolean) {
        val threads = runCatching {
            transport.listThreads()
        }.getOrElse {
            return
        }
        val currentState = _uiState.value
        val mergedThreads = mergeThreadsFromServer(
            localThreads = currentState.threads,
            serverThreads = threads,
        )
        val recoveredThreadId = resolveRecoveryThreadId(
            preferredThreadId = currentState.activeThreadId,
            threads = mergedThreads,
        )
        handleDisplayedThreadChange(
            previousThreadId = currentState.activeThreadId,
            nextThreadId = recoveredThreadId,
        )

        _uiState.update { current ->
            current.copy(
                isLoadingThreads = false,
                threads = mergedThreads,
                conversation = current.conversation
                    .pruneToThreads(mergedThreads.mapTo(linkedSetOf(), CodexThread::id))
                    .withActiveThread(recoveredThreadId),
            )
        }
        val selectedThreadId = _uiState.value.activeThreadId

        if (!selectedThreadId.isNullOrBlank()) {
            prepareThreadForDisplay(
                threadId = selectedThreadId,
                forceHydration = forceHydration,
                surfaceErrors = false,
            )
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

    fun startThread(preferredProjectPath: String? = null) {
        viewModelScope.launch {
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
                    modelIdentifier = _uiState.value.selectedModelOption?.model,
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
            handleDisplayedThreadChange(
                previousThreadId = _uiState.value.activeThreadId,
                nextThreadId = threadId,
            )
            prepareThreadForDisplay(threadId)
        }
    }

    fun startTurn() {
        viewModelScope.launch {
            val currentState = _uiState.value
            val selectedThreadId = currentState.activeThreadId
            val payload = buildPayloadWithMentions(
                text = currentState.draftTurnInput,
                mentions = currentState.composerMentionedFiles,
            )
            val trimmedPayload = payload.trim()
            val skillMentions = currentState.composerMentionedSkills.map {
                CodexTurnSkillMention(
                    id = it.name,
                    name = it.name,
                    path = it.path,
                )
            }
            if (trimmedPayload.isEmpty()) {
                _uiState.update { current ->
                    current.copy(errorMessage = "Enter a prompt before sending a turn.")
                }
                return@launch
            }

            val pendingMessageId = selectedThreadId?.takeIf(String::isNotBlank)?.let { UUID.randomUUID().toString() }
            val rawInput = currentState.draftTurnInput
            val rawFileMentions = currentState.composerMentionedFiles
            val rawSkillMentions = currentState.composerMentionedSkills

            _uiState.update { current ->
                val updatedConversation = if (pendingMessageId != null && !selectedThreadId.isNullOrBlank()) {
                    current.conversation
                        .withActiveThread(selectedThreadId)
                        .appendUserMessage(
                            threadId = selectedThreadId,
                            text = trimmedPayload,
                            messageId = pendingMessageId,
                        )
                } else {
                    current.conversation
                }

                current.copy(
                    conversation = updatedConversation,
                    draftTurnInput = "",
                    composerMentionedFiles = emptyList(),
                    composerMentionedSkills = emptyList(),
                    fileAutocompleteItems = emptyList(),
                    isFileAutocompleteVisible = false,
                    isFileAutocompleteLoading = false,
                    fileAutocompleteQuery = "",
                    skillAutocompleteItems = emptyList(),
                    isSkillAutocompleteVisible = false,
                    isSkillAutocompleteLoading = false,
                    skillAutocompleteQuery = "",
                    isStartingTurn = true,
                    errorMessage = null,
                )
            }
            resetFileAutocompleteState()
            resetSkillAutocompleteState()

            runCatching {
                transport.startTurn(
                    threadId = selectedThreadId,
                    userInput = trimmedPayload,
                    accessMode = currentState.selectedAccessMode,
                    collaborationMode = currentState.selectedCollaborationMode.takeUnless {
                        it == CodexCollaborationModeKind.Default
                    },
                    preferredProjectPath = currentState.threads
                        .firstOrNull { it.id == selectedThreadId }
                        ?.cwd,
                    modelIdentifier = currentState.selectedModelOption?.model,
                    reasoningEffort = currentState.selectedReasoningEffort,
                    skillMentions = skillMentions,
                )
            }.onSuccess { result ->
                applyTurnStarted(
                    result = result,
                    pendingMessageId = pendingMessageId,
                    pendingMessageText = trimmedPayload,
                    requestedThreadId = selectedThreadId ?: result.requestedThreadId,
                )
            }.onFailure { throwable ->
                _uiState.update { current ->
                    val updatedConversation = if (pendingMessageId != null && !selectedThreadId.isNullOrBlank()) {
                        current.conversation.markMessageDeliveryState(
                            threadId = selectedThreadId,
                            messageId = pendingMessageId,
                            deliveryState = CodexMessageDeliveryState.Failed,
                        )
                    } else {
                        current.conversation
                    }

                    current.copy(
                        conversation = updatedConversation,
                        draftTurnInput = rawInput,
                        composerMentionedFiles = rawFileMentions,
                        composerMentionedSkills = rawSkillMentions,
                        isStartingTurn = false,
                        errorMessage = throwable.message,
                    )
                }
                refreshComposerAutocomplete(rawInput)
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

    private fun refreshComposerAutocomplete(text: String) {
        val currentState = _uiState.value
        if (currentState.isStartingTurn || currentState.isStartingThread) {
            clearFileAutocompleteState()
            clearSkillAutocompleteState()
            return
        }
        refreshFileAutocomplete(text)
        refreshSkillAutocomplete(text)
    }

    private fun refreshFileAutocomplete(text: String) {
        val currentState = _uiState.value
        val selectedThread = currentState.threads.firstOrNull { it.id == currentState.activeThreadId }
        val root = selectedThread?.let(::normalizedAutocompleteRoot)
        val token = trailingFileAutocompleteToken(text)
        val isConnected = isTransportConnectedAndInitialized()
        if (!isConnected || selectedThread == null || root == null || token == null) {
            clearFileAutocompleteState()
            return
        }

        clearSkillAutocompleteState()

        val query = token.query.trim()
        if (query.length < MIN_AUTOCOMPLETE_QUERY_LENGTH) {
            resetFileAutocompleteState()
            _uiState.update { current ->
                current.copy(
                    fileAutocompleteItems = emptyList(),
                    isFileAutocompleteVisible = false,
                    isFileAutocompleteLoading = false,
                    fileAutocompleteQuery = query,
                )
            }
            return
        }

        _uiState.update { current ->
            current.copy(
                fileAutocompleteQuery = query,
                isFileAutocompleteVisible = true,
                isFileAutocompleteLoading = true,
            )
        }
        resetFileAutocompleteState()

        val expectedQuery = query
        val searchRoots = listOf(root)
        val cancellationToken = fileAutocompleteCancellationToken(selectedThread.id)
        fileAutocompleteDebounceJob = viewModelScope.launch {
            delay(AUTOCOMPLETE_DEBOUNCE_MILLIS)
            runCatching {
                transport.fuzzyFileSearch(
                    query = expectedQuery,
                    roots = searchRoots,
                    cancellationToken = cancellationToken,
                )
            }.onSuccess { matches ->
                _uiState.update { current ->
                    if (current.fileAutocompleteQuery != expectedQuery) {
                        return@update current
                    }

                    current.copy(
                        fileAutocompleteItems = matches.take(MAX_FILE_AUTOCOMPLETE_ITEMS),
                        isFileAutocompleteLoading = false,
                        isFileAutocompleteVisible = true,
                    )
                }
            }.onFailure {
                _uiState.update { current ->
                    if (current.fileAutocompleteQuery != expectedQuery) {
                        return@update current
                    }

                    current.copy(
                        fileAutocompleteItems = emptyList(),
                        isFileAutocompleteLoading = false,
                        isFileAutocompleteVisible = false,
                    )
                }
            }
        }
    }

    private fun refreshSkillAutocomplete(text: String) {
        val currentState = _uiState.value
        val selectedThread = currentState.threads.firstOrNull { it.id == currentState.activeThreadId }
        val root = selectedThread?.let(::normalizedAutocompleteRoot)
        val token = trailingSkillAutocompleteToken(text)
        val isConnected = isTransportConnectedAndInitialized()
        if (!isConnected || selectedThread == null || root == null || token == null) {
            clearSkillAutocompleteState()
            return
        }

        clearFileAutocompleteState()

        val query = token.query.trim()
        if (query.length < MIN_AUTOCOMPLETE_QUERY_LENGTH) {
            resetSkillAutocompleteState()
            _uiState.update { current ->
                current.copy(
                    skillAutocompleteItems = emptyList(),
                    isSkillAutocompleteVisible = false,
                    isSkillAutocompleteLoading = false,
                    skillAutocompleteQuery = query,
                )
            }
            return
        }

        _uiState.update { current ->
            current.copy(
                skillAutocompleteQuery = query,
                isSkillAutocompleteVisible = true,
                isSkillAutocompleteLoading = cachedSkillSearchIndexByRoot[root] == null &&
                    root !in unsupportedSkillsAutocompleteRoots,
            )
        }
        resetSkillAutocompleteState()

        val expectedQuery = query
        skillAutocompleteDebounceJob = viewModelScope.launch {
            delay(AUTOCOMPLETE_DEBOUNCE_MILLIS)

            if (root in unsupportedSkillsAutocompleteRoots &&
                cachedSkillSearchIndexByRoot[root] == null
            ) {
                _uiState.update { current ->
                    if (current.skillAutocompleteQuery != expectedQuery) {
                        return@update current
                    }

                    current.copy(
                        skillAutocompleteItems = emptyList(),
                        isSkillAutocompleteLoading = false,
                        isSkillAutocompleteVisible = false,
                    )
                }
                return@launch
            }

            runCatching {
                cachedSkillSearchIndexByRoot[root] ?: transport.listSkills(
                    cwds = listOf(root),
                    forceReload = false,
                ).filter(CodexSkillMetadata::enabled)
                    .map { skill ->
                        RemodexSkillSearchIndexEntry(
                            skill = skill,
                            searchBlob = buildSkillSearchBlob(skill),
                        )
                    }.also { cachedSkillSearchIndexByRoot[root] = it }
            }.onSuccess { indexedSkills ->
                _uiState.update { current ->
                    if (current.skillAutocompleteQuery != expectedQuery) {
                        return@update current
                    }

                    current.copy(
                        skillAutocompleteItems = filteredSkillAutocompleteItems(expectedQuery, indexedSkills),
                        isSkillAutocompleteLoading = false,
                        isSkillAutocompleteVisible = true,
                    )
                }
            }.onFailure { throwable ->
                if (isMethodNotFoundRpcError(throwable)) {
                    unsupportedSkillsAutocompleteRoots += root
                }
                _uiState.update { current ->
                    if (current.skillAutocompleteQuery != expectedQuery) {
                        return@update current
                    }

                    current.copy(
                        skillAutocompleteItems = emptyList(),
                        isSkillAutocompleteLoading = false,
                        isSkillAutocompleteVisible = false,
                    )
                }
            }
        }
    }

    private fun clearFileAutocompleteState() {
        resetFileAutocompleteState()
        _uiState.update { current ->
            current.copy(
                fileAutocompleteItems = emptyList(),
                isFileAutocompleteVisible = false,
                isFileAutocompleteLoading = false,
                fileAutocompleteQuery = "",
            )
        }
    }

    private fun clearSkillAutocompleteState() {
        resetSkillAutocompleteState()
        _uiState.update { current ->
            current.copy(
                skillAutocompleteItems = emptyList(),
                isSkillAutocompleteVisible = false,
                isSkillAutocompleteLoading = false,
                skillAutocompleteQuery = "",
            )
        }
    }

    private fun resetFileAutocompleteState() {
        fileAutocompleteDebounceJob?.cancel()
        fileAutocompleteDebounceJob = null
    }

    private fun resetSkillAutocompleteState() {
        skillAutocompleteDebounceJob?.cancel()
        skillAutocompleteDebounceJob = null
    }

    private fun normalizedAutocompleteRoot(thread: CodexThread): String? {
        return thread.normalizedProjectPath
            ?: thread.cwd?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun fileAutocompleteCancellationToken(threadId: String): String {
        return "android-at-file-$threadId"
    }

    private fun buildSkillSearchBlob(skill: CodexSkillMetadata): String {
        val name = skill.name.lowercase()
        val description = skill.description?.lowercase().orEmpty()
        return if (description.isEmpty()) {
            name
        } else {
            "$name $description"
        }
    }

    private fun filteredSkillAutocompleteItems(
        query: String,
        indexedSkills: List<RemodexSkillSearchIndexEntry>,
    ): List<CodexSkillMetadata> {
        val needle = query.lowercase()
        return indexedSkills.asSequence()
            .filter { it.searchBlob.contains(needle) }
            .map(RemodexSkillSearchIndexEntry::skill)
            .take(MAX_SKILL_AUTOCOMPLETE_ITEMS)
            .toList()
    }

    private fun buildPayloadWithMentions(
        text: String,
        mentions: List<RemodexComposerMentionedFile>,
    ): String {
        var payload = text.trim()
        if (mentions.isEmpty()) {
            return payload
        }

        val ambiguousKeys = ambiguousFileNameAliasKeys(mentions)
        for (mention in mentions) {
            val collisionKey = fileNameAliasCollisionKey(mention.fileName)
            val allowFileNameAliases = collisionKey?.let { it !in ambiguousKeys } ?: true
            payload = replacingFileMentionAliases(
                text = payload,
                mention = mention,
                allowFileNameAliases = allowFileNameAliases,
            )
        }

        return payload
    }

    private fun trailingFileAutocompleteToken(text: String): RemodexTrailingFileAutocompleteToken? {
        if (text.isEmpty() || text.last().isWhitespace()) {
            return null
        }

        val triggerIndex = text.lastIndexOf('@')
        if (triggerIndex < 0) {
            return null
        }
        if (triggerIndex > 0 && !text[triggerIndex - 1].isWhitespace()) {
            return null
        }

        val rawQuery = text.substring(triggerIndex + 1)
        val query = rawQuery.trim()
        if (query.isEmpty() || rawQuery.any { it == '\n' || it == '\r' }) {
            return null
        }
        if (query.any(Char::isWhitespace)) {
            val looksFileLike = query.contains('/') || query.contains('\\') || query.contains('.')
            if (!looksFileLike) {
                return null
            }
        }

        return RemodexTrailingFileAutocompleteToken(
            query = query,
            tokenRange = triggerIndex..text.lastIndex,
        )
    }

    private fun trailingSkillAutocompleteToken(text: String): RemodexTrailingSkillAutocompleteToken? {
        val token = trailingToken(text, '$') ?: return null
        if (!token.query.any(Char::isLetter)) {
            return null
        }

        return RemodexTrailingSkillAutocompleteToken(
            query = token.query,
            tokenRange = token.tokenRange,
        )
    }

    private fun trailingToken(text: String, trigger: Char): RemodexTrailingToken? {
        if (text.isEmpty()) {
            return null
        }

        var tokenStart = 0
        for (index in text.lastIndex downTo 0) {
            if (text[index].isWhitespace()) {
                tokenStart = index + 1
                break
            }
        }

        if (tokenStart >= text.length || text[tokenStart] != trigger) {
            return null
        }

        val query = text.substring(tokenStart + 1)
        if (query.isEmpty() || query.any(Char::isWhitespace)) {
            return null
        }

        return RemodexTrailingToken(
            query = query,
            tokenRange = tokenStart..text.lastIndex,
        )
    }

    private fun replacingTrailingFileAutocompleteToken(text: String, selectedPath: String): String? {
        val trimmedPath = selectedPath.trim()
        val token = trailingFileAutocompleteToken(text) ?: return null
        if (trimmedPath.isEmpty()) {
            return null
        }

        return text.replaceRange(token.tokenRange.first, token.tokenRange.last + 1, "@$trimmedPath ")
    }

    private fun replacingTrailingSkillAutocompleteToken(text: String, selectedSkill: String): String? {
        val trimmedSkill = selectedSkill.trim()
        val token = trailingSkillAutocompleteToken(text) ?: return null
        if (trimmedSkill.isEmpty()) {
            return null
        }

        return text.replaceRange(token.tokenRange.first, token.tokenRange.last + 1, "${'$'}$trimmedSkill ")
    }

    private fun replacingFileMentionAliases(
        text: String,
        mention: RemodexComposerMentionedFile,
        allowFileNameAliases: Boolean,
    ): String {
        val replacement = "@${mention.path}"
        val placeholder = "__remodex_file_mention__${mention.path.hashCode()}__"
        val replacedText = fileMentionAliases(
            fileName = mention.fileName,
            path = mention.path,
            allowFileNameAliases = allowFileNameAliases,
        ).fold(text) { partialText, alias ->
            replaceBoundedToken(
                token = "@$alias",
                replacement = placeholder,
                text = partialText,
                caseInsensitive = true,
            )
        }
        return replacedText.replace(placeholder, replacement)
    }

    private fun removingFileMentionAliases(
        mention: RemodexComposerMentionedFile,
        text: String,
        allowFileNameAliases: Boolean,
    ): String {
        return fileMentionAliases(
            fileName = mention.fileName,
            path = mention.path,
            allowFileNameAliases = allowFileNameAliases,
        ).fold(text) { partialText, alias ->
            removeBoundedToken(
                token = "@$alias",
                text = partialText,
                caseInsensitive = true,
            )
        }
    }

    private fun fileMentionAliases(
        fileName: String,
        path: String,
        allowFileNameAliases: Boolean,
    ): List<String> {
        val aliases = linkedSetOf<String>()
        val seeds = mutableListOf(path, deletingPathExtension(path))
        if (allowFileNameAliases) {
            seeds.add(0, fileName)
            seeds += deletingPathExtension(fileName)
        }

        for (seed in seeds) {
            val trimmedSeed = seed.trim()
            if (trimmedSeed.isEmpty()) {
                continue
            }
            aliases += trimmedSeed
            appendNormalizedFileMentionAliases(trimmedSeed, aliases)
        }

        return aliases
            .map(String::trim)
            .filter(String::isNotEmpty)
            .sortedWith(compareByDescending<String> { it.length }.thenBy { it.lowercase() })
    }

    private fun appendNormalizedFileMentionAliases(seed: String, aliases: MutableSet<String>) {
        val trimmedSeed = seed.trim()
        if (trimmedSeed.isEmpty()) {
            return
        }

        val extension = trimmedSeed.substringAfterLast('.', "")
            .takeIf { '.' in trimmedSeed && it.isNotEmpty() }
            ?.lowercase()
            .orEmpty()
        val stem = if (extension.isEmpty()) trimmedSeed else trimmedSeed.substringBeforeLast('.')
        val tokens = mentionSearchTokens(stem)
        if (tokens.isEmpty()) {
            return
        }

        val baseVariants = linkedSetOf(
            tokens.joinToString(" "),
            tokens.joinToString("-"),
            tokens.joinToString("_"),
            tokens.joinToString(""),
            lowerCamelCase(tokens),
            upperCamelCase(tokens),
        ).filter(String::isNotEmpty)

        for (variant in baseVariants) {
            aliases += variant
            if (extension.isNotEmpty()) {
                aliases += "$variant.$extension"
            }
        }
    }

    private fun mentionSearchTokens(value: String): List<String> {
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) {
            return emptyList()
        }

        return trimmedValue
            .split(Regex("[^A-Za-z0-9]+"))
            .filter(String::isNotEmpty)
            .flatMap(::tokensFromMentionSegment)
    }

    private fun tokensFromMentionSegment(segment: String): List<String> {
        val trimmedSegment = segment.trim()
        if (trimmedSegment.isEmpty()) {
            return emptyList()
        }

        val rawTokens = FILE_MENTION_SEGMENT_REGEX.findAll(trimmedSegment)
            .map { it.value }
            .toList()
            .ifEmpty { listOf(trimmedSegment.lowercase()) }

        val normalizedTokens = mutableListOf<String>()
        var index = 0
        while (index < rawTokens.size) {
            val token = rawTokens[index]
            if (token.length == 1 &&
                token == token.lowercase() &&
                index + 1 < rawTokens.size &&
                isAllCapsAcronym(rawTokens[index + 1])
            ) {
                normalizedTokens += (token + rawTokens[index + 1]).lowercase()
                index += 2
                continue
            }

            normalizedTokens += token.lowercase()
            index += 1
        }
        return normalizedTokens
    }

    private fun isAllCapsAcronym(token: String): Boolean {
        return token.length > 1 && token.all { it.isUpperCase() || it.isDigit() }
    }

    private fun lowerCamelCase(tokens: List<String>): String {
        val first = tokens.firstOrNull() ?: return ""
        return first + tokens.drop(1).joinToString("") { capitalizedToken(it) }
    }

    private fun upperCamelCase(tokens: List<String>): String {
        return tokens.joinToString("") { capitalizedToken(it) }
    }

    private fun capitalizedToken(token: String): String {
        return token.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
    }

    private fun deletingPathExtension(value: String): String {
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) {
            return ""
        }
        val lastDotIndex = trimmedValue.lastIndexOf('.')
        return if (lastDotIndex <= 0) {
            trimmedValue
        } else {
            trimmedValue.substring(0, lastDotIndex)
        }
    }

    private fun fileNameAliasCollisionKey(fileName: String): String? {
        val trimmedName = fileName.trim()
        if (trimmedName.isEmpty()) {
            return null
        }

        val extension = trimmedName.substringAfterLast('.', "")
            .takeIf { '.' in trimmedName && it.isNotEmpty() }
            ?.lowercase()
            .orEmpty()
        val stem = deletingPathExtension(trimmedName)
        val tokens = mentionSearchTokens(stem)
        if (tokens.isEmpty()) {
            return if (extension.isEmpty()) null else ".$extension"
        }

        val tokenKey = tokens.joinToString("|")
        return if (extension.isEmpty()) tokenKey else "$tokenKey.$extension"
    }

    private fun ambiguousFileNameAliasKeys(mentions: List<RemodexComposerMentionedFile>): Set<String> {
        return mentions.mapNotNull { fileNameAliasCollisionKey(it.fileName) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    }

    private fun replaceBoundedToken(
        token: String,
        replacement: String,
        text: String,
        caseInsensitive: Boolean,
    ): String {
        val regex = Regex(
            pattern = Regex.escape(token) + "(?=[\\s,.;:!?)\\]}>]|$)",
            options = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet(),
        )
        return regex.replace(text, replacement)
    }

    private fun removeBoundedToken(
        token: String,
        text: String,
        caseInsensitive: Boolean,
    ): String {
        val regex = Regex(
            pattern = Regex.escape(token) + "(?:[\\s,.;:!?)\\]}>]|$)",
            options = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet(),
        )
        return regex.replaceFirst(text, "")
    }

    private fun isMethodNotFoundRpcError(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase().orEmpty()
        return message.contains("method not found") ||
            message.contains("unsupported") ||
            message.contains("code -32601")
    }

    private fun respondToPendingApproval(decision: String) {
        val pendingApproval = _uiState.value.pendingApproval ?: return
        if (_uiState.value.isHandlingPendingApproval) {
            return
        }

        _uiState.update { current ->
            current.copy(
                isHandlingPendingApproval = true,
                errorMessage = null,
            )
        }

        viewModelScope.launch {
            runCatching {
                transport.sendResponse(
                    id = pendingApproval.requestID,
                    result = JsonPrimitive(decision),
                )
            }.onSuccess {
                _uiState.update { current ->
                    current.copy(
                        pendingApproval = current.pendingApproval?.takeUnless { it.id == pendingApproval.id },
                        isHandlingPendingApproval = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { throwable ->
                _uiState.update { current ->
                    current.copy(
                        isHandlingPendingApproval = false,
                        errorMessage = throwable.message,
                    )
                }
            }
        }
    }

    private suspend fun handleServerRequest(message: app.remodex.android.core.protocol.RpcMessage) {
        val method = message.method?.trim().orEmpty()
        val requestID = message.id ?: return
        val normalizedMethod = normalizeServerRequestMethod(method)

        when {
            normalizedMethod == "item/tool/requestuserinput" -> {
                handleStructuredUserInputRequest(
                    requestID = requestID,
                    paramsObject = message.params?.objectValue,
                )
            }

            normalizedMethod.endsWith("requestapproval") -> {
                handleApprovalRequest(
                    method = method,
                    requestID = requestID,
                    params = message.params,
                )
            }

            else -> {
                transport.sendErrorResponse(
                    id = requestID,
                    code = -32601,
                    message = "Unsupported request method: $method",
                )
            }
        }
    }

    private fun handleStructuredUserInputRequest(
        requestID: JsonValue,
        paramsObject: JsonObject?,
    ) {
        val threadId = paramsObject.resolveRequestString("threadId", "thread_id") ?: return
        val turnId = paramsObject.resolveRequestString("turnId", "turn_id") ?: return
        val itemId = paramsObject.resolveRequestString("itemId", "item_id")
            ?: "request-${serverRequestKey(requestID)}"
        val questions = decodeStructuredUserInputQuestions(paramsObject?.get("questions"))
        if (questions.isEmpty()) {
            return
        }

        _uiState.update { current ->
            current.copy(
                conversation = current.conversation.upsertStructuredUserInputPrompt(
                    threadId = threadId,
                    turnId = turnId,
                    itemId = itemId,
                    request = CodexStructuredUserInputRequest(
                        requestID = requestID,
                        questions = questions,
                    ),
                ),
                errorMessage = null,
            )
        }
    }

    private suspend fun handleApprovalRequest(
        method: String,
        requestID: JsonValue,
        params: JsonValue?,
    ) {
        val paramsObject = params?.objectValue
        val request = CodexApprovalRequest(
            id = serverRequestKey(requestID),
            requestID = requestID,
            method = method,
            command = paramsObject.resolveRequestString("command", "cmd"),
            reason = paramsObject.resolveRequestString("reason"),
            threadId = paramsObject.resolveRequestString("threadId", "thread_id"),
            turnId = paramsObject.resolveRequestString("turnId", "turn_id"),
            params = params,
        )

        if (_uiState.value.selectedAccessMode == CodexAccessMode.FullAccess) {
            runCatching {
                transport.sendResponse(
                    id = requestID,
                    result = JsonPrimitive("accept"),
                )
            }.onFailure {
                _uiState.update { current ->
                    current.copy(
                        pendingApproval = request,
                        isHandlingPendingApproval = false,
                    )
                }
            }
            return
        }

        _uiState.update { current ->
            current.copy(
                pendingApproval = request,
                isHandlingPendingApproval = false,
                errorMessage = null,
            )
        }
    }

    private fun decodeStructuredUserInputQuestions(value: JsonValue?): List<CodexStructuredUserInputQuestion> {
        val items = value?.arrayValue ?: return emptyList()
        return items.mapIndexedNotNull { index, questionValue ->
            val questionObject = questionValue.objectValue ?: return@mapIndexedNotNull null
            val id = questionObject.resolveRequestString("id") ?: return@mapIndexedNotNull null
            val header = questionObject.resolveRequestText("header") ?: return@mapIndexedNotNull null
            val question = questionObject.resolveRequestText("question") ?: return@mapIndexedNotNull null
            val options = (questionObject["options"]?.arrayValue ?: JsonArray(emptyList())).mapIndexedNotNull { optionIndex, optionValue ->
                val optionObject = optionValue.objectValue ?: return@mapIndexedNotNull null
                val label = optionObject.resolveRequestString("label") ?: return@mapIndexedNotNull null
                val description = optionObject.resolveRequestText("description") ?: ""
                CodexStructuredUserInputOption(
                    id = optionObject.resolveRequestString("id") ?: "$id-option-$optionIndex",
                    label = label,
                    description = description,
                )
            }

            CodexStructuredUserInputQuestion(
                id = id,
                header = header,
                question = question,
                isOther = questionObject["isOther"]?.boolValue == true,
                isSecret = questionObject["isSecret"]?.boolValue == true,
                options = options,
            )
        }
    }

    private fun buildStructuredUserInputResponse(
        answersByQuestionID: Map<String, List<String>>,
    ): JsonObject {
        return JsonObject(
            mapOf(
                "answers" to JsonObject(
                    answersByQuestionID.mapValues { (_, answers) ->
                        JsonObject(
                            mapOf(
                                "answers" to JsonArray(answers.map(::JsonPrimitive)),
                            ),
                        )
                    },
                ),
            ),
        )
    }

    private fun resolvedStructuredRequestKey(message: app.remodex.android.core.protocol.RpcMessage): String? {
        val normalizedMethod = message.method?.trim()?.lowercase() ?: return null
        if (normalizedMethod != "serverrequest/resolved") {
            return null
        }

        val paramsObject = message.params?.objectValue ?: return null
        val requestID = paramsObject["requestId"] ?: paramsObject["requestID"] ?: return null
        return serverRequestKey(requestID)
    }

    private fun normalizeServerRequestMethod(method: String): String {
        return method
            .trim()
            .lowercase()
            .replace("_", "")
            .replace("-", "")
    }

    private fun JsonObject?.resolveRequestString(vararg keys: String): String? {
        if (this == null) {
            return null
        }

        for (key in keys) {
            val value = this[key]?.stringValue?.trim()
            if (!value.isNullOrEmpty()) {
                return value
            }
        }
        return null
    }

    private fun JsonObject?.resolveRequestText(vararg keys: String): String? {
        if (this == null) {
            return null
        }

        for (key in keys) {
            val value = this[key]?.stringValue ?: continue
            return value
        }
        return null
    }

    private fun serverRequestKey(requestID: JsonValue): String {
        return when (requestID) {
            is JsonPrimitive -> requestID.content
            else -> requestID.toString()
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
                pendingApproval = null,
                isHandlingPendingApproval = false,
                submittingStructuredRequestKeys = emptySet(),
                errorMessage = null,
            )
        }
    }

    companion object {
        private const val APP_VERSION = "0.1.0"
        private const val GIT_STATUS_REFRESH_DEBOUNCE_MILLIS = 350L
        private const val CONNECTING_POLL_DELAY_MILLIS = 300L
        private const val MAX_FOREGROUND_RECONNECT_ATTEMPTS = 20
        private const val MIN_AUTOCOMPLETE_QUERY_LENGTH = 2
        private const val AUTOCOMPLETE_DEBOUNCE_MILLIS = 180L
        private const val MAX_FILE_AUTOCOMPLETE_ITEMS = 6
        private const val MAX_SKILL_AUTOCOMPLETE_ITEMS = 6
        private val AUTO_RECONNECT_BACKOFF_MILLIS = listOf(1_000L, 3_000L)
        private val FILE_MENTION_SEGMENT_REGEX = Regex("[A-Z]+(?=$|[A-Z][a-z]|\\d)|[A-Z]?[a-z]+|\\d+")
    }

    private fun applyThreadStarted(result: RemodexThreadStartResult) {
        handleDisplayedThreadChange(
            previousThreadId = _uiState.value.activeThreadId,
            nextThreadId = result.thread.id,
        )
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
        pendingMessageText: String? = null,
        requestedThreadId: String = result.requestedThreadId,
    ) {
        handleDisplayedThreadChange(
            previousThreadId = _uiState.value.activeThreadId,
            nextThreadId = result.threadId,
        )
        result.archivedThreadId?.let(transport::clearResumedThread)
        _uiState.update { current ->
            var updatedThreads = current.threads
            var updatedConversation = current.conversation
            result.archivedThreadId?.let { archivedThreadId ->
                updatedThreads = markThreadArchived(updatedThreads, archivedThreadId)
                updatedConversation = updatedConversation.handleMissingThread(archivedThreadId)
            }
            result.activeThread?.let { activeThread ->
                updatedThreads = upsertThread(updatedThreads, activeThread)
            }

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
            } else if (!pendingMessageText.isNullOrBlank()) {
                updatedConversation = updatedConversation.appendUserMessage(
                    threadId = result.threadId,
                    text = pendingMessageText,
                    turnId = resolvedTurnId,
                    deliveryState = if (resolvedTurnId == null) {
                        CodexMessageDeliveryState.Pending
                    } else {
                        CodexMessageDeliveryState.Confirmed
                    },
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

    private fun applyThreadRead(
        threadResult: RemodexThreadReadResult,
        markViewed: Boolean,
    ) {
        _uiState.update { current ->
            val updatedConversation = if (markViewed) {
                current.conversation.markThreadAsViewed(threadResult.thread.id)
            } else {
                current.conversation
            }
            current.copy(
                conversation = updatedConversation
                    .withRefreshedInFlightTurnState(threadResult.thread.id, threadResult.turnStateSnapshot)
                    .mergeHydratedThreadMessages(threadResult.thread.id, threadResult.messages)
                    .withThreadLoading(threadResult.thread.id, isLoading = false),
                threads = upsertThread(current.threads, threadResult.thread),
                errorMessage = null,
            )
        }
        sanitizeRunningThreadWatches()
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

        applyThreadRead(
            threadResult = threadResult,
            markViewed = _uiState.value.activeThreadId == threadId,
        )
        return threadResult.turnStateSnapshot.interruptibleTurnId ?: threadResult.turnStateSnapshot.latestTurnId
    }

    private suspend fun prepareThreadForDisplay(
        threadId: String,
        forceHydration: Boolean = false,
        surfaceErrors: Boolean = true,
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
                isLoadingGitBranchTargets = false,
                errorMessage = if (surfaceErrors) {
                    null
                } else {
                    current.errorMessage
                },
            )
        }

        val isConnected = (transport.state.value as? RemodexTransportState.Connected)?.isInitialized == true
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
            if (shouldTreatAsMissingThread(throwable)) {
                handleMissingThreadLocally(threadId)
                return
            }
            _uiState.update { current ->
                current.copy(
                    conversation = current.conversation.withThreadLoading(threadId, isLoading = false),
                    isLoadingGitBranchTargets = false,
                    errorMessage = if (surfaceErrors) {
                        throwable.message
                    } else {
                        current.errorMessage
                    },
                )
            }
            return
        }

        val shouldHydrateHistory = shouldLoadThreadHistory(_uiState.value, threadId, forceHydration)

        val threadReadResult = runCatching {
            transport.readThread(threadId = threadId, includeTurns = true)
        }.onSuccess { result ->
            if (shouldHydrateHistory) {
                applyThreadRead(
                    threadResult = result,
                    markViewed = true,
                )
            } else {
                applyThreadTurnStateRefresh(
                    threadResult = result,
                    markViewed = true,
                )
            }
        }.onFailure { throwable ->
            if (shouldTreatAsMissingThread(throwable)) {
                handleMissingThreadLocally(threadId)
                return
            }
            _uiState.update { current ->
                current.copy(
                    conversation = current.conversation.withThreadLoading(threadId, isLoading = false),
                    isLoadingGitBranchTargets = false,
                    errorMessage = if (surfaceErrors) {
                        throwable.message
                    } else {
                        current.errorMessage
                    },
                )
            }
            return
        }.getOrNull()

        if (threadReadResult != null &&
            _uiState.value.conversation.threadHasActiveOrRunningTurn(threadId)
        ) {
            transport.clearResumedThread(threadId)
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
            }
        }

        if (!shouldHydrateHistory) {
            refreshGitBranchTargets(threadId)
            _uiState.update { current ->
                current.copy(
                    conversation = current.conversation.withThreadLoading(threadId, isLoading = false),
                )
            }
            requestImmediateSync(threadId)
            return
        }

        refreshGitBranchTargets(threadId)
        requestImmediateSync(threadId)
    }

    private fun shouldLoadThreadHistory(
        state: RemodexDebugUiState,
        threadId: String,
        forceHydration: Boolean,
    ): Boolean {
        return forceHydration || !state.conversation.isHydratedThread(threadId)
    }

    private fun applyThreadTurnStateRefresh(
        threadResult: RemodexThreadReadResult,
        markViewed: Boolean,
    ) {
        _uiState.update { current ->
            val updatedConversation = if (markViewed) {
                current.conversation.markThreadAsViewed(threadResult.thread.id)
            } else {
                current.conversation
            }
            current.copy(
                conversation = updatedConversation
                    .withRefreshedInFlightTurnState(threadResult.thread.id, threadResult.turnStateSnapshot)
                    .withThreadLoading(threadResult.thread.id, isLoading = false),
                threads = upsertThread(current.threads, threadResult.thread),
                errorMessage = null,
            )
        }
        sanitizeRunningThreadWatches()
    }

    private fun resolveRecoveryThreadId(
        preferredThreadId: String?,
        threads: List<CodexThread>,
    ): String? {
        val normalizedPreferredThreadId = preferredThreadId?.trim()?.takeIf(String::isNotEmpty)
        if (normalizedPreferredThreadId != null &&
            threads.any { it.id == normalizedPreferredThreadId }
        ) {
            return normalizedPreferredThreadId
        }

        return threads.firstOrNull { it.syncState == CodexThreadSyncState.Live }?.id
            ?: threads.firstOrNull()?.id
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

    private fun handleMissingThreadLocally(threadId: String) {
        transport.clearResumedThread(threadId)
        clearRunningThreadWatch(threadId)
        _uiState.update { current ->
            current.copy(
                threads = markThreadArchived(current.threads, threadId),
                conversation = current.conversation.handleMissingThread(threadId),
                isLoadingGitBranchTargets = false,
                errorMessage = null,
            )
        }
    }

    private fun shouldTreatAsMissingThread(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase() ?: return false
        if (message.contains("not materialized") || message.contains("not yet materialized")) {
            return false
        }
        if (message.contains("thread not found") || message.contains("unknown thread")) {
            return true
        }
        val missingRollout = message.contains("norolloutfound")
            || message.contains("no rollout found")
            || message.contains("rollout not found")
        return missingRollout && (message.contains("threadid") || message.contains("thread"))
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

    private fun canRunRealtimeSyncLoop(): Boolean {
        return realtimeSyncPolicy.enabled && isTransportConnectedAndInitialized()
    }

    private fun updateRealtimeSyncState() {
        if (canRunRealtimeSyncLoop()) {
            startSyncLoop()
        } else {
            stopSyncLoop()
        }
    }

    private fun startSyncLoop() {
        if (!canRunRealtimeSyncLoop()) {
            stopSyncLoop()
            return
        }

        stopSyncLoop()
        threadListSyncJob = viewModelScope.launch {
            while (true) {
                syncThreadsListSilently()
                refreshInactiveRunningBadgeThreads(realtimeSyncPolicy.inactiveRunningThreadSyncLimit)
                delay(
                    if (isAppInForeground) {
                        realtimeSyncPolicy.threadListForegroundIntervalMillis
                    } else {
                        realtimeSyncPolicy.threadListBackgroundIntervalMillis
                    },
                )
            }
        }
        activeThreadSyncJob = viewModelScope.launch {
            while (true) {
                val activeThreadId = normalizeThreadId(_uiState.value.activeThreadId)
                if (activeThreadId != null) {
                    val hasActiveOrRunningTurn = _uiState.value.conversation.threadHasActiveOrRunningTurn(activeThreadId)
                    syncActiveThreadState(activeThreadId)
                    delay(
                        when {
                            isAppInForeground -> realtimeSyncPolicy.activeThreadForegroundIntervalMillis
                            hasActiveOrRunningTurn -> realtimeSyncPolicy.activeThreadBackgroundRunningIntervalMillis
                            else -> realtimeSyncPolicy.activeThreadBackgroundIdleIntervalMillis
                        },
                    )
                    continue
                }

                delay(
                    if (isAppInForeground) {
                        realtimeSyncPolicy.activeThreadForegroundIntervalMillis
                    } else {
                        realtimeSyncPolicy.activeThreadBackgroundIdleIntervalMillis
                    },
                )
            }
        }
        runningThreadWatchSyncJob = viewModelScope.launch {
            while (true) {
                refreshInactiveRunningBadgeThreads(realtimeSyncPolicy.inactiveRunningThreadSyncLimit)
                delay(
                    if (isAppInForeground) {
                        realtimeSyncPolicy.runningThreadWatchForegroundIntervalMillis
                    } else {
                        realtimeSyncPolicy.runningThreadWatchBackgroundIntervalMillis
                    },
                )
            }
        }
        requestImmediateSync()
    }

    private fun stopSyncLoop() {
        threadListSyncJob?.cancel()
        threadListSyncJob = null
        activeThreadSyncJob?.cancel()
        activeThreadSyncJob = null
        runningThreadWatchSyncJob?.cancel()
        runningThreadWatchSyncJob = null
    }

    private fun requestImmediateSync(threadId: String? = _uiState.value.activeThreadId) {
        if (!canRunRealtimeSyncLoop()) {
            return
        }

        viewModelScope.launch {
            syncThreadsListSilently()
            refreshInactiveRunningBadgeThreads(realtimeSyncPolicy.inactiveRunningThreadSyncLimit)
            val targetThreadId = normalizeThreadId(threadId) ?: normalizeThreadId(_uiState.value.activeThreadId)
            if (targetThreadId != null) {
                syncActiveThreadState(targetThreadId)
            }
        }
    }

    private suspend fun syncActiveThreadState(threadId: String) {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return
        val wasRunning = _uiState.value.conversation.threadHasActiveOrRunningTurn(normalizedThreadId)
        if (wasRunning) {
            val didRefresh = refreshThreadState(
                threadId = normalizedThreadId,
                forceHydration = false,
                markViewed = _uiState.value.activeThreadId == normalizedThreadId,
            )
            if (didRefresh && _uiState.value.conversation.threadHasActiveOrRunningTurn(normalizedThreadId)) {
                return
            }
        }

        syncThreadHistory(
            threadId = normalizedThreadId,
            forceHydration = true,
            markViewed = _uiState.value.activeThreadId == normalizedThreadId,
            markReadyWhenComplete = false,
        )
    }

    private suspend fun refreshInactiveRunningBadgeThreads(limit: Int) {
        sanitizeRunningThreadWatches()

        val candidateThreadIds = runningThreadWatchById.values
            .sortedBy(RemodexRunningThreadWatch::expiresAtMillis)
            .map(RemodexRunningThreadWatch::threadId)
            .take(limit)

        for (threadId in candidateThreadIds) {
            val wasRunning = _uiState.value.conversation.threadHasActiveOrRunningTurn(threadId)
            val didRefresh = refreshThreadState(
                threadId = threadId,
                forceHydration = false,
                markViewed = false,
            )
            if (didRefresh && wasRunning && _uiState.value.conversation.threadHasActiveOrRunningTurn(threadId)) {
                continue
            }

            syncThreadHistory(
                threadId = threadId,
                forceHydration = true,
                markViewed = false,
                markReadyWhenComplete = true,
            )
        }
    }

    private suspend fun refreshThreadState(
        threadId: String,
        forceHydration: Boolean,
        markViewed: Boolean,
    ): Boolean {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return false
        val threadResult = runCatching {
            transport.readThread(threadId = normalizedThreadId, includeTurns = true)
        }.getOrElse { throwable ->
            if (shouldTreatAsMissingThread(throwable)) {
                handleMissingThreadLocally(normalizedThreadId)
            }
            return false
        }

        if (forceHydration || !_uiState.value.conversation.isHydratedThread(normalizedThreadId)) {
            applyThreadRead(
                threadResult = threadResult,
                markViewed = markViewed,
            )
        } else {
            applyThreadTurnStateRefresh(
                threadResult = threadResult,
                markViewed = markViewed,
            )
        }
        return true
    }

    private suspend fun syncThreadHistory(
        threadId: String,
        forceHydration: Boolean,
        markViewed: Boolean,
        markReadyWhenComplete: Boolean,
    ) {
        val didRefresh = refreshThreadState(
            threadId = threadId,
            forceHydration = forceHydration,
            markViewed = markViewed,
        )
        if (!didRefresh) {
            return
        }

        if (!markReadyWhenComplete || _uiState.value.conversation.threadHasActiveOrRunningTurn(threadId)) {
            return
        }

        clearRunningThreadWatch(threadId)
        _uiState.update { current ->
            val updatedConversation = if (current.conversation.failedThreadIds.contains(threadId)) {
                current.conversation
            } else {
                current.conversation.markReadyIfUnread(threadId)
            }
            current.copy(conversation = updatedConversation)
        }
    }

    private fun watchRunningThreadIfNeeded(
        threadId: String?,
        nextActiveThreadId: String? = _uiState.value.activeThreadId,
        ttlMillis: Long = realtimeSyncPolicy.runningThreadWatchTtlMillis,
    ) {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return
        val currentState = _uiState.value
        if (normalizedThreadId == normalizeThreadId(nextActiveThreadId) ||
            !currentState.conversation.threadHasActiveOrRunningTurn(normalizedThreadId)
        ) {
            return
        }

        runningThreadWatchById[normalizedThreadId] = RemodexRunningThreadWatch(
            threadId = normalizedThreadId,
            expiresAtMillis = System.currentTimeMillis() + ttlMillis,
        )
    }

    private fun clearRunningThreadWatch(threadId: String?) {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return
        runningThreadWatchById.remove(normalizedThreadId)
    }

    private fun clearRunningThreadWatches() {
        runningThreadWatchById.clear()
    }

    private fun handleDisplayedThreadChange(
        previousThreadId: String?,
        nextThreadId: String?,
    ) {
        val normalizedPreviousThreadId = normalizeThreadId(previousThreadId)
        val normalizedNextThreadId = normalizeThreadId(nextThreadId)
        if (normalizedPreviousThreadId == normalizedNextThreadId) {
            return
        }

        watchRunningThreadIfNeeded(
            threadId = normalizedPreviousThreadId,
            nextActiveThreadId = normalizedNextThreadId,
        )
        clearRunningThreadWatch(normalizedNextThreadId)
    }

    private fun sanitizeRunningThreadWatches(nowMillis: Long = System.currentTimeMillis()) {
        val currentState = _uiState.value
        val availableThreadIds = currentState.threads.mapTo(linkedSetOf(), CodexThread::id)
        val activeThreadId = currentState.activeThreadId
        val iterator = runningThreadWatchById.entries.iterator()
        while (iterator.hasNext()) {
            val (_, watch) = iterator.next()
            if (watch.expiresAtMillis <= nowMillis ||
                watch.threadId == activeThreadId ||
                !availableThreadIds.contains(watch.threadId) ||
                !currentState.conversation.threadHasActiveOrRunningTurn(watch.threadId)
            ) {
                iterator.remove()
            }
        }
    }

    private fun normalizeThreadId(threadId: String?): String? {
        return threadId?.trim()?.takeIf(String::isNotEmpty)
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

    override fun onCleared() {
        resetFileAutocompleteState()
        resetSkillAutocompleteState()
        super.onCleared()
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
