package app.remodex.android.core.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CodexMessageRole {
    @SerialName("user")
    User,

    @SerialName("assistant")
    Assistant,

    @SerialName("system")
    System,
}

@Serializable
enum class CodexMessageDeliveryState {
    @SerialName("pending")
    Pending,

    @SerialName("confirmed")
    Confirmed,

    @SerialName("failed")
    Failed,
}

@Serializable
enum class CodexMessageKind {
    @SerialName("chat")
    Chat,

    @SerialName("thinking")
    Thinking,

    @SerialName("fileChange")
    FileChange,

    @SerialName("commandExecution")
    CommandExecution,

    @SerialName("plan")
    Plan,

    @SerialName("userInputPrompt")
    UserInputPrompt,
}

@Serializable
data class CodexMessage(
    val id: String,
    val threadId: String,
    val role: CodexMessageRole,
    val kind: CodexMessageKind = CodexMessageKind.Chat,
    val text: String,
    @Serializable(with = NullableFlexibleInstantSerializer::class)
    val createdAt: Instant? = null,
    val turnId: String? = null,
    val itemId: String? = null,
    val isStreaming: Boolean = false,
    val deliveryState: CodexMessageDeliveryState = CodexMessageDeliveryState.Confirmed,
    val attachments: List<CodexImageAttachment> = emptyList(),
    val planState: CodexPlanState? = null,
    val structuredUserInputRequest: CodexStructuredUserInputRequest? = null,
    val orderIndex: Int = 0,
)
