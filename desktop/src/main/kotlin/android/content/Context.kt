// Desktop stand-ins for the few Android basics the shared Glasswave code uses, so those files
// can stay almost identical to the phone app's. Only what the app calls is here.
package android.content

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Where Glasswave keeps its files. On Windows: %APPDATA%\Glasswave and %LOCALAPPDATA%\Glasswave Cache.
 * On a Mac: ~/Library/Application Support/Glasswave and ~/Library/Caches/Glasswave, the usual places
 * (the checks set APPDATA and LOCALAPPDATA on a Mac too, to keep their files apart from yours).
 */
object AppDirs {
    private val home = System.getProperty("user.home")
    private val mac = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")
    val data: File = (System.getenv("APPDATA")?.let { File(it, "Glasswave") }
        ?: if (mac) File(home, "Library/Application Support/Glasswave") else File(home, "Glasswave")).apply { mkdirs() }
    // Kept apart from the installed app (which lives in %LOCALAPPDATA%\Glasswave), so updates never touch it.
    val cache: File = (System.getenv("LOCALAPPDATA")?.let { File(it, "Glasswave Cache") }
        ?: if (mac) File(home, "Library/Caches/Glasswave") else File(home, "Glasswave Cache")).apply { mkdirs() }
}

open class Context {
    open val filesDir: File get() = AppDirs.data
    open val cacheDir: File get() = AppDirs.cache
    open val applicationContext: Context get() = this

    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = FilePrefs.get(name)

    companion object {
        const val MODE_PRIVATE = 0
    }
}

/** The one app-wide context on desktop. */
object AppContext : Context()

interface SharedPreferences {
    fun getString(key: String, def: String?): String?
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getFloat(key: String, def: Float): Float
    fun contains(key: String): Boolean
    val all: Map<String, *>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/** Preferences saved as one small JSON file per group, written in the background. */
private class FilePrefs(private val file: File) : SharedPreferences {
    private val map = ConcurrentHashMap<String, Any>()

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                for (k in o.keys()) map[k] = o.get(k)
            }
        }
    }

    override fun getString(key: String, def: String?) = (map[key] as? String) ?: def
    override fun getBoolean(key: String, def: Boolean) = (map[key] as? Boolean) ?: def
    override fun getInt(key: String, def: Int) = (map[key] as? Number)?.toInt() ?: def
    override fun getLong(key: String, def: Long) = (map[key] as? Number)?.toLong() ?: def
    override fun getFloat(key: String, def: Float) = (map[key] as? Number)?.toFloat() ?: def
    override fun contains(key: String) = map.containsKey(key)
    override val all: Map<String, *> get() = HashMap(map)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private var wipe = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value.toDouble() }
        override fun remove(key: String) = apply { puts[key] = null }
        override fun clear() = apply { wipe = true }
        override fun apply() {
            if (wipe) map.clear()
            for ((k, v) in puts) if (v == null) map.remove(k) else map[k] = v
            save()
        }
        override fun commit(): Boolean { apply(); return true }
    }

    private fun save() {
        val snapshot = JSONObject(HashMap(map))
        writer.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(snapshot.toString())
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    companion object {
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "prefs").apply { isDaemon = true } }
        private val open = ConcurrentHashMap<String, FilePrefs>()
        private val dir = File(AppDirs.data, "prefs").apply { mkdirs() }
        fun get(name: String): SharedPreferences = open.getOrPut(name) { FilePrefs(File(dir, "$name.json")) }
    }
}
