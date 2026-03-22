package app.remodex.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class CodexCommandExecutionDetails(
    val rawCommand: String,
    val summary: String? = null,
    val dedupeKey: String? = null,
)
