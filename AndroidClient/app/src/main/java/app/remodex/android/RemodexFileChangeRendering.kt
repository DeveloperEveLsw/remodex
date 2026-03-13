package app.remodex.android

import java.net.URI

data class RemodexDiffLineTotals(
    var additions: Int = 0,
    var deletions: Int = 0,
)

enum class RemodexFileChangeAction(val label: String) {
    Edited("Edited"),
    Added("Added"),
    Deleted("Deleted"),
    Renamed("Renamed"),
    ;

    companion object {
        fun fromInlineVerb(verb: String): RemodexFileChangeAction? {
            return when (verb.trim().lowercase()) {
                "edited", "updated" -> Edited
                "added", "created" -> Added
                "deleted", "removed" -> Deleted
                "renamed", "moved" -> Renamed
                else -> null
            }
        }

        fun fromKind(kind: String): RemodexFileChangeAction? {
            return when (kind.trim().lowercase()) {
                "add", "added", "create", "created" -> Added
                "delete", "deleted", "remove", "removed" -> Deleted
                "rename", "renamed", "move", "moved" -> Renamed
                "update", "updated", "edit", "edited" -> Edited
                else -> null
            }
        }
    }
}

data class RemodexFileChangeSummaryEntry(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val action: RemodexFileChangeAction?,
) {
    val compactPath: String
        get() = path.substringAfterLast('/')

    val fullDirectoryPath: String?
        get() = path.substringBeforeLast('/', "").ifBlank { null }
}

data class RemodexFileChangeSummary(
    val entries: List<RemodexFileChangeSummaryEntry>,
)

data class RemodexFileChangeRenderState(
    val summary: RemodexFileChangeSummary?,
    val actionEntries: List<RemodexFileChangeSummaryEntry>,
    val bodyText: String,
) {
    companion object {
        fun fromSourceText(sourceText: String): RemodexFileChangeRenderState {
            val summary = RemodexFileChangeSummaryParser.parse(sourceText)
            val actionEntries = summary?.entries?.filter { it.action != null }.orEmpty()
            val bodyText = if (actionEntries.isEmpty()) {
                sourceText
            } else {
                RemodexFileChangeSummaryParser.removingInlineEditingRows(sourceText)
            }
            return RemodexFileChangeRenderState(
                summary = summary,
                actionEntries = actionEntries,
                bodyText = bodyText,
            )
        }
    }
}

data class RemodexFileChangeGroup(
    val key: String,
    val entries: List<RemodexFileChangeSummaryEntry>,
)

data class RemodexPerFileDiffChunk(
    val id: String,
    val path: String,
    val action: RemodexFileChangeAction,
    val additions: Int,
    val deletions: Int,
    val diffCode: String,
) {
    val compactPath: String
        get() = path.substringAfterLast('/')

    val fullDirectoryPath: String?
        get() = path.substringBeforeLast('/', "").ifBlank { null }
}

object RemodexFileChangeGrouping {
    fun grouped(entries: List<RemodexFileChangeSummaryEntry>): List<RemodexFileChangeGroup> {
        val order = mutableListOf<String>()
        val grouped = linkedMapOf<String, MutableList<RemodexFileChangeSummaryEntry>>()
        for (entry in entries) {
            val key = entry.action?.label ?: "Edited"
            if (grouped[key] == null) {
                order += key
                grouped[key] = mutableListOf()
            }
            grouped.getValue(key) += entry
        }
        return order.map { key ->
            RemodexFileChangeGroup(key = key, entries = grouped.getValue(key))
        }
    }
}

object RemodexFileChangeSummaryParser {
    private val inlineActionRegex = Regex("""(?i)^(edited|updated|added|created|deleted|removed|renamed|moved)\s+(.+?)$""")
    private val inlineTotalsRegex = Regex("""[+\uFF0B]\s*(\d+)\s*[-\u2212\u2013\u2014\uFE63\uFF0D]\s*(\d+)""")
    private val trailingInlineTotalsRegex = Regex("""\s*[+\uFF0B]\s*\d+\s*[-\u2212\u2013\u2014\uFE63\uFF0D]\s*\d+\s*$""")
    private val trailingLineColumnRegex = Regex(""":\d+(?::\d+)?$""")
    private val fileLikeTokenRegex = Regex("""[A-Za-z0-9_+.-]+\.[A-Za-z0-9]+$""")
    private val markdownLinkTokenRegex = Regex("""^\[([^\]]+)\]\(([^)]+)\)$""")
    private val inlineEditingRowRegex = Regex("""(?i)^(edited|updated|added|created|deleted|removed|renamed|moved)\s+.+\s+[+\uFF0B]\s*\d+\s*[-\u2212\u2013\u2014\uFE63\uFF0D]\s*\d+\s*$""")
    private val collapsibleNewlinesRegex = Regex("""\n{3,}""")

