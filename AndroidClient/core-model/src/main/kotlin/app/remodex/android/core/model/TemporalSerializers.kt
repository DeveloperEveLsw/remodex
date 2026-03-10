package app.remodex.android.core.model

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

object NullableFlexibleInstantSerializer : KSerializer<Instant?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NullableFlexibleInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant?) {
        if (value == null) {
            encoder.encodeNull()
            return
        }

        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("NullableFlexibleInstantSerializer only supports JSON")

        return decodeInstant(jsonDecoder.decodeJsonElement())
    }

    private fun decodeInstant(value: JsonElement): Instant? {
        return when (value) {
            JsonNull -> null
            is JsonPrimitive -> when {
                value.isString -> parseString(value.content)
                else -> parseNumeric(value)
            }
            else -> throw SerializationException("Unsupported JSON value for Instant: $value")
        }
    }

    private fun parseString(rawValue: String): Instant? {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        return runCatching { Instant.parse(trimmed) }.getOrElse {
            val numeric = trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull()
            if (numeric != null) {
                parseEpoch(numeric)
            } else {
                throw SerializationException("Unsupported instant string: $rawValue", it)
            }
        }
    }

    private fun parseNumeric(value: JsonPrimitive): Instant? {
        value.longOrNull?.let { return parseEpoch(it) }
        value.doubleOrNull?.let { return parseEpoch(it) }
        throw SerializationException("Unsupported numeric instant: $value")
    }

    private fun parseEpoch(value: Number): Instant {
        val rawValue = value.toDouble()
        val secondsValue = if (rawValue > 10_000_000_000) rawValue / 1000 else rawValue
        val wholeSeconds = secondsValue.toLong()
        val nanos = ((secondsValue - wholeSeconds) * 1_000_000_000).toLong()
        return Instant.ofEpochSecond(wholeSeconds, nanos)
    }
}
