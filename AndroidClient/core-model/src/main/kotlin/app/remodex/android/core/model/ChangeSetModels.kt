package app.remodex.android.core.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AIFileChangeKind {
    @SerialName("create")
    Create,

    @SerialName("update")
    Update,

    @SerialName("delete")
    Delete,
}

@Serializable
data class AIFileChange(
    val path: String,
    val kind: AIFileChangeKind,
    val additions: Int = 0,
    val deletions: Int = 0,
    val isBinary: Boolean = false,
    val isRenameOrModeOnly: Boolean = false,
    val beforeContentHash: String? = null,
    val afterContentHash: String? = null,
) {
    val id: String
        get() = path
}

@Serializable
enum class AIChangeSetStatus {
    @SerialName("collecting")
    Collecting,

    @SerialName("ready")
    Ready,

    @SerialName("reverted")
    Reverted,

    @SerialName("failed")
    Failed,

    @SerialName("not_revertable")
    NotRevertable,
}

@Serializable
enum class AIChangeSetSource {
    @SerialName("turnDiff")
    TurnDiff,

    @SerialName("fileChangeFallback")
    FileChangeFallback,
}

@Serializable
data class AIRevertMetadata(
    @Serializable(with = NullableFlexibleInstantSerializer::class)
    val revertedAt: Instant? = null,
    @Serializable(with = NullableFlexibleInstantSerializer::class)
    val revertAttemptedAt: Instant? = null,
    val lastRevertError: String? = null,
)

@Serializable
data class AIChangeSet(
    val id: String,
    val repoRoot: String? = null,
    val threadId: String,
    val turnId: String,
    val assistantMessageId: String? = null,
    @Serializable(with = NullableFlexibleInstantSerializer::class)
    val createdAt: Instant? = null,
    @Serializable(with = NullableFlexibleInstantSerializer::class)
    val finalizedAt: Instant? = null,
    val status: AIChangeSetStatus = AIChangeSetStatus.Collecting,
    val source: AIChangeSetSource,
    val forwardUnifiedPatch: String = "",
    val inverseUnifiedPatch: String? = null,
    val patchHash: String = "",
    val fileChanges: List<AIFileChange> = emptyList(),
    val unsupportedReasons: List<String> = emptyList(),
    val revertMetadata: AIRevertMetadata = AIRevertMetadata(),
    val fallbackPatchCount: Int = 0,
)

@Serializable
data class RevertConflict(
    val path: String,
    val message: String,
)

@Serializable
data class RevertPreviewResult(
    val canRevert: Boolean = false,
    val affectedFiles: List<String> = emptyList(),
    val conflicts: List<RevertConflict> = emptyList(),
    val unsupportedReasons: List<String> = emptyList(),
    val stagedFiles: List<String> = emptyList(),
)

@Serializable
data class RevertApplyResult(
    val success: Boolean = false,
    val revertedFiles: List<String> = emptyList(),
    val conflicts: List<RevertConflict> = emptyList(),
    val unsupportedReasons: List<String> = emptyList(),
    val stagedFiles: List<String> = emptyList(),
    val status: GitRepoSyncResult? = null,
)

@Serializable
data class AssistantRevertPresentation(
    val title: String,
    val isEnabled: Boolean,
    val helperText: String? = null,
)

@Serializable
data class AIUnifiedPatchAnalysis(
    val fileChanges: List<AIFileChange> = emptyList(),
    val unsupportedReasons: List<String> = emptyList(),
) {
    val affectedFiles: List<String>
        get() = fileChanges.map(AIFileChange::path)

    val totalAdditions: Int
        get() = fileChanges.sumOf(AIFileChange::additions)

    val totalDeletions: Int
        get() = fileChanges.sumOf(AIFileChange::deletions)
}
