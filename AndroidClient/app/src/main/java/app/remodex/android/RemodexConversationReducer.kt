package app.remodex.android

import app.remodex.android.core.protocol.RpcMessage
import app.remodex.android.core.protocol.arrayValue
import app.remodex.android.core.protocol.objectValue
import app.remodex.android.core.protocol.stringValue
import kotlinx.serialization.json.JsonObject

object RemodexConversationReducer {
    fun reduce(
        conversation: RemodexConversationState,
        message: RpcMessage,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val normalizedMethod = message.method?.lowercase() ?: return conversation
        val paramsObject = message.params?.objectValue ?: return conversation
        val eventObject = paramsObject["event"]?.objectValue

        return when (normalizedMethod) {
            "turn/started" -> reduceTurnStarted(
                conversation = conversation,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "turn/completed" -> reduceTurnCompleted(
                conversation = conversation,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "item/started", "codex/event/item_started" -> reduceItemStarted(
                conversation = conversation,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "item/completed", "codex/event/item_completed", "codex/event/agent_message" -> reduceItemCompleted(
                conversation = conversation,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "item/agentmessage/delta",
            "codex/event/agent_message_content_delta",
            "codex/event/agent_message_delta" -> reduceAgentDelta(
                conversation = conversation,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            else -> conversation
        }
    }

    private fun reduceTurnStarted(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val turnId = extractTurnId(paramsObject, eventObject, allowTopLevelId = true)
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation

        return conversation.withTurnStarted(threadId = threadId, turnId = turnId)
    }

    private fun reduceTurnCompleted(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val turnId = extractTurnId(paramsObject, eventObject, allowTopLevelId = true)
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation

        return conversation.withTurnCompleted(threadId = threadId, turnId = turnId)
    }

    private fun reduceItemStarted(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val itemObject = extractItemObject(paramsObject, eventObject) ?: return conversation
        if (!isAssistantMessageItem(itemObject)) {
            return conversation
        }

        val turnId = extractTurnId(paramsObject, eventObject) ?: return conversation
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation
        val itemId = extractItemId(paramsObject, eventObject, itemObject)

        return conversation.beginAssistantMessage(
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
        )
    }

    private fun reduceAgentDelta(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val delta = extractAssistantDelta(paramsObject, eventObject) ?: return conversation
        val turnId = extractTurnId(paramsObject, eventObject) ?: return conversation
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation
        val itemId = extractItemId(
            paramsObject = paramsObject,
            eventObject = eventObject,
            itemObject = extractItemObject(paramsObject, eventObject),
        )

        return conversation.appendAssistantDelta(
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            delta = delta,
        )
    }

    private fun reduceItemCompleted(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val itemObject = extractItemObject(paramsObject, eventObject)
        val text = when {
            itemObject != null && isAssistantMessageItem(itemObject) -> extractMessageText(itemObject)
            else -> extractCompletionFallbackText(paramsObject, eventObject)
        }
        if (text.isBlank()) {
            return conversation
        }

        val turnId = extractTurnId(paramsObject, eventObject)
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation
        val itemId = extractItemId(paramsObject, eventObject, itemObject)

        return conversation.completeAssistantMessage(
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            text = text,
        )
    }

    private fun extractItemObject(paramsObject: JsonObject, eventObject: JsonObject?): JsonObject? {
        return paramsObject["item"]?.objectValue ?: eventObject?.get("item")?.objectValue
    }

    private fun isAssistantMessageItem(itemObject: JsonObject): Boolean {
        val itemType = itemObject["type"]?.stringValue?.lowercase().orEmpty()
        val role = itemObject["role"]?.stringValue?.lowercase().orEmpty()
        return itemType == "agentmessage" ||
            itemType == "assistantmessage" ||
            (itemType == "message" && role != "user")
    }

    private fun extractTurnId(
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        allowTopLevelId: Boolean = false,
    ): String? {
        return firstNonBlank(
            paramsObject["turnId"]?.stringValue,
            paramsObject["turn_id"]?.stringValue,
            paramsObject["turn"]?.objectValue?.get("id")?.stringValue,
            eventObject?.get("turnId")?.stringValue,
            eventObject?.get("turn_id")?.stringValue,
            eventObject?.get("turn")?.objectValue?.get("id")?.stringValue,
            paramsObject["id"]?.stringValue?.takeIf { allowTopLevelId },
        )
    }

    private fun extractItemId(
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        itemObject: JsonObject?,
    ): String? {
        return firstNonBlank(
            itemObject?.get("id")?.stringValue,
            paramsObject["itemId"]?.stringValue,
            paramsObject["item_id"]?.stringValue,
            eventObject?.get("itemId")?.stringValue,
            eventObject?.get("item_id")?.stringValue,
        )
    }

    private fun extractAssistantDelta(paramsObject: JsonObject, eventObject: JsonObject?): String? {
        return firstNonBlank(
            paramsObject["delta"]?.stringValue,
            eventObject?.get("delta")?.stringValue,
            paramsObject["event"]?.objectValue?.get("delta")?.stringValue,
        )
    }

    private fun extractCompletionFallbackText(paramsObject: JsonObject, eventObject: JsonObject?): String {
        return firstNonBlank(
            paramsObject["message"]?.stringValue,
            eventObject?.get("message")?.stringValue,
            paramsObject["text"]?.stringValue,
            eventObject?.get("text")?.stringValue,
        ).orEmpty()
    }

    private fun extractMessageText(itemObject: JsonObject): String {
        val contentItems = itemObject["content"]?.arrayValue.orEmpty()
        val parts = buildList {
            for (contentItem in contentItems) {
                val contentObject = contentItem.objectValue ?: continue
                val contentType = contentObject["type"]?.stringValue?.lowercase()
                val isTextType = contentType == null ||
                    contentType == "text" ||
                    contentType == "input_text" ||
                    contentType == "output_text" ||
                    contentType == "message"
                if (!isTextType) {
                    continue
                }

                val text = firstNonBlank(
                    contentObject["text"]?.stringValue,
                    contentObject["delta"]?.stringValue,
                    contentObject["data"]?.objectValue?.get("text")?.stringValue,
                )
                if (text != null) {
                    add(text)
                }
            }
        }
        if (parts.isNotEmpty()) {
            return parts.joinToString(separator = "\n").trim()
        }

        return firstNonBlank(
            itemObject["text"]?.stringValue,
            itemObject["message"]?.stringValue,
        ).orEmpty()
    }

    private fun resolveThreadId(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
        turnIdHint: String?,
    ): String? {
        val directThreadId = firstNonBlank(
            paramsObject["threadId"]?.stringValue,
            paramsObject["thread_id"]?.stringValue,
            paramsObject["thread"]?.objectValue?.get("id")?.stringValue,
            eventObject?.get("threadId")?.stringValue,
            eventObject?.get("thread_id")?.stringValue,
            eventObject?.get("thread")?.objectValue?.get("id")?.stringValue,
        )
        if (directThreadId != null) {
            return directThreadId
        }

        if (turnIdHint != null) {
            val mappedThreadId = conversation.threadIdByTurnId[turnIdHint]
            if (mappedThreadId != null) {
                return mappedThreadId
            }
        }

        if (knownThreadIds.size == 1) {
            return knownThreadIds.first()
        }

        if (knownThreadIds.isEmpty() && conversation.messagesByThread.keys.size <= 1) {
            return conversation.activeThreadId
        }

        return null
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}
