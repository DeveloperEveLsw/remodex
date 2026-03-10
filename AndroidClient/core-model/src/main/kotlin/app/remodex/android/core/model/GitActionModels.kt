@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.remodex.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class GitDiffTotals(
    val additions: Int = 0,
    val deletions: Int = 0,
    val binaryFiles: Int = 0,
) {
    val hasChanges: Boolean
        get() = additions > 0 || deletions > 0 || binaryFiles > 0
}

@Serializable
data class GitRepoSyncResult(
    val repoRoot: String? = null,
    @JsonNames("currentBranch", "branch")
    val currentBranch: String? = null,
    @JsonNames("trackingBranch", "tracking")
    val trackingBranch: String? = null,
    @JsonNames("dirty", "isDirty")
    val isDirty: Boolean = false,
    @JsonNames("ahead", "aheadCount")
    val aheadCount: Int = 0,
    @JsonNames("behind", "behindCount")
    val behindCount: Int = 0,
    val state: String = "up_to_date",
    val canPush: Boolean = false,
    @JsonNames("repoDiffTotals", "diff")
    val repoDiffTotals: GitDiffTotals? = null,
)

@Serializable
data class GitCommitResult(
    @JsonNames("commitHash", "hash")
    val commitHash: String = "",
    val branch: String = "",
    val summary: String = "",
)

@Serializable
data class GitPushResult(
    val branch: String = "",
    val remote: String? = null,
    val status: GitRepoSyncResult? = null,
)

@Serializable
data class GitBranchesResult(
    val branches: List<String> = emptyList(),
    @JsonNames("currentBranch", "current")
    val currentBranch: String? = null,
    @JsonNames("defaultBranch", "default")
    val defaultBranch: String? = null,
)

@Serializable
data class GitCheckoutResult(
    @JsonNames("currentBranch", "current")
    val currentBranch: String = "",
    val tracking: String? = null,
    val status: GitRepoSyncResult? = null,
)

@Serializable
data class GitPullResult(
    val success: Boolean = false,
    val status: GitRepoSyncResult? = null,
)

@Serializable
data class GitResetResult(
    val success: Boolean = false,
    val status: GitRepoSyncResult? = null,
)

@Serializable
data class GitRemoteUrlResult(
    val url: String = "",
    val ownerRepo: String? = null,
)

@Serializable
data class GitBranchesWithStatusResult(
    val branches: List<String> = emptyList(),
    @JsonNames("currentBranch", "current")
    val currentBranch: String? = null,
    @JsonNames("defaultBranch", "default")
    val defaultBranch: String? = null,
    val status: GitRepoSyncResult? = null,
)

@Serializable
enum class TurnGitActionKind {
    @SerialName("syncNow")
    SyncNow,

    @SerialName("commit")
    Commit,

    @SerialName("push")
    Push,

    @SerialName("commitAndPush")
    CommitAndPush,

    @SerialName("createPR")
    CreatePR,

    @SerialName("discardRuntimeChangesAndSync")
    DiscardRuntimeChangesAndSync,
    ;

    val title: String
        get() = when (this) {
            SyncNow -> "Update"
            Commit -> "Commit"
            Push -> "Push"
            CommitAndPush -> "Commit & Push"
            CreatePR -> "Create PR"
            DiscardRuntimeChangesAndSync -> "Discard Local Changes"
        }
}

@Serializable
data class TurnGitSyncAlert(
    val id: String,
    val title: String,
    val message: String,
    val action: TurnGitSyncAlertAction,
)

@Serializable
enum class TurnGitSyncAlertAction {
    @SerialName("dismissOnly")
    DismissOnly,

    @SerialName("pullRebase")
    PullRebase,
}
