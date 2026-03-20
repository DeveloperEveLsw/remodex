package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageDeliveryState
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexThreadRunBadgeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemodexConversationStateTests {
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
