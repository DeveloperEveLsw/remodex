package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexThreadRunBadgeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemodexConversationStateTests {
    @Test
    fun mergeHydratedThreadMessagesPreservesLiveAssistantRowDuringStreaming() {
        val threadId = "thread-1"
        val conversation = RemodexConversationState()
            .beginAssistantMessage(threadId = threadId, turnId = "turn-1", itemId = "item-1")
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "item-1",
                delta = "Hello",
            )

        val hydrated = listOf(
            CodexMessage(
                id = "item-1",
                threadId = threadId,
                role = CodexMessageRole.Assistant,
                text = "Hello world",
                turnId = "turn-1",
                itemId = "item-1",
                isStreaming = false,
                orderIndex = 0,
            ),
        )

        val merged = conversation.mergeHydratedThreadMessages(threadId, hydrated)
        val assistantMessage = merged.messagesFor(threadId).single()

        assertEquals("Hello world", assistantMessage.text)
        assertTrue(assistantMessage.isStreaming)
        assertEquals("item-1", assistantMessage.itemId)
        assertTrue(merged.isHydratedThread(threadId))
    }

    @Test
    fun markThreadAsViewedClearsReadyAndFailedBadges() {
        val threadId = "thread-1"
        val conversation = RemodexConversationState(
            readyThreadIds = setOf(threadId),
            failedThreadIds = setOf(threadId),
        ).markThreadAsViewed(threadId)

        assertFalse(conversation.readyThreadIds.contains(threadId))
        assertFalse(conversation.failedThreadIds.contains(threadId))
    }

    @Test
    fun threadRunBadgeStatePrioritizesRunningOverOutcomeBadges() {
        val threadId = "thread-1"
        val conversation = RemodexConversationState(
            activeTurnIdByThread = mapOf(threadId to "turn-1"),
            runningThreadIds = setOf(threadId),
            readyThreadIds = setOf(threadId),
            failedThreadIds = setOf(threadId),
        )

        assertEquals(CodexThreadRunBadgeState.Running, conversation.threadRunBadgeState(threadId))
    }
}
