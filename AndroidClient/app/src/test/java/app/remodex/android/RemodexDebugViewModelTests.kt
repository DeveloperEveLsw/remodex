package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadSyncState
import app.remodex.android.core.transport.RemodexThreadReadResult
import app.remodex.android.core.transport.RemodexThreadStartResult
import app.remodex.android.core.transport.RemodexTransportClient
import app.remodex.android.core.transport.RemodexTurnStartResult
import app.remodex.android.core.protocol.RpcMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemodexDebugViewModelTests {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startThreadSelectsTheCreatedLiveThread() = runTest {
        val createdThread = CodexThread(
            id = "thread-new",
            title = "New Chat",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            startThreadResult = RemodexThreadStartResult(
                thread = createdThread,
                response = RpcMessage.success(null, JsonObject(emptyMap())),
            ),
        )
        val viewModel = RemodexDebugViewModel(transport = transport)

        viewModel.startThread()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("thread-new", state.activeThreadId)
        assertEquals("thread-new", state.threads.first().id)
        assertEquals("New live thread ready: thread-n", state.lastTurnStartSummary)
        assertTrue(state.conversation.messagesFor(state.activeThreadId).isEmpty())
    }

    @Test
    fun startTurnArchivesStaleThreadAndSelectsContinuationThread() = runTest {
        val oldThread = CodexThread(
            id = "thread-old",
            title = "Old Chat",
            cwd = "/tmp/project",
        )
        val oldMessages = listOf(
            CodexMessage(
                id = "msg-1",
                threadId = oldThread.id,
                role = CodexMessageRole.User,
                kind = CodexMessageKind.Chat,
                text = "Old prompt",
            ),
        )
        val newThread = CodexThread(
            id = "thread-new",
            title = "Continuation",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                oldThread.id to RemodexThreadReadResult(
                    thread = oldThread,
                    messages = oldMessages,
                ),
            ),
            startTurnResult = RemodexTurnStartResult(
                requestedThreadId = oldThread.id,
                threadId = newThread.id,
                turnId = "turn-1",
                activeThread = newThread,
                archivedThreadId = oldThread.id,
                continuationSummary = "Continued on a new live thread after `thread-old` became stale.",
                response = RpcMessage.success(null, JsonObject(emptyMap())),
            ),
        )
        val viewModel = RemodexDebugViewModel(transport = transport)

        viewModel.selectThread(oldThread.id)
        advanceUntilIdle()
        viewModel.updateDraftTurnInput("Continue from here")
        viewModel.startTurn()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("thread-new", state.activeThreadId)
        assertEquals("", state.draftTurnInput)
        assertEquals(
            "Continued on a new live thread after `thread-old` became stale.",
            state.lastTurnStartSummary,
        )
        assertTrue(state.conversation.messagesFor(state.activeThreadId).isEmpty())

        val archivedThread = state.threads.first { it.id == "thread-old" }
        assertEquals(CodexThreadSyncState.ArchivedLocal, archivedThread.syncState)
        assertEquals("/tmp/project", transport.lastPreferredProjectPath)
    }

    @Test
    fun selectThreadStoresHistoryInPerThreadConversationState() = runTest {
        val thread = CodexThread(
            id = "thread-history",
            title = "History",
            cwd = "/tmp/project",
        )
        val historyMessages = listOf(
            CodexMessage(
                id = "msg-1",
                threadId = thread.id,
                role = CodexMessageRole.User,
                kind = CodexMessageKind.Chat,
                text = "Prompt",
            ),
            CodexMessage(
                id = "msg-2",
                threadId = thread.id,
                role = CodexMessageRole.Assistant,
                kind = CodexMessageKind.Chat,
                text = "Reply",
            ),
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(
                    thread = thread,
                    messages = historyMessages,
                ),
            ),
        )
        val viewModel = RemodexDebugViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(thread.id, state.activeThreadId)
        assertEquals(historyMessages, state.conversation.messagesFor(thread.id))
        assertTrue(!state.conversation.isLoadingThread(thread.id))
        assertTrue(state.conversation.messageRevisionFor(thread.id) > 0)
    }

    private class FakeTransportClient(
        private val readThreadResults: Map<String, RemodexThreadReadResult> = emptyMap(),
        private val startThreadResult: RemodexThreadStartResult? = null,
        private val startTurnResult: RemodexTurnStartResult? = null,
    ) : RemodexTransportClient(appVersion = "test") {
        var lastPreferredProjectPath: String? = null

        override suspend fun startThread(
            preferredProjectPath: String?,
            accessMode: app.remodex.android.core.model.CodexAccessMode,
        ): RemodexThreadStartResult {
            lastPreferredProjectPath = preferredProjectPath
            return startThreadResult ?: error("startThread was not stubbed")
        }

        override suspend fun readThread(
            threadId: String,
            includeTurns: Boolean,
        ): RemodexThreadReadResult {
            return readThreadResults[threadId] ?: error("No thread/read stub for $threadId")
        }

        override suspend fun startTurn(
            threadId: String,
            userInput: String,
            accessMode: app.remodex.android.core.model.CodexAccessMode,
            collaborationMode: app.remodex.android.core.model.CodexCollaborationModeKind?,
            preferredProjectPath: String?,
        ): RemodexTurnStartResult {
            lastPreferredProjectPath = preferredProjectPath
            return startTurnResult ?: error("startTurn was not stubbed")
        }
    }
}
