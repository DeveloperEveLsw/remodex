package app.remodex.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.remodex.android.core.model.CodexAccessMode
import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadSyncState
import app.remodex.android.core.pairing.RemodexPairingParser
import app.remodex.android.core.pairing.RemodexPairingPayload
import app.remodex.android.core.transport.RemodexHandshakeResult
import app.remodex.android.core.transport.RemodexTransportClient
import app.remodex.android.core.transport.RemodexTransportDiagnostics
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
}

class RemodexDebugViewModel(
    private val transport: RemodexTransportClient = RemodexTransportClient(appVersion = APP_VERSION),
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemodexDebugUiState())
    val uiState: StateFlow<RemodexDebugUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            transport.state.collect { state ->
                _uiState.update { current ->
                    val connectedState = state as? RemodexTransportState.Connected
                    current.copy(
                        connectionState = state,
                        hostInfo = connectedState?.hostInfo,
                        supportsPlanCollaborationMode = connectedState?.supportsPlanCollaborationMode ?: false,
                    )
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
                    accessMode = CodexAccessMode.OnRequest,
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
            _uiState.update { current ->
                current.copy(
                    conversation = current.conversation
                        .withActiveThread(threadId)
                        .withThreadLoading(threadId, isLoading = true),
                    errorMessage = null,
                )
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
                    accessMode = CodexAccessMode.OnRequest,
                    preferredProjectPath = currentState.threads
                        .firstOrNull { it.id == selectedThreadId }
                        ?.cwd,
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
                conversation = current.conversation.withActiveThread(result.threadId),
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
                    .withThreadMessages(threadResult.thread.id, threadResult.messages)
                    .withThreadLoading(threadResult.thread.id, isLoading = false),
                threads = upsertThread(current.threads, threadResult.thread),
                errorMessage = null,
            )
        }
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
