package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemodexTimelineProjectionTests {
    @Test
    fun projectReordersSingleItemTurnIntoUserThinkingAssistantFileChange() {
        val messages = listOf(
            assistantMessage(id = "assistant", turnId = "turn-1", orderIndex = 2),
            fileChangeMessage(id = "diff", turnId = "turn-1", text = "Edited file", orderIndex = 3),
            thinkingMessage(id = "thinking", turnId = "turn-1", text = "Thinking...", orderIndex = 1),
            userMessage(id = "user", turnId = "turn-1", orderIndex = 0),
        )

        val projected = RemodexTimelineProjector.project(messages).messages

        assertEquals(listOf("user", "thinking", "assistant", "diff"), projected.map(CodexMessage::id))
    }

    @Test
    fun projectCollapsesConsecutiveThinkingSnapshotsForSameItem() {
        val messages = listOf(
            thinkingMessage(id = "thinking-1", turnId = "turn-1", itemId = "item-1", text = "Thinking...", isStreaming = true),
            thinkingMessage(id = "thinking-2", turnId = "turn-1", itemId = "item-1", text = "Reviewing files", isStreaming = false),
        )

        val projected = RemodexTimelineProjector.project(messages).messages

        assertEquals(1, projected.size)
        assertEquals("Reviewing files", projected.single().text)
        assertFalse(projected.single().isStreaming)
    }

    @Test
    fun projectKeepsOnlyLatestDuplicateFileChangeSnapshotPerTurn() {
        val messages = listOf(
            fileChangeMessage(id = "diff-1", turnId = "turn-1", text = "Edited App.kt"),
            fileChangeMessage(id = "diff-2", turnId = "turn-1", text = "Edited App.kt"),
        )

        val projected = RemodexTimelineProjector.project(messages).messages

        assertEquals(listOf("diff-2"), projected.map(CodexMessage::id))
    }

    @Test
    fun projectRemovesDuplicateAssistantCompletionsWithinSameTurnAndItem() {
        val messages = listOf(
            assistantMessage(id = "assistant-1", turnId = "turn-1", itemId = "item-1", text = "Done"),
            assistantMessage(id = "assistant-2", turnId = "turn-1", itemId = "item-1", text = "Done"),
        )

        val projected = RemodexTimelineProjector.project(messages).messages

        assertEquals(listOf("assistant-1"), projected.map(CodexMessage::id))
    }

    @Test
    fun assistantResponseAnchorPrefersActiveTurnAssistantRow() {
        val messages = listOf(
            assistantMessage(id = "assistant-1", turnId = "turn-old", isStreaming = true),
            assistantMessage(id = "assistant-2", turnId = "turn-live", isStreaming = true),
        )

        val anchorId = RemodexTimelineProjector.assistantResponseAnchorMessageId(
            messages = messages,
            activeTurnId = "turn-live",
        )

        assertEquals("assistant-2", anchorId)
    }

    private fun userMessage(id: String, turnId: String, orderIndex: Int) = CodexMessage(
        id = id,
        threadId = "thread-1",
        role = CodexMessageRole.User,
        kind = CodexMessageKind.Chat,
        text = "Prompt",
        turnId = turnId,
        orderIndex = orderIndex,
    )

    private fun assistantMessage(
        id: String,
        turnId: String,
        itemId: String? = null,
        text: String = "Reply",
        isStreaming: Boolean = false,
        orderIndex: Int = 0,
    ) = CodexMessage(
        id = id,
        threadId = "thread-1",
        role = CodexMessageRole.Assistant,
        kind = CodexMessageKind.Chat,
        text = text,
        createdAt = Instant.parse("2026-03-14T00:00:00Z"),
        turnId = turnId,
        itemId = itemId,
        isStreaming = isStreaming,
        orderIndex = orderIndex,
    )

    private fun thinkingMessage(
        id: String,
        turnId: String,
        itemId: String? = null,
        text: String,
        isStreaming: Boolean = false,
        orderIndex: Int = 0,
    ) = CodexMessage(
        id = id,
        threadId = "thread-1",
        role = CodexMessageRole.System,
        kind = CodexMessageKind.Thinking,
        text = text,
        turnId = turnId,
        itemId = itemId,
        isStreaming = isStreaming,
        orderIndex = orderIndex,
    )

    private fun fileChangeMessage(
        id: String,
        turnId: String,
        text: String,
        orderIndex: Int = 0,
    ) = CodexMessage(
        id = id,
        threadId = "thread-1",
        role = CodexMessageRole.System,
        kind = CodexMessageKind.FileChange,
        text = text,
        turnId = turnId,
        orderIndex = orderIndex,
    )
}
