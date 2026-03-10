package app.remodex.android.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

typealias JsonValue = JsonElement
typealias RpcObject = Map<String, JsonValue>

val JsonValue.stringValue: String?
    get() = (this as? JsonPrimitive)?.contentOrNull

val JsonValue.intValue: Int?
    get() = (this as? JsonPrimitive)?.intOrNull

val JsonValue.longValue: Long?
    get() = (this as? JsonPrimitive)?.longOrNull

val JsonValue.doubleValue: Double?
    get() = (this as? JsonPrimitive)?.doubleOrNull

val JsonValue.boolValue: Boolean?
    get() = (this as? JsonPrimitive)?.booleanOrNull

val JsonValue.objectValue: JsonObject?
    get() = this as? JsonObject

val JsonValue.arrayValue: JsonArray?
    get() = this as? JsonArray

val JsonValue.isNullValue: Boolean
    get() = this == JsonNull
