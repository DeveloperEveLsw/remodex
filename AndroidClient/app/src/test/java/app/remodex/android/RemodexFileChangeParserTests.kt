package app.remodex.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemodexFileChangeParserTests {
    @Test
    fun parseInlineActionRowsWithTotals() {
        val summary = RemodexFileChangeSummaryParser.parse(
            """
            Edited app/src/Main.kt +12 -3
            Added docs/README.md +4 -0
            """.trimIndent(),
        )

        assertNotNull(summary)
        assertEquals(2, summary!!.entries.size)
        assertEquals(RemodexFileChangeAction.Edited, summary.entries[0].action)
        assertEquals(12, summary.entries[0].additions)
        assertEquals(RemodexFileChangeAction.Added, summary.entries[1].action)
    }

    @Test
    fun parseDiffFenceCountsBodyAndResolvesPath() {
        val summary = RemodexFileChangeSummaryParser.parse(
            """
            Path: app/src/Main.kt
            Kind: update
            ```diff
            diff --git a/app/src/Main.kt b/app/src/Main.kt
            index 1111111..2222222 100644
            --- a/app/src/Main.kt
            +++ b/app/src/Main.kt
            @@ -1 +1,2 @@
            -old
            +new
            +more
            ```
            """.trimIndent(),
        )

        assertNotNull(summary)
        val entry = summary!!.entries.single()
        assertEquals("app/src/Main.kt", entry.path)
        assertEquals(2, entry.additions)
        assertEquals(1, entry.deletions)
        assertEquals(RemodexFileChangeAction.Edited, entry.action)
    }

    @Test
    fun removingInlineEditingRowsKeepsBodyText() {
        val cleaned = RemodexFileChangeSummaryParser.removingInlineEditingRows(
            """
            Edited app/src/Main.kt +12 -3

            Applied patch successfully.
            """.trimIndent(),
        )

        assertEquals("Applied patch successfully.", cleaned)
    }

    @Test
    fun perFileDiffParserCreatesChunksFromSeparatedSections() {
        val entries = listOf(
            RemodexFileChangeSummaryEntry(
                path = "app/src/Main.kt",
                additions = 2,
                deletions = 1,
                action = RemodexFileChangeAction.Edited,
            ),
            RemodexFileChangeSummaryEntry(
                path = "docs/README.md",
                additions = 1,
                deletions = 0,
                action = RemodexFileChangeAction.Added,
            ),
        )
        val chunks = RemodexPerFileDiffParser.parse(
            bodyText = """
            Path: app/src/Main.kt
            ```diff
            @@ -1 +1,2 @@
            -old
            +new
            +more
            ```

            ---

            Path: docs/README.md
            ```diff
            @@ -0,0 +1 @@
            +hello
            ```
            """.trimIndent(),
            entries = entries,
        )

        assertEquals(2, chunks.size)
        assertEquals("app/src/Main.kt", chunks[0].path)
        assertTrue(chunks[0].diffCode.contains("+new"))
        assertEquals("docs/README.md", chunks[1].path)
    }
}
