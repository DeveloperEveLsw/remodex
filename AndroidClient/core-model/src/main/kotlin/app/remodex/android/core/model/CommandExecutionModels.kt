package app.remodex.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class CodexCommandExecutionPhase {
    @SerialName("running")
    Running,

    @SerialName("completed")
    Completed,

    @SerialName("failed")
    Failed,

    @SerialName("stopped")
    Stopped,
}

@Serializable
data class CodexCommandExecutionDetails(
    val rawCommand: String,
    val summary: String? = null,
    val dedupeKey: String? = null,
    val phase: CodexCommandExecutionPhase? = null,
)
