package app.remodex.android.sidebar

import app.remodex.android.core.model.CodexThread
import java.time.Duration
import java.time.Instant
import java.util.Locale

data class RemodexSidebarProjectChoice(
    val path: String,
    val label: String,
)

data class RemodexSidebarThreadGroup(
    val id: String,
    val label: String,
    val projectPath: String?,
    val threads: List<CodexThread>,
)

internal fun buildSidebarProjectChoices(threads: List<CodexThread>): List<RemodexSidebarProjectChoice> {
    return threads
        .mapNotNull { thread ->
            thread.normalizedProjectPath?.let { path ->
                RemodexSidebarProjectChoice(
                    path = path,
                    label = thread.projectDisplayName,
                )
            }
        }
        .distinctBy(RemodexSidebarProjectChoice::path)
        .sortedBy { it.label.lowercase(Locale.ROOT) }
}

internal fun buildSidebarThreadGroups(
    threads: List<CodexThread>,
    searchText: String,
): List<RemodexSidebarThreadGroup> {
    val normalizedQuery = searchText.trim().lowercase(Locale.ROOT)
    val filteredThreads = if (normalizedQuery.isEmpty()) {
        threads
    } else {
        threads.filter { thread -> thread.matchesSidebarQuery(normalizedQuery) }
    }

    return filteredThreads
        .groupBy(CodexThread::projectKey)
        .mapNotNull { (projectKey, groupedThreads) ->
            val firstThread = groupedThreads.firstOrNull() ?: return@mapNotNull null
            RemodexSidebarThreadGroup(
                id = projectKey,
                label = firstThread.projectDisplayName,
                projectPath = firstThread.normalizedProjectPath,
                threads = groupedThreads.sortedByRecentActivity(),
            )
        }
        .sortedWith(
            compareBy<RemodexSidebarThreadGroup> { it.label.equals("No Project", ignoreCase = true) }
                .thenBy { it.label.lowercase(Locale.ROOT) }
        )
}

internal fun buildSidebarRelativeTimeLabel(
    thread: CodexThread,
    now: Instant = Instant.now(),
): String? {
    val timestamp = thread.updatedAt ?: thread.createdAt ?: return null
    val delta = Duration.between(timestamp, now)
    if (delta.isNegative) {
        return "Now"
    }

    val minutes = delta.toMinutes()
    val hours = delta.toHours()
    val days = delta.toDays()

    return when {
        minutes < 1 -> "Now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        days < 30 -> "${days / 7}w"
        else -> "${days / 30}mo"
    }
}

private fun List<CodexThread>.sortedByRecentActivity(): List<CodexThread> {
    return sortedWith(
        compareByDescending<CodexThread> { it.updatedAt ?: it.createdAt ?: Instant.EPOCH }
            .thenByDescending(CodexThread::displayTitle)
    )
}

private fun CodexThread.matchesSidebarQuery(query: String): Boolean {
    val searchableFields = listOf(
        displayTitle,
        preview,
        projectDisplayName,
        cwd,
    )

    return searchableFields.any { value ->
        value?.lowercase(Locale.ROOT)?.contains(query) == true
    }
}
