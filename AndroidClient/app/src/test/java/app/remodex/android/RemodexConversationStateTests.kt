package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageDeliveryState
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexThreadRunBadgeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemodexConversationStateTests {
    @Test
    fun appendAssistantDeltaKeepsSeparateBlocksWhenItemChangesWithinTurn() {
        val threadId = "thread-1"

        val conversation = RemodexConversationState()
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "item-1",
                delta = "First",
            )
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "item-1",
                delta = " chunk",
            )
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "item-2",
                delta = "Second",
            )

        val assistantMessages = conversation.messagesFor(threadId).filter { it.role == CodexMessageRole.Assistant }
        assertEquals(2, assistantMessages.count())
        assertEquals("item-1", assistantMessages[0].itemId)
        assertEquals("First chunk", assistantMessages[0].text)
        assertFalse(assistantMessages[0].isStreaming)
        assertEquals("item-2", assistantMessages[1].itemId)
        assertEquals("Second", assistantMessages[1].text)
        assertTrue(assistantMessages[1].isStreaming)
    }

    @Test
    fun appendAssistantDeltaPreservesLeadingSpacesBetweenStreamTokens() {
        val threadId = "thread-1"

        val conversation = RemodexConversationState()
            .beginAssistantMessage(threadId = threadId, turnId = "turn-1", itemId = "item-1")
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "item-1",
                delta = "Hello",
            )
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "item-1",
                delta = " world",
            )

        assertEquals("Hello world", conversation.messagesFor(threadId).single().text)
    }

    @Test
    fun appendAssistantDeltaMergesCumulativeSnapshotsLikeIos() {
        val threadId = "thread-1"

        val conversation = RemodexConversationState()
            .beginAssistantMessage(threadId = threadId, turnId = "turn-1", itemId = "item-1")
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "item-1",
                delta = "Hello",
            )
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "item-1",
                delta = "Hello world",
            )

        assertEquals("Hello world", conversation.messagesFor(threadId).single().text)
    }

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
    fun mergeHydratedThreadMessagesReconcilesStreamingFileChangeRowLikeIos() {
        val threadId = "thread-1"
        val conversation = RemodexConversationState()
            .withTurnStarted(threadId, "turn-1")
            .appendSystemDelta(
                threadId = threadId,
                kind = CodexMessageKind.FileChange,
                delta = "Path: App.kt\nKind: update",
                turnId = "turn-1",
            )

        val hydrated = listOf(
            CodexMessage(
                id = "filechange-1",
                threadId = threadId,
                role = CodexMessageRole.System,
                kind = CodexMessageKind.FileChange,
                text = "Path: App.kt\nKind: update\n\n```diff\n@@ -1 +1 @@\n-old\n+new\n```",
                turnId = "turn-1",
                itemId = "filechange-1",
                isStreaming = false,
                orderIndex = 0,
            ),
        )

        val merged = conversation.mergeHydratedThreadMessages(threadId, hydrated)
        val messages = merged.messagesFor(threadId)

        assertEquals(1, messages.size)
        assertEquals(CodexMessageKind.FileChange, messages.single().kind)
        assertTrue(messages.single().isStreaming)
        assertEquals("turn-1", messages.single().turnId)
    }

    @Test
    fun appendSystemDeltaMergesLateReasoningIntoExistingRowAfterTurnCompletion() {
        val threadId = "thread-1"
        val conversation = RemodexConversationState()
            .withTurnStarted(threadId, "turn-1")
            .appendSystemDelta(
                threadId = threadId,
                kind = CodexMessageKind.Thinking,
                delta = "Reviewing files",
                turnId = "turn-1",
                itemId = "thinking-1",
            )
            .markTurnCompleted(threadId, "turn-1")
            .appendSystemDelta(
                threadId = threadId,
                kind = CodexMessageKind.Thinking,
                delta = " carefully",
                turnId = "turn-1",
                itemId = "thinking-1",
            )

        val thinkingMessages = conversation.messagesFor(threadId).filter { it.kind == CodexMessageKind.Thinking }
        assertEquals(1, thinkingMessages.size)
        assertEquals("Reviewing files carefully", thinkingMessages.single().text)
        assertFalse(thinkingMessages.single().isStreaming)
    }

    @Test
    fun markTurnCompletedFinalizesAllAssistantItemsForTurn() {
        val threadId = "thread-1"
        val conversation = RemodexConversationState()
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "item-1",
                delta = "A",
            )
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "item-2",
                delta = "B",
            )
            .markTurnCompleted(threadId, "turn-1")

        val assistantMessages = conversation.messagesFor(threadId).filter { it.role == CodexMessageRole.Assistant }
        assertEquals(2, assistantMessages.size)
        assertTrue(assistantMessages.all { !it.isStreaming })
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

    @Test
    fun appendUserMessageAndConfirmDeliveryKeepsUserRowVisibleBeforeHydration() {
        val threadId = "thread-1"
        val messageId = "msg-pending"

        val conversation = RemodexConversationState()
            .appendUserMessage(
                threadId = threadId,
                text = "Ship it",
                messageId = messageId,
            )
            .markMessageDeliveryState(
                threadId = threadId,
                messageId = messageId,
                deliveryState = CodexMessageDeliveryState.Confirmed,
                turnId = "turn-1",
            )

        val message = conversation.messagesFor(threadId).single()
        assertEquals(CodexMessageRole.User, message.role)
        assertEquals("Ship it", message.text)
        assertEquals(CodexMessageDeliveryState.Confirmed, message.deliveryState)
        assertEquals("turn-1", message.turnId)
    }

    @Test
    fun moveMessageToThreadCarriesPendingUserRowToContinuationThread() {
        val conversation = RemodexConversationState()
            .appendUserMessage(
                threadId = "thread-old",
                text = "Continue from here",
                messageId = "msg-pending",
            )
            .moveMessageToThread(
                sourceThreadId = "thread-old",
                targetThreadId = "thread-new",
                messageId = "msg-pending",
            )

        assertTrue(conversation.messagesFor("thread-old").isEmpty())
        val movedMessage = conversation.messagesFor("thread-new").single()
        assertEquals("thread-new", movedMessage.threadId)
        assertEquals("Continue from here", movedMessage.text)
        assertEquals(CodexMessageDeliveryState.Pending, movedMessage.deliveryState)
    }
}
