package app.remodex.android.core.transport

import app.remodex.android.core.model.CodexAccessMode
import app.remodex.android.core.model.CodexCollaborationModeKind
import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexModelOption
import app.remodex.android.core.model.CodexThread
import app.remodex.android.core.model.CodexThreadSyncState
import app.remodex.android.core.model.GitBranchesWithStatusResult
import app.remodex.android.core.model.GitCheckoutResult
import app.remodex.android.core.model.GitRepoSyncResult
import app.remodex.android.core.pairing.RemodexPairingPayload
import app.remodex.android.core.protocol.JsonValue
import app.remodex.android.core.protocol.RpcError
import app.remodex.android.core.protocol.RpcMessage
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

open class RemodexTransportClient(
    private val appVersion: String = "0.1.0",
    private val clientName: String = "remodex_android",
    private val clientTitle: String = "Remodex Android",
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val connectMutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<RpcMessage>>()
    private val pendingRequestMethods = ConcurrentHashMap<String, String>()

    private val _state = MutableStateFlow<RemodexTransportState>(RemodexTransportState.Disconnected)
    val state: StateFlow<RemodexTransportState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(RemodexTransportDiagnostics())
    val diagnostics: StateFlow<RemodexTransportDiagnostics> = _diagnostics.asStateFlow()

    private val _notifications = MutableSharedFlow<RpcMessage>(extraBufferCapacity = 32)
    val notifications: SharedFlow<RpcMessage> = _notifications.asSharedFlow()

    private val _serverRequests = MutableSharedFlow<RpcMessage>(extraBufferCapacity = 16)
    val serverRequests: SharedFlow<RpcMessage> = _serverRequests.asSharedFlow()

    @Volatile
    private var currentWebSocket: WebSocket? = null

    @Volatile
    private var currentSessionUrl: String? = null

    @Volatile
    private var isIntentionalDisconnect = false

    suspend fun connect(
        pairing: RemodexPairingPayload,
        role: String = "mobile",
    ): RemodexHandshakeResult = connectWithRoleCompatibility(
        sessionUrl = pairing.relaySessionUrl(),
        preferredRole = role,
        attempt = 1,
    )

    suspend fun connectWithRecovery(
        pairing: RemodexPairingPayload,
        role: String = "mobile",
        reconnectPolicy: RemodexReconnectPolicy = RemodexReconnectPolicy(),
    ): RemodexHandshakeResult {
        val sessionUrl = pairing.relaySessionUrl()
        var attempt = 1

        while (true) {
            try {
                return connectWithRoleCompatibility(
                    sessionUrl = sessionUrl,
                    preferredRole = role,
                    attempt = attempt,
                )
            } catch (throwable: Throwable) {
                if (!isRecoverableTransientFailure(throwable) || attempt > reconnectPolicy.backoffMillis.size) {
                    throw throwable
                }

                _state.value = RemodexTransportState.Retrying(
                    sessionUrl = sessionUrl,
                    attempt = attempt,
                    message = recoveryStatusMessage(throwable),
                )
                delay(reconnectPolicy.backoffMillis[attempt - 1])
                attempt += 1
            }
        }
    }

    private suspend fun connectWithRoleCompatibility(
        sessionUrl: String,
        preferredRole: String,
        attempt: Int,
    ): RemodexHandshakeResult {
        return try {
            connectInternal(
                sessionUrl = sessionUrl,
                role = preferredRole,
                attempt = attempt,
            )
        } catch (throwable: Throwable) {
            val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
            if (!shouldRetryWithLegacyRole(preferredRole, classified)) {
                throw classified
            }

            connectInternal(
                sessionUrl = sessionUrl,
                role = "iphone",
                attempt = attempt,
            )
        }
    }

    suspend fun disconnect() {
        connectMutex.withLock {
            isIntentionalDisconnect = true
            currentWebSocket?.close(1000, "Client disconnect")
            currentWebSocket = null
            currentSessionUrl = null
            failAllPendingRequests(
                RemodexTransportException(
                    kind = RemodexTransportFailureKind.Disconnected,
                    message = "Disconnected",
                ),
            )
            _state.value = RemodexTransportState.Disconnected
        }
    }

    protected open suspend fun sendRequest(
        method: String,
        params: JsonValue? = null,
        timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
    ): RpcMessage {
        val requestId = JsonPrimitive(UUID.randomUUID().toString())
        val requestKey = idKey(requestId)
        val response = CompletableDeferred<RpcMessage>()
        pendingRequests[requestKey] = response
        pendingRequestMethods[requestKey] = method

        try {
            sendMessage(
                RpcMessage.request(
                    id = requestId,
                    method = method,
                    params = params,
                ),
            )

            return withTimeout(timeoutMillis) {
                response.await()
            }
        } catch (throwable: Throwable) {
            pendingRequests.remove(requestKey)
            pendingRequestMethods.remove(requestKey)
            val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
            throw classified.withMethodContext(method)
        }
    }

    suspend fun sendNotification(
        method: String,
        params: JsonValue? = null,
    ) {
        sendMessage(RpcMessage.notification(method = method, params = params))
    }

    suspend fun sendResponse(id: JsonValue, result: JsonValue) {
        sendMessage(RpcMessage.success(id = id, result = result))
    }

    suspend fun sendErrorResponse(
        id: JsonValue?,
        code: Int,
        message: String,
        data: JsonValue? = null,
    ) {
        sendMessage(RpcMessage.failure(id = id, error = RpcError(code = code, message = message, data = data)))
    }

    open suspend fun listThreads(
        limit: Int = DEFAULT_THREAD_LIMIT,
        archived: Boolean = false,
    ): List<CodexThread> {
        val modernParams = buildMap<String, JsonValue> {
            put(
                "sourceKinds",
                JsonArray(
                    THREAD_LIST_SOURCE_KINDS.map(::JsonPrimitive),
                ),
            )
            put("cursor", kotlinx.serialization.json.JsonNull)
            put("limit", JsonPrimitive(limit))
            if (archived) {
                put("archived", JsonPrimitive(true))
            }
        }

        val response = try {
            sendThreadListRequest(strategy = "modern", params = modernParams)
        } catch (throwable: Throwable) {
            val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
            if (!shouldRetryThreadListWithLegacyParams(classified)) {
                throw classified
            }

            val legacyParams = buildMap<String, JsonValue> {
                put("limit", JsonPrimitive(limit))
                if (archived) {
                    put("archived", JsonPrimitive(true))
                }
            }

            runCatching {
                sendThreadListRequest(strategy = "legacy", params = legacyParams)
            }.getOrElse {
                if (archived || legacyParams.isNotEmpty()) {
                    runCatching {
                        sendThreadListRequest(
                            strategy = "minimal",
                            params = if (archived) {
                                mapOf("archived" to JsonPrimitive(true))
                            } else {
                                emptyMap<String, JsonValue>()
                            },
                        )
                    }.getOrElse {
                        sendThreadListRequest(strategy = "null-params", params = null)
                    }
                } else {
                    sendThreadListRequest(strategy = "null-params", params = null)
                }
            }
        }

        val resultObject = response.result as? JsonObject
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "thread/list response missing payload",
            )

        val page = (resultObject["data"] ?: resultObject["items"] ?: resultObject["threads"]) as? JsonArray
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "thread/list response missing data array",
            )

        return page.mapNotNull(::decodeThread)
    }

    open suspend fun listModels(
        limit: Int = 50,
    ): List<CodexModelOption> {
        val response = sendRequest(
            method = "model/list",
            params = JsonObject(
                mapOf(
                    "cursor" to kotlinx.serialization.json.JsonNull,
                    "limit" to JsonPrimitive(limit),
                    "includeHidden" to JsonPrimitive(false),
                ),
            ),
        )

        val resultObject = response.result as? JsonObject
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "model/list response missing payload",
            )

        val page = (resultObject["items"] ?: resultObject["data"] ?: resultObject["models"]) as? JsonArray
            ?: return emptyList()

        return page.mapNotNull(::decodeModelOption)
    }

    open suspend fun gitStatus(
        workingDirectory: String,
    ): GitRepoSyncResult {
        val normalizedWorkingDirectory = workingDirectory.trim()
        if (normalizedWorkingDirectory.isEmpty()) {
            throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "git/status requires a non-empty cwd",
            )
        }

        val response = sendRequest(
            method = "git/status",
            params = JsonObject(
                mapOf("cwd" to JsonPrimitive(normalizedWorkingDirectory)),
            ),
        )

        val result = response.result
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "git/status response missing payload",
            )

        return decodeGitRepoSyncResult(result)
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "git/status returned an undecodable payload",
            )
    }

    open suspend fun gitBranchesWithStatus(
        workingDirectory: String,
    ): GitBranchesWithStatusResult {
        val normalizedWorkingDirectory = workingDirectory.trim()
        if (normalizedWorkingDirectory.isEmpty()) {
            throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "git/branchesWithStatus requires a non-empty cwd",
            )
        }

        val response = sendRequest(
            method = "git/branchesWithStatus",
            params = JsonObject(
                mapOf("cwd" to JsonPrimitive(normalizedWorkingDirectory)),
            ),
        )

        val result = response.result
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "git/branchesWithStatus response missing payload",
            )

        return decodeGitBranchesWithStatusResult(result)
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "git/branchesWithStatus returned an undecodable payload",
            )
    }

    open suspend fun gitCheckout(
        workingDirectory: String,
        branch: String,
    ): GitCheckoutResult {
        val normalizedWorkingDirectory = workingDirectory.trim()
        val normalizedBranch = branch.trim()
        if (normalizedWorkingDirectory.isEmpty()) {
            throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "git/checkout requires a non-empty cwd",
            )
        }
        if (normalizedBranch.isEmpty()) {
            throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "git/checkout requires a non-empty branch",
            )
        }

        val response = sendRequest(
            method = "git/checkout",
            params = JsonObject(
                mapOf(
                    "cwd" to JsonPrimitive(normalizedWorkingDirectory),
                    "branch" to JsonPrimitive(normalizedBranch),
                ),
            ),
        )

        val result = response.result
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "git/checkout response missing payload",
            )

        return decodeGitCheckoutResult(result)
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "git/checkout returned an undecodable payload",
            )
    }

    private suspend fun sendThreadListRequest(
        strategy: String,
        params: Map<String, JsonValue>?,
    ): RpcMessage {
        updateDiagnostics { current ->
            current.copy(
                lastThreadListStrategy = strategy,
                lastThreadListParams = params?.let(::JsonObject)?.toString() ?: "null",
                recentEvents = (current.recentEvents + listOf("thread/list strategy=$strategy"))
                    .takeLast(MAX_DIAGNOSTIC_EVENTS),
            )
        }
        return sendRequest(
            method = "thread/list",
            params = params?.let(::JsonObject),
        )
    }

    open suspend fun readThread(
        threadId: String,
        includeTurns: Boolean = true,
    ): RemodexThreadReadResult {
        val response = try {
            sendThreadReadRequest(threadId = threadId, includeTurns = includeTurns)
        } catch (throwable: Throwable) {
            val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
            if (!includeTurns || !shouldRetryThreadReadWithoutTurns(classified)) {
                throw classified
            }
            sendThreadReadRequest(threadId = threadId, includeTurns = false)
        }

        val resultObject = response.result as? JsonObject
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "thread/read response missing payload",
            )
        val threadObject = resultObject["thread"] as? JsonObject
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "thread/read response missing thread payload",
            )

        val thread = decodeThread(threadObject)
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "thread/read returned an undecodable thread",
            )

        return RemodexThreadReadResult(
            thread = thread,
            messages = if (includeTurns) decodeMessagesFromThreadRead(threadId, threadObject) else emptyList(),
            turnStateSnapshot = extractThreadTurnStateSnapshot(threadObject),
        )
    }

    open suspend fun startThread(
        preferredProjectPath: String? = null,
        accessMode: CodexAccessMode = CodexAccessMode.OnRequest,
    ): RemodexThreadStartResult {
        val normalizedPreferredProjectPath = CodexThread.normalizeProjectPath(preferredProjectPath)
        val response = sendRequestWithSandboxFallback(
            method = "thread/start",
            baseParams = buildThreadStartRequestParams(normalizedPreferredProjectPath),
            accessMode = accessMode,
        )
        val thread = decodeThreadFromThreadEnvelope(
            response = response,
            method = "thread/start",
            preferredProjectPath = normalizedPreferredProjectPath,
        )
        return RemodexThreadStartResult(
            thread = thread,
            response = response,
        )
    }

    open suspend fun resumeThread(
        threadId: String,
        accessMode: CodexAccessMode = CodexAccessMode.OnRequest,
        modelIdentifier: String? = null,
    ): RemodexThreadResumeResult {
        val normalizedThreadId = threadId.trim()
        if (normalizedThreadId.isEmpty()) {
            throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "thread/resume requires a non-empty threadId",
            )
        }

        val response = sendRequestWithSandboxFallback(
            method = "thread/resume",
            baseParams = buildThreadResumeRequestParams(
                threadId = normalizedThreadId,
                modelIdentifier = modelIdentifier,
            ),
            accessMode = accessMode,
        )

        val resultObject = response.result as? JsonObject
        val threadObject = resultObject?.get("thread") as? JsonObject
        val resumedThread = threadObject
            ?.let(::decodeThread)
            ?.copy(syncState = CodexThreadSyncState.Live)

        return RemodexThreadResumeResult(
            threadId = normalizedThreadId,
            thread = resumedThread,
            response = response,
        )
    }

    open suspend fun interruptTurn(
        turnId: String,
        threadId: String? = null,
    ) {
        val normalizedTurnId = turnId.trim()
        if (normalizedTurnId.isEmpty()) {
            throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "turn/interrupt requires a non-empty turnId",
            )
        }

        val normalizedThreadId = threadId?.trim()?.takeIf(String::isNotEmpty)

        try {
            sendInterruptRequest(
                turnId = normalizedTurnId,
                threadId = normalizedThreadId,
                useSnakeCaseParams = false,
            )
        } catch (throwable: Throwable) {
            val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
            if (!shouldRetryInterruptWithSnakeCaseParams(classified)) {
                throw classified
            }
            sendInterruptRequest(
                turnId = normalizedTurnId,
                threadId = normalizedThreadId,
                useSnakeCaseParams = true,
            )
        }
    }

    open suspend fun listCollaborationModes(): List<CodexCollaborationModeKind> {
        val response = runCatching {
            sendRequest(
                method = "collaborationMode/list",
                params = JsonObject(emptyMap<String, JsonValue>()),
            )
        }.getOrElse { throwable ->
            val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
            if (!shouldRetryCollaborationModeListWithoutParams(classified)) {
                throw classified
            }
            sendRequest(method = "collaborationMode/list", params = null)
        }

        val result = response.result
        val candidateArrays = buildList<JsonArray> {
            if (result is JsonArray) {
                add(result)
            }

            val resultObject = result as? JsonObject
            listOf("modes", "collaborationModes", "items")
                .mapNotNull { key -> resultObject?.get(key) as? JsonArray }
                .forEach(::add)
        }

        val supportedModes = linkedSetOf<CodexCollaborationModeKind>()
        for (candidateArray in candidateArrays) {
            for (entry in candidateArray) {
                when (entryModeName(entry)) {
                    "default" -> supportedModes += CodexCollaborationModeKind.Default
                    "plan" -> supportedModes += CodexCollaborationModeKind.Plan
                }
            }
        }

        return supportedModes.toList()
    }

    open suspend fun startTurn(
        threadId: String,
        userInput: String,
        accessMode: CodexAccessMode = CodexAccessMode.OnRequest,
        collaborationMode: CodexCollaborationModeKind? = null,
        preferredProjectPath: String? = null,
        modelIdentifier: String? = null,
        reasoningEffort: String? = null,
    ): RemodexTurnStartResult {
        val normalizedThreadId = threadId.trim()
        val trimmedInput = userInput.trim()
        if (normalizedThreadId.isEmpty()) {
            throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "turn/start requires a non-empty threadId",
            )
        }
        if (trimmedInput.isEmpty()) {
            throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "turn/start requires non-empty user input",
            )
        }

        val normalizedPreferredProjectPath = CodexThread.normalizeProjectPath(preferredProjectPath)

        val resumedThread = try {
            resumeThread(
                threadId = normalizedThreadId,
                accessMode = accessMode,
                modelIdentifier = modelIdentifier,
            ).thread
        } catch (throwable: Throwable) {
            val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
            if (!shouldTreatAsThreadNotFound(classified)) {
                throw classified
            }

            return startTurnOnContinuationThread(
                requestedThreadId = normalizedThreadId,
                userInput = trimmedInput,
                accessMode = accessMode,
                collaborationMode = collaborationMode,
                preferredProjectPath = normalizedPreferredProjectPath,
                ensureContinuationResumed = true,
                modelIdentifier = modelIdentifier,
                reasoningEffort = reasoningEffort,
            )
        }

        try {
            val turnStart = sendTurnStartRequest(
                threadId = normalizedThreadId,
                userInput = trimmedInput,
                accessMode = accessMode,
                collaborationMode = collaborationMode,
                modelIdentifier = modelIdentifier,
                reasoningEffort = reasoningEffort,
            )
            return RemodexTurnStartResult(
                requestedThreadId = normalizedThreadId,
                threadId = normalizedThreadId,
                turnId = turnStart.turnId,
                collaborationMode = turnStart.collaborationMode,
                downgradedCollaborationMode = turnStart.downgradedCollaborationMode,
                activeThread = resumedThread,
                response = turnStart.response,
            )
        } catch (throwable: Throwable) {
            val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
            if (!shouldTreatAsThreadNotFound(classified)) {
                throw classified
            }

            return startTurnOnContinuationThread(
                requestedThreadId = normalizedThreadId,
                userInput = trimmedInput,
                accessMode = accessMode,
                collaborationMode = collaborationMode,
                preferredProjectPath = normalizedPreferredProjectPath,
                ensureContinuationResumed = false,
                modelIdentifier = modelIdentifier,
                reasoningEffort = reasoningEffort,
            )
        }
    }

    private suspend fun sendThreadReadRequest(
        threadId: String,
        includeTurns: Boolean,
    ): RpcMessage {
        return sendRequest(
            method = "thread/read",
            params = JsonObject(
                mapOf(
                    "threadId" to JsonPrimitive(threadId),
                    "includeTurns" to JsonPrimitive(includeTurns),
                ),
            ),
        )
    }

    private suspend fun sendInterruptRequest(
        turnId: String,
        threadId: String?,
        useSnakeCaseParams: Boolean,
    ) {
        val params = buildMap<String, JsonValue> {
            put(if (useSnakeCaseParams) "turn_id" else "turnId", JsonPrimitive(turnId))
            if (threadId != null) {
                put(if (useSnakeCaseParams) "thread_id" else "threadId", JsonPrimitive(threadId))
            }
        }

        sendRequest(
            method = "turn/interrupt",
            params = JsonObject(params),
        )
    }

    fun isRecoverableTransientFailure(throwable: Throwable): Boolean {
        val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
        return when (classified.kind) {
            RemodexTransportFailureKind.Timeout,
            RemodexTransportFailureKind.Network,
            RemodexTransportFailureKind.Disconnected -> !classified.isPermanent

            RemodexTransportFailureKind.PermanentRelayClosure,
            RemodexTransportFailureKind.Protocol,
            RemodexTransportFailureKind.Rpc,
            RemodexTransportFailureKind.Unknown -> false
        }
    }

    fun recoveryStatusMessage(throwable: Throwable): String {
        val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
        return when (classified.kind) {
            RemodexTransportFailureKind.Timeout -> "Connection timed out. Retrying..."
            RemodexTransportFailureKind.Network -> "Network error. Retrying..."
            RemodexTransportFailureKind.Disconnected -> "Connection dropped. Retrying..."
            RemodexTransportFailureKind.PermanentRelayClosure -> classified.message
            RemodexTransportFailureKind.Protocol,
            RemodexTransportFailureKind.Rpc,
            RemodexTransportFailureKind.Unknown -> classified.message
        }
    }

    private suspend fun connectInternal(
        sessionUrl: String,
        role: String,
        attempt: Int,
    ): RemodexHandshakeResult {
        return connectMutex.withLock {
            isIntentionalDisconnect = false
            currentWebSocket?.cancel()
            currentWebSocket = null
            currentSessionUrl = sessionUrl
            failAllPendingRequests(
                RemodexTransportException(
                    kind = RemodexTransportFailureKind.Disconnected,
                    message = "Superseded by a newer connection attempt",
                ),
            )
            _state.value = RemodexTransportState.Connecting(
                sessionUrl = sessionUrl,
                attempt = attempt,
            )

            val openSocket = openWebSocket(sessionUrl, role)
            currentWebSocket = openSocket
            _state.value = RemodexTransportState.Connected(
                sessionUrl = sessionUrl,
                isInitialized = false,
            )

            try {
                val handshake = initializeSession(sessionUrl)
                _state.value = RemodexTransportState.Connected(
                    sessionUrl = sessionUrl,
                    isInitialized = true,
                    hostInfo = handshake.hostInfo,
                    supportsPlanCollaborationMode = handshake.supportsPlanCollaborationMode,
                )
                handshake
            } catch (throwable: Throwable) {
                currentWebSocket?.cancel()
                currentWebSocket = null
                val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
                _state.value = RemodexTransportState.Failed(
                    sessionUrl = sessionUrl,
                    message = classified.message ?: "Transport initialization failed.",
                    isPermanent = classified.isPermanent,
                )
                throw classified
            }
        }
    }

    private suspend fun initializeSession(sessionUrl: String): RemodexHandshakeResult {
        val clientInfo = JsonObject(
            mapOf(
                "name" to JsonPrimitive(clientName),
                "title" to JsonPrimitive(clientTitle),
                "version" to JsonPrimitive(appVersion),
            ),
        )

        val modernParams = JsonObject(
            mapOf(
                "clientInfo" to clientInfo,
                "capabilities" to JsonObject(
                    mapOf(
                        "experimentalApi" to JsonPrimitive(true),
                    ),
                ),
            ),
        )

        val initializeResponse = try {
            sendRequest(method = "initialize", params = modernParams)
        } catch (throwable: Throwable) {
            val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
            if (!shouldRetryInitializeWithoutCapabilities(classified)) {
                throw classified
            }

            val legacyParams = JsonObject(
                mapOf(
                    "clientInfo" to clientInfo,
                ),
            )
            sendRequest(method = "initialize", params = legacyParams)
        }

        val hostInfo = extractHostInfoFromInitializeResponse(initializeResponse)
        val supportsPlan = runCatching {
            runtimeSupportsPlanCollaborationMode()
        }.getOrDefault(false)

        sendNotification(method = "initialized")
        updateConnectedState(
            hostInfo = hostInfo,
            supportsPlanCollaborationMode = supportsPlan,
        )

        return RemodexHandshakeResult(
            sessionUrl = sessionUrl,
            initializeResponse = initializeResponse,
            hostInfo = hostInfo,
            supportsPlanCollaborationMode = supportsPlan,
        )
    }

    private suspend fun runtimeSupportsPlanCollaborationMode(): Boolean {
        return listCollaborationModes().contains(CodexCollaborationModeKind.Plan)
    }

    private fun entryModeName(value: JsonValue): String? {
        val objectValue = value as? JsonObject
        return when {
            objectValue != null -> {
                listOf("mode", "name", "id")
                    .mapNotNull { key -> (objectValue[key] as? JsonPrimitive)?.contentOrNull }
                    .firstOrNull()
            }

            value is JsonPrimitive -> value.contentOrNull
            else -> null
        }?.lowercase()
    }

    private fun shouldRetryInitializeWithoutCapabilities(error: RemodexTransportException): Boolean {
        if (error.kind != RemodexTransportFailureKind.Rpc) {
            return false
        }

        val rpcError = error.rpcError ?: return false
        if (rpcError.code != -32600 && rpcError.code != -32602) {
            return false
        }

        val message = rpcError.message.lowercase()
        if (!message.contains("capabilities") && !message.contains("experimentalapi")) {
            return false
        }

        return message.contains("unknown")
            || message.contains("unexpected")
            || message.contains("unrecognized")
            || message.contains("invalid")
            || message.contains("unsupported")
            || message.contains("field")
    }

    private fun shouldRetryThreadReadWithoutTurns(error: RemodexTransportException): Boolean {
        if (error.kind != RemodexTransportFailureKind.Rpc) {
            return false
        }

        val rpcError = error.rpcError ?: return false
        if (rpcError.code != -32600) {
            return false
        }

        val message = rpcError.message.lowercase()
        return message.contains("not materialized")
            || message.contains("materialized")
            || message.contains("no messages")
    }

    private fun shouldRetryThreadListWithLegacyParams(error: RemodexTransportException): Boolean {
        if (error.kind != RemodexTransportFailureKind.Rpc) {
            return false
        }

        val rpcError = error.rpcError ?: return false
        if (rpcError.code != -32600 && rpcError.code != -32602) {
            return false
        }

        val message = rpcError.message.lowercase()
        return message.contains("invalid")
            || message.contains("unexpected")
            || message.contains("unknown")
            || message.contains("unrecognized")
            || message.contains("unsupported")
            || message.contains("field")
            || message.contains("sourcekinds")
            || message.contains("cursor")
            || message.contains("limit")
    }

    private fun shouldRetryCollaborationModeListWithoutParams(error: RemodexTransportException): Boolean {
        if (error.kind != RemodexTransportFailureKind.Rpc) {
            return false
        }

        val rpcError = error.rpcError ?: return false
        if (rpcError.code != -32600 && rpcError.code != -32602) {
            return false
        }

        val message = rpcError.message.lowercase()
        return message.contains("params")
            || message.contains("missing field")
            || message.contains("invalid")
    }

    private fun shouldRetryInterruptWithSnakeCaseParams(error: RemodexTransportException): Boolean {
        if (error.kind != RemodexTransportFailureKind.Rpc) {
            return false
        }

        val rpcError = error.rpcError ?: return false
        if (rpcError.code != -32600 && rpcError.code != -32602) {
            return false
        }

        val message = rpcError.message.lowercase()
        val hints = listOf("turnid", "threadid", "turn_id", "thread_id", "unknown field", "missing field", "invalid")
        return hints.any(message::contains)
    }

    private suspend fun sendRequestWithApprovalPolicyFallback(
        method: String,
        baseParams: Map<String, JsonValue>,
        accessMode: CodexAccessMode,
    ): RpcMessage {
        var lastError: Throwable? = null

        for ((index, policy) in accessMode.approvalPolicyCandidates.withIndex()) {
            val params = baseParams + ("approvalPolicy" to JsonPrimitive(policy))
            try {
                return sendRequest(method = method, params = JsonObject(params))
            } catch (throwable: Throwable) {
                lastError = throwable
                val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
                val hasMorePolicies = index < accessMode.approvalPolicyCandidates.lastIndex
                if (hasMorePolicies && shouldRetryWithApprovalPolicyFallback(classified)) {
                    continue
                }
                throw classified
            }
        }

        throw (lastError as? RemodexTransportException ?: classifyThrowable(lastError ?: IllegalStateException()))
    }

    private suspend fun sendRequestWithSandboxFallback(
        method: String,
        baseParams: Map<String, JsonValue>,
        accessMode: CodexAccessMode,
    ): RpcMessage {
        val primaryParams = baseParams + ("sandboxPolicy" to runtimeSandboxPolicyObject(accessMode))
        try {
            return sendRequestWithApprovalPolicyFallback(
                method = method,
                baseParams = primaryParams,
                accessMode = accessMode,
            )
        } catch (throwable: Throwable) {
            val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
            if (!shouldFallbackFromSandboxPolicy(classified)) {
                throw classified
            }
        }

        val legacyParams = baseParams + ("sandbox" to JsonPrimitive(accessMode.sandboxLegacyValue))
        try {
            return sendRequestWithApprovalPolicyFallback(
                method = method,
                baseParams = legacyParams,
                accessMode = accessMode,
            )
        } catch (throwable: Throwable) {
            val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
            if (!shouldFallbackFromSandboxPolicy(classified)) {
                throw classified
            }
        }

        return sendRequestWithApprovalPolicyFallback(
            method = method,
            baseParams = baseParams,
            accessMode = accessMode,
        )
    }

    private suspend fun startTurnOnContinuationThread(
        requestedThreadId: String,
        userInput: String,
        accessMode: CodexAccessMode,
        collaborationMode: CodexCollaborationModeKind?,
        preferredProjectPath: String?,
        ensureContinuationResumed: Boolean,
        modelIdentifier: String?,
        reasoningEffort: String?,
    ): RemodexTurnStartResult {
        val startedThread = startThread(
            preferredProjectPath = preferredProjectPath,
            accessMode = accessMode,
        ).thread
        val continuationThread = if (ensureContinuationResumed) {
            resumeThread(
                threadId = startedThread.id,
                accessMode = accessMode,
                modelIdentifier = modelIdentifier,
            ).thread ?: startedThread
        } else {
            startedThread
        }
        val turnStart = sendTurnStartRequest(
            threadId = continuationThread.id,
            userInput = userInput,
            accessMode = accessMode,
            collaborationMode = collaborationMode,
            modelIdentifier = modelIdentifier,
            reasoningEffort = reasoningEffort,
        )

        return RemodexTurnStartResult(
            requestedThreadId = requestedThreadId,
            threadId = continuationThread.id,
            turnId = turnStart.turnId,
            collaborationMode = turnStart.collaborationMode,
            downgradedCollaborationMode = turnStart.downgradedCollaborationMode,
            activeThread = continuationThread,
            archivedThreadId = requestedThreadId,
            continuationSummary = "Continued on a new live thread after `$requestedThreadId` became stale.",
            response = turnStart.response,
        )
    }

    private suspend fun sendTurnStartRequest(
        threadId: String,
        userInput: String,
        accessMode: CodexAccessMode,
        collaborationMode: CodexCollaborationModeKind?,
        modelIdentifier: String?,
        reasoningEffort: String?,
    ): TurnStartRequestResult {
        var effectiveCollaborationMode = collaborationMode
        var downgradedCollaborationMode = false

        while (true) {
            val requestParams = buildTurnStartRequestParams(
                threadId = threadId,
                userInput = userInput,
                collaborationMode = effectiveCollaborationMode,
                modelIdentifier = modelIdentifier,
                reasoningEffort = reasoningEffort,
            )

            try {
                val response = sendRequestWithSandboxFallback(
                    method = "turn/start",
                    baseParams = requestParams,
                    accessMode = accessMode,
                )
                return TurnStartRequestResult(
                    turnId = extractTurnId(response.result),
                    collaborationMode = effectiveCollaborationMode,
                    downgradedCollaborationMode = downgradedCollaborationMode,
                    response = response,
                )
            } catch (throwable: Throwable) {
                val classified = throwable as? RemodexTransportException ?: classifyThrowable(throwable)
                if (effectiveCollaborationMode != null &&
                    shouldRetryTurnStartWithoutCollaborationMode(classified)
                ) {
                    effectiveCollaborationMode = null
                    downgradedCollaborationMode = true
                    continue
                }
                throw classified
            }
        }
    }

    private fun runtimeSandboxPolicyObject(accessMode: CodexAccessMode): JsonValue {
        return when (accessMode) {
            CodexAccessMode.OnRequest -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("workspaceWrite"),
                    "networkAccess" to JsonPrimitive(true),
                ),
            )

            CodexAccessMode.FullAccess -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("dangerFullAccess"),
                ),
            )
        }
    }

    private fun shouldRetryWithApprovalPolicyFallback(error: RemodexTransportException): Boolean {
        if (error.kind != RemodexTransportFailureKind.Rpc) {
            return false
        }

        val rpcError = error.rpcError ?: return false
        if (rpcError.code != -32600 && rpcError.code != -32602) {
            return false
        }

        val message = rpcError.message.lowercase()
        return message.contains("approval")
            || message.contains("unknown variant")
            || message.contains("expected one of")
            || message.contains("onrequest")
            || message.contains("on-request")
    }

    private fun shouldFallbackFromSandboxPolicy(error: RemodexTransportException): Boolean {
        if (error.kind != RemodexTransportFailureKind.Rpc) {
            return false
        }

        val rpcError = error.rpcError ?: return false
        if (rpcError.code != -32600 && rpcError.code != -32602) {
            return false
        }

        val message = rpcError.message.lowercase()
        if (message.contains("thread not found") || message.contains("unknown thread")) {
            return false
        }

        return message.contains("invalid params")
            || message.contains("invalid param")
            || message.contains("unknown field")
            || message.contains("unexpected field")
            || message.contains("unrecognized field")
            || message.contains("failed to parse")
            || message.contains("unsupported")
    }

    private fun buildThreadStartRequestParams(
        preferredProjectPath: String?,
    ): Map<String, JsonValue> {
        val params = mutableMapOf<String, JsonValue>()
        preferredProjectPath?.let { params["cwd"] = JsonPrimitive(it) }
        return params
    }

    private fun buildThreadResumeRequestParams(
        threadId: String,
        modelIdentifier: String?,
    ): Map<String, JsonValue> {
        return mutableMapOf<String, JsonValue>(
            "threadId" to JsonPrimitive(threadId),
        ).apply {
            if (!modelIdentifier.isNullOrBlank()) {
                this["model"] = JsonPrimitive(modelIdentifier)
            }
        }
    }

    private fun buildTurnStartRequestParams(
        threadId: String,
        userInput: String,
        collaborationMode: CodexCollaborationModeKind?,
        modelIdentifier: String?,
        reasoningEffort: String?,
    ): Map<String, JsonValue> {
        val params = mutableMapOf<String, JsonValue>(
            "threadId" to JsonPrimitive(threadId),
            "input" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("text"),
                            "text" to JsonPrimitive(userInput),
                        ),
                    ),
                ),
            ),
        )

        if (!modelIdentifier.isNullOrBlank()) {
            params["model"] = JsonPrimitive(modelIdentifier)
        }
        if (!reasoningEffort.isNullOrBlank()) {
            params["effort"] = JsonPrimitive(reasoningEffort)
        }

        buildCollaborationModePayload(
            collaborationMode = collaborationMode,
            modelIdentifier = modelIdentifier,
            reasoningEffort = reasoningEffort,
        )?.let { payload ->
            params["collaborationMode"] = payload
        }

        return params
    }

    private fun decodeThreadFromThreadEnvelope(
        response: RpcMessage,
        method: String,
        preferredProjectPath: String? = null,
    ): CodexThread {
        val resultObject = response.result as? JsonObject
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "$method response missing payload",
            )
        val threadObject = resultObject["thread"] as? JsonObject
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "$method response missing thread payload",
            )
        val decodedThread = decodeThread(threadObject)
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Protocol,
                message = "$method returned an undecodable thread",
            )

        return applyPreferredProjectFallback(
            thread = decodedThread.copy(syncState = CodexThreadSyncState.Live),
            preferredProjectPath = preferredProjectPath,
        )
    }

    private fun applyPreferredProjectFallback(
        thread: CodexThread,
        preferredProjectPath: String?,
    ): CodexThread {
        return if (thread.normalizedProjectPath == null && preferredProjectPath != null) {
            thread.copy(cwd = preferredProjectPath)
        } else {
            thread
        }
    }

    private fun buildCollaborationModePayload(
        collaborationMode: CodexCollaborationModeKind?,
        modelIdentifier: String?,
        reasoningEffort: String?,
    ): JsonValue? {
        return when (collaborationMode) {
            null,
            CodexCollaborationModeKind.Default -> null

            CodexCollaborationModeKind.Plan -> JsonObject(
                mapOf(
                    "mode" to JsonPrimitive("plan"),
                    "settings" to JsonObject(
                        mapOf(
                            "model" to JsonPrimitive(modelIdentifier ?: ""),
                            "reasoning_effort" to (
                                if (reasoningEffort.isNullOrBlank()) {
                                    kotlinx.serialization.json.JsonNull
                                } else {
                                    JsonPrimitive(reasoningEffort)
                                }
                            ),
                            "developer_instructions" to kotlinx.serialization.json.JsonNull,
                        ),
                    ),
                ),
            )
        }
    }

    private fun shouldRetryTurnStartWithoutCollaborationMode(error: RemodexTransportException): Boolean {
        if (error.kind != RemodexTransportFailureKind.Rpc) {
            return false
        }

        val rpcError = error.rpcError ?: return false
        val message = rpcError.message.lowercase()
        if (!message.contains("collaborationmode") && !message.contains("collaboration_mode")) {
            return false
        }

        return message.contains("experimentalapi")
            || message.contains("unsupported")
            || message.contains("unknown")
            || message.contains("unexpected")
            || message.contains("unrecognized")
            || message.contains("invalid")
            || message.contains("field")
            || message.contains("mode")
    }

    private fun shouldTreatAsThreadNotFound(error: RemodexTransportException): Boolean {
        val message = (error.rpcError?.message ?: error.message).lowercase()
        if (message.contains("not materialized") || message.contains("not yet materialized")) {
            return false
        }
        return message.contains("thread not found") || message.contains("unknown thread")
    }

    private fun extractTurnId(result: JsonValue?): String? {
        val resultObject = result as? JsonObject ?: return null
        return listOf("turnId", "turn_id")
            .mapNotNull { key -> resultObject[key].stringValueOrNull() }
            .firstOrNull()
    }

    private suspend fun sendMessage(message: RpcMessage) {
        val socket = currentWebSocket
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Disconnected,
                message = "No active socket",
            )

        val encoded = json.encodeToString(RpcMessage.serializer(), message)
        updateDiagnostics { current ->
            current.copy(
                lastOutboundMethod = message.method,
                lastOutboundPayload = encoded,
            )
        }
        if (!socket.send(encoded)) {
            throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Disconnected,
                message = "Socket rejected outgoing message",
            )
        }
    }

    private suspend fun openWebSocket(sessionUrl: String, role: String): WebSocket {
        val request = Request.Builder()
            .url(sessionUrl)
            .header("x-role", role)
            .build()

        val openedSocket = CompletableDeferred<WebSocket>()
        val terminationHandled = AtomicBoolean(false)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openedSocket.complete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (terminationHandled.compareAndSet(false, true)) {
                    handleUnexpectedTermination(classifyClose(code, reason))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (terminationHandled.compareAndSet(false, true)) {
                    handleUnexpectedTermination(classifyClose(code, reason))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val classified = classifyThrowable(t)
                if (!openedSocket.isCompleted) {
                    openedSocket.completeExceptionally(classified)
                    return
                }
                if (terminationHandled.compareAndSet(false, true)) {
                    handleUnexpectedTermination(classified)
                }
            }
        }

        val socket = okHttpClient.newWebSocket(request, listener)
        return try {
            withTimeout(CONNECTION_TIMEOUT_MILLIS) {
                openedSocket.await()
            }
        } catch (throwable: Throwable) {
            socket.cancel()
            throw classifyThrowable(throwable)
        }
    }

    private fun handleIncomingText(text: String) {
        updateDiagnostics { current ->
            current.copy(lastInboundPayload = text)
        }
        val message = runCatching {
            json.decodeFromString(RpcMessage.serializer(), text)
        }.getOrElse {
            handleUnexpectedTermination(
                RemodexTransportException(
                    kind = RemodexTransportFailureKind.Protocol,
                    message = "Unable to decode relay payload.",
                    cause = it,
                ),
            )
            return
        }

        if (message.method != null) {
            if (message.id != null) {
                _serverRequests.tryEmit(message)
            } else {
                if (message.method == "bridge/hostInfo") {
                    updateConnectedState(hostInfo = decodeHostInfo(message.params))
                }
                _notifications.tryEmit(message)
            }
            return
        }

        val responseId = message.id ?: return
        val requestKey = idKey(responseId)
        val deferred = pendingRequests.remove(requestKey) ?: return
        val requestMethod = pendingRequestMethods.remove(requestKey)

        val rpcError = message.error
        if (rpcError != null) {
            updateDiagnostics { current ->
                current.copy(
                    lastRpcErrorMethod = requestMethod,
                    lastRpcErrorCode = rpcError.code,
                    lastRpcErrorMessage = rpcError.message,
                    lastRpcErrorData = rpcError.data?.toString(),
                    recentEvents = (current.recentEvents + listOf(
                        "rpc-error ${requestMethod ?: "unknown"} code=${rpcError.code}",
                    )).takeLast(MAX_DIAGNOSTIC_EVENTS),
                )
            }
            deferred.completeExceptionally(
                RemodexTransportException(
                    kind = RemodexTransportFailureKind.Rpc,
                    message = "RPC error ${rpcError.code}: ${rpcError.message}",
                    rpcError = rpcError,
                ),
            )
        } else {
            deferred.complete(message)
        }
    }

    private fun extractHostInfoFromInitializeResponse(response: RpcMessage): CodexHostInfo? {
        val result = response.result as? JsonObject ?: return null
        return decodeHostInfo(result["host"])
    }

    private fun decodeThread(value: JsonValue): CodexThread? {
        return runCatching {
            json.decodeFromJsonElement(CodexThread.serializer(), value)
        }.getOrNull()
    }

    private fun decodeModelOption(value: JsonValue): CodexModelOption? {
        return runCatching {
            json.decodeFromJsonElement(CodexModelOption.serializer(), value)
        }.getOrNull()
    }

    private fun decodeGitRepoSyncResult(value: JsonValue): GitRepoSyncResult? {
        return runCatching {
            json.decodeFromJsonElement(GitRepoSyncResult.serializer(), value)
        }.getOrNull()
    }

    private fun decodeGitBranchesWithStatusResult(value: JsonValue): GitBranchesWithStatusResult? {
        return runCatching {
            json.decodeFromJsonElement(GitBranchesWithStatusResult.serializer(), value)
        }.getOrNull()
    }

    private fun decodeGitCheckoutResult(value: JsonValue): GitCheckoutResult? {
        return runCatching {
            json.decodeFromJsonElement(GitCheckoutResult.serializer(), value)
        }.getOrNull()
    }

    private fun decodeHostInfo(value: JsonValue?): CodexHostInfo? {
        if (value == null) {
            return null
        }

        return runCatching {
            json.decodeFromJsonElement(CodexHostInfo.serializer(), value)
        }.getOrNull()
    }

    private fun RemodexTransportException.withMethodContext(method: String): RemodexTransportException {
        val loweredMessage = message.lowercase()
        val prefixedMessage = if (loweredMessage.startsWith("${method.lowercase()} failed:")) {
            message
        } else {
            "$method failed: $message"
        }

        return RemodexTransportException(
            kind = kind,
            message = prefixedMessage,
            cause = cause,
            rpcError = rpcError,
            relayCloseCode = relayCloseCode,
            isPermanent = isPermanent,
        )
    }

    private fun updateConnectedState(
        hostInfo: CodexHostInfo? = null,
        supportsPlanCollaborationMode: Boolean? = null,
    ) {
        val currentState = _state.value as? RemodexTransportState.Connected ?: return
        _state.value = currentState.copy(
            hostInfo = hostInfo ?: currentState.hostInfo,
            supportsPlanCollaborationMode = supportsPlanCollaborationMode
                ?: currentState.supportsPlanCollaborationMode,
        )
    }

    private fun handleUnexpectedTermination(throwable: RemodexTransportException) {
        if (isIntentionalDisconnect) {
            return
        }

        currentWebSocket = null
        failAllPendingRequests(throwable)
        _state.value = RemodexTransportState.Failed(
            sessionUrl = currentSessionUrl,
            message = throwable.message ?: "Transport connection failed.",
            isPermanent = throwable.isPermanent,
        )
    }

    private fun failAllPendingRequests(error: Throwable) {
        val outstanding = pendingRequests.values.toList()
        pendingRequests.clear()
        pendingRequestMethods.clear()
        outstanding.forEach { deferred ->
            deferred.completeExceptionally(error)
        }
    }

    private fun updateDiagnostics(transform: (RemodexTransportDiagnostics) -> RemodexTransportDiagnostics) {
        _diagnostics.value = transform(_diagnostics.value)
    }

    private fun classifyClose(code: Int, reason: String?): RemodexTransportException {
        val message = permanentRelayDisconnectMessage(code)
            ?: reason?.takeIf { it.isNotBlank() }
            ?: "Connection closed."
        return RemodexTransportException(
            kind = if (code in PERMANENT_RELAY_CLOSE_CODES) {
                RemodexTransportFailureKind.PermanentRelayClosure
            } else {
                RemodexTransportFailureKind.Disconnected
            },
            message = message,
            relayCloseCode = code,
            isPermanent = code in PERMANENT_RELAY_CLOSE_CODES,
        )
    }

    private fun classifyThrowable(throwable: Throwable): RemodexTransportException {
        if (throwable is RemodexTransportException) {
            return throwable
        }
        if (throwable is TimeoutCancellationException) {
            return RemodexTransportException(
                kind = RemodexTransportFailureKind.Timeout,
                message = "Connection timed out.",
                cause = throwable,
            )
        }
        if (throwable is CancellationException) {
            throw throwable
        }

        return when (throwable) {
            is SocketTimeoutException,
            is InterruptedIOException -> RemodexTransportException(
                kind = RemodexTransportFailureKind.Timeout,
                message = "Connection timed out.",
                cause = throwable,
            )

            is UnknownHostException,
            is ConnectException,
            is EOFException -> RemodexTransportException(
                kind = RemodexTransportFailureKind.Network,
                message = throwable.message ?: "Network error.",
                cause = throwable,
            )

            else -> RemodexTransportException(
                kind = RemodexTransportFailureKind.Unknown,
                message = throwable.message ?: "Unexpected transport failure.",
                cause = throwable,
            )
        }
    }

    private fun shouldRetryWithLegacyRole(
        preferredRole: String,
        error: RemodexTransportException,
    ): Boolean {
        if (preferredRole != "mobile") {
            return false
        }

        if (error.relayCloseCode == 4000) {
            return true
        }

        val loweredMessage = error.message?.lowercase().orEmpty()
        return loweredMessage.contains("invalid x-role")
            || loweredMessage.contains("socket rejected outgoing message")
    }

    private fun permanentRelayDisconnectMessage(closeCode: Int): String? {
        return when (closeCode) {
            4000 -> "The host session closed. Scan a new QR code to reconnect."
            4001 -> "This relay session was replaced by another host connection. Scan a new QR code to reconnect."
            4002 -> "This device was replaced by a newer connection. Scan a new QR code to reconnect."
            4003 -> "This relay pairing is no longer valid. Scan a new QR code to reconnect."
            else -> null
        }
    }

    private fun idKey(id: JsonValue): String {
        val primitive = id as? JsonPrimitive ?: return "j:$id"
        val content = primitive.contentOrNull ?: return "j:$id"
        return when {
            primitive.isString -> "s:$content"
            primitive.intOrNull != null -> "n:$content"
            primitive.booleanOrNull != null -> "b:$content"
            else -> "p:$content"
        }
    }

    private fun decodeMessagesFromThreadRead(
        threadId: String,
        threadObject: JsonObject,
    ): List<CodexMessage> {
        val turns = threadObject["turns"] as? JsonArray ?: return emptyList()
        var orderIndex = 0
        val messages = mutableListOf<CodexMessage>()

        for (turnValue in turns) {
            val turnObject = turnValue as? JsonObject ?: continue
            val turnId = turnObject["id"].stringValueOrNull()
            val items = turnObject["items"] as? JsonArray ?: continue

            for (itemValue in items) {
                val itemObject = itemValue as? JsonObject ?: continue
                val itemType = normalizeItemType(itemObject["type"].stringValueOrNull()) ?: continue
                val itemId = itemObject["id"].stringValueOrNull()
                val decodedText = decodeItemDisplayText(itemObject)
                if (decodedText.isBlank()) {
                    continue
                }

                val messageRole = when (itemType) {
                    "usermessage" -> CodexMessageRole.User
                    "agentmessage", "assistantmessage" -> CodexMessageRole.Assistant
                    "message" -> {
                        val role = itemObject["role"].stringValueOrNull()?.lowercase().orEmpty()
                        if (role.contains("user")) CodexMessageRole.User else CodexMessageRole.Assistant
                    }
                    else -> CodexMessageRole.System
                }

                val messageKind = when (itemType) {
                    "reasoning" -> CodexMessageKind.Thinking
                    "filechange", "toolcall", "diff" -> CodexMessageKind.FileChange
                    "commandexecution" -> CodexMessageKind.CommandExecution
                    "plan" -> CodexMessageKind.Plan
                    else -> CodexMessageKind.Chat
                }

                messages += CodexMessage(
                    id = itemId ?: "${threadId}_$orderIndex",
                    threadId = threadId,
                    role = messageRole,
                    kind = messageKind,
                    text = decodedText,
                    createdAt = null,
                    turnId = turnId,
                    itemId = itemId,
                    orderIndex = orderIndex,
                )
                orderIndex += 1
            }
        }

        return messages
    }

    private fun extractThreadTurnStateSnapshot(threadObject: JsonObject): RemodexThreadTurnStateSnapshot {
        val turns = threadObject["turns"] as? JsonArray ?: return RemodexThreadTurnStateSnapshot()
        val latestTurnObject = turns.lastOrNull() as? JsonObject ?: return RemodexThreadTurnStateSnapshot()
        val latestTurnId = latestTurnObject["id"].stringValueOrNull()
            ?: latestTurnObject["turnId"].stringValueOrNull()
            ?: latestTurnObject["turn_id"].stringValueOrNull()
        val latestStatus = normalizedTurnStatus(latestTurnObject)

        if (!isInterruptibleTurnStatus(latestStatus)) {
            return RemodexThreadTurnStateSnapshot(
                latestTurnId = latestTurnId,
            )
        }

        if (latestTurnId != null) {
            return RemodexThreadTurnStateSnapshot(
                interruptibleTurnId = latestTurnId,
                latestTurnId = latestTurnId,
            )
        }

        return RemodexThreadTurnStateSnapshot(
            hasInterruptibleTurnWithoutId = true,
            latestTurnId = latestTurnId,
        )
    }

    private fun normalizedTurnStatus(turnObject: JsonObject): String? {
        val status = turnObject["status"].stringValueOrNull()
            ?: turnObject["turnStatus"].stringValueOrNull()
            ?: turnObject["turn_status"].stringValueOrNull()

        return status
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.replace("_", "")
            ?.replace("-", "")
            ?.lowercase()
    }

    private fun isInterruptibleTurnStatus(status: String?): Boolean {
        if (status == null) {
            return true
        }

        if (status.contains("inprogress")
            || status.contains("running")
            || status.contains("pending")
            || status.contains("started")
        ) {
            return true
        }

        if (status.contains("complete")
            || status.contains("failed")
            || status.contains("error")
            || status.contains("interrupt")
            || status.contains("cancel")
            || status.contains("stopped")
        ) {
            return false
        }

        return true
    }

    private fun decodeItemDisplayText(itemObject: JsonObject): String {
        val contentItems = itemObject["content"] as? JsonArray ?: JsonArray(emptyList())
        val textParts = buildList {
            for (value in contentItems) {
                val objectValue = value as? JsonObject ?: continue
                val contentType = normalizeItemType(objectValue["type"].stringValueOrNull()).orEmpty()

                when (contentType) {
                    "text", "inputtext", "outputtext", "message" -> {
                        objectValue["text"].stringValueOrNull()?.let(::add)
                    }

                    "skill" -> {
                        val skillId = objectValue["id"].stringValueOrNull()
                        val skillName = objectValue["name"].stringValueOrNull()
                        val resolved = skillId ?: skillName
                        if (!resolved.isNullOrBlank()) {
                            add("\$$resolved")
                        }
                    }
                }

                val nestedDataText = (objectValue["data"] as? JsonObject)
                    ?.get("text")
                    .stringValueOrNull()
                if (!nestedDataText.isNullOrBlank()) {
                    add(nestedDataText)
                }
            }
        }

        if (textParts.isNotEmpty()) {
            return textParts.joinToString("\n").trim()
        }

        return listOf(
            itemObject["text"].stringValueOrNull(),
            itemObject["message"].stringValueOrNull(),
            itemObject["summary"].stringValueOrNull(),
            itemObject["command"].stringValueOrNull(),
            itemObject["title"].stringValueOrNull(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun normalizeItemType(rawValue: String?): String? {
        val normalized = rawValue
            ?.trim()
            ?.lowercase()
            ?.replace("/", "")
            ?.replace("_", "")
            ?.replace("-", "")
            ?.replace(" ", "")
            .orEmpty()
        return normalized.ifEmpty { null }
    }

    private fun JsonValue?.stringValueOrNull(): String? {
        return (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private data class TurnStartRequestResult(
        val turnId: String?,
        val collaborationMode: CodexCollaborationModeKind?,
        val downgradedCollaborationMode: Boolean,
        val response: RpcMessage,
    )

    companion object {
        private const val CONNECTION_TIMEOUT_MILLIS = 12_000L
        private const val REQUEST_TIMEOUT_MILLIS = 15_000L
        private const val DEFAULT_THREAD_LIMIT = 20
        private const val MAX_DIAGNOSTIC_EVENTS = 12
        private val PERMANENT_RELAY_CLOSE_CODES = setOf(4000, 4001, 4002, 4003)
        private val THREAD_LIST_SOURCE_KINDS = listOf(
            "cli",
            "vscode",
            "appServer",
            "exec",
            "unknown",
        )
    }
}
