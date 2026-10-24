package android.net

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A lenient web address, like Android's Uri: it never refuses odd characters, and only splits
 * the address into its parts when asked.
 */
class Uri private constructor(private val raw: String) {
    val scheme: String? get() = raw.substringBefore(':', "").takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c in "+-." } }

    private val afterScheme: String get() = scheme?.let { raw.substring(it.length + 1) } ?: raw

    val authority: String?
        get() {
            val s = afterScheme
            if (!s.startsWith("//")) return null
            return s.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
        }

    val host: String? get() = authority?.substringAfterLast('@')?.substringBefore(':')?.lowercase()

    val path: String?
        get() {
            var s = afterScheme
            if (s.startsWith("//")) s = s.substring(2).dropWhile { it != '/' && it != '?' && it != '#' }
            return s.takeWhile { it != '?' && it != '#' }.let { decode(it) }
        }

    val lastPathSegment: String? get() = path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    private val query: String? get() = raw.substringBefore('#').substringAfter('?', "").takeIf { it.isNotEmpty() }

    fun getQueryParameter(key: String): String? {
        val q = query ?: return null
        for (pair in q.split('&')) {
            val k = decode(pair.substringBefore('='))
            if (k == key) return decode(pair.substringAfter('=', ""))
        }
        return null
    }

    /** A builder starting from this address (its query kept as it is). */
    fun buildUpon(): Builder {
        val b = Builder().scheme(scheme ?: "").authority(authority ?: "").path(path ?: "")
        query?.split('&')?.filter { it.isNotEmpty() }?.forEach { p ->
            b.appendQueryParameter(decode(p.substringBefore('=')), decode(p.substringAfter('=', "")))
        }
        return b
    }

    override fun toString() = raw
    override fun equals(other: Any?) = other is Uri && other.raw == raw
    override fun hashCode() = raw.hashCode()

    class Builder {
        private var scheme = ""
        private var authority = ""
        private var path = ""
        private val params = mutableListOf<Pair<String, String>>()
        fun scheme(s: String) = apply { scheme = s }
        fun authority(a: String) = apply { authority = a }
        fun path(p: String) = apply { path = p }
        fun appendQueryParameter(k: String, v: String) = apply { params += k to v }
        fun build(): Uri {
            val q = if (params.isEmpty()) "" else "?" + params.joinToString("&") { encode(it.first) + "=" + encode(it.second) }
            return Uri("$scheme://$authority$path$q")
        }
    }

    companion object {
        fun parse(s: String) = Uri(s)
        fun fromFile(f: File) = Uri(f.toURI().toString())
        fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        private fun decode(s: String): String = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)
    }
}
