package app.remodex.android

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

    private fun jsonObject(vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject {
        return JsonObject(mapOf(*entries))
    }
}
