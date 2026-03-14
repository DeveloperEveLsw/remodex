package app.remodex.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemodexQrPayloadDecoderTests {
    private val decoder = RemodexQrPayloadDecoder()

    @Test
    fun decodeValidatedPayloadAcceptsValidPairingJson() {
        val payload = """{"relay":"http://localhost:3000/relay","sessionId":"session-1"}"""

        val decoded = decoder.decodeValidatedPayload(rawValue = payload)

        assertEquals(payload, decoded)
    }

    @Test
    fun decodeValidatedPayloadRejectsMalformedUtf8() {
        val error = runCatching {
            decoder.decodeValidatedPayload(
                rawValue = null,
                rawBytes = byteArrayOf(0xC3.toByte(), 0x28),
            )
        }.exceptionOrNull()

        assertEquals("QR code contains invalid text encoding.", error?.message)
    }

    @Test
    fun decodeValidatedPayloadRejectsBlankPayload() {
        val error = runCatching {
            decoder.decodeValidatedPayload(rawValue = "   ")
        }.exceptionOrNull()

        assertEquals(
            "Not a valid pairing code. Make sure you're scanning a QR from the Remodex CLI.",
            error?.message,
        )
    }

    @Test
    fun decodeValidatedPayloadPropagatesPairingValidationErrors() {
        val error = runCatching {
            decoder.decodeValidatedPayload(
                rawValue = """{"relay":"ws://localhost:3000/relay"}""",
            )
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("session ID") == true)
    }
}
