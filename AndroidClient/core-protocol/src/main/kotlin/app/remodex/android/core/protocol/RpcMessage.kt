package app.remodex.android.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class RpcMessage(
    val jsonrpc: String? = "2.0",
    val id: JsonValue? = null,
    val method: String? = null,
    val params: JsonValue? = null,
    val result: JsonValue? = null,
    val error: RpcError? = null,
) {
    val isRequest: Boolean
        get() = method != null

    val isResponse: Boolean
        get() = result != null || error != null

    val isErrorResponse: Boolean
        get() = error != null

    companion object {
        fun request(id: JsonValue, method: String, params: JsonValue? = null): RpcMessage =
            RpcMessage(id = id, method = method, params = params)

        fun notification(method: String, params: JsonValue? = null): RpcMessage =
            RpcMessage(id = null, method = method, params = params)

        fun success(id: JsonValue?, result: JsonValue): RpcMessage =
            RpcMessage(id = id, result = result)

        fun failure(id: JsonValue?, error: RpcError): RpcMessage =
            RpcMessage(id = id, error = error)
    }
}

@Serializable
data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonValue? = null,
)
