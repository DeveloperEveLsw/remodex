package app.remodex.android

import java.net.URI

enum class RemodexStyledTextKind {
    Plain,
    FileReference,
    SkillReference,
}

data class RemodexStyledTextToken(
    val text: String,
    val kind: RemodexStyledTextKind = RemodexStyledTextKind.Plain,
)

object RemodexMessageTextFormatter {
    private val markdownLinkTokenRegex = Regex("""\[[^\]]+]\([^)]+\)""")
    private val markdownLinkParseRegex = Regex("""^\[([^\]]+)]\(([^)]+)\)$""")
    private val inlineCodeContentRegex = Regex("""`([^`\n]+)`""")
    private val genericPathRegex = Regex("""(?:\/[^\s`"'<>]+|~\/[^\s`"'<>]+|\.{1,2}\/[^\s`"'<>]+|[A-Za-z0-9._+\-]+(?:\/[A-Za-z0-9._+\-]+)+)(?::\d+(?::\d+)?)?""")
    private val userMentionTokenRegex = Regex("""(?<![A-Za-z0-9_])([@$])((?:[^@$\n]+?\.[A-Za-z0-9]+)|(?:[^\s@$]+))(?=[\s,.;:!?)\]}>]|$)""")
    private val filenameWithLineRegex = Regex("""^(.*\.[A-Za-z0-9]+):(\d+)(?::\d+)?$""")
    private val knownSkillPathMarkers = listOf(
        "/.codex/skills/",
        "/.agents/skills/",
    )

    fun assistantTokens(raw: String): List<RemodexStyledTextToken> {
        if (raw.isEmpty()) {
            return emptyList()
        }

        val lines = raw.split('\n')
        val tokens = mutableListOf<RemodexStyledTextToken>()
        for ((index, line) in lines.withIndex()) {
            tokens += formatAssistantLine(line)
            if (index != lines.lastIndex) {
                tokens += RemodexStyledTextToken("\n")
            }
        }

        return collapseAdjacentTokens(tokens)
    }

    fun userTokens(raw: String): List<RemodexStyledTextToken> {
        if (raw.isEmpty()) {
            return emptyList()
        }

        val matches = userMentionTokenRegex.findAll(raw).toList()
        if (matches.isEmpty()) {
            return listOf(RemodexStyledTextToken(raw))
        }

        val tokens = mutableListOf<RemodexStyledTextToken>()
        var cursor = 0

        for (match in matches) {
            if (match.range.first > cursor) {
                tokens += RemodexStyledTextToken(raw.substring(cursor, match.range.first))
            }

            val trigger = match.groupValues[1]
            val rawToken = match.groupValues[2]
            val normalizedMention = normalizeMentionToken(rawToken)
            if (normalizedMention.token.isNotEmpty()) {
                val styledToken = if (trigger == "@") {
                    RemodexStyledTextToken(
                        text = fileDisplayLabel(normalizedMention.token),
                        kind = RemodexStyledTextKind.FileReference,
                    )
                } else {
                    RemodexStyledTextToken(
                        text = skillDisplayName(normalizedMention.token),
                        kind = RemodexStyledTextKind.SkillReference,
                    )
                }
                tokens += styledToken
            }

            if (normalizedMention.trailingPunctuation.isNotEmpty()) {
                tokens += RemodexStyledTextToken(normalizedMention.trailingPunctuation)
            }

            cursor = match.range.last + 1
        }

        if (cursor < raw.length) {
            tokens += RemodexStyledTextToken(raw.substring(cursor))
        }

        return collapseAdjacentTokens(tokens)
    }

