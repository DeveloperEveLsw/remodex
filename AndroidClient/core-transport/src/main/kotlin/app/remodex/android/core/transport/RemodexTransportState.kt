package app.remodex.android.core.transport

import app.remodex.android.core.model.CodexHostInfo
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
