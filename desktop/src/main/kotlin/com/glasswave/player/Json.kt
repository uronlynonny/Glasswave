// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import org.json.JSONArray
import org.json.JSONObject

/* Small helpers for digging through big JSON replies without caring about their exact layout. */

/** Every object stored under [key], anywhere inside [node]. Doesn't look inside the ones it finds. */
fun collect(node: Any?, key: String, out: MutableList<JSONObject> = mutableListOf()): MutableList<JSONObject> {
    when (node) {
        is JSONObject -> {
            val keys = node.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = node.opt(k)
                if (k == key && v is JSONObject) out.add(v) else collect(v, key, out)
            }
        }
        is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
    }
    return out
}

/** The first string stored under [key], anywhere inside [node]. */
fun findString(node: Any?, key: String): String? {
    when (node) {
        is JSONObject -> {
            val direct = node.opt(key)
            if (direct is String) return direct
            val keys = node.keys()
            while (keys.hasNext()) findString(node.opt(keys.next()), key)?.let { return it }
        }
        is JSONArray -> for (i in 0 until node.length()) findString(node.opt(i), key)?.let { return it }
    }
    return null
}

/** Follows a path like "a.b.0.c". Returns null if any step is missing. */
fun JSONObject.path(path: String): Any? {
    var cur: Any? = this
    for (part in path.split('.')) {
        cur = when (cur) {
            is JSONObject -> cur.opt(part)
            is JSONArray -> part.toIntOrNull()?.let { cur.opt(it) }
            else -> null
        } ?: return null
    }
    return cur
}

/** Joins YouTube's "runs" text pieces, or reads "simpleText". */
fun ytText(node: Any?): String {
    if (node !is JSONObject) return ""
    node.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
    val runs = node.optJSONArray("runs") ?: return ""
    return buildString { for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text") ?: "") }
}

/** The last (largest) thumbnail url inside [node]. */
fun lastThumb(node: Any?): String? {
    val lists = mutableListOf<JSONArray>()
    fun walk(n: Any?) {
        when (n) {
            is JSONObject -> {
                val t = n.opt("thumbnails")
                if (t is JSONArray && t.length() > 0) { lists.add(t); return }
                val keys = n.keys()
                while (keys.hasNext()) walk(n.opt(keys.next()))
            }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
        }
    }
    walk(node)
    val arr = lists.firstOrNull() ?: return null
    return arr.optJSONObject(arr.length() - 1)?.optString("url")?.ifEmpty { null }
}
