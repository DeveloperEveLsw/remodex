package app.remodex.android

import app.remodex.android.core.protocol.JsonValue
import app.remodex.android.core.protocol.RpcMessage
import app.remodex.android.core.protocol.arrayValue
import app.remodex.android.core.protocol.objectValue
import app.remodex.android.core.protocol.stringValue
import kotlinx.serialization.json.JsonObject

data class RemodexTurnDiffChangeSetSignal(
    val threadId: String,
    val turnId: String,
    val diff: String,
)

data class RemodexFallbackChangeSetSignal(
    val threadId: String,
    val turnId: String,
    val patch: String,
)

object RemodexAIChangeSetSignals {
    fun turnDiffSignal(
        message: RpcMessage,
        conversation: RemodexConversationState,
        knownThreadIds: Set<String>,
    ): RemodexTurnDiffChangeSetSignal? {
        val normalizedMethod = message.method?.trim()?.lowercase() ?: return null
        if (normalizedMethod != "turn/diff/updated" &&
            normalizedMethod != "codex/event/turn_diff_updated" &&
            normalizedMethod != "codex/event/turn_diff"
        ) {
            return null
        }

        val paramsObject = message.params?.objectValue ?: return null
        val eventObject = extractEventObject(paramsObject)
        val nestedEventObject = paramsObject["event"]?.objectValue
        val diffCandidate = firstNonBlank(
            paramsObject["diff"]?.stringValue,
            paramsObject["unified_diff"]?.stringValue,
            eventObject?.get("diff")?.stringValue,
            eventObject?.get("unified_diff")?.stringValue,
            nestedEventObject?.get("diff")?.stringValue,
            nestedEventObject?.get("unified_diff")?.stringValue,
        )
        val normalizedPatch = normalizedUnifiedPatchPayload(diffCandidate) ?: return null
        val turnId = extractTurnId(paramsObject, eventObject, allowTopLevelId = true) ?: return null
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return null

        return RemodexTurnDiffChangeSetSignal(
            threadId = threadId,
            turnId = turnId,
            diff = normalizedPatch,
        )
    }

    fun fallbackPatchSignal(
        message: RpcMessage,
        conversation: RemodexConversationState,
        knownThreadIds: Set<String>,
    ): RemodexFallbackChangeSetSignal? {
        val normalizedMethod = message.method?.trim()?.lowercase() ?: return null
        if (normalizedMethod != "item/completed" && normalizedMethod != "codex/event/item_completed") {
            return null
        }

        val paramsObject = message.params?.objectValue ?: return null
        val eventObject = extractEventObject(paramsObject)
        val itemObject = extractItemObject(paramsObject, eventObject) ?: return null
        val normalizedItemType = normalizeItemType(itemObject["type"]?.stringValue) ?: return null
        if (normalizedItemType != "filechange" &&
            normalizedItemType != "diff" &&
            normalizedItemType != "toolcall"
        ) {
            return null
        }
        if (normalizedItemType == "toolcall" && !isLikelyFileChangeToolCall(itemObject)) {
            return null
        }

        val turnId = extractTurnId(paramsObject, eventObject) ?: return null
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return null
        val patch = extractChangeSetUnifiedPatch(itemObject) ?: return null

        return RemodexFallbackChangeSetSignal(
            threadId = threadId,
            turnId = turnId,
            patch = patch,
        )
    }

    fun completedTurnId(message: RpcMessage): String? {
        val normalizedMethod = message.method?.trim()?.lowercase() ?: return null
        if (normalizedMethod != "turn/completed") {
            return null
        }

        val paramsObject = message.params?.objectValue ?: return null
        return extractTurnId(
            paramsObject = paramsObject,
            eventObject = extractEventObject(paramsObject),
            allowTopLevelId = true,
        )
    }

    fun normalizedUnifiedPatchPayload(rawPatch: String?): String? {
        val patch = rawPatch?.takeIf { it.isNotBlank() } ?: return null
        return if (patch.endsWith("\n")) patch else "$patch\n"
    }

    private fun extractItemObject(
        paramsObject: JsonObject,
        eventObject: JsonObject?,
    ): JsonObject? {
        return paramsObject["item"]?.objectValue
            ?: eventObject?.get("item")?.objectValue
            ?: eventObject
                ?.takeIf { isLikelyIncomingItemPayload(it) }
            ?: paramsObject.takeIf { isLikelyIncomingItemPayload(it) }
    }