    private fun formatAssistantLine(line: String): List<RemodexStyledTextToken> {
        if (line.isEmpty()) {
            return emptyList()
        }

        val replacements = linkedMapOf<String, RemodexStyledTextToken>()
        var placeholderIndex = 0

        fun placeholderFor(token: RemodexStyledTextToken): String {
            val placeholder = "\u0000${placeholderIndex++}\u0000"
            replacements[placeholder] = token
            return placeholder
        }

        var transformed = replaceMatchesReversed(line, markdownLinkTokenRegex) { match ->
            structuredReferenceToken(match.value)
        } { token ->
            placeholderFor(token)
        }

        transformed = replaceMatchesReversed(transformed, inlineCodeContentRegex) { match ->
            val inlineContent = match.groups[1]?.value ?: return@replaceMatchesReversed null
            structuredReferenceToken(inlineContent)
        } { token ->
            placeholderFor(token)
        }

        transformed = replaceMatchesReversed(transformed, genericPathRegex) { match ->
            if (!isEligiblePathToken(match.range, transformed)) {
                return@replaceMatchesReversed null
            }
            structuredReferenceToken(match.value)
        } { token ->
            placeholderFor(token)
        }

        return expandPlaceholders(transformed, replacements)
    }

    private fun structuredReferenceToken(rawReference: String): RemodexStyledTextToken? {
        val skillName = skillNameFromReference(rawReference)
        if (skillName != null) {
            return RemodexStyledTextToken(
                text = skillDisplayName(skillName),
                kind = RemodexStyledTextKind.SkillReference,
            )
        }

        val fileLabel = parseFileReferenceLabel(rawReference) ?: return null
        return RemodexStyledTextToken(
            text = fileLabel,
            kind = RemodexStyledTextKind.FileReference,
        )
    }

    private fun skillNameFromReference(rawReference: String): String? {
        val normalized = normalizeReference(rawReference)
        val lowercased = normalized.lowercase()
        if (!lowercased.endsWith("/skill.md")) {
            return null
        }
        if (knownSkillPathMarkers.none(lowercased::contains)) {
            return null
        }

        val pathComponents = normalized.split('/').filter(String::isNotBlank)
        val skillsIndex = pathComponents.indexOf("skills")
        if (skillsIndex < 0 || skillsIndex + 1 > pathComponents.lastIndex) {
            return null
        }

        val skillName = pathComponents[skillsIndex + 1].trim()
        return skillName.ifEmpty { null }
    }

    private fun parseFileReferenceLabel(rawReference: String): String? {
        var candidate = normalizeReference(rawReference)
        if (!(candidate.startsWith("/") || candidate.contains("/"))) {
            return null
        }

        var path = candidate
        var lineNumber: String? = null
        val lineMatch = filenameWithLineRegex.matchEntire(candidate)
        if (lineMatch != null) {
            path = lineMatch.groupValues[1]
            lineNumber = lineMatch.groupValues[2]
        }

        val basename = path.substringAfterLast('/')
        if (basename.isEmpty()) {
            return null
        }
        if (!basename.contains(".") && lineNumber == null) {
            return null
        }

        return if (lineNumber != null) {
            "$basename (line $lineNumber)"
        } else {
            basename
        }
    }

    private fun normalizeReference(rawReference: String): String {
        var candidate = rawReference.trim().trim('`', '"', '\'')
        val markdownLink = parseMarkdownLink(candidate)
        if (markdownLink != null) {
            candidate = markdownLink.destination
        }

        while (candidate.lastOrNull()?.let { ",.;)]}".contains(it) } == true) {
            candidate = candidate.dropLast(1)
        }
        if (candidate.startsWith("(")) {
            candidate = candidate.drop(1)
        }

        candidate = candidate.substringBefore('?').substringBefore('#')

        if (candidate.contains("://") || candidate.startsWith("file:/")) {
            val uri = runCatching { URI(candidate) }.getOrNull()
            if (uri != null) {
                if (uri.scheme.equals("file", ignoreCase = true)) {
                    return uri.path ?: candidate
                }
                if (!uri.path.isNullOrBlank()) {
                    return uri.path
                }
            }
        }

        return candidate
    }

    private fun parseMarkdownLink(token: String): ParsedMarkdownLink? {
        val match = markdownLinkParseRegex.matchEntire(token) ?: return null
        return ParsedMarkdownLink(
            label = match.groupValues[1],
            destination = match.groupValues[2],
        )
    }

