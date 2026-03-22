package app.remodex.android

import app.remodex.android.core.model.CodexCommandExecutionDetails
import app.remodex.android.core.model.CodexCommandExecutionPhase
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexPlanStep
import app.remodex.android.core.model.CodexPlanStepStatus
import app.remodex.android.core.model.CodexTurnTerminalState
import app.remodex.android.core.protocol.JsonValue
import app.remodex.android.core.protocol.RpcMessage
import app.remodex.android.core.protocol.arrayValue
import app.remodex.android.core.protocol.objectValue
import app.remodex.android.core.protocol.stringValue
import kotlinx.serialization.json.JsonObject

object RemodexConversationReducer {
    private data class CommandRunViewState(
        val itemId: String?,
        val phase: CodexCommandExecutionPhase,
        val shortCommand: String,
        val fullCommand: String,
        val structuredSummary: String?,
        val summaryLabel: String,
        val dedupeKey: String,
    )

    private data class CommandExecutionMessageContext(
        val threadId: String,
        val turnId: String?,
        val itemId: String?,
    )

    fun reduce(
        conversation: RemodexConversationState,
        message: RpcMessage,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val normalizedMethod = message.method?.lowercase() ?: return conversation
        val paramsObject = message.params?.objectValue ?: return conversation
        val eventObject = extractEventObject(paramsObject)

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

            "turn/plan/updated" -> reduceTurnPlanUpdated(
                conversation = conversation,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "codex/event/exec_command_begin",
            "codex/event/exec_command_output_delta",
            "codex/event/exec_command_end" -> reduceLegacyCommandExecutionEvent(
                conversation = conversation,
                normalizedMethod = normalizedMethod,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "codex/event/background_event",
            "codex/event/read",
            "codex/event/search",
            "codex/event/list_files" -> reduceEssentialActivityEvent(
                conversation = conversation,
                normalizedMethod = normalizedMethod,
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

            "item/plan/delta" -> reduceSystemDelta(
                conversation = conversation,
                kind = CodexMessageKind.Plan,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "item/reasoning/summarytextdelta",
            "item/reasoning/summarypartadded",
            "item/reasoning/textdelta" -> reduceSystemDelta(
                conversation = conversation,
                kind = CodexMessageKind.Thinking,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "item/filechange/outputdelta",
            "item/filechange/output_delta",
            "turn/diff/updated",
            "codex/event/turn_diff_updated",
            "codex/event/turn_diff" -> reduceRepoAffectingDelta(
                conversation = conversation,
                normalizedMethod = normalizedMethod,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "item/commandexecution/outputdelta",
            "item/commandexecution/output_delta",
            "item/command_execution/outputdelta",
            "item/command_execution/output_delta" -> reduceCommandExecutionDelta(
                conversation = conversation,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "item/toolcall/outputdelta",
            "item/toolcall/output_delta" -> reduceToolCallDelta(
                conversation = conversation,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "item/commandexecution/terminalinteraction",
            "item/command_execution/terminalinteraction" -> reduceCommandExecutionTerminalInteraction(
                conversation = conversation,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
            )

            "serverrequest/resolved" -> reduceServerRequestResolved(
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

        return conversation.markTurnCompleted(
            threadId = threadId,
            turnId = turnId,
            terminalState = parseTurnTerminalState(paramsObject, eventObject),
        )
    }

    private fun reduceTurnPlanUpdated(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val turnId = extractTurnId(paramsObject, eventObject) ?: return conversation
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation

        return conversation.upsertPlanMessage(
            threadId = threadId,
            turnId = turnId,
            itemId = extractItemId(
                paramsObject = paramsObject,
                eventObject = eventObject,
                itemObject = extractItemObject(paramsObject, eventObject),
            ),
            explanation = firstNonBlank(
                paramsObject["explanation"]?.stringValue,
                eventObject?.get("explanation")?.stringValue,
            ),
            steps = decodePlanSteps(
                paramsObject["plan"] ?: eventObject?.get("plan"),
            ),
            isStreaming = true,
        )
    }

    private fun reduceLegacyCommandExecutionEvent(
        conversation: RemodexConversationState,
        normalizedMethod: String,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val payloadObject = eventObject ?: paramsObject
        val eventType = normalizedMethod.substringAfterLast('/')
        val turnId = extractTurnId(paramsObject, eventObject, allowTopLevelId = true)
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation
        val resolvedTurnId = normalizeThreadId(turnId) ?: conversation.activeTurnIdByThread[threadId]
        val state = decodeCommandRunViewState(
            payloadObject = payloadObject,
            paramsObject = paramsObject,
            eventType = eventType,
        )
        val itemId = state.itemId ?: extractItemId(paramsObject, eventObject, extractItemObject(paramsObject, eventObject))

        if (eventType == "exec_command_output_delta") {
            if (!itemId.isNullOrBlank()) {
                val hasExistingRunRow = conversation.messagesFor(threadId).any { message ->
                    message.role == app.remodex.android.core.model.CodexMessageRole.System &&
                        message.kind == CodexMessageKind.CommandExecution &&
                        message.itemId == itemId
                }
                if (!hasExistingRunRow) {
                    return publishCommandExecutionStatus(
                        conversation = conversation,
                        context = CommandExecutionMessageContext(
                            threadId = threadId,
                            turnId = resolvedTurnId,
                            itemId = itemId,
                        ),
                        state = state,
                    )
                }
            }
            return conversation
        }

        return publishCommandExecutionStatus(
            conversation = conversation,
            context = CommandExecutionMessageContext(
                threadId = threadId,
                turnId = resolvedTurnId,
                itemId = itemId,
            ),
            state = state,
        )
    }

    private fun reduceEssentialActivityEvent(
        conversation: RemodexConversationState,
        normalizedMethod: String,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val payloadObject = eventObject ?: paramsObject
        val eventType = normalizedMethod.substringAfterLast('/')
        val line = essentialActivityLine(eventType, payloadObject) ?: return conversation
        val turnId = extractTurnId(paramsObject, eventObject)
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation
        val resolvedTurnId = normalizeThreadId(turnId) ?: conversation.activeTurnIdByThread[threadId]
        return conversation.appendThinkingActivityLine(
            threadId = threadId,
            turnId = resolvedTurnId,
            line = line,
        )
    }

    private fun reduceToolCallDelta(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val delta = extractAssistantDelta(paramsObject, eventObject) ?: return conversation
        if (delta.isBlank()) {
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
        val resolvedTurnId = normalizeThreadId(turnId) ?: conversation.activeTurnIdByThread[threadId]
        val itemObject = extractItemObject(paramsObject, eventObject)

        if (isLikelyFileChangeToolCall(itemObject, delta)) {
            return conversation.appendSystemDelta(
                threadId = threadId,
                kind = CodexMessageKind.FileChange,
                delta = delta,
                turnId = resolvedTurnId,
                itemId = extractItemId(paramsObject, eventObject, itemObject),
            )
        }

        val activityLines = extractToolCallActivityLines(delta)
        if (activityLines.isEmpty()) {
            return conversation
        }

        return activityLines.fold(conversation) { current, line ->
            current.appendThinkingActivityLine(
                threadId = threadId,
                turnId = resolvedTurnId,
                line = line,
            )
        }
    }

    private fun reduceItemStarted(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val itemObject = extractItemObject(paramsObject, eventObject) ?: return conversation
        if (!isAssistantMessageItem(itemObject)) {
            return reduceSystemItemStarted(
                conversation = conversation,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
                itemObject = itemObject,
            )
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
            itemObject != null -> extractSystemItemText(
                itemObject = itemObject,
                paramsObject = paramsObject,
                eventObject = eventObject,
                isCompleted = true,
            )
            else -> extractCompletionFallbackText(paramsObject, eventObject)
        }
        if (text.isBlank()) {
            return conversation
        }

        if (itemObject != null && !isAssistantMessageItem(itemObject)) {
            return reduceSystemItemCompleted(
                conversation = conversation,
                paramsObject = paramsObject,
                eventObject = eventObject,
                knownThreadIds = knownThreadIds,
                itemObject = itemObject,
                text = text,
            )
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

    private fun reduceSystemItemStarted(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
        itemObject: JsonObject,
    ): RemodexConversationState {
        val kind = resolveSystemItemKind(itemObject) ?: return conversation
        val turnId = extractTurnId(paramsObject, eventObject)
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation
        val itemId = extractItemId(paramsObject, eventObject, itemObject)
        val text = extractSystemItemText(
            itemObject = itemObject,
            paramsObject = paramsObject,
            eventObject = eventObject,
            isCompleted = false,
        ).ifBlank { streamingPlaceholderText(kind) }
        val commandDetails = if (kind == CodexMessageKind.CommandExecution) {
            commandExecutionDetails(
                decodeCommandRunViewState(
                    payloadObject = itemObject,
                    paramsObject = paramsObject,
                    eventType = commandExecutionEventType(eventObject, paramsObject),
                ),
            )
        } else {
            null
        }

        return conversation.upsertSystemMessage(
            threadId = threadId,
            kind = kind,
            text = text,
            turnId = turnId,
            itemId = itemId,
            isStreaming = true,
            commandExecutionDetails = commandDetails,
        )
    }

    private fun reduceSystemItemCompleted(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
        itemObject: JsonObject,
        text: String,
    ): RemodexConversationState {
        val kind = resolveSystemItemKind(itemObject) ?: return conversation
        val turnId = extractTurnId(paramsObject, eventObject)
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation
        val itemId = extractItemId(paramsObject, eventObject, itemObject)
        val commandDetails = if (kind == CodexMessageKind.CommandExecution) {
            commandExecutionDetails(
                decodeCommandRunViewState(
                    payloadObject = itemObject,
                    paramsObject = paramsObject,
                    eventType = commandExecutionEventType(eventObject, paramsObject),
                ),
            )
        } else {
            null
        }

        return conversation.completeSystemMessage(
            threadId = threadId,
            kind = kind,
            text = text,
            turnId = turnId,
            itemId = itemId,
            commandExecutionDetails = commandDetails,
        )
    }

    private fun reduceServerRequestResolved(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val requestId = paramsObject["requestId"]
            ?: paramsObject["requestID"]
            ?: eventObject?.get("requestId")
            ?: eventObject?.get("requestID")
            ?: return conversation
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = extractTurnId(paramsObject, eventObject),
        )

        return conversation.removeStructuredUserInputPrompt(
            requestID = requestId,
            threadIdHint = threadId,
        )
    }

    private fun reduceSystemDelta(
        conversation: RemodexConversationState,
        kind: CodexMessageKind,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val turnId = extractTurnId(paramsObject, eventObject)
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation
        val itemId = extractItemId(paramsObject, eventObject, extractItemObject(paramsObject, eventObject))
        val delta = extractAssistantDelta(paramsObject, eventObject) ?: return conversation

        return conversation.appendSystemDelta(
            threadId = threadId,
            kind = kind,
            delta = delta,
            turnId = turnId,
            itemId = itemId,
        )
    }

    private fun reduceCommandExecutionDelta(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val itemObject = extractItemObject(paramsObject, eventObject)
        val payloadObject = itemObject ?: eventObject ?: paramsObject
        val context = resolveCommandExecutionMessageContext(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            itemObject = itemObject,
        ) ?: return conversation

        if (!context.itemId.isNullOrBlank()) {
            val hasCommandHint = extractCommandExecutionCommand(payloadObject) != null ||
                payloadObject["command"] != null ||
                payloadObject["cmd"] != null
            if (!hasCommandHint) {
                val hasExistingRunRow = conversation.messagesFor(context.threadId).any { message ->
                    message.role == app.remodex.android.core.model.CodexMessageRole.System &&
                        message.kind == CodexMessageKind.CommandExecution &&
                        message.itemId == context.itemId
                }
                if (hasExistingRunRow) {
                    return conversation
                }
            }
        }

        return publishCommandExecutionStatus(
            conversation = conversation,
            context = context,
            state = decodeCommandRunViewState(
                payloadObject = payloadObject,
                paramsObject = paramsObject,
                eventType = commandExecutionEventType(eventObject, paramsObject),
            ),
        )
    }

    private fun reduceRepoAffectingDelta(
        conversation: RemodexConversationState,
        normalizedMethod: String,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val itemObject = extractItemObject(paramsObject, eventObject)
        val kind = when {
            normalizedMethod.contains("commandexecution") -> CodexMessageKind.CommandExecution
            normalizedMethod.contains("filechange") || normalizedMethod.contains("diff") -> CodexMessageKind.FileChange
            itemObject != null -> resolveSystemItemKind(itemObject)
            else -> null
        } ?: return conversation

        val turnId = extractTurnId(paramsObject, eventObject)
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return conversation
        val itemId = extractItemId(paramsObject, eventObject, itemObject)
        val delta = when (kind) {
            CodexMessageKind.FileChange -> extractFileChangeDelta(paramsObject, eventObject)
            CodexMessageKind.CommandExecution -> extractCommandExecutionDelta(paramsObject, eventObject)
            else -> extractCompletionFallbackText(paramsObject, eventObject)
        }

        return conversation.appendSystemDelta(
            threadId = threadId,
            kind = kind,
            delta = delta,
            turnId = turnId,
            itemId = itemId,
        )
    }

    private fun reduceCommandExecutionTerminalInteraction(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
    ): RemodexConversationState {
        val context = resolveCommandExecutionMessageContext(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
        ) ?: return conversation
        val itemId = context.itemId ?: return conversation
        val state = decodeCommandRunViewState(
            payloadObject = eventObject ?: paramsObject,
            paramsObject = paramsObject,
            eventType = commandExecutionEventType(eventObject, paramsObject),
        )
        val existingRunRow = conversation.messagesFor(context.threadId).firstOrNull { message ->
            message.role == app.remodex.android.core.model.CodexMessageRole.System &&
                message.kind == CodexMessageKind.CommandExecution &&
                message.itemId == itemId
        }

        if (existingRunRow != null) {
            if (!existingRunRow.isStreaming && state.phase == CodexCommandExecutionPhase.Running) {
                return conversation
            }
            if (state.shortCommand.lowercase() != "command" || state.phase != CodexCommandExecutionPhase.Running) {
                return publishCommandExecutionStatus(
                    conversation = conversation,
                    context = context,
                    state = state,
                )
            }
            return conversation
        }

        return publishCommandExecutionStatus(
            conversation = conversation,
            context = context,
            state = state,
        )
    }

    private fun publishCommandExecutionStatus(
        conversation: RemodexConversationState,
        context: CommandExecutionMessageContext,
        state: CommandRunViewState,
    ): RemodexConversationState {
        val statusText = commandExecutionStatusText(state)
        val details = commandExecutionDetails(state)
        val isStreaming = state.phase == CodexCommandExecutionPhase.Running
        if (!context.itemId.isNullOrBlank()) {
            return if (isStreaming) {
                conversation.upsertSystemMessage(
                    threadId = context.threadId,
                    kind = CodexMessageKind.CommandExecution,
                    text = statusText,
                    turnId = context.turnId,
                    itemId = context.itemId,
                    isStreaming = true,
                    commandExecutionDetails = details,
                )
            } else {
                conversation.completeSystemMessage(
                    threadId = context.threadId,
                    kind = CodexMessageKind.CommandExecution,
                    text = statusText,
                    turnId = context.turnId,
                    itemId = context.itemId,
                    commandExecutionDetails = details,
                )
            }
        }

        if (!context.turnId.isNullOrBlank()) {
            return if (isStreaming) {
                conversation.upsertSystemMessage(
                    threadId = context.threadId,
                    kind = CodexMessageKind.CommandExecution,
                    text = statusText,
                    turnId = context.turnId,
                    isStreaming = true,
                    commandExecutionDetails = details,
                )
            } else {
                conversation.completeSystemMessage(
                    threadId = context.threadId,
                    kind = CodexMessageKind.CommandExecution,
                    text = statusText,
                    turnId = context.turnId,
                    commandExecutionDetails = details,
                )
            }
        }

        return conversation.appendSystemMessage(
            threadId = context.threadId,
            kind = CodexMessageKind.CommandExecution,
            text = statusText,
            turnId = context.turnId,
            itemId = context.itemId,
            isStreaming = isStreaming,
            commandExecutionDetails = details,
        )
    }

    private fun resolveCommandExecutionMessageContext(
        conversation: RemodexConversationState,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        knownThreadIds: Set<String>,
        itemObject: JsonObject? = null,
    ): CommandExecutionMessageContext? {
        val turnId = extractTurnId(paramsObject, eventObject, allowTopLevelId = true)
        val threadId = resolveThreadId(
            conversation = conversation,
            paramsObject = paramsObject,
            eventObject = eventObject,
            knownThreadIds = knownThreadIds,
            turnIdHint = turnId,
        ) ?: return null
        val resolvedTurnId = normalizeThreadId(turnId) ?: conversation.activeTurnIdByThread[threadId]
        val itemId = extractItemId(paramsObject, eventObject, itemObject)
        return CommandExecutionMessageContext(
            threadId = threadId,
            turnId = resolvedTurnId,
            itemId = itemId,
        )
    }

    private fun commandExecutionEventType(eventObject: JsonObject?, paramsObject: JsonObject): String? {
        return firstNonBlank(
            eventObject?.get("type")?.stringValue,
            eventObject?.get("event_type")?.stringValue,
            paramsObject["type"]?.stringValue,
            paramsObject["event_type"]?.stringValue,
        )?.lowercase()
    }

    private fun commandExecutionStatusText(state: CommandRunViewState): String {
        val phase = when (state.phase) {
            CodexCommandExecutionPhase.Running -> "running"
            CodexCommandExecutionPhase.Completed -> "completed"
            CodexCommandExecutionPhase.Failed -> "failed"
            CodexCommandExecutionPhase.Stopped -> "stopped"
        }
        return "$phase ${state.summaryLabel}"
    }

    private fun commandExecutionDetails(state: CommandRunViewState): CodexCommandExecutionDetails {
        return CodexCommandExecutionDetails(
            rawCommand = state.fullCommand,
            summary = state.structuredSummary,
            dedupeKey = state.dedupeKey,
            phase = state.phase,
        )
    }

    private fun decodeCommandRunViewState(
        payloadObject: JsonObject,
        paramsObject: JsonObject?,
        eventType: String?,
    ): CommandRunViewState {
        val status = firstNonBlank(
            payloadObject["status"]?.stringValue,
            payloadObject["result"]?.objectValue?.get("status")?.stringValue,
            payloadObject["output"]?.objectValue?.get("status")?.stringValue,
            paramsObject?.get("status")?.stringValue,
            paramsObject?.get("event")?.objectValue?.get("status")?.stringValue,
        )
        val phase = commandRunPhase(status, eventType)
        val rawCommand = extractCommandExecutionCommand(payloadObject) ?: "command"
        val shortCommand = shortCommandPreview(rawCommand)
        val structuredSummary = RemodexCommandSummaryParser.summarize(rawCommand)
        val itemId = firstNonBlank(
            payloadObject["id"]?.stringValue,
            payloadObject["call_id"]?.stringValue,
            payloadObject["callId"]?.stringValue,
            paramsObject?.get("itemId")?.stringValue,
            paramsObject?.get("item_id")?.stringValue,
        )
        return CommandRunViewState(
            itemId = itemId,
            phase = phase,
            shortCommand = shortCommand,
            fullCommand = rawCommand,
            structuredSummary = structuredSummary?.summary,
            summaryLabel = structuredSummary?.summary ?: shortCommand,
            dedupeKey = structuredSummary?.dedupeKey
                ?: rawCommand.trim().replace(Regex("\\s+"), " ").lowercase(),
        )
    }

    private fun extractCommandExecutionCommand(itemObject: JsonObject): String? {
        extractLegacyCommandArray(itemObject["command"])?.let { return it }

        val candidates = listOf("command", "cmd", "raw_command", "rawCommand", "input", "invocation")
        for (key in candidates) {
            firstStringDeep(key, itemObject)?.let { return it }
        }
        return null
    }

    private fun extractLegacyCommandArray(value: JsonValue?): String? {
        val array = value?.arrayValue ?: return value?.stringValue?.trim()?.takeIf(String::isNotEmpty)
        val parts = array.mapNotNull { item ->
            item.stringValue?.trim()?.takeIf(String::isNotEmpty)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(separator = " ")
    }

    private fun shortCommandPreview(rawCommand: String, maxLength: Int = 92): String {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) {
            return "command"
        }
        val compact = trimmed.replace(Regex("\\s+"), " ")
        val unwrapped = unwrapShellCommandIfPresent(compact)
        var preview = unwrapped.replace(Regex("\\s+"), " ").trim()
        if (preview.isEmpty()) {
            preview = "command"
        }
        if (preview.length > maxLength) {
            preview = preview.take(maxLength - 1) + "..."
        }
        return preview
    }

    private fun unwrapShellCommandIfPresent(command: String): String {
        val tokens = command.split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.isEmpty()) {
            return command
        }

        val shellNames = listOf("bash", "zsh", "sh", "fish")
        var shellIndex = 0
        if (tokens.size >= 2) {
            val first = tokens[0].lowercase()
            val second = tokens[1].lowercase()
            if ((first == "env" || first.endsWith("/env")) &&
                shellNames.any { second == it || second.endsWith("/$it") }
            ) {
                shellIndex = 1
            }
        }

        val shell = tokens[shellIndex].lowercase()
        if (shellNames.none { shell == it || shell.endsWith("/$it") }) {
            return command
        }

        var index = shellIndex + 1
        while (index < tokens.size) {
            val token = tokens[index]
            if (token == "-c" || token == "-lc" || token == "-cl" || token == "-ic" || token == "-ci") {
                index += 1
                return if (index < tokens.size) {
                    stripWrappingQuotes(tokens.drop(index).joinToString(separator = " "))
                } else {
                    command
                }
            }
            if (token.startsWith("-")) {
                index += 1
                continue
            }
            return stripWrappingQuotes(tokens.drop(index).joinToString(separator = " "))
        }

        return command
    }

    private fun stripWrappingQuotes(input: String): String {
        val trimmed = input.trim()
        if (trimmed.length < 2) {
            return trimmed
        }
        return if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))
        ) {
            trimmed.drop(1).dropLast(1)
        } else {
            trimmed
        }
    }

    private fun commandRunPhase(rawStatus: String?, eventType: String?): CodexCommandExecutionPhase {
        val normalizedStatus = rawStatus?.trim()?.lowercase().orEmpty()
        val normalizedEventType = eventType?.trim()?.lowercase().orEmpty()
        return when {
            normalizedStatus.contains("fail") || normalizedStatus.contains("error") -> CodexCommandExecutionPhase.Failed
            normalizedStatus.contains("cancel") ||
                normalizedStatus.contains("abort") ||
                normalizedStatus.contains("interrupt") -> CodexCommandExecutionPhase.Stopped
            normalizedStatus.contains("complete") ||
                normalizedStatus.contains("success") ||
                normalizedStatus.contains("done") -> CodexCommandExecutionPhase.Completed
            normalizedEventType == "exec_command_end" -> CodexCommandExecutionPhase.Completed
            else -> CodexCommandExecutionPhase.Running
        }
    }

    private fun essentialActivityLine(eventType: String, payloadObject: JsonObject): String? {
        return when (eventType) {
            "background_event" -> {
                val message = firstNonBlank(
                    payloadObject["message"]?.stringValue,
                    payloadObject["text"]?.stringValue,
                    payloadObject["body"]?.stringValue,
                    firstStringDeep("message", payloadObject),
                    firstStringDeep("text", payloadObject),
                    firstStringDeep("body", payloadObject),
                ) ?: return null
                if (message.length > 140) {
                    return null
                }
                message
            }

            "read" -> firstNonBlank(
                firstStringDeep("path", payloadObject),
                firstStringDeep("file_path", payloadObject),
                firstStringDeep("file", payloadObject),
            )?.let { "Read $it" } ?: "Read file"

            "search" -> firstNonBlank(
                firstStringDeep("query", payloadObject),
                firstStringDeep("pattern", payloadObject),
                firstStringDeep("regex", payloadObject),
            )?.let { "Search $it" } ?: "Search files"

            "list_files" -> firstNonBlank(
                firstStringDeep("path", payloadObject),
                firstStringDeep("cwd", payloadObject),
            )?.let { "List files $it" } ?: "List files"

            else -> null
        }
    }

    private fun extractToolCallActivityLines(delta: String): List<String> {
        val acceptedPrefixes = listOf(
            "running ",
            "read ",
            "search ",
            "searched ",
            "exploring ",
            "list ",
            "listing ",
            "open ",
            "opened ",
            "find ",
            "finding ",
            "edit ",
            "edited ",
            "write ",
            "wrote ",
            "apply ",
            "applied ",
        )

        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (line in delta.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                continue
            }
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))
            ) {
                continue
            }
            val normalized = trimmed.lowercase()
            if (acceptedPrefixes.none(normalized::startsWith)) {
                continue
            }
            if (seen.add(normalized)) {
                result += trimmed
            }
        }
        return result
    }

    private fun isLikelyFileChangeToolCall(itemObject: JsonObject?, fallbackText: String?): Boolean {
        if (itemObject == null) {
            return looksLikePatchText(fallbackText.orEmpty())
        }

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
        val hasDiffPayload = looksLikePatchText(
            firstNonBlank(
                itemObject["diff"]?.stringValue,
                itemObject["unified_diff"]?.stringValue,
                itemObject["patch"]?.stringValue,
                firstStringDeep("diff", itemObject),
                firstStringDeep("unified_diff", itemObject),
                firstStringDeep("patch", itemObject),
            ).orEmpty(),
        )
        val hasPatchLikeText = looksLikePatchText(fallbackText.orEmpty())
        return (hasToolHint && (hasStructuredChanges || hasDiffPayload || hasPatchLikeText)) ||
            hasDiffPayload ||
            hasPatchLikeText
    }

    private fun looksLikePatchText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return false
        }
        return trimmed.contains("diff --git ") ||
            trimmed.contains("\n@@ ") ||
            trimmed.startsWith("@@ ") ||
            (trimmed.contains("\n+++ ") && trimmed.contains("\n--- ")) ||
            (trimmed.contains("\nPath: ") && trimmed.contains("\nKind: "))
    }

    private fun extractEventObject(paramsObject: JsonObject): JsonObject? {
        return paramsObject["msg"]?.objectValue ?: paramsObject["event"]?.objectValue
    }

    private fun decodePlanSteps(value: JsonValue?): List<CodexPlanStep> {
        val items = value?.arrayValue ?: return emptyList()
        return items.mapIndexedNotNull { index, itemValue ->
            val itemObject = itemValue.objectValue ?: return@mapIndexedNotNull null
            val step = itemObject["step"]?.stringValue?.trim().orEmpty()
            val rawStatus = itemObject["status"]?.stringValue?.trim().orEmpty()
            val status = when (rawStatus) {
                "pending" -> CodexPlanStepStatus.Pending
                "in_progress" -> CodexPlanStepStatus.InProgress
                "completed" -> CodexPlanStepStatus.Completed
                else -> null
            } ?: return@mapIndexedNotNull null

            if (step.isEmpty()) {
                return@mapIndexedNotNull null
            }

            CodexPlanStep(
                id = itemObject["id"]?.stringValue?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "plan-step-$index",
                step = step,
                status = status,
            )
        }
    }

    private fun extractItemObject(paramsObject: JsonObject, eventObject: JsonObject?): JsonObject? {
        return paramsObject["item"]?.objectValue
            ?: eventObject?.get("item")?.objectValue
            ?: paramsObject["event"]?.objectValue?.get("item")?.objectValue
            ?: paramsObject.takeIf(::isLikelyIncomingItemPayload)
            ?: eventObject?.takeIf(::isLikelyIncomingItemPayload)
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

    private fun extractItemId(
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        itemObject: JsonObject?,
    ): String? {
        return firstNonBlank(
            itemObject?.get("id")?.stringValue,
            itemObject?.get("itemId")?.stringValue,
            itemObject?.get("item_id")?.stringValue,
            itemObject?.get("messageId")?.stringValue,
            itemObject?.get("message_id")?.stringValue,
            paramsObject["itemId"]?.stringValue,
            paramsObject["item_id"]?.stringValue,
            paramsObject["messageId"]?.stringValue,
            paramsObject["message_id"]?.stringValue,
            paramsObject["item"]?.objectValue?.get("id")?.stringValue,
            paramsObject["item"]?.objectValue?.get("itemId")?.stringValue,
            paramsObject["item"]?.objectValue?.get("item_id")?.stringValue,
            paramsObject["item"]?.objectValue?.get("messageId")?.stringValue,
            paramsObject["item"]?.objectValue?.get("message_id")?.stringValue,
            eventObject?.get("itemId")?.stringValue,
            eventObject?.get("item_id")?.stringValue,
            eventObject?.get("messageId")?.stringValue,
            eventObject?.get("message_id")?.stringValue,
            eventObject?.get("item")?.objectValue?.get("id")?.stringValue,
            eventObject?.get("item")?.objectValue?.get("itemId")?.stringValue,
            eventObject?.get("item")?.objectValue?.get("item_id")?.stringValue,
            eventObject?.get("item")?.objectValue?.get("messageId")?.stringValue,
            eventObject?.get("item")?.objectValue?.get("message_id")?.stringValue,
            paramsObject["event"]?.objectValue?.get("item")?.objectValue?.get("id")?.stringValue,
            paramsObject["event"]?.objectValue?.get("messageId")?.stringValue,
            paramsObject["event"]?.objectValue?.get("message_id")?.stringValue,
            eventObject?.get("id")?.stringValue,
        )
    }

    private fun extractAssistantDelta(paramsObject: JsonObject, eventObject: JsonObject?): String? {
        return firstNonEmptyPreservingWhitespace(
            paramsObject["delta"]?.stringValue,
            paramsObject["textDelta"]?.stringValue,
            paramsObject["text_delta"]?.stringValue,
            paramsObject["text"]?.stringValue,
            paramsObject["summary"]?.stringValue,
            paramsObject["part"]?.stringValue,
            eventObject?.get("delta")?.stringValue,
            eventObject?.get("textDelta")?.stringValue,
            eventObject?.get("text_delta")?.stringValue,
            eventObject?.get("text")?.stringValue,
            eventObject?.get("summary")?.stringValue,
            eventObject?.get("part")?.stringValue,
            paramsObject["event"]?.objectValue?.get("delta")?.stringValue,
            paramsObject["event"]?.objectValue?.get("textDelta")?.stringValue,
            paramsObject["event"]?.objectValue?.get("text_delta")?.stringValue,
            paramsObject["event"]?.objectValue?.get("text")?.stringValue,
            paramsObject["event"]?.objectValue?.get("summary")?.stringValue,
            paramsObject["event"]?.objectValue?.get("part")?.stringValue,
        )
    }

    private fun extractFileChangeDelta(paramsObject: JsonObject, eventObject: JsonObject?): String {
        val directDiff = firstNonBlank(
            paramsObject["diff"]?.stringValue,
            paramsObject["unified_diff"]?.stringValue,
            paramsObject["patch"]?.stringValue,
            eventObject?.get("diff")?.stringValue,
            eventObject?.get("unified_diff")?.stringValue,
            eventObject?.get("patch")?.stringValue,
        )
        if (!directDiff.isNullOrBlank()) {
            return directDiff
        }

        return firstNonBlank(
            paramsObject["delta"]?.stringValue,
            eventObject?.get("delta")?.stringValue,
            paramsObject["event"]?.objectValue?.get("delta")?.stringValue,
        ).orEmpty()
    }

    private fun extractCommandExecutionDelta(paramsObject: JsonObject, eventObject: JsonObject?): String {
        return firstNonBlank(
            paramsObject["delta"]?.stringValue,
            eventObject?.get("delta")?.stringValue,
            paramsObject["output"]?.stringValue,
            eventObject?.get("output")?.stringValue,
            paramsObject["stderr"]?.stringValue,
            paramsObject["stdout"]?.stringValue,
        ).orEmpty()
    }

    private fun extractCompletionFallbackText(paramsObject: JsonObject, eventObject: JsonObject?): String {
        return firstNonBlank(
            paramsObject["message"]?.stringValue,
            eventObject?.get("message")?.stringValue,
            paramsObject["text"]?.stringValue,
            eventObject?.get("text")?.stringValue,
        ).orEmpty()
    }

    private fun parseTurnTerminalState(
        paramsObject: JsonObject,
        eventObject: JsonObject?,
    ): CodexTurnTerminalState {
        if (extractFailureMessage(paramsObject, eventObject) != null) {
            return CodexTurnTerminalState.Failed
        }

        val turnObject = paramsObject["turn"]?.objectValue
        val statusObject = turnObject?.get("status")?.objectValue
            ?: paramsObject["status"]?.objectValue
            ?: eventObject?.get("status")?.objectValue

        val rawStatus = firstNonBlank(
            turnObject?.get("status")?.stringValue,
            paramsObject["status"]?.stringValue,
            eventObject?.get("status")?.stringValue,
            statusObject?.get("type")?.stringValue,
            statusObject?.get("statusType")?.stringValue,
            statusObject?.get("status_type")?.stringValue,
        ).orEmpty()

        val normalizedStatus = rawStatus
            .trim()
            .lowercase()
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")

        if (normalizedStatus.contains("cancel")
            || normalizedStatus.contains("abort")
            || normalizedStatus.contains("interrupt")
            || normalizedStatus.contains("stopped")
        ) {
            return CodexTurnTerminalState.Stopped
        }
        if (normalizedStatus.contains("fail") || normalizedStatus.contains("error")) {
            return CodexTurnTerminalState.Failed
        }
        return CodexTurnTerminalState.Completed
    }

    private fun extractFailureMessage(paramsObject: JsonObject, eventObject: JsonObject?): String? {
        val turnObject = paramsObject["turn"]?.objectValue
        val status = firstNonBlank(
            turnObject?.get("status")?.stringValue,
            paramsObject["status"]?.stringValue,
            eventObject?.get("status")?.stringValue,
        )

        val failedStatus = status
            ?.trim()
            ?.lowercase()
            ?.replace("_", "")
            ?.replace("-", "")
            ?.replace(" ", "")
            ?.contains("fail") == true

        if (!failedStatus) {
            return firstNonBlank(
                turnObject?.get("error")?.objectValue?.get("message")?.stringValue,
                paramsObject["error"]?.objectValue?.get("message")?.stringValue,
                paramsObject["errorMessage"]?.stringValue,
                eventObject?.get("error")?.objectValue?.get("message")?.stringValue,
            )
        }

        return firstNonBlank(
            turnObject?.get("error")?.objectValue?.get("message")?.stringValue,
            paramsObject["error"]?.objectValue?.get("message")?.stringValue,
            paramsObject["errorMessage"]?.stringValue,
            eventObject?.get("error")?.objectValue?.get("message")?.stringValue,
            "Turn failed with no details",
        )
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
                if (contentType == "skill") {
                    val resolvedSkill = firstNonBlank(
                        contentObject["id"]?.stringValue,
                        contentObject["name"]?.stringValue,
                    )
                    if (resolvedSkill != null) {
                        add("\$$resolvedSkill")
                    }
                    continue
                }
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

    private fun resolveSystemItemKind(itemObject: JsonObject): CodexMessageKind? {
        return when (normalizeItemType(itemObject["type"]?.stringValue)) {
            "reasoning" -> CodexMessageKind.Thinking
            "filechange", "diff" -> CodexMessageKind.FileChange
            "commandexecution" -> CodexMessageKind.CommandExecution
            "toolcall" -> {
                if (isRepoAffectingToolCall(itemObject)) {
                    CodexMessageKind.FileChange
                } else {
                    null
                }
            }
            "plan" -> CodexMessageKind.Plan
            else -> null
        }
    }

    private fun extractSystemItemText(
        itemObject: JsonObject,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        isCompleted: Boolean,
    ): String {
        val kind = resolveSystemItemKind(itemObject) ?: return ""
        val extractedText = when (kind) {
            CodexMessageKind.CommandExecution -> extractCommandExecutionText(itemObject, paramsObject, eventObject, isCompleted)
            CodexMessageKind.FileChange -> extractFileChangeText(itemObject, paramsObject, eventObject, isCompleted)
            CodexMessageKind.Thinking, CodexMessageKind.Plan, CodexMessageKind.UserInputPrompt, CodexMessageKind.Chat ->
                extractMessageText(itemObject)
        }
        return extractedText.ifBlank {
            when (kind) {
                CodexMessageKind.FileChange, CodexMessageKind.CommandExecution -> streamingPlaceholderText(kind)
                else -> ""
            }
        }
    }

    private fun extractCommandExecutionText(
        itemObject: JsonObject,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        isCompleted: Boolean,
    ): String {
        val state = decodeCommandRunViewState(
            payloadObject = itemObject,
            paramsObject = paramsObject,
            eventType = if (isCompleted) "exec_command_end" else "exec_command_begin",
        )
        return commandExecutionStatusText(state)
    }

    private fun extractFileChangeText(
        itemObject: JsonObject,
        paramsObject: JsonObject,
        eventObject: JsonObject?,
        isCompleted: Boolean,
    ): String {
        val status = firstNonBlank(
            itemObject["status"]?.stringValue,
            paramsObject["status"]?.stringValue,
            eventObject?.get("status")?.stringValue,
        )?.trim().takeUnless { it.isNullOrEmpty() }
            ?: if (isCompleted) "completed" else "inProgress"

        val patch = firstNonBlank(
            itemObject["diff"]?.stringValue,
            itemObject["unified_diff"]?.stringValue,
            itemObject["patch"]?.stringValue,
            paramsObject["diff"]?.stringValue,
            paramsObject["unified_diff"]?.stringValue,
            paramsObject["patch"]?.stringValue,
            eventObject?.get("diff")?.stringValue,
            eventObject?.get("unified_diff")?.stringValue,
            eventObject?.get("patch")?.stringValue,
        )
        if (!patch.isNullOrBlank() && RemodexFileChangeBodyFormatter.looksLikePatchText(patch)) {
            return RemodexFileChangeBodyFormatter.renderUnifiedDiffBody(patch, status)
        }

        if (itemObject["changes"] != null) {
            val body = RemodexFileChangeBodyFormatter.decodeFileChangeItemBody(itemObject)
            if (body.isNotBlank()) {
                return body
            }
        }

        val outputText = firstNonBlank(
            itemObject["output"]?.stringValue,
            itemObject["text"]?.stringValue,
            itemObject["message"]?.stringValue,
            paramsObject["output"]?.stringValue,
            eventObject?.get("output")?.stringValue,
        )
        if (!outputText.isNullOrBlank()) {
            return if (RemodexFileChangeBodyFormatter.looksLikePatchText(outputText)) {
                RemodexFileChangeBodyFormatter.renderUnifiedDiffBody(outputText, status)
            } else {
                outputText
            }
        }

        return if (isCompleted) {
            "File changes applied."
        } else {
            "Applying file changes..."
        }
    }

    private fun isRepoAffectingToolCall(itemObject: JsonObject): Boolean {
        val descriptor = firstNonBlank(
            itemObject["tool"]?.stringValue,
            itemObject["name"]?.stringValue,
            itemObject["title"]?.stringValue,
            itemObject["label"]?.stringValue,
        ).orEmpty().lowercase()
        if (descriptor.contains("filechange")
            || descriptor.contains("diff")
            || descriptor.contains("patch")
            || descriptor.contains("edit")
            || descriptor.contains("write")
        ) {
            return true
        }

        return itemObject["changes"] != null ||
            itemObject["files"] != null ||
            itemObject["diff"] != null ||
            itemObject["unified_diff"] != null ||
            itemObject["patch"] != null
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
            val mappedThreadId = conversation.threadIdByTurnId[turnIdHint]
            if (mappedThreadId != null) {
                return mappedThreadId
            }
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

    private fun normalizeThreadId(threadId: String?): String? {
        val trimmed = threadId?.trim().orEmpty()
        return trimmed.ifEmpty { null }
    }

    private fun firstStringDeep(key: String, root: JsonValue?, maxDepth: Int = 8): String? {
        if (root == null || maxDepth < 0) {
            return null
        }

        val objectValue = root.objectValue
        if (objectValue != null) {
            objectValue[key]?.stringValue?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
            for (value in objectValue.values) {
                firstStringDeep(key, value, maxDepth - 1)?.let { return it }
            }
            return null
        }

        val arrayValue = root.arrayValue
        if (arrayValue != null) {
            for (value in arrayValue) {
                firstStringDeep(key, value, maxDepth - 1)?.let { return it }
            }
        }
        return null
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun firstNonEmptyPreservingWhitespace(vararg values: String?): String? {
        return values.firstOrNull { it?.isNotEmpty() == true }
    }
}
