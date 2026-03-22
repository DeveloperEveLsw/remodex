package app.remodex.android.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.remodex.android.core.model.CodexThreadRunBadgeState

@Composable
internal fun RemodexSidebarThreadList(
    groups: List<RemodexSidebarThreadGroup>,
    isFiltering: Boolean,
    isConnected: Boolean,
    activeThreadId: String?,
    runBadgeStateForThread: (String) -> CodexThreadRunBadgeState?,
    onSelectThread: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (groups.isEmpty()) {
            item(key = "empty-state") {
                Text(
                    text = if (isFiltering) {
                        "No matching conversations"
                    } else if (isConnected) {
                        "No conversations yet"
                    } else {
                        "Connect to view conversations"
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            groups.forEach { group ->
                item(key = "header-${group.id}") {
                    Text(
                        text = group.label,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items(
                    items = group.threads,
                    key = { it.id },
                ) { thread ->
                    RemodexSidebarThreadRow(
                        thread = thread,
                        isSelected = thread.id == activeThreadId,
                        runBadgeState = if (thread.id == activeThreadId) {
                            null
                        } else {
                            runBadgeStateForThread(thread.id)
                        },
                        timingLabel = buildSidebarRelativeTimeLabel(thread),
                        onClick = { onSelectThread(thread.id) },
                    )
                }
            }
        }
    }
}
