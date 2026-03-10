package app.remodex.android.core.model

import app.remodex.android.core.protocol.JsonValue
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CodexApprovalRequest(
    val id: String,
    val requestID: JsonValue,
    val method: String,
    val command: String? = null,
    val reason: String? = null,
    val threadId: String? = null,
    val turnId: String? = null,
    val params: JsonValue? = null,
)

@Serializable
data class CodexRecentActivityLine(
    val line: String,
    @Serializable(with = NullableFlexibleInstantSerializer::class)
    val timestamp: Instant? = null,
)

@Serializable
data class CodexRunningThreadWatch(
    val threadId: String,
    @Serializable(with = NullableFlexibleInstantSerializer::class)
    val expiresAt: Instant? = null,
)

@Serializable
enum class CodexThreadRunBadgeState {
    @SerialName("running")
    Running,

    @SerialName("ready")
    Ready,

    @SerialName("failed")
    Failed,
}

@Serializable
enum class CodexRunCompletionResult {
    @SerialName("completed")
    Completed,

    @SerialName("failed")
    Failed,
}

@Serializable
enum class CodexTurnTerminalState {
    @SerialName("completed")
    Completed,

    @SerialName("failed")
    Failed,

    @SerialName("stopped")
    Stopped,
}
