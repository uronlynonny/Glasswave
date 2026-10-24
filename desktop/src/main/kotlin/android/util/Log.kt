package android.util

/** Android's log, printed to the console on desktop. */
object Log {
    fun d(tag: String, msg: String, tr: Throwable? = null) = print("D", tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = print("I", tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = print("W", tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = print("E", tag, msg, tr)

    private fun print(level: String, tag: String, msg: String, tr: Throwable?): Int {
        System.err.println("$level/$tag: $msg" + (tr?.let { " ($it)" } ?: ""))
        return 0
    }
}

/** Android's Base64, on top of Java's. */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val URL_SAFE = 8

    fun encodeToString(input: ByteArray, flags: Int): String {
        var enc = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) enc = enc.withoutPadding()
        return enc.encodeToString(input)
    }

    fun decode(str: String, flags: Int): ByteArray {
        val clean = str.replace(Regex("\\s"), "")
        return if (flags and URL_SAFE != 0 || clean.contains('-') || clean.contains('_')) java.util.Base64.getUrlDecoder().decode(clean)
        else java.util.Base64.getDecoder().decode(clean)
    }
}
