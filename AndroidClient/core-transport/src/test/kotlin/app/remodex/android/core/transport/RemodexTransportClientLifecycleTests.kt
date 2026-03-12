package app.remodex.android.core.transport

import app.remodex.android.core.protocol.JsonValue
import app.remodex.android.core.protocol.RpcError
import app.remodex.android.core.protocol.RpcMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemodexTransportClientLifecycleTests {
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
                ScriptedStep("thread/resume") {
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
            listOf("thread/resume", "thread/start", "thread/resume", "turn/start"),
            transport.recordedMethods,
        )
        assertEquals("thread-missing", result.archivedThreadId)
        assertEquals("thread-new", result.threadId)
        assertEquals("turn-3", result.turnId)
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