    private fun skillDisplayName(rawName: String): String {
        val normalized = rawName.trim()
        if (normalized.isEmpty()) {
            return rawName
        }

        val parts = normalized
            .split('-', '_')
            .filter(String::isNotBlank)
            .map { token ->
                token.replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase()
                    } else {
                        char.toString()
                    }
                }.lowercase()
                    .replaceFirstChar { char ->
                        if (char.isLowerCase()) {
                            char.titlecase()
                        } else {
                            char.toString()
                        }
                    }
            }

        return if (parts.isEmpty()) normalized else parts.joinToString(" ")
    }

    private fun fileDisplayLabel(path: String): String {
        return path.substringAfterLast('/').ifEmpty { path }
    }

    private fun normalizeMentionToken(token: String): NormalizedMentionToken {
        val punctuation = ".,;:!?)]}"
        var splitIndex = token.length
        while (splitIndex > 0 && punctuation.contains(token[splitIndex - 1])) {
            splitIndex -= 1
        }

        return NormalizedMentionToken(
            token = token.substring(0, splitIndex),
            trailingPunctuation = token.substring(splitIndex),
        )
    }

    private fun isEligiblePathToken(range: IntRange, line: String): Boolean {
        if (range.isEmpty()) {
            return false
        }

        val token = line.substring(range)
        if (token.startsWith("//")) {
            return false
        }

        val contextStart = maxOf(0, range.first - 3)
        val leadingContext = line.substring(contextStart, range.first)
        if (leadingContext.endsWith("://")) {
            return false
        }

        if (token.startsWith("/") && range.first > 0) {
            val previousChar = line[range.first - 1]
            if (previousChar.isLetterOrDigit() || previousChar == '.') {
                return false
            }
        }

        return true
    }

    private fun expandPlaceholders(
        text: String,
        replacements: Map<String, RemodexStyledTextToken>,
    ): List<RemodexStyledTextToken> {
        if (replacements.isEmpty()) {
            return listOf(RemodexStyledTextToken(text))
        }

        val tokens = mutableListOf<RemodexStyledTextToken>()
        var cursor = 0

        while (cursor < text.length) {
            if (text[cursor] == '\u0000') {
                val endIndex = text.indexOf('\u0000', startIndex = cursor + 1)
                if (endIndex > cursor) {
                    val placeholder = text.substring(cursor, endIndex + 1)
                    val replacement = replacements[placeholder]
                    if (replacement != null) {
                        tokens += replacement
                        cursor = endIndex + 1
                        continue
                    }
                }
            }

            val nextPlaceholderStart = text.indexOf('\u0000', startIndex = cursor).let { index ->
                if (index >= 0) index else text.length
            }
            tokens += RemodexStyledTextToken(text.substring(cursor, nextPlaceholderStart))
            cursor = nextPlaceholderStart
        }

        return collapseAdjacentTokens(tokens)
    }

    private fun collapseAdjacentTokens(
        tokens: List<RemodexStyledTextToken>,
    ): List<RemodexStyledTextToken> {
        val collapsed = mutableListOf<RemodexStyledTextToken>()
        for (token in tokens) {
            if (token.text.isEmpty()) {
                continue
            }

            val previous = collapsed.lastOrNull()
            if (previous != null && previous.kind == token.kind) {
                collapsed[collapsed.lastIndex] = previous.copy(text = previous.text + token.text)
            } else {
                collapsed += token
            }
        }
        return collapsed
    }

    private inline fun replaceMatchesReversed(
        text: String,
        regex: Regex,
        replacementToken: (MatchResult) -> RemodexStyledTextToken?,
        placeholderFor: (RemodexStyledTextToken) -> String,
    ): String {
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) {
            return text
        }

        val builder = StringBuilder(text)
        for (match in matches.asReversed()) {
            val token = replacementToken(match) ?: continue
            builder.replace(match.range.first, match.range.last + 1, placeholderFor(token))
        }
        return builder.toString()
    }

    private data class ParsedMarkdownLink(
        val label: String,
        val destination: String,
    )

    private data class NormalizedMentionToken(
        val token: String,
        val trailingPunctuation: String,
    )
}
