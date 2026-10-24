// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Songs you said you're not interested in (from a song's ••• menu). They're taken off Home, and
 * Autoplay, Automix and Flow keep one only one time in five (80% less likely). The same song as a
 * music video or another upload counts too. Choosing it again on the song undoes it.
 */
object NotInterested {
    private lateinit var prefs: SharedPreferences
    private val urls = ConcurrentHashMap.newKeySet<String>()
    private val keys = ConcurrentHashMap.newKeySet<String>()
    private val random = java.util.Random()

    /** Goes up on every change, so Home redraws. */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("not_interested", Context.MODE_PRIVATE)
        runCatching { JSONArray(prefs.getString("songs", "[]")) }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> urls += o.optString("u"); keys += o.optString("k") }
        }
        urls.remove(""); keys.remove("")
    }

    private fun key(t: Track) = YtRepo.songKey(t.title, t.artist)

    fun has(t: Track): Boolean = t.url in urls || (t.title.isNotBlank() && key(t) in keys)

    fun toggle(t: Track): Boolean {
        val on = !has(t)
        if (on) { urls += t.url; if (t.title.isNotBlank()) keys += key(t) } else { urls.remove(t.url); keys.remove(key(t)) }
        save(t, on)
        version++
        return on
    }

    private fun save(t: Track, on: Boolean) {
        if (!::prefs.isInitialized) return
        val a = runCatching { JSONArray(prefs.getString("songs", "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("u") != t.url && o.optString("k") != key(t)) out.put(o)
        }
        if (on) out.put(JSONObject().put("u", t.url).put("k", key(t)).put("t", t.title).put("a", t.artist))
        prefs.edit().putString("songs", out.toString()).apply()
    }

    /** For songs picked automatically (Autoplay, Automix, Flow): each not-interested one stays only 1 time in 5. */
    fun thin(list: List<Track>): List<Track> = if (urls.isEmpty()) list else list.filter { !has(it) || random.nextInt(5) == 0 }

    /** Home without them (shelves that end up nearly empty go too). */
    fun hide(sections: List<Section>): List<Section> {
        version // read, so Home redraws when this changes
        if (urls.isEmpty()) return sections
        return sections.map { s -> s.copy(items = s.items.filterNot { it is SongItem && has(it.track) }) }
            .filter { it.items.size >= 2 }
    }
}
