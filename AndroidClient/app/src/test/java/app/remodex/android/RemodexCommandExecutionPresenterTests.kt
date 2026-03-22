package app.remodex.android

import app.remodex.android.core.model.CodexCommandExecutionDetails
import app.remodex.android.core.model.CodexCommandExecutionPhase
import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RemodexCommandExecutionPresenterTests {
    @Test
    fun presenterUsesSafeSummaryWithoutRunningPrefix() {
        val presentation = RemodexCommandExecutionPresenter.present(
            commandMessage(
                text = "running Read MainActivity.kt",
                rawCommand = "/bin/zsh -lc \"nl -ba AndroidClient/app/src/main/java/app/remodex/android/MainActivity.kt | sed -n '1,80p'\"",
                phase = CodexCommandExecutionPhase.Running,
                summary = "Read MainActivity.kt",
                isStreaming = true,
            ),
        )

        assertNotNull(presentation)
        assertEquals("Read MainActivity.kt", presentation?.collapsedSummary)
        assertEquals("Running", presentation?.detailStatusLabel)
    }

    @Test
    fun presenterFallsBackToRunningPreviewForUnsafeCommand() {
        val presentation = RemodexCommandExecutionPresenter.present(
            commandMessage(
                text = "running mkdir tmp && touch tmp/smoke.txt",
                rawCommand = "mkdir tmp && touch tmp/smoke.txt",
                phase = CodexCommandExecutionPhase.Running,
                isStreaming = true,
            ),
        )

        assertNotNull(presentation)
        assertEquals("Running mkdir tmp && touch tmp/smoke.txt", presentation?.collapsedSummary)
        assertEquals("Running", presentation?.detailStatusLabel)
    }

    @Test
    fun presenterDropsCompletedPrefixForUnsafeCompletedCommand() {
        val presentation = RemodexCommandExecutionPresenter.present(
            commandMessage(
                text = "completed git diff -- Docs/ui-smoke-test-20260322.md",
                rawCommand = "git diff -- Docs/ui-smoke-test-20260322.md",
                phase = CodexCommandExecutionPhase.Completed,
            ),
        )

        assertNotNull(presentation)
        assertEquals("Ran git diff -- Docs/ui-smoke-test-20260322.md", presentation?.collapsedSummary)
        assertEquals("Succeeded", presentation?.detailStatusLabel)
    }

    @Test
    fun presenterFallsBackToLegacyTextWhenDetailsAreMissing() {
        val presentation = RemodexCommandExecutionPresenter.present(
            CodexMessage(
                id = "command-1",
                threadId = "thread-1",
                role = CodexMessageRole.System,
                kind = CodexMessageKind.CommandExecution,
                text = "failed npm test",
            ),
        )

        assertNotNull(presentation)
        assertEquals("Failed npm test", presentation?.collapsedSummary)
        assertEquals("Failed", presentation?.detailStatusLabel)
    }

    private fun commandMessage(
        text: String,
        rawCommand: String,
        phase: CodexCommandExecutionPhase,
        summary: String? = null,
        isStreaming: Boolean = false,
    ): CodexMessage {
        return CodexMessage(
            id = "command-1",
            threadId = "thread-1",
            role = CodexMessageRole.System,
            kind = CodexMessageKind.CommandExecution,
            text = text,
            isStreaming = isStreaming,
            commandExecutionDetails = CodexCommandExecutionDetails(
                rawCommand = rawCommand,
                summary = summary,
                phase = phase,
            ),
        )
    }
}
