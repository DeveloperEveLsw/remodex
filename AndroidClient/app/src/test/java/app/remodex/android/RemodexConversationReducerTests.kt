package app.remodex.android

import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexCommandExecutionPhase
import app.remodex.android.core.model.CodexThreadRunBadgeState
import app.remodex.android.core.protocol.RpcMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemodexConversationReducerTests {
    @Test
    fun reducerStreamsAssistantMessageIntoPerThreadTimeline() {
        var conversation = RemodexConversationState().withActiveThread("thread-1")
        val knownThreadIds = setOf("thread-1")

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("item-1"),
                        "type" to JsonPrimitive("agentMessage"),
                        "role" to JsonPrimitive("assistant"),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/agentMessage/delta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("item-1"),
                        "type" to JsonPrimitive("agentMessage"),
                        "role" to JsonPrimitive("assistant"),
                    ),
                    "delta" to JsonPrimitive("Hello"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("item-1"),
                        "type" to JsonPrimitive("agentMessage"),
                        "role" to JsonPrimitive("assistant"),
                        "text" to JsonPrimitive("Hello world"),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val assistantMessage = conversation.messagesFor("thread-1").single()
        assertEquals("Hello world", assistantMessage.text)
        assertFalse(assistantMessage.isStreaming)
        assertEquals("turn-1", assistantMessage.turnId)
        assertEquals("item-1", assistantMessage.itemId)
        assertTrue(conversation.messageRevisionFor("thread-1") >= 3)
    }

    @Test
    fun reducerFinalizesSeparateAssistantItemsWhenTurnCompletes() {
        var conversation = RemodexConversationState().withActiveThread("thread-1")
        val knownThreadIds = setOf("thread-1")

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/agentMessage/delta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("item-1"),
                        "type" to JsonPrimitive("agentMessage"),
                        "role" to JsonPrimitive("assistant"),
                    ),
                    "delta" to JsonPrimitive("First"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/agentMessage/delta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("item-2"),
                        "type" to JsonPrimitive("agentMessage"),
                        "role" to JsonPrimitive("assistant"),
                    ),
                    "delta" to JsonPrimitive("Second"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "status" to JsonPrimitive("completed"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val assistantMessages = conversation.messagesFor("thread-1").filter { it.role == CodexMessageRole.Assistant }
        assertEquals(2, assistantMessages.size)
        assertTrue(assistantMessages.all { !it.isStreaming })
    }

    @Test
    fun reducerPreservesLeadingSpacesInAssistantStreamingDeltas() {
        var conversation = RemodexConversationState().withActiveThread("thread-1")
        val knownThreadIds = setOf("thread-1")

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("item-1"),
                        "type" to JsonPrimitive("agentMessage"),
                        "role" to JsonPrimitive("assistant"),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/agentMessage/delta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("item-1"),
                        "type" to JsonPrimitive("agentMessage"),
                        "role" to JsonPrimitive("assistant"),
                    ),
                    "delta" to JsonPrimitive("Hello"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/agentMessage/delta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("item-1"),
                        "type" to JsonPrimitive("agentMessage"),
                        "role" to JsonPrimitive("assistant"),
                    ),
                    "delta" to JsonPrimitive(" world"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        assertEquals("Hello world", conversation.messagesFor("thread-1").single().text)
    }

    @Test
    fun reducerAcceptsLegacyMsgEnvelopeForReasoningDelta() {
        var conversation = RemodexConversationState().withActiveThread("thread-1")
        val knownThreadIds = setOf("thread-1")

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/reasoning/textDelta",
                params = jsonObject(
                    "msg" to jsonObject(
                        "threadId" to JsonPrimitive("thread-1"),
                        "turnId" to JsonPrimitive("turn-1"),
                        "item" to jsonObject(
                            "id" to JsonPrimitive("reasoning-1"),
                            "type" to JsonPrimitive("reasoning"),
                        ),
                        "delta" to JsonPrimitive("Reviewing files"),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val messages = conversation.messagesFor("thread-1")
        assertEquals(1, messages.size)
        assertEquals("Reviewing files", messages.single().text)
    }

    @Test
    fun reducerIncludesSkillContentItemsInCompletedAssistantMessages() {
        val conversation = RemodexConversationReducer.reduce(
            conversation = RemodexConversationState().withActiveThread("thread-1"),
            message = RpcMessage.notification(
                method = "item/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("item-1"),
                        "type" to JsonPrimitive("agentMessage"),
                        "role" to JsonPrimitive("assistant"),
                        "content" to jsonArray(
                            jsonObject(
                                "type" to JsonPrimitive("text"),
                                "text" to JsonPrimitive("Use"),
                            ),
                            jsonObject(
                                "type" to JsonPrimitive("skill"),
                                "id" to JsonPrimitive("skill-builder"),
                            ),
                            jsonObject(
                                "type" to JsonPrimitive("text"),
                                "text" to JsonPrimitive("for this step"),
                            ),
                        ),
                    ),
                ),
            ),
            knownThreadIds = setOf("thread-1"),
        )

        assertEquals("Use\n\$skill-builder\nfor this step", conversation.messagesFor("thread-1").single().text)
    }

    @Test
    fun reducerKeepsPerThreadRunningFallbackWhenTurnIdIsMissing() {
        val conversation = RemodexConversationReducer.reduce(
            conversation = RemodexConversationState(),
            message = RpcMessage.notification(
                method = "turn/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                ),
            ),
            knownThreadIds = setOf("thread-1"),
        )

        assertTrue(conversation.runningThreadIds.contains("thread-1"))
        assertEquals(null, conversation.activeTurnIdByThread["thread-1"])
    }

    @Test
    fun reducerMarksCompletedInactiveThreadAsReady() {
        val conversation = RemodexConversationReducer.reduce(
            conversation = RemodexConversationState(),
            message = RpcMessage.notification(
                method = "turn/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "status" to JsonPrimitive("completed"),
                ),
            ),
            knownThreadIds = setOf("thread-1"),
        )

        assertEquals(CodexThreadRunBadgeState.Ready, conversation.threadRunBadgeState("thread-1"))
    }

    @Test
    fun reducerDoesNotMarkActiveThreadAsUnreadWhenCompleted() {
        val conversation = RemodexConversationReducer.reduce(
            conversation = RemodexConversationState().withActiveThread("thread-1"),
            message = RpcMessage.notification(
                method = "turn/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "status" to JsonPrimitive("completed"),
                ),
            ),
            knownThreadIds = setOf("thread-1"),
        )

        assertEquals(null, conversation.threadRunBadgeState("thread-1"))
    }

    @Test
    fun reducerMarksFailedThreadAsFailedBadge() {
        val conversation = RemodexConversationReducer.reduce(
            conversation = RemodexConversationState(),
            message = RpcMessage.notification(
                method = "turn/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "status" to JsonPrimitive("failed"),
                    "error" to jsonObject(
                        "message" to JsonPrimitive("Boom"),
                    ),
                ),
            ),
            knownThreadIds = setOf("thread-1"),
        )

        assertEquals(CodexThreadRunBadgeState.Failed, conversation.threadRunBadgeState("thread-1"))
    }

    @Test
    fun reducerMergesLegacyBeginAndModernItemStartedIntoSingleCommandRow() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/exec_command_begin",
                params = jsonObject(
                    "conversationId" to JsonPrimitive("thread-1"),
                    "id" to JsonPrimitive("turn-1"),
                    "msg" to jsonObject(
                        "type" to JsonPrimitive("exec_command_begin"),
                        "call_id" to JsonPrimitive("call-1"),
                        "turn_id" to JsonPrimitive("turn-1"),
                        "command" to jsonArray(
                            JsonPrimitive("/bin/zsh"),
                            JsonPrimitive("-lc"),
                            JsonPrimitive("echo one"),
                        ),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("call-1"),
                        "type" to JsonPrimitive("commandExecution"),
                        "status" to JsonPrimitive("inProgress"),
                        "command" to JsonPrimitive("/bin/zsh -lc \"echo one\""),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val runRows = conversation.messagesFor("thread-1").filter {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.CommandExecution
        }
        assertEquals(1, runRows.size)
        assertTrue(runRows.single().text.lowercase().startsWith("running "))
    }

    @Test
    fun reducerKeepsCommandPreviewWhenModernOutputDeltaArrives() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("call-1"),
                        "type" to JsonPrimitive("commandExecution"),
                        "status" to JsonPrimitive("inProgress"),
                        "command" to JsonPrimitive("/bin/zsh -lc \"echo one\""),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        val before = conversation.messagesFor("thread-1").first { it.itemId == "call-1" }.text

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/commandExecution/outputDelta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "itemId" to JsonPrimitive("call-1"),
                    "delta" to JsonPrimitive("ONE\n"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val after = conversation.messagesFor("thread-1").first { it.itemId == "call-1" }.text
        assertEquals(before, after)
        assertFalse(after.lowercase().contains("running command"))
    }

    @Test
    fun reducerCompletesLegacyCommandRowOnLegacyEndEvent() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/exec_command_begin",
                params = jsonObject(
                    "conversationId" to JsonPrimitive("thread-1"),
                    "id" to JsonPrimitive("turn-1"),
                    "msg" to jsonObject(
                        "type" to JsonPrimitive("exec_command_begin"),
                        "call_id" to JsonPrimitive("call-1"),
                        "turn_id" to JsonPrimitive("turn-1"),
                        "command" to jsonArray(JsonPrimitive("echo"), JsonPrimitive("ok")),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/exec_command_end",
                params = jsonObject(
                    "conversationId" to JsonPrimitive("thread-1"),
                    "id" to JsonPrimitive("turn-1"),
                    "msg" to jsonObject(
                        "type" to JsonPrimitive("exec_command_end"),
                        "call_id" to JsonPrimitive("call-1"),
                        "turn_id" to JsonPrimitive("turn-1"),
                        "status" to JsonPrimitive("completed"),
                        "command" to jsonArray(JsonPrimitive("echo"), JsonPrimitive("ok")),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val runRow = conversation.messagesFor("thread-1").first {
            it.kind == CodexMessageKind.CommandExecution
        }
        assertTrue(runRow.text.lowercase().startsWith("completed "))
        assertFalse(runRow.isStreaming)
    }

    @Test
    fun reducerHandlesLegacyCommandBeginOutputDeltaEndAsSingleCommandFlow() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/exec_command_begin",
                params = jsonObject(
                    "conversationId" to JsonPrimitive("thread-1"),
                    "id" to JsonPrimitive("turn-1"),
                    "msg" to jsonObject(
                        "type" to JsonPrimitive("exec_command_begin"),
                        "call_id" to JsonPrimitive("call-1"),
                        "turn_id" to JsonPrimitive("turn-1"),
                        "command" to jsonArray(JsonPrimitive("echo"), JsonPrimitive("ok")),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/exec_command_output_delta",
                params = jsonObject(
                    "conversationId" to JsonPrimitive("thread-1"),
                    "id" to JsonPrimitive("turn-1"),
                    "msg" to jsonObject(
                        "type" to JsonPrimitive("exec_command_output_delta"),
                        "call_id" to JsonPrimitive("call-1"),
                        "turn_id" to JsonPrimitive("turn-1"),
                        "delta" to JsonPrimitive("ok\n"),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/exec_command_end",
                params = jsonObject(
                    "conversationId" to JsonPrimitive("thread-1"),
                    "id" to JsonPrimitive("turn-1"),
                    "msg" to jsonObject(
                        "type" to JsonPrimitive("exec_command_end"),
                        "call_id" to JsonPrimitive("call-1"),
                        "turn_id" to JsonPrimitive("turn-1"),
                        "status" to JsonPrimitive("completed"),
                        "command" to jsonArray(JsonPrimitive("echo"), JsonPrimitive("ok")),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val runRows = conversation.messagesFor("thread-1").filter {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.CommandExecution
        }
        assertEquals(1, runRows.size)
        assertTrue(runRows.single().text.lowercase().startsWith("completed "))
        assertFalse(runRows.single().isStreaming)
    }

    @Test
    fun reducerUsesStructuredSummaryForSafeCommandExecutionAndAvoidsThinkingDuplicate() {
        val knownThreadIds = setOf("thread-1")
        val conversation = RemodexConversationReducer.reduce(
            conversation = RemodexConversationState(),
            message = RpcMessage.notification(
                method = "codex/event/exec_command_begin",
                params = jsonObject(
                    "conversationId" to JsonPrimitive("thread-1"),
                    "id" to JsonPrimitive("turn-1"),
                    "msg" to jsonObject(
                        "type" to JsonPrimitive("exec_command_begin"),
                        "call_id" to JsonPrimitive("call-1"),
                        "turn_id" to JsonPrimitive("turn-1"),
                        "command" to jsonArray(
                            JsonPrimitive("/bin/zsh"),
                            JsonPrimitive("-lc"),
                            JsonPrimitive("nl -ba AndroidClient/app/src/main/java/app/remodex/android/MainActivity.kt | sed -n '1,80p'"),
                        ),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val commandRows = conversation.messagesFor("thread-1").filter {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.CommandExecution
        }
        val thinkingRows = conversation.messagesFor("thread-1").filter {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.Thinking
        }
        assertEquals(1, commandRows.size)
        assertEquals("running Read MainActivity.kt", commandRows.single().text)
        assertEquals("Read MainActivity.kt", commandRows.single().commandExecutionDetails?.summary)
        assertEquals(CodexCommandExecutionPhase.Running, commandRows.single().commandExecutionDetails?.phase)
        assertTrue(
            commandRows.single().commandExecutionDetails?.rawCommand?.contains("nl -ba AndroidClient/app/src/main/java/app/remodex/android/MainActivity.kt") == true,
        )
        assertTrue(thinkingRows.isEmpty())
    }

    @Test
    fun reducerFallsBackToRunningPreviewForMixedIntentCommandExecution() {
        val knownThreadIds = setOf("thread-1")
        val conversation = RemodexConversationReducer.reduce(
            conversation = RemodexConversationState(),
            message = RpcMessage.notification(
                method = "item/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("call-1"),
                        "type" to JsonPrimitive("commandExecution"),
                        "status" to JsonPrimitive("inProgress"),
                        "command" to JsonPrimitive("mkdir tmp && touch tmp/smoke.txt"),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val commandRow = conversation.messagesFor("thread-1").first {
            it.kind == CodexMessageKind.CommandExecution
        }
        assertTrue(commandRow.text.startsWith("running "))
        assertTrue(commandRow.text.contains("mkdir tmp && touch"))
        assertEquals("mkdir tmp && touch tmp/smoke.txt", commandRow.commandExecutionDetails?.rawCommand)
        assertEquals(CodexCommandExecutionPhase.Running, commandRow.commandExecutionDetails?.phase)
        assertEquals(null, commandRow.commandExecutionDetails?.summary)
    }

    @Test
    fun reducerAddsToolCallActivityLinesIntoThinkingRow() {
        val conversation = RemodexConversationReducer.reduce(
            conversation = RemodexConversationState(),
            message = RpcMessage.notification(
                method = "item/toolCall/outputDelta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "delta" to JsonPrimitive("Read CodexProtocol.swift\nSearch extractSystemTitleAndBody\n{\"ignore\":\"json\"}"),
                ),
            ),
            knownThreadIds = setOf("thread-1"),
        )

        val thinkingRows = conversation.messagesFor("thread-1").filter {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.Thinking
        }
        assertEquals(1, thinkingRows.size)
        assertTrue(thinkingRows.single().text.contains("Read CodexProtocol.swift"))
        assertTrue(thinkingRows.single().text.contains("Search extractSystemTitleAndBody"))
        assertFalse(thinkingRows.single().text.contains("ignore"))
    }

    @Test
    fun reducerMergesEssentialActivityEventsIntoThinkingRow() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/background_event",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "message" to JsonPrimitive("Checking repo"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/read",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "path" to JsonPrimitive("Sources/App.swift"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/search",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "query" to JsonPrimitive("extractTitle"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/list_files",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "path" to JsonPrimitive("/tmp/project"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val thinkingRows = conversation.messagesFor("thread-1").filter {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.Thinking
        }
        assertEquals(1, thinkingRows.size)
        val body = thinkingRows.single().text
        assertTrue(body.contains("Checking repo"))
        assertTrue(body.contains("Read Sources/App.swift"))
        assertTrue(body.contains("Search extractTitle"))
        assertTrue(body.contains("List files /tmp/project"))
    }

    @Test
    fun reducerIgnoresLateActivityWithoutTurnIdAfterCompletion() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/background_event",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "message" to JsonPrimitive("Controllo subito il repository"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val thinkingRows = conversation.messagesFor("thread-1").filter {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.Thinking
        }
        assertTrue(thinkingRows.isEmpty())
    }

    @Test
    fun reducerUpdatesExistingThinkingRowWhenLateActivityArrivesWithTurnIdAfterCompletion() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/toolCall/outputDelta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "delta" to JsonPrimitive("Read file A.swift"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "codex/event/read",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "path" to JsonPrimitive("B.swift"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val thinkingRows = conversation.messagesFor("thread-1").filter {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.Thinking
        }
        assertEquals(1, thinkingRows.size)
        assertTrue(thinkingRows.single().text.contains("Read file A.swift"))
        assertTrue(thinkingRows.single().text.contains("Read B.swift"))
        assertFalse(thinkingRows.single().isStreaming)
    }

    @Test
    fun reducerIgnoresLateReasoningDeltaAfterCompletionWhenNoRowExists() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/reasoning/textDelta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "itemId" to JsonPrimitive("reasoning-1"),
                    "delta" to JsonPrimitive("Late reasoning chunk"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val thinkingRows = conversation.messagesFor("thread-1").filter {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.Thinking
        }
        assertTrue(thinkingRows.isEmpty())
    }

    @Test
    fun reducerMergesLateReasoningDeltaIntoExistingThinkingAfterCompletion() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/reasoning/textDelta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "itemId" to JsonPrimitive("reasoning-1"),
                    "delta" to JsonPrimitive("First"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/reasoning/textDelta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "itemId" to JsonPrimitive("reasoning-1"),
                    "delta" to JsonPrimitive(" second"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val thinkingRows = conversation.messagesFor("thread-1").filter {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.Thinking
        }
        assertEquals(1, thinkingRows.size)
        assertEquals("First second", thinkingRows.single().text)
        assertFalse(thinkingRows.single().isStreaming)
    }

    @Test
    fun reducerAcceptsTerminalInteractionWithoutNestedItem() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("call-1"),
                        "type" to JsonPrimitive("commandExecution"),
                        "status" to JsonPrimitive("inProgress"),
                        "command" to JsonPrimitive("/bin/zsh -lc \"echo one\""),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("call-1"),
                        "type" to JsonPrimitive("commandExecution"),
                        "status" to JsonPrimitive("completed"),
                        "command" to JsonPrimitive("/bin/zsh -lc \"echo one\""),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/commandExecution/terminalInteraction",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "itemId" to JsonPrimitive("call-1"),
                    "command" to JsonPrimitive("/bin/zsh -lc \"echo one\""),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val runRow = conversation.messagesFor("thread-1").first {
            it.role == CodexMessageRole.System &&
                it.kind == CodexMessageKind.CommandExecution &&
                it.itemId == "call-1"
        }
        assertTrue(runRow.text.lowercase().startsWith("completed "))
        assertFalse(runRow.isStreaming)
    }

    @Test
    fun reducerIgnoresLateCommandUpdateAfterTurnCompletionWhenRunRowIsAlreadyCompleted() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("call-1"),
                        "type" to JsonPrimitive("commandExecution"),
                        "status" to JsonPrimitive("inProgress"),
                        "command" to JsonPrimitive("/bin/zsh -lc \"echo one\""),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("call-1"),
                        "type" to JsonPrimitive("commandExecution"),
                        "status" to JsonPrimitive("completed"),
                        "command" to JsonPrimitive("/bin/zsh -lc \"echo one\""),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/completed",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/commandExecution/terminalInteraction",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "itemId" to JsonPrimitive("call-1"),
                    "command" to JsonPrimitive("/bin/zsh -lc \"echo one\""),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val runRows = conversation.messagesFor("thread-1").filter {
            it.role == CodexMessageRole.System &&
                it.kind == CodexMessageKind.CommandExecution &&
                it.itemId == "call-1"
        }
        assertEquals(1, runRows.size)
        assertTrue(runRows.single().text.lowercase().startsWith("completed "))
        assertFalse(runRows.single().isStreaming)
    }

    @Test
    fun reducerPreservesInterleavedThinkingWhenReasoningStartsAfterAssistantText() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "turn/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/reasoning/textDelta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "itemId" to JsonPrimitive("reasoning-1"),
                    "delta" to JsonPrimitive("Reasoning A"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/agentMessage/delta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("assistant-1"),
                        "type" to JsonPrimitive("agentMessage"),
                        "role" to JsonPrimitive("assistant"),
                    ),
                    "delta" to JsonPrimitive("Answer 1"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/reasoning/textDelta",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "itemId" to JsonPrimitive("reasoning-2"),
                    "delta" to JsonPrimitive("Reasoning B"),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val projected = RemodexTimelineProjector.project(conversation.messagesFor("thread-1")).messages
        assertEquals(
            listOf("Reasoning A", "Answer 1", "Reasoning B"),
            projected.map { it.text },
        )
    }

    @Test
    fun reducerCreatesStreamingRowsOnReasoningAndFileChangeItemStarted() {
        val knownThreadIds = setOf("thread-1")
        var conversation = RemodexConversationState()

        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("reasoning-1"),
                        "type" to JsonPrimitive("reasoning"),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )
        conversation = RemodexConversationReducer.reduce(
            conversation = conversation,
            message = RpcMessage.notification(
                method = "item/started",
                params = jsonObject(
                    "threadId" to JsonPrimitive("thread-1"),
                    "turnId" to JsonPrimitive("turn-1"),
                    "item" to jsonObject(
                        "id" to JsonPrimitive("file-1"),
                        "type" to JsonPrimitive("fileChange"),
                    ),
                ),
            ),
            knownThreadIds = knownThreadIds,
        )

        val messages = conversation.messagesFor("thread-1")
        val thinkingRow = messages.first {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.Thinking
        }
        val fileRow = messages.first {
            it.role == CodexMessageRole.System && it.kind == CodexMessageKind.FileChange
        }

        assertEquals("Thinking...", thinkingRow.text)
        assertEquals("reasoning-1", thinkingRow.itemId)
        assertTrue(thinkingRow.isStreaming)
        assertEquals("Applying file changes...", fileRow.text)
        assertEquals("file-1", fileRow.itemId)
        assertTrue(fileRow.isStreaming)
    }

    private fun jsonObject(vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject {
        return JsonObject(mapOf(*entries))
    }

    private fun jsonArray(vararg entries: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonArray {
        return kotlinx.serialization.json.JsonArray(entries.toList())
    }
}
