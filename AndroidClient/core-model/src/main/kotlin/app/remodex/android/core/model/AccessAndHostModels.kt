package app.remodex.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CodexAccessMode {
    @SerialName("on-request")
    OnRequest,

    @SerialName("full-access")
    FullAccess,
    ;

    val displayName: String
        get() = when (this) {
            OnRequest -> "On-Request"
            FullAccess -> "Full access"
        }

    val approvalPolicyCandidates: List<String>
        get() = when (this) {
            OnRequest -> listOf("on-request", "onRequest")
            FullAccess -> listOf("never")
        }

    val sandboxLegacyValue: String
        get() = when (this) {
            OnRequest -> "workspaceWrite"
            FullAccess -> "dangerFullAccess"
        }
}

@Serializable
data class CodexHostCapabilities(
    val desktopRefreshAvailable: Boolean = false,
    val desktopRefreshEnabled: Boolean = false,
    val desktopAppRoutingAvailable: Boolean = false,
)

@Serializable
data class CodexHostInfo(
    val platform: String,
    val displayName: String,
    val capabilities: CodexHostCapabilities = CodexHostCapabilities(),
)
