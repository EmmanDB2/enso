package app.enso.browser.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// Small helpers for reading and patching Zen's record JSON, and the names of its record kinds.

internal const val KIND_SPACE = "space"
internal const val KIND_TAB = "tab"
internal const val KIND_FOLDER = "folder"
internal const val KIND_SPLIT = "split"
internal const val KIND_LAYOUT = "layout"
internal const val LAYOUT_ID = "layout"

/** The key layout.essentials uses for Essentials outside any container. */
internal const val DEFAULT_CONTAINER = "default"

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.strings(key: String): List<String>? = (this[key] as? JsonArray)?.strings()

internal fun JsonElement?.strings(): List<String> =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }.orEmpty()

/** This object with [changes] put over it; a null value writes JSON null, as Zen's records keep null fields. */
internal fun JsonObject.with(vararg changes: Pair<String, Any?>): JsonObject =
    JsonObject(this + changes.associate { (key, value) -> key to json(value) })

internal fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map(::json))
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to json(v) })
    else -> error("Can't write ${value::class.simpleName} as JSON")
}
