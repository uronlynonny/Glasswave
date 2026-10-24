package android.widget

/** Short notes show in the app's own note bubble on desktop. */
class Toast private constructor(private val text: CharSequence) {
    fun show() = com.glasswave.player.Menus.say(text.toString())

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        fun makeText(@Suppress("UNUSED_PARAMETER") ctx: Any?, text: CharSequence, @Suppress("UNUSED_PARAMETER") length: Int) = Toast(text)
    }
}
