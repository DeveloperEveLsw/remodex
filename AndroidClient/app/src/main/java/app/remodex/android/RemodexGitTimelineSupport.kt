package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageRole

object RemodexGitTimelineSupport {
    const val PushResetItemId = "git.push.reset.marker"

    fun pushResetText(branch: String, remote: String?): String {
        val normalizedBranch = branch.trim()
        val normalizedRemote = remote?.trim().orEmpty()

        return when {
            normalizedRemote.isNotEmpty() && normalizedBranch.isNotEmpty() ->
                "Push completed on $normalizedRemote/$normalizedBranch."
            normalizedBranch.isNotEmpty() ->
                "Push completed on $normalizedBranch."
            else ->
                "Push completed on remote."
        }
    }

    fun isHiddenTimelineMessage(message: CodexMessage): Boolean {
        return message.role == CodexMessageRole.System && message.itemId == PushResetItemId
    }
}
