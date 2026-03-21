package app.remodex.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemodexMessageTextFormatterTests {
    @Test
    fun assistantTokensCompactFilePathsAndLineNumbers() {
        val tokens = RemodexMessageTextFormatter.assistantTokens(
            "Inspect /tmp/project/Sources/App.swift and src/ui/TurnView.kt:42 next",
        )

        assertEquals(
            "Inspect App.swift and TurnView.kt (line 42) next",
            tokens.joinToString(separator = "") { it.text },
        )
        assertTrue(tokens.any { it.kind == RemodexStyledTextKind.FileReference && it.text == "App.swift" })
        assertTrue(tokens.any { it.kind == RemodexStyledTextKind.FileReference && it.text == "TurnView.kt (line 42)" })
    }

    @Test
    fun assistantTokensLeaveUrlsAloneWhileFormattingPaths() {
        val tokens = RemodexMessageTextFormatter.assistantTokens(
            "See https://example.com/foo/bar and /tmp/project/Package.swift",
        )

        assertEquals(
            "See https://example.com/foo/bar and Package.swift",
            tokens.joinToString(separator = "") { it.text },
        )
    }

    @Test
    fun assistantTokensReplaceSkillPathsAndInlineCodeFileRefs() {
        val tokens = RemodexMessageTextFormatter.assistantTokens(
            "Read /Users/me/.codex/skills/skill-builder/SKILL.md then `/tmp/project/Sources/App.swift:9`.",
        )

        assertEquals(
            "Read Skill Builder then App.swift (line 9).",
            tokens.joinToString(separator = "") { it.text },
        )
        assertTrue(tokens.any { it.kind == RemodexStyledTextKind.SkillReference && it.text == "Skill Builder" })
    }

    @Test
    fun userTokensRenderFileMentionsWithSpacesAndSkillDisplayNames() {
        val tokens = RemodexMessageTextFormatter.userTokens(
            "Check @Codex Mobile App Plan/Codex iOS Recap TLDR.md with \$skill-builder.",
        )

        assertEquals(
            "Check Codex iOS Recap TLDR.md with Skill Builder.",
            tokens.joinToString(separator = "") { it.text },
        )
        assertTrue(tokens.any { it.kind == RemodexStyledTextKind.FileReference && it.text == "Codex iOS Recap TLDR.md" })
        assertTrue(tokens.any { it.kind == RemodexStyledTextKind.SkillReference && it.text == "Skill Builder" })
    }
}
