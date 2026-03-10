@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.remodex.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class CodexImageAttachment(
    val id: String,
    val thumbnailBase64JPEG: String,
    val payloadDataURL: String? = null,
    val sourceURL: String? = null,
)

@Serializable
data class CodexSkillMetadata(
    val name: String,
    val description: String? = null,
    val path: String? = null,
    val scope: String? = null,
    val enabled: Boolean = true,
) {
    val normalizedName: String
        get() = name.trim().lowercase()

    val id: String
        get() = normalizedName
}

@Serializable
data class CodexTurnSkillMention(
    val id: String,
    val name: String? = null,
    val path: String? = null,
)

@Serializable
data class CodexFuzzyFileMatch(
    val root: String,
    val path: String,
    @JsonNames("fileName", "file_name")
    val fileName: String,
    val score: Double,
    val indices: List<Int>? = null,
) {
    val id: String
        get() = "$root|$path"
}
