package app.remodex.android

import app.remodex.android.core.pairing.RemodexPairingParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class RemodexQrPayloadDecoder {
    fun decodeValidatedPayload(
        rawValue: String?,
        rawBytes: ByteArray? = null,
    ): String {
        val decodedPayload = when {
            rawBytes != null -> decodeUtf8(rawBytes)
            !rawValue.isNullOrBlank() -> rawValue.trim()
            else -> throw IllegalArgumentException(
                "Not a valid pairing code. Make sure you're scanning a QR from the Remodex CLI.",
            )
        }

        if (decodedPayload.isBlank()) {
            throw IllegalArgumentException(
                "Not a valid pairing code. Make sure you're scanning a QR from the Remodex CLI.",
            )
        }

        RemodexPairingParser.parse(decodedPayload)
        return decodedPayload
    }

    private fun decodeUtf8(rawBytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return runCatching {
            decoder.decode(ByteBuffer.wrap(rawBytes)).toString().trim()
        }.getOrElse {
            throw IllegalArgumentException("QR code contains invalid text encoding.", it)
        }
    }
}
