package app.remodex.android.core.transport

import app.remodex.android.core.model.CodexHostInfo
import app.remodex.android.core.model.CodexMessage
import app.remodex.android.core.model.CodexMessageKind
import app.remodex.android.core.model.CodexMessageRole
import app.remodex.android.core.model.CodexThread
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

class RemodexTransportClient(
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

    private val _state = MutableStateFlow<RemodexTransportState>(RemodexTransportState.Disconnected)
    val state: StateFlow<RemodexTransportState> = _state.asStateFlow()

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

    suspend fun sendRequest(
        method: String,
        params: JsonValue? = null,
        timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
    ): RpcMessage {
        val requestId = JsonPrimitive(UUID.randomUUID().toString())
        val requestKey = idKey(requestId)
        val response = CompletableDeferred<RpcMessage>()
        pendingRequests[requestKey] = response

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
            if (throwable is RemodexTransportException) {
                throw throwable
            }
            throw classifyThrowable(throwable)
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

    suspend fun listThreads(
        limit: Int = DEFAULT_THREAD_LIMIT,
        archived: Boolean = false,
    ): List<CodexThread> {
        val params = buildMap<String, JsonValue> {
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

        val response = sendRequest(
            method = "thread/list",
            params = JsonObject(params),
        )

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

    suspend fun readThread(
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
        )
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
        val response = sendRequest(method = "collaborationMode/list")
        val result = response.result

        if (result is JsonArray && result.any { entryModeName(it) == "plan" }) {
            return true
        }

        val resultObject = result as? JsonObject ?: return false
        val arrays = listOf("modes", "collaborationModes", "items")
            .mapNotNull { key -> resultObject[key] as? JsonArray }

        return arrays.any { entries ->
            entries.any { entryModeName(it) == "plan" }
        }
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

    private suspend fun sendMessage(message: RpcMessage) {
        val socket = currentWebSocket
            ?: throw RemodexTransportException(
                kind = RemodexTransportFailureKind.Disconnected,
                message = "No active socket",
            )

        val encoded = json.encodeToString(RpcMessage.serializer(), message)
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
        val deferred = pendingRequests.remove(idKey(responseId)) ?: return

        val rpcError = message.error
        if (rpcError != null) {
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

    private fun decodeHostInfo(value: JsonValue?): CodexHostInfo? {
        if (value == null) {
            return null
        }

        return runCatching {
            json.decodeFromJsonElement(CodexHostInfo.serializer(), value)
        }.getOrNull()
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
        outstanding.forEach { deferred ->
            deferred.completeExceptionally(error)
        }
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

    companion object {
        private const val CONNECTION_TIMEOUT_MILLIS = 12_000L
        private const val REQUEST_TIMEOUT_MILLIS = 15_000L
        private const val DEFAULT_THREAD_LIMIT = 20
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
