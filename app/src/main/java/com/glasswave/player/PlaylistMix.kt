// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mixes for playlists, like Spotify's: turn Mix on for a playlist and its songs blend into each
 * other when you play it. Every change from one song to the next can have its own style, length
 * and starting point; the rest follow the playlist's own choice. Kept on this device.
 */
object PlaylistMix {
    enum class Style(val label: String, val detail: String) {
        AUTO("Auto", "Like a DJ: beats lined up, tempos matched"),
        FADE("Fade", "One song smoothly fades into the next"),
        CUT("Quick cut", "Straight into the next song, a short blend"),
    }

    /** One change between songs. 0 seconds = Glasswave picks (from the songs' tempo). */
    data class Transition(val style: Style = Style.AUTO, val seconds: Int = 0, val earlySec: Int = 0) {
        val summary: String get() = buildString {
            append(style.label)
            append(if (seconds > 0) " · ${seconds}s" else " · auto length")
            if (earlySec > 0) append(" · starts ${earlySec}s early")
        }
    }

    class Mix(val on: Boolean, val all: Transition, val pairs: Map<String, Transition>, val urls: Set<String>)

    private val empty = Mix(false, Transition(), emptyMap(), emptySet())
    private var prefs: SharedPreferences? = null
    private val mixes = HashMap<String, Mix>()

    /** Goes up whenever a mix changes (the screens redraw). */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playlist_mix", Context.MODE_PRIVATE)
        prefs?.all?.forEach { (id, v) -> (v as? String)?.let { runCatching { mixes[id] = read(JSONObject(it)) } } }
    }

    fun pairKey(from: String, to: String) = "$from>$to"

    @Synchronized fun get(id: String): Mix = mixes[id] ?: empty

    @Synchronized
    fun save(id: String, mix: Mix) {
        mixes[id] = mix
        lastKey = ""
        prefs?.edit()?.putString(id, write(mix).toString())?.apply()
        version++
    }

    /** The change from [from] to [to] in playlist [id]: its own, or the playlist's. */
    fun transition(id: String, from: String, to: String): Transition = get(id).let { it.pairs[pairKey(from, to)] ?: it.all }

    private var lastKey = ""
    private var lastFound: String? = null

    /** The playlist with Mix on that this queue is playing (its songs, in any order), or null. */
    @Synchronized
    fun forQueue(urls: List<String>): String? {
        if (urls.size < 2) return null
        val key = "${urls.size}|${urls.first()}|${urls.last()}|${urls.hashCode()}"
        if (key == lastKey) return lastFound
        val set = urls.toHashSet()
        lastFound = mixes.entries.filter { it.value.on && it.value.urls.isNotEmpty() }.firstOrNull { (_, m) ->
            val shared = set.count { it in m.urls }
            shared >= 2 && shared >= set.size * 0.8 && shared >= m.urls.size * 0.8
        }?.key
        lastKey = key
        return lastFound
    }

    private fun readT(o: JSONObject?) = if (o == null) Transition() else Transition(
        runCatching { Style.valueOf(o.optString("s", "AUTO")) }.getOrDefault(Style.AUTO), o.optInt("sec"), o.optInt("early"),
    )

    private fun writeT(t: Transition) = JSONObject().put("s", t.style.name).put("sec", t.seconds).put("early", t.earlySec)

    private fun read(o: JSONObject): Mix {
        val pairs = HashMap<String, Transition>()
        o.optJSONObject("pairs")?.let { p -> p.keys().forEach { k -> pairs[k] = readT(p.optJSONObject(k)) } }
        val urls = HashSet<String>()
        o.optJSONArray("urls")?.let { a -> for (i in 0 until a.length()) urls += a.optString(i) }
        return Mix(o.optBoolean("on"), readT(o.optJSONObject("all")), pairs, urls)
    }

    private fun write(m: Mix): JSONObject {
        val pairs = JSONObject()
        m.pairs.forEach { (k, t) -> pairs.put(k, writeT(t)) }
        return JSONObject().put("on", m.on).put("all", writeT(m.all)).put("pairs", pairs).put("urls", JSONArray(m.urls.toList()))
    }
}
