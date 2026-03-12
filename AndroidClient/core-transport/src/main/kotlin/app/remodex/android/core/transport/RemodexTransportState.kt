package app.remodex.android.core.transport

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.model.CodexCollaborationModeKind
import app.remodex.android.core.protocol.RpcError
import app.remodex.android.core.protocol.RpcMessage

sealed interface RemodexTransportState {
    data object Disconnected : RemodexTransportState

    data class Connecting(
        val sessionUrl: String,
        val attempt: Int,
    ) : RemodexTransportState

    data class Retrying(
        val sessionUrl: String,
        val attempt: Int,
        val message: String,
    ) : RemodexTransportState

    data class Connected(
        val sessionUrl: String,
        val isInitialized: Boolean,
        val hostInfo: CodexHostInfo? = null,
        val supportsPlanCollaborationMode: Boolean = false,
    ) : RemodexTransportState

    data class Failed(
        val sessionUrl: String?,
        val message: String,
        val isPermanent: Boolean,
    ) : RemodexTransportState
}

data class RemodexReconnectPolicy(
    val backoffMillis: List<Long> = listOf(1_000L, 3_000L),
)

data class RemodexHandshakeResult(
    val sessionUrl: String,
    val initializeResponse: RpcMessage,
    val hostInfo: CodexHostInfo? = null,
    val supportsPlanCollaborationMode: Boolean = false,
)

data class RemodexThreadReadResult(
    val thread: CodexThread,
    val messages: List<CodexMessage> = emptyList(),
)

data class RemodexThreadStartResult(
    val thread: CodexThread,
    val response: RpcMessage,
)

data class RemodexThreadResumeResult(
    val threadId: String,
    val thread: CodexThread? = null,
    val response: RpcMessage,
)

data class RemodexTurnStartResult(
    val requestedThreadId: String,
    val threadId: String,
    val turnId: String? = null,
    val collaborationMode: CodexCollaborationModeKind? = null,
    val downgradedCollaborationMode: Boolean = false,
    val activeThread: CodexThread? = null,
    val archivedThreadId: String? = null,
    val continuationSummary: String? = null,
    val response: RpcMessage,
)

data class RemodexTransportDiagnostics(
    val lastOutboundMethod: String? = null,
    val lastOutboundPayload: String? = null,
    val lastInboundPayload: String? = null,
    val lastRpcErrorMethod: String? = null,
    val lastRpcErrorCode: Int? = null,
    val lastRpcErrorMessage: String? = null,
    val lastRpcErrorData: String? = null,
    val lastThreadListStrategy: String? = null,
    val lastThreadListParams: String? = null,
    val recentEvents: List<String> = emptyList(),
)

enum class RemodexTransportFailureKind {
    Timeout,
    Disconnected,
    Rpc,
    Network,
    PermanentRelayClosure,
    Protocol,
    Unknown,
}

class RemodexTransportException(
    val kind: RemodexTransportFailureKind,
    override val message: String,
    cause: Throwable? = null,
    val rpcError: RpcError? = null,
    val relayCloseCode: Int? = null,
    val isPermanent: Boolean = false,
) : Exception(message, cause)
