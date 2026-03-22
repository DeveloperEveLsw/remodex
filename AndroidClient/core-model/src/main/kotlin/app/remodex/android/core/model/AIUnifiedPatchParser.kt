package app.remodex.android.core.model

import java.security.MessageDigest

object AIUnifiedPatchParser {
    fun analyze(rawPatch: String): AIUnifiedPatchAnalysis {
        val patch = rawPatch.trim()
        if (patch.isEmpty()) {
            return AIUnifiedPatchAnalysis(
                fileChanges = emptyList(),
                unsupportedReasons = listOf("No exact patch was captured."),
            )
        }

        val chunks = splitIntoChunks(patch)
        if (chunks.isEmpty()) {
            return AIUnifiedPatchAnalysis(
                fileChanges = emptyList(),
                unsupportedReasons = listOf("No exact patch was captured."),
            )
        }

        val fileChanges = mutableListOf<AIFileChange>()
        val unsupportedReasons = linkedSetOf<String>()

        for (chunk in chunks) {
            val analysis = analyzeChunk(chunk)
            analysis.fileChange?.let(fileChanges::add)
            unsupportedReasons += analysis.unsupportedReasons
        }

        if (fileChanges.isEmpty()) {
            unsupportedReasons += "This response cannot be auto-reverted because no exact patch was captured."
        }

        return AIUnifiedPatchAnalysis(
            fileChanges = fileChanges,
            unsupportedReasons = unsupportedReasons.toList().sorted(),
        )
    }

    fun hash(rawPatch: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawPatch.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    private fun splitIntoChunks(patch: String): List<List<String>> {
        val lines = patch.split('\n')
        if (lines.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()

        fun flushCurrent() {
            if (current.isEmpty()) {
                return
            }
            chunks += current
            current = mutableListOf()
        }

        for (line in lines) {
            if (line.startsWith("diff --git ") && current.isNotEmpty()) {
                flushCurrent()
            }
            current += line
        }

        flushCurrent()
        return chunks
    }

    private data class ChunkAnalysis(
        val fileChange: AIFileChange?,
        val unsupportedReasons: Set<String>,
    )

    private fun analyzeChunk(lines: List<String>): ChunkAnalysis {
        if (lines.isEmpty()) {
            return ChunkAnalysis(fileChange = null, unsupportedReasons = emptySet())
        }

        val path = extractPath(lines)
        val isBinary = lines.any { it.startsWith("Binary files ") || it == "GIT binary patch" }
        val isRenameOrModeOnly = lines.any {
            it.startsWith("rename from ") ||
                it.startsWith("rename to ") ||
                it.startsWith("copy from ") ||
                it.startsWith("copy to ") ||
                it.startsWith("old mode ") ||
                it.startsWith("new mode ") ||
                it.startsWith("new file mode 120") ||
                it.startsWith("deleted file mode 120") ||
                it.startsWith("similarity index ")
        }

        val isCreate = lines.contains("new file mode 100644") ||
            lines.contains("new file mode 100755") ||
            lines.contains("--- /dev/null")
        val isDelete = lines.contains("deleted file mode 100644") ||
            lines.contains("deleted file mode 100755") ||
            lines.contains("+++ /dev/null")

        var additions = 0
        var deletions = 0
        for (line in lines) {
            when {
                line.startsWith("+") && !line.startsWith("+++") -> additions += 1
                line.startsWith("-") && !line.startsWith("---") -> deletions += 1
            }
        }

        val unsupportedReasons = linkedSetOf<String>()
        if (isBinary) {
            unsupportedReasons += "Binary changes are not auto-revertable in v1."
        }
        if (isRenameOrModeOnly) {
            unsupportedReasons += "Rename, mode-only, or symlink changes are not auto-revertable in v1."
        }

        val kind = when {
            isCreate -> AIFileChangeKind.Create
            isDelete -> AIFileChangeKind.Delete
            else -> AIFileChangeKind.Update
        }
        val hasPatchBody = additions > 0 || deletions > 0 || isCreate || isDelete
        if (path.isBlank() || !hasPatchBody) {
            if (!isBinary && !isRenameOrModeOnly) {
                unsupportedReasons += "This response cannot be auto-reverted because no exact patch was captured."
            }
            return ChunkAnalysis(fileChange = null, unsupportedReasons = unsupportedReasons)
        }

        return ChunkAnalysis(
            fileChange = AIFileChange(
                path = path,
                kind = kind,
                additions = additions,
                deletions = deletions,
                isBinary = isBinary,
                isRenameOrModeOnly = isRenameOrModeOnly,
                beforeContentHash = null,
                afterContentHash = null,
            ),
            unsupportedReasons = unsupportedReasons,
        )
    }

    private fun extractPath(lines: List<String>): String {
        lines.firstOrNull { it.startsWith("+++ ") }
            ?.removePrefix("+++ ")
            ?.trim()
            ?.let(::normalizeDiffPath)
            ?.takeIf { it.isNotEmpty() && it != "/dev/null" }
            ?.let { return it }

        lines.firstOrNull { it.startsWith("diff --git ") }
            ?.split(' ')
            ?.takeIf { it.size >= 4 }
            ?.get(3)
            ?.let(::normalizeDiffPath)
            ?.takeIf(String::isNotEmpty)
            ?.let { return it }

        return ""
    }

    private fun normalizeDiffPath(rawPath: String): String {
        var value = rawPath.trim()
        if (value.startsWith("a/") || value.startsWith("b/")) {
            value = value.drop(2)
        }
        return value
    }
}
