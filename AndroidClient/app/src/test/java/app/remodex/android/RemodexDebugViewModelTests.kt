package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageDeliveryState
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexModelOption
import app.remodex.android.core.model.CodexReasoningEffortOption
import app.remodex.android.core.model.CodexFuzzyFileMatch
import app.remodex.android.core.model.CodexSkillMetadata
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadRunBadgeState
import app.remodex.android.core.model.CodexThreadSyncState
import app.remodex.android.core.model.CodexTurnSkillMention
import app.remodex.android.core.model.GitBranchesWithStatusResult
import app.remodex.android.core.model.GitCheckoutResult
import app.remodex.android.core.model.GitDiffTotals
import app.remodex.android.core.model.GitRepoSyncResult
import app.remodex.android.core.pairing.RemodexPairingPayload
import app.remodex.android.core.transport.RemodexHandshakeResult
import app.remodex.android.core.transport.RemodexThreadResumeResult
import app.remodex.android.core.transport.RemodexThreadTurnStateSnapshot
import app.remodex.android.core.transport.RemodexThreadReadResult
import app.remodex.android.core.transport.RemodexThreadStartResult
import app.remodex.android.core.transport.RemodexTransportClient
import app.remodex.android.core.transport.RemodexTransportException
import app.remodex.android.core.transport.RemodexTransportFailureKind
import app.remodex.android.core.transport.RemodexTransportState
import app.remodex.android.core.transport.RemodexTurnStartResult
import app.remodex.android.core.protocol.RpcMessage
import app.remodex.android.core.protocol.RpcError
import app.remodex.android.core.protocol.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemodexDebugViewModelTests {
    private val dispatcher = UnconfinedTestDispatcher()
    private val disabledRealtimeSyncPolicy = RemodexRealtimeSyncPolicy(enabled = false)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private data class SentRpcResponse(
        val id: JsonValue,
        val result: JsonValue,
    ) {
        fun idAsString(): String {
            return if (id is JsonPrimitive) {
                id.content
            } else {
                id.toString()
            }
        }
    }

    private data class SentRpcErrorResponse(
        val id: JsonValue?,
        val code: Int,
        val message: String,
        val data: JsonValue?,
    )

    private fun createViewModel(
        transport: RemodexTransportClient = FakeTransportClient(),
        relaySessionStore: RemodexRelaySessionStore = InMemoryRemodexRelaySessionStore(),
        realtimeSyncPolicy: RemodexRealtimeSyncPolicy = disabledRealtimeSyncPolicy,
    ): RemodexDebugViewModel {
        return RemodexDebugViewModel(
            transport = transport,
            relaySessionStore = relaySessionStore,
            realtimeSyncPolicy = realtimeSyncPolicy,
        )
    }

    @Test
    fun attemptAutoConnectOnLaunchUsesSavedRelayPairing() = runTest {
        val pairing = RemodexPairingPayload(
            relayUrl = "ws://localhost:9000/relay",
            sessionId = "session-1",
        )
        val store = InMemoryRemodexRelaySessionStore(pairing)
        val transport = FakeTransportClient(initialConnectionState = RemodexTransportState.Disconnected)
        val viewModel = createViewModel(
            transport = transport,
            relaySessionStore = store,
        )

        viewModel.attemptAutoConnectOnLaunchIfNeeded()
        advanceUntilIdle()

        assertEquals(listOf(pairing.relaySessionUrl()), transport.connectedSessionUrls)
        assertTrue(viewModel.uiState.value.hasSavedRelaySession)
        assertEquals(pairing.relaySessionUrl(), viewModel.uiState.value.sessionUrl)
    }

    @Test
    fun attemptAutoConnectOnLaunchRecoversFirstLiveThreadWhenNoSelectionExists() = runTest {
        val pairing = RemodexPairingPayload(
            relayUrl = "ws://localhost:9000/relay",
            sessionId = "session-recover",
        )
        val thread = CodexThread(
            id = "thread-recover",
            title = "Recovered",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(
                    thread = thread,
                    turnStateSnapshot = RemodexThreadTurnStateSnapshot(
                        interruptibleTurnId = "turn-live",
                        latestTurnId = "turn-live",
                    ),
                ),
            ),
            threadListResult = listOf(thread),
            initialConnectionState = RemodexTransportState.Disconnected,
        )
        val store = InMemoryRemodexRelaySessionStore(pairing)
        val viewModel = createViewModel(
            transport = transport,
            relaySessionStore = store,
        )

        viewModel.attemptAutoConnectOnLaunchIfNeeded()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(thread.id, state.activeThreadId)
        assertEquals("turn-live", state.conversation.activeTurnIdByThread[thread.id])
        assertTrue(transport.recordedMethods.contains("thread/list"))
        assertTrue(transport.recordedMethods.contains("thread/resume"))
        assertTrue(transport.recordedMethods.contains("thread/read"))
    }

    @Test
    fun transientFailureKeepsSavedPairingAndReconnectsOnForeground() = runTest {
        val pairing = RemodexPairingPayload(
            relayUrl = "ws://localhost:9000/relay",
            sessionId = "session-2",
        )
        val store = InMemoryRemodexRelaySessionStore(pairing)
        val transport = FakeTransportClient(initialConnectionState = RemodexTransportState.Disconnected)
        val viewModel = createViewModel(
            transport = transport,
            relaySessionStore = store,
        )

        viewModel.setForegroundState(false)
        transport.emitState(
            RemodexTransportState.Failed(
                sessionUrl = pairing.relaySessionUrl(),
                message = "Connection dropped.",
                isPermanent = false,
                failureKind = RemodexTransportFailureKind.Disconnected,
            ),
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.shouldAutoReconnectOnForeground)
        assertTrue(viewModel.uiState.value.hasSavedRelaySession)
        assertNull(viewModel.uiState.value.errorMessage)

        viewModel.setForegroundState(true)
        advanceUntilIdle()

        assertEquals(listOf(pairing.relaySessionUrl()), transport.connectedSessionUrls)
        assertFalse(viewModel.uiState.value.shouldAutoReconnectOnForeground)
        assertFalse(viewModel.uiState.value.isAttemptingAutoReconnect)
    }

    @Test
    fun foregroundTransientDisconnectAttemptsImmediateReconnect() = runTest {
        val pairing = RemodexPairingPayload(
            relayUrl = "ws://localhost:9000/relay",
            sessionId = "session-foreground",
        )
        val store = InMemoryRemodexRelaySessionStore(pairing)
        val transport = FakeTransportClient(initialConnectionState = RemodexTransportState.Connected(
            sessionUrl = pairing.relaySessionUrl(),
            isInitialized = true,
        ))
        val viewModel = createViewModel(
            transport = transport,
            relaySessionStore = store,
        )
        advanceUntilIdle()

        transport.emitState(
            RemodexTransportState.Failed(
                sessionUrl = pairing.relaySessionUrl(),
                message = "Connection dropped.",
                isPermanent = false,
                failureKind = RemodexTransportFailureKind.Disconnected,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(pairing.relaySessionUrl()), transport.connectedSessionUrls)
        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.shouldAutoReconnectOnForeground)
    }

    @Test
    fun unknownFailureWithSavedPairingSurfacesErrorAndDoesNotQueueReconnect() = runTest {
        val pairing = RemodexPairingPayload(
            relayUrl = "ws://localhost:9000/relay",
            sessionId = "session-unknown",
        )
        val store = InMemoryRemodexRelaySessionStore(pairing)
        val transport = FakeTransportClient(initialConnectionState = RemodexTransportState.Disconnected)
        val viewModel = createViewModel(
            transport = transport,
            relaySessionStore = store,
        )

        transport.emitState(
            RemodexTransportState.Failed(
                sessionUrl = pairing.relaySessionUrl(),
                message = "Unexpected relay failure.",
                isPermanent = false,
                failureKind = RemodexTransportFailureKind.Unknown,
            ),
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.shouldAutoReconnectOnForeground)
        assertEquals("Unexpected relay failure.", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.hasSavedRelaySession)
    }

    @Test
    fun permanentFailureClearsSavedPairing() = runTest {
        val pairing = RemodexPairingPayload(
            relayUrl = "ws://localhost:9000/relay",
            sessionId = "session-3",
        )
        val store = InMemoryRemodexRelaySessionStore(pairing)
        val transport = FakeTransportClient(initialConnectionState = RemodexTransportState.Disconnected)
        val viewModel = createViewModel(
            transport = transport,
            relaySessionStore = store,
        )

        transport.emitState(
            RemodexTransportState.Failed(
                sessionUrl = pairing.relaySessionUrl(),
                message = "The host session closed. Scan a new QR code to reconnect.",
                isPermanent = true,
            ),
        )
        advanceUntilIdle()

        assertNull(store.read())
        assertFalse(viewModel.uiState.value.hasSavedRelaySession)
        assertEquals(
            "The host session closed. Scan a new QR code to reconnect.",
            viewModel.uiState.value.errorMessage,
        )
    }

    @Test
    fun connectWithQrPayloadUsesExistingConnectPipeline() = runTest {
        val transport = FakeTransportClient(initialConnectionState = RemodexTransportState.Disconnected)
        val store = InMemoryRemodexRelaySessionStore()
        val viewModel = createViewModel(
            transport = transport,
            relaySessionStore = store,
        )
        val rawPayload = """{"relay":"http://localhost:9000/relay","sessionId":"session-qr"}"""

        viewModel.connectWithQrPayload(rawPayload)
        advanceUntilIdle()

        assertEquals(listOf("ws://localhost:9000/relay/session-qr"), transport.connectedSessionUrls)
        assertEquals(rawPayload, viewModel.uiState.value.qrPayload)
        assertEquals("ws://localhost:9000/relay/session-qr", store.read()?.relaySessionUrl())
        assertEquals("ws://localhost:9000/relay/session-qr", viewModel.uiState.value.sessionUrl)
    }

    @Test
    fun connectWithQrPayloadSurfacesParseErrorsWithoutPersistingPairing() = runTest {
        val transport = FakeTransportClient(initialConnectionState = RemodexTransportState.Disconnected)
        val store = InMemoryRemodexRelaySessionStore()
        val viewModel = createViewModel(
            transport = transport,
            relaySessionStore = store,
        )

        viewModel.connectWithQrPayload("""{"relay":"ws://localhost:9000/relay"}""")
        advanceUntilIdle()

        assertTrue(transport.connectedSessionUrls.isEmpty())
        assertNull(store.read())
        assertEquals("QR payload is missing the session ID.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun onRequestApprovalServerRequestPopulatesPendingApproval() = runTest {
        val transport = FakeTransportClient()
        val viewModel = createViewModel(transport = transport)

        transport.emitServerRequest(
            RpcMessage.request(
                id = JsonPrimitive("approval-1"),
                method = "item/commandExecution/requestApproval",
                params = JsonObject(
                    mapOf(
                        "command" to JsonPrimitive("npm test"),
                        "reason" to JsonPrimitive("Needs permission"),
                        "threadId" to JsonPrimitive("thread-1"),
                        "turnId" to JsonPrimitive("turn-1"),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        val pendingApproval = viewModel.uiState.value.pendingApproval
        assertEquals("approval-1", pendingApproval?.id)
        assertEquals("npm test", pendingApproval?.command)
        assertEquals("Needs permission", pendingApproval?.reason)
        assertTrue(transport.sentResponses.isEmpty())
    }

    @Test
    fun fullAccessApprovalServerRequestAutoApprovesWithoutDialog() = runTest {
        val transport = FakeTransportClient()
        val viewModel = createViewModel(transport = transport)
        viewModel.selectAccessMode(app.remodex.android.core.model.CodexAccessMode.FullAccess)
        advanceUntilIdle()

        transport.emitServerRequest(
            RpcMessage.request(
                id = JsonPrimitive("approval-1"),
                method = "item/commandExecution/requestApproval",
                params = JsonObject(
                    mapOf(
                        "command" to JsonPrimitive("npm test"),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingApproval)
        assertEquals(1, transport.sentResponses.size)
        assertEquals("approval-1", transport.sentResponses.single().idAsString())
        assertEquals("accept", (transport.sentResponses.single().result as JsonPrimitive).content)
    }

    @Test
    fun structuredUserInputServerRequestAddsInlinePrompt() = runTest {
        val transport = FakeTransportClient()
        val viewModel = createViewModel(transport = transport)

        transport.emitServerRequest(
            RpcMessage.request(
                id = JsonPrimitive("request-1"),
                method = "item/tool/requestUserInput",
                params = JsonObject(
                    mapOf(
                        "threadId" to JsonPrimitive("thread-1"),
                        "turnId" to JsonPrimitive("turn-1"),
                        "itemId" to JsonPrimitive("prompt-1"),
                        "questions" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive("question-1"),
                                        "header" to JsonPrimitive("Access"),
                                        "question" to JsonPrimitive("Which mode should we use?"),
                                        "options" to JsonArray(
                                            listOf(
                                                JsonObject(
                                                    mapOf(
                                                        "label" to JsonPrimitive("Safe"),
                                                        "description" to JsonPrimitive("Ask before edits"),
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
        advanceUntilIdle()

        val promptMessage = viewModel.uiState.value.conversation.messagesFor("thread-1").single()
        assertEquals(CodexMessageKind.UserInputPrompt, promptMessage.kind)
        assertEquals("Access\nWhich mode should we use?", promptMessage.text)
        assertEquals("request-1", promptMessage.structuredUserInputRequest?.requestID?.let { requestId ->
            if (requestId is JsonPrimitive) requestId.content else requestId.toString()
        })
    }

    @Test
    fun structuredUserInputResponseUsesIosEnvelopeAndClearsOnResolved() = runTest {
        val transport = FakeTransportClient()
        val viewModel = createViewModel(transport = transport)

        transport.emitServerRequest(
            RpcMessage.request(
                id = JsonPrimitive("request-1"),
                method = "item/tool/requestUserInput",
                params = JsonObject(
                    mapOf(
                        "threadId" to JsonPrimitive("thread-1"),
                        "turnId" to JsonPrimitive("turn-1"),
                        "itemId" to JsonPrimitive("prompt-1"),
                        "questions" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive("question-1"),
                                        "header" to JsonPrimitive("Access"),
                                        "question" to JsonPrimitive("Which mode should we use?"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        viewModel.respondToStructuredUserInput(
            requestID = JsonPrimitive("request-1"),
            answersByQuestionID = mapOf("question-1" to listOf("Safe")),
        )
        advanceUntilIdle()

        assertEquals(1, transport.sentResponses.size)
        assertEquals(
            JsonObject(
                mapOf(
                    "answers" to JsonObject(
                        mapOf(
                            "question-1" to JsonObject(
                                mapOf(
                                    "answers" to JsonArray(listOf(JsonPrimitive("Safe"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            transport.sentResponses.single().result,
        )
        assertTrue(viewModel.uiState.value.submittingStructuredRequestKeys.contains("request-1"))

        transport.emitNotification(
            RpcMessage.notification(
                method = "serverRequest/resolved",
                params = JsonObject(
                    mapOf(
                        "threadId" to JsonPrimitive("thread-1"),
                        "requestId" to JsonPrimitive("request-1"),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.conversation.messagesFor("thread-1").isEmpty())
        assertFalse(viewModel.uiState.value.submittingStructuredRequestKeys.contains("request-1"))
    }

    @Test
    fun startTurnRewritesSelectedFileMentionToCanonicalPath() = runTest {
        val thread = CodexThread(
            id = "thread-1",
            title = "Thread 1",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
            threadListResult = listOf(thread),
            startTurnResult = RemodexTurnStartResult(
                requestedThreadId = thread.id,
                threadId = thread.id,
                turnId = "turn-1",
                activeThread = thread,
                response = RpcMessage.success(null, JsonObject(emptyMap())),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.refreshThreads()
        advanceUntilIdle()
        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        viewModel.updateDraftTurnInput("Inspect @MainAct")
        viewModel.selectFileAutocomplete(
            CodexFuzzyFileMatch(
                root = "/tmp/project",
                path = "app/src/main/java/app/remodex/android/MainActivity.kt",
                fileName = "MainActivity.kt",
                score = 0.97,
            ),
        )
        advanceUntilIdle()

        viewModel.startTurn()
        advanceUntilIdle()

        assertEquals(
            "Inspect @app/src/main/java/app/remodex/android/MainActivity.kt",
            transport.lastStartTurnUserInput,
        )
        assertTrue(transport.lastStartTurnSkillMentions.isEmpty())
    }

    @Test
    fun startTurnSendsSelectedSkillMentionsWithIosEnvelope() = runTest {
        val thread = CodexThread(
            id = "thread-2",
            title = "Thread 2",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
            threadListResult = listOf(thread),
            startTurnResult = RemodexTurnStartResult(
                requestedThreadId = thread.id,
                threadId = thread.id,
                turnId = "turn-2",
                activeThread = thread,
                response = RpcMessage.success(null, JsonObject(emptyMap())),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.refreshThreads()
        advanceUntilIdle()
        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        viewModel.updateDraftTurnInput("Use ${'$'}pla")
        viewModel.selectSkillAutocomplete(
            CodexSkillMetadata(
                name = "planner",
                description = "Planning workflow",
                path = "/skills/planner",
                enabled = true,
            ),
        )
        advanceUntilIdle()

        viewModel.startTurn()
        advanceUntilIdle()

        assertEquals("Use ${'$'}planner", transport.lastStartTurnUserInput)
        assertEquals(
            listOf(
                CodexTurnSkillMention(
                    id = "planner",
                    name = "planner",
                    path = "/skills/planner",
                ),
            ),
            transport.lastStartTurnSkillMentions,
        )
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
        val viewModel = createViewModel(transport = transport)

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
        val viewModel = createViewModel(transport = transport)

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
        val continuedMessages = state.conversation.messagesFor(state.activeThreadId)
        assertEquals(1, continuedMessages.size)
        assertEquals(CodexMessageRole.User, continuedMessages.single().role)
        assertEquals("Continue from here", continuedMessages.single().text)
        assertEquals(CodexMessageDeliveryState.Confirmed, continuedMessages.single().deliveryState)

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
                orderIndex = 0,
            ),
            CodexMessage(
                id = "msg-2",
                threadId = thread.id,
                role = CodexMessageRole.Assistant,
                kind = CodexMessageKind.Chat,
                text = "Reply",
                orderIndex = 1,
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
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(thread.id, state.activeThreadId)
        assertEquals(historyMessages, state.conversation.messagesFor(thread.id))
        assertTrue(!state.conversation.isLoadingThread(thread.id))
        assertTrue(state.conversation.messageRevisionFor(thread.id) > 0)
        assertTrue(transport.recordedMethods.contains("thread/resume"))
        assertTrue(transport.recordedMethods.contains("thread/read"))
    }

    @Test
    fun selectThreadRefreshesInFlightTurnStateOnEveryDisplayPreparation() = runTest {
        val thread = CodexThread(
            id = "thread-history",
            title = "History",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()
        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        assertEquals(2, transport.recordedMethods.count { it == "thread/resume" })
        assertEquals(2, transport.recordedMethods.count { it == "thread/read" })
    }

    @Test
    fun selectMissingRolloutThreadArchivesLocallyWithoutShowingError() = runTest {
        val thread = CodexThread(
            id = "thread-fresh",
            title = "Fresh",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
            threadListResult = listOf(thread),
            resumeThreadThrowable = RemodexTransportException(
                kind = RemodexTransportFailureKind.Rpc,
                message = "RPC error -32600: NoRolloutFoundForThreadId ${thread.id}",
                rpcError = RpcError(
                    code = -32600,
                    message = "NoRolloutFoundForThreadId ${thread.id}",
                ),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(thread.id, state.activeThreadId)
        assertNull(state.errorMessage)
        assertFalse(state.conversation.isLoadingThread(thread.id))
        assertEquals(CodexThreadSyncState.ArchivedLocal, state.threads.first().syncState)
    }

    @Test
    fun selectThreadRefreshesRunningTurnStateFromThreadReadSnapshot() = runTest {
        val thread = CodexThread(
            id = "thread-running",
            title = "Running",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(
                    thread = thread,
                    turnStateSnapshot = RemodexThreadTurnStateSnapshot(
                        interruptibleTurnId = "turn-live",
                        latestTurnId = "turn-live",
                    ),
                ),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        val conversation = viewModel.uiState.value.conversation
        assertEquals("turn-live", conversation.activeTurnIdByThread[thread.id])
        assertTrue(conversation.runningThreadIds.contains(thread.id))
    }

    @Test
    fun foregroundReturnRefreshesRunningTurnStateForSelectedThread() = runTest {
        val thread = CodexThread(
            id = "thread-running",
            title = "Running",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(
                    thread = thread,
                    turnStateSnapshot = RemodexThreadTurnStateSnapshot(
                        interruptibleTurnId = "turn-live",
                        latestTurnId = "turn-live",
                    ),
                ),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()
        val initialReadCount = transport.recordedMethods.count { it == "thread/read" }

        viewModel.setForegroundState(false)
        viewModel.setForegroundState(true)
        advanceUntilIdle()

        val conversation = viewModel.uiState.value.conversation
        assertTrue(transport.recordedMethods.count { it == "thread/read" } > initialReadCount)
        assertEquals("turn-live", conversation.activeTurnIdByThread[thread.id])
        assertTrue(conversation.runningThreadIds.contains(thread.id))
    }

    @Test
    fun immediateRealtimeSyncRecoversActiveThreadAfterMissedCompletion() = runTest {
        val thread = CodexThread(
            id = "thread-running",
            title = "Running",
            cwd = "/tmp/project",
        )
        val completedHistory = listOf(
            CodexMessage(
                id = "msg-complete",
                threadId = thread.id,
                role = CodexMessageRole.Assistant,
                kind = CodexMessageKind.Chat,
                text = "Recovered output",
                orderIndex = 0,
            ),
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(
                    thread = thread,
                    messages = completedHistory,
                ),
            ),
            queuedReadThreadResults = mapOf(
                thread.id to listOf(
                    RemodexThreadReadResult(
                        thread = thread,
                        turnStateSnapshot = RemodexThreadTurnStateSnapshot(
                            interruptibleTurnId = "turn-live",
                            latestTurnId = "turn-live",
                        ),
                    ),
                    RemodexThreadReadResult(
                        thread = thread,
                        messages = completedHistory,
                    ),
                    RemodexThreadReadResult(
                        thread = thread,
                        messages = completedHistory,
                    ),
                ),
            ),
            threadListResult = listOf(thread),
        )
        val realtimePolicy = RemodexRealtimeSyncPolicy(
            enabled = true,
            threadListForegroundIntervalMillis = 60_000L,
            threadListBackgroundIntervalMillis = 60_000L,
            activeThreadForegroundIntervalMillis = 60_000L,
            activeThreadBackgroundIdleIntervalMillis = 60_000L,
            activeThreadBackgroundRunningIntervalMillis = 60_000L,
            runningThreadWatchForegroundIntervalMillis = 60_000L,
            runningThreadWatchBackgroundIntervalMillis = 60_000L,
        )
        val viewModel = createViewModel(
            transport = transport,
            realtimeSyncPolicy = realtimePolicy,
        )
        runCurrent()

        viewModel.selectThread(thread.id)
        runCurrent()

        val conversation = viewModel.uiState.value.conversation
        assertEquals(thread.id, viewModel.uiState.value.activeThreadId)
        assertFalse(conversation.threadHasActiveOrRunningTurn(thread.id))
        assertEquals("Recovered output", conversation.messagesFor(thread.id).last().text)
        assertTrue(transport.recordedMethods.count { it == "thread/read" } >= 3)

        viewModel.disconnect()
        runCurrent()
    }

    @Test
    fun immediateRealtimeSyncMarksWatchedInactiveRunningThreadReadyWithoutChangingSelection() = runTest {
        val runningThread = CodexThread(
            id = "thread-running",
            title = "Running",
            cwd = "/tmp/project",
        )
        val nextThread = CodexThread(
            id = "thread-next",
            title = "Next",
            cwd = "/tmp/project",
        )
        val completedHistory = listOf(
            CodexMessage(
                id = "msg-ready",
                threadId = runningThread.id,
                role = CodexMessageRole.Assistant,
                kind = CodexMessageKind.Chat,
                text = "Ready output",
                orderIndex = 0,
            ),
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                runningThread.id to RemodexThreadReadResult(
                    thread = runningThread,
                    messages = completedHistory,
                ),
                nextThread.id to RemodexThreadReadResult(
                    thread = nextThread,
                ),
            ),
            queuedReadThreadResults = mapOf(
                runningThread.id to listOf(
                    RemodexThreadReadResult(
                        thread = runningThread,
                        turnStateSnapshot = RemodexThreadTurnStateSnapshot(
                            interruptibleTurnId = "turn-live",
                            latestTurnId = "turn-live",
                        ),
                    ),
                    RemodexThreadReadResult(
                        thread = runningThread,
                        turnStateSnapshot = RemodexThreadTurnStateSnapshot(
                            interruptibleTurnId = "turn-live",
                            latestTurnId = "turn-live",
                        ),
                    ),
                    RemodexThreadReadResult(
                        thread = runningThread,
                        messages = completedHistory,
                    ),
                    RemodexThreadReadResult(
                        thread = runningThread,
                        messages = completedHistory,
                    ),
                ),
            ),
            threadListResult = listOf(runningThread, nextThread),
        )
        val realtimePolicy = RemodexRealtimeSyncPolicy(
            enabled = true,
            threadListForegroundIntervalMillis = 60_000L,
            threadListBackgroundIntervalMillis = 60_000L,
            activeThreadForegroundIntervalMillis = 60_000L,
            activeThreadBackgroundIdleIntervalMillis = 60_000L,
            activeThreadBackgroundRunningIntervalMillis = 60_000L,
            runningThreadWatchForegroundIntervalMillis = 60_000L,
            runningThreadWatchBackgroundIntervalMillis = 60_000L,
        )
        val viewModel = createViewModel(
            transport = transport,
            realtimeSyncPolicy = realtimePolicy,
        )
        runCurrent()

        viewModel.selectThread(runningThread.id)
        runCurrent()
        viewModel.selectThread(nextThread.id)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(nextThread.id, state.activeThreadId)
        assertEquals(
            CodexThreadRunBadgeState.Ready,
            state.conversation.threadRunBadgeState(runningThread.id),
        )
        assertFalse(state.conversation.threadHasActiveOrRunningTurn(runningThread.id))
        assertTrue(transport.recordedMethods.count { it == "thread/read" } >= 5)

        viewModel.disconnect()
        runCurrent()
    }

    @Test
    fun interruptTurnRecoversTurnIdFromThreadReadWhenLocalStateIsMissing() = runTest {
        val thread = CodexThread(
            id = "thread-running",
            title = "Running",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            startThreadResult = RemodexThreadStartResult(
                thread = thread,
                response = RpcMessage.success(null, JsonObject(emptyMap())),
            ),
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(
                    thread = thread,
                    turnStateSnapshot = RemodexThreadTurnStateSnapshot(
                        interruptibleTurnId = "turn-live",
                        latestTurnId = "turn-live",
                    ),
                ),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.startThread()
        advanceUntilIdle()
        viewModel.interruptTurn()
        advanceUntilIdle()

        assertEquals(listOf("thread/read", "turn/interrupt"), transport.recordedMethods.takeLast(2))
        assertEquals("turn-live", transport.lastInterruptedTurnId)
    }

    @Test
    fun refreshRuntimeOptionsNormalizesModelAndReasoningSelectionFromServer() = runTest {
        val transport = FakeTransportClient(
            modelOptions = listOf(
                CodexModelOption(
                    id = "model-gpt54",
                    model = "gpt-5.4",
                    displayName = "GPT-5.4",
                    isDefault = true,
                    supportedReasoningEfforts = listOf(
                        CodexReasoningEffortOption(reasoningEffort = "medium"),
                        CodexReasoningEffortOption(reasoningEffort = "high"),
                    ),
                    defaultReasoningEffort = "high",
                ),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.refreshRuntimeOptions()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("model-gpt54", state.selectedModelId)
        assertEquals("high", state.selectedReasoningEffort)
        assertEquals("GPT-5.4", state.selectedModelLabel)
        assertEquals("High", state.selectedReasoningLabel)
    }

    @Test
    fun startThreadUsesSelectedRuntimeModelAndExplicitWorkspace() = runTest {
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
            modelOptions = listOf(
                CodexModelOption(
                    id = "model-gpt54",
                    model = "gpt-5.4",
                    displayName = "GPT-5.4",
                    isDefault = true,
                ),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.refreshRuntimeOptions()
        advanceUntilIdle()
        viewModel.startThread(preferredProjectPath = "/tmp/project/")
        advanceUntilIdle()

        assertEquals("/tmp/project/", transport.lastPreferredProjectPath)
        assertEquals("gpt-5.4", transport.lastStartThreadModelIdentifier)
    }

    @Test
    fun startTurnUsesSelectedRuntimeStateForTransportRequest() = runTest {
        val thread = CodexThread(
            id = "thread-live",
            title = "Live",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
            modelOptions = listOf(
                CodexModelOption(
                    id = "model-gpt54",
                    model = "gpt-5.4",
                    displayName = "GPT-5.4",
                    isDefault = true,
                    supportedReasoningEfforts = listOf(
                        CodexReasoningEffortOption(reasoningEffort = "medium"),
                        CodexReasoningEffortOption(reasoningEffort = "high"),
                    ),
                    defaultReasoningEffort = "medium",
                ),
            ),
            startTurnResult = RemodexTurnStartResult(
                requestedThreadId = thread.id,
                threadId = thread.id,
                turnId = "turn-1",
                activeThread = thread,
                response = RpcMessage.success(null, JsonObject(emptyMap())),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.refreshRuntimeOptions()
        advanceUntilIdle()
        viewModel.selectRuntimeReasoningEffort("high")
        viewModel.selectAccessMode(app.remodex.android.core.model.CodexAccessMode.FullAccess)
        viewModel.selectThread(thread.id)
        advanceUntilIdle()
        viewModel.updateDraftTurnInput("Ship it")
        viewModel.startTurn()
        advanceUntilIdle()

        assertEquals(app.remodex.android.core.model.CodexAccessMode.FullAccess, transport.lastStartTurnAccessMode)
        assertEquals("gpt-5.4", transport.lastStartTurnModelIdentifier)
        assertEquals("high", transport.lastStartTurnReasoningEffort)
    }

    @Test
    fun startTurnWithoutSelectedThreadUsesTransportAutoCreateFlow() = runTest {
        val newThread = CodexThread(
            id = "thread-new",
            title = "New Chat",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            startTurnResult = RemodexTurnStartResult(
                requestedThreadId = newThread.id,
                threadId = newThread.id,
                turnId = "turn-1",
                activeThread = newThread,
                response = RpcMessage.success(null, JsonObject(emptyMap())),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.updateDraftTurnInput("Create and send")
        viewModel.startTurn()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("thread-new", state.activeThreadId)
        assertEquals("Create and send", state.conversation.messagesFor("thread-new").single().text)
        assertEquals(CodexMessageDeliveryState.Confirmed, state.conversation.messagesFor("thread-new").single().deliveryState)
        assertNull(state.errorMessage)
    }

    @Test
    fun startTurnShowsUserMessageImmediatelyInConversationState() = runTest {
        val thread = CodexThread(
            id = "thread-live",
            title = "Live",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
            startTurnResult = RemodexTurnStartResult(
                requestedThreadId = thread.id,
                threadId = thread.id,
                turnId = "turn-1",
                activeThread = thread,
                response = RpcMessage.success(null, JsonObject(emptyMap())),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()
        viewModel.updateDraftTurnInput("Ship it")
        viewModel.startTurn()
        advanceUntilIdle()

        val messages = viewModel.uiState.value.conversation.messagesFor(thread.id)
        assertEquals(1, messages.size)
        assertEquals(CodexMessageRole.User, messages.single().role)
        assertEquals("Ship it", messages.single().text)
        assertEquals(CodexMessageDeliveryState.Confirmed, messages.single().deliveryState)
        assertEquals("turn-1", messages.single().turnId)
    }

    @Test
    fun selectThreadLoadsGitBranchStateFromHostBridgeUsingThreadCwd() = runTest {
        val thread = CodexThread(
            id = "thread-git",
            title = "Git Thread",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
            gitBranchesWithStatusResult = GitBranchesWithStatusResult(
                branches = listOf("main", "feature/android"),
                currentBranch = "feature/android",
                defaultBranch = "main",
                status = GitRepoSyncResult(
                    currentBranch = "feature/android",
                    aheadCount = 2,
                    repoDiffTotals = GitDiffTotals(additions = 3, deletions = 1),
                ),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("/tmp/project", transport.lastGitWorkingDirectory)
        assertEquals("feature/android", state.currentGitBranch)
        assertEquals("main", state.gitDefaultBranch)
        assertEquals("main", state.selectedGitBaseBranch)
        assertEquals(listOf("main", "feature/android"), state.availableGitBranchTargets)
        assertEquals(2, state.gitRepoSync?.aheadCount)
        assertEquals(listOf("thread/resume", "thread/read", "git/branchesWithStatus"), transport.recordedMethods.takeLast(3))
    }

    @Test
    fun switchGitBranchUsesHostCheckoutAndRefreshesCurrentBranch() = runTest {
        val thread = CodexThread(
            id = "thread-git",
            title = "Git Thread",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
            gitBranchesWithStatusResult = GitBranchesWithStatusResult(
                branches = listOf("main", "feature/android"),
                currentBranch = "main",
                defaultBranch = "main",
                status = GitRepoSyncResult(currentBranch = "main"),
            ),
            gitCheckoutResult = GitCheckoutResult(
                currentBranch = "feature/android",
                status = GitRepoSyncResult(
                    currentBranch = "feature/android",
                    repoDiffTotals = GitDiffTotals(additions = 1, deletions = 0),
                ),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()
        viewModel.switchGitBranch("feature/android")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("feature/android", transport.lastCheckedOutBranch)
        assertEquals("feature/android", state.currentGitBranch)
        assertEquals(1, transport.recordedMethods.count { it == "git/checkout" })
        assertEquals(1, transport.recordedMethods.count { it == "git/branchesWithStatus" })
    }

    @Test
    fun turnCompletedRefreshesGitBranchesInsteadOfStatus() = runTest {
        val thread = CodexThread(
            id = "thread-git",
            title = "Git Thread",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
            gitBranchesWithStatusResult = GitBranchesWithStatusResult(
                branches = listOf("main", "feature/android"),
                currentBranch = "main",
                defaultBranch = "main",
                status = GitRepoSyncResult(currentBranch = "main"),
            ),
            gitStatusResult = GitRepoSyncResult(currentBranch = "main"),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        transport.emitNotification(
            RpcMessage.notification(
                method = "turn/started",
                params = JsonObject(
                    mapOf(
                        "threadId" to JsonPrimitive(thread.id),
                        "turnId" to JsonPrimitive("turn-1"),
                    ),
                ),
            ),
        )
        transport.emitNotification(
            RpcMessage.notification(
                method = "turn/completed",
                params = JsonObject(
                    mapOf(
                        "threadId" to JsonPrimitive(thread.id),
                        "turnId" to JsonPrimitive("turn-1"),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(2, transport.recordedMethods.count { it == "git/branchesWithStatus" })
        assertEquals(0, transport.recordedMethods.count { it == "git/status" })
    }

    @Test
    fun branchRefreshFailuresStaySilent() = runTest {
        val thread = CodexThread(
            id = "thread-git",
            title = "Git Thread",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
            gitBranchesWithStatusThrowable = IllegalStateException("branch refresh failed"),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
        assertTrue(!state.isLoadingGitBranchTargets)
    }

    @Test
    fun turnDiffNotificationsCreateRepoAffectingSystemRows() = runTest {
        val thread = CodexThread(
            id = "thread-git",
            title = "Git Thread",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        transport.emitNotification(
            RpcMessage.notification(
                method = "turn/diff/updated",
                params = JsonObject(
                    mapOf(
                        "threadId" to JsonPrimitive(thread.id),
                        "turnId" to JsonPrimitive("turn-1"),
                        "diff" to JsonPrimitive("diff --git a/app.txt b/app.txt\n+hello"),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        val repoMessages = viewModel.uiState.value.conversation.messagesFor(thread.id)
            .filter { it.role == CodexMessageRole.System && it.kind == CodexMessageKind.FileChange }
        assertEquals(1, repoMessages.size)
        assertTrue(repoMessages.single().text.contains("diff --git a/app.txt b/app.txt"))
    }

    @Test
    fun observedPushResetAddsHiddenMarkerWithoutVisibleTimelineRow() = runTest {
        val thread = CodexThread(
            id = "thread-git",
            title = "Git Thread",
            cwd = "/tmp/project",
        )
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
            gitBranchesWithStatusResult = GitBranchesWithStatusResult(
                branches = listOf("main"),
                currentBranch = "main",
                defaultBranch = "main",
                status = GitRepoSyncResult(
                    currentBranch = "main",
                    trackingBranch = "origin/main",
                    aheadCount = 1,
                ),
            ),
            gitStatusResult = GitRepoSyncResult(
                currentBranch = "main",
                trackingBranch = "origin/main",
                aheadCount = 0,
            ),
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()
        viewModel.refreshGitStatus(thread.id)
        advanceUntilIdle()

        val allMessages = viewModel.uiState.value.conversation.messagesFor(thread.id)
        val visibleMessages = viewModel.uiState.value.conversation.visibleMessagesFor(thread.id)
        assertTrue(allMessages.any { it.itemId == RemodexGitTimelineSupport.PushResetItemId })
        assertTrue(visibleMessages.none { it.itemId == RemodexGitTimelineSupport.PushResetItemId })
    }

    @Test
    fun branchRefreshDoesNotOverlapForSameThread() = runTest {
        val thread = CodexThread(
            id = "thread-git",
            title = "Git Thread",
            cwd = "/tmp/project",
        )
        val branchGate = CompletableDeferred<Unit>()
        val transport = FakeTransportClient(
            readThreadResults = mapOf(
                thread.id to RemodexThreadReadResult(thread = thread),
            ),
            gitBranchesWithStatusGate = branchGate,
        )
        val viewModel = createViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()
        viewModel.refreshGitBranchTargets(thread.id)
        advanceUntilIdle()

        assertEquals(1, transport.recordedMethods.count { it == "git/branchesWithStatus" })

        branchGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun selectGitBaseBranchUpdatesPrTargetSelection() = runTest {
        val viewModel = createViewModel(transport = FakeTransportClient())

        viewModel.selectGitBaseBranch("release")

        assertEquals("release", viewModel.uiState.value.selectedGitBaseBranch)
    }

    private class FakeTransportClient(
        private val readThreadResults: Map<String, RemodexThreadReadResult> = emptyMap(),
        queuedReadThreadResults: Map<String, List<RemodexThreadReadResult>> = emptyMap(),
        private val threadListResult: List<CodexThread> = emptyList(),
        private val startThreadResult: RemodexThreadStartResult? = null,
        private val startTurnResult: RemodexTurnStartResult? = null,
        private val resumeThreadThrowable: Throwable? = null,
        private val modelOptions: List<CodexModelOption> = emptyList(),
        private val gitBranchesWithStatusResult: GitBranchesWithStatusResult = GitBranchesWithStatusResult(),
        private val gitBranchesWithStatusThrowable: Throwable? = null,
        private val gitBranchesWithStatusGate: CompletableDeferred<Unit>? = null,
        private val gitStatusResult: GitRepoSyncResult = GitRepoSyncResult(),
        private val gitCheckoutResult: GitCheckoutResult? = null,
        private val connectResult: RemodexHandshakeResult = RemodexHandshakeResult(
            sessionUrl = "ws://test",
            initializeResponse = RpcMessage.success(null, JsonObject(emptyMap())),
        ),
        private val connectThrowable: Throwable? = null,
        initialConnectionState: RemodexTransportState = RemodexTransportState.Connected(
            sessionUrl = "ws://test",
            isInitialized = true,
        ),
    ) : RemodexTransportClient(appVersion = "test") {
        var lastPreferredProjectPath: String? = null
        var lastStartThreadModelIdentifier: String? = null
        var lastInterruptedTurnId: String? = null
        var lastStartTurnAccessMode: app.remodex.android.core.model.CodexAccessMode? = null
        var lastStartTurnModelIdentifier: String? = null
        var lastStartTurnReasoningEffort: String? = null
        var lastStartTurnUserInput: String? = null
        var lastStartTurnSkillMentions: List<CodexTurnSkillMention> = emptyList()
        var lastGitWorkingDirectory: String? = null
        var lastCheckedOutBranch: String? = null
        val connectedSessionUrls = mutableListOf<String>()
        val recordedMethods = mutableListOf<String>()
        val sentResponses = mutableListOf<SentRpcResponse>()
        val sentErrorResponses = mutableListOf<SentRpcErrorResponse>()
        private val stateFlow: MutableStateFlow<app.remodex.android.core.transport.RemodexTransportState>
        private val queuedReadThreadResultsById = queuedReadThreadResults
            .mapValues { (_, results) -> java.util.ArrayDeque(results) }
            .toMutableMap()

        init {
            val stateField = RemodexTransportClient::class.java.getDeclaredField("_state")
            stateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            stateFlow = stateField.get(this) as MutableStateFlow<app.remodex.android.core.transport.RemodexTransportState>
            stateFlow.value = initialConnectionState
        }

        override suspend fun connect(
            pairing: RemodexPairingPayload,
            role: String,
        ): RemodexHandshakeResult {
            connectedSessionUrls += pairing.relaySessionUrl()
            connectThrowable?.let { throw it }
            val resolvedConnectResult = if (connectResult.sessionUrl == "ws://test") {
                connectResult.copy(sessionUrl = pairing.relaySessionUrl())
            } else {
                connectResult
            }
            stateFlow.value = RemodexTransportState.Connected(
                sessionUrl = resolvedConnectResult.sessionUrl,
                isInitialized = true,
                hostInfo = resolvedConnectResult.hostInfo,
                supportsPlanCollaborationMode = resolvedConnectResult.supportsPlanCollaborationMode,
            )
            return resolvedConnectResult
        }

        override suspend fun disconnect() {
            stateFlow.value = RemodexTransportState.Disconnected
        }

        override suspend fun sendResponse(
            id: JsonValue,
            result: JsonValue,
        ) {
            sentResponses += SentRpcResponse(id = id, result = result)
        }

        override suspend fun sendErrorResponse(
            id: JsonValue?,
            code: Int,
            message: String,
            data: JsonValue?,
        ) {
            sentErrorResponses += SentRpcErrorResponse(
                id = id,
                code = code,
                message = message,
                data = data,
            )
        }

        override suspend fun startThread(
            preferredProjectPath: String?,
            accessMode: app.remodex.android.core.model.CodexAccessMode,
            modelIdentifier: String?,
        ): RemodexThreadStartResult {
            lastPreferredProjectPath = preferredProjectPath
            lastStartThreadModelIdentifier = modelIdentifier
            return startThreadResult ?: error("startThread was not stubbed")
        }

        override suspend fun listThreads(
            limit: Int,
            archived: Boolean,
        ): List<CodexThread> {
            recordedMethods += "thread/list"
            return if (threadListResult.isNotEmpty()) {
                threadListResult
            } else {
                readThreadResults.values.map { it.thread }
            }
        }

        override suspend fun readThread(
            threadId: String,
            includeTurns: Boolean,
        ): RemodexThreadReadResult {
            recordedMethods += "thread/read"
            val queuedResult = queuedReadThreadResultsById[threadId]?.pollFirst()
            if (queuedResult != null) {
                return queuedResult
            }
            return readThreadResults[threadId] ?: error("No thread/read stub for $threadId")
        }

        override suspend fun resumeThread(
            threadId: String,
            accessMode: app.remodex.android.core.model.CodexAccessMode,
            modelIdentifier: String?,
        ): RemodexThreadResumeResult {
            recordedMethods += "thread/resume"
            resumeThreadThrowable?.let { throw it }
            return RemodexThreadResumeResult(
                threadId = threadId,
                thread = readThreadResults[threadId]?.thread,
                response = RpcMessage.success(null, JsonObject(emptyMap())),
            )
        }

        override suspend fun interruptTurn(
            turnId: String,
            threadId: String?,
        ) {
            recordedMethods += "turn/interrupt"
            lastInterruptedTurnId = turnId
        }

        override suspend fun listModels(
            limit: Int,
        ): List<CodexModelOption> {
            recordedMethods += "model/list"
            return modelOptions
        }

        override suspend fun listCollaborationModes(): List<app.remodex.android.core.model.CodexCollaborationModeKind> {
            recordedMethods += "collaborationMode/list"
            return listOf(app.remodex.android.core.model.CodexCollaborationModeKind.Default)
        }

        override suspend fun gitBranchesWithStatus(
            workingDirectory: String,
        ): GitBranchesWithStatusResult {
            recordedMethods += "git/branchesWithStatus"
            lastGitWorkingDirectory = workingDirectory
            gitBranchesWithStatusThrowable?.let { throw it }
            gitBranchesWithStatusGate?.await()
            return gitBranchesWithStatusResult
        }

        override suspend fun gitStatus(
            workingDirectory: String,
        ): GitRepoSyncResult {
            recordedMethods += "git/status"
            lastGitWorkingDirectory = workingDirectory
            return gitStatusResult
        }

        override suspend fun gitCheckout(
            workingDirectory: String,
            branch: String,
        ): GitCheckoutResult {
            recordedMethods += "git/checkout"
            lastGitWorkingDirectory = workingDirectory
            lastCheckedOutBranch = branch
            return gitCheckoutResult ?: GitCheckoutResult(
                currentBranch = branch,
                status = gitStatusResult.copy(currentBranch = branch),
            )
        }

        override suspend fun startTurn(
            threadId: String?,
            userInput: String,
            accessMode: app.remodex.android.core.model.CodexAccessMode,
            collaborationMode: app.remodex.android.core.model.CodexCollaborationModeKind?,
            preferredProjectPath: String?,
            modelIdentifier: String?,
            reasoningEffort: String?,
            attachments: List<app.remodex.android.core.model.CodexImageAttachment>,
            skillMentions: List<CodexTurnSkillMention>,
        ): RemodexTurnStartResult {
            lastPreferredProjectPath = preferredProjectPath
            lastStartTurnUserInput = userInput
            lastStartTurnAccessMode = accessMode
            lastStartTurnModelIdentifier = modelIdentifier
            lastStartTurnReasoningEffort = reasoningEffort
            lastStartTurnSkillMentions = skillMentions
            return startTurnResult ?: error("startTurn was not stubbed")
        }

        fun emitNotification(message: RpcMessage) {
            val notificationsField = RemodexTransportClient::class.java.getDeclaredField("_notifications")
            notificationsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val notifications = notificationsField.get(this) as kotlinx.coroutines.flow.MutableSharedFlow<RpcMessage>
            notifications.tryEmit(message)
        }

        fun emitServerRequest(message: RpcMessage) {
            val serverRequestsField = RemodexTransportClient::class.java.getDeclaredField("_serverRequests")
            serverRequestsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val serverRequests = serverRequestsField.get(this) as kotlinx.coroutines.flow.MutableSharedFlow<RpcMessage>
            serverRequests.tryEmit(message)
        }

        fun emitState(state: RemodexTransportState) {
            stateFlow.value = state
        }
    }
}