    fun parse(text: String): RemodexFileChangeSummary? {
        val lines = text.split('\n')
        var lineIndex = 0
        var currentPath: String? = null
        val orderedPaths = mutableListOf<String>()
        val totalsByPath = linkedMapOf<String, RemodexDiffLineTotals>()
        val kindsByPath = mutableMapOf<String, String>()
        val actionsByPath = mutableMapOf<String, RemodexFileChangeAction>()
        val pathsWithInlineTotals = mutableSetOf<String>()
        val pathsWithNonZeroInlineTotals = mutableSetOf<String>()
        val pathsWithDiffBodyEvidence = mutableSetOf<String>()
        var sawPathLine = false
        var sawKindLine = false
        var sawDiffFence = false
        var sawInlineTotals = false
        var sawInlineAction = false

        fun ensurePath(path: String) {
            if (totalsByPath[path] == null) {
                totalsByPath[path] = RemodexDiffLineTotals()
                orderedPaths += path
            }
        }

        while (lineIndex < lines.size) {
            val rawLine = lines[lineIndex]
            val trimmedLine = rawLine.trim()

            val parsedPath = parsePathLine(trimmedLine)
            if (parsedPath != null) {
                sawPathLine = true
                currentPath = parsedPath
                ensurePath(parsedPath)
                lineIndex += 1
                continue
            }

            val parsedKind = parseKindLine(trimmedLine)
            if (parsedKind != null) {
                val path = currentPath
                if (path != null) {
                    sawKindLine = true
                    kindsByPath[path] = parsedKind
                    if (actionsByPath[path] == null) {
                        RemodexFileChangeAction.fromKind(parsedKind)?.let { action ->
                            actionsByPath[path] = action
                        }
                    }
                }
                lineIndex += 1
                continue
            }

            val parsedTotals = parseTotalsLine(trimmedLine)
            if (parsedTotals != null) {
                val path = currentPath
                if (path != null) {
                    if (parsedTotals.additions > 0 || parsedTotals.deletions > 0) {
                        sawInlineTotals = true
                        pathsWithNonZeroInlineTotals += path
                    }
                    ensurePath(path)
                    totalsByPath.getValue(path).additions += parsedTotals.additions
                    totalsByPath.getValue(path).deletions += parsedTotals.deletions
                    pathsWithInlineTotals += path
                }
                lineIndex += 1
                continue
            }

            val inlineEntry = parseInlineFileEntry(trimmedLine)
            if (inlineEntry != null) {
                currentPath = inlineEntry.path
                ensurePath(inlineEntry.path)
                inlineEntry.inlineTotals?.let { totals ->
                    if (totals.additions > 0 || totals.deletions > 0) {
                        sawInlineTotals = true
                        pathsWithNonZeroInlineTotals += inlineEntry.path
                    }
                    totalsByPath.getValue(inlineEntry.path).additions += totals.additions
                    totalsByPath.getValue(inlineEntry.path).deletions += totals.deletions
                    pathsWithInlineTotals += inlineEntry.path
                }
                inlineEntry.action?.let { action ->
                    sawInlineAction = true
                    actionsByPath[inlineEntry.path] = action
                }
                lineIndex += 1
                continue
            }

            if (trimmedLine.startsWith("```")) {
                lineIndex += 1
                val codeLines = mutableListOf<String>()
                while (lineIndex < lines.size) {
                    val candidate = lines[lineIndex]
                    if (candidate.trim() == "```") {
                        break
                    }
                    codeLines += candidate
                    lineIndex += 1
                }

                val code = codeLines.joinToString(separator = "\n")
                if (RemodexDiffLineKind.detectVerifiedPatch(code)) {
                    sawDiffFence = true
                    val resolvedPath = currentPath ?: parsePathFromDiff(codeLines)
                    if (!resolvedPath.isNullOrEmpty()) {
                        ensurePath(resolvedPath)
                        if (!pathsWithInlineTotals.contains(resolvedPath)) {
                            val delta = countDiffLines(codeLines)
                            totalsByPath.getValue(resolvedPath).additions += delta.additions
                            totalsByPath.getValue(resolvedPath).deletions += delta.deletions
                            if (delta.additions > 0 || delta.deletions > 0) {
                                pathsWithDiffBodyEvidence += resolvedPath
                            }
                        } else {
                            val delta = parseDiffBodyEvidence(codeLines)
                            if (delta != null && (delta.additions > 0 || delta.deletions > 0)) {
                                pathsWithDiffBodyEvidence += resolvedPath
                            }
                        }
                    }
                }

                if (lineIndex < lines.size) {
                    lineIndex += 1
                }
                continue
            }

            lineIndex += 1
        }

        val hasStrongFileChangeSignal =
            sawPathLine || sawKindLine || sawDiffFence || sawInlineTotals || sawInlineAction
        if (!hasStrongFileChangeSignal) {
            return null
        }

        val entries = orderedPaths.mapNotNull { path ->
            val totals = totalsByPath[path] ?: RemodexDiffLineTotals()
            val inferredAction = actionsByPath[path]
                ?: kindsByPath[path]?.let(RemodexFileChangeAction::fromKind)
            val hasNonZeroTotals = totals.additions > 0 || totals.deletions > 0
            val hasPatchEvidence = path in pathsWithDiffBodyEvidence || path in pathsWithNonZeroInlineTotals
            val hasActionWithEvidence = inferredAction != null && hasPatchEvidence
            if (!hasNonZeroTotals && !hasActionWithEvidence) {
                null
            } else {
                RemodexFileChangeSummaryEntry(
                    path = path,
                    additions = totals.additions,
                    deletions = totals.deletions,
                    action = inferredAction,
                )
            }
        }

        return entries.takeIf { it.isNotEmpty() }?.let(::RemodexFileChangeSummary)
    }

