package app.remodex.android.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadRunBadgeState
import app.remodex.android.core.model.CodexThreadSyncState

@Composable
internal fun RemodexSidebarThreadRow(
    thread: CodexThread,
    isSelected: Boolean,
    runBadgeState: CodexThreadRunBadgeState?,
    timingLabel: String?,
    onClick: () -> Unit,
) {
    val supportingText = remember(thread.id, thread.preview, thread.cwd, thread.displayTitle) {
        val trimmedPreview = thread.preview?.trim().orEmpty()
        when {
            trimmedPreview.isNotEmpty() && !trimmedPreview.equals(thread.displayTitle, ignoreCase = true) ->
                trimmedPreview

            !thread.cwd.isNullOrBlank() -> thread.cwd
            else -> null
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isSelected) Color(0xFFEAE5DC) else Color.Transparent,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (runBadgeState != null) {
                    RemodexSidebarThreadRunBadge(state = runBadgeState)
                }

                Text(
                    text = thread.displayTitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                timingLabel?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (supportingText != null || thread.syncState == CodexThreadSyncState.ArchivedLocal) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    supportingText?.let { text ->
                        Text(
                            text = text,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } ?: Spacer(modifier = Modifier.weight(1f))

                    if (thread.syncState == CodexThreadSyncState.ArchivedLocal) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFFF8E7D2),
                        ) {
                            Text(
                                text = "Archived",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9B6217),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemodexSidebarThreadRunBadge(state: CodexThreadRunBadgeState) {
    val color = when (state) {
        CodexThreadRunBadgeState.Running -> Color(0xFF2D6FD2)
        CodexThreadRunBadgeState.Ready -> Color(0xFF2F8C4C)
        CodexThreadRunBadgeState.Failed -> Color(0xFFC65446)
    }

    Surface(
        modifier = Modifier.size(10.dp),
        shape = CircleShape,
        color = color,
    ) {}
}
