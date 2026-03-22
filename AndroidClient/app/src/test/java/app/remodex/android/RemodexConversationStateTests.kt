package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageDeliveryState
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexPlanStep
import app.remodex.android.core.model.CodexPlanStepStatus
import app.remodex.android.core.model.CodexStructuredUserInputQuestion
import app.remodex.android.core.model.CodexStructuredUserInputRequest
import app.remodex.android.core.model.CodexThreadRunBadgeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive

class RemodexConversationStateTests {
    @Test
    fun upsertPlanMessageStoresExplanationAndStepsOnSingleTimelineRow() {
        val threadId = "thread-1"

        val conversation = RemodexConversationState().upsertPlanMessage(
            threadId = threadId,
            turnId = "turn-1",
            itemId = "plan-1",
            explanation = "Inspect the repo before editing.",
            steps = listOf(
                CodexPlanStep(
                    id = "step-1",
                    step = "Inspect files",
                    status = CodexPlanStepStatus.InProgress,
                ),
            ),
            isStreaming = true,
        )

        val planMessage = conversation.messagesFor(threadId).single()
        assertEquals(CodexMessageKind.Plan, planMessage.kind)
        assertTrue(planMessage.isStreaming)
        assertEquals("Inspect the repo before editing.", planMessage.planState?.explanation)
        assertEquals(1, planMessage.planState?.steps?.size)
        assertEquals("Inspect files", planMessage.planState?.steps?.single()?.step)
    }

    @Test
    fun structuredUserInputPromptUpdatesInPlaceAndRemovesByRequestId() {
        val threadId = "thread-1"
        val requestId = JsonPrimitive("request-1")
        val initialRequest = CodexStructuredUserInputRequest(
            requestID = requestId,
            questions = listOf(
                CodexStructuredUserInputQuestion(
                    id = "question-1",
                    header = "Access",
                    question = "Which mode should we use?",
                ),
            ),
        )
        val updatedRequest = initialRequest.copy(
            questions = listOf(
                CodexStructuredUserInputQuestion(
                    id = "question-1",
                    header = "Access",
                    question = "Pick the mode for this session.",
                ),
            ),
        )

        val conversation = RemodexConversationState()
            .upsertStructuredUserInputPrompt(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "prompt-1",
                request = initialRequest,
            )
            .upsertStructuredUserInputPrompt(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "prompt-2",
                request = updatedRequest,
            )

        val promptMessage = conversation.messagesFor(threadId).single()
        assertEquals(CodexMessageKind.UserInputPrompt, promptMessage.kind)
        assertEquals("prompt-2", promptMessage.itemId)
        assertEquals("Access\nPick the mode for this session.", promptMessage.text)

        val removed = conversation.removeStructuredUserInputPrompt(
            requestID = requestId,
            threadIdHint = threadId,
        )
        assertTrue(removed.messagesFor(threadId).isEmpty())
    }

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
    fun mergeHydratedThreadMessagesReconcilesThinkingByTurnWhenTextDiffersLikeIos() {
        val threadId = "thread-1"
        val turnId = "turn-1"
        val existing = RemodexConversationState(
            messagesByThread = mapOf(
                threadId to listOf(
                    CodexMessage(
                        id = "thinking-local",
                        threadId = threadId,
                        role = CodexMessageRole.System,
                        kind = CodexMessageKind.Thinking,
                        text = "**Providingexact200-wordparagraph**",
                        createdAt = Instant.parse("2026-03-22T00:00:00Z"),
                        turnId = turnId,
                        orderIndex = 0,
                    ),
                ),
            ),
        )
        val hydrated = listOf(
            CodexMessage(
                id = "thinking-history",
                threadId = threadId,
                role = CodexMessageRole.System,
                kind = CodexMessageKind.Thinking,
                text = "**Providing exact 200-word paragraph**",
                createdAt = Instant.parse("2026-03-22T00:00:01Z"),
                turnId = turnId,
                orderIndex = 0,
            ),
        )

        val merged = existing.mergeHydratedThreadMessages(threadId, hydrated)
        val messages = merged.messagesFor(threadId)

        assertEquals(1, messages.size)
        assertEquals("**Providing exact 200-word paragraph**", messages.single().text)
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
    fun completeSystemMessageKeepsWorkspaceCardInPlaceWhenCompleting() {
        val threadId = "thread-1"
        val conversation = RemodexConversationState()
            .appendUserMessage(threadId = threadId, text = "Apply the patch")
            .appendSystemDelta(
                threadId = threadId,
                kind = CodexMessageKind.FileChange,
                delta = "Path: App.kt\nKind: update",
                turnId = "turn-1",
            )
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "assistant-1",
                delta = "Patched it",
            )
            .completeSystemMessage(
                threadId = threadId,
                kind = CodexMessageKind.FileChange,
                text = "Path: App.kt\nKind: update\n\n```diff\n@@ -1 +1 @@\n-old\n+new\n```",
                turnId = "turn-1",
            )

        val messages = conversation.messagesFor(threadId)

        assertEquals(
            listOf(
                CodexMessageRole.User to CodexMessageKind.Chat,
                CodexMessageRole.System to CodexMessageKind.FileChange,
                CodexMessageRole.Assistant to CodexMessageKind.Chat,
            ),
            messages.map { it.role to it.kind },
        )
        assertFalse(messages[1].isStreaming)
        assertTrue(messages[1].orderIndex < messages[2].orderIndex)
    }

