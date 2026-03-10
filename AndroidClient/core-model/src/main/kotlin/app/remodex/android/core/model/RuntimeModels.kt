@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.remodex.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class CodexReasoningEffortOption(
    @JsonNames("reasoningEffort", "reasoning_effort")
    val reasoningEffort: String = "",
    val description: String = "",
) {
    val id: String
        get() = reasoningEffort
}

@Serializable
data class CodexModelOption(
    val id: String = "",
    val model: String = "",
    @JsonNames("displayName", "display_name")
    val displayName: String = "",
    val description: String = "",
    @JsonNames("isDefault", "is_default")
    val isDefault: Boolean = false,
    @JsonNames("supportedReasoningEfforts", "supported_reasoning_efforts")
    val supportedReasoningEfforts: List<CodexReasoningEffortOption> = emptyList(),
    @JsonNames("defaultReasoningEffort", "default_reasoning_effort")
    val defaultReasoningEffort: String? = null,
)
