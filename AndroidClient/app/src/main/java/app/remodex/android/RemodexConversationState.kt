package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageDeliveryState
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexThreadRunBadgeState
import app.remodex.android.core.model.CodexTurnTerminalState
import app.remodex.android.core.transport.RemodexThreadTurnStateSnapshot
import java.time.Instant
import java.util.UUID

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
        val sortedHistory = historyMessages.sortedBy(CodexMessage::orderIndex)
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

        return replaceThreadMessages(normalizedThreadId, mergedMessages.withSequentialOrderIndices())
            .withThreadHydrated(normalizedThreadId)
    }

    fun appendUserMessage(
        threadId: String,
        text: String,
        messageId: String = UUID.randomUUID().toString(),
        turnId: String? = null,
        deliveryState: CodexMessageDeliveryState = CodexMessageDeliveryState.Pending,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) {
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
        val existingIndex = findAssistantMessageIndex(existingMessages, normalizedTurnId, normalizedItemId)

        if (existingIndex >= 0) {
            val updatedMessages = existingMessages.toMutableList()
            val existingMessage = updatedMessages[existingIndex]
            updatedMessages[existingIndex] = existingMessage.copy(
                isStreaming = true,
                itemId = existingMessage.itemId ?: normalizedItemId,
            )
            return replaceThreadMessages(normalizedThreadId, updatedMessages)
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

        val existingMessages = messagesFor(normalizedThreadId)
        val existingIndex = findAssistantMessageIndex(existingMessages, normalizedTurnId, normalizedItemId)
        if (existingIndex >= 0) {
            val updatedMessages = existingMessages.toMutableList()
            val existingMessage = updatedMessages[existingIndex]
            updatedMessages[existingIndex] = existingMessage.copy(
                text = trimmedText,
                isStreaming = false,
                turnId = existingMessage.turnId ?: normalizedTurnId,
                itemId = existingMessage.itemId ?: normalizedItemId,
            )
            return replaceThreadMessages(normalizedThreadId, updatedMessages)
        }

        val nextMessages = existingMessages + CodexMessage(
            id = UUID.randomUUID().toString(),
            threadId = normalizedThreadId,
            role = CodexMessageRole.Assistant,
            text = trimmedText,
            createdAt = Instant.now(),
            turnId = normalizedTurnId,
            itemId = normalizedItemId,
            isStreaming = false,
            orderIndex = nextOrderIndex(existingMessages),
        )
        return replaceThreadMessages(normalizedThreadId, nextMessages)
    }

    fun upsertSystemMessage(
        threadId: String,
        kind: CodexMessageKind,
        text: String,
        turnId: String? = null,
        itemId: String? = null,
        isStreaming: Boolean = false,
    ): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) {
            return this
        }

        val normalizedTurnId = normalizeThreadId(turnId)
        val normalizedItemId = normalizeThreadId(itemId)
        val existingMessages = messagesFor(normalizedThreadId)
        val existingIndex = findSystemMessageIndex(
            threadMessages = existingMessages,
            kind = kind,
            turnId = normalizedTurnId,
            itemId = normalizedItemId,
        )
        if (existingIndex >= 0) {
            val updatedMessages = existingMessages.toMutableList()
            val existingMessage = updatedMessages[existingIndex]
            updatedMessages[existingIndex] = existingMessage.copy(
                kind = kind,
                text = trimmedText,
                turnId = existingMessage.turnId ?: normalizedTurnId,
                itemId = existingMessage.itemId ?: normalizedItemId,
                isStreaming = isStreaming,
            )
            return replaceThreadMessages(normalizedThreadId, updatedMessages)
        }

        val nextMessages = existingMessages + CodexMessage(
            id = UUID.randomUUID().toString(),
            threadId = normalizedThreadId,
            role = CodexMessageRole.System,
            kind = kind,
            text = trimmedText,
            createdAt = Instant.now(),
            turnId = normalizedTurnId,
            itemId = normalizedItemId,
            isStreaming = isStreaming,
            orderIndex = nextOrderIndex(existingMessages),
        )
        return replaceThreadMessages(normalizedThreadId, nextMessages)
    }

    fun appendSystemMessage(
        threadId: String,
        kind: CodexMessageKind,
        text: String,
        turnId: String? = null,
        itemId: String? = null,
        isStreaming: Boolean = false,
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
            orderIndex = nextOrderIndex(existingMessages),
        )
        return replaceThreadMessages(normalizedThreadId, nextMessages)
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
        if (trimmedDelta.isEmpty()) {
            return upsertSystemMessage(
                threadId = normalizedThreadId,
                kind = kind,
                text = placeholderText,
                turnId = normalizedTurnId,
                itemId = normalizedItemId,
                isStreaming = true,
            )
        }

        val stateWithPlaceholder = upsertSystemMessage(
            threadId = normalizedThreadId,
            kind = kind,
            text = placeholderText,
            turnId = normalizedTurnId,
            itemId = normalizedItemId,
            isStreaming = true,
        )
        val existingMessages = stateWithPlaceholder.messagesFor(normalizedThreadId)
        val targetIndex = findSystemMessageIndex(
            threadMessages = existingMessages,
            kind = kind,
            turnId = normalizedTurnId,
            itemId = normalizedItemId,
        )
        if (targetIndex < 0) {
            return stateWithPlaceholder
        }

        val updatedMessages = existingMessages.toMutableList()
        val targetMessage = updatedMessages[targetIndex]
        val nextText = if (isPlaceholderText(targetMessage.text, kind)) {
            trimmedDelta
        } else {
            targetMessage.text + delta
        }
        updatedMessages[targetIndex] = targetMessage.copy(
            text = nextText.trim(),
            isStreaming = true,
            turnId = targetMessage.turnId ?: normalizedTurnId,
            itemId = targetMessage.itemId ?: normalizedItemId,
        )
        return stateWithPlaceholder.replaceThreadMessages(normalizedThreadId, updatedMessages)
    }

    fun completeSystemMessage(
        threadId: String,
        kind: CodexMessageKind,
        text: String,
        turnId: String? = null,
        itemId: String? = null,
    ): RemodexConversationState {
        return upsertSystemMessage(
            threadId = threadId,
            kind = kind,
            text = text,
            turnId = turnId,
            itemId = itemId,
            isStreaming = false,
        )
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

        private fun findSystemMessageIndex(
            threadMessages: List<CodexMessage>,
            kind: CodexMessageKind,
            turnId: String?,
            itemId: String?,
        ): Int {
            val normalizedTurnId = normalizeThreadId(turnId)
            val normalizedItemId = normalizeThreadId(itemId)

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

            if (normalizedTurnId != null) {
                return threadMessages.indexOfLast { message ->
                    message.role == CodexMessageRole.System &&
                        message.kind == kind &&
                        message.turnId == normalizedTurnId &&
                        (message.isStreaming || normalizedItemId == null)
                }
            }

            return -1
        }

        private fun streamingPlaceholderText(kind: CodexMessageKind): String {
            return when (kind) {
                CodexMessageKind.Thinking -> "Thinking..."
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
            } else if (normalizedHistoryText.isNotEmpty()) {
                value = value.copy(
                    text = historyMessage.text,
                    isStreaming = false,
                )
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
}