    @Test
    fun completeSystemMessageKeepsCommandCardInPlaceWhenCompleting() {
        val threadId = "thread-1"
        val conversation = RemodexConversationState()
            .appendUserMessage(threadId = threadId, text = "Run tests")
            .appendSystemDelta(
                threadId = threadId,
                kind = CodexMessageKind.CommandExecution,
                delta = "npm test\nstatus: running",
                turnId = "turn-1",
            )
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "assistant-1",
                delta = "Waiting for test output",
            )
            .completeSystemMessage(
                threadId = threadId,
                kind = CodexMessageKind.CommandExecution,
                text = "npm test\nstatus: completed",
                turnId = "turn-1",
            )

        val messages = conversation.messagesFor(threadId)

        assertEquals(
            listOf(
                CodexMessageRole.User to CodexMessageKind.Chat,
                CodexMessageRole.System to CodexMessageKind.CommandExecution,
                CodexMessageRole.Assistant to CodexMessageKind.Chat,
            ),
            messages.map { it.role to it.kind },
        )
        assertFalse(messages[1].isStreaming)
        assertTrue(messages[1].orderIndex < messages[2].orderIndex)
    }

    @Test
    fun mergeHydratedThreadMessagesDedupesQuotedCommandPreviewByTurnLikeIos() {
        val threadId = "thread-1"
        val turnId = "turn-1"
        val existing = RemodexConversationState(
            messagesByThread = mapOf(
                threadId to listOf(
                    CodexMessage(
                        id = "command-local",
                        threadId = threadId,
                        role = CodexMessageRole.System,
                        kind = CodexMessageKind.CommandExecution,
                        text = "completed /bin/zsh -lc rg --files",
                        createdAt = Instant.parse("2026-03-22T00:00:00Z"),
                        turnId = turnId,
                        isStreaming = false,
                        orderIndex = 0,
                    ),
                ),
            ),
        )
        val hydrated = listOf(
            CodexMessage(
                id = "command-history",
                threadId = threadId,
                role = CodexMessageRole.System,
                kind = CodexMessageKind.CommandExecution,
                text = "completed /bin/zsh -lc \"rg --files\"",
                createdAt = Instant.parse("2026-03-22T00:00:01Z"),
                turnId = turnId,
                isStreaming = false,
                orderIndex = 0,
            ),
        )

        val merged = existing.mergeHydratedThreadMessages(threadId, hydrated)
        val commandRows = merged.messagesFor(threadId).filter {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.CommandExecution
        }

        assertEquals(1, commandRows.size)
        assertEquals(turnId, commandRows.single().turnId)
    }

    @Test
    fun mergeHydratedThreadMessagesPreservesExistingSystemRowPosition() {
        val threadId = "thread-1"
        val conversation = RemodexConversationState()
            .appendUserMessage(threadId = threadId, text = "Run tests")
            .appendSystemDelta(
                threadId = threadId,
                kind = CodexMessageKind.CommandExecution,
                delta = "running echo one",
                turnId = "turn-1",
                itemId = "cmd-1",
            )
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "assistant-1",
                delta = "Waiting for output",
            )
            .completeSystemMessage(
                threadId = threadId,
                kind = CodexMessageKind.CommandExecution,
                text = "completed echo one",
                turnId = "turn-1",
                itemId = "cmd-1",
            )

        val hydrated = listOf(
            CodexMessage(
                id = "history-user",
                threadId = threadId,
                role = CodexMessageRole.User,
                text = "Run tests",
                createdAt = Instant.parse("2026-03-22T00:00:00Z"),
                turnId = "turn-1",
                orderIndex = 0,
            ),
            CodexMessage(
                id = "history-assistant",
                threadId = threadId,
                role = CodexMessageRole.Assistant,
                text = "Waiting for output",
                createdAt = Instant.parse("2026-03-22T00:00:01Z"),
                turnId = "turn-1",
                itemId = "assistant-1",
                orderIndex = 1,
            ),
            CodexMessage(
                id = "history-command",
                threadId = threadId,
                role = CodexMessageRole.System,
                kind = CodexMessageKind.CommandExecution,
                text = "completed echo one",
                createdAt = Instant.parse("2026-03-22T00:00:02Z"),
                turnId = "turn-1",
                itemId = "cmd-1",
                orderIndex = 2,
            ),
        )

        val merged = conversation.mergeHydratedThreadMessages(threadId, hydrated)
        val messages = merged.messagesFor(threadId)
        val assistantMessage = messages.first { it.role == CodexMessageRole.Assistant }
        val commandMessage = messages.first { it.kind == CodexMessageKind.CommandExecution }

        assertEquals(3, messages.size)
        assertEquals(
            listOf(
                CodexMessageRole.User to CodexMessageKind.Chat,
                CodexMessageRole.System to CodexMessageKind.CommandExecution,
                CodexMessageRole.Assistant to CodexMessageKind.Chat,
            ),
            messages.map { it.role to it.kind },
        )
        assertTrue(commandMessage.orderIndex < assistantMessage.orderIndex)
        assertEquals("completed echo one", commandMessage.text)
    }

    @Test
    fun mergeHydratedThreadMessagesOmitsStaleThinkingFromProjectedRenderOrder() {
        val threadId = "thread-1"
        val conversation = RemodexConversationState()
            .appendUserMessage(threadId = threadId, text = "Run tests")
            .appendSystemDelta(
                threadId = threadId,
                kind = CodexMessageKind.Thinking,
                delta = "Reasoning",
                turnId = "turn-1",
                itemId = "thinking-1",
            )
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "assistant-1",
                delta = "First answer",
            )
            .appendSystemDelta(
                threadId = threadId,
                kind = CodexMessageKind.CommandExecution,
                delta = "running echo one",
                turnId = "turn-1",
                itemId = "cmd-1",
            )
            .appendAssistantDelta(
                threadId = threadId,
                turnId = "turn-1",
                itemId = "assistant-2",
                delta = "Second answer",
            )

        val hydrated = listOf(
            CodexMessage(
                id = "history-user",
                threadId = threadId,
                role = CodexMessageRole.User,
                text = "Run tests",
                createdAt = Instant.parse("2026-03-22T00:00:00Z"),
                turnId = "turn-1",
                orderIndex = 0,
            ),
            CodexMessage(
                id = "history-thinking",
                threadId = threadId,
                role = CodexMessageRole.System,
                kind = CodexMessageKind.Thinking,
                text = "Reasoning",
                createdAt = Instant.parse("2026-03-22T00:00:01Z"),
                turnId = "turn-1",
                itemId = "thinking-1",
                orderIndex = 1,
            ),
            CodexMessage(
                id = "history-assistant-1",
                threadId = threadId,
                role = CodexMessageRole.Assistant,
                text = "First answer",
                createdAt = Instant.parse("2026-03-22T00:00:02Z"),
                turnId = "turn-1",
                itemId = "assistant-1",
                orderIndex = 2,
            ),
            CodexMessage(
                id = "history-command",
                threadId = threadId,
                role = CodexMessageRole.System,
                kind = CodexMessageKind.CommandExecution,
                text = "completed echo one",
                createdAt = Instant.parse("2026-03-22T00:00:03Z"),
                turnId = "turn-1",
                itemId = "cmd-1",
                orderIndex = 3,
            ),
            CodexMessage(
                id = "history-assistant-2",
                threadId = threadId,
                role = CodexMessageRole.Assistant,
                text = "Second answer",
                createdAt = Instant.parse("2026-03-22T00:00:04Z"),
                turnId = "turn-1",
                itemId = "assistant-2",
                orderIndex = 4,
            ),
        )

        val merged = conversation.mergeHydratedThreadMessages(threadId, hydrated)
        val projected = RemodexTimelineProjector.project(merged.messagesFor(threadId)).messages

        assertEquals(
            listOf("Run tests", "First answer", "completed echo one", "Second answer"),
            projected.map(CodexMessage::text),
        )
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
