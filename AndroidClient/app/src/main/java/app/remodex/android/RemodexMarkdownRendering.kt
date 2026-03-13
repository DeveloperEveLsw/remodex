package app.remodex.android

sealed interface MarkdownSegment {
    data class Prose(val text: String) : MarkdownSegment
    data class CodeBlock(val language: String?, val code: String) : MarkdownSegment
}

enum class MarkdownRenderProfile {
    AssistantProse,
    FileChangeSystem,
}

object RemodexMarkdownRenderer {
    private val codeFenceRegex = Regex("""```([^\n`]*)\n([\s\S]*?)(?:\n```|$)""")

    fun parseMarkdownSegments(text: String): List<MarkdownSegment> {
        val matches = codeFenceRegex.findAll(text).toList()
        if (matches.isEmpty()) {
            return listOf(MarkdownSegment.Prose(text))
        }

        val segments = mutableListOf<MarkdownSegment>()
        var lastEnd = 0

        for (match in matches) {
            val prose = text.substring(lastEnd, match.range.first).trim('\n')
            if (prose.isNotEmpty()) {
                segments += MarkdownSegment.Prose(prose)
            }

            val language = match.groupValues[1].trim().ifEmpty { null }
            val code = match.groupValues[2]
            segments += MarkdownSegment.CodeBlock(language = language, code = code)
            lastEnd = match.range.last + 1
        }

        if (lastEnd < text.length) {
            val trailing = text.substring(lastEnd).trim('\n')
            if (trailing.isNotEmpty()) {
                segments += MarkdownSegment.Prose(trailing)
            }
        }

        return if (segments.isEmpty()) {
            listOf(MarkdownSegment.Prose(text))
        } else {
            segments
        }
    }
}

enum class RemodexDiffLineKind {
    Addition,
    Deletion,
    Hunk,
    Meta,
    Neutral,
    ;

    companion object {
        fun detectVerifiedPatch(code: String): Boolean {
            val lines = code.split('\n')
            if (lines.isEmpty()) {
                return false
            }

            var hasHunk = false
            var hasGitHeader = false
            var hasBodyChange = false
            var metadataEvidenceCount = 0

            for (line in lines) {
                if (line.startsWith("@@")) {
                    hasHunk = true
                    continue
                }

                if (
                    line.startsWith("diff --git ") ||
                    line.startsWith("--- ") ||
                    line.startsWith("+++ ") ||
                    line.startsWith("index ") ||
                    line.startsWith("new file mode") ||
                    line.startsWith("deleted file mode") ||
                    line.startsWith("old mode ") ||
                    line.startsWith("new mode ") ||
                    line.startsWith("rename from ") ||
                    line.startsWith("rename to ") ||
                    line.startsWith("similarity index ") ||
                    line.startsWith("dissimilarity index ")
                ) {
                    hasGitHeader = true
                    metadataEvidenceCount += 1
                    continue
                }

                if (line.startsWith("+") && !line.startsWith("+++")) {
                    hasBodyChange = true
                    continue
                }

                if (line.startsWith("-") && !line.startsWith("---")) {
                    hasBodyChange = true
                    continue
                }
            }

            if (hasBodyChange) {
                return hasHunk || hasGitHeader
            }

            if (hasHunk) {
                return true
            }

            return hasGitHeader && metadataEvidenceCount >= 2
        }

        fun classify(line: String): RemodexDiffLineKind {
            return when {
                line.startsWith("@@") -> Hunk
                line.startsWith("diff ") ||
                    line.startsWith("index ") ||
                    line.startsWith("---") ||
                    line.startsWith("+++") -> Meta
                line.startsWith("+") && !line.startsWith("+++") -> Addition
                line.startsWith("-") && !line.startsWith("---") -> Deletion
                else -> Neutral
            }
        }
    }
}
