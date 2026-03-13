package app.remodex.android

import app.remodex.android.core.protocol.JsonValue
import app.remodex.android.core.protocol.arrayValue
import app.remodex.android.core.protocol.doubleValue
import app.remodex.android.core.protocol.intValue
import app.remodex.android.core.protocol.objectValue
import app.remodex.android.core.protocol.stringValue
import kotlinx.serialization.json.JsonObject

object RemodexFileChangeBodyFormatter {
    fun looksLikePatchText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return false
        }
        return trimmed.contains("diff --git ") ||
            trimmed.contains("\n@@ ") ||
            trimmed.startsWith("@@ ") ||
            (trimmed.contains("\n+++ ") && trimmed.contains("\n--- ")) ||
            (trimmed.contains("\nPath: ") && trimmed.contains("\nKind: "))
    }

    fun decodeFileChangeItemBody(itemObject: JsonObject): String {
        val status = itemObject["status"]?.stringValue?.trim().orEmpty().ifBlank { "inProgress" }
        val sections = mutableListOf("Status: $status")

        val changes = decodeFileChangeEntries(itemObject["changes"])
        val renderedChanges = changes.map { entry ->
            buildString {
                append("Path: ${entry.path}")
                append("\nKind: ${entry.kind}")
                entry.inlineTotals?.let { totals ->
                    append("\nTotals: +${totals.first} -${totals.second}")
                }
                if (entry.diff.isNotEmpty()) {
                    append("\n\n```diff\n")
                    append(entry.diff)
                    append("\n```")
                }
            }
        }

        if (renderedChanges.isNotEmpty()) {
            sections += renderedChanges.joinToString(separator = "\n\n---\n\n")
        }

        return sections.joinToString(separator = "\n\n")
    }

    fun renderUnifiedDiffBody(diff: String, status: String): String {
        val perFileDiffs = splitUnifiedDiffByFile(diff)
        if (perFileDiffs.isEmpty()) {
            return "Status: $status\n\n```diff\n${diff.trim()}\n```"
        }

        val renderedChanges = perFileDiffs.map { change ->
            val normalizedPath = normalizeDiffPath(change.path)
            "Path: $normalizedPath\nKind: update\n\n```diff\n${change.diff}\n```"
        }

        return "Status: $status\n\n" + renderedChanges.joinToString(separator = "\n\n---\n\n")
    }

    private fun decodeFileChangeEntries(
        rawChanges: JsonValue?,
    ): List<FileChangeEntry> {
        val changeObjects = mutableListOf<JsonObject>()

        rawChanges?.arrayValue?.forEach { value ->
            value.objectValue?.let(changeObjects::add)
        }
        rawChanges?.objectValue?.keys?.sorted()?.forEach { key ->
            val original = rawChanges.objectValue?.get(key)?.objectValue ?: return@forEach
            val patched = if (original["path"] == null) {
                JsonObject(original + ("path" to kotlinx.serialization.json.JsonPrimitive(key)))
            } else {
                original
            }
            changeObjects += patched
        }

        return changeObjects.map { changeObject ->
            val path = decodeChangePath(changeObject)
            val kind = decodeChangeKind(changeObject)
            var diff = decodeChangeDiff(changeObject)
            val totals = decodeChangeInlineTotals(changeObject)
            if (diff.isEmpty()) {
                val content = changeObject["content"]?.stringValue?.trim().orEmpty()
                if (content.isNotEmpty()) {
                    diff = synthesizeUnifiedDiffFromContent(content, kind, path)
                }
            }
            FileChangeEntry(
                path = path,
                kind = kind,
                diff = diff,
                inlineTotals = totals,
            )
        }
    }

    private fun decodeChangePath(changeObject: JsonObject): String {
        val candidates = listOf(
            changeObject["path"]?.stringValue,
            changeObject["file"]?.stringValue,
            changeObject["file_path"]?.stringValue,
            changeObject["filePath"]?.stringValue,
            changeObject["relative_path"]?.stringValue,
            changeObject["relativePath"]?.stringValue,
            changeObject["new_path"]?.stringValue,
            changeObject["newPath"]?.stringValue,
            changeObject["to"]?.stringValue,
            changeObject["target"]?.stringValue,
            changeObject["name"]?.stringValue,
            changeObject["old_path"]?.stringValue,
            changeObject["oldPath"]?.stringValue,
            changeObject["from"]?.stringValue,
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }?.trim() ?: "unknown"
    }

    private fun decodeChangeKind(changeObject: JsonObject): String {
        val directKind = changeObject["kind"]?.stringValue?.trim().orEmpty()
        if (directKind.isNotEmpty()) {
            return directKind
        }
        val action = changeObject["action"]?.stringValue?.trim().orEmpty()
        if (action.isNotEmpty()) {
            return action
        }
        val kindType = changeObject["kind"]?.objectValue?.get("type")?.stringValue?.trim().orEmpty()
        if (kindType.isNotEmpty()) {
            return kindType
        }
        val type = changeObject["type"]?.stringValue?.trim().orEmpty()
        return type.ifEmpty { "update" }
    }

    private fun decodeChangeDiff(changeObject: JsonObject): String {
        return listOf(
            changeObject["diff"]?.stringValue,
            changeObject["unified_diff"]?.stringValue,
            changeObject["unifiedDiff"]?.stringValue,
            changeObject["patch"]?.stringValue,
            changeObject["delta"]?.stringValue,
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun decodeChangeInlineTotals(changeObject: JsonObject): Pair<Int, Int>? {
        val additions = decodeNumericField(
            changeObject,
            listOf(
                "additions",
                "lines_added",
                "line_additions",
                "lineAdditions",
                "added",
                "insertions",
                "inserted",
                "num_added",
            ),
        ) ?: 0
        val deletions = decodeNumericField(
            changeObject,
            listOf(
                "deletions",
                "lines_deleted",
                "line_deletions",
                "lineDeletions",
                "removed",
                "deleted",
                "num_deleted",
                "num_removed",
            ),
        ) ?: 0

        return if (additions > 0 || deletions > 0) additions to deletions else null
    }

    private fun decodeNumericField(
        objectValue: JsonObject,
        keys: List<String>,
    ): Int? {
        for (key in keys) {
            val value = objectValue[key] ?: continue
            value.intValue?.let { return it }
            value.doubleValue?.let { return it.toInt() }
            value.stringValue?.trim()?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun synthesizeUnifiedDiffFromContent(
        content: String,
        kind: String,
        path: String,
    ): String {
        val normalizedKind = kind.lowercase()
        val contentLines = content.split('\n')

        if (normalizedKind.contains("add") || normalizedKind.contains("create")) {
            return buildList {
                add("diff --git a/$path b/$path")
                add("new file mode 100644")
                add("--- /dev/null")
                add("+++ b/$path")
                addAll(contentLines.map { "+$it" })
            }.joinToString(separator = "\n")
        }

        if (normalizedKind.contains("delete") || normalizedKind.contains("remove")) {
            return buildList {
                add("diff --git a/$path b/$path")
                add("deleted file mode 100644")
                add("--- a/$path")
                add("+++ /dev/null")
                addAll(contentLines.map { "-$it" })
            }.joinToString(separator = "\n")
        }

        return ""
    }

    private fun splitUnifiedDiffByFile(diff: String): List<UnifiedDiffChunk> {
        val lines = diff.split('\n')
        if (lines.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<UnifiedDiffChunk>()
        var currentLines = mutableListOf<String>()
        var currentPath: String? = null

        fun flushChunk() {
            if (currentLines.isEmpty()) {
                return
            }
            val fallbackPath = currentPath ?: parsePathFromDiffLines(currentLines) ?: "unknown"
            val chunkText = currentLines.joinToString(separator = "\n").trim()
            if (chunkText.isNotEmpty()) {
                chunks += UnifiedDiffChunk(path = fallbackPath, diff = chunkText)
            }
            currentLines = mutableListOf()
        }

        for (line in lines) {
            if (line.startsWith("diff --git ") && currentLines.isNotEmpty()) {
                flushChunk()
                currentPath = null
            }
            if (currentPath == null) {
                parsePathFromDiffLine(line)?.let { currentPath = it }
            }
            currentLines += line
        }

        flushChunk()
        return chunks
    }

    private fun parsePathFromDiffLines(lines: List<String>): String? {
        return lines.firstNotNullOfOrNull(::parsePathFromDiffLine)
    }

    private fun parsePathFromDiffLine(line: String): String? {
        if (line.startsWith("+++ ")) {
            val normalized = normalizeDiffPath(line.drop(4))
            return normalized.takeIf { it != "unknown" }
        }
        if (line.startsWith("diff --git ")) {
            val components = line.split(' ').filter(String::isNotBlank)
            if (components.size >= 4) {
                val normalized = normalizeDiffPath(components[3])
                return normalized.takeIf { it != "unknown" }
            }
        }
        return null
    }

    private fun normalizeDiffPath(rawValue: String): String {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty() || trimmed == "/dev/null") {
            return "unknown"
        }
        if (trimmed.startsWith("a/") || trimmed.startsWith("b/")) {
            return trimmed.drop(2)
        }
        return trimmed
    }

    private data class FileChangeEntry(
        val path: String,
        val kind: String,
        val diff: String,
        val inlineTotals: Pair<Int, Int>?,
    )

    private data class UnifiedDiffChunk(
        val path: String,
        val diff: String,
    )
}