    fun removingInlineEditingRows(text: String): String {
        val filtered = text
            .split('\n')
            .filterNot(::isInlineEditingRow)
            .joinToString(separator = "\n")
            .replace(collapsibleNewlinesRegex, "\n\n")
            .trim()
        return filtered
    }

    private fun parsePathLine(line: String): String? {
        if (!line.lowercase().startsWith("path:")) {
            return null
        }
        val value = line.substringAfter(':').trim()
        if (value.isEmpty()) {
            return null
        }
        val normalized = normalizeInlinePath(value)
        return normalized.takeIf(::looksLikePath)
    }

    private fun parseKindLine(line: String): String? {
        if (!line.lowercase().startsWith("kind:")) {
            return null
        }
        return line.substringAfter(':').trim().ifEmpty { null }
    }

    private fun parseTotalsLine(line: String): RemodexDiffLineTotals? {
        if (!line.lowercase().startsWith("totals:")) {
            return null
        }
        return parseInlineTotals(line.substringAfter(':').trim())
    }

    private fun parseInlineFileEntry(
        line: String,
    ): ParsedInlineEntry? {
        var candidate = line
        if (candidate.startsWith("- ") || candidate.startsWith("* ") || candidate.startsWith("• ")) {
            candidate = candidate.drop(2)
        }

        candidate = candidate.replace("`", "")
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val totals = parseInlineTotals(trimmed)
        val withoutTotals = stripInlineTotals(trimmed).trim()

        parseInlineActionEntry(withoutTotals)?.let { (action, path) ->
            val normalizedPath = normalizeInlinePath(path)
            if (!looksLikePath(normalizedPath)) {
                return null
            }
            return ParsedInlineEntry(
                path = normalizedPath,
                inlineTotals = totals,
                action = action,
            )
        }

        if (totals == null) {
            return null
        }

        val firstToken = withoutTotals.substringBefore(' ', withoutTotals)
        val normalizedPath = normalizeInlinePath(firstToken)
        if (!looksLikePath(normalizedPath)) {
            return null
        }

        return ParsedInlineEntry(
            path = normalizedPath,
            inlineTotals = totals,
            action = null,
        )
    }

