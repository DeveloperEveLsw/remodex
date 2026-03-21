package app.remodex.android.core.pairing

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class RemodexPairingPayload(
    @SerialName("relay")
    val relayUrl: String,
    val sessionId: String,
) {
    val normalizedRelayUrl: String
        get() = normalizeRelayUrl(relayUrl)

    val normalizedSessionId: String
        get() = sessionId.trim()

    fun relaySessionUrl(): String = "${normalizedRelayUrl}/${normalizedSessionId}"

    companion object {
        private fun normalizeRelayUrl(rawValue: String): String {
            val trimmed = rawValue.trim()
            val uri = runCatching { URI(trimmed) }.getOrElse {
                throw RemodexPairingException(
                    RemodexPairingErrorCode.InvalidRelayUrl,
                    "QR code relay URL is invalid.",
                    it,
                )
            }

            val scheme = uri.scheme?.lowercase()
                ?: throw RemodexPairingException(
                    RemodexPairingErrorCode.InvalidRelayUrl,
                    "QR code relay URL is missing a scheme.",
                )

            val webSocketScheme = when (scheme) {
                "ws", "wss" -> scheme
                "http" -> "ws"
                "https" -> "wss"
                else -> throw RemodexPairingException(
                    RemodexPairingErrorCode.InvalidRelayUrl,
                    "QR code relay URL must use ws, wss, http, or https.",
                )
            }

            if (uri.host.isNullOrBlank()) {
                throw RemodexPairingException(
                    RemodexPairingErrorCode.InvalidRelayUrl,
                    "QR code relay URL is missing a host.",
                )
            }

            if (uri.rawQuery != null || uri.rawFragment != null) {
                throw RemodexPairingException(
                    RemodexPairingErrorCode.InvalidRelayUrl,
                    "QR code relay URL must not contain a query or fragment.",
                )
            }

            val path = uri.path.orEmpty().trimEnd('/').takeIf { it.isNotEmpty() }.orEmpty()
            val portSuffix = if (uri.port != -1) ":${uri.port}" else ""
            return "$webSocketScheme://${uri.host}$portSuffix$path"
        }
    }
}

enum class RemodexPairingErrorCode {
    EmptyPayload,
    InvalidJson,
    MissingRelayUrl,
    MissingSessionId,
    InvalidRelayUrl,
}

class RemodexPairingException(
    val code: RemodexPairingErrorCode,
    override val message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object RemodexPairingParser {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun parse(rawPayload: String): RemodexPairingPayload {
        val trimmedPayload = rawPayload.trim()
        if (trimmedPayload.isEmpty()) {
            throw RemodexPairingException(
                RemodexPairingErrorCode.EmptyPayload,
                "Paste the QR payload before trying to connect.",
            )
        }

        val decodedObject = runCatching {
            json.parseToJsonElement(trimmedPayload) as? JsonObject
        }.getOrElse {
            throw RemodexPairingException(
                RemodexPairingErrorCode.InvalidJson,
                "Not a valid pairing JSON payload.",
                it,
            )
        }
        if (decodedObject == null) {
            throw RemodexPairingException(
                RemodexPairingErrorCode.InvalidJson,
                "Not a valid pairing JSON payload.",
            )
        }

        val relayUrl = runCatching { decodedObject["relay"]?.jsonPrimitive?.content }
            .getOrNull()
            .orEmpty()
        val sessionId = runCatching { decodedObject["sessionId"]?.jsonPrimitive?.content }
            .getOrNull()
            .orEmpty()

        if (relayUrl.trim().isEmpty()) {
            throw RemodexPairingException(
                RemodexPairingErrorCode.MissingRelayUrl,
                "QR payload is missing the relay URL.",
            )
        }

        if (sessionId.trim().isEmpty()) {
            throw RemodexPairingException(
                RemodexPairingErrorCode.MissingSessionId,
                "QR payload is missing the session ID.",
            )
        }

        val decoded = RemodexPairingPayload(
            relayUrl = relayUrl,
            sessionId = sessionId,
        )

        return decoded.copy(
            relayUrl = decoded.normalizedRelayUrl,
            sessionId = decoded.normalizedSessionId,
        )
    }
}
