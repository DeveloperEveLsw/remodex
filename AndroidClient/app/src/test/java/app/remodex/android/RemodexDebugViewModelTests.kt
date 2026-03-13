package app.remodex.android

import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageDeliveryState
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexModelOption
import app.remodex.android.core.model.CodexReasoningEffortOption
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadSyncState
import app.remodex.android.core.model.GitBranchesWithStatusResult
import app.remodex.android.core.model.GitCheckoutResult
import app.remodex.android.core.model.GitDiffTotals
import app.remodex.android.core.model.GitRepoSyncResult
import app.remodex.android.core.transport.RemodexThreadResumeResult
import app.remodex.android.core.transport.RemodexThreadTurnStateSnapshot
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
import kotlinx.coroutines.flow.MutableStateFlow
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
        val viewModel = RemodexDebugViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(thread.id, state.activeThreadId)
        assertEquals(historyMessages, state.conversation.messagesFor(thread.id))
        assertTrue(!state.conversation.isLoadingThread(thread.id))
        assertTrue(state.conversation.messageRevisionFor(thread.id) > 0)
        assertEquals(listOf("thread/resume", "thread/read"), transport.recordedMethods.takeLast(2))
    }

    @Test
    fun selectThreadHydratesOncePerThreadUntilRecoveryForcesRefresh() = runTest {
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
        val viewModel = RemodexDebugViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()
        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        assertEquals(
            listOf("thread/resume", "thread/read", "thread/resume"),
            transport.recordedMethods.takeLast(3),
        )
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
        val viewModel = RemodexDebugViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()

        val conversation = viewModel.uiState.value.conversation
        assertEquals("turn-live", conversation.activeTurnIdByThread[thread.id])
        assertTrue(conversation.runningThreadIds.contains(thread.id))
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
        val viewModel = RemodexDebugViewModel(transport = transport)

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
        val viewModel = RemodexDebugViewModel(transport = transport)

        viewModel.refreshRuntimeOptions()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("model-gpt54", state.selectedModelId)
        assertEquals("high", state.selectedReasoningEffort)
        assertEquals("GPT-5.4", state.selectedModelLabel)
        assertEquals("High", state.selectedReasoningLabel)
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
        val viewModel = RemodexDebugViewModel(transport = transport)

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
        val viewModel = RemodexDebugViewModel(transport = transport)

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
        val viewModel = RemodexDebugViewModel(transport = transport)

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
        val viewModel = RemodexDebugViewModel(transport = transport)

        viewModel.selectThread(thread.id)
        advanceUntilIdle()
        viewModel.switchGitBranch("feature/android")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("feature/android", transport.lastCheckedOutBranch)
        assertEquals("feature/android", state.currentGitBranch)
        assertEquals(listOf("git/checkout", "git/branchesWithStatus"), transport.recordedMethods.takeLast(2))
    }

    @Test
    fun selectGitBaseBranchUpdatesPrTargetSelection() = runTest {
        val viewModel = RemodexDebugViewModel(transport = FakeTransportClient())

        viewModel.selectGitBaseBranch("release")

        assertEquals("release", viewModel.uiState.value.selectedGitBaseBranch)
    }

    private class FakeTransportClient(
        private val readThreadResults: Map<String, RemodexThreadReadResult> = emptyMap(),
        private val startThreadResult: RemodexThreadStartResult? = null,
        private val startTurnResult: RemodexTurnStartResult? = null,
        private val modelOptions: List<CodexModelOption> = emptyList(),
        private val gitBranchesWithStatusResult: GitBranchesWithStatusResult = GitBranchesWithStatusResult(),
        private val gitStatusResult: GitRepoSyncResult = GitRepoSyncResult(),
        private val gitCheckoutResult: GitCheckoutResult? = null,
    ) : RemodexTransportClient(appVersion = "test") {
        var lastPreferredProjectPath: String? = null
        var lastInterruptedTurnId: String? = null
        var lastStartTurnAccessMode: app.remodex.android.core.model.CodexAccessMode? = null
        var lastStartTurnModelIdentifier: String? = null
        var lastStartTurnReasoningEffort: String? = null
        var lastGitWorkingDirectory: String? = null
        var lastCheckedOutBranch: String? = null
        val recordedMethods = mutableListOf<String>()

        init {
            val stateField = RemodexTransportClient::class.java.getDeclaredField("_state")
            stateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val stateFlow = stateField.get(this) as MutableStateFlow<app.remodex.android.core.transport.RemodexTransportState>
            stateFlow.value = app.remodex.android.core.transport.RemodexTransportState.Connected(
                sessionUrl = "ws://test",
                isInitialized = true,
            )
        }

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
            recordedMethods += "thread/read"
            return readThreadResults[threadId] ?: error("No thread/read stub for $threadId")
        }

        override suspend fun resumeThread(
            threadId: String,
            accessMode: app.remodex.android.core.model.CodexAccessMode,
            modelIdentifier: String?,
        ): RemodexThreadResumeResult {
            recordedMethods += "thread/resume"
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
            threadId: String,
            userInput: String,
            accessMode: app.remodex.android.core.model.CodexAccessMode,
            collaborationMode: app.remodex.android.core.model.CodexCollaborationModeKind?,
            preferredProjectPath: String?,
            modelIdentifier: String?,
            reasoningEffort: String?,
        ): RemodexTurnStartResult {
            lastPreferredProjectPath = preferredProjectPath
            lastStartTurnAccessMode = accessMode
            lastStartTurnModelIdentifier = modelIdentifier
            lastStartTurnReasoningEffort = reasoningEffort
            return startTurnResult ?: error("startTurn was not stubbed")
        }
    }
}