    private fun parseInlineActionEntry(line: String): Pair<RemodexFileChangeAction, String>? {
        val match = inlineActionRegex.matchEntire(line) ?: return null
        val action = RemodexFileChangeAction.fromInlineVerb(match.groupValues[1]) ?: return null
        val path = match.groupValues[2]
        return action to path
    }

    private fun parseInlineTotals(line: String): RemodexDiffLineTotals? {
        val match = inlineTotalsRegex.find(line) ?: return null
        val plus = match.groupValues[1].toIntOrNull() ?: return null
        val minus = match.groupValues[2].toIntOrNull() ?: return null
        return RemodexDiffLineTotals(additions = plus, deletions = minus)
    }

    private fun stripInlineTotals(line: String): String {
        return line.replace(trailingInlineTotalsRegex, "")
    }

    private fun normalizeInlinePath(rawToken: String): String {
        var token = rawToken.trim()
            .trim('`')
            .replace("\"", "")
            .replace("'", "")

        parseMarkdownLink(token)?.let { (_, destination) ->
            val normalized = normalizeLinkDestination(destination)
            token = if (looksLikePath(normalized)) normalized else token
        }

        if (' ' in token) {
            token = token.substringBefore(' ')
        }

        while (token.lastOrNull()?.let { it in ",.;)" } == true) {
            token = token.dropLast(1)
        }
        if (token.startsWith("(")) {
            token = token.drop(1)
        }

        return token.replace(trailingLineColumnRegex, "")
    }

    private fun looksLikePath(token: String): Boolean {
        if (token.isEmpty()) {
            return false
        }
        if ('/' in token || token.startsWith("./") || token.startsWith("../")) {
            return true
        }
        return fileLikeTokenRegex.matches(token)
    }

    private fun parseMarkdownLink(token: String): Pair<String, String>? {
        val match = markdownLinkTokenRegex.matchEntire(token) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun normalizeLinkDestination(destination: String): String {
        var normalized = destination.trim()
        normalized = normalized.substringBefore('?').substringBefore('#')
        return runCatching {
            val uri = URI(normalized)
            when {
                uri.scheme == "file" -> uri.path ?: normalized
                !uri.path.isNullOrEmpty() -> uri.path
                else -> normalized
            }
        }.getOrDefault(normalized)
    }

    private fun parsePathFromDiff(lines: List<String>): String? {
        lines.firstNotNullOfOrNull { line ->
            if (line.startsWith("+++ ")) {
                normalizeDiffPath(line.drop(4)).takeIf(String::isNotEmpty)
            } else {
                null
            }
        }?.let { return it }

        for (line in lines) {
            if (line.startsWith("diff --git ")) {
                val components = line.split(' ').filter(String::isNotBlank)
                if (components.size >= 4) {
                    return normalizeDiffPath(components[3]).takeIf(String::isNotEmpty)
                }
            }
        }
        return null
    }

    private fun normalizeDiffPath(rawValue: String): String {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty() || trimmed == "/dev/null") {
            return ""
        }
        return if (trimmed.startsWith("a/") || trimmed.startsWith("b/")) {
            trimmed.drop(2)
        } else {
            trimmed
        }
    }

    private fun countDiffLines(lines: List<String>): RemodexDiffLineTotals {
        val totals = RemodexDiffLineTotals()
        for (line in lines) {
            if (line.isEmpty() || isDiffMetadataLine(line)) {
                continue
            }
            if (line.startsWith("+")) {
                totals.additions += 1
            } else if (line.startsWith("-")) {
                totals.deletions += 1
            }
        }
        return totals
    }

    private fun parseDiffBodyEvidence(lines: List<String>): RemodexDiffLineTotals? {
        val totals = countDiffLines(lines)
        return totals.takeIf { it.additions > 0 || it.deletions > 0 }
    }

