package dev.yourstream.catalog.stremio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tolerant field access. Add-ons disagree on types ("releaseInfo": 2020 vs "2020", "genre" vs
 * "genres", ratings as strings), so items are read field by field instead of with a strict
 * schema: one odd field never drops a whole catalog page.
 */
internal fun JsonObject.str(key: String): String? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> v.content.takeIf { it.isNotBlank() && it != "null" }
    else -> null
}

internal fun JsonObject.strings(key: String): List<String> = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
    is JsonPrimitive -> v.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"

/** "2008–2013" → 2008, "2020" → 2020, 2020 → 2020. */
internal fun firstYear(text: String?): Int? = text?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toInt() }