    private fun extractChangeSetUnifiedPatch(itemObject: JsonObject): String? {
        val directPatch = firstNonBlank(
            itemObject["diff"]?.stringValue,
            itemObject["unified_diff"]?.stringValue,
            itemObject["patch"]?.stringValue,
            firstStringDeep("diff", itemObject),
            firstStringDeep("unified_diff", itemObject),
            firstStringDeep("patch", itemObject),
        )
        if (RemodexFileChangeBodyFormatter.looksLikePatchText(directPatch.orEmpty())) {
            return normalizedUnifiedPatchPayload(directPatch)
        }

        val changeDiffs = itemObject["changes"]?.arrayValue
            ?.mapNotNull { changeValue ->
                changeValue.objectValue?.let { changeObject ->
                    firstNonBlank(
                        changeObject["diff"]?.stringValue,
                        changeObject["patch"]?.stringValue,
                        firstStringDeep("diff", changeObject),
                        firstStringDeep("patch", changeObject),
                    )
                }
            }
            ?.filter { RemodexFileChangeBodyFormatter.looksLikePatchText(it) }
            .orEmpty()
        if (changeDiffs.isNotEmpty()) {
            return normalizedUnifiedPatchPayload(changeDiffs.joinToString(separator = "\n"))
        }

        val outputPatch = firstNonBlank(
            itemObject["output"]?.stringValue,
            itemObject["text"]?.stringValue,
            itemObject["message"]?.stringValue,
        )
        if (RemodexFileChangeBodyFormatter.looksLikePatchText(outputPatch.orEmpty())) {
            return normalizedUnifiedPatchPayload(outputPatch)
        }

        return null
    }

    private fun isLikelyFileChangeToolCall(itemObject: JsonObject): Boolean {
        val descriptor = buildList {
            add(itemObject["kind"]?.stringValue)
            add(itemObject["name"]?.stringValue)
            add(itemObject["tool"]?.stringValue)
            add(itemObject["tool_name"]?.stringValue)
            add(itemObject["toolName"]?.stringValue)
            add(itemObject["title"]?.stringValue)
            add(itemObject["tool"]?.objectValue?.get("kind")?.stringValue)
            add(itemObject["tool"]?.objectValue?.get("name")?.stringValue)
            add(itemObject["call"]?.objectValue?.get("kind")?.stringValue)
            add(itemObject["call"]?.objectValue?.get("name")?.stringValue)
        }.filterNotNull().joinToString(separator = " ")
            .lowercase()
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")

        val hasToolHint = descriptor.contains("filechange") ||
            descriptor.contains("applypatch") ||
            descriptor.contains("patchapply") ||
            descriptor.contains("diff") ||
            descriptor.contains("edit") ||
            descriptor.contains("write") ||
            descriptor.contains("rename") ||
            descriptor.contains("delete") ||
            descriptor.contains("remove") ||
            descriptor.contains("create") ||
            descriptor.contains("add") ||
            descriptor.contains("move")
        val hasStructuredChanges = itemObject["changes"] != null
        val hasDiffPayload = RemodexFileChangeBodyFormatter.looksLikePatchText(
            firstNonBlank(
                itemObject["diff"]?.stringValue,
                itemObject["unified_diff"]?.stringValue,
                itemObject["patch"]?.stringValue,
                firstStringDeep("diff", itemObject),
                firstStringDeep("unified_diff", itemObject),
                firstStringDeep("patch", itemObject),
            ).orEmpty(),
        )
        return (hasToolHint && (hasStructuredChanges || hasDiffPayload)) || hasDiffPayload
    }

    private fun normalizeItemType(rawType: String?): String? {
        val trimmed = rawType?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        return trimmed
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")
    }

    private fun isLikelyIncomingItemPayload(objectValue: JsonObject): Boolean {
        val normalizedType = normalizeItemType(objectValue["type"]?.stringValue) ?: return false
        if (normalizedType.isEmpty()) {
            return false
        }
        return objectValue["content"] != null ||
            objectValue["status"] != null ||
            objectValue["output"] != null ||
            objectValue["changes"] != null ||
            objectValue["files"] != null ||
            objectValue["diff"] != null ||
            objectValue["patch"] != null ||
            objectValue["result"] != null ||
            objectValue["payload"] != null ||
            objectValue["data"] != null
    }

