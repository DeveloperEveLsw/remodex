package app.remodex.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemodexCommandSummaryParserTests {
    @Test
    fun summarizesPipedReadCommand() {
        val summary = RemodexCommandSummaryParser.summarize(
            "/bin/zsh -lc \"nl -ba AndroidClient/app/src/main/java/app/remodex/android/MainActivity.kt | sed -n '1,80p'\"",
        )

        assertEquals("Read MainActivity.kt", summary?.summary)
    }

    @Test
    fun summarizesSearchCommand() {
        val summary = RemodexCommandSummaryParser.summarize(
            "rg -n \"Read |Search |List files|Running\" CodexMobile/CodexMobile/Views/Turn",
        )

        assertEquals(
            "Searched for Read |Search |List files|Running in Turn",
            summary?.summary,
        )
    }

    @Test
    fun summarizesListedFilesCommand() {
        val summary = RemodexCommandSummaryParser.summarize(
            "rg --files AndroidClient | head -n 12",
        )

        assertEquals("Listed AndroidClient", summary?.summary)
    }

    @Test
    fun summarizesCreateDeleteAndRenameCommands() {
        assertEquals(
            "Created smoke.txt",
            RemodexCommandSummaryParser.summarize("touch smoke.txt")?.summary,
        )
        assertEquals(
            "Deleted smoke.txt",
            RemodexCommandSummaryParser.summarize("rm smoke.txt")?.summary,
        )
        assertEquals(
            "Renamed old.txt -> new.txt",
            RemodexCommandSummaryParser.summarize("mv old.txt new.txt")?.summary,
        )
    }

    @Test
    fun fallsBackForMultiIntentCommand() {
        val summary = RemodexCommandSummaryParser.summarize(
            "mkdir tmp && touch tmp/smoke.txt",
        )

        assertNull(summary)
    }
}
