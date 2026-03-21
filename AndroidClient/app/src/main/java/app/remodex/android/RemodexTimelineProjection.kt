package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import java.time.Duration
import java.time.Instant

data class RemodexTimelineProjection(
    val messages: List<CodexMessage>,
)

object RemodexTimelineProjector {
    fun project(messages: List<CodexMessage>): RemodexTimelineProjection {
        val visibleMessages = removeHiddenSystemMarkers(messages)
        val reordered = enforceIntraTurnOrder(visibleMessages)
        val collapsedThinking = collapseConsecutiveThinkingMessages(reordered)
        val dedupedFileChanges = removeDuplicateFileChangeMessages(collapsedThinking)
        val dedupedAssistant = removeDuplicateAssistantMessages(dedupedFileChanges)
        return RemodexTimelineProjection(messages = dedupedAssistant)
    }

    fun assistantResponseAnchorMessageId(
        messages: List<CodexMessage>,
        activeTurnId: String?,
    ): String? {
        val normalizedActiveTurnId = normalizedIdentifier(activeTurnId)
        if (normalizedActiveTurnId != null) {
            messages.lastOrNull { message ->
                message.role == CodexMessageRole.Assistant &&
                    normalizedIdentifier(message.turnId) == normalizedActiveTurnId
            }?.let { return it.id }
        }

        return messages.lastOrNull { message ->
            message.role == CodexMessageRole.Assistant && message.isStreaming
        }?.id
    }

    private fun enforceIntraTurnOrder(messages: List<CodexMessage>): List<CodexMessage> {
        val indicesByTurn = linkedMapOf<String, MutableList<Int>>()
        messages.forEachIndexed { index, message ->
            val turnId = normalizedIdentifier(message.turnId) ?: return@forEachIndexed
            indicesByTurn.getOrPut(turnId) { mutableListOf() }.add(index)
        }

        val result = messages.toMutableList()
        indicesByTurn.values.forEach { indices ->
            if (indices.size <= 1) {
                return@forEach
            }

            val turnMessages = indices.map { result[it] }
            val sorted = if (hasInterleavedAssistantThinkingFlow(turnMessages)) {
                turnMessages.sortedWith(
                    compareBy<CodexMessage> { if (it.role == CodexMessageRole.User) 0 else 1 }
                        .thenBy(CodexMessage::orderIndex),
                )
            } else {
                turnMessages.sortedWith(
                    compareBy<CodexMessage> { intraTurnPriority(it) }
                        .thenBy(CodexMessage::orderIndex),
                )
            }

            indices.forEachIndexed { offset, originalIndex ->
                result[originalIndex] = sorted[offset]
            }
        }

        return result
    }

