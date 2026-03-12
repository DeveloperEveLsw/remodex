package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageRole
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
) {
    fun messagesFor(threadId: String?): List<CodexMessage> {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return emptyList()
        return messagesByThread[normalizedThreadId] ?: emptyList()
    }

    fun messageRevisionFor(threadId: String?): Int {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return 0
        return messageRevisionByThread[normalizedThreadId] ?: 0
    }

    fun isLoadingThread(threadId: String?): Boolean {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return false
        return loadingThreadIds.contains(normalizedThreadId)
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

    fun withThreadMessages(threadId: String, messages: List<CodexMessage>): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        return replaceThreadMessages(normalizedThreadId, messages)
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

        return copy(
            activeTurnIdByThread = updatedActiveTurnIds,
            threadIdByTurnId = updatedThreadIdsByTurnId,
            runningThreadIds = runningThreadIds + normalizedThreadId,
            readyThreadIds = readyThreadIds - normalizedThreadId,
            failedThreadIds = failedThreadIds - normalizedThreadId,
        )
    }

    fun withTurnCompleted(threadId: String, turnId: String?): RemodexConversationState {
        val normalizedThreadId = normalizeThreadId(threadId) ?: return this
        val normalizedTurnId = normalizeThreadId(turnId)
        val updatedActiveTurnIds = activeTurnIdByThread.toMutableMap()
        if (normalizedTurnId != null && updatedActiveTurnIds[normalizedThreadId] == normalizedTurnId) {
            updatedActiveTurnIds.remove(normalizedThreadId)
        } else if (normalizedTurnId == null) {
            updatedActiveTurnIds.remove(normalizedThreadId)
        }

        return copy(
            activeTurnIdByThread = updatedActiveTurnIds,
            runningThreadIds = runningThreadIds - normalizedThreadId,
        )
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
            text = targetMessage.text + delta,
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
        )
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
}