    private fun extractEventObject(paramsObject: JsonObject): JsonObject? {
        return paramsObject["msg"]?.objectValue ?: paramsObject["event"]?.objectValue
    }

    private fun extractTurnId(
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        allowTopLevelId: Boolean = false,
    ): String? {
        return firstNonBlank(
            paramsObject["turn"]?.objectValue?.get("id")?.stringValue,
            paramsObject["turnId"]?.stringValue,
            paramsObject["turn_id"]?.stringValue,
            paramsObject["item"]?.objectValue?.get("turnId")?.stringValue,
            paramsObject["item"]?.objectValue?.get("turn_id")?.stringValue,
            eventObject?.get("turnId")?.stringValue,
            eventObject?.get("turn_id")?.stringValue,
            eventObject?.get("turn")?.objectValue?.get("id")?.stringValue,
            eventObject?.get("item")?.objectValue?.get("turnId")?.stringValue,
            eventObject?.get("item")?.objectValue?.get("turn_id")?.stringValue,
            paramsObject["event"]?.objectValue?.get("turnId")?.stringValue,
            paramsObject["event"]?.objectValue?.get("turn_id")?.stringValue,
            paramsObject["event"]?.objectValue?.get("turn")?.objectValue?.get("id")?.stringValue,
            paramsObject["id"]?.stringValue?.takeIf { allowTopLevelId },
            eventObject?.get("id")?.stringValue?.takeIf { allowTopLevelId },
        )
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
            paramsObject["conversationId"]?.stringValue,
            paramsObject["conversation_id"]?.stringValue,
            paramsObject["thread"]?.objectValue?.get("id")?.stringValue,
            paramsObject["turn"]?.objectValue?.get("threadId")?.stringValue,
            paramsObject["turn"]?.objectValue?.get("thread_id")?.stringValue,
            paramsObject["item"]?.objectValue?.get("threadId")?.stringValue,
            paramsObject["item"]?.objectValue?.get("thread_id")?.stringValue,
            eventObject?.get("threadId")?.stringValue,
            eventObject?.get("thread_id")?.stringValue,
            eventObject?.get("conversationId")?.stringValue,
            eventObject?.get("conversation_id")?.stringValue,
            eventObject?.get("thread")?.objectValue?.get("id")?.stringValue,
            eventObject?.get("turn")?.objectValue?.get("threadId")?.stringValue,
            eventObject?.get("turn")?.objectValue?.get("thread_id")?.stringValue,
            eventObject?.get("item")?.objectValue?.get("threadId")?.stringValue,
            eventObject?.get("item")?.objectValue?.get("thread_id")?.stringValue,
            paramsObject["event"]?.objectValue?.get("threadId")?.stringValue,
            paramsObject["event"]?.objectValue?.get("thread_id")?.stringValue,
            paramsObject["event"]?.objectValue?.get("conversationId")?.stringValue,
            paramsObject["event"]?.objectValue?.get("conversation_id")?.stringValue,
            paramsObject["event"]?.objectValue?.get("thread")?.objectValue?.get("id")?.stringValue,
            paramsObject["event"]?.objectValue?.get("turn")?.objectValue?.get("threadId")?.stringValue,
            paramsObject["event"]?.objectValue?.get("turn")?.objectValue?.get("thread_id")?.stringValue,
        )
        if (directThreadId != null) {
            return directThreadId
        }
        if (turnIdHint != null) {
            conversation.threadIdByTurnId[turnIdHint]?.let { return it }
        }
        if (conversation.activeTurnIdByThread.size == 1) {
            return conversation.activeTurnIdByThread.keys.first()
        }
        if (knownThreadIds.size == 1) {
            return knownThreadIds.first()
        }
        if (knownThreadIds.isEmpty() && conversation.messagesByThread.keys.size <= 1) {
            return conversation.activeThreadId
        }
        return null
    }

    private fun firstStringDeep(key: String, root: JsonValue?, maxDepth: Int = 8): String? {
        if (root == null || maxDepth < 0) {
            return null
        }

        root.objectValue?.let { objectValue ->
            objectValue[key]?.stringValue?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
            for (value in objectValue.values) {
                firstStringDeep(key, value, maxDepth - 1)?.let { return it }
            }
            return null
        }

        root.arrayValue?.let { arrayValue ->
            for (value in arrayValue) {
                firstStringDeep(key, value, maxDepth - 1)?.let { return it }
            }
        }
        return null
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}
