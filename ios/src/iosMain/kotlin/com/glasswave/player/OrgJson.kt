// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The few bits of org.json the phone app's YouTube Music code uses, so that code can be copied
 * here nearly unchanged (org.json is Java-only and can't run on iOS).
 */

private fun fromElement(e: JsonElement): Any? = when (e) {
    is JsonNull -> null
    is JsonObject -> JSONObject().also { o -> e.forEach { (k, v) -> o.map[k] = fromElement(v) } }
    is JsonArray -> JSONArray().also { a -> e.forEach { a.list += fromElement(it) } }
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull
        e.longOrNull != null -> e.longOrNull
        else -> e.doubleOrNull
    }
}

private fun toElement(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JSONObject -> JsonObject(v.map.mapValues { toElement(it.value) })
    is JSONArray -> JsonArray(v.list.map { toElement(it) })
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}

class JSONObject() {
    internal val map = LinkedHashMap<String, Any?>()

    constructor(text: String) : this() {
        val e = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Not a JSON object")
        e.forEach { (k, v) -> map[k] = fromElement(v) }
    }

    fun opt(key: String): Any? = map[key]
    fun has(key: String) = map.containsKey(key)
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun length() = map.size
    fun put(key: String, value: Any?): JSONObject { map[key] = value; return this }
    fun optJSONObject(key: String) = map[key] as? JSONObject
    fun optJSONArray(key: String) = map[key] as? JSONArray
    fun optString(key: String, fallback: String = ""): String = when (val v = map[key]) {
        null -> fallback
        is String -> v
        else -> v.toString()
    }
    fun optLong(key: String, fallback: Long = 0): Long = when (val v = map[key]) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: fallback
        else -> fallback
    }
    fun optInt(key: String, fallback: Int = 0): Int = optLong(key, fallback.toLong()).toInt()
    fun optDouble(key: String, fallback: Double = Double.NaN): Double = when (val v = map[key]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: fallback
        else -> fallback
    }
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = map[key] as? Boolean ?: fallback

    override fun toString() = toElement(this).toString()
}

class JSONArray() {
    internal val list = mutableListOf<Any?>()

    constructor(text: String) : this() {
        val e = Json.parseToJsonElement(text) as? JsonArray ?: throw IllegalArgumentException("Not a JSON array")
        e.forEach { list += fromElement(it) }
    }

    fun length() = list.size
    fun opt(i: Int): Any? = list.getOrNull(i)
    fun optJSONObject(i: Int) = opt(i) as? JSONObject
    fun optString(i: Int, fallback: String = "") = opt(i)?.toString() ?: fallback
    fun put(value: Any?): JSONArray { list += value; return this }

    override fun toString() = toElement(this).toString()
}
