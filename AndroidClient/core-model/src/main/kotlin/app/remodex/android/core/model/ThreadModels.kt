@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.remodex.android.core.model

import app.remodex.android.core.protocol.JsonValue
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
enum class CodexThreadSyncState {
    @SerialName("live")
    Live,

    @SerialName("archivedLocal")
    ArchivedLocal,
}

@Serializable
data class CodexThread(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val preview: String? = null,
    @Serializable(with = NullableFlexibleInstantSerializer::class)
    @JsonNames("createdAt", "created_at")
    val createdAt: Instant? = null,
    @Serializable(with = NullableFlexibleInstantSerializer::class)
    @JsonNames("updatedAt", "updated_at")
    val updatedAt: Instant? = null,
    @JsonNames("cwd", "current_working_directory", "working_directory")
    val cwd: String? = null,
    val metadata: Map<String, JsonValue>? = null,
    val syncState: CodexThreadSyncState = CodexThreadSyncState.Live,
) {
    val displayTitle: String
        get() {
            val cleanedName = name?.trim().orEmpty()
            if (cleanedName.isNotEmpty()) {
                return cleanedName
            }

            val cleanedTitle = title?.trim().orEmpty()
            if (cleanedTitle.isNotEmpty()) {
                return cleanedTitle
            }

            val cleanedPreview = preview?.trim().orEmpty()
            if (cleanedPreview.isNotEmpty()) {
                return cleanedPreview.replaceFirstChar { character ->
                    if (character.isLowerCase()) character.titlecase() else character.toString()
                }
            }

            return "Conversation"
        }

    val normalizedProjectPath: String?
        get() = normalizeProjectPath(cwd)

    val gitWorkingDirectory: String?
        get() = normalizedProjectPath ?: cwd?.trim()?.takeIf { it.isNotEmpty() }

    val projectKey: String
        get() = normalizedProjectPath ?: NO_PROJECT_GROUP_KEY

    val projectDisplayName: String
        get() {
            val path = normalizedProjectPath ?: return "No Project"
            val trimmed = path.trimEnd('/')
            val name = trimmed.substringAfterLast('/', trimmed)
            return if (name.isBlank()) path else name
        }

    companion object {
        private const val NO_PROJECT_GROUP_KEY = "__no_project__"

        fun normalizeProjectPath(value: String?): String? {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                return null
            }
            if (trimmed == "/") {
                return trimmed
            }
            return trimmed.trimEnd('/').ifEmpty { "/" }
        }
    }
}
