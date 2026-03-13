package app.remodex.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemodexTurnRenderParserTests {
    @Test
    fun thinkingParserBuildsDisclosureSectionsAndMergesPreamble() {
        val content = ThinkingDisclosureParser.parse(
            """
            Thinking...
            Inspecting repository state

            **Plan**
            First detail

            **Patch**
            Second detail
            """.trimIndent(),
        )

        assertTrue(content.showsDisclosure)
        assertEquals(2, content.sections.size)
        assertEquals("Plan", content.sections[0].title)
        assertTrue(content.sections[0].detail.contains("Inspecting repository state"))
        assertEquals("Patch", content.sections[1].title)
    }

    @Test
    fun thinkingParserCoalescesRepeatedStreamingSummaryAnchors() {
        val content = ThinkingDisclosureParser.parse(
            """
            **Plan**
            Initial detail
            **Plan**
            Initial detail
            Expanded detail
            """.trimIndent(),
        )

        assertEquals(1, content.sections.size)
        assertTrue(content.sections.single().detail.contains("Expanded detail"))
    }

    @Test
    fun markdownParserSplitsProseAndCodeFences() {
        val segments = RemodexMarkdownRenderer.parseMarkdownSegments(
            """
            Intro text
            ```kotlin
            println("hi")
            ```
            Outro text
            """.trimIndent(),
        )

        assertEquals(3, segments.size)
        assertTrue(segments[0] is MarkdownSegment.Prose)
        assertTrue(segments[1] is MarkdownSegment.CodeBlock)
        assertTrue(segments[2] is MarkdownSegment.Prose)
        assertEquals("kotlin", (segments[1] as MarkdownSegment.CodeBlock).language)
    }

    @Test
    fun diffDetectorAcceptsVerifiedPatchAndRejectsPlainCode() {
        val patch = """
            diff --git a/a.txt b/a.txt
            index 1111111..2222222 100644
            --- a/a.txt
            +++ b/a.txt
            @@ -1 +1 @@
            -old
            +new
        """.trimIndent()
        val plainCode = """
            fun main() {
                println("hello")
            }
        """.trimIndent()

        assertTrue(RemodexDiffLineKind.detectVerifiedPatch(patch))
        assertFalse(RemodexDiffLineKind.detectVerifiedPatch(plainCode))
    }
}
