package app.remodex.android

import app.remodex.android.core.model.CodexCommandExecutionDetails
import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageDeliveryState
import app.remodex.android.core.model.CodexImageAttachment
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexPlanState
import app.remodex.android.core.model.CodexPlanStep
import app.remodex.android.core.model.CodexStructuredUserInputRequest
import app.remodex.android.core.model.CodexThreadRunBadgeState
import app.remodex.android.core.model.CodexTurnTerminalState
import app.remodex.android.core.protocol.JsonValue
import app.remodex.android.core.transport.RemodexThreadTurnStateSnapshot
import java.time.Instant
import java.util.UUID

data class AssistantCompletionFingerprint(
    val text: String,
    val timestamp: Instant,
)

data class RecentActivityLine(
    val line: String,
    val timestamp: Instant,
)

data class RemodexConversationState(
    val activeThreadId: String? = null,
    val messagesByThread: Map<String, List<CodexMessage>> = emptyMap(),
    val messageRevisionByThread: Map<String, Int> = emptyMap(),
    val activeTurnIdByThread: Map<String, String> = emptyMap(),
    val threadIdByTurnId: Map<String, String> = emptyMap(),
    val runningThreadIds: Set<String> = emptySet(),
    val readyThreadIds: Set<String> = emptySet(),
    val failedThreadIds: Set<String> = emptySet(),
    val loadingThreadIds: Set<String> = emptySet(),
    val hydratedThreadIds: Set<String> = emptySet(),
    private val assistantCompletionFingerprintByThread: Map<String, AssistantCompletionFingerprint> = emptyMap(),
    private val recentActivityLineByThread: Map<String, RecentActivityLine> = emptyMap(),
) {
    fun messagesFor(threadId: String?): List<CodexMessage> {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return emptyList()
        return messagesByThread[normalizedThreadId] ?: emptyList()
    }

    fun visibleMessagesFor(threadId: String?): List<CodexMessage> {
        return messagesFor(threadId).filterNot(RemodexGitTimelineSupport::isHiddenTimelineMessage)
    }

    fun messageRevisionFor(threadId: String?): Int {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return 0
        return messageRevisionByThread[normalizedThreadId] ?: 0
    }

    fun isLoadingThread(threadId: String?): Boolean {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return false
        return loadingThreadIds.contains(normalizedThreadId)
    }

    fun isHydratedThread(threadId: String?): Boolean {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return false
        return hydratedThreadIds.contains(normalizedThreadId)
    }

    fun withActiveThread(threadId: String?): RemodexConversationState {
        return copy(activeThreadId = normalizeThreadId(threadId))
    }

    fun withThreadLoading(threadId: String, isLoading: Boolean): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val updatedLoadingIds = loadingThreadIds.toMutableSet()
        if (isLoading) {
            updatedLoadingIds += normalizedThreadId
        } else {
            updatedLoadingIds -= normalizedThreadId
        }
        return copy(loadingThreadIds = updatedLoadingIds)
    }

    fun withThreadHydrated(threadId: String, isHydrated: Boolean = true): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val updatedHydratedIds = hydratedThreadIds.toMutableSet()
        if (isHydrated) {
            updatedHydratedIds += normalizedThreadId
        } else {
            updatedHydratedIds -= normalizedThreadId
        }
        return copy(hydratedThreadIds = updatedHydratedIds)
    }

    fun markThreadAsViewed(threadId: String): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        return clearOutcomeBadge(normalizedThreadId)
    }

    fun threadRunBadgeState(threadId: String?): CodexThreadRunBadgeState? {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return null
        if (activeTurnIdByThread[normalizedThreadId] != null || runningThreadIds.contains(normalizedThreadId)) {
            return CodexThreadRunBadgeState.Running
        }
        if (failedThreadIds.contains(normalizedThreadId)) {
            return CodexThreadRunBadgeState.Failed
        }
        if (readyThreadIds.contains(normalizedThreadId)) {
            return CodexThreadRunBadgeState.Ready
        }
        return null
    }

    fun markThreadAsRunning(threadId: String): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        return clearOutcomeBadge(normalizedThreadId).copy(
            runningThreadIds = runningThreadIds + normalizedThreadId,
        )
    }

    fun markReadyIfUnread(threadId: String): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val cleared = clearOutcomeBadge(normalizedThreadId).copy(
            runningThreadIds = runningThreadIds - normalizedThreadId,
        )
        if (activeThreadId == normalizedThreadId) {
            return cleared
        }
        return cleared.copy(readyThreadIds = cleared.readyThreadIds + normalizedThreadId)
    }

    fun markFailedIfUnread(threadId: String): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val cleared = clearOutcomeBadge(normalizedThreadId).copy(
            runningThreadIds = runningThreadIds - normalizedThreadId,
        )
        if (activeThreadId == normalizedThreadId) {
            return cleared
        }
        return cleared.copy(failedThreadIds = cleared.failedThreadIds + normalizedThreadId)
    }

    fun withThreadMessages(threadId: String, messages: List<CodexMessage>): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        return replaceThreadMessages(normalizedThreadId, messages)
    }

    fun threadHasActiveOrRunningTurn(threadId: String?): Boolean {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return false
        return activeTurnIdByThread[normalizedThreadId] != null || runningThreadIds.contains(normalizedThreadId)
    }

    fun withTurnStarted(threadId: String, turnId: String?): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId)

        val updatedActiveTurnIds = activeTurnIdByThread.toMutableMap()
        if (normalizedTurnId != null) {
            updatedActiveTurnIds[normalizedThreadId] = normalizedTurnId
        }

        val updatedThreadIdsByTurnId = threadIdByTurnId.toMutableMap()
        if (normalizedTurnId != null) {
            updatedThreadIdsByTurnId[normalizedTurnId] = normalizedThreadId
        }

        return markThreadAsRunning(normalizedThreadId).copy(
            activeTurnIdByThread = updatedActiveTurnIds,
            threadIdByTurnId = updatedThreadIdsByTurnId,
        )
    }

    fun withTurnCompleted(
        threadId: String,
        turnId: String?,
        terminalState: CodexTurnTerminalState = CodexTurnTerminalState.Completed,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId)
        val updatedActiveTurnIds = activeTurnIdByThread.toMutableMap()
        if (normalizedTurnId != null && updatedActiveTurnIds[normalizedThreadId] == normalizedTurnId) {
            updatedActiveTurnIds.remove(normalizedThreadId)
        } else if (normalizedTurnId == null) {
            updatedActiveTurnIds.remove(normalizedThreadId)
        }

        val completedState = copy(
            activeTurnIdByThread = updatedActiveTurnIds,
            runningThreadIds = runningThreadIds - normalizedThreadId,
        )

        return when (terminalState) {
            CodexTurnTerminalState.Completed -> completedState.markReadyIfUnread(normalizedThreadId)
            CodexTurnTerminalState.Failed -> completedState.markFailedIfUnread(normalizedThreadId)
            CodexTurnTerminalState.Stopped -> completedState.clearOutcomeBadge(normalizedThreadId)
        }
    }

    fun markTurnCompleted(
        threadId: String,
        turnId: String?,
        terminalState: CodexTurnTerminalState = CodexTurnTerminalState.Completed,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val resolvedTurnId = normalizeThreadId(turnId) ?: activeTurnIdByThread[normalizedThreadId]
        val completedState = withTurnCompleted(
            threadId = normalizedThreadId,
            turnId = resolvedTurnId,
            terminalState = terminalState,
        )

        val existingMessages = completedState.messagesFor(normalizedThreadId)
        if (existingMessages.isEmpty()) {
            return completedState
        }

        var didMutate = false
        val updatedMessages = mutableListOf<CodexMessage>()
        for (message in existingMessages) {
            var updatedMessage = message

            if (resolvedTurnId != null &&
                updatedMessage.role == CodexMessageRole.Assistant &&
                updatedMessage.isStreaming &&
                updatedMessage.turnId == resolvedTurnId
            ) {
                updatedMessage = updatedMessage.copy(isStreaming = false)
                didMutate = true
            }

            val belongsToTurn = if (resolvedTurnId != null) {
                updatedMessage.turnId == resolvedTurnId || updatedMessage.turnId == null
            } else {
                true
            }
            if (updatedMessage.role == CodexMessageRole.System &&
                updatedMessage.isStreaming &&
                belongsToTurn
            ) {
                updatedMessage = updatedMessage.copy(isStreaming = false)
                didMutate = true
            }

            if (updatedMessage.role == CodexMessageRole.System &&
                updatedMessage.kind == CodexMessageKind.Thinking &&
                belongsToTurn &&
                shouldPruneThinkingRowAfterTurnCompletion(updatedMessage)
            ) {
                didMutate = true
                continue
            }

            updatedMessages += updatedMessage
        }

        return if (didMutate) {
            completedState.replaceThreadMessages(normalizedThreadId, updatedMessages)
        } else {
            completedState
        }
    }

    fun withRefreshedInFlightTurnState(
        threadId: String,
        snapshot: RemodexThreadTurnStateSnapshot,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val updatedActiveTurnIds = activeTurnIdByThread.toMutableMap()
        val updatedThreadIdsByTurnId = threadIdByTurnId.toMutableMap()

        val interruptibleTurnId = normalizeThreadId(snapshot.interruptibleTurnId)
        if (interruptibleTurnId != null) {
            updatedActiveTurnIds[normalizedThreadId] = interruptibleTurnId
            updatedThreadIdsByTurnId[interruptibleTurnId] = normalizedThreadId
            return markThreadAsRunning(normalizedThreadId).copy(
                activeTurnIdByThread = updatedActiveTurnIds,
                threadIdByTurnId = updatedThreadIdsByTurnId,
            )
        }

        if (snapshot.hasInterruptibleTurnWithoutId) {
            updatedActiveTurnIds.remove(normalizedThreadId)
            return markThreadAsRunning(normalizedThreadId).copy(
                activeTurnIdByThread = updatedActiveTurnIds,
                threadIdByTurnId = updatedThreadIdsByTurnId,
            )
        }

        val removedTurnId = updatedActiveTurnIds.remove(normalizedThreadId)
        if (removedTurnId != null && updatedThreadIdsByTurnId[removedTurnId] == normalizedThreadId) {
            updatedThreadIdsByTurnId.remove(removedTurnId)
        }

        return copy(
            activeTurnIdByThread = updatedActiveTurnIds,
            threadIdByTurnId = updatedThreadIdsByTurnId,
            runningThreadIds = runningThreadIds - normalizedThreadId,
        )
    }

    fun mergeHydratedThreadMessages(threadId: String, historyMessages: List<CodexMessage>): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        if (historyMessages.isEmpty()) {
            return withThreadHydrated(normalizedThreadId)
        }

        val existingMessages = messagesFor(normalizedThreadId)
        val sortedHistory = sortHistoryMessages(historyMessages)
        if (existingMessages.isEmpty()) {
            return replaceThreadMessages(normalizedThreadId, sortedHistory.withSequentialOrderIndices())
                .withThreadHydrated(normalizedThreadId)
        }

        val preservesRunningPresentation = threadHasActiveOrRunningTurn(normalizedThreadId)
        val matchedExistingIndices = mutableSetOf<Int>()
        val mergedMessages = mutableListOf<CodexMessage>()

        for (historyMessage in sortedHistory) {
            val existingIndex = findHydrationMatchIndex(existingMessages, historyMessage, matchedExistingIndices)
            if (existingIndex >= 0) {
                matchedExistingIndices += existingIndex
                mergedMessages += reconcileHydratedMessage(
                    localMessage = existingMessages[existingIndex],
                    historyMessage = historyMessage,
                    preservesRunningPresentation = preservesRunningPresentation,
                )
            } else {
                mergedMessages += historyMessage
            }
        }

        for ((index, existingMessage) in existingMessages.withIndex()) {
            if (index in matchedExistingIndices) {
                continue
            }
            if (mergedMessages.none { sameHydrationIdentity(it, existingMessage) }) {
                mergedMessages += existingMessage
            }
        }

        return replaceThreadMessages(
            normalizedThreadId,
            mergedMessages.sortedBy(CodexMessage::orderIndex),
        )
            .withThreadHydrated(normalizedThreadId)
    }

    fun appendUserMessage(
        threadId: String,
        text: String,
        messageId: String = UUID.randomUUID().toString(),
        turnId: String? = null,
        deliveryState: CodexMessageDeliveryState = CodexMessageDeliveryState.Pending,
        attachments: List<CodexImageAttachment> = emptyList(),
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val trimmedText = text.trim()
        if (trimmedText.isEmpty() && attachments.isEmpty()) {
            return this
        }

        val existingMessages = messagesFor(normalizedThreadId)
        val nextMessages = existingMessages + CodexMessage(
            id = messageId,
            threadId = normalizedThreadId,
            role = CodexMessageRole.User,
            text = trimmedText,
            createdAt = Instant.now(),
            turnId = normalizeThreadId(turnId),
            deliveryState = deliveryState,
            attachments = attachments,
            orderIndex = nextOrderIndex(existingMessages),
        )
        return replaceThreadMessages(normalizedThreadId, nextMessages)
    }

    fun markMessageDeliveryState(
        threadId: String,
        messageId: String,
        deliveryState: CodexMessageDeliveryState,
        turnId: String? = null,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        if (messageId.isBlank()) {
            return this
        }

        val existingMessages = messagesFor(normalizedThreadId)
        val messageIndex = existingMessages.indexOfLast { it.id == messageId }
        if (messageIndex < 0) {
            return this
        }

        val updatedMessages = existingMessages.toMutableList()
        val existingMessage = updatedMessages[messageIndex]
        updatedMessages[messageIndex] = existingMessage.copy(
            deliveryState = deliveryState,
            turnId = existingMessage.turnId ?: normalizeThreadId(turnId),
        )
        return replaceThreadMessages(normalizedThreadId, updatedMessages)
            .withThreadTurnMapping(normalizedThreadId, normalizeThreadId(turnId))
    }

    fun removeMessage(
        threadId: String,
        messageId: String,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        if (messageId.isBlank()) {
            return this
        }

        val existingMessages = messagesFor(normalizedThreadId)
        val filteredMessages = existingMessages.filterNot { it.id == messageId }
        if (filteredMessages.size == existingMessages.size) {
            return this
        }

        return replaceThreadMessages(
            normalizedThreadId,
            filteredMessages.withSequentialOrderIndices(),
        )
    }

    fun moveMessageToThread(
        sourceThreadId: String,
        targetThreadId: String,
        messageId: String,
    ): RemodexConversationState {
        val normalizedSourceThreadId = normalizeThreadId(sourceThreadId) ?: return this
        val normalizedTargetThreadId = normalizeThreadId(targetThreadId) ?: return this
        if (normalizedSourceThreadId == normalizedTargetThreadId || messageId.isBlank()) {
            return this
        }

        val sourceMessages = messagesFor(normalizedSourceThreadId)
        val sourceIndex = sourceMessages.indexOfLast { it.id == messageId }
        if (sourceIndex < 0) {
            return this
        }

        val movedMessage = sourceMessages[sourceIndex]
        val nextSourceMessages = sourceMessages.toMutableList().apply { removeAt(sourceIndex) }
        val targetMessages = messagesFor(normalizedTargetThreadId)
        val nextTargetMessages = targetMessages + movedMessage.copy(
            threadId = normalizedTargetThreadId,
            orderIndex = nextOrderIndex(targetMessages),
        )

        return replaceThreadMessages(normalizedSourceThreadId, nextSourceMessages.withSequentialOrderIndices())
            .replaceThreadMessages(normalizedTargetThreadId, nextTargetMessages.withSequentialOrderIndices())
    }

    fun beginAssistantMessage(threadId: String, turnId: String, itemId: String? = null): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId) ?: return this
        val normalizedItemId = normalizeThreadId(itemId)
        val existingMessages = messagesFor(normalizedThreadId)
        val turnMessageIndex = existingMessages.indexOfLast { message ->
            message.role == CodexMessageRole.Assistant &&
                message.turnId == normalizedTurnId &&
                message.isStreaming
        }

        if (normalizedItemId != null) {
            val directItemIndex = existingMessages.indexOfLast { message ->
                message.role == CodexMessageRole.Assistant && message.itemId == normalizedItemId
            }
            if (directItemIndex >= 0) {
                val updatedMessages = existingMessages.toMutableList()
                val existingMessage = updatedMessages[directItemIndex]
                updatedMessages[directItemIndex] = existingMessage.copy(
                    isStreaming = true,
                    turnId = existingMessage.turnId ?: normalizedTurnId,
                )
                return replaceThreadMessages(normalizedThreadId, updatedMessages)
                    .withTurnStarted(normalizedThreadId, normalizedTurnId)
            }
        }

        if (turnMessageIndex >= 0) {
            val updatedMessages = existingMessages.toMutableList()
            val existingMessage = updatedMessages[turnMessageIndex]
            val existingItemId = normalizeThreadId(existingMessage.itemId)

            if (normalizedItemId == null) {
                updatedMessages[turnMessageIndex] = existingMessage.copy(isStreaming = true)
                return replaceThreadMessages(normalizedThreadId, updatedMessages)
                    .withTurnStarted(normalizedThreadId, normalizedTurnId)
            }

            if (existingItemId == null) {
                updatedMessages[turnMessageIndex] = existingMessage.copy(
                    isStreaming = true,
                    itemId = normalizedItemId,
                )
                return replaceThreadMessages(normalizedThreadId, updatedMessages)
                    .withTurnStarted(normalizedThreadId, normalizedTurnId)
            }

            if (existingItemId == normalizedItemId) {
                updatedMessages[turnMessageIndex] = existingMessage.copy(isStreaming = true)
                return replaceThreadMessages(normalizedThreadId, updatedMessages)
                    .withTurnStarted(normalizedThreadId, normalizedTurnId)
            }

            updatedMessages[turnMessageIndex] = existingMessage.copy(isStreaming = false)
            val nextMessages = updatedMessages + CodexMessage(
                id = UUID.randomUUID().toString(),
                threadId = normalizedThreadId,
                role = CodexMessageRole.Assistant,
                text = "",
                createdAt = Instant.now(),
                turnId = normalizedTurnId,
                itemId = normalizedItemId,
                isStreaming = true,
                orderIndex = nextOrderIndex(updatedMessages),
            )
            return replaceThreadMessages(normalizedThreadId, nextMessages)
                .withTurnStarted(normalizedThreadId, normalizedTurnId)
        }

        val nextMessages = existingMessages + CodexMessage(
            id = UUID.randomUUID().toString(),
            threadId = normalizedThreadId,
            role = CodexMessageRole.Assistant,
            text = "",
            createdAt = Instant.now(),
            turnId = normalizedTurnId,
            itemId = normalizedItemId,
            isStreaming = true,
            orderIndex = nextOrderIndex(existingMessages),
        )
        return replaceThreadMessages(normalizedThreadId, nextMessages)
            .withTurnStarted(normalizedThreadId, normalizedTurnId)
    }

    fun appendAssistantDelta(
        threadId: String,
        turnId: String,
        itemId: String? = null,
        delta: String,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId) ?: return this
        if (delta.isBlank()) {
            return this
        }

        val stateWithPlaceholder = beginAssistantMessage(
            threadId = normalizedThreadId,
            turnId = normalizedTurnId,
            itemId = itemId,
        )
        val existingMessages = stateWithPlaceholder.messagesFor(normalizedThreadId)
        val targetIndex = findAssistantMessageIndex(
            threadMessages = existingMessages,
            turnId = normalizedTurnId,
            itemId = itemId,
        )
        if (targetIndex < 0) {
            return stateWithPlaceholder
        }

        val updatedMessages = existingMessages.toMutableList()
        val targetMessage = updatedMessages[targetIndex]
        updatedMessages[targetIndex] = targetMessage.copy(
            text = mergeAssistantDelta(
                existingText = targetMessage.text,
                incomingDelta = delta,
            ),
            isStreaming = true,
            itemId = targetMessage.itemId ?: normalizeThreadId(itemId),
        )

        return stateWithPlaceholder.replaceThreadMessages(normalizedThreadId, updatedMessages)
            .withTurnStarted(normalizedThreadId, normalizedTurnId)
    }

    fun completeAssistantMessage(
        threadId: String,
        turnId: String?,
        itemId: String? = null,
        text: String,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId)
        val normalizedItemId = normalizeThreadId(itemId)
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) {
            return this
        }

        val resolvedTurnId = normalizedTurnId ?: activeTurnIdByThread[normalizedThreadId]
        val existingFingerprint = assistantCompletionFingerprintByThread[normalizedThreadId]
        if (resolvedTurnId == null &&
            normalizedItemId == null &&
            existingFingerprint?.text == trimmedText &&
            existingFingerprint.timestamp.plusSeconds(45).isAfter(Instant.now())
        ) {
            return this
        }

        val existingMessages = messagesFor(normalizedThreadId)
        val existingIndex = findAssistantMessageIndex(existingMessages, resolvedTurnId, normalizedItemId)
        if (existingIndex >= 0) {
            val updatedMessages = existingMessages.toMutableList()
            val existingMessage = updatedMessages[existingIndex]
            updatedMessages[existingIndex] = existingMessage.copy(
                text = trimmedText,
                isStreaming = false,
                turnId = existingMessage.turnId ?: resolvedTurnId,
                itemId = existingMessage.itemId ?: normalizedItemId,
            )
            return replaceThreadMessages(normalizedThreadId, updatedMessages)
                .withThreadTurnMapping(normalizedThreadId, resolvedTurnId)
                .withAssistantCompletionFingerprint(normalizedThreadId, trimmedText)
        }

        if (normalizedItemId != null) {
            val existingItemIndex = existingMessages.indexOfLast { candidate ->
                candidate.role == CodexMessageRole.Assistant && candidate.itemId == normalizedItemId
            }
            if (existingItemIndex >= 0) {
                val updatedMessages = existingMessages.toMutableList()
                val existingMessage = updatedMessages[existingItemIndex]
                updatedMessages[existingItemIndex] = existingMessage.copy(
                    text = trimmedText,
                    isStreaming = false,
                    turnId = existingMessage.turnId ?: resolvedTurnId,
                )
                return replaceThreadMessages(normalizedThreadId, updatedMessages)
                    .withThreadTurnMapping(normalizedThreadId, resolvedTurnId)
                    .withAssistantCompletionFingerprint(normalizedThreadId, trimmedText)
            }
        }

        val duplicateIndex = existingMessages.indexOfLast { candidate ->
            candidate.role == CodexMessageRole.Assistant &&
                normalizedMessageText(candidate.text) == trimmedText &&
                (
                    candidate.isStreaming ||
                        (resolvedTurnId != null && candidate.turnId == resolvedTurnId) ||
                        (normalizedItemId != null && candidate.itemId == normalizedItemId)
                    )
        }
        if (duplicateIndex >= 0) {
            val updatedMessages = existingMessages.toMutableList()
            val existingMessage = updatedMessages[duplicateIndex]
            updatedMessages[duplicateIndex] = existingMessage.copy(
                isStreaming = false,
                turnId = existingMessage.turnId ?: resolvedTurnId,
                itemId = existingMessage.itemId ?: normalizedItemId,
            )
            return replaceThreadMessages(normalizedThreadId, updatedMessages)
                .withThreadTurnMapping(normalizedThreadId, resolvedTurnId)
                .withAssistantCompletionFingerprint(normalizedThreadId, trimmedText)
        }

        val nextMessages = existingMessages + CodexMessage(
            id = UUID.randomUUID().toString(),
            threadId = normalizedThreadId,
            role = CodexMessageRole.Assistant,
            text = trimmedText,
            createdAt = Instant.now(),
            turnId = resolvedTurnId,
            itemId = normalizedItemId,
            isStreaming = false,
            orderIndex = nextOrderIndex(existingMessages),
        )
        return replaceThreadMessages(normalizedThreadId, nextMessages)
            .withThreadTurnMapping(normalizedThreadId, resolvedTurnId)
            .withAssistantCompletionFingerprint(normalizedThreadId, trimmedText)
    }

    fun upsertSystemMessage(
        threadId: String,
        kind: CodexMessageKind,
        text: String,
        turnId: String? = null,
        itemId: String? = null,
        isStreaming: Boolean = false,
        commandExecutionDetails: CodexCommandExecutionDetails? = null,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) {
            return this
        }

        val normalizedTurnId = normalizeThreadId(turnId)
        val normalizedItemId = normalizeThreadId(itemId)
        val resolvedTurnId = normalizedTurnId ?: activeTurnIdByThread[normalizedThreadId]
        val syntheticItemId = resolvedTurnId?.let { syntheticStreamingItemId(it, kind) }
        val incomingFileChangePathKeys = if (kind == CodexMessageKind.FileChange) {
            normalizedFileChangePathKeys(text)
        } else {
            emptySet()
        }
        val incomingCommandKey = if (kind == CodexMessageKind.CommandExecution) {
            commandExecutionKey(text, commandExecutionDetails)
        } else {
            null
        }
        val existingMessages = messagesFor(normalizedThreadId)
        val existingIndex = findSystemMessageIndex(
            threadMessages = existingMessages,
            kind = kind,
            turnId = resolvedTurnId,
            itemId = normalizedItemId,
            syntheticItemId = syntheticItemId,
            fileChangePathKeys = incomingFileChangePathKeys,
            commandKey = incomingCommandKey,
            incomingText = text,
        )
        if (existingIndex >= 0) {
            val updatedMessages = existingMessages.toMutableList()
            val existingMessage = updatedMessages[existingIndex]
            val existingTrimmedText = existingMessage.text.trim()
            val nextText = when {
                kind == CodexMessageKind.CommandExecution -> trimmedText
                isPlaceholderText(trimmedText, kind) &&
                    existingTrimmedText.isNotEmpty() &&
                    !isPlaceholderText(existingTrimmedText, kind) -> existingMessage.text
                isPlaceholderText(existingTrimmedText, kind) -> text
                !isStreaming || (kind == CodexMessageKind.FileChange && isFileChangeSnapshotPayload(trimmedText)) -> text
                else -> mergeAssistantDelta(existingMessage.text, text)
            }

            updatedMessages[existingIndex] = existingMessage.copy(
                kind = kind,
                text = nextText,
                turnId = existingMessage.turnId ?: resolvedTurnId,
                itemId = when {
                    existingMessage.itemId == syntheticItemId && normalizedItemId != null -> normalizedItemId
                    existingMessage.itemId == null -> normalizedItemId ?: syntheticItemId
                    else -> existingMessage.itemId
                },
                isStreaming = isStreaming,
                commandExecutionDetails = when (kind) {
                    CodexMessageKind.CommandExecution -> commandExecutionDetails ?: existingMessage.commandExecutionDetails
                    else -> existingMessage.commandExecutionDetails
                },
                orderIndex = existingMessage.orderIndex,
            )
            val prunedMessages = pruneDuplicateSystemRows(
                threadMessages = updatedMessages,
                keepMessageId = updatedMessages[existingIndex].id,
                kind = kind,
                turnId = resolvedTurnId,
                fileChangePathKeys = incomingFileChangePathKeys,
                commandKey = incomingCommandKey,
            )
            val sortedMessages = prunedMessages.sortedBy(CodexMessage::orderIndex)
            return replaceThreadMessages(normalizedThreadId, sortedMessages)
                .withThreadTurnMapping(normalizedThreadId, resolvedTurnId)
        }

        val nextMessages = existingMessages + CodexMessage(
            id = UUID.randomUUID().toString(),
            threadId = normalizedThreadId,
            role = CodexMessageRole.System,
            kind = kind,
            text = text,
            createdAt = Instant.now(),
            turnId = resolvedTurnId,
            itemId = normalizedItemId ?: syntheticItemId,
            isStreaming = isStreaming,
            commandExecutionDetails = commandExecutionDetails,
            orderIndex = nextOrderIndex(existingMessages),
        )
        return replaceThreadMessages(normalizedThreadId, nextMessages)
            .withThreadTurnMapping(normalizedThreadId, resolvedTurnId)
    }

    fun appendSystemMessage(
        threadId: String,
        kind: CodexMessageKind,
        text: String,
        turnId: String? = null,
        itemId: String? = null,
        isStreaming: Boolean = false,
        commandExecutionDetails: CodexCommandExecutionDetails? = null,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) {
            return this
        }

        val existingMessages = messagesFor(normalizedThreadId)
        val nextMessages = existingMessages + CodexMessage(
            id = UUID.randomUUID().toString(),
            threadId = normalizedThreadId,
            role = CodexMessageRole.System,
            kind = kind,
            text = trimmedText,
            createdAt = Instant.now(),
            turnId = normalizeThreadId(turnId),
            itemId = normalizeThreadId(itemId),
            isStreaming = isStreaming,
            commandExecutionDetails = commandExecutionDetails,
            orderIndex = nextOrderIndex(existingMessages),
        )
        return replaceThreadMessages(normalizedThreadId, nextMessages)
            .withThreadTurnMapping(normalizedThreadId, normalizeThreadId(turnId))
    }

    fun appendSystemDelta(
        threadId: String,
        kind: CodexMessageKind,
        delta: String,
        turnId: String? = null,
        itemId: String? = null,
        placeholderText: String = streamingPlaceholderText(kind),
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId)
        val normalizedItemId = normalizeThreadId(itemId)
        val trimmedDelta = delta.trim()
        if (kind == CodexMessageKind.Thinking) {
            val resolvedTurnId = normalizedTurnId ?: activeTurnIdByThread[normalizedThreadId]
            if (!isTurnActiveForThinkingActivity(normalizedThreadId, resolvedTurnId)) {
                return mergeLateReasoningDeltaIfPossible(
                    threadId = normalizedThreadId,
                    turnId = resolvedTurnId,
                    itemId = normalizedItemId,
                    delta = delta,
                )
            }
        }

        return upsertSystemMessage(
            threadId = normalizedThreadId,
            kind = kind,
            text = if (trimmedDelta.isEmpty()) placeholderText else delta,
            turnId = normalizedTurnId,
            itemId = normalizedItemId,
            isStreaming = true,
        )
    }

    fun completeSystemMessage(
        threadId: String,
        kind: CodexMessageKind,
        text: String,
        turnId: String? = null,
        itemId: String? = null,
        commandExecutionDetails: CodexCommandExecutionDetails? = null,
    ): RemodexConversationState {
        return upsertSystemMessage(
            threadId = threadId,
            kind = kind,
            text = text,
            turnId = turnId,
            itemId = itemId,
            isStreaming = false,
            commandExecutionDetails = commandExecutionDetails,
        )
    }

    fun upsertPlanMessage(
        threadId: String,
        turnId: String?,
        itemId: String?,
        text: String? = null,
        explanation: String? = null,
        steps: List<CodexPlanStep>? = null,
        isStreaming: Boolean,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId)
        val normalizedItemId = normalizeThreadId(itemId)
        val messageText = when {
            !text.isNullOrBlank() -> text
            isStreaming -> streamingPlaceholderText(CodexMessageKind.Plan)
            !explanation.isNullOrBlank() -> explanation
            else -> return this
        }

        val stateWithMessage = upsertSystemMessage(
            threadId = normalizedThreadId,
            kind = CodexMessageKind.Plan,
            text = messageText,
            turnId = normalizedTurnId,
            itemId = normalizedItemId,
            isStreaming = isStreaming,
        )
        val messageIndex = findLatestPlanMessageIndex(
            threadMessages = stateWithMessage.messagesFor(normalizedThreadId),
            turnId = normalizedTurnId,
            itemId = normalizedItemId,
        ) ?: return stateWithMessage

        val existingMessages = stateWithMessage.messagesFor(normalizedThreadId)
        val updatedMessages = existingMessages.toMutableList()
        val existingMessage = updatedMessages[messageIndex]
        var planState = existingMessage.planState ?: CodexPlanState()
        if (explanation != null) {
            planState = planState.copy(
                explanation = explanation.trim().takeIf(String::isNotEmpty),
            )
        }
        if (steps != null) {
            planState = planState.copy(steps = steps)
        }

        updatedMessages[messageIndex] = existingMessage.copy(
            text = if (!text.isNullOrBlank()) {
                text
            } else {
                existingMessage.text
            },
            turnId = existingMessage.turnId ?: normalizedTurnId,
            itemId = existingMessage.itemId ?: normalizedItemId,
            isStreaming = isStreaming,
            planState = planState,
        )
        return stateWithMessage.replaceThreadMessages(normalizedThreadId, updatedMessages)
    }

    fun upsertStructuredUserInputPrompt(
        threadId: String,
        turnId: String?,
        itemId: String,
        request: CodexStructuredUserInputRequest,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId)
        val normalizedItemId = normalizeThreadId(itemId) ?: return this
        if (request.questions.isEmpty()) {
            return this
        }

        val fallbackText = request.questions.joinToString(separator = "\n\n") { question ->
            val header = question.header.trim()
            val prompt = question.question.trim()
            if (header.isEmpty()) {
                prompt
            } else {
                "$header\n$prompt"
            }
        }.trim()
        if (fallbackText.isEmpty()) {
            return this
        }

        val existingMessages = messagesFor(normalizedThreadId)
        val existingIndex = existingMessages.indexOfLast { message ->
            message.role == CodexMessageRole.System &&
                message.kind == CodexMessageKind.UserInputPrompt &&
                message.structuredUserInputRequest?.requestID == request.requestID
        }
        if (existingIndex >= 0) {
            val updatedMessages = existingMessages.toMutableList()
            val existingMessage = updatedMessages[existingIndex]
            updatedMessages[existingIndex] = existingMessage.copy(
                text = fallbackText,
                turnId = normalizedTurnId ?: existingMessage.turnId,
                itemId = normalizedItemId,
                isStreaming = false,
                structuredUserInputRequest = request,
            )
            return replaceThreadMessages(normalizedThreadId, updatedMessages)
                .withThreadTurnMapping(normalizedThreadId, normalizedTurnId)
        }

        val nextMessages = existingMessages + CodexMessage(
            id = UUID.randomUUID().toString(),
            threadId = normalizedThreadId,
            role = CodexMessageRole.System,
            kind = CodexMessageKind.UserInputPrompt,
            text = fallbackText,
            createdAt = Instant.now(),
            turnId = normalizedTurnId,
            itemId = normalizedItemId,
            isStreaming = false,
            structuredUserInputRequest = request,
            orderIndex = nextOrderIndex(existingMessages),
        )
        return replaceThreadMessages(normalizedThreadId, nextMessages)
            .withThreadTurnMapping(normalizedThreadId, normalizedTurnId)
    }

    fun removeStructuredUserInputPrompt(
        requestID: JsonValue,
        threadIdHint: String? = null,
    ): RemodexConversationState {
        val threadIds = normalizeThreadId(threadIdHint)?.let(::listOf) ?: messagesByThread.keys
        var nextState = this
        var didMutate = false

        for (threadId in threadIds) {
            val existingMessages = nextState.messagesFor(threadId)
            if (existingMessages.isEmpty()) {
                continue
            }

            val filteredMessages = existingMessages.filterNot { message ->
                message.kind == CodexMessageKind.UserInputPrompt &&
                    message.structuredUserInputRequest?.requestID == requestID
            }
            if (filteredMessages.size == existingMessages.size) {
                continue
            }

            nextState = nextState.replaceThreadMessages(
                threadId = threadId,
                messages = filteredMessages.sortedBy(CodexMessage::orderIndex),
            )
            didMutate = true
        }

        return if (didMutate) nextState else this
    }

    fun completeStreamingSystemMessages(threadId: String, turnId: String? = null): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId)
        val existingMessages = messagesFor(normalizedThreadId)
        var didMutate = false
        val updatedMessages = existingMessages.map { message ->
            val matchesTurn = normalizedTurnId == null || message.turnId == normalizedTurnId
            if (message.role == CodexMessageRole.System && message.isStreaming && matchesTurn) {
                didMutate = true
                message.copy(isStreaming = false)
            } else {
                message
            }
        }
        if (!didMutate) {
            return this
        }
        return replaceThreadMessages(normalizedThreadId, updatedMessages)
    }

    fun appendThinkingActivityLine(
        threadId: String,
        turnId: String?,
        line: String,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId)
        val trimmedLine = line.trim()
        if (trimmedLine.isEmpty()) {
            return this
        }

        val dedupeKey = "$normalizedThreadId|${normalizedTurnId ?: "no-turn"}"
        val now = Instant.now()
        val previous = recentActivityLineByThread[dedupeKey]
        if (previous != null &&
            previous.line.equals(trimmedLine, ignoreCase = true) &&
            java.time.Duration.between(previous.timestamp, now).seconds <= 4
        ) {
            return this
        }

        val isTurnActive = isTurnActiveForThinkingActivity(normalizedThreadId, normalizedTurnId)
        val existingMessages = messagesFor(normalizedThreadId)
        val targetIndex = thinkingActivityTargetIndex(existingMessages, normalizedTurnId)

        if (!isTurnActive && targetIndex == null && normalizedTurnId == null) {
            return this
        }

        val nextState = if (targetIndex != null) {
            val existingText = existingMessages[targetIndex].text.trim()
            if (containsCaseInsensitiveLine(trimmedLine, existingText)) {
                this
            } else {
                val updatedMessages = existingMessages.toMutableList()
                val targetMessage = updatedMessages[targetIndex]
                updatedMessages[targetIndex] = targetMessage.copy(
                    text = if (existingText.isEmpty()) {
                        trimmedLine
                    } else {
                        "$existingText\n$trimmedLine"
                    },
                    isStreaming = targetMessage.isStreaming || isTurnActive,
                    turnId = targetMessage.turnId ?: normalizedTurnId,
                )
                replaceThreadMessages(normalizedThreadId, updatedMessages)
                    .withThreadTurnMapping(normalizedThreadId, normalizedTurnId)
            }
        } else {
            appendSystemMessage(
                threadId = normalizedThreadId,
                kind = CodexMessageKind.Thinking,
                text = trimmedLine,
                turnId = normalizedTurnId,
                isStreaming = isTurnActive,
            )
        }

        return nextState.withRecentActivityLine(dedupeKey, trimmedLine, now)
    }

    fun appendActivityLine(
        threadId: String,
        turnId: String?,
        line: String,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId)
        val trimmedLine = line.trim()
        if (trimmedLine.isEmpty()) {
            return this
        }

        val dedupeKey = "$normalizedThreadId|${normalizedTurnId ?: "no-turn"}|activity"
        val now = Instant.now()
        val previous = recentActivityLineByThread[dedupeKey]
        if (previous != null &&
            previous.line.equals(trimmedLine, ignoreCase = true) &&
            java.time.Duration.between(previous.timestamp, now).seconds <= 4
        ) {
            return this
        }

        val resolvedTurnId = normalizedTurnId ?: activeTurnIdByThread[normalizedThreadId]
        val isTurnActive = isTurnActiveForThinkingActivity(normalizedThreadId, resolvedTurnId)
        if (!isTurnActive && resolvedTurnId == null) {
            return this
        }

        val nextState = appendSystemMessage(
            threadId = normalizedThreadId,
            kind = CodexMessageKind.Activity,
            text = trimmedLine,
            turnId = resolvedTurnId,
            isStreaming = isTurnActive,
        )

        return nextState.withRecentActivityLine(dedupeKey, trimmedLine, now)
    }

    fun mergeLateReasoningDeltaIfPossible(
        threadId: String,
        turnId: String?,
        itemId: String?,
        delta: String,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val trimmedDelta = delta.trim()
        if (trimmedDelta.isEmpty()) {
            return this
        }

        val normalizedTurnId = normalizeThreadId(turnId)
        val normalizedItemId = normalizeThreadId(itemId)
        val existingMessages = messagesFor(normalizedThreadId)
        if (existingMessages.isEmpty()) {
            return this
        }

        val targetIndex = when {
            normalizedItemId != null -> existingMessages.indices.reversed().firstOrNull { index ->
                val candidate = existingMessages[index]
                candidate.role == CodexMessageRole.System &&
                    candidate.kind == CodexMessageKind.Thinking &&
                    candidate.itemId == normalizedItemId
            }

            normalizedTurnId != null -> existingMessages.indices.reversed().firstOrNull { index ->
                val candidate = existingMessages[index]
                candidate.role == CodexMessageRole.System &&
                    candidate.kind == CodexMessageKind.Thinking &&
                    candidate.turnId == normalizedTurnId
            }

            else -> null
        } ?: return this

        val updatedMessages = existingMessages.toMutableList()
        val targetMessage = updatedMessages[targetIndex]
        updatedMessages[targetIndex] = targetMessage.copy(
            text = mergeAssistantDelta(
                existingText = targetMessage.text,
                incomingDelta = delta,
            ),
            isStreaming = false,
            turnId = targetMessage.turnId ?: normalizedTurnId,
            itemId = targetMessage.itemId ?: normalizedItemId,
        )
        return replaceThreadMessages(normalizedThreadId, updatedMessages)
            .withThreadTurnMapping(normalizedThreadId, normalizedTurnId)
    }

    private fun isTurnActiveForThinkingActivity(threadId: String, turnId: String?): Boolean {
        if (turnId != null) {
            if (activeTurnIdByThread[threadId] == turnId) {
                return true
            }
            return activeTurnIdByThread[threadId] == null && runningThreadIds.contains(threadId)
        }
        return activeTurnIdByThread[threadId] != null || runningThreadIds.contains(threadId)
    }

    private fun thinkingActivityTargetIndex(messages: List<CodexMessage>, turnId: String?): Int? {
        return messages.indices.reversed().firstOrNull { index ->
            val candidate = messages[index]
            if (candidate.role != CodexMessageRole.System || candidate.kind != CodexMessageKind.Thinking) {
                return@firstOrNull false
            }

            if (turnId != null) {
                candidate.turnId == turnId || candidate.turnId == null
            } else {
                candidate.isStreaming
            }
        }
    }

    private fun containsCaseInsensitiveLine(candidateLine: String, text: String): Boolean {
        return text.lineSequence().any { line ->
            line.trim().equals(candidateLine, ignoreCase = true)
        }
    }

    fun pruneToThreads(threadIds: Set<String>): RemodexConversationState {
        val validThreadIds = threadIds.mapNotNull(::normalizeThreadId).toSet()
        val nextActiveThreadId = activeThreadId?.takeIf(validThreadIds::contains)
        val filteredThreadIdByTurnId = threadIdByTurnId.filterValues(validThreadIds::contains)

        return copy(
            activeThreadId = nextActiveThreadId,
            messagesByThread = messagesByThread.filterKeys(validThreadIds::contains),
            messageRevisionByThread = messageRevisionByThread.filterKeys(validThreadIds::contains),
            activeTurnIdByThread = activeTurnIdByThread.filterKeys(validThreadIds::contains),
            threadIdByTurnId = filteredThreadIdByTurnId,
            runningThreadIds = runningThreadIds.filter(validThreadIds::contains).toSet(),
            readyThreadIds = readyThreadIds.filter(validThreadIds::contains).toSet(),
            failedThreadIds = failedThreadIds.filter(validThreadIds::contains).toSet(),
            loadingThreadIds = loadingThreadIds.filter(validThreadIds::contains).toSet(),
            hydratedThreadIds = hydratedThreadIds.filter(validThreadIds::contains).toSet(),
            assistantCompletionFingerprintByThread = assistantCompletionFingerprintByThread.filterKeys(validThreadIds::contains),
            recentActivityLineByThread = recentActivityLineByThread.filterKeys { key ->
                validThreadIds.any { threadId -> key.startsWith("$threadId|") }
            },
        )
    }

    fun handleMissingThread(threadId: String): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val updatedActiveTurnIds = activeTurnIdByThread.toMutableMap()
        updatedActiveTurnIds.remove(normalizedThreadId)
        val updatedThreadIdsByTurnId = threadIdByTurnId.filterValues { it != normalizedThreadId }

        val clearedState = clearOutcomeBadge(normalizedThreadId).copy(
            activeTurnIdByThread = updatedActiveTurnIds,
            threadIdByTurnId = updatedThreadIdsByTurnId,
            runningThreadIds = runningThreadIds - normalizedThreadId,
            loadingThreadIds = loadingThreadIds - normalizedThreadId,
            hydratedThreadIds = hydratedThreadIds - normalizedThreadId,
            assistantCompletionFingerprintByThread = assistantCompletionFingerprintByThread - normalizedThreadId,
            recentActivityLineByThread = recentActivityLineByThread.filterKeys { key ->
                !key.startsWith("$normalizedThreadId|")
            },
        )

        val existingMessages = messagesFor(normalizedThreadId)
        if (existingMessages.none(CodexMessage::isStreaming)) {
            return clearedState
        }

        val updatedMessages = existingMessages.map { message ->
            if (message.isStreaming) {
                message.copy(isStreaming = false)
            } else {
                message
            }
        }
        return clearedState.replaceThreadMessages(normalizedThreadId, updatedMessages)
    }

    companion object {
        private fun normalizeThreadId(threadId: String?): String? {
            val trimmed = threadId?.trim().orEmpty()
            return trimmed.ifEmpty { null }
        }

        private fun nextOrderIndex(messages: List<CodexMessage>): Int {
            return (messages.maxOfOrNull(CodexMessage::orderIndex) ?: -1) + 1
        }

        private fun sortHistoryMessages(messages: List<CodexMessage>): List<CodexMessage> {
            return messages.sortedWith(
                compareBy<CodexMessage> { it.createdAt ?: Instant.EPOCH }
                    .thenBy(CodexMessage::orderIndex),
            )
        }

        private fun findAssistantMessageIndex(
            threadMessages: List<CodexMessage>,
            turnId: String?,
            itemId: String?,
        ): Int {
            val normalizedTurnId = normalizeThreadId(turnId)
            val normalizedItemId = normalizeThreadId(itemId)

            if (normalizedItemId != null) {
                val itemIndex = threadMessages.indexOfLast { message ->
                    message.role == CodexMessageRole.Assistant && message.itemId == normalizedItemId
                }
                if (itemIndex >= 0) {
                    return itemIndex
                }
            }

            if (normalizedTurnId != null) {
                return threadMessages.indexOfLast { message ->
                    message.role == CodexMessageRole.Assistant &&
                        message.turnId == normalizedTurnId &&
                        (message.isStreaming || normalizedItemId == null)
                }
            }

            return -1
        }

        private fun findLatestPlanMessageIndex(
            threadMessages: List<CodexMessage>,
            turnId: String?,
            itemId: String?,
        ): Int? {
            val index = findSystemMessageIndex(
                threadMessages = threadMessages,
                kind = CodexMessageKind.Plan,
                turnId = turnId,
                itemId = itemId,
            )
            return index.takeIf { it >= 0 }
        }

        private fun findSystemMessageIndex(
            threadMessages: List<CodexMessage>,
            kind: CodexMessageKind,
            turnId: String?,
            itemId: String?,
            syntheticItemId: String? = null,
            fileChangePathKeys: Set<String> = emptySet(),
            commandKey: String? = null,
            incomingText: String? = null,
        ): Int {
            val normalizedTurnId = normalizeThreadId(turnId)
            val normalizedItemId = normalizeThreadId(itemId)
            val normalizedSyntheticItemId = normalizeThreadId(syntheticItemId)

            if (normalizedItemId != null) {
                val itemIndex = threadMessages.indexOfLast { message ->
                    message.role == CodexMessageRole.System &&
                        message.kind == kind &&
                        message.itemId == normalizedItemId
                }
                if (itemIndex >= 0) {
                    return itemIndex
                }
            }

            if (normalizedSyntheticItemId != null) {
                val syntheticIndex = threadMessages.indexOfLast { message ->
                    message.role == CodexMessageRole.System &&
                        message.kind == kind &&
                        message.itemId == normalizedSyntheticItemId
                }
                if (syntheticIndex >= 0) {
                    return syntheticIndex
                }
            }

            if (kind == CodexMessageKind.CommandExecution &&
                normalizedTurnId != null &&
                commandKey != null
            ) {
                val commandIndex = threadMessages.indexOfLast { message ->
                    message.role == CodexMessageRole.System &&
                        message.kind == CodexMessageKind.CommandExecution &&
                        message.turnId == normalizedTurnId &&
                        commandExecutionKey(
                            text = message.text,
                            details = message.commandExecutionDetails,
                        ) == commandKey
                }
                if (commandIndex >= 0) {
                    return commandIndex
                }
            }

            if (kind == CodexMessageKind.FileChange &&
                normalizedTurnId != null &&
                fileChangePathKeys.isNotEmpty()
            ) {
                val fileChangeIndex = threadMessages.indexOfLast { message ->
                    message.role == CodexMessageRole.System &&
                        message.kind == CodexMessageKind.FileChange &&
                        message.turnId == normalizedTurnId &&
                        normalizedFileChangePathKeys(message.text).any(fileChangePathKeys::contains)
                }
                if (fileChangeIndex >= 0) {
                    return fileChangeIndex
                }
            }

            if (kind == CodexMessageKind.FileChange &&
                normalizedTurnId != null &&
                fileChangePathKeys.isEmpty() &&
                incomingText != null &&
                isFileChangeSnapshotPayload(incomingText.trim())
            ) {
                val matchingIndices = threadMessages.indices.filter { index ->
                    val message = threadMessages[index]
                    message.role == CodexMessageRole.System &&
                        message.kind == CodexMessageKind.FileChange &&
                        message.turnId == normalizedTurnId
                }
                if (matchingIndices.size == 1) {
                    return matchingIndices.single()
                }
            }

            if (normalizedTurnId != null && normalizedItemId == null) {
                return threadMessages.indexOfLast { message ->
                    message.role == CodexMessageRole.System &&
                        message.kind == kind &&
                        message.turnId == normalizedTurnId &&
                        message.isStreaming
                }
            }

            return -1
        }

        private fun streamingPlaceholderText(kind: CodexMessageKind): String {
            return when (kind) {
                CodexMessageKind.Thinking -> "Thinking..."
                CodexMessageKind.Activity -> "Working..."
                CodexMessageKind.FileChange -> "Applying file changes..."
                CodexMessageKind.CommandExecution -> "Running command"
                CodexMessageKind.Plan -> "Planning..."
                CodexMessageKind.UserInputPrompt -> "Waiting for input..."
                CodexMessageKind.Chat -> "Updating..."
            }
        }

        private fun isPlaceholderText(text: String, kind: CodexMessageKind): Boolean {
            return text.trim().equals(streamingPlaceholderText(kind), ignoreCase = true)
        }

        private fun shouldPruneThinkingRowAfterTurnCompletion(message: CodexMessage): Boolean {
            val trimmedText = message.text.trim()
            if (trimmedText.isEmpty()) {
                return true
            }
            if (isPlaceholderText(trimmedText, CodexMessageKind.Thinking)) {
                return true
            }
            val withoutPrefix = trimmedText.replace(
                Regex("^\\s*thinking(?:\\.\\.\\.)?\\s*", RegexOption.IGNORE_CASE),
                "",
            )
            return withoutPrefix.trim().isEmpty()
        }

        private fun findHydrationMatchIndex(
            existingMessages: List<CodexMessage>,
            historyMessage: CodexMessage,
            consumedIndices: Set<Int>,
        ): Int {
            if (historyMessage.role == CodexMessageRole.Assistant) {
                val incomingItemId = normalizeThreadId(historyMessage.itemId)
                if (incomingItemId != null) {
                    for (index in existingMessages.indices.reversed()) {
                        val existingMessage = existingMessages[index]
                        if (index in consumedIndices) {
                            continue
                        }
                        if (existingMessage.role == CodexMessageRole.Assistant &&
                            existingMessage.itemId == incomingItemId
                        ) {
                            return index
                        }
                    }
                }

                val normalizedTurnId = normalizeThreadId(historyMessage.turnId)
                if (normalizedTurnId != null) {
                    for (index in existingMessages.indices.reversed()) {
                        val existingMessage = existingMessages[index]
                        if (index in consumedIndices) {
                            continue
                        }
                        if (existingMessage.role == CodexMessageRole.Assistant &&
                            existingMessage.turnId == normalizedTurnId &&
                            normalizedMessageText(existingMessage.text) == normalizedMessageText(historyMessage.text)
                        ) {
                            return index
                        }
                    }

                    for (index in existingMessages.indices.reversed()) {
                        val existingMessage = existingMessages[index]
                        if (index in consumedIndices) {
                            continue
                        }
                        if (existingMessage.role == CodexMessageRole.Assistant &&
                            existingMessage.turnId == normalizedTurnId &&
                            (normalizeThreadId(existingMessage.itemId) == null ||
                                normalizeThreadId(existingMessage.itemId) == incomingItemId)
                        ) {
                            return index
                        }
                    }
                }
            }

            if (historyMessage.role == CodexMessageRole.User) {
                val normalizedTurnId = normalizeThreadId(historyMessage.turnId)
                if (normalizedTurnId != null) {
                    for (index in existingMessages.indices.reversed()) {
                        val existingMessage = existingMessages[index]
                        if (index in consumedIndices) {
                            continue
                        }
                        if (existingMessage.role == CodexMessageRole.User &&
                            normalizedMessageText(existingMessage.text) == normalizedMessageText(historyMessage.text) &&
                            attachmentSignature(existingMessage) == attachmentSignature(historyMessage) &&
                            (existingMessage.turnId == null || existingMessage.turnId == normalizedTurnId)
                        ) {
                            return index
                        }
                    }
                }
            }

            if (historyMessage.role == CodexMessageRole.System &&
                historyMessage.kind == CodexMessageKind.Thinking
            ) {
                val normalizedTurnId = normalizeThreadId(historyMessage.turnId)
                if (normalizedTurnId != null) {
                    for (index in existingMessages.indices.reversed()) {
                        val existingMessage = existingMessages[index]
                        if (index in consumedIndices) {
                            continue
                        }
                        if (existingMessage.role == CodexMessageRole.System &&
                            existingMessage.kind == CodexMessageKind.Thinking &&
                            existingMessage.turnId == normalizedTurnId
                        ) {
                            return index
                        }
                    }
                }
            }

            if (historyMessage.role == CodexMessageRole.System &&
                historyMessage.kind == CodexMessageKind.FileChange
            ) {
                val normalizedTurnId = normalizeThreadId(historyMessage.turnId)
                if (normalizedTurnId != null) {
                    for (index in existingMessages.indices.reversed()) {
                        val existingMessage = existingMessages[index]
                        if (index in consumedIndices) {
                            continue
                        }
                        if (existingMessage.role == CodexMessageRole.System &&
                            existingMessage.kind == CodexMessageKind.FileChange &&
                            existingMessage.turnId == normalizedTurnId
                        ) {
                            return index
                        }
                    }
                }
            }

            if (historyMessage.role == CodexMessageRole.System &&
                historyMessage.kind == CodexMessageKind.CommandExecution
            ) {
                val normalizedTurnId = normalizeThreadId(historyMessage.turnId)
                val incomingCommandKey = commandExecutionKey(
                    text = historyMessage.text,
                    details = historyMessage.commandExecutionDetails,
                )
                if (normalizedTurnId != null && incomingCommandKey != null) {
                    for (index in existingMessages.indices.reversed()) {
                        val existingMessage = existingMessages[index]
                        if (index in consumedIndices) {
                            continue
                        }
                        if (existingMessage.role == CodexMessageRole.System &&
                            existingMessage.kind == CodexMessageKind.CommandExecution &&
                            existingMessage.turnId == normalizedTurnId &&
                            commandExecutionKey(
                                text = existingMessage.text,
                                details = existingMessage.commandExecutionDetails,
                            ) == incomingCommandKey
                        ) {
                            return index
                        }
                    }
                }
                if (normalizedTurnId != null) {
                    for (index in existingMessages.indices.reversed()) {
                        val existingMessage = existingMessages[index]
                        if (index in consumedIndices) {
                            continue
                        }
                        if (existingMessage.role == CodexMessageRole.System &&
                            existingMessage.kind == CodexMessageKind.CommandExecution &&
                            existingMessage.turnId == normalizedTurnId
                        ) {
                            return index
                        }
                    }
                }
            }

            val messageId = normalizeThreadId(historyMessage.id)
            if (messageId != null) {
                for (index in existingMessages.indices.reversed()) {
                    val existingMessage = existingMessages[index]
                    if (index in consumedIndices) {
                        continue
                    }
                    if (normalizeThreadId(existingMessage.id) == messageId) {
                        return index
                    }
                }
            }

            val historyKey = historyMessageKey(historyMessage)
            for (index in existingMessages.indices.reversed()) {
                val existingMessage = existingMessages[index]
                if (index in consumedIndices) {
                    continue
                }
                if (historyMessageKey(existingMessage) == historyKey) {
                    return index
                }
            }

            return -1
        }

        private fun reconcileHydratedMessage(
            localMessage: CodexMessage,
            historyMessage: CodexMessage,
            preservesRunningPresentation: Boolean,
        ): CodexMessage {
            val keepsStreamingPresentation = preservesRunningPresentation &&
                (localMessage.turnId == null ||
                    historyMessage.turnId == null ||
                    localMessage.turnId == historyMessage.turnId)

            var value = localMessage.copy(
                createdAt = localMessage.createdAt ?: historyMessage.createdAt,
                turnId = localMessage.turnId ?: historyMessage.turnId,
                itemId = localMessage.itemId ?: historyMessage.itemId,
                kind = if (localMessage.kind == historyMessage.kind) {
                    localMessage.kind
                } else if (localMessage.role == CodexMessageRole.Assistant && localMessage.kind != historyMessage.kind) {
                    historyMessage.kind
                } else {
                    localMessage.kind
                },
                attachments = if (localMessage.attachments.isEmpty()) historyMessage.attachments else localMessage.attachments,
                deliveryState = if (localMessage.deliveryState == CodexMessageDeliveryState.Pending) {
                    CodexMessageDeliveryState.Confirmed
                } else {
                    localMessage.deliveryState
                },
            )

            val normalizedHistoryText = normalizedMessageText(historyMessage.text)
            if (localMessage.role == CodexMessageRole.Assistant && normalizedHistoryText.isNotEmpty()) {
                value = value.copy(
                    text = if (keepsStreamingPresentation) {
                        mergeStreamingSnapshotText(localMessage.text, historyMessage.text)
                    } else {
                        historyMessage.text
                    },
                    isStreaming = keepsStreamingPresentation && (localMessage.isStreaming || historyMessage.isStreaming),
                )
            } else if (localMessage.role == CodexMessageRole.System && normalizedHistoryText.isNotEmpty()) {
                value = value.copy(
                    text = if (keepsStreamingPresentation && localMessage.isStreaming) {
                        mergeStreamingSnapshotText(localMessage.text, historyMessage.text)
                    } else {
                        historyMessage.text
                    },
                    isStreaming = keepsStreamingPresentation && (localMessage.isStreaming || historyMessage.isStreaming),
                )
            } else if (normalizedHistoryText.isNotEmpty()) {
                value = value.copy(text = historyMessage.text, isStreaming = false)
            }

            return value
        }

        private fun mergeStreamingSnapshotText(existingText: String, incomingText: String): String {
            val normalizedExisting = normalizedMessageText(existingText)
            val normalizedIncoming = normalizedMessageText(incomingText)
            if (normalizedExisting.isEmpty()) {
                return incomingText
            }
            if (normalizedIncoming.isEmpty()) {
                return existingText
            }
            if (normalizedIncoming.startsWith(normalizedExisting)) {
                return incomingText
            }
            if (normalizedExisting.startsWith(normalizedIncoming)) {
                return existingText
            }

            val maxOverlap = minOf(existingText.length, incomingText.length)
            if (maxOverlap > 0) {
                for (overlap in maxOverlap downTo 1) {
                    if (existingText.takeLast(overlap) == incomingText.take(overlap)) {
                        return existingText + incomingText.drop(overlap)
                    }
                }
            }

            return incomingText
        }

        private fun mergeAssistantDelta(existingText: String, incomingDelta: String): String {
            if (existingText.isEmpty()) {
                return incomingDelta
            }

            if (incomingDelta == existingText) {
                return existingText
            }

            if (existingText.endsWith(incomingDelta)) {
                return existingText
            }

            if (incomingDelta.length > existingText.length && incomingDelta.startsWith(existingText)) {
                return incomingDelta
            }

            if (existingText.length > incomingDelta.length && existingText.startsWith(incomingDelta)) {
                return existingText
            }

            val maxOverlap = minOf(existingText.length, incomingDelta.length)
            if (maxOverlap > 0) {
                for (overlap in maxOverlap downTo 1) {
                    if (existingText.takeLast(overlap) == incomingDelta.take(overlap)) {
                        return existingText + incomingDelta.drop(overlap)
                    }
                }
            }

            return existingText + incomingDelta
        }

        private fun normalizedMessageText(text: String): String {
            return text.trim()
        }

        private fun attachmentSignature(message: CodexMessage): String {
            return message.attachments.joinToString(separator = "|") { attachment ->
                listOf(attachment.id, attachment.sourceURL.orEmpty(), attachment.payloadDataURL.orEmpty())
                    .joinToString(separator = "#")
            }
        }

        private fun historyMessageKey(message: CodexMessage): String {
            val normalizedItemId = normalizeThreadId(message.itemId)
            if (normalizedItemId != null) {
                return "item:${message.role}:${message.kind}:$normalizedItemId"
            }

            return listOf(
                message.role.name,
                normalizeThreadId(message.turnId) ?: "no-turn",
                message.text,
                attachmentSignature(message),
            ).joinToString(separator = "|")
        }

        private fun sameHydrationIdentity(left: CodexMessage, right: CodexMessage): Boolean {
            val leftItemId = normalizeThreadId(left.itemId)
            val rightItemId = normalizeThreadId(right.itemId)
            if (leftItemId != null && leftItemId == rightItemId) {
                return true
            }

            val leftId = normalizeThreadId(left.id)
            val rightId = normalizeThreadId(right.id)
            if (leftId != null && leftId == rightId) {
                return true
            }

            return left.role == right.role &&
                normalizeThreadId(left.turnId) == normalizeThreadId(right.turnId) &&
                normalizedMessageText(left.text) == normalizedMessageText(right.text)
        }

        private fun List<CodexMessage>.withSequentialOrderIndices(): List<CodexMessage> {
            return mapIndexed { index, message ->
                message.copy(orderIndex = index)
            }
        }

        private fun syntheticStreamingItemId(turnId: String, kind: CodexMessageKind): String {
            return "turn:$turnId|kind:${kind.name.lowercase()}"
        }

        private fun normalizedFileChangePathKeys(text: String): Set<String> {
            val keys = mutableSetOf<String>()
            val lines = text.split('\n')
            for (line in lines) {
                var trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    continue
                }
                if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
                    trimmed = trimmed.drop(2).trim()
                }
                when {
                    trimmed.startsWith("Path:", ignoreCase = true) -> {
                        keys += normalizedFileChangePathAliases(trimmed.drop(5))
                    }

                    trimmed.startsWith("+++ ") || trimmed.startsWith("--- ") -> {
                        keys += normalizedFileChangePathAliases(trimmed.drop(4))
                    }

                    trimmed.startsWith("diff --git ") -> {
                        val components = trimmed.split(' ').filter(String::isNotBlank)
                        if (components.size >= 4) {
                            keys += normalizedFileChangePathAliases(components[3])
                        }
                    }

                    else -> {
                        val lowercased = trimmed.lowercase()
                        val actionVerbs = listOf(
                            "edited ",
                            "updated ",
                            "added ",
                            "created ",
                            "deleted ",
                            "removed ",
                            "renamed ",
                            "moved ",
                        )
                        val verb = actionVerbs.firstOrNull(lowercased::startsWith)
                        if (verb != null) {
                            val rawPath = trimmed.drop(verb.length).trim().replace(Regex("\\s*[+]\\s*\\d+\\s*[-−–—﹣－]\\s*\\d+\\s*$"), "")
                            keys += normalizedFileChangePathAliases(rawPath)
                        }
                    }
                }
            }
            return keys
        }

        private fun normalizedFileChangePathAliases(rawPath: String): Set<String> {
            val normalized = normalizeFileChangePathKey(rawPath) ?: return emptySet()
            val aliases = mutableSetOf(normalized)
            val components = normalized.split('/').filter(String::isNotBlank)
            val workspaceIndex = components.indexOf("workspace")
            if (workspaceIndex >= 0 && components.size > workspaceIndex + 2) {
                aliases += components.drop(workspaceIndex + 2).joinToString(separator = "/")
            }
            return aliases
        }

        private fun normalizeFileChangePathKey(rawPath: String): String? {
            var normalized = rawPath.trim()
            if (normalized.isEmpty() || normalized == "/dev/null") {
                return null
            }
            normalized = normalized
                .replace("`", "")
                .replace("\"", "")
                .replace("'", "")
            if (normalized.startsWith("(") && normalized.endsWith(")") && normalized.length > 2) {
                normalized = normalized.drop(1).dropLast(1)
            }
            if (normalized.startsWith("a/") || normalized.startsWith("b/")) {
                normalized = normalized.drop(2)
            }
            if (normalized.startsWith("./")) {
                normalized = normalized.drop(2)
            }
            normalized = normalized.replace(Regex(":\\d+(?::\\d+)?$"), "")
            while (normalized.lastOrNull() in listOf(',', '.', ';')) {
                normalized = normalized.dropLast(1)
            }
            normalized = normalized.trim()
            return normalized.ifEmpty { null }?.lowercase()
        }

        private fun commandExecutionPreviewKey(text: String): String? {
            val tokens = text.split(Regex("\\s+")).filter(String::isNotBlank)
            if (tokens.size < 2) {
                return null
            }
            val phase = tokens.first().lowercase()
            if (phase !in setOf("running", "completed", "failed", "stopped")) {
                return null
            }
            val command = tokens.drop(1).joinToString(separator = " ").trim().lowercase()
            return command.ifEmpty { null }
        }

        private fun commandExecutionKey(
            text: String,
            details: CodexCommandExecutionDetails?,
        ): String? {
            val detailKey = details?.dedupeKey?.trim().orEmpty()
            if (detailKey.isNotEmpty()) {
                return detailKey.lowercase()
            }
            return commandExecutionPreviewKey(text)
        }

        private fun isFileChangeSnapshotPayload(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                return false
            }
            val lower = trimmed.lowercase()
            if (lower.startsWith("status:")) {
                return true
            }

            val lines = trimmed.split('\n')
            var hasPath = false
            var hasKind = false
            var hasTotals = false
            var hasDiffFence = false
            var hasDiffHeader = false
            for (line in lines) {
                val candidate = line.trim()
                val candidateLower = candidate.lowercase()
                if (candidateLower.startsWith("path:")) hasPath = true
                if (candidateLower.startsWith("kind:")) hasKind = true
                if (candidateLower.startsWith("totals:")) hasTotals = true
                if (candidate.startsWith("```diff") || candidate == "```") hasDiffFence = true
                if (candidate.startsWith("diff --git ") ||
                    candidate.startsWith("+++ ") ||
                    candidate.startsWith("--- ") ||
                    candidate.startsWith("@@ ")
                ) {
                    hasDiffHeader = true
                }
            }
            return (hasPath && hasKind) ||
                (hasPath && (hasTotals || hasDiffFence || hasDiffHeader)) ||
                (hasDiffFence && hasDiffHeader)
        }

        private fun pruneDuplicateSystemRows(
            threadMessages: List<CodexMessage>,
            keepMessageId: String,
            kind: CodexMessageKind,
            turnId: String?,
            fileChangePathKeys: Set<String>,
            commandKey: String?,
        ): List<CodexMessage> {
            val normalizedTurnId = normalizeThreadId(turnId) ?: return threadMessages
            val keepText = threadMessages.firstOrNull { it.id == keepMessageId }?.text?.trim().orEmpty()
            return threadMessages.filter { candidate ->
                if (candidate.id == keepMessageId ||
                    candidate.role != CodexMessageRole.System ||
                    candidate.kind != kind ||
                    candidate.turnId != normalizedTurnId
                ) {
                    return@filter true
                }

                if (kind == CodexMessageKind.FileChange) {
                    if (fileChangePathKeys.isNotEmpty()) {
                        val candidateKeys = normalizedFileChangePathKeys(candidate.text)
                        return@filter candidateKeys.none(fileChangePathKeys::contains)
                    }
                    return@filter candidate.text.trim() != keepText
                }

                if (kind == CodexMessageKind.CommandExecution && commandKey != null) {
                    return@filter commandExecutionKey(
                        text = candidate.text,
                        details = candidate.commandExecutionDetails,
                    ) != commandKey
                }

                true
            }
        }
    }

    private fun replaceThreadMessages(threadId: String, messages: List<CodexMessage>): RemodexConversationState {
        val updatedMessagesByThread = messagesByThread.toMutableMap()
        updatedMessagesByThread[threadId] = messages

        val updatedRevisions = messageRevisionByThread.toMutableMap()
        updatedRevisions[threadId] = (updatedRevisions[threadId] ?: 0) + 1

        return copy(
            messagesByThread = updatedMessagesByThread,
            messageRevisionByThread = updatedRevisions,
        )
    }

    private fun clearOutcomeBadge(threadId: String): RemodexConversationState {
        return copy(
            readyThreadIds = readyThreadIds - threadId,
            failedThreadIds = failedThreadIds - threadId,
        )
    }

    private fun withAssistantCompletionFingerprint(
        threadId: String,
        text: String,
    ): RemodexConversationState {
        return copy(
            assistantCompletionFingerprintByThread = assistantCompletionFingerprintByThread +
                (threadId to AssistantCompletionFingerprint(text = text, timestamp = Instant.now())),
        )
    }

    private fun withRecentActivityLine(
        dedupeKey: String,
        line: String,
        timestamp: Instant,
    ): RemodexConversationState {
        return copy(
            recentActivityLineByThread = recentActivityLineByThread +
                (dedupeKey to RecentActivityLine(line = line, timestamp = timestamp)),
        )
    }

    private fun withThreadTurnMapping(
        threadId: String,
        turnId: String?,
    ): RemodexConversationState {
        val normalizedTurnId = normalizeThreadId(turnId) ?: return this
        return copy(
            threadIdByTurnId = threadIdByTurnId + (normalizedTurnId to threadId),
        )
    }
}