    private fun isDiffMetadataLine(line: String): Boolean {
        val metadataPrefixes = listOf(
            "+++",
            "---",
            "diff --git",
            "@@",
            "index ",
            "\\ No newline",
            "new file mode",
            "deleted file mode",
            "similarity index",
            "rename from",
            "rename to",
        )
        return metadataPrefixes.any(line::startsWith)
    }

    private fun isInlineEditingRow(line: String): Boolean {
        return inlineEditingRowRegex.matches(line.trim())
    }

    private data class ParsedInlineEntry(
        val path: String,
        val inlineTotals: RemodexDiffLineTotals?,
        val action: RemodexFileChangeAction?,
    )
}

object RemodexPerFileDiffParser {
    fun parse(
        bodyText: String,
        entries: List<RemodexFileChangeSummaryEntry>,
    ): List<RemodexPerFileDiffChunk> {
        val sections = bodyText.split("\n\n---\n\n")
        if (sections.size <= 1) {
            return singleChunkFallback(bodyText, entries)
        }

        return sections.mapIndexed { index, section ->
            val lines = section.split('\n')
            val path = extractPath(lines) ?: entries.getOrNull(index)?.path ?: "file-$index"
            val code = extractFencedCode(lines).orEmpty()
            val entry = entries.firstOrNull { it.path == path }
            RemodexPerFileDiffChunk(
                id = "$index-$path",
                path = path,
                action = entry?.action ?: RemodexFileChangeAction.Edited,
                additions = entry?.additions ?: 0,
                deletions = entry?.deletions ?: 0,
                diffCode = code,
            )
        }
    }

    private fun singleChunkFallback(
        bodyText: String,
        entries: List<RemodexFileChangeSummaryEntry>,
    ): List<RemodexPerFileDiffChunk> {
        val lines = bodyText.split('\n')
        val chunks = mutableListOf<RemodexPerFileDiffChunk>()
        var currentPath: String? = null
        var index = 0

        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.startsWith("**Path:**") || trimmed.startsWith("Path:")) {
                currentPath = trimmed
                    .replace("**Path:**", "")
                    .replace("Path:", "")
                    .trim()
                    .trim('`')
                index += 1
                continue
            }

            if (trimmed.startsWith("```")) {
                index += 1
                val codeLines = mutableListOf<String>()
                while (index < lines.size) {
                    if (lines[index].trim() == "```") {
                        break
                    }
                    codeLines += lines[index]
                    index += 1
                }
                if (index < lines.size) {
                    index += 1
                }

                val code = codeLines.joinToString(separator = "\n")
                if (RemodexDiffLineKind.detectVerifiedPatch(code)) {
                    val path = currentPath ?: entries.getOrNull(chunks.size)?.path ?: "file-${chunks.size}"
                    val entry = entries.firstOrNull { it.path == path }
                    chunks += RemodexPerFileDiffChunk(
                        id = "${chunks.size}-$path",
                        path = path,
                        action = entry?.action ?: RemodexFileChangeAction.Edited,
                        additions = entry?.additions ?: 0,
                        deletions = entry?.deletions ?: 0,
                        diffCode = code,
                    )
                    currentPath = null
                }
                continue
            }

            index += 1
        }

        if (chunks.isEmpty() && entries.isNotEmpty()) {
            val first = entries.first()
            val allCode = extractFencedCode(lines) ?: bodyText
            chunks += RemodexPerFileDiffChunk(
                id = "0-${first.path}",
                path = first.path,
                action = first.action ?: RemodexFileChangeAction.Edited,
                additions = first.additions,
                deletions = first.deletions,
                diffCode = allCode,
            )
        }

        return chunks
    }

    private fun extractPath(lines: List<String>): String? {
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("**Path:**") || trimmed.startsWith("Path:")) {
                return trimmed
                    .replace("**Path:**", "")
                    .replace("Path:", "")
                    .trim()
                    .trim('`')
                    .ifEmpty { null }
            }
        }
        return null
    }

    private fun extractFencedCode(lines: List<String>): String? {
        var inFence = false
        val codeLines = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                if (inFence) {
                    return codeLines.joinToString(separator = "\n")
                }
                inFence = true
                codeLines.clear()
                continue
            }
            if (inFence) {
                codeLines += line
            }
        }
        return if (inFence) codeLines.joinToString(separator = "\n") else null
    }
}
