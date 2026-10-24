package androidx.media3.common

/** The bit of Android's player the shared code uses: repeat modes, and pausing (for the sleep timer). */
interface Player {
    fun pause()

    companion object {
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2
    }
}
