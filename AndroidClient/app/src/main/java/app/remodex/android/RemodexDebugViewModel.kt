package app.remodex.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexAccessMode
import app.remodex.android.core.pairing.RemodexPairingParser
import app.remodex.android.core.pairing.RemodexPairingPayload
import app.remodex.android.core.transport.RemodexHandshakeResult
import app.remodex.android.core.transport.RemodexTransportClient
import app.remodex.android.core.transport.RemodexTransportDiagnostics
import app.remodex.android.core.transport.RemodexThreadReadResult
import app.remodex.android.core.transport.RemodexTransportState
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
    val selectedThreadId: String? = null,
    val selectedMessages: List<CodexMessage> = emptyList(),
    val draftTurnInput: String = "",
    val isStartingTurn: Boolean = false,
    val lastStartedTurnId: String? = null,
    val lastTurnStartSummary: String? = null,
    val isLoadingThreads: Boolean = false,
    val isLoadingThread: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
)

class RemodexDebugViewModel : ViewModel() {
    private val transport = RemodexTransportClient(appVersion = APP_VERSION)
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
                    current.copy(lastNotificationMethod = message.method)
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
                    threads = emptyList(),
                    selectedThreadId = null,
                    selectedMessages = emptyList(),
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
                    val selectedThreadStillExists = current.selectedThreadId?.let { selectedId ->
                        threads.any { it.id == selectedId }
                    } == true
                    current.copy(
                        isLoadingThreads = false,
                        threads = threads,
                        selectedThreadId = current.selectedThreadId?.takeIf { selectedThreadStillExists },
                        selectedMessages = if (selectedThreadStillExists) current.selectedMessages else emptyList(),
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

    fun selectThread(threadId: String) {
        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    selectedThreadId = threadId,
                    isLoadingThread = true,
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
                        isLoadingThread = false,
                        errorMessage = throwable.message,
                    )
                }
            }
        }
    }

    fun startTurn() {
        viewModelScope.launch {
            val currentState = _uiState.value
            val selectedThreadId = currentState.selectedThreadId
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
                )
            }.onSuccess { result ->
                _uiState.update { current ->
                    current.copy(
                        draftTurnInput = "",
                        isStartingTurn = false,
                        lastStartedTurnId = result.turnId,
                        lastTurnStartSummary = if (result.turnId != null) {
                            "turn/start acknowledged: ${result.turnId}"
                        } else {
                            "turn/start acknowledged without immediate turnId"
                        },
                        errorMessage = null,
                    )
                }
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

    private fun applyThreadRead(threadResult: RemodexThreadReadResult) {
        _uiState.update { current ->
            val updatedThreads = current.threads.toMutableList()
            val existingIndex = updatedThreads.indexOfFirst { it.id == threadResult.thread.id }
            if (existingIndex >= 0) {
                updatedThreads[existingIndex] = threadResult.thread
            } else {
                updatedThreads.add(0, threadResult.thread)
            }

            current.copy(
                isLoadingThread = false,
                selectedThreadId = threadResult.thread.id,
                selectedMessages = threadResult.messages,
                threads = updatedThreads,
                errorMessage = null,
            )
        }
    }
}
