package app.remodex.android

data class ThinkingDisclosureSection(
    val id: String,
    val title: String,
    val detail: String,
)

data class ThinkingDisclosureContent(
    val sections: List<ThinkingDisclosureSection>,
    val fallbackText: String,
) {
    val showsDisclosure: Boolean
        get() = sections.isNotEmpty()
}

object ThinkingDisclosureParser {
    private val summaryLineRegex = Regex("""^\s*\*\*(.+?)\*\*\s*$""")

    fun parse(rawText: String): ThinkingDisclosureContent {
        val normalizedText = normalizedThinkingContent(rawText)
        if (normalizedText.isEmpty()) {
            return ThinkingDisclosureContent(sections = emptyList(), fallbackText = "")
        }

        val lines = normalizedText.split('\n')
        val preambleLines = mutableListOf<String>()
        var currentTitle: String? = null
        var currentDetailLines = mutableListOf<String>()
        val sections = mutableListOf<ThinkingDisclosureSection>()

        fun flushCurrentSection() {
            val title = currentTitle ?: return
            sections += ThinkingDisclosureSection(
                id = "${sections.size}-$title",
                title = title,
                detail = joinedThinkingBlock(currentDetailLines),
            )
            currentDetailLines = mutableListOf()
        }

        for (line in lines) {
            val summaryTitle = summaryTitle(line)
            if (summaryTitle != null) {
                flushCurrentSection()
                currentTitle = summaryTitle
                continue
            }

            if (currentTitle == null) {
                preambleLines += line
            } else {
                currentDetailLines += line
            }
        }

        flushCurrentSection()

        if (sections.isNotEmpty()) {
            val preamble = joinedThinkingBlock(preambleLines)
            if (preamble.isNotEmpty()) {
                val first = sections.removeAt(0)
                val mergedDetail = listOf(preamble, first.detail)
                    .filter(String::isNotEmpty)
                    .joinToString(separator = "\n\n")
                sections.add(
                    index = 0,
                    element = first.copy(detail = mergedDetail),
                )
            }

            return ThinkingDisclosureContent(
                sections = coalescedAdjacentSections(sections),
                fallbackText = normalizedText,
            )
        }

        return ThinkingDisclosureContent(
            sections = emptyList(),
            fallbackText = normalizedText,
        )
    }

    fun normalizedThinkingContent(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        if (trimmed.equals("thinking", ignoreCase = true)) {
            return ""
        }

        val prefix = "thinking..."
        if (trimmed.lowercase().startsWith(prefix)) {
            return trimmed.substring(prefix.length).trim()
        }

        return trimmed
    }

    private fun summaryTitle(line: String): String? {
        val match = summaryLineRegex.matchEntire(line) ?: return null
        val title = match.groupValues[1].trim()
        return title.ifEmpty { null }
    }

    private fun joinedThinkingBlock(lines: List<String>): String {
        return lines.joinToString(separator = "\n").trim()
    }

    private fun coalescedAdjacentSections(
        sections: List<ThinkingDisclosureSection>,
    ): List<ThinkingDisclosureSection> {
        val collapsed = mutableListOf<ThinkingDisclosureSection>()

        for (section in sections) {
            val previous = collapsed.lastOrNull()
            if (previous == null || previous.title != section.title) {
                collapsed += section
                continue
            }

            val mergedDetail = when {
                previous.detail == section.detail || section.detail.isEmpty() -> previous.detail
                previous.detail.isEmpty() || section.detail.contains(previous.detail) -> section.detail
                previous.detail.contains(section.detail) -> previous.detail
                else -> listOf(previous.detail, section.detail)
                    .filter(String::isNotEmpty)
                    .joinToString(separator = "\n\n")
            }

            collapsed[collapsed.lastIndex] = previous.copy(detail = mergedDetail)
        }

        return collapsed
    }
}