    private fun hasInterleavedAssistantThinkingFlow(turnMessages: List<CodexMessage>): Boolean {
        val distinctAssistantItemIds = turnMessages
            .asSequence()
            .filter { it.role == CodexMessageRole.Assistant }
            .mapNotNull { normalizedIdentifier(it.itemId) }
            .toSet()
        if (distinctAssistantItemIds.size > 1) {
            return true
        }

        val ordered = turnMessages.sortedBy(CodexMessage::orderIndex)
        var hasThinkingBeforeAssistant = false
        var seenAssistant = false
        ordered.forEach { message ->
            when {
                message.role == CodexMessageRole.Assistant -> seenAssistant = true
                message.role == CodexMessageRole.System && message.kind == CodexMessageKind.Thinking -> {
                    if (!seenAssistant) {
                        hasThinkingBeforeAssistant = true
                    } else if (hasThinkingBeforeAssistant) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun intraTurnPriority(message: CodexMessage): Int {
        return when (message.role) {
            CodexMessageRole.User -> 0
            CodexMessageRole.System -> when (message.kind) {
                CodexMessageKind.Thinking -> 1
                CodexMessageKind.CommandExecution -> 2
                CodexMessageKind.Chat -> 3
                CodexMessageKind.Plan -> 3
                CodexMessageKind.FileChange -> 5
                CodexMessageKind.UserInputPrompt -> 6
            }

            CodexMessageRole.Assistant -> 4
        }
    }

    private fun removeHiddenSystemMarkers(messages: List<CodexMessage>): List<CodexMessage> {
        return messages.filterNot(RemodexGitTimelineSupport::isHiddenTimelineMessage)
    }

    private fun collapseConsecutiveThinkingMessages(messages: List<CodexMessage>): List<CodexMessage> {
        val result = mutableListOf<CodexMessage>()
        messages.forEach { message ->
            if (message.role != CodexMessageRole.System || message.kind != CodexMessageKind.Thinking) {
                result += message
                return@forEach
            }

            val previous = result.lastOrNull()
            if (
                previous == null ||
                previous.role != CodexMessageRole.System ||
                previous.kind != CodexMessageKind.Thinking ||
                !shouldMergeThinkingRows(previous, message)
            ) {
                result += message
                return@forEach
            }

            val mergedText = mergeThinkingText(previous.text, message.text)
            result[result.lastIndex] = previous.copy(
                text = mergedText,
                isStreaming = message.isStreaming,
                turnId = message.turnId ?: previous.turnId,
                itemId = message.itemId ?: previous.itemId,
            )
        }
        return result
    }

    private fun shouldMergeThinkingRows(previous: CodexMessage, incoming: CodexMessage): Boolean {
        val previousItemId = normalizedIdentifier(previous.itemId)
        val incomingItemId = normalizedIdentifier(incoming.itemId)
        if (previousItemId != null && incomingItemId != null) {
            return previousItemId == incomingItemId
        }
        if (previousItemId != null || incomingItemId != null) {
            return false
        }

        val previousTurnId = normalizedIdentifier(previous.turnId)
        val incomingTurnId = normalizedIdentifier(incoming.turnId)
        return previousTurnId != null && previousTurnId == incomingTurnId
    }

    private fun mergeThinkingText(existing: String, incoming: String): String {
        val existingTrimmed = existing.trim()
        val incomingTrimmed = incoming.trim()
        if (incomingTrimmed.isEmpty()) {
            return existingTrimmed
        }
        if (existingTrimmed.isEmpty()) {
            return incomingTrimmed
        }

        val placeholderValues = setOf("thinking...")
        val existingLower = existingTrimmed.lowercase()
        val incomingLower = incomingTrimmed.lowercase()

        if (incomingLower in placeholderValues) {
            return existingTrimmed
        }
        if (existingLower in placeholderValues) {
            return incomingTrimmed
        }
        if (incomingLower == existingLower) {
            return incomingTrimmed
        }
        if (incomingTrimmed.contains(existingTrimmed)) {
            return incomingTrimmed
        }
        if (existingTrimmed.contains(incomingTrimmed)) {
            return existingTrimmed
        }

        return "$existingTrimmed\n$incomingTrimmed"
    }

    private fun removeDuplicateAssistantMessages(messages: List<CodexMessage>): List<CodexMessage> {
        val seenKeys = mutableSetOf<String>()
        val seenNoTurnByText = mutableMapOf<String, Instant>()
        val result = mutableListOf<CodexMessage>()

        messages.forEach { message ->
            if (message.role != CodexMessageRole.Assistant) {
                result += message
                return@forEach
            }

            val normalizedText = message.text.trim()
            if (normalizedText.isEmpty()) {
                result += message
                return@forEach
            }

            val turnId = normalizedIdentifier(message.turnId)
            if (turnId != null) {
                val dedupeScope = normalizedIdentifier(message.itemId) ?: "no-item"
                val key = "$turnId|$dedupeScope|$normalizedText"
                if (!seenKeys.add(key)) {
                    return@forEach
                }
                result += message
                return@forEach
            }

            val createdAt = message.createdAt
            val previous = seenNoTurnByText[normalizedText]
            if (createdAt != null && previous != null && absSecondsBetween(createdAt, previous) <= 12) {
                return@forEach
            }
            if (createdAt != null) {
                seenNoTurnByText[normalizedText] = createdAt
            }
            result += message
        }

        return result
    }

    private fun removeDuplicateFileChangeMessages(messages: List<CodexMessage>): List<CodexMessage> {
        val latestIndexByKey = mutableMapOf<String, Int>()
        messages.forEachIndexed { index, message ->
            val key = duplicateFileChangeKey(message) ?: return@forEachIndexed
            latestIndexByKey[key] = index
        }

        return messages.filterIndexed { index, message ->
            val key = duplicateFileChangeKey(message) ?: return@filterIndexed true
            latestIndexByKey[key] == index
        }
    }

    private fun duplicateFileChangeKey(message: CodexMessage): String? {
        if (message.role != CodexMessageRole.System || message.kind != CodexMessageKind.FileChange) {
            return null
        }
        val normalizedText = message.text.trim()
        val turnId = normalizedIdentifier(message.turnId)
        if (normalizedText.isEmpty() || turnId == null) {
            return null
        }
        return "$turnId|$normalizedText"
    }

    private fun normalizedIdentifier(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        return trimmed.ifEmpty { null }
    }

    private fun absSecondsBetween(first: Instant, second: Instant): Long {
        return kotlin.math.abs(Duration.between(first, second).seconds)
    }
}
