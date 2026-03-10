package app.remodex.android.core.model

import app.remodex.android.core.protocol.JsonValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CodexCollaborationModeKind {
    @SerialName("default")
    Default,

    @SerialName("plan")
    Plan,
}

@Serializable
enum class CodexPlanStepStatus {
    @SerialName("pending")
    Pending,

    @SerialName("in_progress")
    InProgress,

    @SerialName("completed")
    Completed,
}

@Serializable
data class CodexPlanStep(
    val id: String,
    val step: String,
    val status: CodexPlanStepStatus,
)

@Serializable
data class CodexPlanState(
    val explanation: String? = null,
    val steps: List<CodexPlanStep> = emptyList(),
)

@Serializable
data class CodexStructuredUserInputOption(
    val id: String,
    val label: String,
    val description: String,
)

@Serializable
data class CodexStructuredUserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val isOther: Boolean = false,
    val isSecret: Boolean = false,
    val options: List<CodexStructuredUserInputOption> = emptyList(),
)

@Serializable
data class CodexStructuredUserInputRequest(
    val requestID: JsonValue,
    val questions: List<CodexStructuredUserInputQuestion> = emptyList(),
)
