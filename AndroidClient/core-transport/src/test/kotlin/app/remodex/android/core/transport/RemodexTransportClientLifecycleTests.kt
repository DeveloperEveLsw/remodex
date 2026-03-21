package app.remodex.android.core.transport

import app.remodex.android.core.model.CodexImageAttachment
import app.remodex.android.core.model.CodexTurnSkillMention
import app.remodex.android.core.protocol.JsonValue
import app.remodex.android.core.protocol.RpcError
import app.remodex.android.core.protocol.RpcMessage
import java.net.SocketException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemodexTransportClientLifecycleTests {
    @Test
    fun permanentRelayCloseCodesMatchIosMessages() {
        val transport = RemodexTransportClient(appVersion = "test")
        val method = RemodexTransportClient::class.java.getDeclaredMethod(
            "permanentRelayDisconnectMessage",
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true

        assertEquals(
            "This relay pairing is no longer valid. Scan a new QR code to reconnect.",
            method.invoke(transport, 4000),
        )
        assertEquals(
            "This relay session was replaced by another host connection. Scan a new QR code to reconnect.",
            method.invoke(transport, 4001),
        )
        assertEquals(
            "The host session closed. Scan a new QR code to reconnect.",
            method.invoke(transport, 4002),
        )
        assertEquals(
            "This device was replaced by a newer connection. Scan a new QR code to reconnect.",
            method.invoke(transport, 4003),
        )
    }

    @Test
    fun benignSocketClosureClassifiesAsDisconnected() {
        val transport = RemodexTransportClient(appVersion = "test")
        val method = RemodexTransportClient::class.java.getDeclaredMethod(
            "classifyThrowable",
            Throwable::class.java,
        )
        method.isAccessible = true

        val classified = method.invoke(transport, SocketException("Socket closed")) as RemodexTransportException

        assertEquals(RemodexTransportFailureKind.Disconnected, classified.kind)
        assertEquals("Socket closed", classified.message)
    }

    @Test
    fun unknownSocketFailureStaysNetworkScoped() {
        val transport = RemodexTransportClient(appVersion = "test")
        val method = RemodexTransportClient::class.java.getDeclaredMethod(
            "classifyThrowable",
            Throwable::class.java,
        )
        method.isAccessible = true

        val classified = method.invoke(transport, SocketException("Network is unreachable")) as RemodexTransportException

        assertEquals(RemodexTransportFailureKind.Network, classified.kind)
        assertEquals("Network is unreachable", classified.message)
    }

    @Test
    fun startTurnResumesThreadBeforeSendingTurnStart() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("thread/resume") {
                    RpcMessage.success(
                        id = null,
                        result = threadEnvelope(threadId = "thread-live", cwd = "/tmp/project"),
                    )
                },
                ScriptedStep("turn/start") {
                    RpcMessage.success(
                        id = null,
                        result = JsonObject(mapOf("turnId" to JsonPrimitive("turn-1"))),
                    )
                },
            ),
        )

        val result = transport.startTurn(
            threadId = "thread-live",
            userInput = "hello world",
        )

        assertEquals(listOf("thread/resume", "turn/start"), transport.recordedMethods)
        assertEquals("thread-live", result.requestedThreadId)
        assertEquals("thread-live", result.threadId)
        assertEquals("turn-1", result.turnId)
        assertEquals("thread-live", result.activeThread?.id)
        assertNull(result.archivedThreadId)
        assertNull(result.continuationSummary)
    }

    @Test
    fun startTurnCreatesNewThreadWhenNoThreadIdIsSelected() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("thread/start") {
                    RpcMessage.success(
                        id = null,
                        result = threadEnvelope(threadId = "thread-new", cwd = "/tmp/project"),
                    )
                },
                ScriptedStep("turn/start") {
                    RpcMessage.success(
                        id = null,
                        result = JsonObject(mapOf("turnId" to JsonPrimitive("turn-1"))),
                    )
                },
            ),
        )

        val result = transport.startTurn(
            threadId = null,
            userInput = "hello world",
            preferredProjectPath = "/tmp/project/",
            modelIdentifier = "gpt-5.4",
        )

        assertEquals(
            listOf("thread/start", "turn/start"),
            transport.recordedMethods,
        )
        assertEquals("thread-new", result.requestedThreadId)
        assertEquals("thread-new", result.threadId)
        assertEquals("thread-new", result.activeThread?.id)

        val threadStartParams = transport.recordedParams.first().second as JsonObject
        assertEquals("/tmp/project", (threadStartParams["cwd"] as JsonPrimitive).content)
        assertEquals("gpt-5.4", (threadStartParams["model"] as JsonPrimitive).content)
    }

    @Test
    fun startTurnCreatesContinuationWhenTurnStartReportsMissingThread() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("thread/resume") {
                    RpcMessage.success(
                        id = null,
                        result = threadEnvelope(threadId = "thread-stale", cwd = "/tmp/project"),
                    )
                },
                ScriptedStep("turn/start") {
                    throw RemodexTransportException(
                        kind = RemodexTransportFailureKind.Rpc,
                        message = "RPC error -32000: thread not found",
                        rpcError = RpcError(code = -32000, message = "thread not found"),
                    )
                },
                ScriptedStep("thread/start") {
                    RpcMessage.success(
                        id = null,
                        result = threadEnvelope(threadId = "thread-new"),
                    )
                },
                ScriptedStep("turn/start") {
                    RpcMessage.success(
                        id = null,
                        result = JsonObject(mapOf("turnId" to JsonPrimitive("turn-2"))),
                    )
                },
            ),
        )

        val result = transport.startTurn(
            threadId = "thread-stale",
            userInput = "continue this",
            preferredProjectPath = "/tmp/project/",
        )

        assertEquals(
            listOf("thread/resume", "turn/start", "thread/start", "turn/start"),
            transport.recordedMethods,
        )
        assertEquals("thread-stale", result.requestedThreadId)
        assertEquals("thread-new", result.threadId)
        assertEquals("thread-stale", result.archivedThreadId)
        assertEquals("turn-2", result.turnId)
        assertEquals("/tmp/project", result.activeThread?.cwd)
        assertNotNull(result.continuationSummary)

        val threadStartParams = transport.recordedParams.first { it.first == "thread/start" }.second as JsonObject
        assertEquals("/tmp/project", (threadStartParams["cwd"] as JsonPrimitive).content)
    }

    @Test
    fun startTurnResumesContinuationWhenOriginalResumeReportsMissingThread() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("thread/resume") {
                    throw RemodexTransportException(
                        kind = RemodexTransportFailureKind.Rpc,
                        message = "RPC error -32000: thread not found",
                        rpcError = RpcError(code = -32000, message = "thread not found"),
                    )
                },
                ScriptedStep("thread/start") {
                    RpcMessage.success(
                        id = null,
                        result = threadEnvelope(threadId = "thread-new", cwd = "/tmp/project"),
                    )
                },
                ScriptedStep("turn/start") {
                    RpcMessage.success(
                        id = null,
                        result = JsonObject(mapOf("turnId" to JsonPrimitive("turn-3"))),
                    )
                },
            ),
        )

        val result = transport.startTurn(
            threadId = "thread-missing",
            userInput = "resume me",
            preferredProjectPath = "/tmp/project",
        )

        assertEquals(
            listOf("thread/resume", "thread/start", "turn/start"),
            transport.recordedMethods,
        )
        assertEquals("thread-missing", result.archivedThreadId)
        assertEquals("thread-new", result.threadId)
        assertEquals("turn-3", result.turnId)
        assertEquals("thread-new", result.activeThread?.id)
    }

    @Test
    fun startTurnTreatsMissingRolloutAsMissingThread() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("thread/resume") {
                    throw RemodexTransportException(
                        kind = RemodexTransportFailureKind.Rpc,
                        message = "RPC error -32600: NoRolloutFoundForThreadId 019ceee2-b668-7393-baa2-af0904e8949d",
                        rpcError = RpcError(
                            code = -32600,
                            message = "NoRolloutFoundForThreadId 019ceee2-b668-7393-baa2-af0904e8949d",
                        ),
                    )
                },
                ScriptedStep("thread/start") {
                    RpcMessage.success(
                        id = null,
                        result = threadEnvelope(threadId = "thread-new", cwd = "/tmp/project"),
                    )
                },
                ScriptedStep("turn/start") {
                    RpcMessage.success(
                        id = null,
                        result = JsonObject(mapOf("turnId" to JsonPrimitive("turn-3"))),
                    )
                },
            ),
        )

        val result = transport.startTurn(
            threadId = "thread-stale",
            userInput = "resume me",
            preferredProjectPath = "/tmp/project",
        )

        assertEquals(
            listOf("thread/resume", "thread/start", "turn/start"),
            transport.recordedMethods,
        )
        assertEquals("thread-stale", result.archivedThreadId)
        assertEquals("thread-new", result.threadId)
        assertEquals("thread-new", result.activeThread?.id)
    }

    @Test
    fun startTurnSkipsResumeForFreshlyStartedThreadJustLikeIos() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("thread/start") {
                    RpcMessage.success(
                        id = null,
                        result = threadEnvelope(threadId = "thread-new", cwd = "/tmp/project"),
                    )
                },
                ScriptedStep("turn/start") {
                    RpcMessage.success(
                        id = null,
                        result = JsonObject(mapOf("turnId" to JsonPrimitive("turn-4"))),
                    )
                },
            ),
        )

        transport.startThread(preferredProjectPath = "/tmp/project")
        val result = transport.startTurn(
            threadId = "thread-new",
            userInput = "hello again",
        )

        assertEquals(listOf("thread/start", "turn/start"), transport.recordedMethods)
        assertEquals("thread-new", result.threadId)
        assertEquals("thread-new", result.activeThread?.id)
    }

    @Test
    fun startThreadPreservesPreferredProjectPathWhenServerOmitsCwd() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("thread/start") {
                    RpcMessage.success(
                        id = null,
                        result = threadEnvelope(threadId = "thread-new"),
                    )
                },
            ),
        )

        val result = transport.startThread(preferredProjectPath = "/tmp/project/")

        assertEquals("/tmp/project", result.thread.cwd)
        val threadStartParams = transport.recordedParams.single().second as JsonObject
        assertEquals("/tmp/project", (threadStartParams["cwd"] as JsonPrimitive).content)
    }

    @Test
    fun startThreadIncludesRuntimeModelInRequest() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("thread/start") {
                    RpcMessage.success(
                        id = null,
                        result = threadEnvelope(threadId = "thread-new"),
                    )
                },
            ),
        )

        transport.startThread(
            preferredProjectPath = "/tmp/project",
            modelIdentifier = "gpt-5.4",
        )

        val threadStartParams = transport.recordedParams.single().second as JsonObject
        assertEquals("gpt-5.4", (threadStartParams["model"] as JsonPrimitive).content)
    }

    @Test
    fun interruptTurnRetriesWithSnakeCaseParamsWhenServerRejectsCamelCase() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("turn/interrupt") {
                    throw RemodexTransportException(
                        kind = RemodexTransportFailureKind.Rpc,
                        message = "RPC error -32602: unknown field turnId",
                        rpcError = RpcError(code = -32602, message = "unknown field turnId"),
                    )
                },
                ScriptedStep("turn/interrupt") {
                    RpcMessage.success(
                        id = null,
                        result = JsonObject(emptyMap()),
                    )
                },
            ),
        )

        transport.interruptTurn(
            turnId = "turn-1",
            threadId = "thread-1",
        )

        assertEquals(listOf("turn/interrupt", "turn/interrupt"), transport.recordedMethods)

        val firstParams = transport.recordedParams[0].second as JsonObject
        val secondParams = transport.recordedParams[1].second as JsonObject
        assertEquals("turn-1", (firstParams["turnId"] as JsonPrimitive).content)
        assertEquals("thread-1", (firstParams["threadId"] as JsonPrimitive).content)
        assertEquals("turn-1", (secondParams["turn_id"] as JsonPrimitive).content)
        assertEquals("thread-1", (secondParams["thread_id"] as JsonPrimitive).content)
    }

    @Test
    fun startTurnIncludesRuntimeModelAndReasoningPayload() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("thread/resume") {
                    RpcMessage.success(
                        id = null,
                        result = threadEnvelope(threadId = "thread-live", cwd = "/tmp/project"),
                    )
                },
                ScriptedStep("turn/start") {
                    RpcMessage.success(
                        id = null,
                        result = JsonObject(mapOf("turnId" to JsonPrimitive("turn-1"))),
                    )
                },
            ),
        )
        transport.emitState(
            RemodexTransportState.Connected(
                sessionUrl = "ws://test",
                isInitialized = true,
                supportsPlanCollaborationMode = true,
            ),
        )

        transport.startTurn(
            threadId = "thread-live",
            userInput = "hello world",
            collaborationMode = app.remodex.android.core.model.CodexCollaborationModeKind.Plan,
            modelIdentifier = "gpt-5.4",
            reasoningEffort = "high",
        )

        val resumeParams = transport.recordedParams[0].second as JsonObject
        val turnStartParams = transport.recordedParams[1].second as JsonObject

        assertEquals("gpt-5.4", (resumeParams["model"] as JsonPrimitive).content)
        assertEquals("gpt-5.4", (turnStartParams["model"] as JsonPrimitive).content)
        assertEquals("high", (turnStartParams["effort"] as JsonPrimitive).content)

        val collaboration = turnStartParams["collaborationMode"] as JsonObject
        assertEquals("plan", (collaboration["mode"] as JsonPrimitive).content)
        val settings = collaboration["settings"] as JsonObject
        assertEquals("gpt-5.4", (settings["model"] as JsonPrimitive).content)
        assertEquals("high", (settings["reasoning_effort"] as JsonPrimitive).content)
    }

    @Test
    fun startTurnBuildsIosOrderedMixedInputPayload() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("thread/resume") {
                    RpcMessage.success(
                        id = null,
                        result = threadEnvelope(threadId = "thread-live", cwd = "/tmp/project"),
                    )
                },
                ScriptedStep("turn/start") {
                    RpcMessage.success(
                        id = null,
                        result = JsonObject(mapOf("turnId" to JsonPrimitive("turn-1"))),
                    )
                },
            ),
        )

        transport.startTurn(
            threadId = "thread-live",
            userInput = "inspect this",
            attachments = listOf(
                CodexImageAttachment(
                    id = "image-1",
                    thumbnailBase64JPEG = "thumb",
                    payloadDataURL = "data:image/jpeg;base64,abc123",
                ),
            ),
            skillMentions = listOf(
                CodexTurnSkillMention(
                    id = "planner",
                    name = "planner",
                    path = "/skills/planner",
                ),
            ),
        )

        val turnStartParams = transport.recordedParams[1].second as JsonObject
        val input = turnStartParams["input"] as JsonArray

        val imageItem = input[0] as JsonObject
        val textItem = input[1] as JsonObject
        val skillItem = input[2] as JsonObject

        assertEquals("image", (imageItem["type"] as JsonPrimitive).content)
        assertEquals("data:image/jpeg;base64,abc123", (imageItem["url"] as JsonPrimitive).content)
        assertEquals("text", (textItem["type"] as JsonPrimitive).content)
        assertEquals("inspect this", (textItem["text"] as JsonPrimitive).content)
        assertEquals("skill", (skillItem["type"] as JsonPrimitive).content)
        assertEquals("planner", (skillItem["id"] as JsonPrimitive).content)
        assertEquals("planner", (skillItem["name"] as JsonPrimitive).content)
        assertEquals("/skills/planner", (skillItem["path"] as JsonPrimitive).content)
    }

    @Test
    fun listSkillsFallsBackToLegacyCwdParameter() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("skills/list") {
                    throw RemodexTransportException(
                        kind = RemodexTransportFailureKind.Rpc,
                        message = "RPC error -32602: unknown field cwds",
                        rpcError = RpcError(code = -32602, message = "unknown field cwds"),
                    )
                },
                ScriptedStep("skills/list") {
                    RpcMessage.success(
                        id = null,
                        result = JsonObject(
                            mapOf(
                                "data" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "skills" to JsonArray(
                                                    listOf(
                                                        JsonObject(
                                                            mapOf(
                                                                "name" to JsonPrimitive("planner"),
                                                                "description" to JsonPrimitive("Planning workflow"),
                                                                "path" to JsonPrimitive("/skills/planner"),
                                                                "enabled" to JsonPrimitive(true),
                                                            ),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )
                },
            ),
        )

        val skills = transport.listSkills(cwds = listOf("/tmp/project"))

        assertEquals(listOf("skills/list", "skills/list"), transport.recordedMethods)
        val firstParams = transport.recordedParams[0].second as JsonObject
        val secondParams = transport.recordedParams[1].second as JsonObject
        assertEquals("/tmp/project", ((firstParams["cwds"] as JsonArray)[0] as JsonPrimitive).content)
        assertEquals("/tmp/project", (secondParams["cwd"] as JsonPrimitive).content)
        assertEquals(listOf("planner"), skills.map { it.name })
    }

    @Test
    fun readThreadDecodesHistoryMessagesWithRealAndSyntheticTimestamps() = runTest {
        val transport = ScriptedTransportClient(
            steps = listOf(
                ScriptedStep("thread/read") {
                    RpcMessage.success(
                        id = null,
                        result = JsonObject(
                            mapOf(
                                "thread" to JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive("thread-live"),
                                        "title" to JsonPrimitive("Conversation"),
                                        "createdAt" to JsonPrimitive("2026-03-22T00:00:00Z"),
                                        "turns" to JsonArray(
                                            listOf(
                                                JsonObject(
                                                    mapOf(
                                                        "id" to JsonPrimitive("turn-1"),
                                                        "createdAt" to JsonPrimitive("2026-03-22T00:00:10Z"),
                                                        "items" to JsonArray(
                                                            listOf(
                                                                JsonObject(
                                                                    mapOf(
                                                                        "id" to JsonPrimitive("assistant-1"),
                                                                        "type" to JsonPrimitive("agentMessage"),
                                                                        "text" to JsonPrimitive("First reply"),
                                                                        "createdAt" to JsonPrimitive("2026-03-22T00:00:11Z"),
                                                                    ),
                                                                ),
                                                                JsonObject(
                                                                    mapOf(
                                                                        "id" to JsonPrimitive("assistant-2"),
                                                                        "type" to JsonPrimitive("agentMessage"),
                                                                        "text" to JsonPrimitive("Second reply"),
                                                                    ),
                                                                ),
                                                            ),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )
                },
            ),
        )

        val result = transport.readThread(threadId = "thread-live")

        assertEquals(2, result.messages.size)
        assertEquals("2026-03-22T00:00:11Z", result.messages[0].createdAt?.toString())
        assertEquals("2026-03-22T00:00:10.001Z", result.messages[1].createdAt?.toString())
    }

    private data class ScriptedStep(
        val expectedMethod: String,
        val action: (JsonValue?) -> RpcMessage,
    )

    private class ScriptedTransportClient(
        private val steps: List<ScriptedStep>,
    ) : RemodexTransportClient(appVersion = "test") {
        val recordedMethods = mutableListOf<String>()
        val recordedParams = mutableListOf<Pair<String, JsonValue?>>()
        private var cursor = 0

        override suspend fun sendRequest(
            method: String,
            params: JsonValue?,
            timeoutMillis: Long,
        ): RpcMessage {
            recordedMethods += method
            recordedParams += method to params

            val step = steps.getOrNull(cursor)
                ?: error("Unexpected request $method at index $cursor")
            cursor += 1
            assertEquals(step.expectedMethod, method)
            return step.action(params)
        }
    }

    private fun ScriptedTransportClient.emitState(state: RemodexTransportState) {
        val field = RemodexTransportClient::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(this) as kotlinx.coroutines.flow.MutableStateFlow<RemodexTransportState>
        stateFlow.value = state
    }

    private fun threadEnvelope(
        threadId: String,
        cwd: String? = null,
    ): JsonObject {
        return JsonObject(
            mapOf(
                "thread" to threadJson(threadId = threadId, cwd = cwd),
            ),
        )
    }

    private fun threadJson(
        threadId: String,
        cwd: String? = null,
    ): JsonObject {
        val values = linkedMapOf<String, JsonValue>(
            "id" to JsonPrimitive(threadId),
            "title" to JsonPrimitive("Conversation"),
        )
        if (cwd != null) {
            values["cwd"] = JsonPrimitive(cwd)
        }
        return JsonObject(values)
    }
}
